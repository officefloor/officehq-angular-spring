import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { AMOUNT, Claims, QuickAdded } from '../../core/claims';

/** The message for a refused quick-add. */
function refusal(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 423) return 'The claim\'s period is closed.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That name is already in use on this sheet.';
  return 'The claim could not be added.';
}

// The quick-add bar: a name and an amount make a draft claim dated the sheet's today for the
// person of that name, who is added at grade A when the name is not on the sheet.
@Component({
  selector: 'app-quick-add-bar',
  imports: [ReactiveFormsModule],
  template: `
    <form data-testid="quick-add" [formGroup]="form" (ngSubmit)="submit()" aria-label="Quick add a claim">
      <label for="quick-name">Name</label>
      <input id="quick-name" data-testid="quick-name" formControlName="name" />
      <label for="quick-amount">Amount</label>
      <input id="quick-amount" data-testid="quick-amount" formControlName="amount" inputmode="decimal" />
      <button type="submit" data-testid="quick-submit">Quick add</button>
      @if (error()) {
        <p role="alert" data-testid="quick-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class QuickAddBar {
  private readonly api = inject(Claims);

  readonly added = output<QuickAdded>();

  protected readonly error = signal('');
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { name, amount } = this.form.getRawValue();
    if (!name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    if (!AMOUNT.test(amount.trim())) {
      this.error.set('Enter an amount of 0.00 or more with up to two decimals.');
      return;
    }
    this.submitting = true;
    try {
      this.added.emit(await this.api.quickAdd(name.trim(), amount.trim()));
      this.form.reset({ name: '', amount: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(refusal(e));
    } finally {
      this.submitting = false;
    }
  }
}
