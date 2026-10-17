import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Claims, counts, formatCents } from '../../core/claims';
import { Person } from '../../core/people';
import { PERIOD_NAME, Periods } from '../../core/periods';
import { Rates } from '../../core/rates';
import { Session } from '../../core/session';

// The sheet's periods, each with the due-total of the counted claims dated in it, and the form that adds one.
@Component({
  selector: 'app-periods',
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>

      <form (submit)="save($event)">
        <label for="period-name">Name</label>
        <input
          id="period-name"
          data-testid="period-form-name"
          [value]="name()"
          (input)="name.set($any($event.target).value); error.set(null)"
        />
        <label for="period-from">From</label>
        <input
          id="period-from"
          type="date"
          data-testid="period-form-from"
          [value]="from()"
          (input)="from.set($any($event.target).value); error.set(null)"
        />
        <label for="period-to">To</label>
        <input
          id="period-to"
          type="date"
          data-testid="period-form-to"
          [value]="to()"
          (input)="to.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error(); as err) {
          <p role="alert" data-testid="period-form-error">{{ err }}</p>
        }
      </form>

      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due</th></tr>
        </thead>
        <tbody>
          @for (p of periods.all(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <td data-testid="period-name">{{ p.name }}</td>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotal(p.id) }}</td>
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
  private readonly rates = inject(Rates);
  private readonly session = inject(Session);

  /** The sheet's people, for the grade each claim's due is taken at. */
  readonly people = input.required<Person[]>();

  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  /** Set while a submit awaits the store, so a second submit does not add the period twice. */
  private saving = false;

  /**
   * Each period's due-total in cents, by period id: the due figures (as shown, each already rounded)
   * of the counted claims dated in it, skipping those that are n/a.
   */
  private readonly dueCents = computed(() => {
    const grades = new Map(this.people().map((p) => [p.id, p.grade]));
    const out = new Map<number, number>();
    for (const c of this.claims.all()) {
      const grade = grades.get(c.personId);
      const period = this.periods.on(c.date);
      if (!counts(c) || grade === undefined || period === undefined) continue;
      const due = this.rates.dueOf(c, grade);
      if (due !== undefined) out.set(period.id, (out.get(period.id) ?? 0) + due);
    }
    return out;
  });

  constructor() {
    // Re-read the store each time the sheet is (re-)loaded.
    effect(() => {
      this.session.loads();
      untracked(() => {
        this.error.set(null);
        void this.periods.reload();
      });
    });
  }

  protected dueTotal(periodId: number): string {
    return formatCents(this.dueCents().get(periodId) ?? 0);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const name = this.name().trim();
    const from = this.from().trim();
    const to = this.to().trim();
    if (!PERIOD_NAME.test(name)) {
      this.error.set('Enter a name of up to 100 characters, without spaces.');
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      this.error.set('Enter a from and a to date as yyyy-mm-dd.');
      return;
    }
    if (from > to) {
      this.error.set('The to date cannot be before the from date.');
      return;
    }
    const lower = name.toLowerCase();
    const clash = this.periods.all().find((p) => p.name.toLowerCase() === lower);
    if (clash) {
      this.error.set(`A period named ${clash.name} already exists.`);
      return;
    }
    const overlap = this.periods.all().find((p) => p.from <= to && from <= p.to);
    if (overlap) {
      this.error.set(`The period overlaps ${overlap.name}.`);
      return;
    }
    this.saving = true;
    try {
      await this.periods.add(name, from, to);
      this.name.set('');
      this.from.set('');
      this.to.set('');
    } catch {
      this.error.set('The period could not be added: its name is taken or it overlaps another period.');
    } finally {
      this.saving = false;
    }
  }
}
