import { Component, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MoneyPipe } from '../currencies/money.pipe';
import { DashboardService } from './dashboard.service';

// A reconciliation proving the books balance: every client's balance (by the same rule as the dashboard's outstanding
// figure, so disputed and written-off invoices are left out), their total, and the outstanding figure it is checked
// against, with whether the two agree and by how much they differ if not.
@Component({
  selector: 'app-reconciliation',
  imports: [MoneyPipe],
  styles: `
    td.amount,
    tfoot td {
      text-align: right;
    }
  `,
  template: `
    <section aria-labelledby="reconciliation-heading" data-testid="reconciliation">
      <h2 id="reconciliation-heading">Reconciliation</h2>
      @if (report.error()) {
        <p role="alert" data-testid="reconciliation-error">Could not load the reconciliation.</p>
      } @else if (report.value(); as r) {
        <table data-testid="reconciliation-table">
          <thead>
            <tr>
              <th scope="col">Client</th>
              <th scope="col">Balance ({{ r.currency }})</th>
            </tr>
          </thead>
          <tbody>
            @for (c of r.clients; track c.id) {
              <tr [attr.data-testid]="'reconciliation-client-row-' + c.id">
                <th scope="row" data-testid="reconciliation-client-name">{{ c.name }}</th>
                <td class="amount" data-testid="reconciliation-client-balance">{{ c.balance | money: r.currency }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="2" data-testid="reconciliation-empty">No clients yet.</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row">Sum of client balances</th>
              <td data-testid="reconciliation-total">{{ r.total | money: r.currency }}</td>
            </tr>
            <tr>
              <th scope="row">Total outstanding</th>
              <td data-testid="reconciliation-outstanding">{{ r.outstanding | money: r.currency }}</td>
            </tr>
            <tr>
              <th scope="row">Difference</th>
              <td data-testid="reconciliation-difference">{{ r.difference | money: r.currency }}</td>
            </tr>
          </tfoot>
        </table>
        <p role="status">
          <strong data-testid="reconciliation-balanced">{{ r.balanced ? 'Balanced' : 'Out of balance' }}</strong>
        </p>
      } @else {
        <p data-testid="reconciliation-loading">Loading…</p>
      }
    </section>
  `,
})
export class ReconciliationPanel {
  private readonly service = inject(DashboardService);

  /** Bumped by the dashboard whenever the currency its totals are shown in changes, to reload them in it. */
  readonly totalsVersion = input(0);

  protected readonly report = rxResource({ params: () => this.totalsVersion(), stream: () => this.service.reconciliation() });
}
