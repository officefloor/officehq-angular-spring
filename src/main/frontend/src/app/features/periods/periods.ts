import { Component, computed, inject, signal } from '@angular/core';
import { Claims, counts } from '../../core/claims';
import { People } from '../../core/people';
import { Period, Periods } from '../../core/periods';
import { Rates, hundredths, money } from '../../core/rates';

// The sheet's periods: one form adds a period, and the list shows each with its dates and its
// due-total, the due of the claims that count whose date falls in it (fixed once it is closed), and
// whether it is closed, with a button to close it.
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
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due total</th><th scope="col">Closed</th><th scope="col">Actions</th></tr>
        </thead>
        <tbody>
          @for (p of periods.periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ p.closedTotal ?? dueTotals().get(p.id) ?? '0.00' }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : 'no' }}</td>
              <td>
                <button type="button" data-testid="period-close" [disabled]="acting() === p.id" (click)="close(p)">
                  Close<span class="visually-hidden"> period {{ p.name }}</span>
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
  private readonly claims = inject(Claims);
  private readonly people = inject(People);
  private readonly rates = inject(Rates);
  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  /** The period whose action is under way, and the last action's refusal, by period. */
  protected readonly acting = signal<number | null>(null);
  protected readonly actionError = signal<{ id: number; message: string } | null>(null);

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

  protected async close(p: Period): Promise<void> {
    if (this.acting() !== null) return;
    this.actionError.set(null);
    this.acting.set(p.id);
    try {
      const err = await this.periods.close(p.id);
      if (err) this.actionError.set({ id: p.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

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
