import { Component, computed, inject, input, signal } from '@angular/core';
import { MoneyPipe } from '../currencies/money.pipe';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { InvoiceService } from './invoice.service';
import { StatementEmailAction } from './statement-email';

// A client's statement: every invoice across the client's projects grouped by job, how much is left to
// pay on each, a subtotal still owed and the tax per job, the tax across the statement, and the total the client still owes. Drafts are listed but do not count towards what is owed.
// It can be run for a chosen date range, giving the opening balance, the entries within the range with the running
// balance, the net movement within the range, and the closing balance (opening plus movements).
// The payments received within the range are listed on their own, with their total, and so are the credit notes
// issued within it.
// The balance owed as at a chosen past date can be looked up, counting only entries up to that date.
// Each invoice is shown in its own currency and every figure is rounded by the same currency rule as the invoice
// (the server rounds each one before adding them up), so the totals match the invoices to the cent. At the foot the total owed is also given in the home currency.
// It opens with the running account: invoices, payments, credit notes, deposits and refunds in date order with the
// balance owed after each.
// It is laid out to print cleanly: a summary of what was invoiced, what was paid and the grand total owed,
// with the app's navigation and the page's controls left off the printed copy.
// Under the summary, the statement can be emailed to the client, with a note kept of each time it was sent.
// Below that, the balance owed is broken down by age as at today: current (up to 30 days overdue), 31 to 60
// days, and more than 60 days overdue.
@Component({
  selector: 'app-client-statement',
  imports: [MoneyPipe, RouterLink, StatementEmailAction],
  styles: `
    .statement-actions {
      display: flex;
      gap: 1rem;
      align-items: center;
    }
    .statement-summary {
      display: grid;
      grid-template-columns: max-content max-content;
      gap: 0.25rem 1.5rem;
    }
    .statement-summary dd {
      margin: 0;
      text-align: right;
    }
    .statement-grand-total {
      font-weight: bold;
      border-top: 1px solid currentColor;
      padding-top: 0.25rem;
    }
    table {
      border-collapse: collapse;
    }
    .statement-money {
      text-align: right;
    }
    th,
    td {
      padding: 0.25rem 0.5rem;
      text-align: left;
    }
    @media print {
      .statement-actions {
        display: none;
      }
      table {
        width: 100%;
      }
      tbody {
        break-inside: avoid;
      }
    }
  `,
  template: `
    <div class="statement-actions">
      <a [routerLink]="['/clients', clientId()]" data-testid="client-statement-back">Back to client</a>
      @if (statement.hasValue()) {
        <button type="button" data-testid="statement-print" (click)="print()">Print statement</button>
      }
    </div>
    @if (statement.error()) {
      <p role="alert" data-testid="client-statement-error">Could not load the statement.</p>
    } @else if (statement.value(); as s) {
      <article data-testid="statement-print-view" aria-labelledby="statement-heading">
        <h1 id="statement-heading" data-testid="client-statement-name">Statement for {{ s.clientName }}</h1>
        <section aria-labelledby="statement-summary-heading" data-testid="statement-summary">
          <h2 id="statement-summary-heading">Summary</h2>
          <dl class="statement-summary">
            <dt>Total invoiced</dt>
            <dd data-testid="statement-total-invoiced">{{ s.invoiced | money: s.currency }}</dd>
            <dt>Tax</dt>
            <dd data-testid="client-statement-tax-total">{{ s.tax | money: s.currency }}</dd>
            <dt>Less paid</dt>
            <dd data-testid="statement-total-paid">{{ s.paid | money: s.currency }}</dd>
            <dt class="statement-grand-total">Grand total owed</dt>
            <dd class="statement-grand-total" data-testid="statement-grand-total">{{ s.outstanding | money: s.currency }}</dd>
          </dl>
        </section>
        <app-statement-email [clientId]="clientId()" />
        <section aria-labelledby="statement-aging-heading" data-testid="statement-aging">
          <h2 id="statement-aging-heading">Balance by age</h2>
          @if (aging.error()) {
            <p role="alert" data-testid="statement-aging-error">Could not load the balance by age.</p>
          } @else if (aging.value(); as a) {
            <dl class="statement-summary">
              <dt>Current (up to 30 days overdue)</dt>
              <dd data-testid="statement-aging-current">{{ a.current | money: a.currency }}</dd>
              <dt>31 to 60 days overdue</dt>
              <dd data-testid="statement-aging-30-60">{{ a.days30To60 | money: a.currency }}</dd>
              <dt>More than 60 days overdue</dt>
              <dd data-testid="statement-aging-60-plus">{{ a.days60Plus | money: a.currency }}</dd>
            </dl>
            <p data-testid="statement-aging-asof">As at {{ a.asOf }}</p>
          }
        </section>
        <section aria-labelledby="statement-range-heading" data-testid="statement-range">
          <h2 id="statement-range-heading">Statement for a date range</h2>
          <form class="statement-actions" (submit)="applyRange($event, rangeFrom.value, rangeTo.value)">
            <label for="statement-range-from">From</label>
            <input #rangeFrom id="statement-range-from" type="date" required data-testid="statement-range-from" />
            <label for="statement-range-to">To</label>
            <input #rangeTo id="statement-range-to" type="date" required data-testid="statement-range-to" />
            <button type="submit" data-testid="statement-range-apply">Run statement</button>
          </form>
          @if (rangeInvalid()) {
            <p role="alert" data-testid="statement-range-invalid">The end date must be on or after the start date.</p>
          } @else if (statementRange.error()) {
            <p role="alert" data-testid="statement-range-error">Could not load the statement for that range.</p>
          } @else if (statementRange.value(); as r) {
            <div aria-live="polite" data-testid="statement-range-result">
              <dl class="statement-summary">
                <dt>Opening balance at {{ r.from }}</dt>
                <dd data-testid="statement-opening-balance">{{ r.openingBalance | money: r.currency }}</dd>
                <dt>Movements in the range</dt>
                <dd data-testid="statement-movements-total">{{ r.movementsTotal | money: r.currency }}</dd>
                <dt>Closing balance at {{ r.to }}</dt>
                <dd data-testid="statement-closing-balance">{{ r.closingBalance | money: r.currency }}</dd>
              </dl>
              @if (r.entries.length === 0) {
                <p data-testid="statement-range-empty">Nothing on the account in this range.</p>
              } @else {
                <table data-testid="statement-range-table">
                  <caption>Account from {{ r.from }} to {{ r.to }}</caption>
                  <thead>
                    <tr>
                      <th scope="col">Date</th>
                      <th scope="col">Entry</th>
                      <th scope="col" class="statement-money">Charges</th>
                      <th scope="col" class="statement-money">Credits</th>
                      <th scope="col" class="statement-money">Balance</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (e of r.entries; track e.kind + e.sourceId; let n = $index) {
                      <tr [attr.data-testid]="'statement-range-row-' + (n + 1)" [attr.data-kind]="e.kind">
                        <td data-testid="statement-range-entry-date">{{ e.date }}</td>
                        <td data-testid="statement-range-entry-description">{{ e.description }}</td>
                        <td class="statement-money" data-testid="statement-range-entry-charge">
                          @if (e.charge !== null) {
                            {{ e.charge | money: r.currency }}
                          }
                        </td>
                        <td class="statement-money" data-testid="statement-range-entry-credit">
                          @if (e.credit !== null) {
                            {{ e.credit | money: r.currency }}
                          }
                        </td>
                        <td class="statement-money" data-testid="statement-range-entry-balance">{{ e.balance | money: r.currency }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              }
              <section aria-labelledby="statement-payments-heading" data-testid="statement-payments">
                <h3 id="statement-payments-heading">Payments received</h3>
                @if (r.payments.length === 0) {
                  <p data-testid="statement-payments-empty">No payments received in this range.</p>
                } @else {
                  <table data-testid="statement-payments-table">
                    <caption>Payments received from {{ r.from }} to {{ r.to }}</caption>
                    <thead>
                      <tr>
                        <th scope="col">Date</th>
                        <th scope="col">Payment</th>
                        <th scope="col" class="statement-money">Amount</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (p of r.payments; track p.sourceId; let n = $index) {
                        <tr [attr.data-testid]="'statement-payment-row-' + (n + 1)">
                          <td data-testid="statement-payment-date">{{ p.date }}</td>
                          <td data-testid="statement-payment-description">{{ p.description }}</td>
                          <td class="statement-money" data-testid="statement-payment-amount">{{ p.credit | money: r.currency }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                }
                <p>
                  Total received:
                  <strong data-testid="statement-payments-total">{{ r.paymentsTotal | money: r.currency }}</strong>
                </p>
              </section>
              <section aria-labelledby="statement-credits-heading" data-testid="statement-credits">
                <h3 id="statement-credits-heading">Credits issued</h3>
                @if (r.credits.length === 0) {
                  <p data-testid="statement-credits-empty">No credits issued in this range.</p>
                } @else {
                  <table data-testid="statement-credits-table">
                    <caption>Credits issued from {{ r.from }} to {{ r.to }}</caption>
                    <thead>
                      <tr>
                        <th scope="col">Date</th>
                        <th scope="col">Credit</th>
                        <th scope="col" class="statement-money">Amount</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (c of r.credits; track c.sourceId; let n = $index) {
                        <tr [attr.data-testid]="'statement-credit-row-' + (n + 1)">
                          <td data-testid="statement-credit-date">{{ c.date }}</td>
                          <td data-testid="statement-credit-description">{{ c.description }}</td>
                          <td class="statement-money" data-testid="statement-credit-amount">{{ c.credit | money: r.currency }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                }
                <p>
                  Total credited:
                  <strong data-testid="statement-credits-total">{{ r.creditsTotal | money: r.currency }}</strong>
                </p>
              </section>
            </div>
          }
        </section>
        <section aria-labelledby="statement-asof-heading" data-testid="statement-asof">
          <h2 id="statement-asof-heading">Balance as at a date</h2>
          <form class="statement-actions" (submit)="applyAsOf($event, asOfInput.value)">
            <label for="statement-asof-date">As at</label>
            <input #asOfInput id="statement-asof-date" type="date" required data-testid="statement-asof-date" />
            <button type="submit" data-testid="statement-asof-apply">Show balance</button>
          </form>
          @if (balanceAsOf.error()) {
            <p role="alert" data-testid="client-balance-asof-error">Could not load the balance.</p>
          } @else if (balanceAsOf.value(); as b) {
            <p aria-live="polite">
              Owed as at {{ b.asOf }}:
              <strong data-testid="client-balance-asof">{{ b.balance | money: b.currency }}</strong>
            </p>
          }
        </section>
        <section aria-labelledby="statement-account-heading" data-testid="statement-account">
          <h2 id="statement-account-heading">Account</h2>
          @if (s.entries.length === 0) {
            <p data-testid="statement-account-empty">Nothing on the account yet.</p>
          } @else {
            <table data-testid="statement-account-table">
              <caption>Running account for {{ s.clientName }}</caption>
              <thead>
                <tr>
                  <th scope="col">Date</th>
                  <th scope="col">Entry</th>
                  <th scope="col">PO number</th>
                  <th scope="col" class="statement-money">Charges</th>
                  <th scope="col" class="statement-money">Credits</th>
                  <th scope="col" class="statement-money">Balance</th>
                </tr>
              </thead>
              <tbody>
                @for (e of s.entries; track e.kind + e.sourceId; let n = $index) {
                  <tr [attr.data-testid]="'statement-entry-row-' + (n + 1)" [attr.data-kind]="e.kind">
                    <td data-testid="statement-entry-date">{{ e.date }}</td>
                    <td data-testid="statement-entry-description">{{ e.description }}</td>
                    <td data-testid="statement-po">{{ e.poNumber }}</td>
                    <td class="statement-money" data-testid="statement-entry-charge">
                      @if (e.charge !== null) {
                        {{ e.charge | money: s.currency }}
                      }
                    </td>
                    <td class="statement-money" data-testid="statement-entry-credit">
                      @if (e.credit !== null) {
                        {{ e.credit | money: s.currency }}
                      }
                    </td>
                    <td class="statement-money" data-testid="statement-running-balance">{{ e.balance | money: s.currency }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </section>
        @if (s.invoices.length === 0) {
          <p data-testid="client-statement-empty">No invoices yet.</p>
        } @else {
          <table data-testid="client-statement-table">
            <caption>Invoices for {{ s.clientName }}</caption>
            <thead>
              <tr>
                <th scope="col">Invoice</th>
                <th scope="col">Job</th>
                <th scope="col">Status</th>
                <th scope="col">Issued</th>
                <th scope="col">Due</th>
                <th scope="col">Amount</th>
                <th scope="col">Tax</th>
                <th scope="col">Left to pay</th>
              </tr>
            </thead>
            @for (job of s.jobs; track job.projectId) {
              <tbody [attr.data-testid]="'statement-project-' + job.projectId">
                <tr>
                  <th scope="colgroup" colspan="8" data-testid="statement-project-name">{{ job.projectName }}</th>
                </tr>
                @for (i of job.invoices; track i.id) {
                  <tr [attr.data-testid]="'statement-invoice-row-' + i.id">
                    <td data-testid="statement-invoice-id">
                      <a
                        [routerLink]="['/projects', i.projectId, 'invoices', i.id]"
                        [attr.data-testid]="'statement-invoice-open-' + i.id"
                        [attr.aria-label]="'Open invoice #' + i.id"
                        >#{{ i.id }}</a
                      >
                    </td>
                    <td data-testid="statement-invoice-project">{{ i.projectName }}</td>
                    <td data-testid="statement-invoice-status">{{ i.status }}</td>
                    <td data-testid="statement-invoice-issued">{{ i.issuedDate }}</td>
                    <td data-testid="statement-invoice-due">{{ i.dueDate }}</td>
                    <td data-testid="statement-invoice-amount">{{ i.amount | money: i.currency }}</td>
                    <td data-testid="statement-invoice-tax">{{ i.tax | money: i.currency }}</td>
                    <td data-testid="statement-invoice-due-amount">{{ i.amountDue | money: i.currency }}</td>
                  </tr>
                }
                <tr>
                  <th scope="row" colspan="6">Tax on {{ job.projectName }}</th>
                  <td data-testid="statement-project-tax">{{ job.tax | money: s.currency }}</td>
                  <td></td>
                </tr>
                <tr>
                  <th scope="row" colspan="7">Subtotal owed on {{ job.projectName }}</th>
                  <td data-testid="statement-project-subtotal">{{ job.subtotal | money: s.currency }}</td>
                </tr>
              </tbody>
            }
          </table>
        }
        <p>
          Total owed:
          <strong data-testid="client-outstanding-total">{{ s.outstanding | money: s.currency }}</strong>
        </p>
        <p data-testid="statement-home">
          Total owed in {{ s.homeCurrency }}:
          @if (s.homeOutstanding !== null) {
            <strong data-testid="statement-home-total">{{ s.homeOutstanding | money: s.homeCurrency }}</strong>
          } @else {
            <span data-testid="statement-home-total-unavailable">No exchange rate recorded for {{ s.currency }}.</span>
          }
        </p>
      </article>
    }
  `,
})
export class ClientStatement {
  private readonly service = inject(InvoiceService);

