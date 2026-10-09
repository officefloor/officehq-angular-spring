import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, Injector, afterNextRender, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { Notes } from '../notes/notes';
import { InvoicePayments } from '../payments/invoice-payments';
import { InvoiceDetail, InvoiceService, LineItem } from './invoice.service';

const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;
// A unit price may be a fraction of a cent; each line is rounded to the cent on its own.
const FOUR_DECIMALS = /^\d+(\.\d{1,4})?$/;

// A single invoice: the client's tax number when they are tax registered, the things it charges for (description, how many and of what, price each), each line's
// amount, their subtotal, any percentage discount, the taxable amount (leaving out tax-free lines), any
// sales tax added on it after the discount, any levy (a second tax) added on the same base, the total before tax, and the final total including both taxes (also shown as the total after tax).
// For a client whose prices already include tax, the tax and levy are instead shown as worked back out of the price; the total is unchanged.
// Lines, the discount, the tax rate and the levy rate can be changed while it is a draft.
@Component({
  selector: 'app-invoice-detail',
  imports: [ReactiveFormsModule, CurrencyPipe, DecimalPipe, RouterLink, InvoicePayments, Notes],
  template: `
    <a [routerLink]="['/projects', projectIdNumber()]" data-testid="invoice-back">Back to job</a>
    @if (invoice.error()) {
      <p role="alert" data-testid="invoice-error">Could not load the invoice.</p>
    } @else if (invoice.value(); as inv) {
      <h1 data-testid="invoice-detail-title">Invoice #{{ inv.id }}</h1>
      <p>
        Status: <span data-testid="invoice-status">{{ inv.status }}</span> · Issued
        <span data-testid="invoice-issued">{{ inv.issuedDate }}</span> · Due
        <span data-testid="invoice-due">{{ inv.dueDate }}</span>
      </p>
      <p>
        Tax: <span data-testid="invoice-tax-mode">{{ inv.taxInclusive ? 'Inclusive' : 'Exclusive' }}</span>
        @if (inv.taxInclusive) {
          (prices already include tax)
        }
      </p>
      @if (inv.clientTaxNumber) {
        <p>Client tax number: <span data-testid="invoice-client-tax-number">{{ inv.clientTaxNumber }}</span></p>
      }

      <section aria-labelledby="invoice-lineitems-heading">
        <h2 id="invoice-lineitems-heading" tabindex="-1">Line items</h2>
        @if (inv.lineItems.length === 0) {
          <p data-testid="invoice-lineitems-empty">No line items yet.</p>
        }
        <table data-testid="invoice-lineitems-table">
          <caption>What this invoice charges for</caption>
          <thead>
            <tr>
              <th scope="col">Description</th>
              <th scope="col">Quantity</th>
              <th scope="col">Unit</th>
              <th scope="col">Unit price</th>
              <th scope="col">Amount</th>
              @if (inv.status === 'DRAFT') {
                <th scope="col"><span class="visually-hidden">Actions</span></th>
              }
            </tr>
          </thead>
          <tbody>
            @for (l of inv.lineItems; track l.id) {
              <tr [attr.data-testid]="'lineitem-row-' + l.id">
                @if (editingId() === l.id) {
                  <td>
                    <input
                      [id]="'lineitem-edit-description-' + l.id"
                      type="text"
                      [formControl]="editForm.controls.description"
                      data-testid="lineitem-edit-description"
                      aria-label="Description"
                      [attr.aria-invalid]="editForm.controls.description.invalid"
                      (keydown.enter)="saveEdit(l.id)"
                      (keydown.escape)="cancelEdit(l.id)"
                    />
                    <label>
                      <input
                        type="checkbox"
                        [formControl]="editForm.controls.taxExempt"
                        data-testid="lineitem-edit-taxexempt"
                        (keydown.escape)="cancelEdit(l.id)"
                      />
                      Tax-free
                    </label>
                  </td>
                  <td>
                    <input
                      type="number"
                      inputmode="decimal"
                      min="0.01"
                      step="0.01"
                      [formControl]="editForm.controls.qty"
                      data-testid="lineitem-edit-qty"
                      aria-label="Quantity"
                      [attr.aria-invalid]="editForm.controls.qty.invalid"
                      (keydown.enter)="saveEdit(l.id)"
                      (keydown.escape)="cancelEdit(l.id)"
                    />
                  </td>
                  <td>
                    <input
                      type="text"
                      [formControl]="editForm.controls.unit"
                      data-testid="lineitem-edit-unit"
                      aria-label="Unit"
                      [attr.aria-invalid]="editForm.controls.unit.invalid"
                      (keydown.enter)="saveEdit(l.id)"
                      (keydown.escape)="cancelEdit(l.id)"
                    />
                  </td>
                  <td>
                    <input
                      type="number"
                      inputmode="decimal"
                      min="0.01"
                      step="0.0001"
                      [formControl]="editForm.controls.unitPrice"
                      data-testid="lineitem-edit-unitprice"
                      aria-label="Unit price"
                      [attr.aria-invalid]="editForm.controls.unitPrice.invalid"
                      (keydown.enter)="saveEdit(l.id)"
                      (keydown.escape)="cancelEdit(l.id)"
                    />
                  </td>
                  <td data-testid="lineitem-amount">{{ lineCents(l.qty, l.unitPrice) / 100 | currency: inv.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
                  <td>
                    <button
                      type="button"
                      [attr.data-testid]="'lineitem-save-' + l.id"
                      [disabled]="busy() || editForm.invalid"
                      (click)="saveEdit(l.id)"
                    >
                      Save
                    </button>
                    <button type="button" [attr.data-testid]="'lineitem-cancel-' + l.id" (click)="cancelEdit(l.id)">
                      Cancel
                    </button>
                  </td>
                } @else {
                  <td>
                    <span data-testid="lineitem-description">{{ l.description }}</span>
                    @if (l.taxExempt) {
                      <span data-testid="lineitem-taxexempt">(tax-free)</span>
                    }
                  </td>
                  <td data-testid="lineitem-qty">{{ l.qty | number: '1.0-2' : 'en-US' }}</td>
                  <td data-testid="lineitem-unit">{{ l.unit ?? '' }}</td>
                  <td data-testid="lineitem-unitprice">{{ l.unitPrice | currency: inv.currency : 'symbol' : '1.2-4' : 'en-US' }}</td>
                  <td data-testid="lineitem-amount">{{ l.amount | currency: inv.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
                  @if (inv.status === 'DRAFT') {
                    <td>
                      <button
                        type="button"
                        [id]="'lineitem-edit-' + l.id"
                        [attr.data-testid]="'lineitem-edit-' + l.id"
                        [attr.aria-label]="'Edit line item ' + l.description"
                        [disabled]="busy()"
                        (click)="startEdit(l)"
                      >
                        Edit
                      </button>
                      <button
                        type="button"
                        [attr.data-testid]="'lineitem-remove-' + l.id"
                        [attr.aria-label]="'Remove line item ' + l.description"
                        [disabled]="busy()"
                        (click)="remove(l.id)"
                      >
                        Remove
                      </button>
                    </td>
                  }
                }
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="4">Subtotal</th>
              <td data-testid="invoice-subtotal">{{ inv.subtotal | currency: inv.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="4">
                Discount (<span data-testid="invoice-discount-pct">{{ inv.discountPct | number: '1.0-2' : 'en-US' }}</span>%)
              </th>
              <td data-testid="invoice-discount">{{ inv.discount | currency: inv.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="4">Taxable amount (excludes tax-free lines)</th>
              <td data-testid="invoice-taxable-base">{{ inv.taxableBase | currency: inv.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="4" data-testid="invoice-tax-label">
                Tax (<span data-testid="invoice-tax-pct">{{ inv.taxPct | number: '1.0-2' : 'en-US' }}</span>%)@if (inv.taxInclusive) { included}
              </th>
              <td data-testid="invoice-tax">{{ inv.tax | currency: inv.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="4" data-testid="invoice-tax-levy-label">
                Levy (<span data-testid="invoice-tax-levy-pct">{{ inv.levyPct | number: '1.0-2' : 'en-US' }}</span>%)@if (inv.taxInclusive) { included}
              </th>
              <td data-testid="invoice-tax-levy">{{ inv.levy | currency: inv.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="4">Total before tax</th>
              <td data-testid="invoice-total-ex-tax">{{ inv.totalExTax | currency: inv.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="4">Total</th>
              <td data-testid="invoice-amount">{{ inv.amount | currency: inv.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="4">Total after tax</th>
              <td data-testid="invoice-total-inc-tax">{{ inv.amount | currency: inv.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
          </tfoot>
        </table>
        @if (editError()) {
          <p role="alert" data-testid="lineitem-edit-error">{{ editError() }}</p>
        }
      </section>

      @if (inv.status === 'DRAFT') {
        <form [formGroup]="discountForm" (ngSubmit)="applyDiscount()" data-testid="discount-form" novalidate>
          <h2>Discount</h2>
          <div>
            <label for="discount-pct">Percentage off the subtotal</label>
            <input
              id="discount-pct"
              type="number"
              inputmode="decimal"
              min="0"
              max="100"
              step="0.01"
              formControlName="discountPct"
              data-testid="discount-form-pct"
              [attr.aria-invalid]="discountInvalid()"
              [attr.aria-describedby]="discountInvalid() ? 'discount-pct-error' : null"
            />
            @if (discountInvalid()) {
              <p id="discount-pct-error" role="alert" data-testid="discount-form-pct-error">
                Enter a percentage from 0 to 100 with at most two decimal places.
              </p>
            }
          </div>
          <button type="submit" data-testid="discount-form-submit" [disabled]="discountSaving()">Apply discount</button>
          @if (discountError()) {
            <p role="alert" data-testid="discount-form-error">{{ discountError() }}</p>
          }
        </form>

        <form [formGroup]="taxForm" (ngSubmit)="applyTax()" data-testid="tax-form" novalidate>
          <h2>Sales tax</h2>
          <div>
            <label for="tax-pct">Tax percentage added after the discount</label>
            <input
              id="tax-pct"
              type="number"
              inputmode="decimal"
              min="0"
              max="100"
              step="0.01"
              formControlName="taxPct"
              data-testid="tax-form-pct"
              [attr.aria-invalid]="taxInvalid()"
              [attr.aria-describedby]="taxInvalid() ? 'tax-pct-error' : null"
            />
            @if (taxInvalid()) {
              <p id="tax-pct-error" role="alert" data-testid="tax-form-pct-error">
                Enter a percentage from 0 to 100 with at most two decimal places.
              </p>
            }
          </div>
          <button type="submit" data-testid="tax-form-submit" [disabled]="taxSaving()">Apply tax</button>
          @if (taxError()) {
            <p role="alert" data-testid="tax-form-error">{{ taxError() }}</p>
          }
        </form>

        <form [formGroup]="levyForm" (ngSubmit)="applyLevy()" data-testid="levy-form" novalidate>
          <h2>Levy</h2>
          <div>
            <label for="levy-pct">Levy percentage added on top of the sales tax</label>
            <input
              id="levy-pct"
              type="number"
              inputmode="decimal"
              min="0"
              max="100"
              step="0.01"
              formControlName="levyPct"
              data-testid="levy-form-pct"
              [attr.aria-invalid]="levyInvalid()"
              [attr.aria-describedby]="levyInvalid() ? 'levy-pct-error' : null"
            />
            @if (levyInvalid()) {
              <p id="levy-pct-error" role="alert" data-testid="levy-form-pct-error">
                Enter a percentage from 0 to 100 with at most two decimal places.
              </p>
            }
          </div>
          <button type="submit" data-testid="levy-form-submit" [disabled]="levySaving()">Apply levy</button>
          @if (levyError()) {
            <p role="alert" data-testid="levy-form-error">{{ levyError() }}</p>
          }
        </form>

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
            <label for="lineitem-unit">Unit (optional, e.g. hours)</label>
            <input
              id="lineitem-unit"
              type="text"
              formControlName="unit"
              data-testid="lineitem-form-unit"
              [attr.aria-invalid]="invalid('unit')"
              [attr.aria-describedby]="invalid('unit') ? 'lineitem-unit-error' : null"
            />
            @if (invalid('unit')) {
              <p id="lineitem-unit-error" role="alert" data-testid="lineitem-form-unit-error">
                Unit must be at most 50 characters.
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
              step="0.0001"
              formControlName="unitPrice"
              data-testid="lineitem-form-unitprice"
              [attr.aria-invalid]="invalid('unitPrice')"
              [attr.aria-describedby]="invalid('unitPrice') ? 'lineitem-unitprice-error' : null"
            />
            @if (invalid('unitPrice')) {
              <p id="lineitem-unitprice-error" role="alert" data-testid="lineitem-form-unitprice-error">
                Enter a price greater than zero with at most four decimal places.
              </p>
            }
          </div>
          <div>
            <input id="lineitem-taxexempt" type="checkbox" formControlName="taxExempt" data-testid="lineitem-form-taxexempt" />
            <label for="lineitem-taxexempt">Tax-free (no tax is charged on this line)</label>
          </div>
          <button type="submit" data-testid="lineitem-form-submit" [disabled]="saving()">Add line item</button>
          @if (saveError()) {
            <p role="alert" data-testid="lineitem-form-error">{{ saveError() }}</p>
          }
        </form>
      }

      <app-invoice-payments
        [projectId]="projectIdNumber()"
        [invoiceId]="inv.id"
        [invoiceAmount]="inv.amount"
        [currency]="inv.currency"
        [canRecord]="inv.status === 'SENT' || inv.status === 'PARTIAL'"
        (recorded)="invoice.reload()"
      />

      <app-notes [projectId]="projectIdNumber()" [invoiceId]="inv.id" />
    }
  `,
})
export class InvoiceDetailPage {
  private readonly service = inject(InvoiceService);
  private readonly injector = inject(Injector);
  private readonly fb = inject(NonNullableFormBuilder);

