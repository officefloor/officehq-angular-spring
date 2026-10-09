import { CurrencyPipe } from '@angular/common';
import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { InvoiceService } from './invoice.service';

// A client's statement: every invoice across the client's projects, how much is left to pay on each,
// and the total the client still owes. Drafts are listed but do not count towards what is owed.
@Component({
  selector: 'app-client-statement',
  imports: [CurrencyPipe, RouterLink],
  template: `
    <a [routerLink]="['/clients', clientId()]" data-testid="client-statement-back">Back to client</a>
    @if (statement.error()) {
      <p role="alert" data-testid="client-statement-error">Could not load the statement.</p>
    } @else if (statement.value(); as s) {
      <h1 data-testid="client-statement-name">Statement for {{ s.clientName }}</h1>
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
          <tbody>
            @for (i of s.invoices; track i.id) {
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
                <td data-testid="statement-invoice-amount">{{ i.amount | currency: 'USD' : 'symbol' : '1.2-2' : 'en-US' }}</td>
                <td data-testid="statement-invoice-due-amount">{{ i.amountDue | currency: 'USD' : 'symbol' : '1.2-2' : 'en-US' }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
      <p>
        Total owed:
        <strong data-testid="client-outstanding-total">{{ s.outstanding | currency: 'USD' : 'symbol' : '1.2-2' : 'en-US' }}</strong>
      </p>
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
}
