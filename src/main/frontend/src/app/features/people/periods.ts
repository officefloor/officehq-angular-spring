import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Period, PeriodsService } from './periods.service';
import { fiscalYearOf } from './settings.service';
import { FigurePipe } from '../../core/figure.pipe';

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const YEAR = /^\d{4}$/;

// The periods of the sheet, by from-date, each with the fiscal year its from-date falls in, the due-total of the counting claims dated in
// it (fixed once it is closed or soft-closed), its cap on each person's open claims, whether it is closed (soft for a soft close), the buttons
// that close, soft-close and reopen it, and the
// form that adds a period. Monthly periods are generated between two dates, or for a fiscal year.
@Component({
  selector: 'app-periods',
  imports: [FigurePipe],
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
        <label for="period-form-cap">Open claims cap</label>
        <input
          id="period-form-cap"
          data-testid="period-form-cap"
          inputmode="numeric"
          placeholder="none"
          [value]="cap()"
          (input)="cap.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="period-form-error">{{ (message) | figure }}</p>
        }
      </form>
      <form (submit)="generate($event)" aria-label="Generate monthly periods" data-testid="period-generate-form">
        <label for="period-generate-from">Months from</label>
        <input
          id="period-generate-from"
          type="date"
          data-testid="period-generate-from"
          [value]="generateFrom()"
          (input)="generateFrom.set(value($event)); generateError.set(null)"
        />
        <label for="period-generate-to">To</label>
        <input
          id="period-generate-to"
          type="date"
          data-testid="period-generate-to"
          [value]="generateTo()"
          (input)="generateTo.set(value($event)); generateError.set(null)"
        />
        <button type="submit" data-testid="period-generate">Generate monthly periods</button>
        <span aria-live="polite">Made: <span data-testid="period-generated">{{ (generated() ?? '') | figure }}</span></span>
        <label for="period-generate-fy-year">Fiscal year</label>
        <input
          id="period-generate-fy-year"
          data-testid="period-generate-fy-year"
          inputmode="numeric"
          placeholder="YYYY"
          [value]="generateFyYear()"
          (input)="generateFyYear.set(value($event)); generateError.set(null)"
        />
        <button type="button" data-testid="period-generate-fy" (click)="generateFiscalYear()">
          Generate fiscal year
        </button>
        @if (generateError(); as message) {
          <p role="alert" class="error" data-testid="period-generate-error">{{ (message) | figure }}</p>
        }
      </form>
      <form (submit)="importOpenings($event)" aria-label="Paste opening balances" data-testid="opening-import-form">
        <label for="opening-import">Opening balances, one per line: name,period,amount</label>
        <textarea
          id="opening-import"
          data-testid="opening-import"
          rows="3"
          [value]="openingText()"
          (input)="openingText.set(value($event)); openingError.set(null)"
        ></textarea>
        <button type="submit" data-testid="opening-import-submit">Paste opening balances</button>
        <span aria-live="polite">Went through: <span data-testid="opening-import-done">{{ (openingsDone() ?? '') | figure }}</span></span>
        @if (openingError(); as message) {
          <p role="alert" class="error" data-testid="opening-error">{{ (message) | figure }}</p>
        }
      </form>
      @if (sorted().length > 0) {
        <table data-testid="period-list">
          <thead>
            <tr>
              <th scope="col">Period</th>
              <th scope="col">From</th>
              <th scope="col">To</th>
              <th scope="col">Fiscal year</th>
              <th scope="col">Open claims cap</th>
              <th scope="col">Due</th>
              <th scope="col">Closed</th>
              <th scope="col"><span class="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (p of sorted(); track p.id) {
              <tr [attr.data-testid]="'period-row-' + p.name">
                <td data-testid="period-name">{{ (p.name) | figure }}</td>
                <td data-testid="period-from">{{ (p.from) | figure }}</td>
                <td data-testid="period-to">{{ (p.to) | figure }}</td>
                <td data-testid="period-fy">{{ (fiscalYear(p)) | figure }}</td>
                <td data-testid="period-cap">{{ (p.cap ?? '') | figure }}</td>
                <td data-testid="period-due-total">{{ (dueTotals()[p.name] ?? '0.00') | figure }}</td>
                <td data-testid="period-closed">{{ (p.closed ? (p.soft ? 'soft' : 'yes') : 'no') | figure }}</td>
                <td>
                  @if (!p.closed || p.soft) {
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
                    data-testid="period-soft-close"
                    [attr.aria-label]="'Soft-close period ' + p.name"
                    (click)="softClose(p)"
                  >
                    Soft-close
                  </button>
                  <button
                    type="button"
                    data-testid="period-reopen"
                    [attr.aria-label]="'Reopen period ' + p.name"
                    (click)="reopen(p)"
                  >
                    Reopen
                  </button>
                  @if (rowErrors()[p.id]; as message) {
                    <p role="alert" class="error" data-testid="period-error">{{ (message) | figure }}</p>
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
  /** The month (1 to 12) the sheet's fiscal year starts in. */
  readonly fiscalStartMonth = input.required<number>();
  /** A period was added. */
  readonly added = output<Period>();
  /** A period was closed. */
  readonly closed = output<Period>();
  /** A period was reopened. */
  readonly reopened = output<Period>();
  /** The sheet's periods were re-read: opening balances were pasted. */
  readonly reloaded = output<Period[]>();

  protected readonly sorted = computed(() => [...this.periods()].sort((a, b) => a.from.localeCompare(b.from)));
  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly cap = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly generateFrom = signal('');
  protected readonly generateTo = signal('');
  protected readonly generateFyYear = signal('');
  /** How many periods the last generate made; null while one runs or before the first. */
  protected readonly generated = signal<number | null>(null);
  protected readonly generateError = signal<string | null>(null);
  protected readonly openingText = signal('');
  /** How many lines the last opening balance paste put through; null while one runs or before the first. */
  protected readonly openingsDone = signal<number | null>(null);
  protected readonly openingError = signal<string | null>(null);
  /** An opening balance paste is waiting for its answer; another meanwhile is ignored. */
  private importingOpenings = false;
  /** The error of the last refused action on each period, by period id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private submitting = false;
  /** A generate is waiting for its answer; another meanwhile is ignored. */
  private generating = false;

  /** The fiscal year the period's from-date falls in. */
  protected fiscalYear(period: Period): string {
    return fiscalYearOf(period.from, this.fiscalStartMonth());
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Close the period, fixing its due-total; closing one already closed, or a close the
   * sheet's permission matrix does not allow the user's role, is refused with an error. */
  protected async close(period: Period): Promise<void> {
    this.rowErrors.update(({ [period.id]: _, ...rest }) => rest);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const closed = await this.service.close(sheet, period.id);
      if (this.session.sheetId() === sheet) this.closed.emit(closed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [period.id]:
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Your role may not close a period on this sheet.'
            : 'That period could not be closed.',
      }));
    }
  }

  /** Soft-close the period, fixing its due-total while claims dated in it can still change; one
   * already closed or soft-closed, or a close the permission matrix does not allow, is refused. */
  protected async softClose(period: Period): Promise<void> {
    this.rowErrors.update(({ [period.id]: _, ...rest }) => rest);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const closed = await this.service.softClose(sheet, period.id);
      if (this.session.sheetId() === sheet) this.closed.emit(closed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [period.id]:
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Your role may not close a period on this sheet.'
            : 'That period could not be soft-closed.',
      }));
    }
  }

  /** Reopen the period, making its due-total live again; refused with an error unless the
   * permission matrix allows the user's role (a steward's by default) and the period is closed. */
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
            ? 'Your role may not reopen a period on this sheet.'
            : 'That period could not be reopened.',
      }));
    }
  }

  /** Add an opening balance per pasted line of name,period,amount; when any line is refused none is
   *  added. Shows how many lines went through; the old count is gone while it runs. */
  protected async importOpenings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.importingOpenings) return;
    this.openingError.set(null);
    this.openingsDone.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (!this.openingText().trim()) {
      this.openingError.set('Paste a line of name,period,amount.');
      return;
    }
    this.importingOpenings = true;
    try {
      const result = await this.service.importOpenings(sheet, this.openingText());
      const periods = await this.service.list(sheet);
      if (this.session.sheetId() !== sheet) return;
      this.reloaded.emit(periods);
      this.openingText.set('');
      this.openingsDone.set(result.set);
    } catch {
      if (this.session.sheetId() !== sheet) return;
      this.openingError.set('Those opening balances could not be added: each line needs a person on the sheet by name or alias, a period on the sheet, and an amount of 0.00 or more with two decimals.');
    } finally {
      this.importingOpenings = false;
    }
  }

  /** Generate one period per month between the dates, showing how many were made; a to-date
   * before the from-date is refused with an error. */
  protected async generate(event: Event): Promise<void> {
    event.preventDefault();
    if (this.generating) return;
    this.generateError.set(null);
    this.generated.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const from = this.generateFrom().trim();
    const to = this.generateTo().trim();
    if ((from && !DATE.test(from)) || (to && !DATE.test(to))) {
      this.generateError.set('Type a from and a to date as YYYY-MM-DD.');
      return;
    }
    if (from && to && to < from) {
      this.generateError.set('The to-date must not be before the from-date.');
      return;
    }
    this.generating = true;
    try {
      const made = await this.service.generate(sheet, from || null, to || null);
      if (this.session.sheetId() !== sheet) return;
      for (const period of made) this.added.emit(period);
      this.generated.set(made.length);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.generateError.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'The to-date must not be before the from-date.'
          : 'Those periods could not be generated.',
      );
    } finally {
      this.generating = false;
    }
  }

  /** Generate the twelve monthly periods of the fiscal year typed, as the generator does, showing how
   *  many were made; a year not typed as four digits is refused with an error. */
  protected async generateFiscalYear(): Promise<void> {
    if (this.generating) return;
    this.generateError.set(null);
    this.generated.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const year = this.generateFyYear().trim();
    if (!YEAR.test(year)) {
      this.generateError.set('Type the fiscal year as four digits, YYYY.');
      return;
    }
    this.generating = true;
    try {
      const made = await this.service.generateFiscalYear(sheet, year);
      if (this.session.sheetId() !== sheet) return;
      for (const period of made) this.added.emit(period);
      this.generated.set(made.length);
    } catch {
      if (this.session.sheetId() !== sheet) return;
      this.generateError.set('That fiscal year\'s periods could not be generated.');
    } finally {
      this.generating = false;
    }
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const fields = { name: this.name().trim(), from: this.from().trim(), to: this.to().trim(), cap: this.cap().trim() };
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
    if (fields.cap !== '' && !/^\d{1,9}$/.test(fields.cap)) {
      this.error.set('Type the open claims cap as a whole number, or leave it blank for none.');
      return;
    }
    if (fields.cap !== '' && Number(fields.cap) < 1) {
      this.error.set('The open claims cap must be at least 1.');
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
      this.cap.set('');
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
