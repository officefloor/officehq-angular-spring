import { Component, effect, inject, input, output, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { InvoiceDetail, InvoiceService } from './invoice.service';

// An invoice's purchase-order number: shows it, and puts it on or clears it (leave it blank to clear it).
@Component({
  selector: 'app-invoice-po-number',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="invoice-po-number-heading" data-testid="invoice-po-number-section">
      <h2 id="invoice-po-number-heading">Purchase order</h2>
      @if (invoice().poNumber; as po) {
        <p>PO number: <span data-testid="invoice-po-number">{{ po }}</span></p>
      } @else {
        <p data-testid="invoice-po-number-none">No PO number given.</p>
      }

      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="invoice-po-number-form" novalidate>
        <label for="invoice-po-number-input">PO number</label>
        <input
          id="invoice-po-number-input"
          type="text"
          maxlength="50"
          formControlName="poNumber"
          data-testid="invoice-po-number-input"
        />
        <button type="submit" data-testid="invoice-po-number-submit" [disabled]="saving()">Save PO number</button>
        @if (saveError()) {
          <p role="alert" data-testid="invoice-po-number-save-error">{{ saveError() }}</p>
        }
      </form>
    </section>
  `,
})
export class InvoicePoNumber {
  private readonly service = inject(InvoiceService);

  readonly invoice = input.required<InvoiceDetail>();
  /** Emits the invoice once its PO number has been saved. */
  readonly changed = output<InvoiceDetail>();

  protected readonly form = inject(NonNullableFormBuilder).group({ poNumber: ['', Validators.maxLength(50)] });
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.form.setValue({ poNumber: this.invoice().poNumber ?? '' });
    });
  }

  protected submit(): void {
    const poNumber = this.form.getRawValue().poNumber.trim();
    this.saving.set(true);
    this.saveError.set(null);
    this.service.setPoNumber(this.invoice().projectId, this.invoice().id, poNumber || null).subscribe({
      next: (updated) => {
        this.changed.emit(updated);
        this.saving.set(false);
      },
      error: () => {
        this.saveError.set('Could not save the PO number.');
        this.saving.set(false);
      },
    });
  }
}
