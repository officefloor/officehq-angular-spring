import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Late, Session } from '../../core/session';
import { Period, closedOn, periodOn } from '../periods/periods';
import { NA, dueCents } from '../rates/rates';

/**
 * DRAFT when added, then OPEN, then SETTLED; a clerk's settling above the clerk limit makes it
 * PENDING until a steward approves it (SETTLED) or rejects it (OPEN). A DRAFT, OPEN or PENDING
 * claim can be made VOID.
 */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

/** A status change a claim's buttons ask for. */
type ClaimAction = 'open' | 'settle' | 'void' | 'approve' | 'reject';

export interface Claim {
  id: number;
  personId: number;
  /** Always two decimals, e.g. "2.50". */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** Given when the claim is opened and kept once VOID; null while DRAFT. */
  /** The sheet-wide number, or <period name>-<n> (X-<n> in no period) when numbered by period; null while DRAFT. */
  number: string | null;
  /** The due fixed when it was settled, two decimals; null while its due is live. */
  settledDue: string | null;
  /** The user who opened it; null while DRAFT. They cannot settle it. */
  openedBy: string | null;
  /** Bound by its person's last statement, to the due it had then; not while PENDING, when its due is live. */
  stated: boolean;
  /** The due bound by its person's last statement, two decimals; null while not stated, or stated with none. */
  statedDue: string | null;
  /** A correction's (negative amount's) number of the SETTLED claim it corrects; null for any other claim. */
  corrects: string | null;
  /** The date it is due by (yyyy-mm-dd), fixed when it was added; it does not move with its date or the terms. */
  dueBy: string;
  /** Its status log, oldest first: a line for each status it has had, DRAFT when added. */
  log: ClaimLogLine[];
}

/** A status a claim was given, by whom, on the sheet's today then, with its due then (null: none). */
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  date: string;
  due: string | null;
}

/** A person's rate on a date: the rate then of the grade they had then, or null when it has none. */
export type PctOn = (date: string) => string | null;

/** Open: OPEN, or PENDING, which is handled like it. */
export function isOpen(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'PENDING';
}

/** Only open (OPEN or PENDING) and SETTLED claims count toward what is claimed and due. */
export function counts(c: Claim): boolean {
  return isOpen(c) || c.status === 'SETTLED';
}

/** Whether its due is now bound by its person's last statement: stated and not PENDING. */
export function isBound(c: Claim): boolean {
  return c.stated && c.status !== 'PENDING';
}

/**
 * Whether the claim counts for its person, given whether its date falls within one of their holds:
 * an open claim dated within a hold does not, unless bound by a statement.
 */
export function countsFor(c: Claim, heldOnDate: boolean): boolean {
  return counts(c) && (!heldOnDate || !isOpen(c) || isBound(c));
}

/** A claim's amount as cents, so sums are exact; a correction's is negative. */
export function cents(amount: string): number {
  const negative = amount.startsWith('-');
  const [whole, frac = ''] = (negative ? amount.slice(1) : amount).split('.');
  const size = Number(whole) * 100 + Number(frac.padEnd(2, '0'));
  return negative ? -size : size;
}

/** A correction: a claim with a negative amount. Its due has no floor and no late charge. */
export function isCorrection(c: Claim): boolean {
  return cents(c.amount) < 0;
}

/**
 * The date a claim's live figures round by: a correction's corrected claim's date (found among its
 * person's claims, `theirs`), else its own.
 */
export function roundsOn(c: Claim, theirs: Claim[]): string {
  if (c.corrects === null) return c.date;
  return theirs.find((o) => o.number === c.corrects)?.date ?? c.date;
}

/** Whether a claim rounding by the given date rounds half up: dated before the policy date; else half to even. */
function halfUp(basis: string, late: Late): boolean {
  return late.policy !== null && basis < late.policy;
}

/**
 * A claim's due in cents: the due fixed when it was settled (late charge frozen in), else its figure
 * — the due bound by its person's last statement, else its amount times its person's rate on its
 * date (as `pctOn` gives it), at least the floor (in cents) unless a correction — with its late charge on top;
 * null when it has none. Its live figures round by the date `basis` (see {@link roundsOn}).
 */
