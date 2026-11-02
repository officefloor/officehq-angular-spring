import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { CLAIM_AMOUNT, Claim, ClaimAction, Claims, claimDue, dueBy, overdue } from '../../core/claims';
import { GradeFrom, gradeOn } from '../../core/people';
import { Period, periodOf } from '../../core/periods';
import { Rate } from '../../core/rates';

/** Why the server refused an action: the claim's status does not allow it. */
const REFUSALS: Record<ClaimAction, string> = {
  open: 'Only a draft claim can be opened.',
  settle: 'Only an open claim can be settled.',
  void: 'Only a draft, open or pending claim can be voided.',
  approve: 'Only a pending claim can be approved.',
  reject: 'Only a pending claim can be rejected.',
};

/** The message when an edit of a claim that is not a draft is refused. */
const NOT_DRAFT = 'Only a draft claim can be edited; void an open claim and add a new one.';

/** The message when the server refused a change to a claim dated inside a closed period. */
const CLOSED = 'The claim\'s period is closed.';
const RETIRED = 'A claim cannot be dated after the person\'s retirement.';
const CORRECTS = 'A negative amount must name the number of a settled claim of this person.';

/** The message for a refused action on a claim. */
function refusal(e: unknown, action: ClaimAction): string {
  if (e instanceof HttpErrorResponse && e.status === 423) return CLOSED;
  if (e instanceof HttpErrorResponse && e.status === 409) return REFUSALS[action];
  if (e instanceof HttpErrorResponse && e.status === 403 && action === 'settle') {
    return 'The user who opened a claim cannot settle it.';
  }
  if (e instanceof HttpErrorResponse && e.status === 403 && (action === 'approve' || action === 'reject')) {
    return action === 'approve' ? 'Only a steward who did not open the claim can approve it.'
      : 'Only a steward can reject a claim.';
  }
  if (e instanceof HttpErrorResponse && e.status === 400 && action === 'reject') {
    return 'Enter a reason for rejecting the claim.';
  }
  return 'The claim could not be changed.';
}

