import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Period, inClosedPeriod, periodOn } from '../periods/periods';
import { DatedGrade, Lateness, Rate, chargedDueCents, dueText, gradeOn } from '../rates/rates';

// A person's claims, newest first, and the one form under their row that both adds a claim and
// edits an existing one (opening a claim's editor fills the form with its values). Each claim
// shows its status and the buttons that move it on; a move its status does not allow is refused
// with an error on the claim, as is the user who opened a claim settling it (four eyes). A claim
// dated inside a closed period cannot be added, edited or moved on: each is refused with an error.
// Only a DRAFT can be edited; editing any other claim is refused with an error on the claim (to
// change an open claim, void it and add a new one).
// Each claim shows its due-by, its date plus the sheet's terms in days, and whether it is overdue:
// OPEN with its due-by before the sheet's today. An overdue claim's due gains the sheet's late
// percent of its figure for every full 30 days it is overdue.
// A claim with a negative amount is a correction: it must name, by number, a SETTLED claim of the
// same person that it corrects, else it is refused. Its due has no floor and no late charge, and
// it shows the number it corrects.
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
  /** The number of the claim this correction corrects; null for a claim that is not a correction. */
  corrects: number | null;
}

/** The date a claim's live figures round by: its own, or for a correction the date of the claim it
 *  corrects, found by number among `claims` (its own when that claim is not among them). */
export function roundingDate(c: Claim, claims: readonly Claim[]): string {
  if (!isCorrection(c) || c.corrects === null) return c.date;
  return claims.find((o) => o.number === c.corrects)?.date ?? c.date;
}

/** Whether a claim is a correction: its amount is below zero. */
export function isCorrection(c: Claim): boolean {
  return cents(c.amount) < 0;
}

/** A move from one status to the next, by the endpoint that makes it. */
type ClaimAction = 'open' | 'settle' | 'void';

/** Whether a claim counts toward claimed, due-total and every total: only OPEN and SETTLED do. */
export function counts(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'SETTLED';
}

/** The refusal of a change to a claim dated inside a closed period. */
const CLOSED = 'That date is inside a closed period.';

/** The refusal of a correction that names no SETTLED claim of its person. */
const UNCORRECTABLE = 'A negative amount must correct a SETTLED claim of this person, by its number.';

const PAST: Record<ClaimAction, string> = { open: 'opened', settle: 'settled', void: 'voided' };

/** An amount: 0.00 or more, with up to two decimals. */
export const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** A claim's amount: as {@link AMOUNT}, or below zero for a correction. */
const SIGNED_AMOUNT = /^-?\d{1,12}(\.\d{1,2})?$/;

/** A claim number: a whole number from 1. */
const NUMBER = /^[1-9]\d{0,8}$/;

/** An amount in whole cents, so sums are exact; below zero for a negative amount. */
export function cents(amount: string): number {
  const negative = amount.startsWith('-');
  const [whole, frac = ''] = (negative ? amount.slice(1) : amount).split('.');
  const c = Number(whole) * 100 + Number(frac.padEnd(2, '0'));
  return negative ? -c : c;
}

