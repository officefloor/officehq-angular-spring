import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Period, PeriodsService } from './periods.service';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The periods of the sheet, by from-date, each with the due-total of the counting claims dated in
// it (fixed once it is closed), whether it is closed, the buttons that close and reopen it, and the
// form that adds a period.
@Component({
  selector: 'app-periods',
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>
      <form (submit)="submit($event)" aria-label="Period" data-testid="period-form">
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
          <p role="alert" class="error" data-testid="period-form-error">{{ message }}</p>
        }
      </form>
      @if (sorted().length > 0) {
        <table data-testid="period-list">
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
            @for (p of sorted(); track p.id) {
              <tr [attr.data-testid]="'period-row-' + p.name">
                <td data-testid="period-name">{{ p.name }}</td>
                <td data-testid="period-from">{{ p.from }}</td>
                <td data-testid="period-to">{{ p.to }}</td>
                <td data-testid="period-due-total">{{ dueTotals()[p.name] ?? '0.00' }}</td>
                <td data-testid="period-closed">{{ p.closed ? 'yes' : 'no' }}</td>
                <td>
                  @if (!p.closed) {
                    <button
                      type="button"
                      data-testid="period-close"
                      [attr.aria-label]="'Close period ' + p.name"
                      (click)="close(p)"
                    >
                      Close
                    </button>
                  }
                  <button
                    type="button"
                    data-testid="period-reopen"
                    [attr.aria-label]="'Reopen period ' + p.name"
                    (click)="reopen(p)"
                  >
                    Reopen
                  </button>
                  @if (rowErrors()[p.id]; as message) {
                    <p role="alert" class="error" data-testid="period-error">{{ message }}</p>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
    table {
      border-collapse: collapse;
      margin-bottom: 1rem;
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
    th,
    td {
      text-align: left;
      padding: 0.25rem 0.75rem 0.25rem 0;
    }
  `,
})
export class Periods {
  private readonly session = inject(Session);
  private readonly service = inject(PeriodsService);

  /** The sheet's periods. */
  readonly periods = input.required<Period[]>();
  /** Each period's due-total, by name: the sum of the people's figures for it. */
  readonly dueTotals = input.required<Record<string, string>>();
  /** A period was added. */
  readonly added = output<Period>();
  /** A period was closed. */
  readonly closed = output<Period>();
  /** A period was reopened. */
  readonly reopened = output<Period>();

  protected readonly sorted = computed(() => [...this.periods()].sort((a, b) => a.from.localeCompare(b.from)));
  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  /** The error of the last refused action on each period, by period id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private submitting = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Close the period, fixing its due-total; closing one already closed is refused with an error. */
  protected async close(period: Period): Promise<void> {
    this.rowErrors.update(({ [period.id]: _, ...rest }) => rest);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const closed = await this.service.close(sheet, period.id);
      if (this.session.sheetId() === sheet) this.closed.emit(closed);
    } catch {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({ ...errors, [period.id]: 'That period could not be closed.' }));
    }
  }

  /** Reopen the period, making its due-total live again; refused with an error unless the user is a
   * steward and the period is closed. */
  protected async reopen(period: Period): Promise<void> {
    this.rowErrors.update(({ [period.id]: _, ...rest }) => rest);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const reopened = await this.service.reopen(sheet, period.id);
      if (this.session.sheetId() === sheet) this.reopened.emit(reopened);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [period.id]:
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Only a steward can reopen a period.'
            : 'That period could not be reopened.',
      }));
    }
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const fields = { name: this.name().trim(), from: this.from().trim(), to: this.to().trim() };
    if (!/^\S+$/.test(fields.name)) {
      this.error.set('Type a name for the period, without spaces.');
      return;
    }
    if (!DATE.test(fields.from) || !DATE.test(fields.to)) {
      this.error.set('Type a from and a to date as YYYY-MM-DD.');
      return;
    }
    if (fields.to < fields.from) {
      this.error.set('The to-date must not be before the from-date.');
      return;
    }
    this.submitting = true;
    try {
      const period = await this.service.add(sheet, fields);
      if (this.session.sheetId() !== sheet) return;
      this.added.emit(period);
      this.name.set('');
      this.from.set('');
      this.to.set('');
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'Another period has that name or shares some of its dates.'
          : 'That period could not be added.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
