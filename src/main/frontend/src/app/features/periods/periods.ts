import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { PERIOD_DATE, PeriodView, PeriodsApi } from '../../core/periods';
import { Session } from '../../core/session';

// The sheet's periods: listed by from-date with the due-total of the counting claims dated in each,
// and one form to add a period (a unique name and inclusive from and to dates, overlapping no other).
@Component({
  selector: 'app-periods',
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>
      <form (submit)="save($event)" aria-label="Add a period">
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
          type="date"
          data-testid="period-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="period-form-to">To</label>
        <input
          id="period-form-to"
          type="date"
          data-testid="period-form-to"
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
          <tr>
            <th scope="col">Period</th>
            <th scope="col">From</th>
            <th scope="col">To</th>
            <th scope="col">Due</th>
          </tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
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
export class Periods {
  private readonly session = inject(Session);
  private readonly api = inject(PeriodsApi);

  /** The sheet's periods by from-date. */
  readonly periods = input.required<PeriodView[]>();
  /** Each period's due-total, by period id, with two decimals. */
  readonly dueTotals = input.required<Map<number, string>>();
  /** A period added through the form. */
  readonly saved = output<PeriodView>();

  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const form = { name: this.name().trim(), from: this.from().trim(), to: this.to().trim() };
    if (!form.name) {
      this.error.set('Type a name for the period.');
      return;
    }
    if (!PERIOD_DATE.test(form.from) || !PERIOD_DATE.test(form.to)) {
      this.error.set('Pick a from and a to date for the period.');
      return;
    }
    if (form.from > form.to) {
      this.error.set('The from date cannot be after the to date.');
      return;
    }
    this.busy = true;
    try {
      this.saved.emit(await this.api.add(sheet, form));
      this.name.set('');
      this.from.set('');
      this.to.set('');
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'The name is already in use, or the dates overlap another period.'
          : 'The period could not be added.',
      );
    } finally {
      this.busy = false;
    }
  }
}
