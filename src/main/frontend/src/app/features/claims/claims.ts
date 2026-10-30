import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Period, periodOf } from '../periods/periods';

/**
 * DRAFT when added, then OPEN, then SETTLED; a clerk's settle above the clerk limit leaves it PENDING
 * until a steward approves it (SETTLED) or rejects it (OPEN again). A steward can re-settle a SETTLED claim,
 * fixing its due again at today's figure. A DRAFT, OPEN or PENDING claim can be VOID,
 * as can a SETTLED one by a steward within 7 days of settling.
 */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

export type ClaimAction = 'open' | 'settle' | 'approve' | 'reject' | 'resettle' | 'void';

export interface Claim {
  id: number;
  amount: string;
  date: string;
  note: string;
  /** The amount at the rate for the person's grade, or once settled the due fixed then; null when there is no rate. */
  due: string | null;
  status: ClaimStatus;
  /** The number the claim got when opened: the next on the sheet, or within its period (P1-1, X-1); null while a draft. */
  number: string | null;
  /** The user who opened the claim, kept once voided; null while it was never opened. */
  openedBy: string | null;
  /** Whether the person's last statement binds the claim to the figure it had then. */
  stated: boolean;
  /** Fixed when the claim was added: its date plus the terms then of its person's grade on its date. */
  dueBy: string;
  /** Whether the claim is OPEN or PENDING and its due-by is before the sheet's today. */
  overdue: boolean;
  /** For a correction (a negative amount), the number of the settled claim it corrects, as shown (3, P1-1); null otherwise. */
  corrects: string | null;
  /** The claim's status log, oldest first: one line per status it has had. */
  log: ClaimLogLine[];
  /** Whether the claim is OPEN and deferred until a date after the sheet's today: it then counts in no total,
   * is not overdue and cannot be settled. */
  deferred: boolean;
  /** The date the claim was last deferred until, kept once that date has passed; null when it was never deferred. */
  deferredUntil: string | null;
  /** How many proofs the claim has. */
  proofs: number;
  /** For a claim split off another, the number of the claim it was split from, as shown; null otherwise. */
  splitFrom: string | null;
  /** The user whose open editor locks the claim for everyone else; null when no editor holds it. */
  lockedBy: string | null;
  /** Whether the claim was added while its period was soft-closed. */
  lateEntry: boolean;
  /** The last sheet's today a reminder was written for the claim; null when it was never reminded. */
  remindedOn: string | null;
}

/** A line of a claim's status log: the status, who changed it, the sheet's today then and its due then (null when none). */
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  on: string;
  due: string | null;
}

/** A hold on a person from a date to a date (both included); null to-date: open-ended. */
export interface Hold {
  from: string;
  to: string | null;
}

/** Whether the claim is OPEN, not bound by a statement, and dated within one of the holds: it then counts in no live total. */
export function heldOut(c: Claim, holds: Hold[]): boolean {
  return c.status === 'OPEN' && !c.stated && holds.some((h) => c.date >= h.from && (h.to === null || c.date <= h.to));
}

/** Whether the claim is OPEN or PENDING, not bound by a statement (a PENDING one never is), and its person's retirement date is reached
 * on the sheet's today: it then counts in no live total. */
export function retiredOut(c: Claim, retiredNow: boolean): boolean {
  return retiredNow && ((c.status === 'OPEN' && !c.stated) || c.status === 'PENDING');
}

