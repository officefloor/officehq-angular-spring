import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Period, periodOn } from '../periods/periods';
import { Rate, Rounding, dueOf, floored, percentOf, rateOn, sumDues } from '../rates/rates';

export interface Claim {
  id: number;
  personId: number;
  /** Two decimals, e.g. "2.50". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
  /** Given when the claim is opened; null for a DRAFT and once voided. */
  number: string | null;
  /** The due fixed when the claim was settled, two decimals; null when it has none and its due is live. */
  settledDue: string | null;
  /** The user who opened the claim; null for a DRAFT. That user cannot settle it. */
  openedBy: string | null;
  /** Whether the person's last statement bound the claim: it then shows the due it bound it to. */
  stated: boolean;
  /** The due the last statement bound the claim to, two decimals; null when unbound, or bound with none (n/a). */
  statedDue: string | null;
  /** The number of the SETTLED claim of the same person this claim corrects; null unless it is a correction. */
  corrects: string | null;
  /** The date (yyyy-mm-dd) that decides how its live figures round: its own, or a correction's corrected claim's. */
  roundingDate: string;
  /** Its place in its person's list: a claim with a later place is listed first (newest added, or last reassigned). */
  listed: number;
  /** The day (yyyy-mm-dd) the claim is due by, fixed when it was added from the terms of its person's grade on its date. */
  dueBy: string;
  /** Its status log, oldest first: one line per status it has had. */
  log: ClaimLogLine[];
  /** The date (yyyy-mm-dd) an OPEN claim is deferred until; null when it was never deferred. */
  deferredUntil: string | null;
  /** How many proofs the claim has: one above the proof threshold on its date cannot be settled or approved with none. */
  proofs: number;
  /** The number of the claim this one was split off; null unless it was split off another. */
  splitFrom: string | null;
}

/** A line of a claim's status log: the status, who changed it, the sheet's today then, and the due then (or n/a). */
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  /** yyyy-mm-dd */
  date: string;
  due: string;
}

/** Claims as listed: the latest list place first. */
export function byListed(a: Claim, b: Claim): number {
  return b.listed - a.listed || b.id - a.id;
}

/**
 * DRAFT when added, then OPEN, then SETTLED; a clerk settling above the clerk limit makes it PENDING until a steward
 * approves (SETTLED) or rejects it (OPEN). A DRAFT, OPEN or PENDING claim may be VOID.
 */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

/** Whether a claim is a correction (a negative amount): its due has no floor and no late charge. */
export function isCorrection(claim: Claim): boolean {
  return claim.amount.trim().startsWith('-') && /[1-9]/.test(claim.amount);
}

/**
 * Whether a claim counts toward claimed and the totals: only OPEN, PENDING and SETTLED ones do, but not one deferred on
 * the sheet's today.
 */
export function counts(claim: Claim, today: string | null): boolean {
  return (isOpen(claim) || claim.status === 'SETTLED') && !deferred(claim, today);
}

/**
 * Whether the claim is deferred on the sheet's today: an OPEN claim deferred until a later date. It then counts in no
 * total, is not overdue, cannot be settled and is left unbound by a statement.
 */
export function deferred(claim: Claim, today: string | null): boolean {
  return claim.status === 'OPEN' && claim.deferredUntil !== null && today !== null && today < claim.deferredUntil;
}

/**
 * The status the claim had at the end of the date, as its status log records it: its last line logged on or before
 * the date; null when it has lines but none by then (it was added later). With no log at all, its status now.
 */
export function statusOn(claim: Claim, date: string): ClaimStatus | null {
  if (claim.log.length === 0) return claim.status;
  let found: ClaimStatus | null = null;
  for (const line of claim.log) if (line.date <= date) found = line.status;
  return found;
}

/** Whether a claim is handled as open: OPEN or PENDING. It goes overdue, bears the late charge, and a hold takes it out. */
export function isOpen(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'PENDING';
}

/** Whether the claim's due follows its last statement now: a PENDING one shows its live figure while PENDING. */
export function bound(claim: Claim): boolean {
  return claim.stated && claim.status !== 'PENDING';
}

/** One of a person's grades, from its from-date (yyyy-mm-dd); null from the beginning of time. */
export interface PersonGrade {
  grade: string;
  from: string | null;
}