  /** Bound from the `:projectId` route parameter. */
  readonly projectId = input.required<string>();
  /** Bound from the `:invoiceId` route parameter. */
  readonly invoiceId = input.required<string>();
  protected readonly projectIdNumber = computed(() => Number(this.projectId()));

  protected readonly invoice = rxResource({
    params: () => ({ projectId: this.projectIdNumber(), invoiceId: Number(this.invoiceId()) }),
    stream: ({ params }) => this.service.get(params.projectId, params.invoiceId),
  });

  // Work in whole cents (and ten-thousandths of a unit price) so the line is exact rather than
  // accumulating floating-point error; each line is rounded to the cent on its own.
  protected lineCents(qty: number, unitPrice: number): number {
    return Math.round((Math.round(qty * 100) * Math.round(unitPrice * 10000)) / 10000);
  }

  protected readonly discountSaving = signal(false);
  protected readonly discountError = signal<string | null>(null);

  protected readonly discountForm = this.fb.group({
    discountPct: ['', [Validators.required, Validators.min(0), Validators.max(100), Validators.pattern(TWO_DECIMALS)]],
  });

  // Starts the discount field from the invoice's current percentage whenever the invoice loads.
  private readonly syncDiscount = effect(() => {
    if (this.invoice.hasValue()) {
      this.discountForm.setValue({ discountPct: String(this.invoice.value().discountPct) });
    }
  });

