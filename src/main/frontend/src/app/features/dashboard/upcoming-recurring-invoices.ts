import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { MoneyPipe } from '../currencies/money.pipe';
import { RecurringInvoiceService } from '../recurring/recurring-invoice.service';

// The recurring invoices across all projects that are coming up (next falling today or later), soonest first,
// each with the date it next falls on.
@Component({
  selector: 'app-upcoming-recurring-invoices',
  imports: [MoneyPipe, RouterLink],
  template: `
    <section aria-labelledby="upcoming-recurring-heading" data-testid="upcoming-recurring">
      <h2 id="upcoming-recurring-heading">Upcoming recurring invoices</h2>
      @if (upcoming.error()) {
        <p role="alert" data-testid="upcoming-recurring-error">Could not load the upcoming recurring invoices.</p>
      } @else if (upcoming.hasValue()) {
        @if (upcoming.value().length === 0) {
          <p data-testid="upcoming-recurring-empty">No recurring invoices coming up.</p>
        } @else {
          <table>
            <thead>
              <tr>
                <th scope="col">Next invoice</th>
                <th scope="col">Job</th>
                <th scope="col">Client</th>
                <th scope="col">Amount</th>
                <th scope="col">Repeats</th>
              </tr>
            </thead>
            <tbody>
              @for (r of upcoming.value(); track r.id) {
                <tr [attr.data-testid]="'recurring-row-' + r.id">
                  <td data-testid="recurring-next-date">{{ r.nextDate }}</td>
                  <td data-testid="recurring-project">
                    <a [routerLink]="['/projects', r.projectId]">{{ r.projectName }}</a>
                  </td>
                  <td data-testid="recurring-client">{{ r.clientName }}</td>
                  <td data-testid="recurring-amount">{{ r.amount | money: r.currency }}</td>
                  <td data-testid="recurring-frequency">{{ r.frequency }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      } @else {
        <p data-testid="upcoming-recurring-loading">Loading…</p>
      }
    </section>
  `,
})
export class UpcomingRecurringInvoices {
  private readonly service = inject(RecurringInvoiceService);

  protected readonly upcoming = rxResource({ stream: () => this.service.upcoming() });
}
