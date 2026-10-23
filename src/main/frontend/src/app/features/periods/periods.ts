import { HttpErrorResponse } from '@angular/common/http';
import { FigurePipe } from '../../core/locale';
import { Component, inject, input, output, signal } from '@angular/core';
import { CALENDAR_YEAR, FISCAL_YEAR, PERIOD_CAP, PERIOD_DATE, PeriodView, PeriodsApi, fiscalYearOf } from '../../core/periods';
import { Session } from '../../core/session';

// The sheet's periods: listed by from-date with the due-total of the counting claims dated in each,
// and one form to add a period (a unique name and inclusive from and to dates, overlapping no other).
// Its due-total is the sum of the people's figures for it. A period can be closed: each person's figure
// is then the one fixed at closing. It can instead be soft-closed: the figures are fixed the same way
// but its claims can still change; a soft-closed period can then be closed or reopened. A permitted role (a steward by default) can reopen a
// closed period, making its due-total live again. A second form generates a period per calendar month
// over a range of dates, named yyyy-mm, leaving out months whose name is taken or that overlap a period;
// a third does the same for the twelve months of a fiscal year, and a fourth for the four calendar
// quarters of a year, named yyyy-Q1 to yyyy-Q4. Each period shows the fiscal year its
// from-date falls in. A period may cap the OPEN or PENDING claims due by in it each person may have.
// A steward can lock a period, so it cannot be reopened, and unlock it again. A steward can roll a
// fiscal year over: the next one's periods are generated and each person's open figure at the old
// year's end is added to their carried figure for the new year's first period.
@Component({
  selector: 'app-periods',
  imports: [FigurePipe],
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
        <label for="period-form-cap">Open claims cap (blank for none)</label>
        <input
          id="period-form-cap"
          inputmode="numeric"
          data-testid="period-form-cap"
          [value]="cap()"
          (input)="cap.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error(); as message) {
          <p role="alert" data-testid="period-form-error">{{ message }}</p>
        }
      </form>
      <form (submit)="generate($event)" aria-label="Generate monthly periods">
        <label for="period-generate-from">From</label>
        <input
          id="period-generate-from"
          type="date"
          data-testid="period-generate-from"
          [value]="generateFrom()"
          (input)="generateFrom.set(value($event)); generateReset()"
        />
        <label for="period-generate-to">To</label>
        <input
          id="period-generate-to"
          type="date"
          data-testid="period-generate-to"
          [value]="generateTo()"
          (input)="generateTo.set(value($event)); generateReset()"
        />
        <button type="submit" data-testid="period-generate">Generate monthly periods</button>
        @if (generated() !== null) {
          <p role="status">Periods made: <span data-testid="period-generated">{{ generated() }}</span></p>
        }
        @if (generateError(); as message) {
          <p role="alert" data-testid="period-generate-error">{{ message }}</p>
        }
      </form>
      <form (submit)="generateFiscalYear($event)" aria-label="Generate a fiscal year's monthly periods">
        <label for="period-generate-fy-year">Fiscal year (the calendar year it starts in, yyyy)</label>
        <input
          id="period-generate-fy-year"
          data-testid="period-generate-fy-year"
          inputmode="numeric"
          [value]="fiscalYear()"
          (input)="fiscalYear.set(value($event)); generateReset()"
        />
        <button type="submit" data-testid="period-generate-fy">Generate fiscal year</button>
      </form>
      <form (submit)="rollover($event)" aria-label="Roll a fiscal year over">
        <label for="rollover-year">Fiscal year to roll over (the calendar year it starts in, yyyy)</label>
        <input
          id="rollover-year"
          data-testid="rollover-year"
          inputmode="numeric"
          [value]="rolloverYear()"
          (input)="rolloverYear.set(value($event)); rolloverReset()"
        />
        <button type="submit" data-testid="rollover">Roll the year over</button>
        <button type="button" data-testid="rollover-undo" (click)="undoRollover()">Undo the last rollover</button>
        @if (rolloverMade() !== null) {
          <p role="status">Periods made: <span data-testid="rollover-made">{{ rolloverMade() }}</span></p>
        }
        @if (rolloverError(); as message) {
          <p role="alert" data-testid="rollover-error">{{ message }}</p>
        }
      </form>
      <form (submit)="generateQuarters($event)" aria-label="Generate a year's quarterly periods">
        <label for="period-generate-quarters-year">Calendar year (yyyy)</label>
        <input
          id="period-generate-quarters-year"
          data-testid="period-generate-quarters-year"
          inputmode="numeric"
          [value]="quartersYear()"
          (input)="quartersYear.set(value($event)); generateReset()"
        />
        <button type="submit" data-testid="period-generate-quarters">Generate quarters</button>
      </form>
      <table>
        <thead>
          <tr>
            <th scope="col">Period</th>
            <th scope="col">From</th>
            <th scope="col">To</th>
            <th scope="col">Due</th>
            <th scope="col">Closed</th>
            <th scope="col">Fiscal year</th>
            <th scope="col">Cap</th>
            <th scope="col">Locked</th>
            <th scope="col">Claims</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ (dueTotals().get(p.id) ?? '0.00') | figure }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</td>
              <td data-testid="period-fy">{{ fiscalYearOf(p.from, session.fiscalStartMonth()) }}</td>
              <td data-testid="period-cap">{{ (p.cap ?? '') | figure }}</td>
              <td data-testid="period-locked">{{ p.locked ? 'yes' : 'no' }}</td>
              <td data-testid="period-claims-count">{{ claimCounts().get(p.id) ?? 0 }}</td>
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
                  <button
                    type="button"
                    data-testid="period-soft-close"
                    [attr.aria-label]="'Soft-close ' + p.name"
                    (click)="softClose(p)"
                  >
                    Soft-close
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
                <button
                  type="button"
                  data-testid="period-lock"
                  [attr.aria-label]="'Lock ' + p.name"
                  (click)="lock(p, true)"
                >
                  Lock
                </button>
                <button
                  type="button"
                  data-testid="period-unlock"
                  [attr.aria-label]="'Unlock ' + p.name"
                  (click)="lock(p, false)"
                >
                  Unlock
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
  protected readonly session = inject(Session);
  protected readonly fiscalYearOf = fiscalYearOf;
  private readonly api = inject(PeriodsApi);

  /** The sheet's periods by from-date. */
  readonly periods = input.required<PeriodView[]>();
  /** Each period's due-total (the sum of the people's figures for it), by period id, with two decimals. */
  readonly dueTotals = input.required<Map<number, string>>();
  /** Each period's count of the claims due by in it, VOID and DELETED claims left out, by period id. */
  readonly claimCounts = input.required<Map<number, number>>();
  /** A period added through the form. */
  readonly saved = output<PeriodView>();
  /** A period closed through its row. */
  readonly closed = output<PeriodView>();
  /** A period reopened through its row. */
  readonly reopened = output<PeriodView>();
  /** A period locked or unlocked through its row. */
  readonly locked = output<PeriodView>();
  /** A fiscal year was rolled over: periods and opening balances changed. */
  readonly rolledOver = output<void>();

  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly cap = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly generateFrom = signal('');
  protected readonly generateTo = signal('');
  protected readonly fiscalYear = signal('');
  protected readonly quartersYear = signal('');
  /** How many periods the last generate made; null before one, or once its range is changed. */
  protected readonly generated = signal<number | null>(null);
  protected readonly generateError = signal<string | null>(null);
  protected readonly rolloverYear = signal('');
  /** How many periods the last rollover made; null before one, or once its year is changed. */
  protected readonly rolloverMade = signal<number | null>(null);
  protected readonly rolloverError = signal<string | null>(null);
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
    } catch (e) {
      this.periodError.set({
        id: period.id,
        message:
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Your role is not allowed to close a period.'
            : 'The period could not be closed.',
      });
    }
  }

  /** Soft-close a period; a refusal is shown on its row. */
  protected async softClose(period: PeriodView): Promise<void> {
    this.periodError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      this.closed.emit(await this.api.softClose(sheet, period.id));
    } catch (e) {
      this.periodError.set({
        id: period.id,
        message:
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Your role is not allowed to close a period.'
            : 'The period could not be soft-closed.',
      });
    }
  }

  /** Reopen a period; a refusal (a role not allowed to, or not closed) is shown on its row. */
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
            ? 'Your role is not allowed to reopen a period.'
            : 'The period could not be reopened.',
      });
    }
  }

  /** Lock or unlock a period; a refusal (not a steward, or already so) is shown on its row. */
  protected async lock(period: PeriodView, lock: boolean): Promise<void> {
    this.periodError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      this.locked.emit(await (lock ? this.api.lock(sheet, period.id) : this.api.unlock(sheet, period.id)));
    } catch (e) {
      this.periodError.set({
        id: period.id,
        message:
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Only a steward may lock or unlock a period.'
            : lock
              ? 'The period could not be locked.'
              : 'The period could not be unlocked.',
      });
    }
  }

  protected generateReset(): void {
    this.generated.set(null);
    this.generateError.set(null);
  }

  /** Generate a period per month in the range; how many were made is shown, a refusal as an error. */
  protected async generate(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.generateReset();
    const sheet = this.session.sheet();
    if (!sheet) return;
    const from = this.generateFrom().trim();
    const to = this.generateTo().trim();
    if (!PERIOD_DATE.test(from) || !PERIOD_DATE.test(to)) {
      this.generateError.set('Pick a from and a to date to generate periods for.');
      return;
    }
    if (to < from) {
      this.generateError.set('The to date cannot be before the from date.');
      return;
    }
    this.busy = true;
    try {
      const made = await this.api.generate(sheet, from, to);
      made.forEach((period) => this.saved.emit(period));
      this.generated.set(made.length);
    } catch {
      this.generateError.set('The periods could not be generated.');
    } finally {
      this.busy = false;
    }
  }

  /** Generate the fiscal year's monthly periods; how many were made is shown, a refusal as an error. */
  protected async generateFiscalYear(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.generateReset();
    const sheet = this.session.sheet();
    if (!sheet) return;
    const year = this.fiscalYear().trim();
    if (!FISCAL_YEAR.test(year)) {
      this.generateError.set('Type the fiscal year as the four-digit calendar year it starts in.');
      return;
    }
    this.busy = true;
    try {
      const made = await this.api.generateFiscalYear(sheet, year);
      made.forEach((period) => this.saved.emit(period));
      this.generated.set(made.length);
    } catch {
      this.generateError.set('The fiscal year\'s periods could not be generated.');
    } finally {
      this.busy = false;
    }
  }

  protected rolloverReset(): void {
    this.rolloverMade.set(null);
    this.rolloverError.set(null);
  }

  /** Roll the fiscal year over; how many periods were made is shown, a refusal as an error. */
  protected async rollover(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.rolloverReset();
    const sheet = this.session.sheet();
    if (!sheet) return;
    const year = this.rolloverYear().trim();
    if (!FISCAL_YEAR.test(year)) {
      this.rolloverError.set('Type the fiscal year as the four-digit calendar year it starts in.');
      return;
    }
    this.busy = true;
    try {
      const made = await this.api.rollover(sheet, year);
      if (this.session.sheet() !== sheet) return;
      this.rolloverMade.set(made.length);
      this.rolledOver.emit();
    } catch (e) {
      this.rolloverError.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward may roll the year over.'
          : e instanceof HttpErrorResponse && e.status === 409
            ? 'That fiscal year has no periods to roll over.'
            : 'The year could not be rolled over.',
      );
    } finally {
      this.busy = false;
    }
  }

  /** Undo the last rollover; the periods are read again, a refusal shown as the rollover's error. */
  protected async undoRollover(): Promise<void> {
    if (this.busy) return;
    this.rolloverReset();
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.busy = true;
    try {
      await this.api.undoRollover(sheet);
      if (this.session.sheet() !== sheet) return;
      this.rolledOver.emit();
    } catch (e) {
      this.rolloverError.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only the steward who made the rollover may undo it.'
          : e instanceof HttpErrorResponse && e.status === 409
            ? 'The last rollover cannot be undone: there is none, it was made on another day, or a claim is in its periods.'
            : 'The rollover could not be undone.',
      );
    } finally {
      this.busy = false;
    }
  }

  /** Generate the year's four calendar quarters; how many were made is shown, a refusal as an error. */
  protected async generateQuarters(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.generateReset();
    const sheet = this.session.sheet();
    if (!sheet) return;
    const year = this.quartersYear().trim();
    if (!CALENDAR_YEAR.test(year)) {
      this.generateError.set('Type the year as four digits.');
      return;
    }
    this.busy = true;
    try {
      const made = await this.api.generateQuarters(sheet, year);
      made.forEach((period) => this.saved.emit(period));
      this.generated.set(made.length);
    } catch {
      this.generateError.set('The quarters could not be generated.');
    } finally {
      this.busy = false;
    }
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const cap = this.cap().trim();
    const form = { name: this.name().trim(), from: this.from().trim(), to: this.to().trim(), cap: cap ? Number(cap) : null };
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
    if (cap && !PERIOD_CAP.test(cap)) {
      this.error.set('The cap must be a whole number of at least 1, or blank for none.');
      return;
    }
    this.busy = true;
    try {
      this.saved.emit(await this.api.add(sheet, form));
      this.name.set('');
      this.from.set('');
      this.to.set('');
      this.cap.set('');
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