  protected discountInvalid(): boolean {
    const control = this.discountForm.controls.discountPct;
    return control.invalid && (control.touched || control.dirty);
  }

  protected applyDiscount(): void {
    if (this.discountForm.invalid) {
      this.discountForm.markAllAsTouched();
      return;
    }
    this.discountSaving.set(true);
    this.discountError.set(null);
    const discountPct = Number(this.discountForm.getRawValue().discountPct);
    this.service.applyDiscount(this.projectIdNumber(), Number(this.invoiceId()), discountPct).subscribe({
      next: (updated) => {
        this.invoice.set(updated);
        this.discountSaving.set(false);
      },
      error: () => {
        this.discountError.set('Could not apply the discount. Please try again.');
        this.discountSaving.set(false);
      },
    });
  }

  protected readonly taxSaving = signal(false);
  protected readonly taxError = signal<string | null>(null);

  protected readonly taxForm = this.fb.group({
    taxPct: ['', [Validators.required, Validators.min(0), Validators.max(100), Validators.pattern(TWO_DECIMALS)]],
  });

  // Starts the tax field from the invoice's current rate whenever the invoice loads.
  private readonly syncTax = effect(() => {
    if (this.invoice.hasValue()) {
      this.taxForm.setValue({ taxPct: String(this.invoice.value().taxPct) });
    }
  });