  /** Bound from the `:id` route parameter. */
  readonly id = input.required<string>();
  protected readonly clientId = computed(() => Number(this.id()));

  protected readonly statement = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.statementForClient(params),
  });

  /** The balance owed broken down by how far past due it is, as at today. */
  protected readonly aging = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.agingForClient(params),
  });

  /** The day the balance is looked up as at; unset until one is applied. */
  protected readonly asOf = signal<string | undefined>(undefined);

  protected readonly balanceAsOf = rxResource({
    params: () => {
      const asOf = this.asOf();
      return asOf ? { clientId: this.clientId(), asOf } : undefined;
    },
    stream: ({ params }) => this.service.balanceAsOf(params.clientId, params.asOf),
  });

  /** The date range the statement is run for; unset until one is applied. */
  protected readonly range = signal<{ from: string; to: string } | undefined>(undefined);
  protected readonly rangeInvalid = computed(() => {
    const range = this.range();
    return !!range && range.to < range.from;
  });

  protected readonly statementRange = rxResource({
    params: () => {
      const range = this.range();
      return range && !this.rangeInvalid() ? { clientId: this.clientId(), ...range } : undefined;
    },
    stream: ({ params }) => this.service.statementForRange(params.clientId, params.from, params.to),
  });

  protected applyRange(event: Event, from: string, to: string): void {
    event.preventDefault();
    if (from && to) {
      this.range.set({ from, to });
    }
  }

  protected applyAsOf(event: Event, value: string): void {
    event.preventDefault();
    if (value) {
      this.asOf.set(value);
    }
  }

  protected print(): void {
    window.print();
  }
}
