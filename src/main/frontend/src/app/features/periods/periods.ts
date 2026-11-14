import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { money } from '../claims/claims-api';
import { Period, PeriodsApi, periodError } from './periods-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's periods, by from-date, each with its due-total, and the form that adds a period.
@Component({
  selector: 'app-periods',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Add period">
        <label for="period-form-name">Name</label>
        <input id="period-form-name" data-testid="period-form-name" formControlName="name" autocomplete="off" />
        <label for="period-form-from">From</label>
        <input id="period-form-from" type="date" data-testid="period-form-from" formControlName="from" />
        <label for="period-form-to">To</label>
        <input id="period-form-to" type="date" data-testid="period-form-to" formControlName="to" />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error(); as err) {
          <p role="alert" data-testid="period-form-error">{{ err }}</p>
        }
      </form>
      <table data-testid="periods-list">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueOf(p) }}</td>
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

  /** The sheet's periods, by from-date. */
  readonly periods = input.required<Period[]>();
  /** Each period's due-total in cents, by period id. */
  readonly dueTotals = input.required<Map<number, number>>();
  /** A period just added, for the sheet to take in. */
  readonly added = output<Period>();

  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is ignored. */
  private submitting = false;

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
  }

  protected dueOf(p: Period): string {
    return money(this.dueTotals().get(p.id) ?? 0);
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const name = this.form.controls.name.value.trim();
    const from = this.form.controls.from.value.trim() || (this.session.today() ?? '');
    const to = this.form.controls.to.value.trim();
    if (!/^\S+$/.test(name)) {
      this.error.set('Enter a name without spaces.');
      return;
    }
    if (!DATE.test(from) || !DATE.test(to)) {
      this.error.set('Enter a from-date and a to-date as yyyy-mm-dd.');
      return;
    }
    if (from > to) {
      this.error.set('The from-date must be on or before the to-date.');
      return;
    }
    if (this.periods().some((p) => p.name.toLowerCase() === name.toLowerCase())) {
      this.error.set('The name is in use on the sheet.');
      return;
    }
    const overlapping = this.periods().find((p) => p.from <= to && from <= p.to);
    if (overlapping) {
      this.error.set(`The dates overlap period ${overlapping.name}.`);
      return;
    }
    this.submitting = true;
    try {
      const period = await this.api.add(sheetId, name, from, to);
      if (this.session.sheetId() === sheetId) this.added.emit(period);
      this.form.reset({ name: '', from: '', to: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(periodError(e));
    } finally {
      this.submitting = false;
    }
  }
}
