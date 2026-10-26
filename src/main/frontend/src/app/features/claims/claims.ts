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
  // Given when the claim is opened: the sheet's next (1, 2, ...) or, while the sheet numbers by period, its
  // period's next (P1-1; X-1 for no period); null for a claim never opened.
  number: string | null;
  // The due fixed when the claim was settled; null while its due is live.
  settledDue: string | null;
  // The user who opened the claim; null for a claim never opened. That user cannot settle it.
  openedBy: string | null;
  // Whether the person's last statement binds the claim, and the figure it binds it to (null for n/a).
  stated: boolean;
  statedDue: string | null;
  // The number of the settled claim a correction (a negative amount) corrects; null for any other claim.
  corrects: string | null;
  // The date the claim's rounding follows: its own, or for a correction the date of the claim it corrects.
  roundsOn: string;
  // The date the claim is due by: fixed when it was added, from the terms for its person's grade on its date.
  dueBy: string;
  // The claim's status log, oldest first: a line for each status it has had, from its DRAFT on.
  log: ClaimLogLine[];
}

// A status the claim came to have: who made the change, the sheet's today then, and the claim's due then (null
// for n/a).
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  on: string | null;
  due: string | null;
}

// A clerk settling a claim whose due is above the clerk limit makes it PENDING, for a steward to approve (SETTLED)
// or reject (OPEN again).
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

type ClaimAction = 'open' | 'settle' | 'approve' | 'reject' | 'void';

/** Only open, pending and settled claims count toward what is claimed and due. */
export function counts(c: Claim): boolean {
  return isOpen(c) || c.status === 'SETTLED';
}

/** Whether the claim is open: OPEN or PENDING. An open claim goes overdue and bears the late charge. */
export function isOpen(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'PENDING';
}

/** Whether the person's last statement binds the claim's figure: not while it is pending, which shows it live. */
function isBound(c: Claim): boolean {
  return c.stated && c.status !== 'PENDING';
}

/** Whether the claim is a correction: one with a negative amount. */
export function isCorrection(c: Claim): boolean {
  return c.amount.startsWith('-');
}

/**
 * Whether the claim's live figures round half up: when the date its rounding follows is before the policy date
 * (the sheet's today when the setting is blank); else they round half to even.
 */
export function roundsHalfUp(c: Claim, policyDate: string | null): boolean {
  return policyDate !== null && c.roundsOn < policyDate;
}

/**
 * The figure a claim shows and totals: its settled due, else the one a statement bound it to (not while it is
 * pending), else its live due
 * at the rate in force on its date and at least the floor (a correction has none), rounded as the policy date
 * decides for it; null (n/a) with none.
 */
export function figureOf(c: Claim, rates: readonly RateView[], floor: string, policyDate: string | null): string | null {
  if (c.settledDue !== null) return c.settledDue;
  if (isBound(c)) return c.statedDue;
  const due = dueOn(c.amount, rateOn(rates, c.date), roundsHalfUp(c, policyDate));
  return due === null || isCorrection(c) ? due : atLeast(due, floor);
}

/** Whether the claim keeps a figure whatever the rates: a settled or bound one with a figure. */
export function keepsFigure(c: Claim): boolean {
  return c.settledDue !== null || (isBound(c) && c.statedDue !== null);
}

/** Whether a claim is overdue: open (or pending), and due by a date before the sheet's today. */
export function overdue(c: Claim, today: string | null): boolean {
  return isOpen(c) && today !== null && c.dueBy < today;
}

/**
 * What the late charge follows: the sheet's today and its late percent; and the policy date in force
 * (the sheet's today when the setting is blank), which decides how a claim's live figures round.
 */
