import { DecimalPipe } from '@angular/common';
import { MoneyPipe } from '../currencies/money.pipe';
import { Component, Injector, afterNextRender, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { Notes } from '../notes/notes';
import { InvoicePayments } from '../payments/invoice-payments';
import { InvoiceCreditNotes } from '../credit-notes/invoice-credit-notes';
import { CreditNoteService } from '../credit-notes/credit-note.service';
import { InvoiceInstalments } from '../instalments/invoice-instalments';
import { InvoiceDetail, InvoiceService, LineItem } from './invoice.service';

const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;
// A unit price may be a fraction of a cent; each line is rounded to the cent on its own.
const FOUR_DECIMALS = /^\d+(\.\d{1,4})?$/;

// A single invoice: the client's tax number when they are tax registered, the things it charges for (description, how many and of what, price each), each line's
// amount, their subtotal, each discount on it (a percentage or a flat amount) as its own negative line and what they take off combined before tax, the taxable amount (leaving out tax-free lines), any
// sales tax added on it after the discount, any levy (a second tax) added on the same base, the effective tax rate
// (the tax and levy as a percentage of the total before tax), any flat surcharge (such as a handling fee, added after tax and
// never taxed), the total before tax, and the final total including both taxes (also shown as the total after tax).
// For a client whose prices already include tax, the tax and levy are instead shown as worked back out of the price; the total is unchanged.
// When the net total comes out under the invoice's minimum charge, the minimum is billed instead and marked as applied.
// A foreign invoice also shows its original amount in the client's currency alongside its total in the home currency, converted at the exchange rate from its issue date.
// It also shows the total savings: every line discount and invoice discount added together.
// When an early-payment discount is offered, it also shows the reduced amount to pay if settled within the set number of days.
// Lines, the discounts (set to one, added to, or removed one at a time), the tax rate, the levy rate, the surcharge, the minimum charge and the early-payment discount can be changed while it is a draft.
@Component({
  selector: 'app-invoice-detail',
  imports: [MoneyPipe, ReactiveFormsModule, DecimalPipe, RouterLink, InvoicePayments, InvoiceCreditNotes, InvoiceInstalments, Notes],
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
      @if (inv.status === 'SENT' || inv.status === 'PARTIAL') {
        <p>
          <button type="button" (click)="writeOff()" [disabled]="writingOff()" data-testid="invoice-write-off">
            Write off as bad debt
          </button>
        </p>
      }
      @if (writeOffError()) {
        <p role="alert" data-testid="invoice-write-off-error">{{ writeOffError() }}</p>
      }
      <p>
        Tax: <span data-testid="invoice-tax-mode">{{ inv.taxInclusive ? 'Inclusive' : 'Exclusive' }}</span>
        @if (inv.taxInclusive) {
          (prices already include tax)
        }
      </p>
      @if (inv.taxExempt) {
        <p data-testid="invoice-tax-exempt">Client is tax exempt: no tax is charged on this invoice.</p>
      }
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
              <th scope="col">Line discount</th>
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
                  <td>
                    <input
                      type="number"
                      inputmode="decimal"
                      min="0"
                      max="100"
                      step="0.01"
                      [formControl]="editForm.controls.discountPct"
                      data-testid="lineitem-edit-discountpct"
                      aria-label="Line discount percentage"
                      [attr.aria-invalid]="editForm.controls.discountPct.invalid"
                      (keydown.enter)="saveEdit(l.id)"
                      (keydown.escape)="cancelEdit(l.id)"
                    />
                  </td>
                  <td data-testid="lineitem-amount">{{ lineCents(l.qty, l.unitPrice, l.discountPct) / 100 | money: inv.currency }}</td>
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
                  <td data-testid="lineitem-unitprice">{{ l.unitPrice | money: inv.currency : '1.2-4' : false }}</td>
                  <td data-testid="lineitem-discount">
                    @if (l.discountPct > 0) {
                      {{ l.discountPct | number: '1.0-2' : 'en-US' }}%
                    }
                  </td>
                  <td data-testid="lineitem-amount">{{ l.amount | money: inv.currency }}</td>
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
              <th scope="row" colspan="5">Subtotal</th>
              <td data-testid="invoice-subtotal">{{ inv.subtotal | money: inv.currency }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            @for (d of inv.discounts; track d.id) {
              <tr [attr.data-testid]="'discount-row-' + d.id">
                <th scope="row" colspan="5" data-testid="discount-row-label">
                  @if (d.discountAmount > 0) {
                    Discount {{ $index + 1 }} ({{ d.discountAmount | money: inv.currency }} off)
                  } @else {
                    Discount {{ $index + 1 }} ({{ d.discountPct | number: '1.0-2' : 'en-US' }}%@if (d.discountCap !== null) {, up to <span data-testid="discount-row-cap">{{ d.discountCap | money: inv.currency }}</span>})
                  }
                </th>
                <td>
                  <span [attr.data-testid]="'breakdown-discount-row-' + d.id">-<span data-testid="discount-row-amount">{{ d.amount | money: inv.currency }}</span></span>
                </td>
                @if (inv.status === 'DRAFT') {
                  <td>
                    <button
                      type="button"
                      data-testid="discount-row-remove"
                      [attr.aria-label]="'Remove discount ' + ($index + 1)"
                      [disabled]="discountSaving()"
                      (click)="removeDiscount(d.id)"
                    >
                      Remove
                    </button>
                  </td>
                }
              </tr>
            }
            <tr>
              <th scope="row" colspan="5">
                @if (inv.discounts.length > 1) {
                  Combined discount (@if (inv.discountPct > 0) {<span data-testid="invoice-discount-pct">{{ inv.discountPct | number: '1.0-2' : 'en-US' }}</span>%}@if (inv.discountPct > 0 && inv.discountAmount > 0) { + }@if (inv.discountAmount > 0) {<span data-testid="invoice-discount-amount">{{ inv.discountAmount | money: inv.currency }}</span> off})
                } @else if (inv.discountAmount > 0) {
                  Discount (<span data-testid="invoice-discount-amount">{{ inv.discountAmount | money: inv.currency }}</span> off)
                } @else {
                  Discount (<span data-testid="invoice-discount-pct">{{ inv.discountPct | number: '1.0-2' : 'en-US' }}</span>%)
                }
              </th>
              <td data-testid="invoice-discount">{{ inv.discount | money: inv.currency }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="5">Taxable amount (excludes tax-free lines)</th>
              <td data-testid="invoice-taxable-base">{{ inv.taxableBase | money: inv.currency }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="5" data-testid="invoice-tax-label">
                Tax (<span data-testid="invoice-tax-pct">{{ inv.taxPct | number: '1.0-2' : 'en-US' }}</span>%)@if (inv.taxInclusive) { included}
              </th>
              <td data-testid="invoice-tax">{{ inv.tax | money: inv.currency }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="5" data-testid="invoice-tax-levy-label">
                Levy (<span data-testid="invoice-tax-levy-pct">{{ inv.levyPct | number: '1.0-2' : 'en-US' }}</span>%)@if (inv.taxInclusive) { included}
              </th>
              <td data-testid="invoice-tax-levy">{{ inv.levy | money: inv.currency }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="5">Surcharge</th>
              <td data-testid="invoice-surcharge">{{ inv.surcharge | money: inv.currency }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="5">Effective tax rate (tax and levy as a share of the total before tax)</th>
              <td data-testid="invoice-effective-tax-rate">{{ inv.effectiveTaxPct | number: '1.2-2' : 'en-US' }}%</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="5">Total before tax</th>
              <td data-testid="invoice-total-ex-tax">{{ inv.totalExTax | money: inv.currency }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            @if (inv.minimumApplied) {
              <tr data-testid="invoice-minimum-applied">
                <th scope="row" colspan="5">
                  Minimum charge applied (net total
                  <span data-testid="invoice-net-total">{{ inv.netTotal | money: inv.currency }}</span>
                  is under the minimum)
                </th>
                <td data-testid="invoice-minimum-charge">{{ inv.minimumCharge | money: inv.currency }}</td>
                @if (inv.status === 'DRAFT') {
                  <td></td>
                }
              </tr>
            }
            <tr>
              <th scope="row" colspan="5">Total</th>
              <td data-testid="invoice-amount">{{ inv.amount | money: inv.currency }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            @if (inv.currency !== inv.homeCurrency) {
              <tr>
                <th scope="row" colspan="5">Original amount in {{ inv.currency }}</th>
                <td data-testid="invoice-original-amount">{{ inv.amount | money: inv.currency }}</td>
                @if (inv.status === 'DRAFT') {
                  <td></td>
                }
              </tr>
              <tr>
                <th scope="row" colspan="5">Total in {{ inv.homeCurrency }} (at the rate on {{ inv.issuedDate }})</th>
                @if (inv.homeAmount !== null) {
                  <td data-testid="invoice-home-amount">{{ inv.homeAmount | money: inv.homeCurrency }}</td>
                } @else {
                  <td data-testid="invoice-home-amount-missing">No exchange rate</td>
                }
                @if (inv.status === 'DRAFT') {
                  <td></td>
                }
              </tr>
            }
            <tr>
              <th scope="row" colspan="5">Total after tax</th>
              <td data-testid="invoice-total-inc-tax">{{ inv.amount | money: inv.currency }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            <tr>
              <th scope="row" colspan="5">Total savings (all discounts added together)</th>
              <td data-testid="invoice-total-savings">{{ inv.totalSavings | money: inv.currency }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
            @if (inv.earlyPaymentAmount !== null) {
              <tr data-testid="invoice-early-pay">
                <th scope="row" colspan="5">
                  Pay by <span data-testid="invoice-early-pay-by">{{ inv.earlyPaymentBy }}</span> (within
                  <span data-testid="invoice-early-pay-days">{{ inv.earlyPaymentDays }}</span> days) for
                  <span data-testid="invoice-early-pay-pct">{{ inv.earlyPaymentPct | number: '1.0-2' : 'en-US' }}</span>% off
                </th>
                <td data-testid="invoice-early-pay-amount">{{ inv.earlyPaymentAmount | money: inv.currency }}</td>
                @if (inv.status === 'DRAFT') {
                  <td></td>
                }
              </tr>
            }
            @if (inv.lateFeePerDay > 0) {
              <tr data-testid="invoice-late-fee-row">
                <th scope="row" colspan="5">
                  Late fee (<span data-testid="invoice-late-fee-per-day">{{ inv.lateFeePerDay | money: inv.currency }}</span> per day,
                  <span data-testid="invoice-days-late">{{ inv.daysLate }}</span> days late)
                </th>
                <td data-testid="invoice-late-fee">{{ inv.lateFee | money: inv.currency }}</td>
                @if (inv.status === 'DRAFT') {
                  <td></td>
                }
              </tr>
            }
            @if (inv.retentionPct > 0) {
              <tr data-testid="invoice-retention-row">
                <th scope="row" colspan="5">
                  @if (inv.retentionReleased) {
                    Retention <span data-testid="invoice-retention-released">released</span>, now due
                  } @else {
                    Retention held back, not due yet
                  }
                  (<span data-testid="invoice-retention-pct">{{ inv.retentionPct | number: '1.0-2' : 'en-US' }}</span>%)
                </th>
                <td data-testid="invoice-retention">{{ inv.retention | money: inv.currency }}</td>
                @if (inv.status === 'DRAFT') {
                  <td></td>
                }
              </tr>
              <tr data-testid="invoice-due-now-row">
                <th scope="row" colspan="5">Due now</th>
                <td data-testid="invoice-due-now">{{ inv.dueNow | money: inv.currency }}</td>
                @if (inv.status === 'DRAFT') {
                  <td></td>
                }
              </tr>
            }
            <tr data-testid="invoice-due-amount-row">
              <th scope="row" colspan="5">Owed now (after payments and credits)</th>
              <td data-testid="invoice-due-amount">{{ inv.amountDue | money: inv.currency }}</td>
              @if (inv.status === 'DRAFT') {
                <td></td>
              }
            </tr>
          </tfoot>
        </table>
        @if (canReleaseRetention()) {
          <p>
            <button
              type="button"
              (click)="releaseRetention()"
              [disabled]="releasingRetention()"
              data-testid="invoice-retention-release"
            >
              Release retention (job finished)
            </button>
          </p>
        }
        @if (releaseRetentionError()) {
          <p role="alert" data-testid="invoice-retention-release-error">{{ releaseRetentionError() }}</p>
        }
        @if (editError()) {
          <p role="alert" data-testid="lineitem-edit-error">{{ editError() }}</p>
        }
      </section>

      @if (inv.status === 'DRAFT') {
        <form [formGroup]="discountForm" (ngSubmit)="applyDiscount()" data-testid="discount-form" novalidate>
          <h2>Discount</h2>
          <fieldset>
            <legend>Take off</legend>
            <label>
              <input type="radio" formControlName="discountType" value="pct" data-testid="discount-form-type-pct" />
              A percentage
            </label>
            <label>
              <input type="radio" formControlName="discountType" value="amount" data-testid="discount-form-type-amount" />
              A fixed amount
            </label>
          </fieldset>
          @if (discountType() === 'amount') {
            <div>
              <label for="discount-amount">Amount off the subtotal</label>
              <input
                id="discount-amount"
                type="number"
                inputmode="decimal"
                min="0"
                step="0.01"
                formControlName="discountAmount"
                data-testid="discount-form-amount"
                [attr.aria-invalid]="discountAmountInvalid()"
                [attr.aria-describedby]="discountAmountInvalid() ? 'discount-amount-error' : null"
              />
              @if (discountAmountInvalid()) {
                <p id="discount-amount-error" role="alert" data-testid="discount-form-amount-error">
                  Enter an amount of 0 or more with at most two decimal places.
                </p>
              }
            </div>
          } @else {
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
            <div>
              <label for="discount-cap">Most it takes off (optional)</label>
              <input
                id="discount-cap"
                type="number"
                inputmode="decimal"
                min="0.01"
                step="0.01"
                formControlName="discountCap"
                data-testid="discount-form-cap"
                [attr.aria-invalid]="discountCapInvalid()"
                [attr.aria-describedby]="discountCapInvalid() ? 'discount-cap-error' : null"
              />
              @if (discountCapInvalid()) {
                <p id="discount-cap-error" role="alert" data-testid="discount-form-cap-error">
                  Enter an amount above 0 with at most two decimal places, or leave it empty for no cap.
                </p>
              }
            </div>
          }
          <button type="submit" data-testid="discount-form-submit" [disabled]="discountSaving()">Apply discount</button>
          <button type="button" data-testid="discount-form-add" [disabled]="discountSaving()" (click)="addDiscount()">
            Add as another discount
          </button>
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

        <form [formGroup]="surchargeForm" (ngSubmit)="applySurcharge()" data-testid="surcharge-form" novalidate>
          <h2>Surcharge</h2>
          <div>
            <label for="surcharge-amount">Flat amount added to the total, such as a handling fee</label>
            <input
              id="surcharge-amount"
              type="number"
              inputmode="decimal"
              min="0"
              step="0.01"
              formControlName="surcharge"
              data-testid="surcharge-form-amount"
              [attr.aria-invalid]="surchargeInvalid()"
              [attr.aria-describedby]="surchargeInvalid() ? 'surcharge-amount-error' : null"
            />
            @if (surchargeInvalid()) {
              <p id="surcharge-amount-error" role="alert" data-testid="surcharge-form-amount-error">
                Enter an amount of 0 or more with at most two decimal places.
              </p>
            }
          </div>
          <button type="submit" data-testid="surcharge-form-submit" [disabled]="surchargeSaving()">Apply surcharge</button>
          @if (surchargeError()) {
            <p role="alert" data-testid="surcharge-form-error">{{ surchargeError() }}</p>
          }
        </form>

        <form [formGroup]="minimumChargeForm" (ngSubmit)="applyMinimumCharge()" data-testid="minimum-charge-form" novalidate>
          <h2>Minimum charge</h2>
          <div>
            <label for="minimum-charge-amount">Least amount billed when the total comes out under it</label>
            <input
              id="minimum-charge-amount"
              type="number"
              inputmode="decimal"
              min="0"
              step="0.01"
              formControlName="minimumCharge"
              data-testid="minimum-charge-form-amount"
              [attr.aria-invalid]="minimumChargeInvalid()"
              [attr.aria-describedby]="minimumChargeInvalid() ? 'minimum-charge-amount-error' : null"
            />
            @if (minimumChargeInvalid()) {
              <p id="minimum-charge-amount-error" role="alert" data-testid="minimum-charge-form-amount-error">
                Enter an amount of 0 or more with at most two decimal places.
              </p>
            }
          </div>
          <button type="submit" data-testid="minimum-charge-form-submit" [disabled]="minimumChargeSaving()">Apply minimum charge</button>
          @if (minimumChargeError()) {
            <p role="alert" data-testid="minimum-charge-form-error">{{ minimumChargeError() }}</p>
          }
        </form>

        <form [formGroup]="earlyPayForm" (ngSubmit)="applyEarlyPayment()" data-testid="early-pay-form" novalidate>
          <h2>Early-payment discount</h2>
          <div>
            <label for="early-pay-pct">Percentage off when paid early</label>
            <input
              id="early-pay-pct"
              type="number"
              inputmode="decimal"
              min="0"
              max="100"
              step="0.01"
              formControlName="earlyPaymentPct"
              data-testid="early-pay-form-pct"
              [attr.aria-invalid]="earlyPayInvalid('earlyPaymentPct')"
              [attr.aria-describedby]="earlyPayInvalid('earlyPaymentPct') ? 'early-pay-pct-error' : null"
            />
            @if (earlyPayInvalid('earlyPaymentPct')) {
              <p id="early-pay-pct-error" role="alert" data-testid="early-pay-form-pct-error">
                Enter a percentage from 0 to 100 with at most two decimal places.
              </p>
            }
          </div>
          <div>
            <label for="early-pay-days">Paid within this many days of issue</label>
            <input
              id="early-pay-days"
              type="number"
              inputmode="numeric"
              min="0"
              step="1"
              formControlName="earlyPaymentDays"
              data-testid="early-pay-form-days"
              [attr.aria-invalid]="earlyPayInvalid('earlyPaymentDays')"
              [attr.aria-describedby]="earlyPayInvalid('earlyPaymentDays') ? 'early-pay-days-error' : null"
            />
            @if (earlyPayInvalid('earlyPaymentDays')) {
              <p id="early-pay-days-error" role="alert" data-testid="early-pay-form-days-error">
                Enter a whole number of days from 0 to 3650.
              </p>
            }
          </div>
          <button type="submit" data-testid="early-pay-form-submit" [disabled]="earlyPaySaving()">Apply early-payment discount</button>
          @if (earlyPayError()) {
            <p role="alert" data-testid="early-pay-form-error">{{ earlyPayError() }}</p>
          }
        </form>

        <form [formGroup]="lateFeeForm" (ngSubmit)="applyLateFee()" data-testid="late-fee-form" novalidate>
          <h2>Late fee</h2>
          <div>
            <label for="late-fee-per-day">Late fee per day overdue</label>
            <input
              id="late-fee-per-day"
              type="number"
              inputmode="decimal"
              min="0"
              step="0.01"
              formControlName="lateFeePerDay"
              data-testid="late-fee-form-per-day"
              [attr.aria-invalid]="lateFeeInvalid()"
              [attr.aria-describedby]="lateFeeInvalid() ? 'late-fee-per-day-error' : null"
            />
            @if (lateFeeInvalid()) {
              <p id="late-fee-per-day-error" role="alert" data-testid="late-fee-form-per-day-error">
                Enter an amount of 0 or more with at most two decimal places.
              </p>
            }
          </div>
          <button type="submit" data-testid="late-fee-form-submit" [disabled]="lateFeeSaving()">Apply late fee</button>
          @if (lateFeeError()) {
            <p role="alert" data-testid="late-fee-form-error">{{ lateFeeError() }}</p>
          }
        </form>

        <form [formGroup]="retentionForm" (ngSubmit)="applyRetention()" data-testid="retention-form" novalidate>
          <h2>Retention</h2>
          <div>
            <label for="retention-pct">Percentage held back, not due yet</label>
            <input
              id="retention-pct"
              type="number"
              inputmode="decimal"
              min="0"
              max="100"
              step="0.01"
              formControlName="retentionPct"
              data-testid="retention-form-pct"
              [attr.aria-invalid]="retentionInvalid()"
              [attr.aria-describedby]="retentionInvalid() ? 'retention-pct-error' : null"
            />
            @if (retentionInvalid()) {
              <p id="retention-pct-error" role="alert" data-testid="retention-form-pct-error">
                Enter a percentage from 0 to 100 with at most two decimal places.
              </p>
            }
          </div>
          <button type="submit" data-testid="retention-form-submit" [disabled]="retentionSaving()">Apply retention</button>
          @if (retentionError()) {
            <p role="alert" data-testid="retention-form-error">{{ retentionError() }}</p>
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
            <label for="lineitem-discountpct">Line discount % (optional)</label>
            <input
              id="lineitem-discountpct"
              type="number"
              inputmode="decimal"
              min="0"
              max="100"
              step="0.01"
              formControlName="discountPct"
              data-testid="lineitem-form-discountpct"
              [attr.aria-invalid]="invalid('discountPct')"
              [attr.aria-describedby]="invalid('discountPct') ? 'lineitem-discountpct-error' : null"
            />
            @if (invalid('discountPct')) {
              <p id="lineitem-discountpct-error" role="alert" data-testid="lineitem-form-discountpct-error">
                Enter a percentage from 0 to 100 with at most two decimal places.
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

      <app-invoice-instalments
        [projectId]="projectIdNumber()"
        [invoiceId]="inv.id"
        [invoiceAmount]="inv.amount"
        [currency]="inv.currency"
        [canEdit]="inv.status === 'DRAFT' || inv.status === 'SENT' || inv.status === 'PARTIAL'"
      />

      <app-invoice-payments
        [projectId]="projectIdNumber()"
        [invoiceId]="inv.id"
        [invoiceAmount]="inv.amount"
        [credited]="credited()"
        [currency]="inv.currency"
        [canRecord]="inv.status === 'SENT' || inv.status === 'PARTIAL'"
        (recorded)="invoice.reload()"
      />

      <app-invoice-credit-notes
        [projectId]="projectIdNumber()"
        [invoiceId]="inv.id"
        [currency]="inv.currency"
        [canIssue]="inv.status !== 'DRAFT' && inv.status !== 'VOID' && inv.status !== 'WRITTEN_OFF'"
        (issued)="creditNotes.reload()"
      />

      <app-notes [projectId]="projectIdNumber()" [invoiceId]="inv.id" />
    }
  `,
})
export class InvoiceDetailPage {
  private readonly service = inject(InvoiceService);
  private readonly creditNoteService = inject(CreditNoteService);
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

  // The credit notes reduce what is left to pay alongside the payments.
  protected readonly creditNotes = rxResource({
    params: () => ({ projectId: this.projectIdNumber(), invoiceId: Number(this.invoiceId()) }),
    stream: ({ params }) => this.creditNoteService.list(params.projectId, params.invoiceId),
  });
  protected readonly credited = computed(
    () =>
      (this.creditNotes.hasValue() ? this.creditNotes.value() : []).reduce((sum, c) => sum + Math.round(c.amount * 100), 0) /
      100,
  );

  // Work in whole cents (and ten-thousandths of a unit price) so the line is exact rather than
  // accumulating floating-point error; each line, and then its own discount, is rounded to the cent on its own.
  protected lineCents(qty: number, unitPrice: number, discountPct: number): number {
    const gross = Math.round((Math.round(qty * 100) * Math.round(unitPrice * 10000)) / 10000);
    return gross - Math.round((gross * Math.round(discountPct * 100)) / 10000);
  }

  protected readonly writingOff = signal(false);
  protected readonly writeOffError = signal<string | null>(null);

  // Writes the invoice off as bad debt: it is kept on record but no longer counts toward what is owed.
  protected writeOff(): void {
    this.writingOff.set(true);
    this.writeOffError.set(null);
    this.service.writeOff(this.projectIdNumber(), Number(this.invoiceId())).subscribe({
      next: (updated) => {
        this.invoice.set(updated);
        this.writingOff.set(false);
      },
      error: () => {
        this.writeOffError.set('Could not write off the invoice. Please try again.');
        this.writingOff.set(false);
      },
    });
  }

  protected readonly releasingRetention = signal(false);
  protected readonly releaseRetentionError = signal<string | null>(null);

  // Retention can be released once the invoice is sent and still holds some back.
  protected readonly canReleaseRetention = computed(() => {
    const inv = this.invoice.value();
    return (
      !!inv &&
      inv.retention > 0 &&
      !inv.retentionReleased &&
      inv.status !== 'DRAFT' &&
      inv.status !== 'VOID' &&
      inv.status !== 'WRITTEN_OFF'
    );
  });

  // Releases the retention held back once the job is finished, so it becomes due.
  protected releaseRetention(): void {
    this.releasingRetention.set(true);
    this.releaseRetentionError.set(null);
    this.service.releaseRetention(this.projectIdNumber(), Number(this.invoiceId())).subscribe({
      next: (updated) => {
        this.invoice.set(updated);
        this.releasingRetention.set(false);
      },
      error: () => {
        this.releaseRetentionError.set('Could not release the retention. Please try again.');
        this.releasingRetention.set(false);
      },
    });
  }

  protected readonly discountSaving = signal(false);
  protected readonly discountError = signal<string | null>(null);

  protected readonly discountForm = this.fb.group({
    discountType: ['pct' as 'pct' | 'amount'],
    discountPct: ['', [Validators.required, Validators.min(0), Validators.max(100), Validators.pattern(TWO_DECIMALS)]],
    discountAmount: ['', [Validators.required, Validators.min(0), Validators.pattern(TWO_DECIMALS)]],
    discountCap: ['', [Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
  });

  // Whether the discount is taken off as a percentage or a fixed amount, as chosen in the form.
  protected readonly discountType = toSignal(this.discountForm.controls.discountType.valueChanges, {
    initialValue: this.discountForm.controls.discountType.value,
  });

  // Starts the discount fields from the invoice's current discount whenever the invoice loads.
  private readonly syncDiscount = effect(() => {
    if (this.invoice.hasValue()) {
      const inv = this.invoice.value();
      this.discountForm.setValue({
        discountType: inv.discountAmount > 0 ? 'amount' : 'pct',
        discountPct: String(inv.discountPct),
        discountAmount: String(inv.discountAmount),
        discountCap: inv.discounts.length === 1 && inv.discounts[0].discountCap !== null ? String(inv.discounts[0].discountCap) : '',
      });
    }
  });

  protected discountInvalid(): boolean {
    const control = this.discountForm.controls.discountPct;
    return control.invalid && (control.touched || control.dirty);
  }

  protected discountCapInvalid(): boolean {
    const control = this.discountForm.controls.discountCap;
    return control.invalid && (control.touched || control.dirty);
  }

  protected discountAmountInvalid(): boolean {
    const control = this.discountForm.controls.discountAmount;
    return control.invalid && (control.touched || control.dirty);
  }

  // Replaces the invoice's discounts with the one in the form.
  protected applyDiscount(): void {
    const chosen = this.chosenDiscount();
    if (chosen) {
      this.saveDiscount(
        this.service.applyDiscount(this.projectIdNumber(), Number(this.invoiceId()), chosen.pct, chosen.amount, chosen.cap),
        'Could not apply the discount. Please try again.',
      );
    }
  }

  // Adds the discount in the form alongside any the invoice already has.
  protected addDiscount(): void {
    const chosen = this.chosenDiscount();
    if (!chosen) {
      return;
    }
    if (chosen.pct <= 0 && chosen.amount <= 0) {
      this.discountError.set('Enter more than zero to add a discount.');
      return;
    }
    this.saveDiscount(
      this.service.addDiscount(this.projectIdNumber(), Number(this.invoiceId()), chosen.pct, chosen.amount, chosen.cap),
      'Could not add the discount. Please try again.',
    );
  }

  protected removeDiscount(discountId: number): void {
    this.saveDiscount(
      this.service.removeDiscount(this.projectIdNumber(), Number(this.invoiceId()), discountId),
      'Could not remove the discount. Please try again.',
    );
  }

  // The percentage (with any cap) or amount chosen in the form (only the chosen kind; the other is zero), or null when it is invalid.
  private chosenDiscount(): { pct: number; amount: number; cap: number | null } | null {
    const { discountType, discountPct, discountAmount, discountCap } = this.discountForm.controls;
    const isPct = discountType.value === 'pct';
    const fields = isPct ? [discountPct, discountCap] : [discountAmount];
    const invalid = fields.filter((f) => f.invalid);
    if (invalid.length > 0) {
      invalid.forEach((f) => f.markAsTouched());
      return null;
    }
    const pct = isPct ? Number(discountPct.value) : 0;
    return {
      pct,
      amount: isPct ? 0 : Number(discountAmount.value),
      cap: pct > 0 && discountCap.value ? Number(discountCap.value) : null,
    };
  }

  private saveDiscount(request: Observable<InvoiceDetail>, failure: string): void {
    this.discountSaving.set(true);
    this.discountError.set(null);
    request.subscribe({
      next: (updated) => {
        this.invoice.set(updated);
        this.discountSaving.set(false);
      },
      error: () => {
        this.discountError.set(failure);
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

  protected readonly surchargeSaving = signal(false);
  protected readonly surchargeError = signal<string | null>(null);

  protected readonly surchargeForm = this.fb.group({
    surcharge: ['', [Validators.required, Validators.min(0), Validators.pattern(TWO_DECIMALS)]],
  });

  // Starts the surcharge field from the invoice's current surcharge whenever the invoice loads.
  private readonly syncSurcharge = effect(() => {
    if (this.invoice.hasValue()) {
      this.surchargeForm.setValue({ surcharge: String(this.invoice.value().surcharge) });
    }
  });

  protected surchargeInvalid(): boolean {
    const control = this.surchargeForm.controls.surcharge;
    return control.invalid && (control.touched || control.dirty);
  }

  protected applySurcharge(): void {
    if (this.surchargeForm.invalid) {
      this.surchargeForm.markAllAsTouched();
      return;
    }
    this.surchargeSaving.set(true);
    this.surchargeError.set(null);
    const surcharge = Number(this.surchargeForm.getRawValue().surcharge);
    this.service.applySurcharge(this.projectIdNumber(), Number(this.invoiceId()), surcharge).subscribe({
      next: (updated) => {
        this.invoice.set(updated);
        this.surchargeSaving.set(false);
      },
      error: () => {
        this.surchargeError.set('Could not apply the surcharge. Please try again.');
        this.surchargeSaving.set(false);
      },
    });
  }

  protected readonly minimumChargeSaving = signal(false);
  protected readonly minimumChargeError = signal<string | null>(null);

  protected readonly minimumChargeForm = this.fb.group({
    minimumCharge: ['', [Validators.required, Validators.min(0), Validators.pattern(TWO_DECIMALS)]],
  });

  // Starts the minimum charge field from the invoice's current minimum whenever the invoice loads.
  private readonly syncMinimumCharge = effect(() => {
    if (this.invoice.hasValue()) {
      this.minimumChargeForm.setValue({ minimumCharge: String(this.invoice.value().minimumCharge) });
    }
  });

  protected minimumChargeInvalid(): boolean {
    const control = this.minimumChargeForm.controls.minimumCharge;
    return control.invalid && (control.touched || control.dirty);
  }

  protected applyMinimumCharge(): void {
    if (this.minimumChargeForm.invalid) {
      this.minimumChargeForm.markAllAsTouched();
      return;
    }
    this.minimumChargeSaving.set(true);
    this.minimumChargeError.set(null);
    const minimumCharge = Number(this.minimumChargeForm.getRawValue().minimumCharge);
    this.service.applyMinimumCharge(this.projectIdNumber(), Number(this.invoiceId()), minimumCharge).subscribe({
      next: (updated) => {
        this.invoice.set(updated);
        this.minimumChargeSaving.set(false);
      },
      error: () => {
        this.minimumChargeError.set('Could not apply the minimum charge. Please try again.');
        this.minimumChargeSaving.set(false);
      },
    });
  }

  protected readonly earlyPaySaving = signal(false);
  protected readonly earlyPayError = signal<string | null>(null);

  protected readonly earlyPayForm = this.fb.group({
    earlyPaymentPct: ['', [Validators.required, Validators.min(0), Validators.max(100), Validators.pattern(TWO_DECIMALS)]],
    earlyPaymentDays: ['', [Validators.required, Validators.min(0), Validators.max(3650), Validators.pattern(/^\d+$/)]],
  });

  // Starts the early-payment fields from the invoice's current offer whenever the invoice loads.
  private readonly syncEarlyPay = effect(() => {
    if (this.invoice.hasValue()) {
      const inv = this.invoice.value();
      this.earlyPayForm.setValue({
        earlyPaymentPct: String(inv.earlyPaymentPct),
        earlyPaymentDays: String(inv.earlyPaymentDays),
      });
    }
  });

  protected earlyPayInvalid(name: 'earlyPaymentPct' | 'earlyPaymentDays'): boolean {
    const control = this.earlyPayForm.controls[name];
    return control.invalid && (control.touched || control.dirty);
  }

  protected applyEarlyPayment(): void {
    if (this.earlyPayForm.invalid) {
      this.earlyPayForm.markAllAsTouched();
      return;
    }
    this.earlyPaySaving.set(true);
    this.earlyPayError.set(null);
    const { earlyPaymentPct, earlyPaymentDays } = this.earlyPayForm.getRawValue();
    this.service
      .applyEarlyPayment(this.projectIdNumber(), Number(this.invoiceId()), Number(earlyPaymentPct), Number(earlyPaymentDays))
      .subscribe({
        next: (updated) => {
          this.invoice.set(updated);
          this.earlyPaySaving.set(false);
        },
        error: () => {
          this.earlyPayError.set('Could not apply the early-payment discount. Please try again.');
          this.earlyPaySaving.set(false);
        },
      });
  }

  protected readonly lateFeeSaving = signal(false);
  protected readonly lateFeeError = signal<string | null>(null);

  protected readonly lateFeeForm = this.fb.group({
    lateFeePerDay: ['', [Validators.required, Validators.min(0), Validators.pattern(TWO_DECIMALS)]],
  });

  // Starts the late fee field from the invoice's current fee whenever the invoice loads.
  private readonly syncLateFee = effect(() => {
    if (this.invoice.hasValue()) {
      this.lateFeeForm.setValue({ lateFeePerDay: String(this.invoice.value().lateFeePerDay) });
    }
  });

  protected lateFeeInvalid(): boolean {
    const control = this.lateFeeForm.controls.lateFeePerDay;
    return control.invalid && (control.touched || control.dirty);
  }

  protected applyLateFee(): void {
    if (this.lateFeeForm.invalid) {
      this.lateFeeForm.markAllAsTouched();
      return;
    }
    this.lateFeeSaving.set(true);
    this.lateFeeError.set(null);
    this.service
      .applyLateFee(this.projectIdNumber(), Number(this.invoiceId()), Number(this.lateFeeForm.getRawValue().lateFeePerDay))
      .subscribe({
        next: (updated) => {
          this.invoice.set(updated);
          this.lateFeeSaving.set(false);
        },
        error: () => {
          this.lateFeeError.set('Could not apply the late fee. Please try again.');
          this.lateFeeSaving.set(false);
        },
      });
  }

  protected readonly retentionSaving = signal(false);
  protected readonly retentionError = signal<string | null>(null);

  protected readonly retentionForm = this.fb.group({
    retentionPct: ['', [Validators.required, Validators.min(0), Validators.max(100), Validators.pattern(TWO_DECIMALS)]],
  });

  // Starts the retention field from the invoice's current retention whenever the invoice loads.
  private readonly syncRetention = effect(() => {
    if (this.invoice.hasValue()) {
      this.retentionForm.setValue({ retentionPct: String(this.invoice.value().retentionPct) });
    }
  });

  protected retentionInvalid(): boolean {
    const control = this.retentionForm.controls.retentionPct;
    return control.invalid && (control.touched || control.dirty);
  }

  protected applyRetention(): void {
    if (this.retentionForm.invalid) {
      this.retentionForm.markAllAsTouched();
      return;
    }
    this.retentionSaving.set(true);
    this.retentionError.set(null);
    this.service
      .applyRetention(this.projectIdNumber(), Number(this.invoiceId()), Number(this.retentionForm.getRawValue().retentionPct))
      .subscribe({
        next: (updated) => {
          this.invoice.set(updated);
          this.retentionSaving.set(false);
        },
        error: () => {
          this.retentionError.set('Could not apply the retention. Please try again.');
          this.retentionSaving.set(false);
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
    discountPct: ['', [Validators.min(0), Validators.max(100), Validators.pattern(TWO_DECIMALS)]],
    taxExempt: false,
  });

  protected invalid(name: 'description' | 'qty' | 'unit' | 'unitPrice' | 'discountPct'): boolean {
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
    const { description, qty, unit, unitPrice, discountPct, taxExempt } = this.form.getRawValue();
    const item = {
      description: description.trim(),
      qty: Number(qty),
      unit: unit.trim() || null,
      unitPrice: Number(unitPrice),
      taxExempt,
      discountPct: Number(discountPct || 0),
    };
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
    discountPct: ['', [Validators.min(0), Validators.max(100), Validators.pattern(TWO_DECIMALS)]],
    taxExempt: false,
  });

  protected startEdit(line: LineItem): void {
    this.editError.set(null);
    this.editForm.setValue({
      description: line.description,
      qty: String(line.qty),
      unit: line.unit ?? '',
      unitPrice: String(line.unitPrice),
      discountPct: String(line.discountPct),
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
    const { description, qty, unit, unitPrice, discountPct, taxExempt } = this.editForm.getRawValue();
    const item = {
      description: description.trim(),
      qty: Number(qty),
      unit: unit.trim() || null,
      unitPrice: Number(unitPrice),
      taxExempt,
      discountPct: Number(discountPct || 0),
    };
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
