import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { PeriodStore, periodOf } from '../periods/periods';
import { NO_RATE, PersonGrade, Rate, RateStore, dueCents, rateOn } from '../rates/rates';

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
  /** The claim's number on the sheet, given when it is opened and kept when it is voided; null for a
   *  claim never opened. */
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
  /** The number of the claim a correction (a negative amount) corrects; null when it is not one. */
  corrects: number | null;
  /** The date the claim's live figures round by (yyyy-mm-dd): its own, or for a correction the date
   *  of the claim it corrects. */
  roundsBy: string;
}

/** Whether a claim is a correction: its amount is negative. */
export function isCorrection(claim: Claim): boolean {
  return cents(claim.amount) < 0;
}

/** Whether a claim counts toward what is claimed and due: only OPEN and SETTLED ones do. */
export function counts(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'SETTLED';
}

/** An amount in cents, from the two-decimal string the server sends. */
export function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** What a late charge works from: the sheet's late percent (two decimals), its terms in days and
 *  its today; and what a claim's figures round by: the sheet's policy date (blank for its today). */
export interface Late {
  pct: string;
  terms: number;
  today: string | null;
  policyDate: string;
}

/** Whether a claim's live figures round half up: it rounds by a date before the sheet's policy date
 *  (its today when the policy date is blank); otherwise they round half to even. */
function roundsHalfUp(claim: Claim, late: Late): boolean {
  const policy = late.policyDate !== '' ? late.policyDate : late.today;
  return policy !== null && claim.roundsBy < policy;
}

/** A claim's figure in cents: the due fixed when it was settled; otherwise the figure a statement
 *  bound it to; otherwise its amount times the rate in force on its date among its grade's rates,
 *  raised to the sheet's floor (in cents) unless it is a correction; null when it has no figure. */
function figureCents(claim: Claim, rates: Rate[], floor: number, halfUp: boolean): number | null {
  if (claim.settledDue !== null) return cents(claim.settledDue);
  if (claim.stated) return claim.statedDue === null ? null : cents(claim.statedDue);
  const rate = rateOn(rates, claim.date);
  if (rate === null) return null;
  const due = dueCents(cents(claim.amount), rate, halfUp);
  return isCorrection(claim) ? due : Math.max(due, floor);
}

/** A claim's due in cents: its figure plus its late charge, the late percent of the figure for
 *  every full 30 days an OPEN claim is overdue, rounded like a due (a statement never binds the
 *  charge; settling freezes it into the settled due; a correction has none); null when it has no
 *  figure. */
export function claimDueCents(claim: Claim, rates: Rate[], floor: number, late: Late): number | null {
  const halfUp = roundsHalfUp(claim, late);
  const figure = figureCents(claim, rates, floor, halfUp);
  if (figure === null) return null;
  const periods = isCorrection(claim) ? 0 : Math.floor(daysOverdue(claim, late) / 30);
  return periods > 0 ? figure + dueCents(figure * periods, late.pct, halfUp) : figure;
}

/** How many days an OPEN claim is past its due-by on the sheet's today; 0 when it is not overdue. */
function daysOverdue(claim: Claim, late: Late): number {
  if (!overdue(claim, late.terms, late.today) || late.today === null) return 0;
  return Math.round((Date.parse(late.today) - Date.parse(dueByOf(claim, late.terms))) / 86_400_000);
}

/** The date a claim is due by (yyyy-mm-dd): its date plus the terms in days. */
export function dueByOf(claim: Claim, terms: number): string {
  const [y, m, d] = claim.date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + terms)).toISOString().slice(0, 10);
}

/** Whether a claim is overdue: it is OPEN and its due-by is before the sheet's today. */
export function overdue(claim: Claim, terms: number, today: string | null): boolean {
  return claim.status === 'OPEN' && today !== null && dueByOf(claim, terms) < today;
}

/** Cents shown with two decimals. */
export function money(total: number): string {
  return (total / 100).toFixed(2);
}

const PAST: Record<ClaimAction, string> = { open: 'opened', settle: 'settled', void: 'voided' };

/** An amount of 0.00 or more with up to two decimals. */
export const AMOUNT = /^\d+(\.\d{1,2})?$/;

/** A claim's amount: any amount with up to two decimals; a negative one is a correction. */
const CLAIM_AMOUNT = /^-?\d+(\.\d{1,2})?$/;

