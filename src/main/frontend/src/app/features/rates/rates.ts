import { Component, computed, inject, signal } from '@angular/core';
import { Figures } from '../../core/figures';
import { Rate, Rates, money } from '../../core/rates';
import { Session } from '../../core/session';

// The sheet's rates for each grade: one form sets a grade's rate from a date (or from the beginning
// of time) through a to-date (or for good), one table shows each grade's rate in force on the sheet's today, or n/a when none
// applies, a calendar shows each grade's rate in force on the first day of each month of the sheet's
// today's calendar year, and another lists every rate with its from- and to-date. A preview shows how many open and
// pending claims of a grade a candidate rate would change, and by how much; it saves nothing.
@Component({
  selector: 'app-rates',
  template: `
    <section aria-label="Rates" data-testid="rates">
      <h2>Rates</h2>
      <form (submit)="submit($event)" data-testid="rate-form">
        <label for="rate-form-grade">Grade</label>
        <select
          id="rate-form-grade"
          data-testid="rate-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input
          id="rate-form-pct"
          data-testid="rate-form-pct"
          inputmode="decimal"
          [value]="pct()"
          (input)="pct.set(value($event)); error.set(null)"
        />
        <label for="rate-form-from">From (YYYY-MM-DD, blank for always)</label>
        <input
          id="rate-form-from"
          data-testid="rate-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="rate-form-to">To (YYYY-MM-DD, blank for no end)</label>
        <input
          id="rate-form-to"
          data-testid="rate-form-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="rate-form-submit" [disabled]="saving()">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err }}</p>
        }
      </form>
      <form (submit)="preview($event)" data-testid="rate-preview-form" aria-label="Rate preview">
        <label for="rate-preview-grade">Preview grade</label>
        <select
          id="rate-preview-grade"
          data-testid="rate-preview-grade"
          [value]="previewGrade()"
          (change)="previewGrade.set(value($event)); clearPreview()"
        >
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === previewGrade()">{{ g }}</option>
          }
        </select>
        <label for="rate-preview-pct">Candidate rate (%)</label>
        <input
          id="rate-preview-pct"
          data-testid="rate-preview-pct"
          inputmode="decimal"
          [value]="previewPct()"
          (input)="previewPct.set(value($event)); clearPreview()"
        />
        <button type="submit" data-testid="rate-preview">Preview</button>
        @if (previewed(); as r) {
          <p>
            Claims changed <span data-testid="rate-preview-count">{{ r.count }}</span>,
            difference <span data-testid="rate-preview-diff">{{ r.diff }}</span>
          </p>
        }
        @if (previewError(); as err) {
          <p role="alert" data-testid="rate-preview-error">{{ err }}</p>
        }
      </form>
      <table data-testid="rate-today">
        <caption>In force today</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades(); track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ rates.current(g) }}</td>
            </tr>
          }
        </tbody>
      </table>
      @if (months().length) {
        <table data-testid="rate-calendar">
          <caption>On the first of each month</caption>
          <thead>
            <tr>
              <th scope="col">Grade</th>
              @for (m of months(); track m) {
                <th scope="col">{{ m }}</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (g of grades(); track g) {
              <tr>
                <th scope="row">{{ g }}</th>
                @for (m of months(); track m) {
                  <td [attr.data-testid]="'rate-month-' + g + '-' + m">{{ rates.onDate(g, m + '-01') }}</td>
                }
              </tr>
            }
          </tbody>
        </table>
      }
      <table data-testid="rate-list">
        <caption>All rates</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Rate (%)</th><th scope="col">Versions</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (r of rates.rates(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? 'always' }}</td>
              <td data-testid="rate-to">{{ r.to ?? '' }}</td>
              <td><span data-testid="rate-pct">{{ r.rate }}</span></td>
              <td data-testid="rate-version">{{ r.version }}</td>
              <td>
                <button
                  type="button"
                  data-testid="rate-withdraw"
                  [disabled]="saving()"
                  [attr.aria-label]="'Withdraw rate ' + r.grade + ' ' + (r.from ?? 'always')"
                  (click)="withdraw(r)"
                >
                  Withdraw
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class RatesPanel {
  protected readonly rates = inject(Rates);
  private readonly figures = inject(Figures);
  private readonly session = inject(Session);
  protected readonly grades = this.session.grades;
  protected readonly grade = signal<string>('A');
  protected readonly pct = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  /** The months (YYYY-MM) of the sheet's today's calendar year; none before a today is known. */
  protected readonly months = computed(() => {
    const year = this.session.shownToday()?.slice(0, 4);
    return year ? Array.from({ length: 12 }, (_, i) => `${year}-${String(i + 1).padStart(2, '0')}`) : [];
  });

  protected readonly previewGrade = signal<string>('A');
  protected readonly previewPct = signal('');
  protected readonly previewed = signal<{ count: number; diff: string } | null>(null);
  protected readonly previewError = signal<string | null>(null);

  protected clearPreview(): void {
    this.previewed.set(null);
    this.previewError.set(null);
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.rates.set(this.grade(), this.pct(), this.from(), this.to());
      if (err) this.error.set(err);
      else {
        this.pct.set('');
        this.from.set('');
        this.to.set('');
      }
    } finally {
      this.saving.set(false);
    }
  }

  /** Withdraw a rate's newest version; a refusal shows on the rate form's error. */
  protected async withdraw(r: Rate): Promise<void> {
    if (this.saving()) return;
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.rates.withdraw(r.grade, r.from);
      if (err) this.error.set(err);
    } finally {
      this.saving.set(false);
    }
  }

  protected preview(e: Event): void {
    e.preventDefault();
    this.clearPreview();
    const pct = this.previewPct().trim();
    if (!/^\d+(\.\d{1,2})?$/.test(pct)) {
      this.previewError.set('Enter a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    const r = this.figures.ratePreview(this.previewGrade(), pct);
    // shown after a tick, so two presses with the same result can be told apart
    setTimeout(() => this.previewed.set({ count: r.count, diff: money(r.diffCents) }));
  }
}