/** The person's grade in force on the date: the one with the latest from-date on or before it. */
export function gradeOn(grades: PersonGrade[], date: string): string | undefined {
  let found: PersonGrade | undefined;
  for (const g of grades) {
    if (g.from !== null && g.from > date) continue;
    if (found === undefined || (found.from ?? '') <= (g.from ?? '')) found = g;
  }
  return found?.grade;
}

/**
 * How the claim's live figures round: half up when its rounding date is before the sheet's policy date (the sheet's
 * today when blank), half to even on or after it.
 */
export function roundingOf(claim: Claim, session: Session): Rounding {
  const policy = session.policyDate() || session.today();
  return policy !== null && claim.roundingDate < policy ? 'half-up' : 'half-even';
}

/**
 * The due fixed when the claim was settled, else its figure (the one its person's last statement bound it to, else
 * its amount times the rate of its person's grade on its date, both in force on its date, raised to the sheet's floor on its date, or n/a with no rate) with its late
 * charge added on top. A correction has neither floor nor late charge.
 */
export function claimDue(claim: Claim, rates: Rate[], grades: PersonGrade[], session: Session): string {
  if (claim.settledDue !== null) return claim.settledDue;
  const grade = gradeOn(grades, claim.date);
  const due = dueOf(claim.amount, grade === undefined ? undefined : rateOn(rates, grade, claim.date), roundingOf(claim, session));
  const figure = bound(claim)
    ? (claim.statedDue ?? 'n/a')
    : isCorrection(claim) ? due : floored(due, session.settingOn('floor', claim.date));
  return figure === 'n/a' ? figure : sumDues([figure, lateCharge(claim, figure, session)]);
}

/**
 * For every full 30 days an OPEN claim is past its due-by on the sheet's today, the late percent in force on that
 * boundary (the due-by plus 30, 60, 90 ... days) of the figure, each rounded like the claim's due, added up; 0.00 when
 * it is not overdue, and on a correction.
 */
export function lateCharge(claim: Claim, figure: string, session: Session): string {
  const by = claim.dueBy;
  const today = session.today();
  if (!isOpen(claim) || isCorrection(claim) || by === '' || today === null) return '0.00';
  if (deferred(claim, today)) return '0.00';
  const days = Math.round((Date.parse(today) - Date.parse(by)) / 86_400_000);
  const charges: string[] = [];
  for (let boundary = 1; boundary <= Math.floor(days / 30); boundary++) {
    const pct = session.settingOn('late-pct', plusDays(by, 30 * boundary));
    if (pct !== null) charges.push(percentOf(figure, pct, 1, roundingOf(claim, session)));
  }
  return sumDues(charges);
}

/** The yyyy-mm-dd date the days after the date. */
function plusDays(date: string, days: number): string {
  return new Date(Date.parse(date) + days * 86_400_000).toISOString().slice(0, 10);
}

/** Whether the claim's due is fixed, by settling or by a statement, rather than worked out from the rates. */
export function dueFixed(claim: Claim): boolean {
  return claim.settledDue !== null || (bound(claim) && claim.statedDue !== null);
}

/** Whether the claim is OPEN or PENDING, not deferred, and its due-by is before the sheet's today. */
export function overdue(claim: Claim, today: string | null): boolean {
  return isOpen(claim) && today !== null && claim.dueBy < today && !deferred(claim, today);
}

type ClaimAction = 'open' | 'settle' | 'void' | 'approve' | 'reject' | 'resettle';

/** Sums amounts with two decimals exactly, in whole cents. */
export function sumAmounts(amounts: string[]): string {
  const cents = amounts.reduce((total, a) => total + Math.round(Number(a) * 100), 0);
  return (cents / 100).toFixed(2);
}