  protected taxInvalid(): boolean {
    const control = this.taxForm.controls.taxPct;
    return control.invalid && (control.touched || control.dirty);
  }

  protected applyTax(): void {
    if (this.taxForm.invalid) {
      this.taxForm.markAllAsTouched();
      return;
    }
    this.taxSaving.set(true);
    this.taxError.set(null);
    const taxPct = Number(this.taxForm.getRawValue().taxPct);
    this.service.applyTax(this.projectIdNumber(), Number(this.invoiceId()), taxPct).subscribe({
      next: (updated) => {
        this.invoice.set(updated);
        this.taxSaving.set(false);
      },
      error: () => {
        this.taxError.set('Could not apply the tax. Please try again.');
        this.taxSaving.set(false);
      },
    });
  }

  protected readonly levySaving = signal(false);
  protected readonly levyError = signal<string | null>(null);

  protected readonly levyForm = this.fb.group({
    levyPct: ['', [Validators.required, Validators.min(0), Validators.max(100), Validators.pattern(TWO_DECIMALS)]],
  });

  // Starts the levy field from the invoice's current rate whenever the invoice loads.
  private readonly syncLevy = effect(() => {
    if (this.invoice.hasValue()) {
      this.levyForm.setValue({ levyPct: String(this.invoice.value().levyPct) });
    }
  });

