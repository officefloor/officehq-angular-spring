import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { InvoiceService } from './invoice.service';

const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;

// A single invoice: the things it charges for (description, how many, price each), each line's
// amount, and the invoice total worked out from them. Lines can be added while it is a draft.
@Component({
  selector: 'app-invoice-detail',
  imports: [ReactiveFormsModule, CurrencyPipe, DecimalPipe, RouterLink],
  template: `
    <a [routerLink]="['/projects', projectIdNumber()]" data-testid="invoice-back">Back to project</a>
    @if (invoice.error()) {
      <p role="alert" data-testid="invoice-error">Could not load the invoice.</p>
    } @else if (invoice.value(); as inv) {
      <h1 data-testid="invoice-detail-title">Invoice #{{ inv.id }}</h1>
      <p>
        Status: <span data-testid="invoice-status">{{ inv.status }}</span> · Issued
        <span data-testid="invoice-issued">{{ inv.issuedDate }}</span> · Due
        <span data-testid="invoice-due">{{ inv.dueDate }}</span>
      </p>

      <section aria-labelledby="invoice-lineitems-heading">
        <h2 id="invoice-lineitems-heading">Line items</h2>
        @if (inv.lineItems.length === 0) {
          <p data-testid="invoice-lineitems-empty">No line items yet.</p>
        }
        <table data-testid="invoice-lineitems-table">
          <caption>What this invoice charges for</caption>
          <thead>
            <tr>
              <th scope="col">Description</th>
              <th scope="col">Quantity</th>
              <th scope="col">Unit price</th>
              <th scope="col">Amount</th>
            </tr>
          </thead>
          <tbody>
            @for (l of inv.lineItems; track l.id) {
              <tr [attr.data-testid]="'lineitem-row-' + l.id">
                <td data-testid="lineitem-description">{{ l.description }}</td>
                <td data-testid="lineitem-qty">{{ l.qty | number: '1.0-2' : 'en-US' }}</td>
                <td data-testid="lineitem-unitprice">{{ l.unitPrice | currency: 'USD' : 'symbol' : '1.2-2' : 'en-US' }}</td>
                <td data-testid="lineitem-amount">{{ lineCents(l.qty, l.unitPrice) / 100 | currency: 'USD' : 'symbol' : '1.2-2' : 'en-US' }}</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="3">Total</th>
              <td data-testid="invoice-amount">{{ totalCents() / 100 | currency: 'USD' : 'symbol' : '1.2-2' : 'en-US' }}</td>
            </tr>
          </tfoot>
        </table>
      </section>

      @if (inv.status === 'DRAFT') {
        <form [formGroup]="form" (ngSubmit)="submit()" data-testid="lineitem-form" novalidate>
          <h2>Add a line item</h2>
          <div>
            <label for="lineitem-description">Description</label>
            <input
              id="lineitem-description"
              type="text"
              formControlName="description"
              data-testid="lineitem-form-description"
              [attr.aria-invalid]="invalid('description')"
              [attr.aria-describedby]="invalid('description') ? 'lineitem-description-error' : null"
            />
            @if (invalid('description')) {
              <p id="lineitem-description-error" role="alert" data-testid="lineitem-form-description-error">
                Description is required.
              </p>
            }
          </div>
          <div>
            <label for="lineitem-qty">Quantity</label>
            <input
              id="lineitem-qty"
              type="number"
              inputmode="decimal"
              min="0.01"
              step="0.01"
              formControlName="qty"
              data-testid="lineitem-form-qty"
              [attr.aria-invalid]="invalid('qty')"
              [attr.aria-describedby]="invalid('qty') ? 'lineitem-qty-error' : null"
            />
            @if (invalid('qty')) {
              <p id="lineitem-qty-error" role="alert" data-testid="lineitem-form-qty-error">
                Enter a quantity greater than zero with at most two decimal places.
              </p>
            }
          </div>
          <div>
            <label for="lineitem-unitprice">Unit price</label>
            <input
              id="lineitem-unitprice"
              type="number"
              inputmode="decimal"
              min="0.01"
              step="0.01"
              formControlName="unitPrice"
              data-testid="lineitem-form-unitprice"
              [attr.aria-invalid]="invalid('unitPrice')"
              [attr.aria-describedby]="invalid('unitPrice') ? 'lineitem-unitprice-error' : null"
            />
            @if (invalid('unitPrice')) {
              <p id="lineitem-unitprice-error" role="alert" data-testid="lineitem-form-unitprice-error">
                Enter a price greater than zero with at most two decimal places.
              </p>
            }
          </div>
          <button type="submit" data-testid="lineitem-form-submit" [disabled]="saving()">Add line item</button>
          @if (saveError()) {
            <p role="alert" data-testid="lineitem-form-error">{{ saveError() }}</p>
          }
        </form>
      }
    }
  `,
})
export class InvoiceDetailPage {
  private readonly service = inject(InvoiceService);

  /** Bound from the `:projectId` route parameter. */
  readonly projectId = input.required<string>();
  /** Bound from the `:invoiceId` route parameter. */
  readonly invoiceId = input.required<string>();
  protected readonly projectIdNumber = computed(() => Number(this.projectId()));

  protected readonly invoice = rxResource({
    params: () => ({ projectId: this.projectIdNumber(), invoiceId: Number(this.invoiceId()) }),
    stream: ({ params }) => this.service.get(params.projectId, params.invoiceId),
  });

  // Work in whole cents so the total is exact rather than accumulating floating-point error.
  protected lineCents(qty: number, unitPrice: number): number {
    return Math.round(Math.round(qty * 100) * Math.round(unitPrice * 100) / 100);
  }
  protected readonly totalCents = computed(() =>
    (this.invoice.hasValue() ? this.invoice.value().lineItems : []).reduce(
      (sum, l) => sum + this.lineCents(l.qty, l.unitPrice),
      0,
    ),
  );

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    description: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(255)]],
    qty: ['', [Validators.required, Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
    unitPrice: ['', [Validators.required, Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
  });

  protected invalid(name: 'description' | 'qty' | 'unitPrice'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    const { description, qty, unitPrice } = this.form.getRawValue();
    const item = { description: description.trim(), qty: Number(qty), unitPrice: Number(unitPrice) };
    this.service.addLineItem(this.projectIdNumber(), Number(this.invoiceId()), item).subscribe({
      next: (updated) => {
        this.invoice.set(updated);
        this.form.reset();
        this.saving.set(false);
      },
      error: () => {
        this.saveError.set('Could not add the line item. Please try again.');
        this.saving.set(false);
      },
    });
  }
}
