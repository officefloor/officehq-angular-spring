import { CurrencyPipe } from '@angular/common';
import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { InvoiceService } from './invoice.service';

// A client's statement: every invoice across the client's projects grouped by job, how much is left to
// pay on each, a subtotal still owed per job, and the total the client still owes. Drafts are listed but do not count towards what is owed.
// It is laid out to print cleanly: a summary of what was invoiced, what was paid and the grand total owed,
// with the app's navigation and the page's controls left off the printed copy.
@Component({
  selector: 'app-client-statement',
  imports: [CurrencyPipe, RouterLink],
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
            <dd data-testid="statement-total-invoiced">{{ s.invoiced | currency: s.currency : 'symbol' : '1.2-2' : 'en-US' }}</dd>
            <dt>Less paid</dt>
            <dd data-testid="statement-total-paid">{{ s.paid | currency: s.currency : 'symbol' : '1.2-2' : 'en-US' }}</dd>
            <dt class="statement-grand-total">Grand total owed</dt>
            <dd class="statement-grand-total" data-testid="statement-grand-total">{{ s.outstanding | currency: s.currency : 'symbol' : '1.2-2' : 'en-US' }}</dd>
          </dl>
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
                <th scope="col">Left to pay</th>
              </tr>
            </thead>
            @for (job of s.jobs; track job.projectId) {
              <tbody [attr.data-testid]="'statement-project-' + job.projectId">
                <tr>
                  <th scope="colgroup" colspan="7" data-testid="statement-project-name">{{ job.projectName }}</th>
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
                    <td data-testid="statement-invoice-amount">{{ i.amount | currency: s.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
                    <td data-testid="statement-invoice-due-amount">{{ i.amountDue | currency: s.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
                  </tr>
                }
                <tr>
                  <th scope="row" colspan="6">Subtotal owed on {{ job.projectName }}</th>
                  <td data-testid="statement-project-subtotal">{{ job.subtotal | currency: s.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
                </tr>
              </tbody>
            }
          </table>
        }
        <p>
          Total owed:
          <strong data-testid="client-outstanding-total">{{ s.outstanding | currency: s.currency : 'symbol' : '1.2-2' : 'en-US' }}</strong>
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

  protected print(): void {
    window.print();
  }
}
