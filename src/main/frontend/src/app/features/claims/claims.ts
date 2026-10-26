import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { PeriodStore, inClosedPeriod, periodOn } from '../periods/periods';
import { DatedGrade, RateStore, RateView, atLeast, dueOn, gradeOn, rateOn, withPctPer } from '../rates/rates';
import { SettingsStore } from '../settings/settings';

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
 * at the rate in force on its date and at least the floor; null (n/a) with none.
 */
export function figureOf(c: Claim, rates: readonly RateView[], floor: string): string | null {
  if (c.settledDue !== null) return c.settledDue;
  if (c.stated) return c.statedDue;
  const due = dueOn(c.amount, rateOn(rates, c.date));
  return due === null ? null : atLeast(due, floor);
}

/** Whether the claim keeps a figure whatever the rates: a settled or stated one with a figure. */
export function keepsFigure(c: Claim): boolean {
  return c.settledDue !== null || (c.stated && c.statedDue !== null);
}

/** The date (yyyy-mm-dd) the given number of days after the date. */
export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** The date a claim is due by: its date plus the sheet's terms. */
export function dueByOf(c: Claim, terms: number): string {
  return addDays(c.date, terms);
}

/** Whether a claim is overdue: open, and due by a date before the sheet's today. */
export function overdue(c: Claim, terms: number, today: string | null): boolean {
  return c.status === 'OPEN' && today !== null && dueByOf(c, terms) < today;
}

/** What the late charge follows: the sheet's terms, its today and its late percent. */
export interface Lateness {
  terms: number;
  today: string | null;
  latePct: string;
}

/** The whole days from one date (yyyy-mm-dd) to another. */
function daysBetween(from: string, to: string): number {
  const utc = (d: string) => {
    const [y, m, day] = d.split('-').map(Number);
    return Date.UTC(y, m - 1, day);
  };
  return Math.round((utc(to) - utc(from)) / 86_400_000);
}

/**
 * The due a claim shows and totals: its figure, and for an overdue claim the late percent of that figure for
 * every full 30 days it is overdue, added on top. The charge is never bound by a statement; a settled claim's
 * due already holds it.
 */
export function dueOf(c: Claim, rates: readonly RateView[], floor: string, late: Lateness): string | null {
  const figure = figureOf(c, rates, floor);
  if (figure === null || !overdue(c, late.terms, late.today)) return figure;
  const periods = Math.floor(daysBetween(dueByOf(c, late.terms), late.today!) / 30);
  return withPctPer(figure, late.latePct, periods);
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
          <th scope="col">No.</th><th scope="col">Amount</th><th scope="col">Date</th><th scope="col">Note</th><th scope="col">Due</th><th scope="col">Due by</th><th scope="col">Overdue</th><th scope="col">Status</th><th scope="col">Opened by</th><th scope="col">Stated</th><th scope="col">Period</th>
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
            <td data-testid="claim-due">{{ dueOf(c, ratesOn(c.date), settings.floor(), settings.lateness()) ?? 'n/a' }}</td>
            <td data-testid="claim-due-by">{{ dueByOf(c, settings.terms()) }}</td>
            <td data-testid="claim-overdue">{{ overdue(c, settings.terms(), session.sheetToday()) ? 'yes' : 'no' }}</td>
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
  protected readonly session = inject(Session);
  private readonly periods = inject(PeriodStore);
  protected readonly settings = inject(SettingsStore);
  private readonly rateStore = inject(RateStore);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly claims = input.required<Claim[]>();
  // Every grade recorded for the person; a claim's due uses the rate in force on its date for the grade in force then.
  readonly grades = input<readonly DatedGrade[]>([]);
  // The date the person is retired from; no claim dated after it can be added or saved. Null while not retired.
  readonly retiredFrom = input<string | null>(null);
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

  protected readonly dueOf = dueOf;

  /** Every rate set for the person's grade in force on the date. */
  protected ratesOn(date: string): readonly RateView[] {
    return this.rateStore.rates().get(gradeOn(this.grades(), date)) ?? [];
  }
  protected readonly dueByOf = dueByOf;
  protected readonly overdue = overdue;

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
    } catch (e) {
      const err =
        action === 'settle' && c.openedBy !== null && c.openedBy === this.session.user()
          ? 'You opened this claim, so another user must settle it.'
          : action === 'settle' && e instanceof HttpErrorResponse && e.status === 403
            ? 'Its due is above the clerk limit, so only a steward can settle it.'
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
    const retiredFrom = this.retiredFrom();
    const on = date || (this.session.sheetToday() ?? '');
    if (retiredFrom !== null && on > retiredFrom) {
      this.error.set(`${this.personName()} is retired from ${retiredFrom}, so a claim cannot be dated after it.`);
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
