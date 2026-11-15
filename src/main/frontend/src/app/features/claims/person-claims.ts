import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Period, periodOn } from '../periods/periods-api';
import { DatedGrade, gradeOn } from '../people/people-api';
import { Rate, claimDueCents } from '../rates/rates-api';
import { AMOUNT_PATTERN, CLAIM_AMOUNT_PATTERN, Claim, ClaimAction, ClaimLogLine, ClaimsApi, cents, claimError, deferError, deferred, remindError, editError, proofError, redateError, dueBy, logLineText, money, overdue, reassignError, resettleError, roundsHalfUp, splitError, statusError } from './claims-api';

// One person's claims, newest first, and the one form that adds a claim or, with a claim's
// editor open, saves that claim.
@Component({
  selector: 'app-person-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()"
      [attr.aria-label]="(editingId() === null ? 'Add claim for ' : 'Edit claim for ') + personName()">
      <label [for]="fieldId('amount')">Amount</label>
      <input [id]="fieldId('amount')" data-testid="claim-form-amount" formControlName="amount"
        inputmode="decimal" autocomplete="off" />
      <label [for]="fieldId('date')">Date</label>
      <input [id]="fieldId('date')" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="fieldId('note')">Note</label>
      <input [id]="fieldId('note')" data-testid="claim-form-note" formControlName="note" autocomplete="off" />
      <label [for]="fieldId('corrects')">Corrects claim no.</label>
      <input [id]="fieldId('corrects')" data-testid="claim-form-corrects" formControlName="corrects"
        autocomplete="off" />
      <label [for]="fieldId('ref')">Reference</label>
      <input [id]="fieldId('ref')" data-testid="claim-form-ref" formControlName="ref" autocomplete="off" />
      <button type="submit" data-testid="claim-form-submit">{{ editingId() === null ? 'Add claim' : 'Save claim' }}</button>
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>
    @if (claims().length > 0) {
      <ul [attr.aria-label]="'Claims of ' + personName()">
        @for (c of claims(); track c.id) {
          <li [attr.data-testid]="'claim-row-' + c.id">
            <input type="checkbox" data-testid="claim-select" [checked]="selected().has(c.id)"
              (change)="toggled.emit({ id: c.id, on: $any($event.target).checked })"
              [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date" />
            <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
            <span data-testid="claim-amount">{{ c.amount }}</span>
            <span data-testid="claim-date">{{ c.date }}</span>
            <span data-testid="claim-note">{{ c.note }}</span>
            <span>Ref <span data-testid="claim-ref">{{ c.ref ?? '' }}</span></span>
            <span>Corrects No. <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
            <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
            <span>Status <span data-testid="claim-status">{{ c.status }}</span></span>
            <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
            <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
            <span>Period <span data-testid="claim-period">{{ periodOf(c) }}</span></span>
            <span>Due by <span data-testid="claim-due-by">{{ dueByOf(c) }}</span></span>
            <span>Overdue <span data-testid="claim-overdue">{{ overdueOf(c) ? 'yes' : 'no' }}</span></span>
            <span>Deferred <span data-testid="claim-deferred">{{ deferredOf(c) ? 'yes' : 'no' }}</span></span>
            <span>Deferred until <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span></span>
            <span>Proofs <span data-testid="claim-proofs">{{ c.proofs }}</span></span>
            <span>Late entry <span data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' }}</span></span>
            <span>Reminded <span data-testid="claim-reminded">{{ c.reminded ?? '' }}</span></span>
            <span>Split from No. <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span></span>
            <ol [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
              @for (l of c.log; track $index) {
                <li data-testid="claim-log-line">{{ logText(l) }}</li>
              }
            </ol>
            <button type="button" data-testid="claim-open" (click)="changeStatus(c, 'open')"
              [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
            <button type="button" data-testid="claim-settle" (click)="changeStatus(c, 'settle')"
              [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
            <button type="button" data-testid="claim-proof" (click)="addProof(c)"
              [attr.aria-label]="'Add a proof to claim of ' + c.amount + ' on ' + c.date">Add proof</button>
            <button type="button" data-testid="claim-remind" (click)="remind(c)"
              [attr.aria-label]="'Remind claim of ' + c.amount + ' on ' + c.date">Remind</button>
            <label [for]="'claim-defer-date-' + c.id">Defer until</label>
            <input [id]="'claim-defer-date-' + c.id" type="date" data-testid="claim-defer-date"
              [value]="deferDates()[c.id] ?? ''" (input)="deferDateChanged(c.id, $any($event.target).value)" />
            <button type="button" data-testid="claim-defer" (click)="defer(c)"
              [attr.aria-label]="'Defer claim of ' + c.amount + ' on ' + c.date">Defer</button>
            <label [for]="'claim-date-new-' + c.id">Re-date to</label>
            <input [id]="'claim-date-new-' + c.id" type="date" data-testid="claim-date-new"
              [value]="redates()[c.id] ?? ''" (input)="redateChanged(c.id, $any($event.target).value)" />
            <button type="button" data-testid="claim-redate" (click)="redate(c)"
              [attr.aria-label]="'Re-date claim of ' + c.amount + ' on ' + c.date">Re-date</button>
            <button type="button" data-testid="claim-approve" (click)="changeStatus(c, 'approve')"
              [attr.aria-label]="'Approve claim of ' + c.amount + ' on ' + c.date">Approve</button>
            <button type="button" data-testid="claim-resettle" (click)="changeStatus(c, 'resettle')"
              [attr.aria-label]="'Re-settle claim of ' + c.amount + ' on ' + c.date">Re-settle</button>
            <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
            <input [id]="'claim-reject-reason-' + c.id" data-testid="claim-reject-reason" autocomplete="off"
              [value]="rejectReasons()[c.id] ?? ''" (input)="reasonChanged(c.id, $any($event.target).value)" />
            <button type="button" data-testid="claim-reject" (click)="reject(c)"
              [attr.aria-label]="'Reject claim of ' + c.amount + ' on ' + c.date">Reject</button>
            <button type="button" data-testid="claim-void" (click)="changeStatus(c, 'void')"
              [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
            <button type="button" data-testid="claim-edit" (click)="edit(c)"
              [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
            <label [for]="'claim-inline-amount-' + c.id">Amount</label>
            <input [id]="'claim-inline-amount-' + c.id" data-testid="claim-inline-amount" inputmode="decimal" autocomplete="off"
              [value]="inlineAmounts()[c.id] ?? ''" (input)="inlineAmountChanged(c.id, $any($event.target).value)" />
            <button type="button" data-testid="claim-inline-save" (click)="saveInline(c)"
              [attr.aria-label]="'Save amount of claim of ' + c.amount + ' on ' + c.date">Save amount</button>
            <label [for]="'claim-split-amount-' + c.id">Split off</label>
            <input [id]="'claim-split-amount-' + c.id" data-testid="claim-split-amount" inputmode="decimal" autocomplete="off"
              [value]="splitAmounts()[c.id] ?? ''" (input)="splitAmountChanged(c.id, $any($event.target).value)" />
            <button type="button" data-testid="claim-split" (click)="split(c)"
              [attr.aria-label]="'Split claim of ' + c.amount + ' on ' + c.date">Split</button>
            <label [for]="'claim-reassign-to-' + c.id">Reassign to</label>
            <select #reassignTo [id]="'claim-reassign-to-' + c.id" data-testid="claim-reassign-to"
              (change)="clearRowError(c.id)">
              @for (o of others(); track o.id) {
                <option [value]="o.id">{{ o.name }}</option>
              }
            </select>
            <button type="button" data-testid="claim-reassign" (click)="reassign(c, reassignTo.value)"
              [attr.aria-label]="'Reassign claim of ' + c.amount + ' on ' + c.date">Reassign</button>
            @if (editingId() === c.id) {
              <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
            }
            @if (rowErrors()[c.id]; as err) {
              <p role="alert" data-testid="claim-error">{{ err }}</p>
            }
          </li>
        }
      </ul>
    }
  `,
})
export class PersonClaims {
  private readonly session = inject(Session);
  private readonly api = inject(ClaimsApi);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** This person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** Every rate the sheet has, each for a grade from its from-date. */
  readonly rates = input.required<Rate[]>();
  /** Every grade this person has been given, each from its from-date. */
  readonly grades = input.required<DatedGrade[]>();
  /** The other people on the sheet, whom a claim may be reassigned to. */
  readonly others = input.required<{ id: number; name: string }[]>();
  /** The sheet's periods. */
  readonly periods = input.required<Period[]>();
  /** The ids of the claims selected for a bulk action. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** A claim's selection turned on or off. */
  readonly toggled = output<{ id: number; on: boolean }>();
  /** A claim added or saved, for the sheet's list to take in. */
  readonly saved = output<Claim>();

  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** The error of each claim's last refused status change, by claim id. */
  /** Whether a submit is waiting for its answer; a second submit meanwhile is ignored. */
  private submitting = false;
  /** The lock taken for the claim whose editor is open, settling true once it is held. */
  private locking: { id: number; result: Promise<boolean> } | null = null;
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** The reason typed for rejecting each PENDING claim, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});
  /** The date typed for deferring each OPEN claim until, by claim id. */
  protected readonly deferDates = signal<Record<number, string>>({});
  /** The new date typed for re-dating each claim, by claim id. */
  protected readonly redates = signal<Record<number, string>>({});
  /** The amount typed for splitting off each OPEN claim, by claim id. */
  protected readonly splitAmounts = signal<Record<number, string>>({});
  /** The amount typed for changing each DRAFT claim's amount in its row, by claim id. */
  protected readonly inlineAmounts = signal<Record<number, string>>({});
  protected readonly fieldId = (name: string) => `claim-form-${name}-${this.personId()}`;

  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
    corrects: new FormControl('', { nonNullable: true }),
    ref: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    // An editor this user left open (the claim is still locked by them) opens again when the
    // sheet is shown anew.
    let restored = false;
    effect(() => {
      const claims = this.claims();
      const user = this.session.user();
      if (restored) return;
      restored = true;
      const mine = claims.find((c) => c.status === 'DRAFT' && c.lockedBy !== null && c.lockedBy === user);
      if (mine) untracked(() => this.openEditor(mine));
    });
    // A change of the sheet's today releases every lock, so an open editor closes.
    let today: string | null | undefined;
    effect(() => {
      const now = this.session.today();
      if (today !== undefined && now !== today) untracked(() => this.reset());
      today = now;
    });
  }

  /** The claim's figure (its amount times the rate in force on the claim's date for the person's grade then, divided by 100, rounded
   *  by the claim's rounding policy (half up before the sheet's policy date, else half to even), or the figure a statement
   *  bound it to), with its late charge on top when it is an overdue open (OPEN or PENDING) claim, then raised to the
   *  sheet's floor in force on its date; n/a with no rate in force then. A claim settled with a due shows the due fixed then. */
  protected dueOf(c: Claim): string {
    const grade = gradeOn(this.grades(), c.date);
    const due = claimDueCents(this.rates().filter((r) => r.grade === grade), c, cents(this.session.floorOn(c.date)), {
      today: this.session.today(), latePctOn: (d) => this.session.latePctOn(d), graceOf: (o) => this.graceOf(o),
    }, roundsHalfUp(c, this.claims(), this.session.policyDate(), this.session.today()));
    return due === null ? 'n/a' : money(due);
  }

  /** A line of the claim's status log as the screen shows it. */
  protected logText(l: ClaimLogLine): string {
    return logLineText(l);
  }

  /** The claim's due-by, fixed when it was created. */
  protected dueByOf(c: Claim): string {
    return dueBy(c);
  }

  /** Whether the claim is open (OPEN or PENDING) with its due-by plus its grace days before the sheet's today. */
  protected overdueOf(c: Claim): boolean {
    return overdue(c, this.session.today(), this.graceOf(c));
  }

  /** The claim's grace days: those of the person's grade in force on its date. */
  private graceOf(c: Claim): number {
    return this.session.graceOn(gradeOn(this.grades(), c.date), c.date);
  }

  /** Whether the claim is deferred on the sheet's today. */
  protected deferredOf(c: Claim): boolean {
    return deferred(c, this.session.today());
  }

  /** The name of the period whose dates contain the claim's due-by; blank when none does. */
  protected periodOf(c: Claim): string {
    return periodOn(this.periods(), dueBy(c))?.name ?? '';
  }

  protected async edit(c: Claim): Promise<void> {
    this.error.set(null);
    this.clearRowError(c.id);
    if (c.status !== 'DRAFT') {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: 'Only a draft claim can be edited; void it and add a new one.' }));
      return;
    }
    if (periodOn(this.periods(), dueBy(c))?.closed) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: 'This claim is due in a closed period.' }));
      return;
    }
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const was = this.editingId();
    if (was !== null && was !== c.id) void this.release(sheetId, was);
    // The editor is the claim's at once, so what is typed meanwhile is never taken for a new
    // claim; it fills with the claim's values once the lock is taken, unless typed in already.
    this.editingId.set(c.id);
    this.form.markAsPristine();
    const result = this.api.lock(sheetId, c.id).then((claim) => {
      if (this.session.sheetId() !== sheetId) return false;
      this.saved.emit(claim);
      if (this.editingId() === c.id && this.form.pristine) {
        this.form.setValue({ amount: claim.amount, date: claim.date, note: claim.note, corrects: claim.corrects ?? '',
          ref: claim.ref ?? '' });
      }
      return true;
    }, (e: unknown) => {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: editError(e) }));
      return false;
    });
    this.locking = { id: c.id, result };
    await result;
  }

  private openEditor(c: Claim): void {
    this.editingId.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note, corrects: c.corrects ?? '', ref: c.ref ?? '' });
  }

  /** Close the claim's editor without saving, releasing its lock. */
  protected async cancel(): Promise<void> {
    const editing = this.editingId();
    const sheetId = this.session.sheetId();
    this.reset();
    if (editing !== null && sheetId) await this.release(sheetId, editing);
  }

  /** Release this user's lock on a claim; one already gone (today changed) leaves nothing to do. */
  private async release(sheetId: string, id: number): Promise<void> {
    try {
      const claim = await this.api.unlock(sheetId, id);
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
    } catch {
      // the lock lapses anyway when the sheet's today changes
    }
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.form.getRawValue();
    const amount = value.amount.trim();
    if (!CLAIM_AMOUNT_PATTERN.test(amount)) {
      this.error.set('Enter an amount with up to two decimals.');
      return;
    }
    const date = value.date.trim();
    if (date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date as yyyy-mm-dd.');
      return;
    }
    const corrects = value.corrects.trim();
    if (corrects !== '' && !/^\S{1,120}$/.test(corrects)) {
      this.error.set('Enter the number of the claim corrected as it is shown, without spaces.');
      return;
    }
    const correction = amount.startsWith('-');
    if (correction && corrects === '') {
      this.error.set('A negative amount must name the number of the settled claim it corrects.');
      return;
    }
    const ref = value.ref.trim();
    if (ref.length > 120) {
      this.error.set('Enter a reference of up to 120 characters.');
      return;
    }
    const form = { amount, date: date || null, note: value.note, corrects: corrects === '' ? null : corrects,
      ref: ref === '' ? null : ref };
    const editing = this.editingId();
    this.submitting = true;
    try {
      const locking = this.locking;
      if (editing !== null && locking?.id === editing && !(await locking.result)) {
        // the refusal shows on the claim; the editor it left open closes, saving nothing
        if (this.locking === locking) this.reset();
        return;
      }
      const claim = editing === null
        ? await this.api.add(sheetId, this.personId(), form)
        : await this.api.update(sheetId, editing, form);
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
      this.reset();
    } catch (e) {
      this.error.set(claimError(e, correction, ref === '' ? 'none' : editing === null ? 'add' : 'edit'));
    } finally {
      this.submitting = false;
    }
  }

  protected deferDateChanged(id: number, date: string): void {
    this.clearRowError(id);
    this.deferDates.update((ds) => ({ ...ds, [id]: date }));
  }

  /** Defer an OPEN claim until the date typed for it; a refusal shows an error on the claim. */
  protected async defer(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const until = (this.deferDates()[c.id] ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(until)) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: 'Enter a date as yyyy-mm-dd to defer the claim until.' }));
      return;
    }
    try {
      const claim = await this.api.defer(sheetId, c.id, until);
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: deferError(e) }));
    }
  }

  /** Remind an OPEN or PENDING claim; a refusal shows an error on the claim. */
  protected async remind(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const claim = await this.api.remind(sheetId, c.id);
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: remindError(e) }));
    }
  }

  protected redateChanged(id: number, date: string): void {
    this.clearRowError(id);
    this.redates.update((ds) => ({ ...ds, [id]: date }));
  }

  /** Re-date the claim to the date typed for it; a refusal shows an error on the claim. */
  protected async redate(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const date = (this.redates()[c.id] ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: 'Enter a date as yyyy-mm-dd to re-date the claim to.' }));
      return;
    }
    try {
      const claim = await this.api.redate(sheetId, c.id, date);
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: redateError(e) }));
    }
  }

  /** Add a proof to the claim; a refusal shows an error on the claim. */
  protected async addProof(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const claim = await this.api.addProof(sheetId, c.id);
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: proofError(e) }));
    }
  }

  protected reasonChanged(id: number, reason: string): void {
    this.clearRowError(id);
    this.rejectReasons.update((rs) => ({ ...rs, [id]: reason }));
  }

  /** Reject a PENDING claim with the reason typed for it; it goes back to OPEN. */
  protected async reject(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const reason = (this.rejectReasons()[c.id] ?? '').trim();
    if (reason === '') {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: 'Enter a reason for rejecting the claim.' }));
      return;
    }
    try {
      const claim = await this.api.reject(sheetId, c.id, reason);
      this.rejectReasons.update(({ [c.id]: _, ...rest }) => rest);
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: statusError(e, c.openedBy === this.session.user()) }));
    }
  }

  /** Open, settle, approve, re-settle or void a claim; a move its status does not allow shows an error on the claim. */
  protected async changeStatus(c: Claim, action: ClaimAction): Promise<void> {
    this.clearRowError(c.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const claim = await this.api.changeStatus(sheetId, c.id, action);
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
    } catch (e) {
      const err = action === 'resettle' ? resettleError(e) : statusError(e, c.openedBy === this.session.user());
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: err }));
    }
  }

  protected splitAmountChanged(id: number, amount: string): void {
    this.clearRowError(id);
    this.splitAmounts.update((as) => ({ ...as, [id]: amount }));
  }

  /** Split the amount typed for it off an OPEN claim; the original keeps the rest and the new
   *  claim is listed first. A refusal shows an error on the claim. */
  protected async split(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const amount = (this.splitAmounts()[c.id] ?? '').trim();
    if (!AMOUNT_PATTERN.test(amount)) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: 'Enter an amount with up to two decimals to split off.' }));
      return;
    }
    try {
      const { original, split } = await this.api.split(sheetId, c.id, amount);
      this.splitAmounts.update(({ [c.id]: _, ...rest }) => rest);
      if (this.session.sheetId() === sheetId) {
        this.saved.emit(original);
        this.saved.emit(split);
      }
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: splitError(e) }));
    }
  }

  protected inlineAmountChanged(id: number, amount: string): void {
    this.clearRowError(id);
    this.inlineAmounts.update((as) => ({ ...as, [id]: amount }));
  }

  /** Change a DRAFT claim's amount to the one typed in its row, keeping its date, note and what
   *  it corrects, by the claim form's rules; a refusal shows an error on the claim. */
  protected async saveInline(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const amount = (this.inlineAmounts()[c.id] ?? '').trim();
    if (!CLAIM_AMOUNT_PATTERN.test(amount)) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: 'Enter an amount with up to two decimals.' }));
      return;
    }
    try {
      const claim = await this.api.update(sheetId, c.id, { amount, date: c.date, note: c.note, corrects: c.corrects, ref: c.ref });
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: claimError(e, amount.startsWith('-')) }));
    }
  }

  /** Move a claim to another person; a refusal shows an error on the claim. */
  protected async reassign(c: Claim, personId: string): Promise<void> {
    this.clearRowError(c.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    if (!personId) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: 'Choose a person to reassign the claim to.' }));
      return;
    }
    try {
      const claim = await this.api.reassign(sheetId, c.id, Number(personId));
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: reassignError(e) }));
    }
  }

  protected clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.locking = null;
    this.form.reset({ amount: '', date: '', note: '', corrects: '', ref: '' });
    this.error.set(null);
  }
}
