import { Component, computed, inject, signal } from '@angular/core';
import { Claims, counts } from '../../core/claims';
import { People } from '../../core/people';
import { Periods } from '../../core/periods';
import { Rates, hundredths, money } from '../../core/rates';

// The sheet's periods: one form adds a period, and the list shows each with its dates and its
// due-total, the due of the claims that count whose date falls in it.
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
        <button type="submit" data-testid="period-form-submit" [disabled]="saving()">Add period</button>
        @if (error(); as err) {
          <p role="alert" data-testid="period-form-error">{{ err }}</p>
        }
      </form>
      <table data-testid="period-list">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due total</th></tr>
        </thead>
        <tbody>
          @for (p of periods.periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotals().get(p.id) ?? '0.00' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class PeriodsPanel {
  protected readonly periods = inject(Periods);
  private readonly claims = inject(Claims);
  private readonly people = inject(People);
  private readonly rates = inject(Rates);
  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  /** Each period's due-total, by period id: the figures of the claims that count whose date falls
   *  in it — a settled claim's frozen due, else as its person's last statement bound it, else its
   *  live due (a claim with no figure adds nothing). */
  protected readonly dueTotals = computed(() => {
    const grades = new Map(this.people.people().map((p) => [p.id, p.grade]));
    const sums = new Map<number, bigint>();
    for (const c of this.claims.claims().filter(counts)) {
      const period = this.periods.of(c.date);
      if (!period) continue;
      let due: bigint | null;
      if (c.settledDue !== null) due = hundredths(c.settledDue);
      else if (c.stated) due = c.statedDue === null ? null : hundredths(c.statedDue);
      else {
        const grade = grades.get(c.personId);
        due = grade === undefined ? null : this.rates.dueCents(hundredths(c.amount), grade, c.date);
      }
      sums.set(period.id, (sums.get(period.id) ?? 0n) + (due ?? 0n));
    }
    return new Map([...sums].map(([id, sum]) => [id, money(sum)]));
  });

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.periods.add({ name: this.name(), from: this.from(), to: this.to() });
      if (err) this.error.set(err);
      else {
        this.name.set('');
        this.from.set('');
        this.to.set('');
      }
    } finally {
      this.saving.set(false);
    }
  }
}
