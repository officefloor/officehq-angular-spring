import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { CLAIM_AMOUNT_PATTERN, ClaimStatus, ClaimView, Claims, cents, countsWithHolds, isDeferred, isOverdue, money, roundingDate } from '../../core/claims';
import { Grade, HoldView, PersonView, gradeOn } from '../../core/people';
import { PeriodView, periodOn } from '../../core/periods';
import { DEFAULT_DUE_TERMS, NOT_AVAILABLE, RateView, claimDueCents } from '../../core/rates';
import { Session } from '../../core/session';
import { Sheets } from '../../core/sheet';

const NO_RATES: readonly RateView[] = [];

// One person's claims: their claimed total, one form to add a claim or edit an existing one, and
// the claims listed newest first.
@Component({
  selector: 'app-person-claims',
  imports: [ReactiveFormsModule],
  template: `
    <p>Claimed: <span data-testid="person-claimed">{{ claimed() }}</span></p>

    <form [formGroup]="form" (ngSubmit)="submit()"
          [attr.aria-label]="(editingId() === null ? 'Add a claim for ' : 'Edit a claim of ') + personName()">
      <label [for]="fieldId('amount')">Amount</label>
      <input [id]="fieldId('amount')" data-testid="claim-form-amount" formControlName="amount"
             inputmode="decimal" autocomplete="off" />
      <label [for]="fieldId('date')">Date</label>
      <input [id]="fieldId('date')" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="fieldId('note')">Note</label>
      <input [id]="fieldId('note')" data-testid="claim-form-note" formControlName="note" autocomplete="off" />
      <label [for]="fieldId('corrects')">Corrects claim No.</label>
      <input [id]="fieldId('corrects')" data-testid="claim-form-corrects" formControlName="corrects"
             inputmode="numeric" autocomplete="off" />
      <button type="submit" data-testid="claim-form-submit" [disabled]="saving()">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>

    <ul [attr.aria-label]="'Claims of ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input type="checkbox" data-testid="claim-select" [checked]="selected().has(c.id)"
                 (change)="selectToggled.emit({ id: c.id, selected: $any($event.target).checked })"
                 [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date" />
          No. <span data-testid="claim-number">{{ c.number ?? '' }}</span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          on <span data-testid="claim-date">{{ c.date }}</span>
          — due <span data-testid="claim-due">{{ dueOf(c) }}</span>
          by <span data-testid="claim-due-by">{{ dueByOf(c) }}</span>
          — overdue <span data-testid="claim-overdue">{{ overdueOf(c) ? 'yes' : 'no' }}</span>
          in period <span data-testid="claim-period">{{ periodOf(c) }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          corrects No. <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span>
          <span data-testid="claim-status">{{ c.status }}</span>
          opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span>
          — stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span>
          — deferred <span data-testid="claim-deferred">{{ deferredOf(c) ? 'yes' : 'no' }}</span>
          until <span data-testid="claim-deferred-until">{{ deferredOf(c) ? c.deferredUntil : '' }}</span>
          <label [for]="'claim-defer-date-' + c.id">Defer until</label>
          <input [id]="'claim-defer-date-' + c.id" type="date" data-testid="claim-defer-date"
                 [value]="deferDates()[c.id] ?? ''" (input)="deferDateChanged(c, $any($event.target).value)" />
          <button type="button" data-testid="claim-defer" (click)="defer(c)"
                  [attr.aria-label]="'Defer claim of ' + c.amount + ' on ' + c.date">Defer</button>
          <button type="button" data-testid="claim-open" (click)="changeStatus(c, 'OPEN')"
                  [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
          <button type="button" data-testid="claim-settle" (click)="changeStatus(c, 'SETTLED')"
                  [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
          <button type="button" data-testid="claim-approve" (click)="approve(c)"
                  [attr.aria-label]="'Approve claim of ' + c.amount + ' on ' + c.date">Approve</button>
          <button type="button" data-testid="claim-resettle" (click)="resettle(c)"
                  [attr.aria-label]="'Re-settle claim of ' + c.amount + ' on ' + c.date">Re-settle</button>
          <label [for]="'claim-reject-reason-' + c.id">Reason to reject</label>
          <input [id]="'claim-reject-reason-' + c.id" data-testid="claim-reject-reason" autocomplete="off"
                 [value]="rejectReasons()[c.id] ?? ''" (input)="reasonChanged(c, $any($event.target).value)" />
          <button type="button" data-testid="claim-reject" (click)="reject(c)"
                  [attr.aria-label]="'Reject claim of ' + c.amount + ' on ' + c.date">Reject</button>
          <button type="button" data-testid="claim-void" (click)="changeStatus(c, 'VOID')"
                  [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
          <label [for]="'claim-reassign-to-' + c.id">Move to</label>
          <select [id]="'claim-reassign-to-' + c.id" data-testid="claim-reassign-to"
                  [value]="reassignTo()[c.id] ?? ''" (change)="reassignToChanged(c, $any($event.target).value)">
            <option value="">Choose a person</option>
            @for (o of others(); track o.id) {
              <option [value]="o.id">{{ o.name }}</option>
            }
          </select>
          <button type="button" data-testid="claim-reassign" (click)="reassign(c)"
                  [attr.aria-label]="'Move claim of ' + c.amount + ' on ' + c.date + ' to another person'">Move</button>
          @if (editingId() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
          } @else {
            <button type="button" data-testid="claim-edit" (click)="edit(c)"
                    [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
          }
          <ol [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
            @for (l of c.log; track $index) {
              <li data-testid="claim-log-line">{{ l.status }} by {{ l.by }} on {{ l.on }} due={{ l.due ?? notAvailable }}</li>
            }
          </ol>
          @if (claimError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ claimError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class PersonClaims {
  private readonly sheets = inject(Sheets);
  private readonly session = inject(Session);
  private readonly api = inject(Claims);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** This person's claims, newest first. */
  readonly claims = input.required<ClaimView[]>();
  /** The rates of each grade, in the order they take effect. */
  readonly ratesByGrade = input.required<ReadonlyMap<Grade, readonly RateView[]>>();
  /** This person's grades: a claim is at the one in force on its date. */
  readonly grades = input.required<Pick<PersonView, 'grade' | 'grades'>>();
  /** The sheet's periods, by from-date. */
  readonly periods = input.required<readonly PeriodView[]>();
  /** The other people on the sheet a claim can be moved to. */
  readonly others = input<readonly Pick<PersonView, 'id' | 'name'>[]>([]);
  /** The person's holds: their open claims dated within one, and not bound by a statement, count in no total. */
  readonly holds = input<readonly HoldView[]>([]);
  /** The ids of the claims selected for a bulk action. */
  readonly selected = input<ReadonlySet<number>>(new Set());
  readonly saved = output<ClaimView>();
  readonly selectToggled = output<{ id: number; selected: boolean }>();

  protected readonly notAvailable = NOT_AVAILABLE;
  protected readonly claimed = computed(() => money(this.claims().filter(countsWithHolds(this.holds(), this.sheets.current()?.today ?? '')).reduce((sum, c) => sum + cents(c.amount), 0)));
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  /** The error of the last action taken on a claim's row, until the next action on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  /** The person chosen to move each claim to, by claim id. */
  protected readonly reassignTo = signal<Readonly<Record<number, string>>>({});
  /** The date typed to defer each OPEN claim until, by id. */
  protected readonly deferDates = signal<Readonly<Record<number, string>>>({});
  /** The reason typed to reject each PENDING claim, by id. */
  protected readonly rejectReasons = signal<Readonly<Record<number, string>>>({});

  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
    corrects: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected dueOf(c: ClaimView): string {
    const rates = this.ratesByGrade().get(gradeOn(this.grades(), c.date)) ?? NO_RATES;
    const due = claimDueCents(c, rates, this.sheets.current() ?? DEFAULT_DUE_TERMS, roundingDate(c, this.claims()));
    return due === null ? NOT_AVAILABLE : money(due);
  }

  protected dueByOf(c: ClaimView): string {
    return c.dueBy;
  }

  protected overdueOf(c: ClaimView): boolean {
    const sheet = this.sheets.current();
    return sheet !== null && isOverdue(c, sheet.today);
  }

  protected deferredOf(c: ClaimView): boolean {
    const sheet = this.sheets.current();
    return sheet !== null && isDeferred(c, sheet.today);
  }

  protected deferDateChanged(c: ClaimView, date: string): void {
    this.deferDates.update((dates) => ({ ...dates, [c.id]: date }));
    if (this.claimError()?.id === c.id) this.claimError.set(null);
  }

  protected defer(c: ClaimView): void {
    this.claimError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const until = (this.deferDates()[c.id] ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(until)) {
      this.claimError.set({ id: c.id, message: 'Choose the date to defer the claim until.' });
      return;
    }
    this.api.defer(sheet.id, c.id, until).subscribe({
      next: (saved) => {
        this.deferDates.update((dates) => ({ ...dates, [c.id]: '' }));
        this.saved.emit(saved);
      },
      error: (err: unknown) => this.claimError.set({
        id: c.id,
        message: err instanceof HttpErrorResponse && err.status === 423
          ? 'This claim is dated inside a closed period.'
          : err instanceof HttpErrorResponse && err.status === 400
            ? `A claim is deferred to a day after today (${sheet.today}).`
            : `A ${c.status} claim cannot be deferred; only an OPEN one can.`,
      }),
    });
  }

  protected periodOf(c: ClaimView): string {
    return periodOn(this.periods(), c.date)?.name ?? '';
  }

  protected changeStatus(c: ClaimView, status: ClaimStatus): void {
    this.claimError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.api.changeStatus(sheet.id, c.id, status).subscribe({
      next: (saved) => this.saved.emit(saved),
      error: (err: unknown) => this.claimError.set({
        id: c.id,
        message: err instanceof HttpErrorResponse && err.status === 403
          ? 'You opened this claim, so someone else must settle it.'
          : err instanceof HttpErrorResponse && err.status === 423
            ? 'This claim is dated inside a closed period.'
            : status === 'SETTLED' && this.deferredOf(c)
              ? `This claim is deferred until ${c.deferredUntil}, so it cannot be settled before then.`
              : `A ${c.status} claim cannot become ${status}.`,
      }),
    });
  }

  protected approve(c: ClaimView): void {
    this.claimError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.api.approve(sheet.id, c.id).subscribe({
      next: (saved) => this.saved.emit(saved),
      error: (err: unknown) => this.claimError.set({
        id: c.id,
        message: err instanceof HttpErrorResponse && err.status === 403
          ? c.openedBy === this.session.user()
            ? 'You opened this claim, so another steward must approve it.'
            : 'Only a steward can approve a claim.'
          : this.refusal(err, c, 'approved'),
      }),
    });
  }

  protected resettle(c: ClaimView): void {
    this.claimError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.api.resettle(sheet.id, c.id).subscribe({
      next: (saved) => this.saved.emit(saved),
      error: (err: unknown) => this.claimError.set({
        id: c.id,
        message: err instanceof HttpErrorResponse && err.status === 403
          ? 'Only a steward can re-settle a claim.'
          : err instanceof HttpErrorResponse && err.status === 423
            ? 'This claim is dated inside a closed period.'
            : `A ${c.status} claim cannot be re-settled; only a SETTLED one can.`,
      }),
    });
  }

  protected reasonChanged(c: ClaimView, reason: string): void {
    this.rejectReasons.update((reasons) => ({ ...reasons, [c.id]: reason }));
    if (this.claimError()?.id === c.id) this.claimError.set(null);
  }

  protected reject(c: ClaimView): void {
    this.claimError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const reason = (this.rejectReasons()[c.id] ?? '').trim().replace(/\s+/g, ' ');
    if (reason === '') {
      this.claimError.set({ id: c.id, message: 'Type why the claim is rejected.' });
      return;
    }
    this.api.reject(sheet.id, c.id, reason).subscribe({
      next: (saved) => {
        this.rejectReasons.update((reasons) => ({ ...reasons, [c.id]: '' }));
        this.saved.emit(saved);
      },
      error: (err: unknown) => this.claimError.set({
        id: c.id,
        message: err instanceof HttpErrorResponse && err.status === 403
          ? 'Only a steward can reject a claim.'
          : this.refusal(err, c, 'rejected'),
      }),
    });
  }

  protected reassignToChanged(c: ClaimView, personId: string): void {
    this.reassignTo.update((chosen) => ({ ...chosen, [c.id]: personId }));
    if (this.claimError()?.id === c.id) this.claimError.set(null);
  }

  protected reassign(c: ClaimView): void {
    this.claimError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const to = Number(this.reassignTo()[c.id] ?? '');
    if (!to) {
      this.claimError.set({ id: c.id, message: 'Choose the person to move the claim to.' });
      return;
    }
    this.api.reassign(sheet.id, c.id, to).subscribe({
      next: (saved) => {
        this.reassignTo.update((chosen) => ({ ...chosen, [c.id]: '' }));
        this.saved.emit(saved);
      },
      error: (err: unknown) => this.claimError.set({
        id: c.id,
        message: err instanceof HttpErrorResponse && err.status === 403
          ? 'Only a steward can move a claim to another person.'
          : err instanceof HttpErrorResponse && err.status === 423
            ? 'This claim is dated inside a closed period.'
            : err instanceof HttpErrorResponse && err.status === 422
              ? 'This claim is dated after that person retired.'
              : err instanceof HttpErrorResponse && err.status === 400
                ? `That person has no settled claim No. ${c.corrects} for this correction to correct.`
                : `A ${c.status} claim cannot be moved; only a DRAFT, OPEN or PENDING one can.`,
      }),
    });
  }

  /** Why approving or rejecting a claim was refused, other than by who asked. */
  private refusal(err: unknown, c: ClaimView, done: string): string {
    return err instanceof HttpErrorResponse && err.status === 423
      ? 'This claim is dated inside a closed period.'
      : err instanceof HttpErrorResponse && err.status === 400
        ? 'Type why the claim is rejected.'
        : `A ${c.status} claim cannot be ${done}; only a PENDING one can.`;
  }

  protected fieldId(field: string): string {
    return `claim-form-${field}-${this.personId()}`;
  }

  protected edit(c: ClaimView): void {
    this.claimError.set(null);
    // Only a draft can be edited; an open claim is changed by voiding it and adding a new one.
    if (c.status !== 'DRAFT') {
      this.claimError.set({ id: c.id, message: `A ${c.status} claim cannot be edited. Void it and add a new one.` });
      return;
    }
    this.editingId.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note, corrects: c.corrects ?? '' },
      { emitEvent: false });
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset({ amount: '', date: '', note: '', corrects: '' }, { emitEvent: false });
    this.error.set(null);
  }

  protected submit(): void {
    // A second submit before the first has answered is ignored, so it stores one record.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.form.getRawValue();
    const amount = value.amount.trim();
    if (!CLAIM_AMOUNT_PATTERN.test(amount)) {
      this.error.set('Type an amount with up to two decimals; a negative one is a correction.');
      return;
    }
    // A negative amount is a correction: it must name the number of a settled claim of this person.
    const correction = amount.startsWith('-');
    const corrects = value.corrects.trim();
    if (correction && !/^\S{1,120}$/.test(corrects)) {
      this.error.set('A negative amount is a correction: type the number of the settled claim it corrects.');
      return;
    }
    if (!correction && corrects !== '') {
      this.error.set('Only a negative amount (a correction) names a claim it corrects.');
      return;
    }
    const date = value.date.trim();
    if (date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Choose a date for the claim, or leave it blank for today.');
      return;
    }
    const claim = { amount, date: date === '' ? null : date, note: value.note, corrects: correction ? corrects : null };
    const id = this.editingId();
    this.saving.set(true);
    const request = id === null ? this.api.add(sheet.id, this.personId(), claim) : this.api.update(sheet.id, id, claim);
    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.saved.emit(saved);
        this.reset();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.error.set(
          err instanceof HttpErrorResponse && err.status === 423
            ? 'That claim is dated inside a closed period.'
            : err instanceof HttpErrorResponse && err.status === 422
              ? 'That claim is dated after the person retired.'
            : correction && err instanceof HttpErrorResponse && err.status === 400
              ? `Claim No. ${corrects} is not a settled claim of ${this.personName()}, so this cannot correct it.`
            : id !== null && err instanceof HttpErrorResponse && err.status === 409
              ? 'Only a draft claim can be edited. Void it and add a new one.'
              : id === null ? 'Could not add that claim.' : 'Could not save that claim.',
        );
      },
    });
  }
}
