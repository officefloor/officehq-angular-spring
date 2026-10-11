import { Component, effect, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { InvoiceDetail, InvoiceService, NewLine } from './invoice.service';
import { money, statusLabel } from './invoice-format';

// A positive decimal with at most two decimals, e.g. 1, 1.5 or 12.35. The server applies the same rules.
const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;

// A percentage from 0 to 100 with at most two decimals, e.g. 15 or 12.5; the server checks it again.
function problemWithDiscount(pct: string): string {
  if (!pct) {
    return 'Enter a discount percentage.';
  }
  if (!/^-?\d+(\.\d+)?$/.test(pct)) {
    return 'The discount must be a number, like 15 or 12.5.';
  }
  if (Number(pct) < 0 || Number(pct) > 100) {
    return 'The discount must be from 0 to 100 percent.';
  }
  if (!TWO_DECIMALS.test(pct)) {
    return 'The discount can have at most two decimals.';
  }
  return '';
}

// What is wrong with a line, or '' when it can be added.
function problemWith(line: NewLine): string {
  const problems: string[] = [];
  if (!line.description) {
    problems.push('Enter a description.');
  }
  if (!line.qty) {
    problems.push('Enter a quantity.');
  } else if (!TWO_DECIMALS.test(line.qty)) {
    problems.push('The quantity must be a number with at most two decimals.');
  } else if (Number(line.qty) <= 0) {
    problems.push('The quantity must be more than zero.');
  }
  if (!line.unitPrice) {
    problems.push('Enter a unit price.');
  } else if (!TWO_DECIMALS.test(line.unitPrice)) {
    problems.push('The unit price must be a number with at most two decimals.');
  } else if (Number(line.unitPrice) < 0.01) {
    problems.push('The unit price must be at least $0.01.');
  }
  return problems.join(' ');
}

// One opened invoice: its status, its lines and its totals. A draft takes new lines and a
// percentage discount.
@Component({
  selector: 'app-invoice-panel',
  imports: [ReactiveFormsModule],
  template: `
    <section data-testid="invoice-panel" aria-labelledby="invoice-panel-heading">
      <h2 id="invoice-panel-heading">
        Invoice <span data-testid="invoice-panel-number">{{ invoice()?.number }}</span>
      </h2>
      @if (invoice(); as inv) {
        <p>Status: <span data-testid="invoice-panel-status">{{ statusLabel(inv.status) }}</span></p>
        <table>
          <thead>
            <tr>
              <th scope="col">Description</th>
              <th scope="col">Qty</th>
              <th scope="col">Unit price</th>
              <th scope="col">Amount</th>
            </tr>
          </thead>
          <tbody>
            @for (l of inv.lines; track l.id) {
              <tr [attr.data-testid]="'line-row-' + l.id">
                <td data-testid="line-description">{{ l.description }}</td>
                <td data-testid="line-qty">{{ l.qty }}</td>
                <td data-testid="line-unit-price">{{ money(l.unitPrice) }}</td>
                <td data-testid="line-amount">{{ money(l.amount) }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="4" data-testid="line-empty">No lines yet.</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="3">Subtotal</th>
              <td data-testid="invoice-subtotal">{{ money(inv.subtotal) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="3">Discount ({{ inv.discountPct }}%)</th>
              <td data-testid="invoice-discount">{{ money(inv.discount) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="3">Total</th>
              <td data-testid="invoice-grand-total">{{ money(inv.total) }}</td>
            </tr>
          </tfoot>
        </table>

        @if (inv.status === 'DRAFT') {
          <form [formGroup]="form" (ngSubmit)="addLine(inv.id)" aria-label="Add a line" novalidate>
            <label for="line-description">Description</label>
            <input
              id="line-description"
              data-testid="line-form-description"
              formControlName="description"
              required
            />
            <label for="line-qty">Quantity</label>
            <input
              id="line-qty"
              data-testid="line-form-qty"
              inputmode="decimal"
              formControlName="qty"
              required
            />
            <label for="line-unit-price">Unit price ($)</label>
            <input
              id="line-unit-price"
              data-testid="line-form-unit-price"
              inputmode="decimal"
              formControlName="unitPrice"
              required
            />
            <button type="submit" data-testid="line-form-submit" [disabled]="saving()">
              Add line
            </button>
            @if (error()) {
              <p role="alert" data-testid="line-form-error">{{ error() }}</p>
            }
          </form>

          <form
            [formGroup]="discountForm"
            (ngSubmit)="setDiscount(inv.id)"
            aria-label="Discount"
            novalidate
          >
            <label for="discount-pct">Discount (%)</label>
            <input
              id="discount-pct"
              data-testid="discount-form-pct"
              inputmode="decimal"
              formControlName="pct"
              required
            />
            <button type="submit" data-testid="discount-form-submit" [disabled]="savingDiscount()">
              Apply discount
            </button>
            @if (discountError()) {
              <p role="alert" data-testid="discount-form-error">{{ discountError() }}</p>
            }
          </form>
        }
      }
    </section>
  `,
})
export class InvoicePanel {
  private readonly service = inject(InvoiceService);

  readonly invoiceId = input.required<number>();
  // The invoice after a change made here, so the list can show its new total.
  readonly changed = output<InvoiceDetail>();

  protected readonly invoice = signal<InvoiceDetail | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly savingDiscount = signal(false);
  protected readonly discountError = signal('');
  protected readonly statusLabel = statusLabel;
  protected readonly money = money;

  protected readonly form = new FormGroup({
    description: new FormControl('', { nonNullable: true }),
    qty: new FormControl('', { nonNullable: true }),
    unitPrice: new FormControl('', { nonNullable: true }),
  });

  protected readonly discountForm = new FormGroup({
    pct: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    effect((onCleanup) => {
      const id = this.invoiceId();
      this.invoice.set(null);
      this.form.reset();
      this.error.set('');
      this.discountForm.reset();
      this.discountError.set('');
      const sub = this.service.open(id).subscribe((detail) => this.invoice.set(detail));
      onCleanup(() => sub.unsubscribe());
    });
  }

  protected addLine(invoiceId: number): void {
    const raw = this.form.getRawValue();
    const line: NewLine = {
      description: raw.description.trim(),
      qty: raw.qty.trim(),
      unitPrice: raw.unitPrice.trim().replace(/^\$/, ''),
    };
    const problem = problemWith(line);
    if (problem) {
      this.form.markAllAsTouched();
      this.error.set(problem);
      return;
    }
    this.saving.set(true);
    this.error.set('');
    this.service.addLine(invoiceId, line).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.form.reset();
        this.saving.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.error.set(
          typeof detail === 'string' && detail ? detail : 'The line could not be added.',
        );
        this.saving.set(false);
      },
    });
  }

  protected setDiscount(invoiceId: number): void {
    const pct = this.discountForm.getRawValue().pct.trim().replace(/%$/, '').trim();
    const problem = problemWithDiscount(pct);
    if (problem) {
      this.discountForm.markAllAsTouched();
      this.discountError.set(problem);
      return;
    }
    this.savingDiscount.set(true);
    this.discountError.set('');
    this.service.setDiscount(invoiceId, pct).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.discountForm.reset();
        this.savingDiscount.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.discountError.set(
          typeof detail === 'string' && detail ? detail : 'The discount could not be applied.',
        );
        this.savingDiscount.set(false);
      },
    });
  }
}