export interface Lateness {
  today: string | null;
  latePct: string;
  policyDate: string | null;
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
 * due already holds it. A correction gains no late charge.
 */
export function dueOf(c: Claim, rates: readonly RateView[], floor: string, late: Lateness): string | null {
  const figure = figureOf(c, rates, floor, late.policyDate);
  if (figure === null || isCorrection(c) || !overdue(c, late.today)) return figure;
  const periods = Math.floor(daysBetween(c.dueBy, late.today!) / 30);
  return withPctPer(figure, late.latePct, periods, roundsHalfUp(c, late.policyDate));
}

const CLOSED = 'That claim is dated in a closed period, so it cannot change.';

export const AMOUNT = /^\d+(\.\d{1,2})?$/;

/** A claim form's amount: a negative one is a correction. */
const SIGNED_AMOUNT = /^-?\d+(\.\d{1,2})?$/;

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
      <label [for]="'claim-corrects-' + personId()">Corrects claim no. (for a negative amount)</label>
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
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>

    <table>
      <caption class="visually-hidden">Claims of {{ personName() }}</caption>
      <thead>
        <tr>
          <th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">No.</th><th scope="col">Amount</th><th scope="col">Date</th><th scope="col">Note</th><th scope="col">Due</th><th scope="col">Due by</th><th scope="col">Overdue</th><th scope="col">Status</th><th scope="col">Opened by</th><th scope="col">Stated</th><th scope="col">Period</th><th scope="col">Corrects</th><th scope="col">Log</th>
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
                (change)="selectedChange.emit({ id: c.id, checked: checked($event) })"
              />
            </td>
            <td data-testid="claim-number">{{ c.number ?? '' }}</td>
            <td data-testid="claim-amount">{{ c.amount }}</td>
            <td data-testid="claim-date">{{ c.date }}</td>
            <td data-testid="claim-note">{{ c.note }}</td>
            <td data-testid="claim-due">{{ dueOf(c, ratesOn(c.date), settings.floor(), settings.lateness()) ?? 'n/a' }}</td>
            <td data-testid="claim-due-by">{{ c.dueBy }}</td>
            <td data-testid="claim-overdue">{{ overdue(c, session.sheetToday()) ? 'yes' : 'no' }}</td>
            <td data-testid="claim-status">{{ c.status }}</td>
            <td data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</td>
            <td data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</td>
            <td data-testid="claim-period">{{ periodName(c.date) }}</td>
            <td data-testid="claim-corrects">{{ c.corrects ?? '' }}</td>
            <td>
              <ol class="claim-log" [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
                @for (l of c.log; track $index) {
                  <li data-testid="claim-log-line">{{ l.status }} by {{ l.by }} on {{ l.on ?? '' }} due={{ l.due ?? 'n/a' }}</li>
                }
              </ol>
            </td>
            <td>
              <button type="button" data-testid="claim-open" (click)="act(c, 'open')">
                Open<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')">
                Settle<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-approve" (click)="act(c, 'approve')">
                Approve<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <label [for]="'claim-reject-reason-' + c.id">Reason to reject</label>
              <input
                [id]="'claim-reject-reason-' + c.id"
                data-testid="claim-reject-reason"
                [value]="rejectReasons()[c.id] ?? ''"
                (input)="setRejectReason(c.id, value($event))"
              />
              <button type="button" data-testid="claim-reject" (click)="act(c, 'reject')">
                Reject<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-void" (click)="act(c, 'void')">
                Void<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <label [for]="'claim-reassign-to-' + c.id">Move to</label>
              <select
                [id]="'claim-reassign-to-' + c.id"
                data-testid="claim-reassign-to"
                (change)="setReassignTo(c.id, value($event))"
              >
                <option value="" [selected]="!reassignTo()[c.id]">Choose a person</option>
                @for (q of people(); track q.id) {
                  @if (q.id !== c.personId) {
                    <option [value]="q.id" [selected]="reassignTo()[c.id] === '' + q.id">{{ q.name }}</option>
                  }
                }
              </select>
              <button type="button" data-testid="claim-reassign" (click)="reassign(c)">
                Move<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
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
  // Every person on the sheet, whom a steward can move a claim to.
  readonly people = input<readonly { id: number; name: string }[]>([]);
  readonly saved = output<Claim>();
  // The claims selected for a bulk action, by id; a checkbox's change is reported, not kept here.
  readonly selected = input<ReadonlySet<number>>(new Set());
  readonly selectedChange = output<{ id: number; checked: boolean }>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly corrects = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  // A submit in flight: a second submit before it answers is ignored, so it stores one claim.
  private submitting = false;
  // Each claim's refused status change, by claim id.
  protected readonly rowErrors = signal<Record<number, string>>({});
  // The reason typed to reject each pending claim, by claim id.
  protected readonly rejectReasons = signal<Record<number, string>>({});
  // The person (by id) chosen to move each claim to, by claim id.
  protected readonly reassignTo = signal<Record<number, string>>({});

  protected readonly dueOf = dueOf;

  /** Every rate set for the person's grade in force on the date. */
  protected ratesOn(date: string): readonly RateView[] {
    return this.rateStore.rates().get(gradeOn(this.grades(), date)) ?? [];
  }
  protected readonly overdue = overdue;

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
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
    this.corrects.set(c.corrects ?? '');
    this.error.set(null);
  }

  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((r) => ({ ...r, [id]: reason }));
    this.clearRowError(id);
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
    const reason = (this.rejectReasons()[c.id] ?? '').trim();
    if (action === 'reject' && !reason) {
      this.setRowError(c.id, 'Enter a reason to reject this claim.');
      return;
    }
    try {
      const body = action === 'reject' ? { reason } : null;
      this.saved.emit(await firstValueFrom(this.http.post<Claim>(url, body)));
      if (action === 'reject') this.rejectReasons.update(({ [c.id]: _, ...rest }) => rest);
    } catch (e) {
      const opener = c.openedBy !== null && c.openedBy === this.session.user();
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      const err =
        action === 'settle' && opener
          ? 'You opened this claim, so another user must settle it.'
          : (action === 'approve' || action === 'reject') && forbidden
            ? 'Only a steward can approve or reject a pending claim.'
            : action === 'approve' && opener
              ? 'You opened this claim, so another steward must approve it.'
              : `A ${c.status} claim cannot be changed that way, or its period is closed.`;
      this.setRowError(c.id, err);
    }
  }

  protected setReassignTo(id: number, personId: string): void {
    this.reassignTo.update((r) => ({ ...r, [id]: personId }));
    this.clearRowError(id);
  }

  /** A steward moves the claim to the chosen person; a settled or voided claim cannot move. */
  protected async reassign(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const to = this.reassignTo()[c.id] ?? '';
    if (!to) {
      this.setRowError(c.id, 'Choose the person to move this claim to.');
      return;
    }
    if (c.status === 'SETTLED' || c.status === 'VOID') {
      this.setRowError(c.id, `A ${c.status} claim cannot move.`);
      return;
    }
    if (inClosedPeriod(this.periods.list(), c.date)) {
      this.setRowError(c.id, CLOSED);
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/reassign`;
    try {
      const moved = await firstValueFrom(this.http.post<Claim>(url, { personId: Number(to) }));
      this.reassignTo.update(({ [c.id]: _, ...rest }) => rest);
      this.saved.emit(moved);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.setRowError(
        c.id,
        forbidden ? 'Only a steward can move a claim.' : `This ${c.status} claim cannot be moved to that person.`,
      );
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
    this.corrects.set('');
    this.error.set(null);
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!SIGNED_AMOUNT.test(amount)) {
      this.error.set('Enter an amount with up to two decimals.');
      return;
    }
    const corrects = this.corrects().trim();
    const correction = amount.startsWith('-');
    if (correction && !/^([1-9]\d*|.+-[1-9]\d*)$/.test(corrects)) {
      this.error.set('A negative amount is a correction: enter the number of the settled claim it corrects.');
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
    const form = { amount, date: date || null, note: this.note().trim(), corrects: correction ? corrects : null };
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
      this.error.set(
        correction
          ? `Claim no. ${corrects} is not a settled claim of ${this.personName()}, so it cannot be corrected.`
          : editing === null ? 'Could not add that claim.' : 'Could not save that claim.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