// A person's claims, newest first, and the form that adds a claim for them or, once a claim's
// editor is opened, edits it.
@Component({
  selector: 'app-person-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form data-testid="claim-form" [formGroup]="form" (ngSubmit)="submit()">
      <label [for]="'claim-form-amount-' + personId()">Amount</label>
      <input [id]="'claim-form-amount-' + personId()" data-testid="claim-form-amount" formControlName="amount"
        inputmode="decimal" />
      <label [for]="'claim-form-date-' + personId()">Date</label>
      <input [id]="'claim-form-date-' + personId()" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="'claim-form-note-' + personId()">Note</label>
      <input [id]="'claim-form-note-' + personId()" data-testid="claim-form-note" formControlName="note" />
      <label [for]="'claim-form-corrects-' + personId()">Corrects claim number</label>
      <input [id]="'claim-form-corrects-' + personId()" data-testid="claim-form-corrects" formControlName="corrects"
        inputmode="numeric" />
      <button type="submit" data-testid="claim-form-submit">{{ editing() === null ? 'Add claim' : 'Save claim' }}</button>
      @if (error()) {
        <p role="alert" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
    <ul data-testid="claims">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input type="checkbox" data-testid="claim-select" [checked]="selected().has(c.id)"
            (change)="selectedChange.emit({ id: c.id, selected: $any($event.target).checked })"
            [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date" />
          <span data-testid="claim-number">{{ c.number ?? '' }}</span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span>
          <span data-testid="claim-due">{{ dueOf(c) }}</span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span>
          <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span>
          <span data-testid="claim-period">{{ periodName(c) }}</span>
          <span data-testid="claim-due-by">{{ dueByOf(c) }}</span>
          <span data-testid="claim-overdue">{{ overdueOf(c) ? 'yes' : 'no' }}</span>
          <button type="button" data-testid="claim-open" (click)="act(c, 'open')"
            [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
          <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')"
            [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
          <button type="button" data-testid="claim-void" (click)="act(c, 'void')"
            [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
          <button type="button" data-testid="claim-approve" (click)="act(c, 'approve')"
            [attr.aria-label]="'Approve claim of ' + c.amount + ' on ' + c.date">Approve</button>
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input [id]="'claim-reject-reason-' + c.id" data-testid="claim-reject-reason" #reason
            (input)="clearError(c)" />
          <button type="button" data-testid="claim-reject" (click)="act(c, 'reject', reason.value)"
            [attr.aria-label]="'Reject claim of ' + c.amount + ' on ' + c.date">Reject</button>
          <button type="button" data-testid="claim-edit" (click)="open(c)"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
          @if (editing() === c.id) {
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
  private readonly api = inject(Claims);

  readonly personId = input.required<number>();
  /** This person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** The rates set on the sheet; a claim's due uses its person's grade's rate on the claim's date. */
  readonly rates = input.required<Rate[]>();
  /** The sheet's floor: the least live due a claim has. */
  readonly floor = input.required<string>();
  /** The person's grades; a claim's due uses the one in force on the claim's date. */
  readonly grades = input.required<GradeFrom[]>();
  /** The periods on the sheet; a claim belongs to the one whose days contain its date. */
  readonly periods = input.required<Period[]>();
  /** The sheet's terms: the days after its date a claim is due by. */
  readonly terms = input.required<number>();
  /** The sheet's today; a claim is overdue once its due-by is before it. */
  readonly today = input.required<string | null>();
  /** The sheet's late percent: what an overdue claim gains for every full 30 days it is overdue. */
  readonly latePct = input.required<string>();
  /** The sheet's policy date (null when blank): it decides how a claim's live figures round. */
  readonly policyDate = input.required<string | null>();
  /** The ids of the claims selected for a bulk action. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** A claim's checkbox ticked or cleared. */
  readonly selectedChange = output<{ id: number; selected: boolean }>();
  /** A claim added or saved through the form. */
  readonly saved = output<Claim>();

  /** The id of the claim whose editor is open, or null when the form adds a claim. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  /** The refusal of the last action taken on a claim's row, shown on that row. */
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

  protected dueOf(c: Claim): string {
    return claimDue(c, this.rates(), gradeOn(this.grades(), c.date), this.floor(),
      { terms: this.terms(), today: this.today(), pct: this.latePct(), policyDate: this.policyDate() }) ?? 'n/a';
  }

  protected dueByOf(c: Claim): string {
    return dueBy(c, this.terms());
  }

  protected overdueOf(c: Claim): boolean {
    return overdue(c, this.terms(), this.today());
  }

  /** The name of the period the claim belongs to; empty when it belongs to none. */
  protected periodName(c: Claim): string {
    return periodOf(this.periods(), c.date)?.name ?? '';
  }

  protected open(c: Claim): void {
    this.claimError.set(null);
    if (c.status !== 'DRAFT') {
      this.claimError.set({ id: c.id, message: NOT_DRAFT });
      return;
    }
    this.editing.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note, corrects: c.corrects ?? '' },
      { emitEvent: false });
    this.error.set('');
  }

  /** Clear the refusal shown on a claim's row once its reject reason is changed. */
  protected clearError(c: Claim): void {
    if (this.claimError()?.id === c.id) this.claimError.set(null);
  }

  protected async act(c: Claim, action: ClaimAction, reason?: string): Promise<void> {
    this.claimError.set(null);
    if (action === 'reject' && (reason ?? '').trim() === '') {
      this.claimError.set({ id: c.id, message: refusal(new HttpErrorResponse({ status: 400 }), action) });
      return;
    }
    try {
      this.saved.emit(await this.api.act(c.id, action, reason?.trim()));
    } catch (e) {
      this.claimError.set({
        id: c.id,
        message: refusal(e, action),
      });
    }
  }

  protected cancel(): void {
    this.reset();
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    this.claimError.set(null);
    const { amount, date, note, corrects } = this.form.getRawValue();
    if (!CLAIM_AMOUNT.test(amount.trim())) {
      this.error.set('Enter an amount with up to two decimals; a negative amount is a correction.');
      return;
    }
    const number = corrects.trim();
    if (number.length > 120) {
      this.error.set(CORRECTS);
      return;
    }
    const day = date.trim();
    if (day !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(day)) {
      this.error.set('Enter a date, or leave it blank for the sheet\'s today.');
      return;
    }
    const form = {
      amount: amount.trim(), date: day === '' ? null : day, note, corrects: number === '' ? null : number,
    };
    const id = this.editing();
    this.submitting = true;
    try {
      const claim = id === null ? await this.api.add(this.personId(), form) : await this.api.edit(id, form);
      this.saved.emit(claim);
      this.reset();
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 423) this.error.set(CLOSED);
      else if (e instanceof HttpErrorResponse && e.status === 422) this.error.set(RETIRED);
      else if (e instanceof HttpErrorResponse && e.status === 400 && amount.trim().startsWith('-')) this.error.set(CORRECTS);
      else if (id !== null && e instanceof HttpErrorResponse && e.status === 409) {
        this.claimError.set({ id, message: NOT_DRAFT });
      }
      else this.error.set(id === null ? 'The claim could not be added.' : 'The claim could not be saved.');
    } finally {
      this.submitting = false;
    }
  }

  private reset(): void {
    this.editing.set(null);
    this.form.reset({ amount: '', date: '', note: '', corrects: '' }, { emitEvent: false });
    this.error.set('');
  }
}