export function claimDueCents(c: Claim, pctOn: PctOn, floorCents: number, late: Late, basis: string): number | null {
  if (c.settledDue !== null) return cents(c.settledDue);
  const up = halfUp(basis, late);
  const figure = figureCents(c, pctOn, floorCents, up);
  return figure === null ? null : figure + lateChargeCents(c, figure, late, up);
}

/** A claim's figure in cents: the due bound by its person's last statement, else its floored live due. */
function figureCents(c: Claim, pctOn: PctOn, floorCents: number, halfUp: boolean): number | null {
  if (isBound(c)) return c.statedDue === null ? null : cents(c.statedDue);
  const pct = pctOn(c.date);
  if (pct === null) return null;
  const due = dueCents(cents(c.amount), pct, halfUp);
  return isCorrection(c) ? due : Math.max(due, floorCents);
}

/**
 * The late charge in cents on an overdue claim with the given figure: the late percent of it for
 * every full 30 days past its due-by, rounded like a due; 0 when not overdue or a correction. Never bound
 * by a statement.
 */
export function lateChargeCents(c: Claim, figureCents: number, late: Late, halfUp: boolean): number {
  if (late.today === null || isCorrection(c) || !isOverdue(c, late.today)) return 0;
  const days = Math.round((Date.parse(`${late.today}T00:00:00Z`) - Date.parse(`${c.dueBy}T00:00:00Z`)) / 86_400_000);
  return dueCents(figureCents * Math.floor(days / 30), late.pct, halfUp);
}

/** Overdue: OPEN or PENDING with its due-by before the sheet's today. */
export function isOverdue(c: Claim, today: string | null): boolean {
  return isOpen(c) && today !== null && c.dueBy < today;
}

/** Why a change to a claim dated inside a closed period is refused. */
const CLOSED = 'No claim dated inside a closed period can be changed.';

/** Why a claim dated after its person's retirement date is refused. */
export const RETIRED = 'No claim can be dated after its person\'s retirement date.';

/** Cents as an amount with two decimals; a minus sign when negative. */
export function money(cents: number): string {
  const size = Math.abs(cents);
  return `${cents < 0 ? '-' : ''}${Math.floor(size / 100)}.${String(size % 100).padStart(2, '0')}`;
}

/** What each action makes of a claim, for why it was refused. */
const PAST: Record<ClaimAction, string> = {
  open: 'opened', settle: 'settled', void: 'voided', approve: 'approved', reject: 'rejected',
};

/** Why a claim's correction is refused. */
const CORRECTION = 'A negative amount is a correction: name the number of a SETTLED claim of this person that it corrects.';

/** Why a claim cannot be edited: only a DRAFT can; an OPEN or PENDING one is voided and added again. */
function notEditable(status: ClaimStatus | undefined): string {
  return status === 'OPEN' || status === 'PENDING'
    ? `An ${status} claim cannot be edited; void it and add a new one.`
    : `${status ? 'A ' + status : 'This'} claim cannot be edited; only a DRAFT can.`;
}

