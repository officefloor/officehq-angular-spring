import { Component, effect, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  InvoiceDetail,
  InvoiceLineView,
  InvoiceService,
  InvoiceSummary,
  NewDiscount,
  NewLine,
} from './invoice.service';
import { TWO_DECIMALS, instalmentStatusLabel, money, statusLabel, symbolOf } from './invoice-format';
import { PaymentForm } from './payment-form';

// A percentage from 0 to 100 with at most two decimals, e.g. 15 or 12.5; the server checks it again.
function problemWithDiscount(pct: string): string {
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

// A retention percentage from 0 to 20 with at most two decimals, e.g. 5 or 2.5; the server checks it again.
function problemWithRetention(pct: string): string {
  if (!/^-?\d+(\.\d+)?$/.test(pct)) {
    return 'The retention must be a number, like 5 or 2.5.';
  }
  if (Number(pct) < 0 || Number(pct) > 20) {
    return 'The retention must be from 0 to 20 percent.';
  }
  if (!TWO_DECIMALS.test(pct)) {
    return 'The retention can have at most two decimals.';
  }
  return '';
}

// A surcharge percentage from 0 to 25 with at most two decimals, e.g. 10 or 7.5; the server checks it again.
function problemWithSurcharge(pct: string): string {
  if (!/^-?\d+(\.\d+)?$/.test(pct)) {
    return 'The surcharge must be a number, like 10 or 7.5.';
  }
  if (Number(pct) < 0 || Number(pct) > 25) {
    return 'The surcharge must be from 0 to 25 percent.';
  }
  if (!TWO_DECIMALS.test(pct)) {
    return 'The surcharge can have at most two decimals.';
  }
  return '';
}

// A plan of 2 to 24 monthly instalments at an annual rate from 0 to 30 with at most two decimals,
// after an optional down payment from 0 to 50 percent with at most two decimals; the server checks
// them again.
function problemWithPlan(count: string, rate: string, down: string): string {
  if (!/^\d+$/.test(count)) {
    return 'The number of instalments must be a whole number, like 3 or 12.';
  }
  if (Number(count) < 2 || Number(count) > 24) {
    return 'The plan must have from 2 to 24 instalments.';
  }
  if (!/^-?\d+(\.\d+)?$/.test(rate)) {
    return 'The interest rate must be a number, like 12 or 7.5.';
  }
  if (Number(rate) < 0 || Number(rate) > 30) {
    return 'The interest rate must be from 0 to 30 percent.';
  }
  if (!TWO_DECIMALS.test(rate)) {
    return 'The interest rate can have at most two decimals.';
  }
  if (down !== '') {
    if (!/^-?\d+(\.\d+)?$/.test(down)) {
      return 'The down payment must be a number, like 10 or 12.5.';
    }
    if (Number(down) < 0 || Number(down) > 50) {
      return 'The down payment must be from 0 to 50 percent.';
    }
    if (!TWO_DECIMALS.test(down)) {
      return 'The down payment can have at most two decimals.';
    }
  }
  return '';
}

// A fixed amount of money, not negative and to the cent, e.g. 25 or 12.50; the server checks it is
// no more than the subtotal.
function problemWithDiscountAmount(amount: string): string {
  if (!/^-?\d+(\.\d+)?$/.test(amount)) {
    return 'The discount amount must be a number, like 25 or 12.50.';
  }
  if (Number(amount) < 0) {
    return 'The discount amount cannot be negative.';
  }
  if (!TWO_DECIMALS.test(amount)) {
    return 'The discount amount can have at most two decimals.';
  }
  return '';
}

// A cap on the retention held back: money, not negative and to the cent, e.g. 500 or 250.50.
function problemWithRetentionCap(cap: string): string {
  if (!/^-?\d+(\.\d+)?$/.test(cap)) {
    return 'The retention cap must be a number, like 500 or 250.50.';
  }
  if (Number(cap) < 0) {
    return 'The retention cap cannot be negative.';
  }
  if (!TWO_DECIMALS.test(cap)) {
    return 'The retention cap can have at most two decimals.';
  }
  return '';
}

// An amount of retention to release: money, more than zero and to the cent, and no more than is held.
function problemWithRelease(amount: string, held: string): string {
  if (!/^-?\d+(\.\d+)?$/.test(amount)) {
    return 'The amount to release must be a number, like 500 or 250.50.';
  }
  if (Number(amount) <= 0) {
    return 'The amount to release must be more than zero.';
  }
  if (!TWO_DECIMALS.test(amount)) {
    return 'The amount to release can have at most two decimals.';
  }
  if (Number(amount) > Number(held)) {
    return 'The amount to release cannot be more than is still held.';
  }
  return '';
}

// A cap on everything taken off, line discounts included: money, not negative and to the cent, e.g. 25 or 12.50.
function problemWithDiscountCap(cap: string): string {
  if (!/^-?\d+(\.\d+)?$/.test(cap)) {
    return 'The discount cap must be a number, like 25 or 12.50.';
  }
  if (Number(cap) < 0) {
    return 'The discount cap cannot be negative.';
  }
  if (!TWO_DECIMALS.test(cap)) {
    return 'The discount cap can have at most two decimals.';
  }
  return '';
}

// Either a percentage or a fixed amount, not both: the one given replaces the other. A cap is optional.
function problemWithDiscountForm(pct: string, amount: string, cap: string): string {
  if (cap && problemWithDiscountCap(cap)) {
    return problemWithDiscountCap(cap);
  }
  if (!pct && !amount) {
    return 'Enter a discount percentage or a discount amount.';
  }
  if (pct && amount) {
    return 'Enter either a discount percentage or a discount amount, not both.';
  }
  return pct ? problemWithDiscount(pct) : problemWithDiscountAmount(amount);
}

// What is wrong with a line, or '' when it can be added or saved.
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
  if (line.unitPrice === undefined) {
    if (line.unitType === undefined) {
      problems.push('Enter a unit price, or a unit type to price the line from the client\'s rate card or the price list.');
    }
  } else if (!TWO_DECIMALS.test(line.unitPrice)) {
    problems.push('The unit price must be a number with at most two decimals.');
  } else if (Number(line.unitPrice) < 0.01) {
    problems.push('The unit price must be at least $0.01.');
  }
  if (line.discountPct !== undefined) {
    if (!/^-?\d+(\.\d+)?$/.test(line.discountPct)) {
      problems.push('The line discount must be a number, like 15 or 12.5.');
    } else if (Number(line.discountPct) < 0 || Number(line.discountPct) > 100) {
      problems.push('The line discount must be from 0 to 100 percent.');
    } else if (!TWO_DECIMALS.test(line.discountPct)) {
      problems.push('The line discount can have at most two decimals.');
    }
  }
  if (line.taxRate !== undefined) {
    if (!/^-?\d+(\.\d+)?$/.test(line.taxRate)) {
      problems.push('The line tax rate must be a number, like 5 or 12.5.');
    } else if (Number(line.taxRate) < 0 || Number(line.taxRate) > 100) {
      problems.push('The line tax rate must be from 0 to 100 percent.');
    } else if (!TWO_DECIMALS.test(line.taxRate)) {
      problems.push('The line tax rate can have at most two decimals.');
    }
  }
  return problems.join(' ');
}

