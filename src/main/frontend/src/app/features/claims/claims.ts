import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Period, inClosedPeriod, periodOn } from '../periods/periods';
import { Rate, claimDueCents, dueText } from '../rates/rates';

// A person's claims, newest first, and the one form under their row that both adds a claim and
// edits an existing one (opening a claim's editor fills the form with its values). Each claim
// shows its status and the buttons that move it on; a move its status does not allow is refused
// with an error on the claim, as is the user who opened a claim settling it (four eyes). A claim
// dated inside a closed period cannot be added, edited or moved on: each is refused with an error.
// Only a DRAFT can be edited; editing any other claim is refused with an error on the claim (to
// change an open claim, void it and add a new one).
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

export interface Claim {
  id: number;
  personId: number;
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number on the sheet, given when opened; null while DRAFT or once VOID. */
  number: number | null;
  /** The due fixed when the claim was settled; null while not settled or if its grade then had no rate. */
  settledDue: string | null;
  /** The user who opened the claim; null while DRAFT, or if voided without being opened. */
  openedBy: string | null;
  /** Whether the person's last statement bound the claim to the figure it had then. */
  stated: boolean;
  /** The figure bound by the last statement; null while unbound or if it had no rate then. */
  statedDue: string | null;
}

/** A move from one status to the next, by the endpoint that makes it. */
type ClaimAction = 'open' | 'settle' | 'void';

/** Whether a claim counts toward claimed, due-total and every total: only OPEN and SETTLED do. */
export function counts(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'SETTLED';
}

/** The refusal of a change to a claim dated inside a closed period. */
const CLOSED = 'That date is inside a closed period.';

const PAST: Record<ClaimAction, string> = { open: 'opened', settle: 'settled', void: 'voided' };

/** An amount: 0.00 or more, with up to two decimals. */
export const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** An amount in whole cents, so sums are exact. */
export function cents(amount: string): number {
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number(frac.padEnd(2, '0'));
}

/** Whole cents shown with two decimals. */
export function money(c: number): string {
  return `${Math.floor(c / 100)}.${String(c % 100).padStart(2, '0')}`;
}

@Component({
  selector: 'app-person-claims',
  template: `
    <form [attr.aria-label]="'Claim for ' + personName()" (submit)="save($event)">
      <label [for]="'claim-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-amount-' + personId()"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amountInput()"
        (input)="amountInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-date-' + personId()">Date</label>
      <input
        [id]="'claim-date-' + personId()"
        type="date"
        data-testid="claim-form-date"
        [value]="dateInput()"
        (input)="dateInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-note-' + personId()">Note</label>
      <input
        [id]="'claim-note-' + personId()"
        data-testid="claim-form-note"
        [value]="noteInput()"
        (input)="noteInput.set($any($event.target).value); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>
    <ul [attr.aria-label]="'Claims of ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Period <span data-testid="claim-period">{{ periodOf(c) }}</span></span>
          <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <button type="button" data-testid="claim-open" (click)="act(c, 'open')">Open</button>
          <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')">Settle</button>
          <button type="button" data-testid="claim-void" (click)="act(c, 'void')">Void</button>
          <button
            type="button"
            data-testid="claim-edit"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
            (click)="edit(c)"
          >
            Edit
          </button>
          @if (editingId() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
          }
          @if (actionErrors().get(c.id); as err) {
            <p role="alert" data-testid="claim-error">{{ err }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class PersonClaims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly claims = input.required<Claim[]>();
  /** The person's grade, whose rate in force on a claim's date gives what the claim has due. */
  readonly grade = input.required<string>();
  /** The sheet's rates. */
  readonly rates = input.required<Rate[]>();
  /** The sheet's periods. */
  readonly periods = input.required<Period[]>();
  readonly saved = output<Claim>();

  protected readonly amountInput = signal('');
  protected readonly dateInput = signal('');
  protected readonly noteInput = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  /** The error of each claim's last refused status change, by claim id. */
  protected readonly actionErrors = signal<ReadonlyMap<number, string>>(new Map());

  /** A stated claim shows the figure bound by the last statement, a settled claim the due fixed
   *  when it was settled; any other claim's due is live, at the rate for the person's grade in
   *  force on the claim's date. */
  protected dueOf(c: Claim): string {
    return dueText(claimDueCents(c, this.rates(), this.grade()));
  }

  /** The name of the period whose dates contain the claim's date; blank when none does. */
  protected periodOf(c: Claim): string {
    return periodOn(this.periods(), c.date)?.name ?? '';
  }

  protected edit(c: Claim): void {
    this.clearActionError(c.id);
    if (inClosedPeriod(this.periods(), c.date)) {
      this.actionErrors.update((m) => new Map(m).set(c.id, CLOSED));
      return;
    }
    if (c.status !== 'DRAFT') {
      this.actionErrors.update((m) => new Map(m).set(c.id, `A ${c.status} claim cannot be edited.`));
      return;
    }
    this.editingId.set(c.id);
    this.amountInput.set(c.amount);
    this.dateInput.set(c.date);
    this.noteInput.set(c.note);
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amountInput.set('');
    this.dateInput.set('');
    this.noteInput.set('');
    this.error.set(null);
  }

  private clearActionError(id: number): void {
    this.actionErrors.update((m) => {
      if (!m.has(id)) return m;
      const next = new Map(m);
      next.delete(id);
      return next;
    });
  }

  /** Move a claim to its next status; the server refuses a move its status does not allow. */
  protected async act(c: Claim, action: ClaimAction): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(`/api/claims/${action}`, { sheet, user: this.session.user(), id: c.id }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const err =
        e instanceof HttpErrorResponse && e.status === 403
          ? 'The user who opened a claim cannot settle it.'
          : e instanceof HttpErrorResponse && e.status === 423
            ? CLOSED
            : `A ${c.status} claim cannot be ${PAST[action]}.`;
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const amount = this.amountInput().trim();
    const date = this.dateInput().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    if (date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date, or leave it blank for the sheet\'s today.');
      return;
    }
    const id = this.editingId();
    const body = { sheet, user: this.session.user(), amount, date: date || null, note: this.noteInput().trim() };
    this.submitting = true;
    try {
      const claim =
        id === null
          ? await firstValueFrom(this.http.post<Claim>('/api/claims/add', { ...body, personId: this.personId() }))
          : await firstValueFrom(this.http.post<Claim>('/api/claims/update', { ...body, id }));
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(claim);
      this.reset();
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? CLOSED
          : e instanceof HttpErrorResponse && e.status === 422
            ? 'A claim cannot be dated after its person\'s retirement date.'
          : e instanceof HttpErrorResponse && e.status === 409
            ? 'Only a DRAFT claim can be edited.'
            : id === null
              ? 'Could not add this claim.'
              : 'Could not save this claim.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