/** A claim number a correction names. */
const NUMBER = /^\d+$/;

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
      <label [for]="'claim-corrects-' + personId()">Corrects claim no.</label>
      <input
        [id]="'claim-corrects-' + personId()"
        data-testid="claim-form-corrects"
        inputmode="numeric"
        [value]="corrects()"
        (input)="corrects.set(value($event)); error.set(null)"
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
          <input
            type="checkbox"
            data-testid="claim-select"
            [attr.aria-label]="'Select claim ' + c.id"
            [checked]="selectedIds().has(c.id)"
            (change)="selectedChange.emit({ id: c.id, selected: checked($event) })"
          />
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span data-testid="claim-due">{{ dueOf(c) }}</span>
          <span data-testid="claim-due-by">{{ dueBy(c) }}</span>
          <span data-testid="claim-overdue">{{ isOverdue(c) ? 'yes' : 'no' }}</span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span data-testid="claim-number">{{ c.number ?? '' }}</span>
          <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span>
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
  private readonly rates = inject(RateStore);

  readonly personId = input.required<number>();
  readonly claims = input.required<Claim[]>();
  /** The person's grades, each in force from its from-date. */
  readonly grades = input.required<PersonGrade[]>();
  /** The sheet's floor in cents: the least a live due can be. */
  readonly floor = input.required<number>();
  /** The sheet's terms in days: a claim is due by its date plus them. */
  readonly terms = input.required<number>();
  /** The sheet's late percent (two decimals). */
  readonly latePct = input.required<string>();
  /** The sheet's policy date (yyyy-mm-dd); blank for its today. */
  readonly policyDate = input.required<string>();
  /** The ids of the claims selected to act on together. */
  readonly selectedIds = input.required<ReadonlySet<number>>();
  readonly saved = output<Claim>();
  /** A claim's checkbox ticked or cleared. */
  readonly selectedChange = output<{ id: number; selected: boolean }>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly corrects = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);
  /** The error from the last status change refused, and the claim it belongs to. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  /** The claim's due (see claimDueCents), or n/a when it has no figure. */
  protected dueOf(claim: Claim): string {
    const late = {
      pct: this.latePct(),
      terms: this.terms(),
      today: this.session.today(),
      policyDate: this.policyDate(),
    };
    const due = claimDueCents(claim, this.rates.ofGradeOn(this.grades(), claim.date), this.floor(), late);
    return due === null ? NO_RATE : money(due);
  }

  protected dueBy(claim: Claim): string {
    return dueByOf(claim, this.terms());
  }

  protected isOverdue(claim: Claim): boolean {
    return overdue(claim, this.terms(), this.session.today());
  }

  /** The name of the period the claim's date falls in; empty when it falls in none. */
  protected periodNameOf(claim: Claim): string {
    return periodOf(this.periods.periods(), claim.date)?.name ?? '';
  }

  /** Open the editor on a DRAFT claim; any other claim cannot be edited (an OPEN one is voided and
   *  added again), which is refused on the row. */
  protected edit(claim: Claim): void {
    this.rowError.set(null);
    if (claim.status !== 'DRAFT') {
      this.rowError.set({ id: claim.id, message: `A ${claim.status} claim cannot be edited; only a draft can.` });
      return;
    }
    this.editingId.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.corrects.set(claim.corrects === null ? '' : String(claim.corrects));
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.error.set(null);
  }

  /** Open, settle or void a claim; a change its status does not allow, the user who opened it
   *  settling it, or a clerk settling a due above the clerk limit, is refused on the row. */
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
      const status = action === 'settle' && e instanceof HttpErrorResponse ? e.status : null;
      this.rowError.set({
        id: claim.id,
        message:
          status === 403
            ? 'The claim could not be settled: whoever opened it cannot settle it.'
            : status === 422
              ? 'The claim could not be settled: its due is above the clerk limit; a steward must settle it.'
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
    if (!CLAIM_AMOUNT.test(amount)) {
      this.error.set('Type an amount with up to two decimals.');
      return;
    }
    const corrects = this.corrects().trim();
    const negative = amount.startsWith('-') && Number(amount) !== 0;
    if (negative && !NUMBER.test(corrects)) {
      this.error.set('A negative amount is a correction: type the number of the settled claim it corrects.');
      return;
    }
    if (!negative && corrects !== '') {
      this.error.set('Only a negative amount corrects a claim: leave the claim number blank.');
      return;
    }
    const body = {
      amount,
      date: this.date().trim() || null,
      note: this.note().trim(),
      corrects: corrects === '' ? null : Number(corrects),
    };
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
      const what = negative ? 'correction' : 'claim';
      this.error.set(id === null ? `The ${what} could not be added.` : `The ${what} could not be saved.`);
    } finally {
      this.busy.set(false);
    }
  }
}