export interface PersonWithClaims {
  id: number;
  /** The version the person was last saved at; an edit sends the one its editor loaded. */
  version: number;
  name: string;
  grade: string;
  /** The person's own terms in days; null when they have none (their grade's or the sheet's apply). */
  terms: number | null;
  claims: Claim[];
  /** The amounts of the OPEN, PENDING and SETTLED claims added up, but not unbound OPEN ones dated within a hold. */
  claimed: string;
  /** Claimed at the rate for the person's grade, held to the sheet's cap; null when the grade has no rate. */
  dueTotal: string | null;
  /** What the person's sum of figures is above the sheet's cap; 0.00 when it is not (or is n/a). */
  excess: string;
  /** How many statements were taken for the person. */
  statementCount: number;
  /** The last statement's due-total; null when there is none or it was n/a. */
  statedTotal: string | null;
  /** The sheet's today when the last statement was taken; null when there is none. */
  statementDate: string | null;
  /** Whether a retirement date is set: retired at once, whatever the date. */
  retired: boolean;
  /** The date the person is retired from; no claim dated after it can be added. Null when not retired. */
  retiredFrom: string | null;
  /** Whether the person has any hold, whatever its dates. */
  held: boolean;
  /** The person's holds, earliest first: their OPEN claims dated within one, unless bound by a statement,
   * count in no live total. */
  holds: Hold[];
  /** How many of the claims that count are overdue. */
  overdueCount: number;
  /** The due figures of the overdue claims that count, added up. */
  overdueTotal: string;
  /** The ids of the people on the sheet the person named as their patrons. */
  patrons: number[];
  /** Whether they have an OPEN or PENDING claim that counts dated within the 30 days up to and including the sheet's today. */
  active: boolean;
  /** The settled figures of their SETTLED claims dated within the 90 days up to and including the sheet's today, added up. */
  recentSettled: string;
  /** The due figures of their OPEN and PENDING claims that count, by the claim's age on the sheet's today (days from its date; one dated after it is aged 0): 0 to 30 days, 31 to 60, 61 or more. */
  aged0to30: string;
  aged31to60: string;
  aged61: string;
  /** Whether the sheet's today has reached their retirement date: their OPEN and PENDING claims, unless bound by a
   * statement, then count in no live total. */
  retiredNow: boolean;
}