// One person's claims, newest first, with one form to add a claim or edit one, and buttons to
// open, settle or void each claim, approve or reject (with a reason) a PENDING one, move one to another person, and a checkbox to select each for a bulk change.
@Component({
  selector: 'app-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="save()" [attr.aria-label]="(editingId() === null ? 'Add claim for ' : 'Edit claim for ') + personName()">
      <label [for]="fieldId('amount')">Amount</label>
      <input [id]="fieldId('amount')" data-testid="claim-form-amount" formControlName="amount" inputmode="decimal" />
      <label [for]="fieldId('date')">Date</label>
      <input [id]="fieldId('date')" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="fieldId('note')">Note</label>
      <input [id]="fieldId('note')" data-testid="claim-form-note" formControlName="note" />
      <label [for]="fieldId('corrects')">Corrects claim no.</label>
      <input [id]="fieldId('corrects')" data-testid="claim-form-corrects" formControlName="corrects" />
      <button type="submit" data-testid="claim-form-submit">{{ editingId() === null ? 'Add claim' : 'Save claim' }}</button>
      @if (error()) {
        <p role="alert" class="error" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
    <ul [attr.aria-label]="'Claims for ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input type="checkbox" data-testid="claim-select" [checked]="selected().has(c.id)"
                 (change)="toggled.emit({ id: c.id, checked: $any($event.target).checked })"
                 [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date" />
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Period <span data-testid="claim-period">{{ periodOf(c) }}</span></span>
          <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>Due by <span data-testid="claim-due-by">{{ c.dueBy }}</span></span>
          <span>Overdue <span data-testid="claim-overdue">{{ overdueOf(c) ? 'yes' : 'no' }}</span></span>
          <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
          <ol [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
            @for (line of c.log; track $index) {
              <li data-testid="claim-log-line">{{ line.status }} by {{ line.by }} on {{ line.date }} due={{ line.due ?? na }}</li>
            }
          </ol>
          <button type="button" data-testid="claim-open" (click)="act(c, 'open')"
                  [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
          <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')"
                  [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
          <button type="button" data-testid="claim-void" (click)="act(c, 'void')"
                  [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
          <button type="button" data-testid="claim-approve" (click)="act(c, 'approve')"
                  [attr.aria-label]="'Approve claim of ' + c.amount + ' on ' + c.date">Approve</button>
          <label [for]="fieldId('reject-' + c.id)">Reject reason</label>
          <input [id]="fieldId('reject-' + c.id)" data-testid="claim-reject-reason" #rejectReason />
          <button type="button" data-testid="claim-reject" (click)="act(c, 'reject', rejectReason.value)"
                  [attr.aria-label]="'Reject claim of ' + c.amount + ' on ' + c.date">Reject</button>
          <label [for]="fieldId('reassign-' + c.id)">Move to</label>
          <select [id]="fieldId('reassign-' + c.id)" data-testid="claim-reassign-to" #reassignTo
                  (change)="clearClaimError(c.id)">
            @for (o of others(); track o.id) {
              <option [value]="o.id">{{ o.name }}</option>
            }
          </select>
          <button type="button" data-testid="claim-reassign" (click)="reassign(c, reassignTo.value)"
                  [attr.aria-label]="'Move claim of ' + c.amount + ' on ' + c.date">Move</button>
          @if (editingId() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
          } @else {
            <button type="button" data-testid="claim-edit" (click)="edit(c)"
                    [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
          }
          @if (claimError()?.id === c.id) {
            <p role="alert" class="error" data-testid="claim-error">{{ claimError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly claims = input.required<Claim[]>();
  /** The person's rate on a date, of the grade they had then. */
  readonly pctOn = input.required<PctOn>();
  /** The sheet's periods, for the one each claim's date falls in. */
  readonly periods = input.required<Period[]>();
  /** A claim added, edited or given a new status, as the store now holds it. */
  readonly saved = output<Claim>();
  /** The other people on the sheet a claim may be moved to. */
  readonly others = input.required<{ id: number; name: string }[]>();
  /** A claim moved to another person, as the store now holds it. */
  readonly moved = output<Claim>();
  /** The ids of the claims selected for a bulk change. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** A claim's checkbox ticked or cleared. */
  readonly toggled = output<{ id: number; checked: boolean }>();

  protected readonly na = NA;
  protected readonly editingId = signal<number | null>(null);
  /** A submit is under way; another one waits for its answer rather than storing twice. */
  private saving = false;
  protected readonly error = signal('');
  /** The error from the last action on a claim's row, if it was refused. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
    corrects: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected fieldId(field: string): string {
    return `claim-${this.personId()}-${field}`;
  }

  /** The due fixed when it was settled, else its figure with its late charge on top. */
  protected dueOf(c: Claim): string {
    const due = claimDueCents(c, this.pctOn(), cents(this.session.floor()), this.session.late(), roundsOn(c, this.claims()));
    return due === null ? NA : money(due);
  }

  protected overdueOf(c: Claim): boolean {
    return isOverdue(c, this.session.today());
  }

  /** The name of the period its date falls in, or blank when none. */
  protected periodOf(c: Claim): string {
    return periodOn(this.periods(), c.date)?.name ?? '';
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private reset(): void {
    this.editingId.set(null);
    this.form.reset({ amount: '', date: '', note: '', corrects: '' }, { emitEvent: false });
    this.error.set('');
  }

  protected edit(c: Claim): void {
    this.claimError.set(null);
    if (closedOn(this.periods(), c.date)) {
      this.claimError.set({ id: c.id, message: CLOSED });
      return;
    }
    if (c.status !== 'DRAFT') {
      this.claimError.set({ id: c.id, message: notEditable(c.status) });
      return;
    }
    this.editingId.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note, corrects: c.corrects ?? '' },
      { emitEvent: false });
    this.error.set('');
  }

  protected cancel(): void {
    this.reset();
  }

  /**
   * Open, settle or void a claim, or approve or reject (with a reason) a PENDING one; a change its
   * status does not allow is refused.
   */
  protected async act(c: Claim, action: ClaimAction, reason = ''): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const why = reason.trim().replace(/\s+/g, ' ');
    if (action === 'reject' && (why === '' || why.length > 200)) {
      this.claimError.set({ id: c.id, message: 'Give a reason for rejecting the claim (at most 200 characters).' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/${action}`;
    const body = action === 'reject' ? { reason: why } : null;
    try {
      const claim = await firstValueFrom(this.http.post<Claim>(url, body, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 423
        ? CLOSED
        : e instanceof HttpErrorResponse && e.status === 409
        ? `A ${c.status} claim cannot be ${PAST[action]}.`
        : e instanceof HttpErrorResponse && e.status === 403
        ? action === 'settle'
          ? 'The user who opened a claim cannot settle it.'
          : action === 'approve' && c.openedBy === this.session.user()
          ? 'The user who opened a claim cannot approve it.'
          : 'Only a steward can approve or reject a PENDING claim.'
        : 'Could not change that claim.';
      this.claimError.set({ id: c.id, message });
    }
  }

  protected clearClaimError(id: number): void {
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** A steward moves a DRAFT, OPEN or PENDING claim to another person; a SETTLED or VOID one cannot move. */
  protected async reassign(c: Claim, to: string): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (to === '') {
      this.claimError.set({ id: c.id, message: 'Choose another person to move the claim to.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/reassign`;
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(url, { personId: Number(to) }, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      if (this.editingId() === c.id) this.reset();
      this.moved.emit(claim);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 423
        ? CLOSED
        : e instanceof HttpErrorResponse && e.status === 409
        ? `A ${c.status} claim cannot be moved; only a DRAFT, OPEN or PENDING one can.`
        : e instanceof HttpErrorResponse && e.status === 403
        ? 'Only a steward can move a claim to another person.'
        : e instanceof HttpErrorResponse && e.status === 422
        ? RETIRED
        : 'Could not move that claim.';
      this.claimError.set({ id: c.id, message });
    }
  }

  protected async save(): Promise<void> {
    if (this.saving) return;
    this.error.set('');
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.form.getRawValue();
    const amount = raw.amount.trim();
    const date = raw.date.trim();
    const corrects = raw.corrects.trim();
    if (!/^-?\d{1,12}(\.\d{1,2})?$/.test(amount)) {
      this.error.set('Enter an amount with at most two decimals, negative only for a correction.');
      return;
    }
    const correction = amount.startsWith('-') && cents(amount) < 0;
    if (correction ? !/^\S{1,220}$/.test(corrects) : corrects !== '') {
      this.error.set(correction ? CORRECTION : 'Only a correction (a negative amount) names a claim it corrects.');
      return;
    }
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date, or leave it blank for today.');
      return;
    }
    const body = { amount, date: date || null, note: raw.note.trim(), corrects: corrects || null };
    const id = this.editingId();
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    this.saving = true;
    try {
      const claim = await firstValueFrom(
        id === null
          ? this.http.post<Claim>(`${base}/people/${this.personId()}/claims`, body, { headers: this.headers() })
          : this.http.put<Claim>(`${base}/claims/${id}`, body, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
      this.reset();
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      if (id !== null && e instanceof HttpErrorResponse && e.status === 409) {
        const status = this.claims().find((c) => c.id === id)?.status;
        this.claimError.set({ id, message: notEditable(status) });
        return;
      }
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? CLOSED
          : e instanceof HttpErrorResponse && e.status === 422
          ? RETIRED
          : e instanceof HttpErrorResponse && e.status === 400 && correction
          ? CORRECTION
          : id === null ? 'Could not add that claim.' : 'Could not save that claim.',
      );
    } finally {
      this.saving = false;
    }
  }
}