  protected levyInvalid(): boolean {
    const control = this.levyForm.controls.levyPct;
    return control.invalid && (control.touched || control.dirty);
  }

  protected applyLevy(): void {
    if (this.levyForm.invalid) {
      this.levyForm.markAllAsTouched();
      return;
    }
    this.levySaving.set(true);
    this.levyError.set(null);
    const levyPct = Number(this.levyForm.getRawValue().levyPct);
    this.service.applyLevy(this.projectIdNumber(), Number(this.invoiceId()), levyPct).subscribe({
      next: (updated) => {
        this.invoice.set(updated);
        this.levySaving.set(false);
      },
      error: () => {
        this.levyError.set('Could not apply the levy. Please try again.');
        this.levySaving.set(false);
      },
    });
  }

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = this.fb.group({
    description: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(255)]],
    qty: ['', [Validators.required, Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
    unit: ['', [Validators.maxLength(50)]],
    unitPrice: ['', [Validators.required, Validators.min(0.01), Validators.pattern(FOUR_DECIMALS)]],
    taxExempt: false,
  });

  protected invalid(name: 'description' | 'qty' | 'unit' | 'unitPrice'): boolean {
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
    const { description, qty, unit, unitPrice, taxExempt } = this.form.getRawValue();
    const item = { description: description.trim(), qty: Number(qty), unit: unit.trim() || null, unitPrice: Number(unitPrice), taxExempt };
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

  // The line item being changed in place, and whether a change or removal is in flight.
  protected readonly editingId = signal<number | null>(null);
  protected readonly busy = signal(false);
  protected readonly editError = signal<string | null>(null);

  protected readonly editForm = this.fb.group({
    description: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(255)]],
    qty: ['', [Validators.required, Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
    unit: ['', [Validators.maxLength(50)]],
    unitPrice: ['', [Validators.required, Validators.min(0.01), Validators.pattern(FOUR_DECIMALS)]],
    taxExempt: false,
  });

  protected startEdit(line: LineItem): void {
    this.editError.set(null);
    this.editForm.setValue({
      description: line.description,
      qty: String(line.qty),
      unit: line.unit ?? '',
      unitPrice: String(line.unitPrice),
      taxExempt: line.taxExempt,
    });
    this.editingId.set(line.id);
    this.focus(`lineitem-edit-description-${line.id}`);
  }

  protected cancelEdit(lineItemId: number): void {
    this.editingId.set(null);
    this.editError.set(null);
    this.focus(`lineitem-edit-${lineItemId}`);
  }

  protected saveEdit(lineItemId: number): void {
    if (this.editForm.invalid || this.busy()) {
      this.editForm.markAllAsTouched();
      return;
    }
    const { description, qty, unit, unitPrice, taxExempt } = this.editForm.getRawValue();
    const item = { description: description.trim(), qty: Number(qty), unit: unit.trim() || null, unitPrice: Number(unitPrice), taxExempt };
    this.run(
      this.service.updateLineItem(this.projectIdNumber(), Number(this.invoiceId()), lineItemId, item),
      'Could not save the line item. Please try again.',
      () => {
        this.editingId.set(null);
        this.focus(`lineitem-edit-${lineItemId}`);
      },
    );
  }

  protected remove(lineItemId: number): void {
    this.run(
      this.service.removeLineItem(this.projectIdNumber(), Number(this.invoiceId()), lineItemId),
      'Could not remove the line item. Please try again.',
      () => this.focus('invoice-lineitems-heading'),
    );
  }

  private run(request: Observable<InvoiceDetail>, failure: string, done: () => void): void {
    this.busy.set(true);
    this.editError.set(null);
    request.subscribe({
      next: (updated) => {
        this.invoice.set(updated);
        this.busy.set(false);
        done();
      },
      error: () => {
        this.editError.set(failure);
        this.busy.set(false);
      },
    });
  }

  // Moves focus once the view has re-rendered, so keyboard users are not left on a removed element.
  private focus(id: string): void {
    afterNextRender(() => document.getElementById(id)?.focus(), { injector: this.injector });
  }
}
