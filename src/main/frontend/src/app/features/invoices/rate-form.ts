import { Component, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { InvoiceService, InvoiceSummary } from './invoice.service';
import { TWO_DECIMALS } from './invoice-format';

// Edits the client's rate card: a unit type and a price more than zero with at most two decimals adds
// that rate, or replaces the price for that unit type. The client's drafts priced from the card are
// repriced by the server; sent invoices keep the prices they were sent with.
@Component({
  selector: 'app-rate-form',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="save()" aria-label="Set a rate on the rate card" novalidate>
      <label for="rate-unit">Unit type</label>
      <input id="rate-unit" data-testid="rate-form-unit" formControlName="unitType" placeholder="hour" required />
      <label for="rate-price">Price</label>
      <input id="rate-price" data-testid="rate-form-price" inputmode="decimal" formControlName="unitPrice" required />
      <button type="submit" data-testid="rate-form-submit" [disabled]="saving()">Set rate</button>
      @if (error()) {
        <p role="alert" data-testid="rate-form-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class RateForm {
  private readonly service = inject(InvoiceService);

  readonly clientId = input.required<number>();
  // The client's invoices after the rate was set.
  readonly saved = output<InvoiceSummary[]>();

  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected readonly form = new FormGroup({
    unitType: new FormControl('', { nonNullable: true }),
    unitPrice: new FormControl('', { nonNullable: true }),
  });

  protected save(): void {
    const unitType = this.form.controls.unitType.value.trim();
    const unitPrice = this.form.controls.unitPrice.value.trim().replace(/^\$/, '');
    const problems: string[] = [];
    if (!unitType) {
      problems.push('Enter the unit type.');
    }
    if (!unitPrice) {
      problems.push('Enter the price.');
    } else if (!TWO_DECIMALS.test(unitPrice)) {
      problems.push('The price must be a number with at most two decimals.');
    } else if (Number(unitPrice) <= 0) {
      problems.push('The price must be more than zero.');
    }
    if (problems.length > 0) {
      this.form.markAllAsTouched();
      this.error.set(problems.join(' '));
      return;
    }
    this.saving.set(true);
    this.error.set('');
    this.service.setRate(this.clientId(), { unitType, unitPrice }).subscribe({
      next: (list) => {
        // Cleared for the next rate, unless one is already being typed.
        const now = this.form.getRawValue();
        if (now.unitType.trim() === unitType && now.unitPrice.trim().replace(/^\$/, '') === unitPrice) {
          this.form.reset();
        }
        this.saving.set(false);
        this.saved.emit(list);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.error.set(typeof detail === 'string' && detail ? detail : 'The rate could not be set.');
        this.saving.set(false);
      },
    });
  }
}
