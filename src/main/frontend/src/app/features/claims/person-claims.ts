import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { PeriodView, periodOf } from '../periods/periods-api';
import { GradeView, gradeOn } from '../people/people-api';
import { RateView } from '../rates/rates-api';
import { SheetFloor } from '../settings/sheet-floor';
import { SheetLatePct } from '../settings/sheet-late-pct';
import { SheetPolicyDate } from '../settings/sheet-policy-date';
import { ClaimEditors } from './claim-editors';
import { CLAIM_AMOUNT, CLAIM_NUMBER, ClaimAction, ClaimView, ClaimsApi, SplitView, claimOwedCents, isDeferred, isOverdue, money } from './claims-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;
/** The claim form's fields, as typed. */
type ClaimFields = { amount: string; date: string; note: string; corrects: string };
const PAST: Record<ClaimAction, string> = { open: 'opened', settle: 'settled', void: 'voided', approve: 'approved', reject: 'rejected', resettle: 're-settled' };

// One person's claims, newest first, with one form to add a claim or edit one.
@Component({
  selector: 'app-person-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()"
      [attr.aria-label]="(editingId() === null ? 'Add a claim for ' : 'Edit a claim of ') + personName()">
      <label [for]="fieldId('amount')">Amount</label>
      <input [id]="fieldId('amount')" data-testid="claim-form-amount" formControlName="amount" inputmode="decimal" />
      <label [for]="fieldId('date')">Date</label>
      <input [id]="fieldId('date')" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="fieldId('note')">Note</label>
      <input [id]="fieldId('note')" data-testid="claim-form-note" formControlName="note" />
      <label [for]="fieldId('corrects')">Corrects claim no.</label>
      <input [id]="fieldId('corrects')" data-testid="claim-form-corrects" formControlName="corrects" />
      <button type="submit" data-testid="claim-form-submit" [disabled]="saving()">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error()) {
        <p role="alert" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
    <ul [attr.aria-label]="'Claims of ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input type="checkbox" data-testid="claim-select" [checked]="selected().has(c.id)"
            (change)="selectedChange.emit({ id: c.id, on: $any($event.target).checked })"
            [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date" />
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
          <span>Due <span data-testid="claim-due">{{ due(c) }}</span></span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periodName(c) }}</span></span>
          <span>Due by <span data-testid="claim-due-by">{{ c.dueBy }}</span></span>
          <span>Overdue <span data-testid="claim-overdue">{{ overdue(c) ? 'yes' : 'no' }}</span></span>
          <span>Deferred <span data-testid="claim-deferred">{{ deferred(c) ? 'yes' : 'no' }}</span></span>
          <span>Deferred until <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span></span>
          <span>Proofs <span data-testid="claim-proofs">{{ c.proofs }}</span></span>
          <span>Split from <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span></span>
          <ol [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
            @for (line of c.log; track $index) {
              <li data-testid="claim-log-line">{{ line.status }} by {{ line.by }} on {{ line.on }} due={{ line.due ?? 'n/a' }}</li>
            }
          </ol>
          <button type="button" data-testid="claim-open" (click)="act(c, 'open')"
            [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
          <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')"
            [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
          <button type="button" data-testid="claim-approve" (click)="act(c, 'approve')"
            [attr.aria-label]="'Approve claim of ' + c.amount + ' on ' + c.date">Approve</button>
          <button type="button" data-testid="claim-resettle" (click)="act(c, 'resettle')"
            [attr.aria-label]="'Re-settle claim of ' + c.amount + ' on ' + c.date">Re-settle</button>
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input #rejectReason [id]="'claim-reject-reason-' + c.id" data-testid="claim-reject-reason" />
          <button type="button" data-testid="claim-reject" (click)="act(c, 'reject', rejectReason.value)"
            [attr.aria-label]="'Reject claim of ' + c.amount + ' on ' + c.date">Reject</button>
          <button type="button" data-testid="claim-void" (click)="act(c, 'void')"
            [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
          <label [for]="'claim-defer-date-' + c.id">Defer until</label>
          <input #deferDate type="date" [id]="'claim-defer-date-' + c.id" data-testid="claim-defer-date" />
          <button type="button" data-testid="claim-defer" (click)="defer(c, deferDate.value)"
            [attr.aria-label]="'Defer claim of ' + c.amount + ' on ' + c.date">Defer</button>
          <label [for]="'claim-date-new-' + c.id">Re-date to</label>
          <input #newDate type="date" [id]="'claim-date-new-' + c.id" data-testid="claim-date-new" />
          <button type="button" data-testid="claim-redate" (click)="redate(c, newDate.value)"
            [attr.aria-label]="'Re-date claim of ' + c.amount + ' on ' + c.date">Re-date</button>
          <button type="button" data-testid="claim-proof" (click)="addProof(c)"
            [attr.aria-label]="'Add a proof to claim of ' + c.amount + ' on ' + c.date">Add proof</button>
          <label [for]="'claim-split-amount-' + c.id">Split off</label>
          <input #splitAmount [id]="'claim-split-amount-' + c.id" data-testid="claim-split-amount" inputmode="decimal" />
          <button type="button" data-testid="claim-split" (click)="splitClaim(c, splitAmount.value)"
            [attr.aria-label]="'Split claim of ' + c.amount + ' on ' + c.date">Split</button>
          <label [for]="'claim-reassign-to-' + c.id">Move to</label>
          <select #reassignTo [id]="'claim-reassign-to-' + c.id" data-testid="claim-reassign-to">
            <option value="">Choose a person</option>
            @for (o of people(); track o.id) {
              @if (o.id !== personId()) {
                <option [value]="o.id">{{ o.name }}</option>
              }
            }
          </select>
          <button type="button" data-testid="claim-reassign" (click)="reassign(c, reassignTo.value)"
            [attr.aria-label]="'Move claim of ' + c.amount + ' on ' + c.date + ' to another person'">Move</button>
          <button type="button" data-testid="claim-edit" (click)="edit(c)"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
          @if (editingId() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
          }
          @if (claimError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ claimError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class PersonClaims {
  private readonly session = inject(Session);
  private readonly api = inject(ClaimsApi);
  private readonly editors = inject(ClaimEditors);
  private readonly floor = inject(SheetFloor);
  private readonly latePct = inject(SheetLatePct);
  private readonly policyDate = inject(SheetPolicyDate);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's claims, newest first. */
  readonly claims = input.required<ClaimView[]>();
  /** Everyone on the sheet, whom a claim can be moved to. */
  readonly people = input.required<readonly { id: number; name: string }[]>();
  /** The sheet's rates, whatever their grades and from-dates. */
  readonly rates = input.required<RateView[]>();
  /** The person's grades, each from its from-date. */
  readonly grades = input.required<GradeView[]>();
  /** The sheet's periods in date order. */
  readonly periods = input.required<PeriodView[]>();
  /** The ids of the claims selected on the sheet. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** A claim's checkbox ticked or cleared. */
  readonly selectedChange = output<{ id: number; on: boolean }>();
  readonly added = output<ClaimView>();
  readonly saved = output<ClaimView>();
  /** A claim whose status changed. */
  readonly changed = output<ClaimView>();
  /** A claim moved to another person. */
  readonly reassigned = output<ClaimView>();
  /** A claim split in two. */
  readonly split = output<SplitView>();

  protected readonly amount = new FormControl('', { nonNullable: true });
  protected readonly date = new FormControl('', { nonNullable: true });
  protected readonly note = new FormControl('', { nonNullable: true });
  protected readonly corrects = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ amount: this.amount, date: this.date, note: this.note, corrects: this.corrects });

  /** The claim the editor has open, or null when the form adds a claim; it stays open while the sheet
   *  is re-loaded. */
  protected readonly editingId = computed(() => {
    const sheet = this.session.sheet();
    return sheet ? this.editors.editing(sheet.id, this.personId()) : null;
  });
  /** The claim whose values the form was last filled with. */
  private filledFor: number | null = null;
  /** The last edit button's open of the editor, settled once the store has answered it. */
  private opening: Promise<void> = Promise.resolve();
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  /** The refusal of the last action taken on a claim's row, shown on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);

  constructor() {
    this.form.valueChanges.subscribe(() => {
      this.error.set('');
      // A refused save shown on the claim being edited belongs to the form too.
      if (this.editingId() !== null && this.claimError()?.id === this.editingId()) this.claimError.set(null);
    });
    // An editor left open when the sheet was re-loaded is filled again once its claim is shown.
    effect(() => {
      const id = this.editingId();
      if (id === null || id === this.filledFor) return;
      const claim = this.claims().find((c) => c.id === id);
      if (claim) this.fill(claim);
    });
  }

  /** What is due on the claim: the figure fixed when it was settled, else the figure its person's
   *  last statement bound it to, else at the rate in force on the claim's date of the person's grade
   *  in force on that date, and at least the sheet's floor, or n/a when the claim is dated before every rate of the grade;
   *  an open overdue claim has its late charge on top. */
  protected due(claim: ClaimView): string {
    const grade = gradeOn(this.grades(), claim.date);
    const due = claimOwedCents(claim, this.rates().filter((r) => r.grade === grade), this.floor.centsOn(claim.date),
      { today: this.session.sheet()?.today, latePct: (date: string) => this.latePct.pctOn(date),
        policyDate: this.policyDate.date() ?? this.session.sheet()?.today }, this.claims());
    return due === null ? 'n/a' : money(due);
  }

  /** Whether the claim is open and its due-by is before the sheet's today. */
  protected overdue(claim: ClaimView): boolean {
    return isOverdue(claim, this.session.sheet()?.today);
  }

  /** Whether the claim is OPEN and deferred until a date the sheet's today has not reached. */
  protected deferred(claim: ClaimView): boolean {
    return isDeferred(claim, this.session.sheet()?.today);
  }

  /** The name of the period whose dates contain the claim's date, or blank when none does. */
  protected periodName(claim: ClaimView): string {
    return periodOf(this.periods(), claim.date)?.name ?? '';
  }

  private inClosedPeriod(date: string): boolean {
    return date !== '' && (periodOf(this.periods(), date)?.closed ?? false);
  }

  protected fieldId(field: string): string {
    return `claim-form-${field}-${this.personId()}`;
  }

  private setEditing(claimId: number | null): void {
    const sheet = this.session.sheet();
    if (sheet) this.editors.set(sheet.id, this.personId(), claimId);
  }

  private reset(): void {
    this.setEditing(null);
    this.filledFor = null;
    this.error.set('');
    this.form.reset({ amount: '', date: '', note: '', corrects: '' }, { emitEvent: false });
  }

  private fill(claim: ClaimView): void {
    this.filledFor = claim.id;
    this.form.setValue({ amount: claim.amount, date: claim.date, note: claim.note, corrects: claim.corrects ?? '' },
      { emitEvent: false });
  }

  /** Fill the fields of the form not typed in since the editor was opened with the claim's values. */
  private fillUntyped(claim: ClaimView): void {
    const values = { amount: claim.amount, date: claim.date, note: claim.note, corrects: claim.corrects ?? '' };
    for (const [key, control] of Object.entries(this.form.controls)) {
      if (control.pristine) control.setValue(values[key as keyof typeof values], { emitEvent: false });
    }
  }

  /** Release the user's lock on a claim whose editor is closed unsaved; a lock already gone is no matter. */
  private release(sheetId: string, claimId: number): void {
    this.api.unlock(sheetId, claimId).catch(() => undefined);
  }

  /** Open the claim's editor, locking the claim for other users until it is saved or cancelled, or the
   *  sheet's today changes. A claim that cannot be edited (not a draft, or dated inside a closed period)
   *  is refused and takes no lock, as is a viewer's edit. A claim another user's editor locks is refused
   *  too, the editor showing its values without the lock; its save is refused while the lock holds. */
  protected async edit(claim: ClaimView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    if (this.inClosedPeriod(claim.date)) {
      this.claimError.set({ id: claim.id, message: 'This claim is dated inside a closed period, so it cannot be edited.' });
      return;
    }
    if (claim.status !== 'DRAFT') {
      this.claimError.set({ id: claim.id, message: 'Only a draft claim can be edited. To change an open claim, void it and add a new one.' });
      return;
    }
    // The editor is on the claim at once, so what is typed before the store answers is kept; its
    // fields are filled once the store has answered, and a save waits for that.
    const was = this.editingId();
    const typed = this.form.getRawValue();
    const filled = this.filledFor;
    this.setEditing(claim.id);
    this.filledFor = claim.id;
    this.error.set('');
    this.form.reset({ amount: '', date: '', note: '', corrects: '' }, { emitEvent: false });
    this.opening = this.lock(sheet.id, claim, was, typed, filled);
    await this.opening;
  }

  private async lock(sheetId: string, claim: ClaimView, was: number | null,
                     typed: ClaimFields, filled: number | null): Promise<void> {
    try {
      const locked = await this.api.lock(sheetId, claim.id);
      if (this.session.sheet()?.id !== sheetId) return;
      if (was !== null && was !== claim.id) this.release(sheetId, was);
      this.fillUntyped(locked);
    } catch (e) {
      if (this.session.sheet()?.id !== sheetId) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      if (status === 423) {
        if (was !== null && was !== claim.id) this.release(sheetId, was);
        this.fillUntyped(claim);
        this.claimError.set({ id: claim.id, message: 'Someone else is editing this claim, so it cannot be edited until they save or cancel.' });
        return;
      }
      // Refused: the editor is back as it was.
      this.setEditing(was);
      this.filledFor = filled;
      this.form.setValue(typed, { emitEvent: false });
      const message = status === 403
        ? 'Your role may not edit a claim on this sheet.'
        : periodOf(this.periods(), claim.date)?.closed
        ? 'This claim is dated inside a closed period, so it cannot be edited.'
        : 'Only a draft claim can be edited. To change an open claim, void it and add a new one.';
      this.claimError.set({ id: claim.id, message });
    }
  }

  /** Open, settle, void, approve, reject (for a reason) or re-settle a claim; the store refuses a change its
   *  status does not allow. A clerk's settling above the clerk limit leaves the claim PENDING. */
  protected async act(claim: ClaimView, action: ClaimAction, reason = ''): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    if (action === 'reject' && reason.trim() === '') {
      this.claimError.set({ id: claim.id, message: 'Type a reason to reject this claim.' });
      return;
    }
    try {
      const changed = await this.api.act(sheet.id, claim.id, action, reason.trim());
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(changed);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        const forbidden = e instanceof HttpErrorResponse && e.status === 403;
        const message = forbidden && (action === 'approve' || action === 'reject')
          ? `Only a steward who did not open this claim can ${action} it.`
          : forbidden && action === 'resettle'
          ? 'Only a steward can re-settle this claim.'
          : forbidden && action === 'void' && claim.status === 'SETTLED'
          ? 'Only a steward can void a settled claim.'
          : forbidden && action === 'settle' && claim.openedBy === this.session.user()
          ? 'You opened this claim, so someone else must settle it.'
          : forbidden
          ? `Your role may not ${action} a claim on this sheet.`
          : periodOf(this.periods(), claim.date)?.closed
            ? `This claim is dated inside a closed period, so it cannot be ${PAST[action]}.`
          : action === 'settle' && this.deferred(claim)
            ? `This claim is deferred until ${claim.deferredUntil}, so it cannot be settled yet.`
          : claim.proofs === 0 && ((action === 'settle' && claim.status === 'OPEN') || (action === 'approve' && claim.status === 'PENDING'))
            ? `This claim's due is above the proof threshold, so it needs a proof before it can be ${PAST[action]}.`
          : action === 'void' && claim.status === 'SETTLED'
            ? 'A settled claim can only be voided within 7 days of its settlement.'
            : `This claim cannot be ${PAST[action]} from its status.`;
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Defer an open claim until a date after the sheet's today; the store refuses a claim that is not
   *  OPEN, a date not after today, and a claim dated inside a closed period. */
  protected async defer(claim: ClaimView, until: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    if (!DATE.test(until.trim())) {
      this.claimError.set({ id: claim.id, message: 'Type the date to defer this claim until as yyyy-mm-dd.' });
      return;
    }
    try {
      const deferred = await this.api.defer(sheet.id, claim.id, until.trim());
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(deferred);
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        const message = periodOf(this.periods(), claim.date)?.closed
          ? 'This claim is dated inside a closed period, so it cannot be deferred.'
          : claim.status !== 'OPEN'
          ? 'Only an OPEN claim can be deferred.'
          : 'A claim can only be deferred to a date after the sheet\'s today.';
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Re-date an OPEN or PENDING claim; only a steward may, and the store refuses another status, a claim
   *  dated or a new date inside a closed period, and a new date after the person's retirement. */
  protected async redate(claim: ClaimView, date: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    const dated = date.trim();
    if (!DATE.test(dated)) {
      this.claimError.set({ id: claim.id, message: 'Type the new date of this claim as yyyy-mm-dd.' });
      return;
    }
    try {
      const redated = await this.api.redate(sheet.id, claim.id, dated);
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(redated);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        const forbidden = e instanceof HttpErrorResponse && e.status === 403;
        const message = forbidden
          ? 'Only a steward can re-date a claim.'
          : this.inClosedPeriod(claim.date)
          ? 'This claim is dated inside a closed period, so it cannot be re-dated.'
          : this.inClosedPeriod(dated)
          ? 'The new date is inside a closed period.'
          : !(claim.status === 'OPEN' || claim.status === 'PENDING')
          ? 'Only an OPEN or PENDING claim can be re-dated.'
          : `The new date is after ${this.personName()} retired.`;
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Raise a claim's proofs count by one; the store refuses a SETTLED or VOID claim and a claim dated
   *  inside a closed period. */
  protected async addProof(claim: ClaimView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    try {
      const proven = await this.api.addProof(sheet.id, claim.id);
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(proven);
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        const message = periodOf(this.periods(), claim.date)?.closed
          ? 'This claim is dated inside a closed period, so no proof can be added.'
          : `A ${claim.status} claim cannot have a proof added.`;
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Split an amount off an OPEN claim into a new OPEN claim; the store refuses a claim that is not OPEN,
   *  a deferred claim, a claim dated inside a closed period, and an amount not between 0.00 and the claim's. */
  protected async splitClaim(claim: ClaimView, amount: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    const part = amount.trim();
    if (!/^\d+(\.\d{1,2})?$/.test(part) || Number(part) <= 0) {
      this.claimError.set({ id: claim.id, message: 'Type an amount to split off, more than 0.00 and less than the claim\'s.' });
      return;
    }
    try {
      const split = await this.api.split(sheet.id, claim.id, part);
      if (this.session.sheet()?.id === sheet.id) this.split.emit(split);
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        const message = periodOf(this.periods(), claim.date)?.closed
          ? 'This claim is dated inside a closed period, so it cannot be split.'
          : claim.status !== 'OPEN'
          ? 'Only an OPEN claim can be split.'
          : this.deferred(claim)
          ? `This claim is deferred until ${claim.deferredUntil}, so it cannot be split.`
          : 'The amount split off must be more than 0.00 and less than the claim\'s amount.';
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Move a draft, open or pending claim to the chosen person; only a role the permission matrix allows may. */
  protected async reassign(claim: ClaimView, to: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    if (to === '') {
      this.claimError.set({ id: claim.id, message: 'Choose the person to move this claim to.' });
      return;
    }
    try {
      const moved = await this.api.reassign(sheet.id, claim.id, Number(to));
      if (this.session.sheet()?.id === sheet.id) {
        if (this.editingId() === claim.id) this.cancel();
        this.reassigned.emit(moved);
      }
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        const forbidden = e instanceof HttpErrorResponse && e.status === 403;
        const message = forbidden
          ? 'Your role may not move a claim to another person on this sheet.'
          : periodOf(this.periods(), claim.date)?.closed
          ? 'This claim is dated inside a closed period, so it cannot be moved.'
          : 'This claim cannot be moved to that person; a settled or voided claim cannot move.';
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Close the editor unsaved, releasing the user's lock on its claim. */
  protected cancel(): void {
    const sheet = this.session.sheet();
    const id = this.editingId();
    this.reset();
    if (sheet && id !== null) this.release(sheet.id, id);
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    await this.opening;
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    this.claimError.set(null);
    const value = this.form.getRawValue();
    const amount = value.amount.trim();
    const date = value.date.trim();
    const corrects = value.corrects.trim();
    if (!CLAIM_AMOUNT.test(amount)) {
      this.error.set('Type an amount with up to two decimals; a negative amount is a correction.');
      return;
    }
    const correction = amount.startsWith('-') && Number(amount) !== 0;
    if (correction && !CLAIM_NUMBER.test(corrects)) {
      this.error.set('A negative amount is a correction: type the number of the settled claim it corrects.');
      return;
    }
    if (date !== '' && !DATE.test(date)) {
      this.error.set('Type a date as yyyy-mm-dd.');
      return;
    }
    const form = { amount, date: date === '' ? null : date, note: value.note.trim(),
      corrects: correction ? corrects : null };
    const id = this.editingId();
    this.saving.set(true);
    try {
      if (id === null) {
        const claim = await this.api.add(sheet.id, this.personId(), form);
        if (this.session.sheet()?.id === sheet.id) this.added.emit(claim);
      } else {
        const claim = await this.api.update(sheet.id, id, form);
        if (this.session.sheet()?.id === sheet.id) this.saved.emit(claim);
      }
      if (this.session.sheet()?.id === sheet.id) this.reset();
    } catch (e) {
      const locked = e instanceof HttpErrorResponse && e.status === 423;
      const conflict = e instanceof HttpErrorResponse && e.status === 409;
      const uncorrected = e instanceof HttpErrorResponse && e.status === 422;
      const editing = id === null ? undefined : this.claims().find((c) => c.id === id);
      if (locked && editing) {
        // Another user's editor locks the claim; the refusal is shown on the claim, and the editor stays open.
        this.claimError.set({ id: editing.id, message: 'Someone else is editing this claim, so it cannot be saved until they save or cancel.' });
      } else if (conflict && editing && !this.inClosedPeriod(editing.date) && !this.inClosedPeriod(date)) {
        // Only a draft can be edited; the refusal is shown on the claim, and the editor stays open.
        this.claimError.set({ id: editing.id, message: 'Only a draft claim can be edited. To change an open claim, void it and add a new one.' });
      } else {
        this.error.set(uncorrected
          ? 'A correction must name, by number, a settled claim of this person.'
          : conflict
          ? 'A claim dated inside a closed period cannot be added or changed.'
          : id === null ? 'The claim could not be added.' : 'The claim could not be saved.');
      }
    } finally {
      this.saving.set(false);
    }
  }
}
