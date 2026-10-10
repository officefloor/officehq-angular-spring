import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { MoneyPipe } from '../currencies/money.pipe';
import { AgingBucket, AgingReport, DashboardService } from './dashboard.service';

interface BucketView {
  bucket: AgingBucket;
  key: string;
  label: string;
  amount: (r: AgingReport) => number;
}

const BUCKETS: BucketView[] = [
  { bucket: 'CURRENT', key: 'current', label: 'Current (up to 30 days overdue)', amount: (r) => r.current },
  { bucket: 'DAYS_30_60', key: '30-60', label: '31 to 60 days overdue', amount: (r) => r.days30To60 },
  { bucket: 'DAYS_60_PLUS', key: '60-plus', label: 'More than 60 days overdue', amount: (r) => r.days60Plus },
];

// An aging report across all clients: what is left to pay on sent invoices, in the home currency, split by how many
// days past due each invoice is — current (not yet due, or up to 30 days overdue), 31 to 60 days, and more than 60.
// Clicking a bucket lists the invoices that make it up. The report can be exported to a CSV file.
@Component({
  selector: 'app-aging-report',
  imports: [MoneyPipe, RouterLink],
  styles: `
    .aging-report-figures {
      display: grid;
      grid-template-columns: max-content max-content;
      gap: 0.25rem 1.5rem;
    }
    .aging-report-figures dd {
      margin: 0;
      text-align: right;
    }
    .aging-bucket-open {
      font: inherit;
      text-align: left;
      padding: 0;
      background: none;
      border: none;
      color: inherit;
      text-decoration: underline;
      cursor: pointer;
    }
    .aging-detail td.amount {
      text-align: right;
    }
  `,
  template: `
    <section aria-labelledby="aging-report-heading" data-testid="aging-report">
      <h2 id="aging-report-heading">Aging report</h2>
      @if (report.error()) {
        <p role="alert" data-testid="aging-report-error">Could not load the aging report.</p>
      } @else if (report.value(); as r) {
        <dl class="aging-report-figures">
          @for (b of buckets; track b.bucket) {
            <dt>
              <button
                type="button"
                class="aging-bucket-open"
                [attr.data-testid]="'aging-bucket-' + b.key + '-open'"
                [attr.aria-expanded]="selected() === b.bucket"
                aria-controls="aging-detail"
                (click)="toggle(b.bucket)"
              >
                {{ b.label }}
              </button>
            </dt>
            <dd [attr.data-testid]="'aging-report-' + b.key">{{ b.amount(r) | money: r.homeCurrency }}</dd>
          }
          <dt>Total outstanding</dt>
          <dd data-testid="aging-report-total">{{ r.total | money: r.homeCurrency }}</dd>
        </dl>
        <p data-testid="aging-report-asof">As at {{ r.asOf }}</p>
        <p>
          <button type="button" data-testid="aging-export" [disabled]="exporting()" (click)="export()">
            Export aging report
          </button>
        </p>
        <div role="status">
          @if (exportError()) {
            <p data-testid="aging-export-error">Could not export the aging report.</p>
          } @else if (exported()) {
            <p data-testid="export-confirm">Exported the aging report to {{ exportFilename }}.</p>
          }
        </div>

        <div id="aging-detail" aria-live="polite">
          @if (selectedView(); as b) {
            <section class="aging-detail" data-testid="aging-detail" [attr.aria-label]="b.label + ' invoices'">
              <h3 data-testid="aging-detail-heading">{{ b.label }}</h3>
              @if (linesFor(r, b.bucket); as lines) {
                @if (lines.length) {
                  <table data-testid="aging-detail-table">
                    <thead>
                      <tr>
                        <th scope="col">Invoice</th>
                        <th scope="col">Client</th>
                        <th scope="col">Due date</th>
                        <th scope="col">Days overdue</th>
                        <th scope="col">Outstanding ({{ r.homeCurrency }})</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (line of lines; track line.invoiceId) {
                        <tr [attr.data-testid]="'aging-detail-row-' + line.invoiceId">
                          <td>
                            <a
                              [routerLink]="['/projects', line.projectId, 'invoices', line.invoiceId]"
                              data-testid="aging-detail-invoice"
                              >#{{ line.invoiceId }}</a
                            >
                          </td>
                          <td data-testid="aging-detail-client">{{ line.clientName }}</td>
                          <td data-testid="aging-detail-due">{{ line.dueDate ?? '—' }}</td>
                          <td data-testid="aging-detail-days">{{ line.daysOverdue }}</td>
                          <td class="amount" data-testid="aging-detail-amount">
                            {{ line.amount | money: r.homeCurrency }}
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                } @else {
                  <p data-testid="aging-detail-empty">No invoices in this bucket.</p>
                }
              }
            </section>
          }
        </div>
      } @else {
        <p data-testid="aging-report-loading">Loading…</p>
      }
    </section>
  `,
})
export class AgingReportPanel {
  private readonly service = inject(DashboardService);

  protected readonly buckets = BUCKETS;
  protected readonly report = rxResource({ stream: () => this.service.agingReport() });
  protected readonly selected = signal<AgingBucket | null>(null);

  protected readonly selectedView = computed(() => BUCKETS.find((b) => b.bucket === this.selected()));

  protected readonly exportFilename = 'aging-report.csv';
  protected readonly exporting = signal(false);
  protected readonly exported = signal(false);
  protected readonly exportError = signal(false);

  /** Downloads the aging report as a CSV file and confirms it. */
  protected export(): void {
    this.exporting.set(true);
    this.exported.set(false);
    this.exportError.set(false);
    this.service.exportAgingReport().subscribe({
      next: (csv) => {
        download(csv, this.exportFilename);
        this.exported.set(true);
        this.exporting.set(false);
      },
      error: () => {
        this.exportError.set(true);
        this.exporting.set(false);
      },
    });
  }

  protected linesFor(r: AgingReport, bucket: AgingBucket) {
    return r.invoices.filter((line) => line.bucket === bucket);
  }

  protected toggle(bucket: AgingBucket): void {
    this.selected.update((current) => (current === bucket ? null : bucket));
  }
}

/** Saves the text as a file through the browser. */
function download(text: string, filename: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url));
}
