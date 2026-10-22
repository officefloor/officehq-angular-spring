import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { PERIOD_DATE, PeriodView, PeriodsApi } from '../../core/periods';
import { Session } from '../../core/session';

// The sheet's periods: listed by from-date with the due-total of the counting claims dated in each,
// and one form to add a period (a unique name and inclusive from and to dates, overlapping no other).
// A period can be closed: its due-total is then the one fixed at closing. A steward can reopen a
// closed period, making its due-total live again.
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
            <th scope="col">Closed</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ p.closedTotal ?? dueTotals().get(p.id) ?? '0.00' }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : 'no' }}</td>
              <td>
                @if (!p.closed) {
                  <button
                    type="button"
                    data-testid="period-close"
                    [attr.aria-label]="'Close ' + p.name"
                    (click)="close(p)"
                  >
                    Close
                  </button>
                }
                <button
                  type="button"
                  data-testid="period-reopen"
                  [attr.aria-label]="'Reopen ' + p.name"
                  (click)="reopen(p)"
                >
                  Reopen
                </button>
                @if (periodError()?.id === p.id) {
                  <p role="alert" data-testid="period-error">{{ periodError()?.message }}</p>
                }
              </td>
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
  /** A period closed through its row. */
  readonly closed = output<PeriodView>();
  /** A period reopened through its row. */
  readonly reopened = output<PeriodView>();

  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  /** The refusal of the last action taken on a period's row, shown on that row. */
  protected readonly periodError = signal<{ id: number; message: string } | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Close a period; a refusal is shown on its row. */
  protected async close(period: PeriodView): Promise<void> {
    this.periodError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      this.closed.emit(await this.api.close(sheet, period.id));
    } catch {
      this.periodError.set({ id: period.id, message: 'The period could not be closed.' });
    }
  }

  /** Reopen a period; a refusal (not a steward, or not closed) is shown on its row. */
  protected async reopen(period: PeriodView): Promise<void> {
    this.periodError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      this.reopened.emit(await this.api.reopen(sheet, period.id));
    } catch (e) {
      this.periodError.set({
        id: period.id,
        message:
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Only a steward can reopen a period.'
            : 'The period could not be reopened.',
      });
    }
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
