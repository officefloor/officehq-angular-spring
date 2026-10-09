import { CurrencyPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { INVOICE_STATUSES, InvoiceService, InvoiceStatus } from './invoice.service';

// Invoices page: every invoice from every project in one list, showing the project each is for and
// the stage it is at. The list can be narrowed to a single stage.
@Component({
  selector: 'app-invoices',
  imports: [CurrencyPipe, RouterLink],
  template: `
    <h1>Invoices</h1>

    <label for="invoice-status-filter">Stage</label>
    <select
      id="invoice-status-filter"
      data-testid="invoice-status-filter"
      [value]="status()"
      (change)="onStatusChange($event)"
    >
      <option value="">All stages</option>
      @for (s of statuses; track s) {
        <option [value]="s">{{ s }}</option>
      }
    </select>

    @if (invoices.error()) {
      <p role="alert" data-testid="all-invoices-error">Could not load the invoices.</p>
    } @else if (invoices.isLoading()) {
      <p data-testid="all-invoices-loading">Loading invoices…</p>
    } @else if (list().length === 0) {
      <p data-testid="all-invoices-empty">
        {{ status() ? 'No invoices at the ' + status() + ' stage.' : 'No invoices yet.' }}
      </p>
    } @else {
      <table data-testid="all-invoices-table">
        <caption>All invoices across every project</caption>
        <thead>
          <tr>
            <th scope="col">Invoice</th>
            <th scope="col">Project</th>
            <th scope="col">Amount</th>
            <th scope="col">Status</th>
            <th scope="col">Issued</th>
            <th scope="col">Due</th>
          </tr>
        </thead>
        <tbody>
          @for (i of list(); track i.id) {
            <tr [attr.data-testid]="'invoice-row-' + i.id">
              <td data-testid="invoice-id">#{{ i.id }}</td>
              <td data-testid="invoice-project">
                <a [routerLink]="['/projects', i.projectId]">{{ i.projectName }}</a>
              </td>
              <td data-testid="invoice-amount">{{ i.amount | currency: 'USD' : 'symbol' : '1.2-2' : 'en-US' }}</td>
              <td data-testid="invoice-status">{{ i.status }}</td>
              <td data-testid="invoice-issued">{{ i.issuedDate }}</td>
              <td data-testid="invoice-due">{{ i.dueDate }}</td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class Invoices {
  private readonly service = inject(InvoiceService);

  protected readonly statuses = INVOICE_STATUSES;
  /** The stage the list is narrowed to; empty for every stage. */
  protected readonly status = signal<InvoiceStatus | ''>('');

  protected readonly invoices = rxResource({
    params: () => this.status(),
    stream: ({ params }) => this.service.listAll(params || undefined),
  });
  protected readonly list = computed(() => (this.invoices.hasValue() ? this.invoices.value() : []));

  protected onStatusChange(event: Event): void {
    this.status.set((event.target as HTMLSelectElement).value as InvoiceStatus | '');
  }
}
