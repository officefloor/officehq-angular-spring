import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { PeriodStore, periodOf } from '../periods/periods';
import { NO_RATE, PersonGrade, Rate, RateStore, dueCents, rateOn } from '../rates/rates';

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; an OPEN claim a clerk settles above
 *  the clerk limit is PENDING until a steward approves it (SETTLED) or rejects it (OPEN again); a
 *  DRAFT, OPEN or PENDING claim can be VOID, and a steward may void a SETTLED one within 7 days of
 *  its settlement. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

/** A status change a claim's buttons ask for. */
type ClaimAction = 'open' | 'settle' | 'approve' | 'reject' | 'void' | 'resettle';

export interface Claim {
  id: number;
  personId: number;
  /** Two decimals, e.g. "10.00". */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number, given when it is opened and kept when it is voided: sheet-wide (1, 2, ...)
   *  or, under period numbering, <period>-<n> or X-<n>; null for a claim never opened. */
  number: string | null;
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
  corrects: string | null;
  /** The date the claim's live figures round by (yyyy-mm-dd): its own, or for a correction the date
   *  of the claim it corrects. */
  roundsBy: string;
  /** The date the claim is due by (yyyy-mm-dd), fixed when it was added: its date plus the terms of
   *  its person's grade in force on that date. */
  dueBy: string;
  /** The claim's status log, oldest first: one line per status it has had, as
   *  <STATUS> by <user> on <date> due=<due>. */
  log: string[];
  /** The date the claim was deferred until (yyyy-mm-dd); null when it never was. */
  deferredUntil: string | null;
  /** How many proofs were added to the claim. */
  proofs: number;
  /** The number of the claim this claim was split off; null when it was not. */
  splitFrom: string | null;
}

/** What a split did: the claim that kept the rest of its amount, and the new claim split off it. */
interface Split {
  kept: Claim;
  added: Claim;
}

/** Whether a claim is deferred on the sheet's today: it is OPEN and today is before the date it was
 *  deferred until. From that date on it is an OPEN claim like any other. */
export function deferred(claim: Claim, today: string | null): boolean {
  return claim.status === 'OPEN' && claim.deferredUntil !== null && today !== null && today < claim.deferredUntil;
}

/** The status a claim had at the end of a day, as its status log records it: the last status it
 *  moved to on or before that day; null when it was added after it. */
export function statusAsOf(claim: Claim, day: string): ClaimStatus | null {
  let status: ClaimStatus | null = null;
  for (const line of claim.log) {
    const match = /^(\S+) by .* on (\d{4}-\d{2}-\d{2}) due=\S+$/.exec(line);
    if (match && match[2] <= day) status = match[1] as ClaimStatus;
  }
  return status;
}

/** Whether a claim is a correction: its amount is negative. */
export function isCorrection(claim: Claim): boolean {
  return cents(claim.amount) < 0;
}

/** Whether a claim counts toward what is claimed and due: only OPEN, PENDING and SETTLED ones do. */
export function counts(claim: Claim): boolean {
  return isOpen(claim) || claim.status === 'SETTLED';
}

/** Whether a claim is open: OPEN, or PENDING a steward's approval. */
export function isOpen(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'PENDING';
}

/** An amount in cents, from the two-decimal string the server sends. */
export function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** What a late charge works from: the sheet's late percent (two decimals) in force on a date and its today; and what a claim's figures round by: the sheet's policy date (blank for its today). */
export interface Late {
  pct: (date: string) => string;
  today: string | null;
  policyDate: string;
}

/** Whether a claim's live figures round half up: it rounds by a date before the sheet's policy date
 *  (its today when the policy date is blank); otherwise they round half to even. */
function roundsHalfUp(claim: Claim, late: Late): boolean {
  const policy = late.policyDate !== '' ? late.policyDate : late.today;
  return policy !== null && claim.roundsBy < policy;
}