// One opened invoice: its status, its lines and its totals. A line may carry its own percentage
// discount, shown on the line, with its amount after it, and its own tax. A line's edit button loads it
// into the line form, which then saves the changes to that line (with the same checks, and refused when
// the subtotal would be less than a fixed discount) instead of adding one. A draft takes new lines and a
// percentage or fixed discount, can have lines removed (refused when what is left would be less than a
// fixed discount), and can be sent; a change to a sent invoice with no payments puts it back to
// draft, and once paid anything the server refuses further changes. A sent invoice takes payments
// until nothing is due, less than $2.00 (and no more than 1% of the grand total) left being written off; the panel lists them with what has
// been paid, what is still due and what was written off. The
// server refuses a payment on a draft, or dated after today or before the issue date; what a payment
// pays beyond what is due is held on the client's account as credit.
// The panel shows the invoice's RF payment reference; a payment may quote one, which must pass the
// ISO 11649 check and be this invoice's. When parts of a payment are filled in on the list, the
// payment is spread over those invoices instead; each payment shows how much of it went here.
// A sent invoice paid only by credit used when it was sent can be voided (the credit goes back to the
// client); the server refuses to void a draft or one with a recorded
// payment, and refuses any payment or change to a void invoice. A payment can be reversed (a bounced
// cheque): it is undone on every invoice it was applied to and never counts again. Any invoice can
// be duplicated into a new draft for the same client, which then opens. A draft's due date can be set
// by hand, from its issue date to 120 days after it; it is kept when the invoice is sent, and the
// server refuses to send it once that date is past. A draft can hold back retention, a percentage of
// the amount before tax, the subtotal less the discount (0 to 20); while held it is not part of the amount due, and once nothing else is due
// it can be released on the sent invoice, all of it or an amount of what is still held, all that is
// released falling due 30 days after the latest release. A draft without retention can
// instead be spread over 2 to 24 monthly instalments at an annual interest rate (0 to 30); the server
// works out the schedule from the grand total, dated from the issue date (and again when it is sent),
// and the amount due is then the plan total less what is paid.
@Component({
  selector: 'app-invoice-panel',
  imports: [ReactiveFormsModule, PaymentForm],
  template: `
    <section data-testid="invoice-panel" aria-labelledby="invoice-panel-heading">
      <h2 id="invoice-panel-heading">
        Invoice <span data-testid="invoice-panel-number">{{ invoice()?.number }}</span>
      </h2>
      @if (invoice(); as inv) {
        <p>Status: <span data-testid="invoice-panel-status">{{ statusLabel(inv.status) }}</span></p>
        @if (inv.paidDate) {
          <p>Paid on: <span data-testid="invoice-paid-date">{{ inv.paidDate }}</span></p>
        }
        <p>Payment reference: <span data-testid="invoice-reference">{{ inv.reference }}</span></p>
        <table>
          <thead>
            <tr>
              <th scope="col">Description</th>
              <th scope="col">Qty</th>
              <th scope="col">Billed qty</th>
              <th scope="col">Unit type</th>
              <th scope="col">Unit price</th>
              <th scope="col">Line discount</th>
              <th scope="col">Amount</th>
              <th scope="col">Discount share</th>
              <th scope="col">Tax status</th>
              <th scope="col">Tax rate</th>
              <th scope="col">Tax</th>
              <th scope="col">Net</th>
              <th scope="col">Actions</th>
            </tr>
          </thead>
          <tbody>
            @for (l of inv.lines; track l.id) {
              <tr [attr.data-testid]="'line-row-' + l.id">
                <td data-testid="line-description">{{ l.description }}</td>
                <td data-testid="line-qty">{{ l.qty }}</td>
                <td data-testid="line-billed-qty">{{ l.billedQty }}</td>
                <td data-testid="line-unit-type">{{ l.unitType ?? '' }}</td>
                <td data-testid="line-unit-price">{{ l.tiered ? 'Tiered from ' + amount(l.unitPrice) : amount(l.unitPrice) }}</td>
                <td data-testid="line-discount">{{ amount(l.discount) }}</td>
                <td data-testid="line-amount">{{ amount(l.amount) }}</td>
                <td data-testid="line-discount-share">{{ amount(l.discountShare) }}</td>
                <td data-testid="line-tax-exempt">{{ l.taxExempt ? 'Exempt' : 'Taxable' }}</td>
                <td data-testid="line-tax-rate">{{ (l.taxRate ?? inv.taxRate) + '%' }}</td>
                <td data-testid="line-tax">{{ amount(l.tax) }}</td>
                <td data-testid="line-net">{{ amount(l.net) }}</td>
                <td>
                  <button
                    type="button"
                    data-testid="line-edit"
                    [disabled]="saving()"
                    [attr.aria-label]="'Edit line ' + l.description"
                    (click)="editLine(l)"
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    data-testid="line-remove"
                    [disabled]="saving()"
                    [attr.aria-label]="'Remove line ' + l.description"
                    (click)="removeLine(inv.id, l.id)"
                  >
                    Remove
                  </button>
                  @if (inv.creditOf === null && inv.status !== 'DRAFT' && inv.status !== 'VOID') {
                    <button
                      type="button"
                      data-testid="line-credit"
                      [disabled]="crediting()"
                      [attr.aria-label]="'Credit line ' + l.description"
                      (click)="creditLine(inv.id, l.id)"
                    >
                      Credit
                    </button>
                  }
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="13" data-testid="line-empty">No lines yet.</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="10">Subtotal</th>
              <td data-testid="invoice-subtotal">{{ amount(inv.subtotal) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="10">
                @if (+inv.discountAmount > 0) {
                  Discount (fixed@if (inv.discountCap !== null) {, max {{ amount(inv.discountCap) }} in all})
                } @else {
                  Discount ({{ inv.discountPct }}%@if (inv.discountCap !== null) {, max
                    {{ amount(inv.discountCap) }} in all})
                }
              </th>
              <td data-testid="invoice-discount">{{ amount(inv.discount) }}</td>
            </tr>
            @if (inv.discountReason) {
              <tr>
                <th scope="row" colspan="10">Discount reason</th>
                <td data-testid="invoice-discount-reason">{{ inv.discountReason }}</td>
              </tr>
            }
            <tr>
              <th scope="row" colspan="10">Surcharge ({{ inv.surchargePct }}%)</th>
              <td data-testid="invoice-surcharge">{{ amount(inv.surcharge) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="10">Minimum charge</th>
              <td data-testid="invoice-minimum-charge">{{ amount(inv.minimumCharge) }}</td>
            </tr>
            @if (inv.reverseCharge) {
              <tr>
                <td colspan="11" data-testid="invoice-reverse-charge">Reverse charge</td>
              </tr>
            }
            <tr>
              <th scope="row" colspan="10">Amount before tax</th>
              <td data-testid="invoice-net">{{ amount(inv.net) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="10">
                @if (inv.pricesIncludeTax && !inv.reverseCharge) {
                  Tax included (lines at their own rate, otherwise {{ inv.taxRate }}%)
                } @else {
                  Tax (lines at their own rate, otherwise {{ inv.taxRate }}%)
                }
              </th>
              <td data-testid="invoice-tax">{{ amount(inv.tax) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="10">Total</th>
              <td data-testid="invoice-grand-total">{{ amount(inv.total) }}</td>
            </tr>
            @if (+inv.withholdingPct > 0) {
              <tr>
                <th scope="row" colspan="10">Withheld by the client for its tax office</th>
                <td data-testid="invoice-withholding">{{ amount(inv.withholding) }}</td>
              </tr>
            }
            <tr>
              <th scope="row" colspan="10">
                Retention ({{ inv.retentionPct }}%@if (inv.retentionCap !== null) {, max
                  {{ amount(inv.retentionCap) }}}@if (inv.retentionReleasedOn) {, released
                  {{ inv.retentionReleasedOn }}, due {{ inv.retentionDueDate }}}@if (+inv.retentionHeld > 0) {, held back})
              </th>
              <td data-testid="invoice-retention">{{ amount(inv.retention) }}</td>
            </tr>
            @if (+inv.retention > 0) {
              <tr>
                <th scope="row" colspan="10">Retention still held</th>
                <td data-testid="invoice-retention-held">{{ amount(inv.retentionHeld) }}</td>
              </tr>
            }
          </tfoot>
        </table>

        @if (inv.instalments !== null) {
          <table aria-label="Instalments">
            <caption>
              {{ inv.instalments }} monthly instalments at {{ inv.instalmentRate }}% a year@if (inv.planDownPct !== '0') {, after a {{ inv.planDownPct }}% down payment}
            </caption>
            <thead>
              <tr>
                <th scope="col">No.</th>
                <th scope="col">Due</th>
                <th scope="col">Payment</th>
                <th scope="col">Interest</th>
                <th scope="col">Principal</th>
                <th scope="col">Balance</th>
                <th scope="col">Paid</th>
                <th scope="col">Late fee</th>
                <th scope="col">Late interest</th>
                <th scope="col">Status</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              @for (s of inv.schedule; track s.number) {
                <tr [attr.data-testid]="'instalment-row-' + s.number">
                  <td>{{ s.number }}</td>
                  <td data-testid="instalment-due">{{ s.dueDate }}</td>
                  <td data-testid="instalment-payment">{{ amount(s.payment) }}</td>
                  <td data-testid="instalment-interest">{{ amount(s.interest) }}</td>
                  <td data-testid="instalment-principal">{{ amount(s.principal) }}</td>
                  <td data-testid="instalment-balance">{{ amount(s.balance) }}</td>
                  <td data-testid="instalment-paid">{{ amount(s.paid) }}</td>
                  <td data-testid="instalment-late-fee">{{ amount(s.lateFee) }}</td>
                  <td data-testid="instalment-late-interest">{{ amount(s.lateInterest) }}</td>
                  <td data-testid="instalment-status">{{ instalmentStatusLabel(s.status) }}</td>
                  <td>
                    @if (s.number > 0 && inv.status !== 'DRAFT' && inv.status !== 'VOID') {
                      <button
                        type="button"
                        data-testid="instalment-skip"
                        [disabled]="skipping()"
                        [attr.aria-label]="'Skip instalment ' + s.number"
                        (click)="skipInstalment(inv.id, s.number)"
                      >
                        Skip
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" colspan="5">Plan total</th>
                <td data-testid="invoice-plan-total">{{ amount(inv.planTotal ?? '0') }}</td>
                <td colspan="5"></td>
              </tr>
            </tfoot>
          </table>
          @if (skipError()) {
            <p role="alert" data-testid="instalment-skip-error">{{ skipError() }}</p>
          }
        }

        <table aria-label="Payments">
          <thead>
            <tr>
              <th scope="col">Paid on</th>
              <th scope="col">Amount</th>
              <th scope="col">Card surcharge</th>
              <th scope="col">To this invoice</th>
              <th scope="col">Reference</th>
              <th scope="col">Actions</th>
            </tr>
          </thead>
          <tbody>
            @for (p of inv.payments; track p.id) {
              <tr [attr.data-testid]="'payment-row-' + p.id">
                <td data-testid="payment-date">{{ p.date }}</td>
                <td data-testid="payment-amount">{{ money(p.amount, p.currency) }}</td>
                <td data-testid="payment-surcharge">{{ amount(p.surcharge) }}</td>
                <td data-testid="payment-allocated">{{ amount(p.allocated) }}</td>
                <td data-testid="payment-reference">{{ p.reference ?? '' }}</td>
                <td>
                  <button
                    type="button"
                    data-testid="payment-reverse"
                    [disabled]="reversing()"
                    [attr.aria-label]="'Reverse payment of ' + amount(p.amount) + ' on ' + p.date"
                    (click)="reversePayment(inv.id, inv.clientId, p.id)"
                  >
                    Reverse
                  </button>
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="6" data-testid="payment-empty">No payments yet.</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="5">Paid</th>
              <td data-testid="invoice-paid">{{ amount(inv.paid) }}</td>
            </tr>
            @if (inv.creditOf === null) {
              <tr>
                <th scope="row" colspan="5">Credited</th>
                <td data-testid="invoice-credited">{{ amount(inv.credited) }}</td>
              </tr>
            }
            @if (inv.creditOf === null) {
              <tr>
                <th scope="row" colspan="5">Early-payment discount</th>
                <td data-testid="invoice-early-discount">{{ amount(inv.earlyDiscount) }}</td>
              </tr>
            }
            @if (inv.creditOf === null) {
              <tr>
                <th scope="row" colspan="5">Late fee</th>
                <td data-testid="invoice-late-fee">{{ amount(inv.lateFee) }}</td>
              </tr>
            }
            @if (inv.creditOf === null) {
              <tr>
                <th scope="row" colspan="5">Late interest</th>
                <td data-testid="invoice-interest">{{ amount(inv.lateInterest) }}</td>
              </tr>
            }
            @if (inv.creditOf === null) {
              <tr>
                <th scope="row" colspan="5">Late charges waived</th>
                <td data-testid="invoice-waived">{{ amount(inv.lateWaived) }}</td>
              </tr>
            }
            <tr>
              <th scope="row" colspan="5">Amount due</th>
              <td data-testid="invoice-amount-due">{{ amount(inv.amountDue) }}</td>
            </tr>
            @if (inv.creditOf === null) {
              <tr>
                <th scope="row" colspan="5">Amount due in cash (rounded down to the coin below)</th>
                <td data-testid="invoice-cash-due">{{ amount(inv.cashDue) }}</td>
              </tr>
              <tr>
                <th scope="row" colspan="5">Cash rounding</th>
                <td data-testid="invoice-cash-rounding">{{ amount(inv.cashRounding) }}</td>
              </tr>
            }
            @if (inv.settleAmount !== null) {
              <tr>
                <th scope="row" colspan="5">Settles it if paid now (early-payment discount)</th>
                <td data-testid="invoice-settle-amount">{{ amount(inv.settleAmount) }}</td>
              </tr>
            }
            @if (inv.payoff !== null) {
              <tr>
                <th scope="row" colspan="5">Pays off the plan if paid now</th>
                <td data-testid="invoice-payoff">{{ amount(inv.payoff) }}</td>
              </tr>
            }
            @if (inv.instalments !== null) {
              <tr>
                <th scope="row" colspan="5">Interest saved by paying off early</th>
                <td data-testid="invoice-interest-saved">{{ amount(inv.interestSaved) }}</td>
              </tr>
            }
            @if (inv.fxDifference !== null) {
              <tr>
                <th scope="row" colspan="5">Exchange difference (US dollars)</th>
                <td data-testid="invoice-fx-difference">{{ money(inv.fxDifference) }}</td>
              </tr>
            }
            <tr>
              <th scope="row" colspan="5">
                {{ inv.writtenOffOn === null ? 'Written off (small balance left)' : 'Written off as a bad debt' }}
              </th>
              <td data-testid="invoice-written-off">{{ amount(inv.writtenOff) }}</td>
            </tr>
          </tfoot>
        </table>
        @if (reverseError()) {
          <p role="alert" data-testid="payment-reverse-error">{{ reverseError() }}</p>
        }

        @if (
          inv.creditOf === null &&
          inv.status !== 'PAID' &&
          inv.status !== 'PAID_LATE' &&
          inv.status !== 'CREDITED' &&
          inv.status !== 'RETENTION_HELD'
        ) {
          <app-payment-form
            [clientId]="inv.clientId"
            [currency]="inv.currency"
            [invoiceId]="inv.id"
            (paid)="paymentRecorded($event)"
            (split)="splitRecorded(inv.id, $event)"
            (deposited)="splitRecorded(inv.id, $event)"
          />
        }

        @if (inv.status === 'DRAFT' && inv.creditOf === null) {
          <form [formGroup]="dueForm" (ngSubmit)="setDueDate(inv.id)" aria-label="Due date" novalidate>
            <label for="invoice-due-date">Due date (up to 120 days after the issue date)</label>
            <input id="invoice-due-date" type="date" data-testid="invoice-due-input" formControlName="dueDate" />
            <button type="submit" data-testid="invoice-due-submit" [disabled]="savingDue()">Set due date</button>
            @if (dueError()) {
              <p role="alert" data-testid="invoice-due-error">{{ dueError() }}</p>
            }
          </form>
        }

        @if (inv.creditOf === null) {
          <form [formGroup]="surchargeForm" (ngSubmit)="setSurcharge(inv.id)" aria-label="Surcharge" novalidate>
            <label for="surcharge-pct">Rush surcharge (%, 0 to 25, drafts only)</label>
            <input id="surcharge-pct" data-testid="surcharge-form-pct" inputmode="decimal" formControlName="pct" />
            <button type="submit" data-testid="surcharge-form-submit" [disabled]="savingSurcharge()">
              Set surcharge
            </button>
            @if (surchargeError()) {
              <p role="alert" data-testid="surcharge-form-error">{{ surchargeError() }}</p>
            }
          </form>
        }

        @if (inv.status === 'DRAFT' && inv.creditOf === null) {
          <form [formGroup]="retentionForm" (ngSubmit)="setRetention(inv.id)" aria-label="Retention" novalidate>
            <label for="retention-pct">Retention (%, 0 to 20)</label>
            <input id="retention-pct" data-testid="retention-form-pct" inputmode="decimal" formControlName="pct" />
            <label for="retention-cap">capped at ({{ symbol() }}, optional)</label>
            <input id="retention-cap" data-testid="retention-form-cap" inputmode="decimal" formControlName="cap" />
            <button type="submit" data-testid="retention-form-submit" [disabled]="savingRetention()">
              Set retention
            </button>
            @if (retentionError()) {
              <p role="alert" data-testid="retention-form-error">{{ retentionError() }}</p>
            }
          </form>
        }

        @if (inv.status === 'DRAFT' && inv.creditOf === null) {
          <form [formGroup]="planForm" (ngSubmit)="setPlan(inv.id)" aria-label="Instalment plan" novalidate>
            <label for="plan-count">Monthly instalments (2 to 24)</label>
            <input id="plan-count" data-testid="plan-form-count" inputmode="numeric" formControlName="count" />
            <label for="plan-rate">Annual interest rate (%, 0 to 30)</label>
            <input id="plan-rate" data-testid="plan-form-rate" inputmode="decimal" formControlName="rate" />
            <label for="plan-down">Down payment (% of the total, 0 to 50, optional)</label>
            <input id="plan-down" data-testid="plan-form-down" inputmode="decimal" formControlName="down" />
            <button type="submit" data-testid="plan-form-submit" [disabled]="savingPlan()">Set plan</button>
            @if (planError()) {
              <p role="alert" data-testid="plan-form-error">{{ planError() }}</p>
            }
          </form>
        }

        @if (
          inv.creditOf === null &&
          inv.status !== 'DRAFT' &&
          inv.status !== 'VOID' &&
          +inv.retentionHeld > 0
        ) {
          <form [formGroup]="releaseForm" (ngSubmit)="releaseRetention(inv.id, inv.retentionHeld)" aria-label="Release retention" novalidate>
            <label for="retention-release-amount">Amount to release ({{ symbol() }}, empty for all still held)</label>
            <input
              id="retention-release-amount"
              data-testid="retention-release-amount"
              inputmode="decimal"
              formControlName="amount"
            />
            <button type="submit" data-testid="invoice-release-retention" [disabled]="releasing()">
              Release retention
            </button>
          </form>
        }
        @if (releaseError()) {
          <p role="alert" data-testid="invoice-release-error">{{ releaseError() }}</p>
        }

        @if (inv.creditOf === null && inv.status !== 'DRAFT' && inv.status !== 'VOID' && inv.status !== 'WRITTEN_OFF') {
          <button
            type="button"
            data-testid="invoice-waive"
            [disabled]="waiving()"
            (click)="waiveInterest(inv.id)"
          >
            Waive late charges
          </button>
        }
        @if (waiveError()) {
          <p role="alert" data-testid="invoice-waive-error">{{ waiveError() }}</p>
        }

        @if (inv.creditOf === null && inv.status !== 'WRITTEN_OFF') {
          <button type="button" data-testid="invoice-write-off" [disabled]="writingOff()" (click)="writeOff(inv.id)">
            Write off
          </button>
        }
        @if (writeOffError()) {
          <p role="alert" data-testid="invoice-write-off-error">{{ writeOffError() }}</p>
        }

        @if (inv.status === 'DRAFT') {
          <button type="button" data-testid="invoice-send" [disabled]="sending()" (click)="send(inv)">
            {{ inv.creditOf === null ? 'Send invoice' : 'Issue credit note' }}
          </button>
          @if (sendError()) {
            <p role="alert" data-testid="invoice-send-error">{{ sendError() }}</p>
          }
        }

        @if (inv.creditOf === null && inv.status !== 'DRAFT' && inv.status !== 'VOID') {
          <button type="button" data-testid="invoice-credit" [disabled]="crediting()" (click)="creditNote(inv.id)">
            Credit note
          </button>
          @if (creditError()) {
            <p role="alert" data-testid="invoice-credit-error">{{ creditError() }}</p>
          }
        }

        <button
          type="button"
          data-testid="invoice-duplicate"
          [disabled]="duplicating()"
          (click)="duplicate(inv.id)"
        >
          Duplicate invoice
        </button>
        @if (duplicateError()) {
          <p role="alert" data-testid="invoice-duplicate-error">{{ duplicateError() }}</p>
        }

        @if (inv.status !== 'VOID' && inv.status !== 'CREDIT_NOTE') {
          <button type="button" data-testid="invoice-void" [disabled]="voiding()" (click)="voidInvoice(inv.id)">
            Void invoice
          </button>
        }
        @if (voidError()) {
          <p role="alert" data-testid="invoice-void-error">{{ voidError() }}</p>
        }

        <form [formGroup]="form" (ngSubmit)="saveLine(inv.id)" [attr.aria-label]="editingLineId() === null ? 'Add a line' : 'Edit a line'" novalidate>
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
          <label for="line-unit-type">Unit type (optional, e.g. hour)</label>
          <input
            id="line-unit-type"
            data-testid="line-form-unit-type"
            formControlName="unitType"
          />
          <label for="line-unit-price">Unit price ({{ symbol() }}, optional with a unit type on the rate card or price list)</label>
          <input
            id="line-unit-price"
            data-testid="line-form-unit-price"
            inputmode="decimal"
            formControlName="unitPrice"
          />
          <label for="line-discount-pct">Line discount (%, optional)</label>
          <input
            id="line-discount-pct"
            data-testid="line-form-discount-pct"
            inputmode="decimal"
            formControlName="discountPct"
          />
          <label for="line-tax-rate">Tax rate (%, optional)</label>
          <input
            id="line-tax-rate"
            data-testid="line-form-tax-rate"
            inputmode="decimal"
            formControlName="taxRate"
          />
          <input
            id="line-overtime"
            type="checkbox"
            data-testid="line-form-overtime"
            formControlName="overtime"
          />
          <label for="line-overtime">Overtime (time and a half)</label>
          <input
            id="line-exempt"
            type="checkbox"
            data-testid="line-form-exempt"
            formControlName="taxExempt"
          />
          <label for="line-exempt">Tax exempt</label>
          <button type="submit" data-testid="line-form-submit" [disabled]="saving()">
            {{ editingLineId() === null ? 'Add line' : 'Save line' }}
          </button>
          @if (editingLineId() !== null) {
            <button type="button" (click)="cancelEdit()">Cancel</button>
          }
          @if (error()) {
            <p role="alert" data-testid="line-form-error">{{ error() }}</p>
          }
        </form>

        @if (inv.creditOf === null) {
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
            />
            <label for="discount-amount">or amount ({{ symbol() }})</label>
            <input
              id="discount-amount"
              data-testid="discount-form-amount"
              inputmode="decimal"
              formControlName="amount"
            />
            <label for="discount-cap">capped at ({{ symbol() }}, optional)</label>
            <input
              id="discount-cap"
              data-testid="discount-form-cap"
              inputmode="decimal"
              formControlName="cap"
            />
            <label for="discount-reason">reason (needed over 20% of the subtotal)</label>
            <input
              id="discount-reason"
              data-testid="discount-form-reason"
              maxlength="500"
              formControlName="reason"
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
  // Bumped when the invoice was changed elsewhere (sent with the client's other drafts), to load it again.
  readonly reload = input(0);
  // The invoice after a change made here, so the list can show its new total.
  readonly changed = output<InvoiceDetail>();
  // The client's invoices after a deposit or a payment spread over several of them.
  readonly split = output<InvoiceSummary[]>();
  // A new draft copied from this invoice, to be listed and opened.
  readonly duplicated = output<InvoiceDetail>();
  // A new draft credit note against this invoice, to be listed and opened.
  readonly credited = output<InvoiceDetail>();

  protected readonly invoice = signal<InvoiceDetail | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  // The line loaded into the line form to be changed, or null when the form adds a new line.
  protected readonly editingLineId = signal<number | null>(null);
  protected readonly savingDiscount = signal(false);
  protected readonly discountError = signal('');
  protected readonly savingDue = signal(false);
  protected readonly dueError = signal('');
  protected readonly savingRetention = signal(false);
  protected readonly retentionError = signal('');
  protected readonly savingSurcharge = signal(false);
  protected readonly surchargeError = signal('');
  protected readonly savingPlan = signal(false);
  protected readonly planError = signal('');
  protected readonly releasing = signal(false);
  protected readonly releaseError = signal('');
  protected readonly waiving = signal(false);
  protected readonly skipping = signal(false);
  protected readonly skipError = signal('');
  protected readonly waiveError = signal('');
  protected readonly writingOff = signal(false);
  protected readonly writeOffError = signal('');
  protected readonly sending = signal(false);
  protected readonly sendError = signal('');
  protected readonly duplicating = signal(false);
  protected readonly duplicateError = signal('');
  protected readonly crediting = signal(false);
  protected readonly creditError = signal('');
  protected readonly voiding = signal(false);
  protected readonly voidError = signal('');
  protected readonly reversing = signal(false);
  protected readonly reverseError = signal('');
  protected readonly statusLabel = statusLabel;
  // A payment's amount is shown in the currency it was paid in.
  protected readonly money = money;
  protected readonly instalmentStatusLabel = instalmentStatusLabel;
  // An amount shown in the currency of the open invoice's client.
  protected amount(value: string): string {
    return money(value, this.invoice()?.currency ?? 'USD');
  }
  // The symbol amounts on the open invoice are entered in.
  protected symbol(): string {
    return symbolOf(this.invoice()?.currency ?? 'USD');
  }

  protected readonly form = new FormGroup({
    description: new FormControl('', { nonNullable: true }),
    qty: new FormControl('', { nonNullable: true }),
    unitType: new FormControl('', { nonNullable: true }),
    unitPrice: new FormControl('', { nonNullable: true }),
    overtime: new FormControl(false, { nonNullable: true }),
    taxExempt: new FormControl(false, { nonNullable: true }),
    discountPct: new FormControl('', { nonNullable: true }),
    taxRate: new FormControl('', { nonNullable: true }),
  });

  protected readonly discountForm = new FormGroup({
    pct: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
    reason: new FormControl('', { nonNullable: true }),
  });

  protected readonly retentionForm = new FormGroup({
    pct: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
  });

  protected readonly surchargeForm = new FormGroup({
    pct: new FormControl('', { nonNullable: true }),
  });

  protected readonly releaseForm = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
  });

  protected readonly planForm = new FormGroup({
    count: new FormControl('', { nonNullable: true }),
    rate: new FormControl('', { nonNullable: true }),
    down: new FormControl('', { nonNullable: true }),
  });

  protected readonly dueForm = new FormGroup({
    dueDate: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    effect((onCleanup) => {
      const id = this.invoiceId();
      this.reload();
      this.invoice.set(null);
      this.form.reset();
      this.editingLineId.set(null);
      this.error.set('');
      this.discountForm.reset();
      this.discountError.set('');
      this.dueForm.reset();
      this.dueError.set('');
      this.retentionForm.reset();
      this.retentionError.set('');
      this.surchargeForm.reset();
      this.surchargeError.set('');
      this.planForm.reset();
      this.planError.set('');
      this.releaseForm.reset();
      this.releaseError.set('');
      this.waiveError.set('');
      this.skipError.set('');
      this.writeOffError.set('');
      this.sendError.set('');
      this.voidError.set('');
      this.duplicateError.set('');
      this.creditError.set('');
      this.reverseError.set('');
      const sub = this.service.open(id).subscribe((detail) => this.invoice.set(detail));
      onCleanup(() => sub.unsubscribe());
    });
  }

  // Loads a line into the line form, so submitting it changes that line instead of adding one.
  protected editLine(line: InvoiceLineView): void {
    this.form.setValue({
      description: line.description,
      qty: line.qty,
      unitType: line.unitType ?? '',
      // A tiered line has no price of its own: left empty, it is repriced from the rate card's bands.
      // An overtime line keeps the price it was given; time and a half is worked out on it again.
      unitPrice: line.tiered ? '' : line.basePrice,
      overtime: line.overtime,
      taxExempt: line.taxExempt,
      discountPct: Number(line.discountPct) === 0 ? '' : line.discountPct,
      taxRate: line.taxRate ?? '',
    });
    this.editingLineId.set(line.id);
    this.error.set('');
  }

  protected cancelEdit(): void {
    this.form.reset();
    this.editingLineId.set(null);
    this.error.set('');
  }

  protected saveLine(invoiceId: number): void {
    const raw = this.form.getRawValue();
    const line: NewLine = {
      description: raw.description.trim(),
      qty: raw.qty.trim(),
      overtime: raw.overtime,
      taxExempt: raw.taxExempt,
    };
    const unitType = raw.unitType.trim();
    if (unitType) {
      line.unitType = unitType;
    }
    const unitPrice = raw.unitPrice.trim().replace(/^\$/, '');
    if (unitPrice) {
      line.unitPrice = unitPrice;
    }
    const discountPct = raw.discountPct.trim().replace(/%$/, '').trim();
    if (discountPct) {
      line.discountPct = discountPct;
    }
    const taxRate = raw.taxRate.trim().replace(/%$/, '').trim();
    if (taxRate) {
      line.taxRate = taxRate;
    }
    const problem = problemWith(line);
    if (problem) {
      this.form.markAllAsTouched();
      this.error.set(problem);
      return;
    }
    this.saving.set(true);
    this.error.set('');
    const lineId = this.editingLineId();
    const request =
      lineId === null
        ? this.service.addLine(invoiceId, line)
        : this.service.updateLine(invoiceId, lineId, line);
    request.subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.form.reset();
        this.editingLineId.set(null);
        this.saving.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        const fallback = lineId === null ? 'The line could not be added.' : 'The line could not be changed.';
        this.error.set(typeof detail === 'string' && detail ? detail : fallback);
        this.saving.set(false);
      },
    });
  }

  protected removeLine(invoiceId: number, lineId: number): void {
    this.saving.set(true);
    this.error.set('');
    this.service.removeLine(invoiceId, lineId).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        if (this.editingLineId() === lineId) {
          this.form.reset();
          this.editingLineId.set(null);
        }
        this.changed.emit(detail);
        this.saving.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.error.set(
          typeof detail === 'string' && detail ? detail : 'The line could not be removed.',
        );
        this.saving.set(false);
      },
    });
  }

  protected setDiscount(invoiceId: number): void {
    const raw = this.discountForm.getRawValue();
    const pct = raw.pct.trim().replace(/%$/, '').trim();
    const amount = raw.amount.trim().replace(/^\$/, '').trim();
    const cap = raw.cap.trim().replace(/^\$/, '').trim();
    const problem = problemWithDiscountForm(pct, amount, cap);
    if (problem) {
      this.discountForm.markAllAsTouched();
      this.discountError.set(problem);
      return;
    }
    this.savingDiscount.set(true);
    this.discountError.set('');
    const discount: NewDiscount = pct ? { discountPct: pct } : { discountAmount: amount };
    if (cap) {
      discount.discountCap = cap;
    }
    const reason = raw.reason.trim();
    if (reason) {
      discount.discountReason = reason;
    }
    this.service.setDiscount(invoiceId, discount).subscribe({
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

  protected paymentRecorded(detail: InvoiceDetail): void {
    this.invoice.set(detail);
    this.changed.emit(detail);
  }

  // A payment was spread over several invoices: show this one again with its share.
  protected splitRecorded(invoiceId: number, list: InvoiceSummary[]): void {
    this.split.emit(list);
    this.service.open(invoiceId).subscribe((detail) => this.invoice.set(detail));
  }

  protected setDueDate(invoiceId: number): void {
    const dueDate = this.dueForm.getRawValue().dueDate.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(dueDate)) {
      this.dueForm.markAllAsTouched();
      this.dueError.set('Enter the due date as a date, like 2026-04-30.');
      return;
    }
    this.savingDue.set(true);
    this.dueError.set('');
    this.service.setDueDate(invoiceId, dueDate).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.dueForm.reset();
        this.savingDue.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.dueError.set(
          typeof detail === 'string' && detail ? detail : 'The due date could not be set.',
        );
        this.savingDue.set(false);
      },
    });
  }

  protected setRetention(invoiceId: number): void {
    const raw = this.retentionForm.getRawValue();
    const pct = raw.pct.trim().replace(/%$/, '').trim();
    const cap = raw.cap.trim().replace(/^\$/, '').trim();
    const problem = problemWithRetention(pct) || (cap ? problemWithRetentionCap(cap) : '');
    if (problem) {
      this.retentionForm.markAllAsTouched();
      this.retentionError.set(problem);
      return;
    }
    this.savingRetention.set(true);
    this.retentionError.set('');
    this.service.setRetention(invoiceId, pct, cap || undefined).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.retentionForm.reset();
        this.savingRetention.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.retentionError.set(
          typeof detail === 'string' && detail ? detail : 'The retention could not be set.',
        );
        this.savingRetention.set(false);
      },
    });
  }

  protected setSurcharge(invoiceId: number): void {
    const pct = this.surchargeForm.getRawValue().pct.trim().replace(/%$/, '').trim();
    const problem = problemWithSurcharge(pct);
    if (problem) {
      this.surchargeForm.markAllAsTouched();
      this.surchargeError.set(problem);
      return;
    }
    this.savingSurcharge.set(true);
    this.surchargeError.set('');
    this.service.setSurcharge(invoiceId, pct).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.surchargeForm.reset();
        this.savingSurcharge.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.surchargeError.set(
          typeof detail === 'string' && detail ? detail : 'The surcharge could not be set.',
        );
        this.savingSurcharge.set(false);
      },
    });
  }

  protected setPlan(invoiceId: number): void {
    const raw = this.planForm.getRawValue();
    const count = raw.count.trim();
    const rate = raw.rate.trim().replace(/%$/, '').trim();
    const down = raw.down.trim().replace(/%$/, '').trim();
    const problem = problemWithPlan(count, rate, down);
    if (problem) {
      this.planForm.markAllAsTouched();
      this.planError.set(problem);
      return;
    }
    this.savingPlan.set(true);
    this.planError.set('');
    this.service.setPlan(invoiceId, count, rate, down || '0').subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.planForm.reset();
        this.savingPlan.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.planError.set(
          typeof detail === 'string' && detail ? detail : 'The plan could not be set.',
        );
        this.savingPlan.set(false);
      },
    });
  }

  protected releaseRetention(invoiceId: number, held: string): void {
    const amount = this.releaseForm.getRawValue().amount.trim();
    const problem = amount ? problemWithRelease(amount, held) : '';
    if (problem) {
      this.releaseForm.markAllAsTouched();
      this.releaseError.set(problem);
      return;
    }
    this.releasing.set(true);
    this.releaseError.set('');
    this.service.releaseRetention(invoiceId, amount || undefined).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.releaseForm.reset();
        this.changed.emit(detail);
        this.releasing.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.releaseError.set(
          typeof detail === 'string' && detail ? detail : 'The retention could not be released.',
        );
        this.releasing.set(false);
      },
    });
  }

  protected waiveInterest(invoiceId: number): void {
    this.waiving.set(true);
    this.waiveError.set('');
    this.service.waiveInterest(invoiceId).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.waiving.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.waiveError.set(
          typeof detail === 'string' && detail ? detail : 'The late charges could not be waived.',
        );
        this.waiving.set(false);
      },
    });
  }

  protected skipInstalment(invoiceId: number, instalment: number): void {
    this.skipping.set(true);
    this.skipError.set('');
    this.service.skipInstalment(invoiceId, instalment).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.skipping.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.skipError.set(
          typeof detail === 'string' && detail ? detail : 'The instalment could not be skipped.',
        );
        this.skipping.set(false);
      },
    });
  }

  protected writeOff(invoiceId: number): void {
    this.writingOff.set(true);
    this.writeOffError.set('');
    this.service.writeOff(invoiceId).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.writingOff.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.writeOffError.set(
          typeof detail === 'string' && detail ? detail : 'The invoice could not be written off.',
        );
        this.writingOff.set(false);
      },
    });
  }

  // Sends a draft; an issued credit note also changes what its original owes, so the client's
  // invoices are shown again.
  protected send(inv: InvoiceDetail): void {
    this.sending.set(true);
    this.sendError.set('');
    this.service.send(inv.id).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        if (detail.creditOf !== null) {
          this.service.forClient(detail.clientId).subscribe((list) => this.split.emit(list));
        }
        this.sending.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.sendError.set(
          typeof detail === 'string' && detail ? detail : 'The invoice could not be sent.',
        );
        this.sending.set(false);
      },
    });
  }

  protected duplicate(invoiceId: number): void {
    this.duplicating.set(true);
    this.duplicateError.set('');
    this.service.duplicate(invoiceId).subscribe({
      next: (detail) => {
        this.duplicating.set(false);
        this.duplicated.emit(detail);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.duplicateError.set(
          typeof detail === 'string' && detail ? detail : 'The invoice could not be duplicated.',
        );
        this.duplicating.set(false);
      },
    });
  }

  protected creditNote(invoiceId: number): void {
    this.crediting.set(true);
    this.creditError.set('');
    this.service.creditNote(invoiceId).subscribe({
      next: (detail) => {
        this.crediting.set(false);
        this.credited.emit(detail);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.creditError.set(
          typeof detail === 'string' && detail ? detail : 'The credit note could not be started.',
        );
        this.crediting.set(false);
      },
    });
  }

  // Adds a copy of the line to the invoice's draft credit note (starting one if needed), then opens it.
  protected creditLine(invoiceId: number, lineId: number): void {
    this.crediting.set(true);
    this.creditError.set('');
    this.service.creditLine(invoiceId, lineId).subscribe({
      next: (detail) => {
        this.crediting.set(false);
        this.credited.emit(detail);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.creditError.set(
          typeof detail === 'string' && detail ? detail : 'The line could not be credited.',
        );
        this.crediting.set(false);
      },
    });
  }

  protected voidInvoice(invoiceId: number): void {
    this.voiding.set(true);
    this.voidError.set('');
    this.service.void(invoiceId).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.voiding.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.voidError.set(
          typeof detail === 'string' && detail ? detail : 'The invoice could not be voided.',
        );
        this.voiding.set(false);
      },
    });
  }

  // A payment was reversed: show this invoice again, and the client's other invoices it was applied to.
  protected reversePayment(invoiceId: number, clientId: number, paymentId: number): void {
    this.reversing.set(true);
    this.reverseError.set('');
    this.service.reversePayment(invoiceId, paymentId).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.service.forClient(clientId).subscribe((list) => this.split.emit(list));
        this.reversing.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.reverseError.set(
          typeof detail === 'string' && detail ? detail : 'The payment could not be reversed.',
        );
        this.reversing.set(false);
      },
    });
  }
}