const AMOUNT = /^-?\d{1,12}(\.\d{1,2})?$/;
const NUMBER = /^([1-9]\d{0,8}|.{1,100}-[1-9]\d{0,8})$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// A person's claims, newest first, and the one form that adds a claim or, once a claim's editor is
// opened, edits it. Each claim can be opened, settled, approved, rejected (with a reason), re-settled or voided. A saved or moved claim answers with
// the person, which is handed up as `saved`. Each claim can be selected, to be opened together with
// the others selected.
@Component({
  selector: 'app-claims',
  template: `
    <ul aria-label="Claims">
      @for (c of person().claims; track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input
            type="checkbox"
            [id]="'claim-select-' + c.id"
            data-testid="claim-select"
            [checked]="selected().has(c.id)"
            (change)="selectToggled.emit({ id: c.id, checked: checked($event) })"
          />
          <label [for]="'claim-select-' + c.id" class="visually-hidden">Select claim {{ c.amount }} {{ c.date }}</label>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Due <span data-testid="claim-due">{{ c.due ?? 'n/a' }}</span></span>
          <span>Status <span data-testid="claim-status">{{ c.status }}</span></span>
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periodName(c) }}</span></span>
          <span>Due by <span data-testid="claim-due-by">{{ c.dueBy }}</span></span>
          <span>Overdue <span data-testid="claim-overdue">{{ c.overdue ? 'yes' : 'no' }}</span></span>
          <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
          <span>Deferred <span data-testid="claim-deferred">{{ c.deferred ? 'yes' : 'no' }}</span></span>
          <span>Deferred until <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span></span>
          <span>Proofs <span data-testid="claim-proofs">{{ c.proofs }}</span></span>
          <span>Split from <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span></span>
          <span>Late entry <span data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' }}</span></span>
          <span>Reminded <span data-testid="claim-reminded">{{ c.remindedOn ?? '' }}</span></span>
          <button type="button" data-testid="claim-remind" (click)="remind(c)">
            Remind<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <button type="button" data-testid="claim-proof" (click)="addProof(c)">
            Add proof<span class="visually-hidden"> to claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <ol aria-label="Status log">
            @for (l of c.log; track $index) {
              <li data-testid="claim-log-line">{{ l.status }} by {{ l.by }} on {{ l.on }} due={{ l.due ?? 'n/a' }}</li>
            }
          </ol>
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input
            [id]="'claim-reject-reason-' + c.id"
            data-testid="claim-reject-reason"
            [value]="rejectReasons()[c.id] ?? ''"
            (input)="setRejectReason(c.id, value($event))"
          />
          @for (a of actions; track a.action) {
            <button type="button" [attr.data-testid]="'claim-' + a.action" (click)="move(c, a.action)">
              {{ a.label }}<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
            </button>
          }
          <label [for]="'claim-defer-date-' + c.id">Defer until</label>
          <input
            [id]="'claim-defer-date-' + c.id"
            type="date"
            data-testid="claim-defer-date"
            [value]="deferDates()[c.id] ?? ''"
            (input)="setDeferDate(c.id, value($event))"
          />
          <button type="button" data-testid="claim-defer" (click)="defer(c)">
            Defer<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <label [for]="'claim-date-new-' + c.id">New date</label>
          <input
            [id]="'claim-date-new-' + c.id"
            type="date"
            data-testid="claim-date-new"
            [value]="newDates()[c.id] ?? ''"
            (input)="setNewDate(c.id, value($event))"
          />
          <button type="button" data-testid="claim-redate" (click)="redate(c)">
            Re-date<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <label [for]="'claim-split-amount-' + c.id">Split off amount</label>
          <input
            [id]="'claim-split-amount-' + c.id"
            inputmode="decimal"
            data-testid="claim-split-amount"
            [value]="splitAmounts()[c.id] ?? ''"
            (input)="setSplitAmount(c.id, value($event))"
          />
          <button type="button" data-testid="claim-split" (click)="split(c)">
            Split<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <label [for]="'claim-reassign-to-' + c.id">Reassign to</label>
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
          <button type="button" data-testid="claim-reassign" (click)="reassign(c)">
            Reassign<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
          </button>
          @if (claimError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ claimError()?.message }}</p>
          }
          <button type="button" data-testid="claim-edit" (click)="edit(c)">
            Edit claim<span class="visually-hidden"> {{ c.amount }} {{ c.date }}</span>
          </button>
          @if (editing() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
          }
        </li>
      }
    </ul>
    <form (submit)="save($event)" [attr.aria-label]="(editing() === null ? 'Add claim for ' : 'Edit claim for ') + person().name">
      <label [for]="fid('amount')">Amount</label>
      <input
        [id]="fid('amount')"
        inputmode="decimal"
        data-testid="claim-form-amount"
        [disabled]="locking()"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set('')"
      />
      <label [for]="fid('date')">Date</label>
      <input
        [id]="fid('date')"
        type="date"
        data-testid="claim-form-date"
        [disabled]="locking()"
        [value]="date()"
        (input)="date.set(value($event)); error.set('')"
      />
      <label [for]="fid('note')">Note</label>
      <input
        [id]="fid('note')"
        data-testid="claim-form-note"
        [disabled]="locking()"
        [value]="note()"
        (input)="note.set(value($event)); error.set('')"
      />
      <label [for]="fid('corrects')">Corrects claim no.</label>
      <input
        [id]="fid('corrects')"
        inputmode="numeric"
        data-testid="claim-form-corrects"
        [disabled]="locking()"
        [value]="corrects()"
        (input)="corrects.set(value($event)); error.set('')"
      />
      <button type="submit" data-testid="claim-form-submit" [disabled]="locking()">
        {{ editing() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error()) {
        <p role="alert" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class Claims implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly person = input.required<PersonWithClaims>();
  readonly periods = input<Period[]>([]);
  readonly saved = output<PersonWithClaims>();
  /** The people on the sheet a claim can be reassigned to. */
  readonly people = input<readonly { id: number; name: string }[]>([]);
  /** The ids of the claims selected to be opened together. */
  readonly selected = input<ReadonlySet<number>>(new Set());
  readonly selectToggled = output<{ id: number; checked: boolean }>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly corrects = signal('');
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');
  /** An edit button waiting for its claim's lock: the form is held until the answer comes. */
  protected readonly locking = signal(false);
  /** The refusal of the last action taken on a claim's row, shown on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  /** The reason typed for rejecting each claim, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});
  /** The date typed for deferring each claim until, by claim id. */
  protected readonly deferDates = signal<Record<number, string>>({});
  /** The new date typed for re-dating each claim, by claim id. */
  protected readonly newDates = signal<Record<number, string>>({});
  /** The amount typed for splitting off each claim, by claim id. */
  protected readonly splitAmounts = signal<Record<number, string>>({});
  /** The id of the person chosen to reassign each claim to, by claim id. */
  protected readonly reassignTo = signal<Record<number, string>>({});
  /** The other people on the sheet, whom this person's claims can be reassigned to. */
  protected readonly others = computed(() => this.people().filter((p) => p.id !== this.person().id));
  /** A submit or action still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'approve', label: 'Approve' },
    { action: 'reject', label: 'Reject' },
    { action: 'resettle', label: 'Re-settle' },
    { action: 'void', label: 'Void' },
  ];

  /** The name of the period whose dates contain the claim's due-by; '' when none does. */
  protected periodName(c: Claim): string {
    return periodOf(this.periods(), c.dueBy)?.name ?? '';
  }

  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((all) => ({ ...all, [id]: reason }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  protected setDeferDate(id: number, until: string): void {
    this.deferDates.update((all) => ({ ...all, [id]: until }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** Defer an OPEN claim until the date typed, which must be after the sheet's today; a refusal shows on the claim's row. */
  protected async defer(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const until = (this.deferDates()[c.id] ?? '').trim();
    if (!DATE.test(until)) {
      this.claimError.set({ id: c.id, message: 'Enter the date to defer the claim until.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/defer`,
          { until },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.deferDates.update((all) => ({ ...all, [c.id]: '' }));
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'The claim cannot be deferred to that date.' });
    } finally {
      this.busy = false;
    }
  }

  protected setNewDate(id: number, date: string): void {
    this.newDates.update((all) => ({ ...all, [id]: date }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** A steward re-dates an OPEN or PENDING claim to the date typed; a refusal shows on the claim's row. */
  protected async redate(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const date = (this.newDates()[c.id] ?? '').trim();
    if (!DATE.test(date)) {
      this.claimError.set({ id: c.id, message: 'Enter the new date of the claim.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/redate`,
          { date },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.newDates.update((all) => ({ ...all, [c.id]: '' }));
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'The claim cannot be re-dated to that date.' });
    } finally {
      this.busy = false;
    }
  }

  protected setSplitAmount(id: number, amount: string): void {
    this.splitAmounts.update((all) => ({ ...all, [id]: amount }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /**
   * Split the amount typed off an OPEN claim into a new OPEN claim; the amount must be more than 0.00 and
   * less than the claim's. A refusal shows on the claim's row.
   */
  protected async split(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = (this.splitAmounts()[c.id] ?? '').trim();
    if (!/^\d{1,12}(\.\d{1,2})?$/.test(amount)) {
      this.claimError.set({ id: c.id, message: 'Enter the amount to split off, with up to two decimals.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/split`,
          { amount },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.splitAmounts.update((all) => ({ ...all, [c.id]: '' }));
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'The claim cannot be split by that amount.' });
    } finally {
      this.busy = false;
    }
  }

  /** Remind an OPEN or PENDING claim, once per sheet's today; a refusal shows on the claim's row. */
  protected async remind(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(`/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/remind`, {}, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'The claim cannot be reminded.' });
    } finally {
      this.busy = false;
    }
  }

  /** Add a proof to the claim, raising its proofs count by one; a refusal shows on the claim's row. */
  protected async addProof(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(`/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/proof`, {}, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'A proof cannot be added to this claim.' });
    } finally {
      this.busy = false;
    }
  }

  protected setReassignTo(id: number, personId: string): void {
    this.reassignTo.update((all) => ({ ...all, [id]: personId }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** A steward moves a claim to the chosen person; a refusal shows on the claim's row. */
  protected async reassign(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const to = this.reassignTo()[c.id] ?? '';
    if (to === '') {
      this.claimError.set({ id: c.id, message: 'Choose the person to reassign the claim to.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const moved = await firstValueFrom(
        this.http.post<PersonWithClaims[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/reassign`,
          { personId: Number(to) },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.reassignTo.update((all) => ({ ...all, [c.id]: '' }));
      for (const person of moved) this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? `A ${c.status} claim cannot be reassigned.` });
    } finally {
      this.busy = false;
    }
  }

  protected fid(field: string): string {
    return `claim-${this.person().id}-${field}`;
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  /** An editor the signed-in user left open on one of the person's claims is open again once the sheet is re-read. */
  ngOnInit(): void {
    const mine = this.person().claims.find((c) => c.status === 'DRAFT' && c.lockedBy !== null && c.lockedBy === this.session.user());
    if (mine) this.fill(mine);
  }

  /** Why the claim's editor cannot be opened, as far as the screen knows; null when it may be. */
  private editRefusal(c: Claim): string | null {
    if (c.status !== 'DRAFT') return `A ${c.status} claim cannot be edited; void it and add a new one.`;
    const period = periodOf(this.periods(), c.dueBy);
    if (period?.closed) return `The claim is in the closed period ${period.name}, so it cannot be edited.`;
    if (c.lockedBy !== null && c.lockedBy !== this.session.user()) return `${c.lockedBy} is editing this claim.`;
    return null;
  }

  /**
   * Open a claim's editor, locking the claim for other users until it is saved or cancelled (or the sheet's
   * today changes). A claim that cannot be edited (not a DRAFT, in a closed period, or locked by another
   * user), or a viewer's edit, is refused on its row and takes no lock.
   */
  protected async edit(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const refusal = this.editRefusal(c);
    if (refusal !== null) {
      this.claimError.set({ id: c.id, message: refusal });
      return;
    }
    const sheet = this.session.sheet();
    if (!sheet) return;
    const previous = this.editing();
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    this.locking.set(true);
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(`/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/lock`, null, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      if (previous !== null && previous !== c.id) this.unlock(previous);
      this.saved.emit(person);
      this.fill(person.claims.find((x) => x.id === c.id) ?? c);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refused =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refused || 'The claim cannot be edited now; another user may be editing it.' });
    } finally {
      this.busy = false;
      this.locking.set(false);
    }
  }

  private fill(c: Claim): void {
    this.editing.set(c.id);
    this.amount.set(c.amount);
    this.date.set(c.date);
    this.note.set(c.note);
    this.corrects.set(c.corrects ?? '');
    this.error.set('');
  }

  /** Close the claim's editor without saving, releasing its lock. */
  protected cancel(): void {
    const id = this.editing();
    if (id !== null) {
      if (this.claimError()?.id === id) this.claimError.set(null);
      this.unlock(id);
    }
    this.reset();
  }

  /** Release the signed-in user's lock on a claim; nothing to show either way. */
  private unlock(id: number): void {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.http.post(`/api/sheets/${encodeURIComponent(sheet)}/claims/${id}/unlock`, null, { headers }).subscribe({
      error: () => undefined,
    });
  }

  protected reset(): void {
    this.editing.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.error.set('');
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = this.amount().trim();
    const date = this.date().trim();
    const note = this.note();
    const corrects = this.corrects().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Enter an amount with up to two decimals; a negative amount is a correction.');
      return;
    }
    if (corrects !== '' && !NUMBER.test(corrects)) {
      this.error.set('Enter the number of the claim this corrects, or leave it blank.');
      return;
    }
    if (date !== '' && !DATE.test(date)) {
      this.error.set('Enter a date, or leave it blank for the entry date (or today).');
      return;
    }
    const id = this.editing();
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    const body = { amount, date: date === '' ? null : date, note, corrects: corrects === '' ? null : corrects };
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        id === null
          ? this.http.post<PersonWithClaims>(`${base}/people/${this.person().id}/claims`, body, { headers })
          : this.http.put<PersonWithClaims>(`${base}/claims/${id}`, body, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(person);
      this.reset();
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.error.set(refusal ?? (id === null ? 'Could not add the claim.' : 'Could not save the claim.'));
    } finally {
      this.busy = false;
    }
  }

  /**
   * Open, settle, approve, reject, re-settle or void a claim; a change its status does not allow is refused on its
   * row, as is a reject without a reason.
   */
  protected async move(c: Claim, action: ClaimAction): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const reason = (this.rejectReasons()[c.id] ?? '').trim();
    if (action === 'reject' && reason === '') {
      this.claimError.set({ id: c.id, message: 'Enter a reason to reject the claim.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/${action}`,
          action === 'reject' ? { reason } : null,
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      if (action === 'reject') this.rejectReasons.update((all) => ({ ...all, [c.id]: '' }));
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      if (err instanceof HttpErrorResponse && err.status === 403) {
        this.claimError.set({ id: c.id, message: err.error?.message || `You opened this claim, so another user must ${action} it.` });
        return;
      }
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? `A ${c.status} claim cannot be ${PAST[action]}.` });
    } finally {
      this.busy = false;
    }
  }
}

const PAST: Record<ClaimAction, string> = {
  open: 'opened',
  settle: 'settled',
  approve: 'approved',
  reject: 'rejected',
  resettle: 're-settled',
  void: 'voided',
};