/** Days since 1970-01-01 of a yyyy-mm-dd date (proleptic Gregorian). */
function dayNumber(date: string): number {
  const [y0, m, d] = date.split('-').map(Number);
  const y = m <= 2 ? y0 - 1 : y0;
  const era = Math.floor(y / 400);
  const yoe = y - era * 400;
  const doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

/** The yyyy-mm-dd date of a number of days since 1970-01-01. */
function dateOf(days: number): string {
  const z = days + 719468;
  const era = Math.floor(z / 146097);
  const doe = z - era * 146097;
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365);
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  const mp = Math.floor((5 * doy + 2) / 153);
  const d = doy - Math.floor((153 * mp + 2) / 5) + 1;
  const m = mp < 10 ? mp + 3 : mp - 9;
  const y = yoe + era * 400 + (m <= 2 ? 1 : 0);
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

/** The date a claim is due by: its date plus the terms in days. */
export function dueBy(c: Claim, terms: number): string {
  return dateOf(dayNumber(c.date) + terms);
}

/** Whether a claim is overdue: OPEN, and due by a date before the sheet's today. */
export function overdue(c: Claim, terms: number, today: string | null): boolean {
  return c.status === 'OPEN' && today !== null && dueBy(c, terms) < today;
}

/** The days a claim is overdue on the sheet's today: past its due-by; 0 when it is not overdue. */
export function daysOverdue(c: Claim, terms: number, today: string | null): number {
  return overdue(c, terms, today) && today !== null ? dayNumber(today) - dayNumber(c.date) - terms : 0;
}

/** Whole cents shown with two decimals, with a minus sign below zero. */
export function money(c: number): string {
  const a = Math.abs(c);
  return `${c < 0 ? '-' : ''}${Math.floor(a / 100)}.${String(a % 100).padStart(2, '0')}`;
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
      <label [for]="'claim-corrects-' + personId()">Corrects claim no.</label>
      <input
        [id]="'claim-corrects-' + personId()"
        data-testid="claim-form-corrects"
        inputmode="numeric"
        [value]="correctsInput()"
        (input)="correctsInput.set($any($event.target).value); error.set(null)"
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
          <input
            type="checkbox"
            data-testid="claim-select"
            [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date"
            [checked]="selected().has(c.id)"
            (change)="selectedChange.emit({ id: c.id, on: $any($event.target).checked })"
          />
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periodOf(c) }}</span></span>
          <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
          <span>Due by <span data-testid="claim-due-by">{{ dueByOf(c) }}</span></span>
          <span>Overdue <span data-testid="claim-overdue">{{ overdueOf(c) ? 'yes' : 'no' }}</span></span>
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
  /** The person's grades: the one in force on a claim's date, at its rate then, gives what the claim has due. */
  readonly grades = input.required<DatedGrade[]>();
  /** The sheet's rates. */
  readonly rates = input.required<Rate[]>();
  /** The sheet's floor in whole cents: the least a claim's live due can be. */
  readonly floor = input.required<number>();
  /** The sheet's terms: the days after its date a claim is due by. */
  readonly terms = input.required<number>();
  /** The sheet's late percent: what an overdue claim gains of its figure for every full 30 days. */
  readonly latePct = input.required<string>();
  /** The sheet's policy date (blank: its today): a claim dated before it rounds half up, else half to even. */
  readonly policyDate = input.required<string>();
  /** The sheet's periods. */
  readonly periods = input.required<Period[]>();
  /** The ids of the claims selected for a bulk action, across the sheet. */
  readonly selected = input.required<ReadonlySet<number>>();
  readonly saved = output<Claim>();
  /** A claim's checkbox ticked (on) or cleared. */
  readonly selectedChange = output<{ id: number; on: boolean }>();

  protected readonly amountInput = signal('');
  protected readonly dateInput = signal('');
  protected readonly noteInput = signal('');
  protected readonly correctsInput = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  /** The error of each claim's last refused status change, by claim id. */
  protected readonly actionErrors = signal<ReadonlyMap<number, string>>(new Map());

  /** A stated claim shows the figure bound by the last statement, a settled claim the due fixed
   *  when it was settled; any other claim's due is live, at the rate for the person's grade in
   *  force on the claim's date (the grade in force then), never below the sheet's floor. An overdue claim's late charge is
   *  added on top. */
  protected dueOf(c: Claim): string {
    const late: Lateness = {
      latePct: this.latePct(),
      terms: this.terms(),
      today: this.session.today(),
      policyDate: this.policyDate(),
    };
    const roundOn = roundingDate(c, this.claims());
    return dueText(chargedDueCents(c, this.rates(), gradeOn(this.grades(), c.date), this.floor(), late, roundOn));
  }

  protected dueByOf(c: Claim): string {
    return dueBy(c, this.terms());
  }

  protected overdueOf(c: Claim): boolean {
    return overdue(c, this.terms(), this.session.today());
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
    this.correctsInput.set(c.corrects === null ? '' : String(c.corrects));
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amountInput.set('');
    this.dateInput.set('');
    this.noteInput.set('');
    this.correctsInput.set('');
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
          ? c.openedBy === this.session.user()
            ? 'The user who opened a claim cannot settle it.'
            : 'A clerk cannot settle a claim whose due is above the clerk limit.'
          : e instanceof HttpErrorResponse && e.status === 423
            ? CLOSED
            : `A ${c.status} claim cannot be ${PAST[action]}.`;
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  /** Whether a claim number names a SETTLED claim of this person, as a correction must. */
  private correctable(corrects: string): boolean {
    return NUMBER.test(corrects) && this.claims().some((c) => c.number === Number(corrects) && c.status === 'SETTLED');
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const amount = this.amountInput().trim();
    const date = this.dateInput().trim();
    const corrects = this.correctsInput().trim();
    if (!SIGNED_AMOUNT.test(amount)) {
      this.error.set('Enter an amount with up to two decimals (below zero only for a correction).');
      return;
    }
    const correction = cents(amount) < 0;
    if (correction && !this.correctable(corrects)) {
      this.error.set(UNCORRECTABLE);
      return;
    }
    if (date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date, or leave it blank for the sheet\'s today.');
      return;
    }
    const id = this.editingId();
    const body = { sheet, user: this.session.user(), amount, date: date || null, note: this.noteInput().trim(),
      corrects: correction ? Number(corrects) : null };
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
          : e instanceof HttpErrorResponse && e.status === 400 && correction
            ? UNCORRECTABLE
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