// One person's claims, newest first, each with its due, and the form that adds a claim or, once a claim's editor is
// opened, saves that claim. Rendered as a table cell inside the person's row.
@Component({
  selector: 'td[app-claims]',
  template: `
    <form (submit)="submit($event)">
      <label [for]="fieldId('amount')">Amount</label>
      <input
        [id]="fieldId('amount')"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('date')">Date</label>
      <input
        [id]="fieldId('date')"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('note')">Note</label>
      <input
        [id]="fieldId('note')"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('corrects')">Corrects claim no.</label>
      <input
        [id]="fieldId('corrects')"
        data-testid="claim-form-corrects"
        [value]="corrects()"
        (input)="corrects.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editing() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as message) {
        <p role="alert" data-testid="claim-form-error">{{ message }}</p>
      }
    </form>
    @if (claims().length > 0) {
      <table>
        <caption class="visually-hidden">Claims of {{ personName() }}</caption>
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">No.</th><th scope="col">Amount</th><th scope="col">Date</th><th scope="col">Note</th><th scope="col">Due</th><th scope="col">Due by</th><th scope="col">Overdue</th>
            <th scope="col">Status</th><th scope="col">Opened by</th><th scope="col">Stated</th><th scope="col">Period</th><th scope="col">Corrects</th><th scope="col">Deferred</th><th scope="col">Deferred until</th><th scope="col">Proofs</th><th scope="col">Split from</th><th scope="col">Log</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (c of claims(); track c.id) {
            <tr [attr.data-testid]="'claim-row-' + c.id">
              <td>
                <input
                  type="checkbox"
                  data-testid="claim-select"
                  [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date"
                  [checked]="selected().has(c.id)"
                  (change)="selectToggled.emit({ id: c.id, checked: checked($event) })"
                />
              </td>
              <td data-testid="claim-number">{{ c.number ?? '' }}</td>
              <td data-testid="claim-amount">{{ c.amount }}</td>
              <td data-testid="claim-date">{{ c.date }}</td>
              <td data-testid="claim-note">{{ c.note }}</td>
              <td data-testid="claim-due">{{ due(c) }}</td>
              <td data-testid="claim-due-by">{{ c.dueBy }}</td>
              <td data-testid="claim-overdue">{{ overdue(c) ? 'yes' : 'no' }}</td>
              <td data-testid="claim-status">{{ c.status }}</td>
              <td data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</td>
              <td data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</td>
              <td data-testid="claim-period">{{ periodOf(c) }}</td>
              <td data-testid="claim-corrects">{{ c.corrects ?? '' }}</td>
              <td data-testid="claim-deferred">{{ deferred(c) ? 'yes' : 'no' }}</td>
              <td data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</td>
              <td data-testid="claim-proofs">{{ c.proofs }}</td>
              <td data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</td>
              <td>
                <ol class="claim-log">
                  @for (line of c.log; track $index) {
                    <li data-testid="claim-log-line">{{ line.status }} by {{ line.by }} on {{ line.date }} due={{ line.due }}</li>
                  }
                </ol>
              </td>
              <td>
                <button
                  type="button"
                  data-testid="claim-edit"
                  [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
                  (click)="edit(c)"
                >
                  Edit
                </button>
                @if (editing() === c.id) {
                  <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
                }
                @for (a of actions; track a.action) {
                  <button
                    type="button"
                    [attr.data-testid]="'claim-' + a.action"
                    [attr.aria-label]="a.label + ' claim of ' + c.amount + ' on ' + c.date"
                    (click)="act(c, a.action)"
                  >
                    {{ a.label }}
                  </button>
                }
                <button
                  type="button"
                  data-testid="claim-proof"
                  [attr.aria-label]="'Add a proof to claim of ' + c.amount + ' on ' + c.date"
                  (click)="addProof(c)"
                >
                  Add proof
                </button>
                <label class="visually-hidden" [for]="'claim-split-amount-' + c.id">Amount to split off</label>
                <input
                  [id]="'claim-split-amount-' + c.id"
                  data-testid="claim-split-amount"
                  inputmode="decimal"
                  placeholder="Amount to split off"
                  [value]="splitAmounts()[c.id] ?? ''"
                  (input)="setSplitAmount(c.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="claim-split"
                  [attr.aria-label]="'Split claim of ' + c.amount + ' on ' + c.date"
                  (click)="split(c)"
                >
                  Split
                </button>
                <label class="visually-hidden" [for]="'claim-defer-date-' + c.id">Defer until</label>
                <input
                  [id]="'claim-defer-date-' + c.id"
                  type="date"
                  data-testid="claim-defer-date"
                  [value]="deferDates()[c.id] ?? ''"
                  (input)="setDeferDate(c.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="claim-defer"
                  [attr.aria-label]="'Defer claim of ' + c.amount + ' on ' + c.date"
                  (click)="defer(c)"
                >
                  Defer
                </button>
                <label class="visually-hidden" [for]="'claim-reassign-to-' + c.id">Reassign to</label>
                <select
                  [id]="'claim-reassign-to-' + c.id"
                  data-testid="claim-reassign-to"
                  (change)="setReassignTo(c.id, value($event))"
                >
                  <option value="" [selected]="!reassignTo()[c.id]">Reassign to…</option>
                  @for (o of others(); track o.id) {
                    <option [value]="o.id" [selected]="reassignTo()[c.id] === '' + o.id">{{ o.name }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="claim-reassign"
                  [attr.aria-label]="'Reassign claim of ' + c.amount + ' on ' + c.date"
                  (click)="reassign(c)"
                >
                  Reassign
                </button>
                <label class="visually-hidden" [for]="'claim-reject-reason-' + c.id">Reason to reject</label>
                <input
                  [id]="'claim-reject-reason-' + c.id"
                  data-testid="claim-reject-reason"
                  placeholder="Reason to reject"
                  [value]="rejectReasons()[c.id] ?? ''"
                  (input)="setRejectReason(c.id, value($event))"
                />
                @if (actionError(); as e) {
                  @if (e.id === c.id) {
                    <p role="alert" data-testid="claim-error">{{ e.message }}</p>
                  }
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** The person's grades, each from its from-date. */
  readonly grades = input.required<PersonGrade[]>();
  /** The sheet's rates: a claim's due uses the rate in force on the claim's date of the grade in force then. */
  readonly rates = input.required<Rate[]>();
  /** The sheet's periods: a claim belongs to the one whose dates contain its date. */
  readonly periods = input.required<Period[]>();
  /** The sheet's people, in the order they were added: a claim may be reassigned to any of the others. */
  readonly people = input<{ id: number; name: string }[]>([]);
  /** The date the person is retired from (yyyy-mm-dd); null while not retired. */
  readonly retiredFrom = input<string | null>(null);
  /** The ids of the sheet's claims selected to act on together. */
  readonly selected = input<ReadonlySet<number>>(new Set());
  /** A claim's checkbox was ticked or cleared. */
  readonly selectToggled = output<{ id: number; checked: boolean }>();
  /** A claim was added, saved or changed status. */
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  /** The number of the settled claim a correction (a negative amount) corrects. */
  protected readonly corrects = signal('');
  /** The id of the claim whose editor is open, or null when the form adds a claim. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  /** The error of the last status change refused, and the claim it belongs to. */
  protected readonly actionError = signal<{ id: number; message: string } | null>(null);
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
    { action: 'approve', label: 'Approve' },
    { action: 'reject', label: 'Reject' },
    { action: 'resettle', label: 'Re-settle' },
  ];
  /** The people other than this one, whom a claim may be reassigned to. */
  protected readonly others = computed(() => this.people().filter((p) => p.id !== this.personId()));
  /** The id (as text) of the person picked to reassign each claim to, by claim id. */
  protected readonly reassignTo = signal<Record<number, string>>({});
  /** The reason typed to reject each PENDING claim, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});
  /** The date (yyyy-mm-dd) picked to defer each OPEN claim until, by claim id. */
  protected readonly deferDates = signal<Record<number, string>>({});
  /** The amount typed to split off each OPEN claim, by claim id. */
  protected readonly splitAmounts = signal<Record<number, string>>({});

  protected fieldId(field: string): string {
    return `claim-form-${field}-${this.personId()}`;
  }

  /** The due fixed when the claim was settled, else its amount times the rate on its date, or n/a with no rate. */
  protected due(claim: Claim): string {
    return claimDue(claim, this.rates(), this.grades(), this.session);
  }

  /** Whether the claim is OPEN and was due by a day before the sheet's today. */
  protected overdue(claim: Claim): boolean {
    return overdue(claim, this.session.today());
  }

  /** Whether the claim is deferred on the sheet's today. */
  protected deferred(claim: Claim): boolean {
    return deferred(claim, this.session.today());
  }

  /** The name of the period the claim's date falls in, or blank when none. */
  protected periodOf(claim: Claim): string {
    return periodOn(this.periods(), claim.date)?.name ?? '';
  }

  /** The closed period the date falls in, or undefined when none does. */
  private closedOn(date: string): Period | undefined {
    const period = periodOn(this.periods(), date);
    return period?.closed ? period : undefined;
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  /** Open the claim's editor; only a DRAFT can be edited; to change an OPEN claim, void it and add a new one. */
  protected edit(claim: Claim): void {
    if (claim.status !== 'DRAFT') {
      this.reset();
      this.actionError.set({
        id: claim.id,
        message: `This claim is ${claim.status}: only a DRAFT can be edited. To change an open claim, void it and add a new one.`,
      });
      return;
    }
    this.editing.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.corrects.set(claim.corrects ?? '');
    this.error.set(null);
    this.actionError.set(null);
  }

  protected reset(): void {
    this.editing.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.error.set(null);
  }

  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((all) => ({ ...all, [id]: reason }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  protected setDeferDate(id: number, date: string): void {
    this.deferDates.update((all) => ({ ...all, [id]: date }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /**
   * Defer an OPEN claim until the date picked for it; the store refuses a claim not OPEN, a date not after the sheet's
   * today, and a closed period.
   */
  protected async defer(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const until = (this.deferDates()[claim.id] ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(until)) {
      this.actionError.set({ id: claim.id, message: 'Pick the date to defer this claim until.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/defer`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { until }, this.session.headers()));
      this.setDeferDate(claim.id, '');
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch {
      const closed = this.closedOn(claim.date);
      const today = this.session.today();
      const message =
        claim.status !== 'OPEN'
          ? `This claim is ${claim.status}: only an OPEN claim can be deferred.`
          : closed
            ? `This claim is dated in the closed period ${closed.name}: it cannot be deferred.`
            : today !== null && until <= today
              ? `A claim can only be deferred to a date after today (${today}).`
              : 'This claim could not be deferred.';
      this.actionError.set({ id: claim.id, message });
    }
  }

  /** Add a proof to the claim; the store refuses a SETTLED or VOID claim, and a closed period. */
  protected async addProof(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/proof`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, null, this.session.headers()));
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch {
      const closed = this.closedOn(claim.date);
      const message =
        claim.status === 'SETTLED' || claim.status === 'VOID'
          ? `This claim is ${claim.status}: a proof cannot be added to it.`
          : closed
            ? `This claim is dated in the closed period ${closed.name}: a proof cannot be added to it.`
            : 'A proof could not be added to this claim.';
      this.actionError.set({ id: claim.id, message });
    }
  }

  protected setSplitAmount(id: number, amount: string): void {
    this.splitAmounts.update((all) => ({ ...all, [id]: amount }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /**
   * Split the amount typed for it off an OPEN claim into a new OPEN claim; the store refuses a claim not OPEN or
   * deferred, an amount not above 0.00 and below the claim's, a closed period, and a role that may not open a claim.
   */
  protected async split(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = (this.splitAmounts()[claim.id] ?? '').trim();
    if (!/^\d+(\.\d{1,2})?$/.test(amount)) {
      this.actionError.set({ id: claim.id, message: 'Type the amount to split off, with at most two decimals.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/split`;
    try {
      const added = await firstValueFrom(this.http.post<Claim>(url, { amount }, this.session.headers()));
      this.setSplitAmount(claim.id, '');
      if (this.session.sheet() === sheet) this.saved.emit(added);
    } catch (e) {
      const closed = this.closedOn(claim.date);
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role may not open a claim, so it may not split one.'
          : claim.status !== 'OPEN'
            ? `This claim is ${claim.status}: only an OPEN claim can be split.`
            : this.deferred(claim)
              ? `This claim is deferred until ${claim.deferredUntil}: it cannot be split.`
              : closed
                ? `This claim is dated in the closed period ${closed.name}: it cannot be split.`
                : `The amount split off must be more than 0.00 and less than ${claim.amount}.`;
      this.actionError.set({ id: claim.id, message });
    }
  }

  protected setReassignTo(id: number, personId: string): void {
    this.reassignTo.update((all) => ({ ...all, [id]: personId }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /**
   * Move a DRAFT, OPEN or PENDING claim to the person picked for it; the store refuses a SETTLED or VOID claim, a
   * closed period, a person retired before its date, and a user who is not a steward.
   */
  protected async reassign(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const to = this.others().find((p) => String(p.id) === this.reassignTo()[claim.id]);
    if (to === undefined) {
      this.actionError.set({ id: claim.id, message: 'Pick the person to reassign this claim to.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/reassign`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { personId: to.id }, this.session.headers()));
      this.setReassignTo(claim.id, '');
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const closed = this.closedOn(claim.date);
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can reassign a claim.'
          : closed
            ? `This claim is dated in the closed period ${closed.name}: it cannot be reassigned.`
            : claim.status === 'SETTLED' || claim.status === 'VOID'
              ? `This claim is ${claim.status}: only a DRAFT, OPEN or PENDING claim can be reassigned.`
              : `This claim could not be reassigned to ${to.name}.`;
      this.actionError.set({ id: claim.id, message });
    }
  }

  /**
   * Open, settle, void, approve, reject or re-settle a claim; the store refuses a change its status, or the user's role, does not
   * allow. Rejecting takes the reason typed for the claim.
   */
  protected async act(claim: Claim, action: ClaimAction): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const reason = (this.rejectReasons()[claim.id] ?? '').trim();
    if (action === 'reject' && reason === '') {
      this.actionError.set({ id: claim.id, message: 'Type a reason to reject this claim.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/${action}`;
    try {
      const body = action === 'reject' ? { reason } : null;
      const saved = await firstValueFrom(this.http.post<Claim>(url, body, this.session.headers()));
      if (action === 'reject') this.setRejectReason(claim.id, '');
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const closed = this.closedOn(claim.date);
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? action === 'settle'
            ? 'Whoever opened this claim cannot also settle it: another user must settle it.'
            : action === 'resettle'
            ? 'Only a steward can re-settle a claim.'
            : 'Only a steward can approve or reject a pending claim, and not the one who opened it.'
          : closed
              ? `This claim is dated in the closed period ${closed.name}: it cannot be ${pastOf[action]}.`
              : e instanceof HttpErrorResponse && e.status === 422 && (action === 'settle' || action === 'approve')
                ? `This claim's due is above the proof threshold: add a proof before it can be ${pastOf[action]}.`
              : action === 'settle' && this.deferred(claim)
                ? `This claim is deferred until ${claim.deferredUntil}: it cannot be settled before then.`
              : action === 'resettle'
                ? `This claim is ${claim.status}: only a SETTLED claim can be re-settled.`
                : `This claim's status does not let it be ${pastOf[action]}.`;
      this.actionError.set({ id: claim.id, message });
    }
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!/^-?\d+(\.\d{1,2})?$/.test(amount)) {
      this.error.set('Type an amount with at most two decimals; a negative one is a correction.');
      return;
    }
    const corrects = this.corrects().trim();
    if (corrects.length > 255) {
      this.error.set('Type the number of the settled claim the correction corrects.');
      return;
    }
    const date = this.date().trim();
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Pick a date for the claim, or leave it blank for today.');
      return;
    }
    const body = { amount, date: date || null, note: this.note(), corrects: corrects || null };
    const editing = this.editing();
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        editing === null
          ? this.http.post<Claim>(`${base}/people/${this.personId()}/claims`, body, this.session.headers())
          : this.http.put<Claim>(`${base}/claims/${editing}`, body, this.session.headers()),
      );
      if (this.session.sheet() === sheet) {
        this.reset();
        this.saved.emit(saved);
      }
    } catch (e) {
      const dated = date || (this.session.today() ?? '');
      const closed = this.closedOn(dated);
      const retiredFrom = this.retiredFrom();
      const why = closed
        ? ` Its date is in the closed period ${closed.name}.`
        : retiredFrom !== null && dated > retiredFrom
          ? ` ${this.personName()} is retired from ${retiredFrom}.`
          : editing !== null && e instanceof HttpErrorResponse && e.status === 409
          ? ' Only a DRAFT claim can be edited.'
          : e instanceof HttpErrorResponse && e.status === 422
          ? ' A negative amount is a correction: it must name, by number, a settled claim of this person.'
          : '';
      this.error.set((editing === null ? 'The claim could not be added.' : 'The claim could not be saved.') + why);
    } finally {
      this.submitting = false;
    }
  }
}

const pastOf: Record<ClaimAction, string> = {
  open: 'opened',
  settle: 'settled',
  void: 'voided',
  approve: 'approved',
  reject: 'rejected',
  resettle: 're-settled',
};