/** A claim's figure in cents: the figure a statement bound it to, unless it is PENDING (no statement
 *  binds it then); otherwise its amount times the rate in force on its date among its grade's rates;
 *  null when it has no figure. The floor is not applied here (see claimDueCents). */
function figureCents(claim: Claim, rates: Rate[], halfUp: boolean): number | null {
  if (claim.stated && claim.status !== 'PENDING') return claim.statedDue === null ? null : cents(claim.statedDue);
  const rate = rateOn(rates, claim.date);
  if (rate === null) return null;
  return dueCents(cents(claim.amount), rate, halfUp);
}

/** A claim's due in cents: the due fixed when it was settled; otherwise the greater of the sheet's floor
 *  in force on its date (none for a correction) and its figure plus its late charge: for every full 30
 *  days an OPEN or PENDING claim is overdue, the late percent in force on that boundary (its due-by plus
 *  30, 60, 90 ... days) of the figure, rounded like a due, the charges added up (a statement never binds
 *  the charge; settling freezes the due; a correction has none); null when it has no figure. */
export function claimDueCents(claim: Claim, rates: Rate[], floor: (date: string) => number, late: Late): number | null {
  if (claim.settledDue !== null) return cents(claim.settledDue);
  const halfUp = roundsHalfUp(claim, late);
  const figure = figureCents(claim, rates, halfUp);
  if (figure === null) return null;
  if (isCorrection(claim)) return figure;
  const periods = Math.floor(daysOverdue(claim, late) / 30);
  let charge = 0;
  for (let boundary = 1; boundary <= periods; boundary++) {
    const pct = late.pct(plusDays(claim.dueBy, 30 * boundary));
    if (cents(pct) !== 0) charge += dueCents(figure, pct, halfUp);
  }
  return Math.max(figure + charge, floor(claim.date));
}

/** A date (yyyy-mm-dd) some days on. */
export function plusDays(date: string, days: number): string {
  return new Date(Date.parse(date) + days * 86_400_000).toISOString().slice(0, 10);
}

/** How many days an OPEN or PENDING claim is past its due-by on the sheet's today; 0 when it is not overdue. */
function daysOverdue(claim: Claim, late: Late): number {
  if (!overdue(claim, late.today) || late.today === null) return 0;
  return Math.round((Date.parse(late.today) - Date.parse(claim.dueBy)) / 86_400_000);
}

/** Whether a claim is overdue: it is OPEN or PENDING, not deferred, and its due-by is before the sheet's today. */
export function overdue(claim: Claim, today: string | null): boolean {
  return isOpen(claim) && today !== null && claim.dueBy < today && !deferred(claim, today);
}

/** Cents shown with two decimals. */
export function money(total: number): string {
  return (total / 100).toFixed(2);
}

const PAST: Record<ClaimAction, string> = {
  open: 'opened',
  settle: 'settled',
  approve: 'approved',
  reject: 'rejected',
  void: 'voided',
  resettle: 're-settled',
};

/** An amount of 0.00 or more with up to two decimals. */
export const AMOUNT = /^\d+(\.\d{1,2})?$/;

/** A claim's amount: any amount with up to two decimals; a negative one is a correction. */
const CLAIM_AMOUNT = /^-?\d+(\.\d{1,2})?$/;

