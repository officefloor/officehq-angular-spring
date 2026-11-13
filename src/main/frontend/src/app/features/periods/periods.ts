import { Component, inject, signal } from '@angular/core';
import { Figures } from '../../core/figures';
import { Period, Periods } from '../../core/periods';

// The sheet's periods: one form adds a period, another generates one per calendar month, a third the twelve of a fiscal year,
// and the list shows each with its dates, its cap on each person's open claims in it, the fiscal year its from-date falls in, and its
// due-total, the due of the claims that count whose date falls in it (fixed once it is closed), and
// whether it is closed or soft-closed, with buttons to close it, soft-close it and reopen it (by the roles the sheet allows).
@Component({
  selector: 'app-periods',
  template: `
    <section aria-label="Periods" data-testid="periods">
      <h2>Periods</h2>
      <form (submit)="submit($event)" data-testid="period-form">
        <label for="period-form-name">Name</label>
        <input
          id="period-form-name"
          data-testid="period-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="period-form-from">From (YYYY-MM-DD)</label>
        <input
          id="period-form-from"
          data-testid="period-form-from"
          placeholder="YYYY-MM-DD"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="period-form-to">To (YYYY-MM-DD)</label>
        <input
          id="period-form-to"
          data-testid="period-form-to"
          placeholder="YYYY-MM-DD"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <label for="period-form-cap">Open claims cap per person (blank for none)</label>
        <input
          id="period-form-cap"
          data-testid="period-form-cap"
          inputmode="numeric"
          [value]="cap()"
          (input)="cap.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="period-form-submit" [disabled]="saving()">Add period</button>
        @if (error(); as err) {
          <p role="alert" data-testid="period-form-error">{{ err }}</p>
        }
      </form>
      <form (submit)="generate($event)" data-testid="period-generate-form">
        <label for="period-generate-from">Generate monthly periods from (YYYY-MM-DD)</label>
        <input
          id="period-generate-from"
          data-testid="period-generate-from"
          placeholder="YYYY-MM-DD"
          [value]="genFrom()"
          (input)="genFrom.set(value($event)); genError.set(null)"
        />
        <label for="period-generate-to">To (YYYY-MM-DD)</label>
        <input
          id="period-generate-to"
          data-testid="period-generate-to"
          placeholder="YYYY-MM-DD"
          [value]="genTo()"
          (input)="genTo.set(value($event)); genError.set(null)"
        />
        <button type="submit" data-testid="period-generate" [disabled]="generating()">Generate periods</button>
      </form>
      <form (submit)="generateFy($event)" data-testid="period-generate-fy-form">
        <label for="period-generate-fy-year">Generate the monthly periods of fiscal year (YYYY)</label>
        <input
          id="period-generate-fy-year"
          data-testid="period-generate-fy-year"
          inputmode="numeric"
          placeholder="YYYY"
          [value]="fyYear()"
          (input)="fyYear.set(value($event)); genError.set(null)"
        />
        <button type="submit" data-testid="period-generate-fy" [disabled]="generating()">Generate fiscal year</button>
      </form>
      @if (generated() !== null) {
        <p>Made <span data-testid="period-generated">{{ generated() }}</span> period(s)</p>
      }
      @if (genError(); as err) {
        <p role="alert" data-testid="period-generate-error">{{ err }}</p>
      }
      <table data-testid="period-list">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Fiscal year</th><th scope="col">Due total</th><th scope="col">Closed</th><th scope="col">Cap</th><th scope="col">Actions</th></tr>
        </thead>
        <tbody>
          @for (p of periods.periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-fy">{{ periods.fiscalYearOf(p.from) }}</td>
              <td data-testid="period-due-total">{{ p.closedTotal ?? dueTotals().get(p.id) ?? '0.00' }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.soft ? 'soft' : 'no' }}</td>
              <td data-testid="period-cap">{{ p.cap ?? '' }}</td>
              <td>
                <button type="button" data-testid="period-close" [disabled]="acting() === p.id" (click)="close(p)">
                  Close<span class="visually-hidden"> period {{ p.name }}</span>
                </button>
                <button type="button" data-testid="period-soft-close" [disabled]="acting() === p.id" (click)="softClose(p)">
                  Soft-close<span class="visually-hidden"> period {{ p.name }}</span>
                </button>
                <button type="button" data-testid="period-reopen" [disabled]="acting() === p.id" (click)="reopen(p)">
                  Reopen<span class="visually-hidden"> period {{ p.name }}</span>
                </button>
                @if (actionError()?.id === p.id) {
                  <p role="alert" data-testid="period-error">{{ actionError()?.message }}</p>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class PeriodsPanel {
  protected readonly periods = inject(Periods);
  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly cap = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  /** The generate form: its dates, how many it last made, and its refusal. */
  protected readonly genFrom = signal('');
  protected readonly genTo = signal('');
  protected readonly generating = signal(false);
  protected readonly generated = signal<number | null>(null);
  protected readonly genError = signal<string | null>(null);
  /** The fiscal year typed for the fiscal-year generator, which shares the count and refusal above. */
  protected readonly fyYear = signal('');
  /** The period whose action is under way, and the last action's refusal, by period. */
  protected readonly acting = signal<number | null>(null);
  protected readonly actionError = signal<{ id: number; message: string } | null>(null);

  /** Each open period's live due-total, by period id (a closed one shows the total fixed when it was
   *  closed). */
  protected readonly dueTotals = inject(Figures).periodDueTotals;

  protected close(p: Period): Promise<void> {
    return this.act(p, () => this.periods.close(p.id));
  }

  protected softClose(p: Period): Promise<void> {
    return this.act(p, () => this.periods.softClose(p.id));
  }

  protected reopen(p: Period): Promise<void> {
    return this.act(p, () => this.periods.reopen(p.id));
  }

  private async act(p: Period, action: () => Promise<string | null>): Promise<void> {
    if (this.acting() !== null) return;
    this.actionError.set(null);
    this.acting.set(p.id);
    try {
      const err = await action();
      if (err) this.actionError.set({ id: p.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async generate(e: Event): Promise<void> {
    e.preventDefault();
    if (this.generating()) return;
    this.generated.set(null);
    this.genError.set(null);
    this.generating.set(true);
    try {
      const made = await this.periods.generate(this.genFrom(), this.genTo());
      if (typeof made === 'string') this.genError.set(made);
      else this.generated.set(made);
    } finally {
      this.generating.set(false);
    }
  }

  protected async generateFy(e: Event): Promise<void> {
    e.preventDefault();
    if (this.generating()) return;
    this.generated.set(null);
    this.genError.set(null);
    this.generating.set(true);
    try {
      const made = await this.periods.generateFiscalYear(this.fyYear());
      if (typeof made === 'string') this.genError.set(made);
      else this.generated.set(made);
    } finally {
      this.generating.set(false);
    }
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.periods.add({ name: this.name(), from: this.from(), to: this.to(), cap: this.cap() });
      if (err) this.error.set(err);
      else {
        this.name.set('');
        this.from.set('');
        this.to.set('');
        this.cap.set('');
      }
    } finally {
      this.saving.set(false);
    }
  }
}
