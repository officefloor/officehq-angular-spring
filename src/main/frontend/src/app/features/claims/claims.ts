import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { PeriodStore, periodOf } from '../periods/periods';
import { NO_RATE, Rate, dueCents, rateOn } from '../rates/rates';

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; a DRAFT or OPEN claim can be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** A status change a claim's buttons ask for. */
type ClaimAction = 'open' | 'settle' | 'void';

export interface Claim {
  id: number;
  personId: number;
  /** Two decimals, e.g. "10.00". */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number on the sheet, given when it is opened; null for a draft or a void claim. */
  number: number | null;
  /** The due fixed when the claim was settled (two decimals); null until then, or when its grade
   *  had no rate to work from. */
  settledDue: string | null;
  /** The user who opened the claim; null for a claim never opened. */
  openedBy: string | null;
  /** Whether the person's last statement bound the claim to the figure it had then. */
  stated: boolean;
  /** The figure the statement bound the claim to (two decimals); null when unbound or it had none. */
  statedDue: string | null;
}

/** Whether a claim counts toward what is claimed and due: only OPEN and SETTLED ones do. */
export function counts(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'SETTLED';
}

/** An amount in cents, from the two-decimal string the server sends. */
export function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** A claim's due in cents: the due fixed when it was settled; otherwise the figure a statement
 *  bound it to; otherwise its amount times the rate in force on its date among its grade's rates;
 *  null when it has no figure. */
export function claimDueCents(claim: Claim, rates: Rate[]): number | null {
  if (claim.settledDue !== null) return cents(claim.settledDue);
  if (claim.stated) return claim.statedDue === null ? null : cents(claim.statedDue);
  const rate = rateOn(rates, claim.date);
  return rate === null ? null : dueCents(cents(claim.amount), rate);
}

/** Cents shown with two decimals. */
export function money(total: number): string {
  return (total / 100).toFixed(2);
}

const PAST: Record<ClaimAction, string> = { open: 'opened', settle: 'settled', void: 'voided' };

const AMOUNT = /^\d+(\.\d{1,2})?$/;

// A person's claims, newest first, and the one form that adds a claim or, once a claim's editor is
// opened, saves its changes.
@Component({
  selector: 'app-person-claims',
  template: `
    <form (submit)="submit($event)">
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
    </form>
    @if (error(); as message) {
      <p role="alert" data-testid="claim-form-error">{{ message }}</p>
    }
    <ul>
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span data-testid="claim-due">{{ dueOf(c) }}</span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span data-testid="claim-number">{{ c.number ?? '' }}</span>
          <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span>
          <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span>
          <span data-testid="claim-period">{{ periodNameOf(c) }}</span>
          <button type="button" data-testid="claim-open" [attr.aria-label]="'Open claim ' + c.id" (click)="act(c, 'open')">
            Open
          </button>
          <button type="button" data-testid="claim-settle" [attr.aria-label]="'Settle claim ' + c.id" (click)="act(c, 'settle')">
            Settle
          </button>
          <button type="button" data-testid="claim-void" [attr.aria-label]="'Void claim ' + c.id" (click)="act(c, 'void')">
            Void
          </button>
          <button type="button" data-testid="claim-edit" [attr.aria-label]="'Edit claim ' + c.id" (click)="edit(c)">
            Edit
          </button>
          @if (editingId() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
          }
          @if (rowError(); as e) {
            @if (e.id === c.id) {
              <p role="alert" data-testid="claim-error">{{ e.message }}</p>
            }
          }
        </li>
      }
    </ul>
  `,
})
export class PersonClaims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  private readonly periods = inject(PeriodStore);

  readonly personId = input.required<number>();
  readonly claims = input.required<Claim[]>();
  /** The rates for the person's grade; none when the grade has no rate. */
  readonly rates = input.required<Rate[]>();
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);
  /** The error from the last status change refused, and the claim it belongs to. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** The claim's due (see claimDueCents), or n/a when it has no figure. */
  protected dueOf(claim: Claim): string {
    const due = claimDueCents(claim, this.rates());
    return due === null ? NO_RATE : money(due);
  }

  /** The name of the period the claim's date falls in; empty when it falls in none. */
  protected periodNameOf(claim: Claim): string {
    return periodOf(this.periods.periods(), claim.date)?.name ?? '';
  }

  protected edit(claim: Claim): void {
    this.rowError.set(null);
    this.editingId.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.error.set(null);
  }

  /** Open, settle or void a claim; a change its status does not allow, or the user who opened it
   *  settling it, is refused on the row. */
  protected async act(claim: Claim, action: ClaimAction): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url =
      `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/${action}`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, null));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(saved);
    } catch (e) {
      const own = action === 'settle' && e instanceof HttpErrorResponse && e.status === 403;
      this.rowError.set({
        id: claim.id,
        message: own
          ? 'The claim could not be settled: whoever opened it cannot settle it.'
          : `The claim could not be ${PAST[action]}.`,
      });
    }
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const body = { amount, date: this.date().trim() || null, note: this.note().trim() };
    const id = this.editingId();
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims`;
    this.busy.set(true);
    try {
      const saved = await firstValueFrom(
        id === null ? this.http.post<Claim>(base, body) : this.http.put<Claim>(`${base}/${id}`, body),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(saved);
      this.reset();
    } catch {
      this.error.set(id === null ? 'The claim could not be added.' : 'The claim could not be saved.');
    } finally {
      this.busy.set(false);
    }
  }
}