/** A claim number a correction names: sheet-wide (1, 2, ...) or within a period (P1-1, X-1). */
const NUMBER = /^\S+$/;

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
          <span data-testid="claim-deferred">{{ isDeferred(c) ? 'yes' : 'no' }}</span>
          <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span>
          <span data-testid="claim-proofs">{{ c.proofs }}</span>
          <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span>
          <ol data-testid="claim-log" [attr.aria-label]="'Status log of claim ' + c.id">
            @for (line of c.log; track $index) {
              <li data-testid="claim-log-line">{{ line }}</li>
            }
          </ol>
          <button type="button" data-testid="claim-open" [attr.aria-label]="'Open claim ' + c.id" (click)="act(c, 'open')">
            Open
          </button>
          <button type="button" data-testid="claim-settle" [attr.aria-label]="'Settle claim ' + c.id" (click)="act(c, 'settle')">
            Settle
          </button>
          <button type="button" data-testid="claim-approve" [attr.aria-label]="'Approve claim ' + c.id" (click)="act(c, 'approve')">
            Approve
          </button>
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input
            [id]="'claim-reject-reason-' + c.id"
            data-testid="claim-reject-reason"
            [value]="rejectReasons()[c.id] ?? ''"
            (input)="setRejectReason(c.id, value($event))"
          />
          <button type="button" data-testid="claim-reject" [attr.aria-label]="'Reject claim ' + c.id" (click)="act(c, 'reject')">
            Reject
          </button>
          <label [for]="'claim-defer-date-' + c.id">Defer until</label>
          <input
            [id]="'claim-defer-date-' + c.id"
            type="date"
            data-testid="claim-defer-date"
            [value]="deferDates()[c.id] ?? ''"
            (input)="setDeferDate(c.id, value($event))"
          />
          <button type="button" data-testid="claim-defer" [attr.aria-label]="'Defer claim ' + c.id" (click)="defer(c)">
            Defer
          </button>
          <label [for]="'claim-date-new-' + c.id">Re-date to</label>
          <input
            [id]="'claim-date-new-' + c.id"
            type="date"
            data-testid="claim-date-new"
            [value]="newDates()[c.id] ?? ''"
            (input)="setNewDate(c.id, value($event))"
          />
          <button type="button" data-testid="claim-redate" [attr.aria-label]="'Re-date claim ' + c.id" (click)="redate(c)">
            Re-date
          </button>
          <label [for]="'claim-split-amount-' + c.id">Split off</label>
          <input
            [id]="'claim-split-amount-' + c.id"
            data-testid="claim-split-amount"
            inputmode="decimal"
            [value]="splitAmounts()[c.id] ?? ''"
            (input)="setSplitAmount(c.id, value($event))"
          />
          <button type="button" data-testid="claim-split" [attr.aria-label]="'Split claim ' + c.id" (click)="split(c)">
            Split
          </button>
          <button type="button" data-testid="claim-proof" [attr.aria-label]="'Add a proof to claim ' + c.id" (click)="addProof(c)">
            Add proof
          </button>
          <button type="button" data-testid="claim-void" [attr.aria-label]="'Void claim ' + c.id" (click)="act(c, 'void')">
            Void
          </button>
          <button
            type="button"
            data-testid="claim-resettle"
            [attr.aria-label]="'Re-settle claim ' + c.id"
            (click)="act(c, 'resettle')"
          >
            Re-settle
          </button>
          <label [for]="'claim-reassign-to-' + c.id">Move to</label>
          <select
            [id]="'claim-reassign-to-' + c.id"
            data-testid="claim-reassign-to"
            (change)="setReassignTo(c.id, value($event))"
          >
            <option value="" [selected]="!reassignTo()[c.id]">Choose a person</option>
            @for (o of others(); track o.id) {
              <option [value]="o.id" [selected]="reassignTo()[c.id] === '' + o.id">{{ o.name }}</option>
            }
          </select>
          <button type="button" data-testid="claim-reassign" [attr.aria-label]="'Move claim ' + c.id" (click)="reassign(c)">
            Move
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
  /** The sheet's floor in cents in force on a date: the least the live due of a claim of that date can be. */
  readonly floor = input.required<(date: string) => number>();
  /** The sheet's late percent (two decimals) in force on a date. */
  readonly latePct = input.required<(date: string) => string>();
  /** The sheet's policy date (yyyy-mm-dd); blank for its today. */
  readonly policyDate = input.required<string>();
  /** The ids of the claims selected to act on together. */
  readonly selectedIds = input.required<ReadonlySet<number>>();
  /** The people on the sheet, in the order they were added: a claim can move to any other. */
  readonly people = input.required<readonly { id: number; name: string }[]>();
  readonly saved = output<Claim>();
  /** A claim moved to another person: listed as their newest. */
  readonly moved = output<Claim>();
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
  /** The reject reason typed on each claim's row, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});

  /** The defer-until date typed on each claim's row, by claim id. */
  protected readonly deferDates = signal<Record<number, string>>({});

  /** The new date typed on each claim's row to re-date it to, by claim id. */
  protected readonly newDates = signal<Record<number, string>>({});

  /** The amount typed on each claim's row to split off it, by claim id. */
  protected readonly splitAmounts = signal<Record<number, string>>({});

  /** The person chosen on each claim's row to move it to, by claim id (a person id, or blank). */
  protected readonly reassignTo = signal<Record<number, string>>({});
  /** The people a claim of this person can move to: everyone else on the sheet. */
  protected readonly others = computed(() => this.people().filter((p) => p.id !== this.personId()));

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
      today: this.session.today(),
      policyDate: this.policyDate(),
    };
    const due = claimDueCents(claim, this.rates.ofGradeOn(this.grades(), claim.date), this.floor(), late);
    return due === null ? NO_RATE : money(due);
  }

  protected dueBy(claim: Claim): string {
    return claim.dueBy;
  }

  protected isOverdue(claim: Claim): boolean {
    return overdue(claim, this.session.today());
  }

  protected isDeferred(claim: Claim): boolean {
    return deferred(claim, this.session.today());
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
    this.corrects.set(claim.corrects ?? '');
    this.error.set(null);
  }

  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((reasons) => ({ ...reasons, [id]: reason }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  protected setReassignTo(id: number, personId: string): void {
    this.reassignTo.update((chosen) => ({ ...chosen, [id]: personId }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  protected setDeferDate(id: number, date: string): void {
    this.deferDates.update((dates) => ({ ...dates, [id]: date }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /** Defer an OPEN claim until the date typed on its row; a claim that is not OPEN, a date not after
   *  the sheet's today, or a claim dated in a closed period, is refused on the row. */
  protected async defer(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const until = (this.deferDates()[claim.id] ?? '').trim();
    if (until === '') {
      this.rowError.set({ id: claim.id, message: 'Type the date the claim is deferred until.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/defer`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { until }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(saved);
    } catch {
      this.rowError.set({
        id: claim.id,
        message: 'The claim could not be deferred: only an open claim outside a closed period can be, to a date after today.',
      });
    }
  }

  protected setNewDate(id: number, date: string): void {
    this.newDates.update((dates) => ({ ...dates, [id]: date }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /** Re-date an OPEN or PENDING claim to the date typed on its row; anyone but a steward, a claim
   *  that is not OPEN or PENDING, a claim in a closed period or a new date inside one, or a new date
   *  after its person's retirement, is refused on the row. */
  protected async redate(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const date = (this.newDates()[claim.id] ?? '').trim();
    if (date === '') {
      this.rowError.set({ id: claim.id, message: 'Type the date to re-date the claim to.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/redate`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { date }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(saved);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.rowError.set({
        id: claim.id,
        message: forbidden
          ? 'The claim could not be re-dated: only a steward can re-date a claim.'
          : 'The claim could not be re-dated: only an open or pending claim outside a closed period can be, to a date outside one and not after its person retired.',
      });
    }
  }

  protected setSplitAmount(id: number, amount: string): void {
    this.splitAmounts.update((amounts) => ({ ...amounts, [id]: amount }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /** Split the amount typed on its row off an OPEN claim: the claim keeps the rest, and the new OPEN
   *  claim is listed first. A claim that is not OPEN or is deferred, one in a closed period, an
   *  amount not above 0.00 and below the claim's, or a role that may not open a claim, is refused on
   *  the row. */
  protected async split(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = (this.splitAmounts()[claim.id] ?? '').trim();
    if (!AMOUNT.test(amount) || cents(amount) <= 0 || cents(amount) >= cents(claim.amount)) {
      this.rowError.set({ id: claim.id, message: "Type an amount above 0.00 and below the claim's to split off." });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/split`;
    try {
      const result = await firstValueFrom(this.http.post<Split>(url, { amount }));
      if (this.session.sheetId() !== sheet) return;
      this.setSplitAmount(claim.id, '');
      this.saved.emit(result.kept);
      this.saved.emit(result.added);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.rowError.set({
        id: claim.id,
        message: forbidden
          ? 'The claim could not be split: your role may not open a claim on this sheet.'
          : 'The claim could not be split: only an open claim outside a closed period, not deferred, can be.',
      });
    }
  }

  /** Add a proof to a claim, raising its proofs count by one; a SETTLED or VOID claim, or one dated
   *  in a closed period, is refused on the row. */
  protected async addProof(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/proof`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, null));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(saved);
    } catch {
      this.rowError.set({
        id: claim.id,
        message: 'A proof could not be added: a settled or voided claim, or one in a closed period, takes no more.',
      });
    }
  }

  /** A steward moves a DRAFT, OPEN or PENDING claim to the person chosen on its row; anyone else, a
   *  SETTLED or VOID claim, or no person chosen, is refused on the row. */
  protected async reassign(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const to = this.reassignTo()[claim.id] ?? '';
    if (to === '') {
      this.rowError.set({ id: claim.id, message: 'Choose the person to move the claim to.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/reassign`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { toPersonId: Number(to) }));
      if (this.session.sheetId() !== sheet) return;
      this.setReassignTo(claim.id, '');
      this.moved.emit(saved);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.rowError.set({
        id: claim.id,
        message: forbidden
          ? 'The claim could not be moved: your role may not move a claim on this sheet.'
          : 'The claim could not be moved: a settled or voided claim, or a correction, stays where it is.',
      });
    }
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.error.set(null);
  }

  /** Open, settle, approve, reject (with the reason typed on its row), void or re-settle a claim; a clerk
   *  settling a due above the clerk limit leaves it PENDING. A change its status does not allow, the
   *  user who opened it settling or approving it, anyone but a steward approving or rejecting it, or
   *  a reject with no reason, is refused on the row. */
  protected async act(claim: Claim, action: ClaimAction): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const reason = (this.rejectReasons()[claim.id] ?? '').trim();
    if (action === 'reject' && reason === '') {
      this.rowError.set({ id: claim.id, message: 'Type the reason the claim is rejected.' });
      return;
    }
    const url =
      `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/${action}`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, action === 'reject' ? { reason } : null));
      if (this.session.sheetId() !== sheet) return;
      if (action === 'reject') this.setRejectReason(claim.id, '');
      this.saved.emit(saved);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.rowError.set({
        id: claim.id,
        message:
          forbidden && action === 'settle'
            ? 'The claim could not be settled: whoever opened it cannot settle it, and your role must be allowed to settle.'
            : !forbidden && (action === 'settle' || action === 'approve')
              ? `The claim could not be ${PAST[action]}: one due above the proof threshold needs a proof first, and its status must allow it.`
            : forbidden && action === 'resettle'
              ? 'The claim could not be re-settled: only a steward can re-settle a claim.'
              : !forbidden && action === 'void' && claim.status === 'SETTLED'
                ? 'The claim could not be voided: only a steward voids a settled claim, within 7 days of its settlement and outside a closed period.'
              : forbidden
                ? action === 'open' || action === 'void'
                  ? `The claim could not be ${PAST[action]}: your role may not ${action} a claim on this sheet.`
                  : `The claim could not be ${PAST[action]}: only a steward who did not open it can.`
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
      corrects: corrects === '' ? null : corrects,
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
