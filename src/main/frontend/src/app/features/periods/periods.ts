import { HttpClient } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Period {
  id: number;
  name: string;
  /** The first day of the period (yyyy-mm-dd), inclusive. */
  from: string;
  /** The last day of the period (yyyy-mm-dd), inclusive. */
  to: string;
}

/** The period whose dates contain the date; undefined when none does. */
export function periodOn(periods: Period[], date: string): Period | undefined {
  return periods.find((p) => p.from <= date && date <= p.to);
}

// The sheet's periods in date order, each with its due-total, and the form that adds a period.
@Component({
  selector: 'app-periods',
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>
      <form (submit)="submit($event)">
        <label for="period-form-name">Name</label>
        <input
          id="period-form-name"
          data-testid="period-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="period-form-from">From</label>
        <input
          id="period-form-from"
          data-testid="period-form-from"
          type="date"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="period-form-to">To</label>
        <input
          id="period-form-to"
          data-testid="period-form-to"
          type="date"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error(); as message) {
          <p role="alert" data-testid="period-form-error">{{ message }}</p>
        }
      </form>
      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotals().get(p.name) ?? '0.00' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Periods {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's periods in date order. */
  readonly periods = input.required<Period[]>();
  /** Each period's due-total by its name: the due of the counting claims dated in it. */
  readonly dueTotals = input.required<Map<string, string>>();
  /** A period was added. */
  readonly saved = output<Period>();

  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.name().trim();
    if (!/^\S{1,100}$/.test(name)) {
      this.error.set('Type a name for the period, without spaces.');
      return;
    }
    const from = this.from().trim() || (this.session.today() ?? '');
    const to = this.to().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      this.error.set('Pick a from and a to date for the period.');
      return;
    }
    if (to < from) {
      this.error.set('The period cannot end before it starts.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Period>(
          `/api/sheets/${encodeURIComponent(sheet)}/periods`,
          { name, from, to },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) {
        this.name.set('');
        this.from.set('');
        this.to.set('');
        this.saved.emit(saved);
      }
    } catch {
      this.error.set('The period could not be added: its name is taken, or it overlaps another period.');
    } finally {
      this.submitting = false;
    }
  }
}
