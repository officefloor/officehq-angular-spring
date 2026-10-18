import { Component, computed, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { finalize } from 'rxjs';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Claim, ClaimAction, ClaimsService, claimDue, money } from '../../core/claims.service';
import { Grade } from '../../core/people.service';
import { RatesService } from '../../core/rates.service';

// A person's claims, newest first, each with its status and buttons to open, settle or void it, who opened it (they may not settle it), its due (n/a when the person's grade has no rate in force on the claim's date), and a form that adds a claim or edits the one opened.
@Component({
  selector: 'app-person-claims',
  imports: [ReactiveFormsModule],
  template: `
    <ul [attr.aria-label]="'Claims of ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span>Status <span data-testid="claim-status">{{ c.status }}</span></span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <button type="button" data-testid="claim-edit" (click)="open(c)"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
          @for (a of actions; track a.action) {
            <button type="button" [attr.data-testid]="'claim-' + a.action" (click)="change(c, a.action)"
              [attr.aria-label]="a.label + ' claim of ' + c.amount + ' on ' + c.date">{{ a.label }}</button>
          }
          @if (claimErrors().get(c.id); as e) {
            <p role="alert" data-testid="claim-error">{{ e }}</p>
          }
        </li>
      }
    </ul>
    <form [formGroup]="form" (ngSubmit)="submit()"
      [attr.aria-label]="(editing() === null ? 'Add claim for ' : 'Edit claim of ') + personName()">
      <label [for]="'claim-form-amount-' + personId()">Amount</label>
      <input [id]="'claim-form-amount-' + personId()" data-testid="claim-form-amount" formControlName="amount"
        inputmode="decimal" />
      <label [for]="'claim-form-date-' + personId()">Date (empty: today)</label>
      <input [id]="'claim-form-date-' + personId()" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="'claim-form-note-' + personId()">Note</label>
      <input [id]="'claim-form-note-' + personId()" data-testid="claim-form-note" formControlName="note" />
      <button type="submit" data-testid="claim-form-submit">{{ editing() === null ? 'Add claim' : 'Save claim' }}</button>
      @if (editing() !== null) {
        <button type="button" data-testid="claim-form-cancel" (click)="reset()">Cancel</button>
      }
      @if (error()) {
        <p role="alert" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class PersonClaims {
  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly grade = input.required<Grade>();
  private readonly service = inject(ClaimsService);
  private readonly rates = inject(RatesService);
  protected readonly claims = computed(() => this.service.byPerson().get(this.personId()) ?? []);
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private saving = false;
  /** Each claim's refused status change, by claim id; cleared by the next action on that claim. */
  protected readonly claimErrors = signal<ReadonlyMap<number, string>>(new Map());
  protected readonly actions: readonly { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
  ];

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  /** A settled claim shows the due fixed when it was settled; any other (or one settled without a rate) follows the rate in force on its date. */
  protected dueOf(c: Claim): string {
    const d = claimDue(c, (date) => this.rates.rateOn(this.grade(), date));
    return d === undefined ? 'n/a' : money(d);
  }

  protected change(c: Claim, action: ClaimAction): void {
    this.setClaimError(c.id, '');
    this.service.changeStatus(c.id, action).subscribe({
      error: (e: unknown) => this.setClaimError(c.id,
        e instanceof HttpErrorResponse && e.status === 403
          ? 'You opened this claim, so someone else must settle it.'
          : `A ${c.status} claim cannot be changed that way.`),
    });
  }

  private setClaimError(claimId: number, message: string): void {
    this.claimErrors.update((all) => {
      const out = new Map(all);
      if (message) out.set(claimId, message);
      else out.delete(claimId);
      return out;
    });
  }

  protected open(c: Claim): void {
    this.setClaimError(c.id, '');
    this.error.set('');
    this.editing.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note }, { emitEvent: false });
  }

  protected reset(): void {
    this.error.set('');
    this.editing.set(null);
    this.form.reset({ amount: '', date: '', note: '' }, { emitEvent: false });
  }

  protected submit(): void {
    if (this.saving) return;
    this.error.set('');
    const { amount, date, note } = this.form.getRawValue();
    const a = amount.trim();
    if (!/^\d{1,13}(\.\d{1,2})?$/.test(a)) {
      this.error.set('Enter an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const form = { amount: a, date: date.trim() || null, note: note.trim() };
    const id = this.editing();
    const saved = id === null ? this.service.add(this.personId(), form) : this.service.edit(id, form);
    this.saving = true;
    saved.pipe(finalize(() => (this.saving = false))).subscribe({
      next: () => this.reset(),
      error: () => this.error.set(id === null ? 'Could not add that claim.' : 'Could not save that claim.'),
    });
  }
}
