import { HttpClient } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { PeriodStore, inClosedPeriod, periodOn } from '../periods/periods';
import { RateView, dueOn, rateOn } from '../rates/rates';

export interface Claim {
  id: number;
  personId: number;
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  // Given when the claim is opened; null for a draft or voided claim.
  number: number | null;
  // The due fixed when the claim was settled; null while its due is live.
  settledDue: string | null;
  // The user who opened the claim; null for a claim never opened. That user cannot settle it.
  openedBy: string | null;
  // Whether the person's last statement binds the claim, and the figure it binds it to (null for n/a).
  stated: boolean;
  statedDue: string | null;
}

export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

type ClaimAction = 'open' | 'settle' | 'void';

/** Only open and settled claims count toward what is claimed and due. */
export function counts(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'SETTLED';
}

/**
 * The figure a claim shows and totals: its settled due, else the one a statement bound it to, else its live due
 * at the rate in force on its date; null (n/a) with none.
 */
export function figureOf(c: Claim, rates: readonly RateView[]): string | null {
  if (c.settledDue !== null) return c.settledDue;
  return c.stated ? c.statedDue : dueOn(c.amount, rateOn(rates, c.date));
}

/** Whether the claim keeps a figure whatever the rates: a settled or stated one with a figure. */
export function keepsFigure(c: Claim): boolean {
  return c.settledDue !== null || (c.stated && c.statedDue !== null);
}

const CLOSED = 'That claim is dated in a closed period, so it cannot change.';

export const AMOUNT = /^\d+(\.\d{1,2})?$/;

/** The sum of the counted claims' amounts, with two decimals; added in whole cents so nothing drifts. */
export function claimedTotal(claims: readonly Claim[]): string {
  const cents = claims.filter(counts).reduce((sum, c) => sum + Math.round(Number(c.amount) * 100), 0);
  return (cents / 100).toFixed(2);
}

// One person's claims, newest first: one form adds a claim or, with a claim's editor open, saves it.
@Component({
  selector: 'app-claims',
  template: `
    <form (submit)="submit($event)" [attr.aria-label]="(editingId() === null ? 'Add claim for ' : 'Edit claim for ') + personName()">
      <label [for]="'claim-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-amount-' + personId()"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="'claim-date-' + personId()">Date</label>
      <input
        [id]="'claim-date-' + personId()"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set(value($event)); error.set(null)"
      />
      <label [for]="'claim-note-' + personId()">Note</label>
      <input
        [id]="'claim-note-' + personId()"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>

    <table>
      <caption class="visually-hidden">Claims of {{ personName() }}</caption>
      <thead>
        <tr>
          <th scope="col">No.</th><th scope="col">Amount</th><th scope="col">Date</th><th scope="col">Note</th><th scope="col">Due</th><th scope="col">Status</th><th scope="col">Opened by</th><th scope="col">Stated</th><th scope="col">Period</th>
          <th scope="col"><span class="visually-hidden">Actions</span></th>
        </tr>
      </thead>
      <tbody>
        @for (c of claims(); track c.id) {
          <tr [attr.data-testid]="'claim-row-' + c.id">
            <td data-testid="claim-number">{{ c.number ?? '' }}</td>
            <td data-testid="claim-amount">{{ c.amount }}</td>
            <td data-testid="claim-date">{{ c.date }}</td>
            <td data-testid="claim-note">{{ c.note }}</td>
            <td data-testid="claim-due">{{ figureOf(c, rates()) ?? 'n/a' }}</td>
            <td data-testid="claim-status">{{ c.status }}</td>
            <td data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</td>
            <td data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</td>
            <td data-testid="claim-period">{{ periodName(c.date) }}</td>
            <td>
              <button type="button" data-testid="claim-open" (click)="act(c, 'open')">
                Open<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')">
                Settle<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-void" (click)="act(c, 'void')">
                Void<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-edit" (click)="edit(c)">
                Edit<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              @if (editingId() === c.id) {
                <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
              }
              @if (rowErrors()[c.id]; as err) {
                <p role="alert" data-testid="claim-error">{{ err }}</p>
              }
            </td>
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  private readonly periods = inject(PeriodStore);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly claims = input.required<Claim[]>();
  // Every rate set for the person's grade; a claim's due uses the one in force on its date.
  readonly rates = input<readonly RateView[]>([]);
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  // A submit in flight: a second submit before it answers is ignored, so it stores one claim.
  private submitting = false;
  // Each claim's refused status change, by claim id.
  protected readonly rowErrors = signal<Record<number, string>>({});

  protected readonly figureOf = figureOf;

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  /** The name of the period the date falls in; empty when none does. */
  protected periodName(date: string): string {
    return periodOn(this.periods.list(), date)?.name ?? '';
  }

  protected edit(c: Claim): void {
    this.clearRowError(c.id);
    if (c.status !== 'DRAFT') {
      // an open claim is changed by voiding it and adding a new one
      this.setRowError(c.id, `A ${c.status} claim cannot be edited; only a draft can. Void it and add a new one.`);
      return;
    }
    this.editingId.set(c.id);
    this.amount.set(c.amount);
    this.date.set(c.date);
    this.note.set(c.note);
    this.error.set(null);
  }

  protected async act(c: Claim, action: ClaimAction): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/${action}`;
    if (inClosedPeriod(this.periods.list(), c.date)) {
      this.setRowError(c.id, CLOSED);
      return;
    }
    try {
      this.saved.emit(await firstValueFrom(this.http.post<Claim>(url, null)));
    } catch {
      const err =
        action === 'settle' && c.openedBy !== null && c.openedBy === this.session.user()
          ? 'You opened this claim, so another user must settle it.'
          : `A ${c.status} claim cannot be changed that way, or its period is closed.`;
      this.setRowError(c.id, err);
    }
  }

  private setRowError(id: number, err: string): void {
    this.rowErrors.update((e) => ({ ...e, [id]: err }));
  }

  private clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.error.set(null);
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const date = this.date().trim();
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date as yyyy-mm-dd.');
      return;
    }
    const editing = this.editingId();
    const was = this.claims().find((c) => c.id === editing);
    const periods = this.periods.list();
    if ((was && inClosedPeriod(periods, was.date)) || inClosedPeriod(periods, date || (this.session.sheetToday() ?? ''))) {
      this.error.set(CLOSED);
      return;
    }
    const form = { amount, date: date || null, note: this.note().trim() };
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    this.submitting = true;
    try {
      const claim =
        editing === null
          ? await firstValueFrom(this.http.post<Claim>(`${base}/people/${this.personId()}/claims`, form))
          : await firstValueFrom(this.http.put<Claim>(`${base}/claims/${editing}`, form));
      this.saved.emit(claim);
      this.reset();
    } catch {
      this.error.set(editing === null ? 'Could not add that claim.' : 'Could not save that claim.');
    } finally {
      this.submitting = false;
    }
  }
}
