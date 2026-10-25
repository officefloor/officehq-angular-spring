import { Component, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { PeriodView, periodOf } from '../periods/periods-api';
import { GradeView, gradeOn } from '../people/people-api';
import { RateView } from '../rates/rates-api';
import { SheetFloor } from '../settings/sheet-floor';
import { SheetLatePct } from '../settings/sheet-late-pct';
import { SheetPolicyDate } from '../settings/sheet-policy-date';
import { CLAIM_AMOUNT, CLAIM_NUMBER, ClaimAction, ClaimView, ClaimsApi, claimOwedCents, isOverdue, money } from './claims-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const PAST: Record<ClaimAction, string> = { open: 'opened', settle: 'settled', void: 'voided', approve: 'approved', reject: 'rejected' };

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
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input #rejectReason [id]="'claim-reject-reason-' + c.id" data-testid="claim-reject-reason" />
          <button type="button" data-testid="claim-reject" (click)="act(c, 'reject', rejectReason.value)"
            [attr.aria-label]="'Reject claim of ' + c.amount + ' on ' + c.date">Reject</button>
          <button type="button" data-testid="claim-void" (click)="act(c, 'void')"
            [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
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

  protected readonly amount = new FormControl('', { nonNullable: true });
  protected readonly date = new FormControl('', { nonNullable: true });
  protected readonly note = new FormControl('', { nonNullable: true });
  protected readonly corrects = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ amount: this.amount, date: this.date, note: this.note, corrects: this.corrects });

  protected readonly editingId = signal<number | null>(null);
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
  }

  /** What is due on the claim: the figure fixed when it was settled, else the figure its person's
   *  last statement bound it to, else at the rate in force on the claim's date of the person's grade
   *  in force on that date, and at least the sheet's floor, or n/a when the claim is dated before every rate of the grade;
   *  an open overdue claim has its late charge on top. */
  protected due(claim: ClaimView): string {
    const grade = gradeOn(this.grades(), claim.date);
    const due = claimOwedCents(claim, this.rates().filter((r) => r.grade === grade), this.floor.cents(),
      { today: this.session.sheet()?.today, latePct: this.latePct.pct(),
        policyDate: this.policyDate.date() ?? this.session.sheet()?.today }, this.claims());
    return due === null ? 'n/a' : money(due);
  }

  /** Whether the claim is open and its due-by is before the sheet's today. */
  protected overdue(claim: ClaimView): boolean {
    return isOverdue(claim, this.session.sheet()?.today);
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

  private reset(): void {
    this.editingId.set(null);
    this.error.set('');
    this.form.reset({ amount: '', date: '', note: '', corrects: '' }, { emitEvent: false });
  }

  protected edit(claim: ClaimView): void {
    this.claimError.set(null);
    this.editingId.set(claim.id);
    this.error.set('');
    this.form.setValue({ amount: claim.amount, date: claim.date, note: claim.note, corrects: claim.corrects ?? '' },
      { emitEvent: false });
  }

  /** Open, settle, void, approve or reject (for a reason) a claim; the store refuses a change its
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
          : forbidden
          ? 'You opened this claim, so someone else must settle it.'
          : periodOf(this.periods(), claim.date)?.closed
            ? `This claim is dated inside a closed period, so it cannot be ${PAST[action]}.`
            : `This claim cannot be ${PAST[action]} from its status.`;
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  /** Move a draft, open or pending claim to the chosen person; only a steward may. */
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
        if (this.editingId() === claim.id) this.reset();
        this.reassigned.emit(moved);
      }
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        const forbidden = e instanceof HttpErrorResponse && e.status === 403;
        const message = forbidden
          ? 'Only a steward can move a claim to another person.'
          : periodOf(this.periods(), claim.date)?.closed
          ? 'This claim is dated inside a closed period, so it cannot be moved.'
          : 'This claim cannot be moved to that person; a settled or voided claim cannot move.';
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  protected cancel(): void {
    this.reset();
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
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
      const conflict = e instanceof HttpErrorResponse && e.status === 409;
      const uncorrected = e instanceof HttpErrorResponse && e.status === 422;
      const editing = id === null ? undefined : this.claims().find((c) => c.id === id);
      if (conflict && editing && !this.inClosedPeriod(editing.date) && !this.inClosedPeriod(date)) {
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
