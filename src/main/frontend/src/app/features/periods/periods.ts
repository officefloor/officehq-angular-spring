import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { FigurePipe } from '../../core/locale';
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
  /** Whether the period is closed: no claim dated in it can change. */
  closed: boolean;
  /** Whether the period is soft-closed: its figures are fixed, yet claims dated in it can still change. */
  softClosed: boolean;
  /** The due-total fixed when the period was closed or soft-closed; null while it is open. */
  closedTotal: string | null;
  /** Each person's due-total for the period by person id, fixed when it was closed or soft-closed; empty while it is open. */
  personTotals: Record<number, string>;
  /** Each person's carried figure fixed when it was closed or soft-closed, by person id; empty while it is open. */
  personCarried: Record<number, string>;
  /** Each person's opening balance for the period by person id, added to their carried figure for it. */
  personOpenings: Record<number, string>;
  /** The fiscal year (FYyyyy) the from-date falls in, by the sheet's fiscal year start month. */
  fy: string;
  /** The most OPEN or PENDING claims a person may have in the period; null for no cap. */
  cap: number | null;
}

/** The period whose dates contain the date; undefined when none does. */
export function periodOn(periods: Period[], date: string): Period | undefined {
  return periods.find((p) => p.from <= date && date <= p.to);
}

// The sheet's periods in date order, each with its due-total and whether it is closed, and the form that adds a period.
@Component({
  selector: 'app-periods',
  imports: [FigurePipe],
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
        <label for="period-form-cap">Open claims cap per person</label>
        <input
          id="period-form-cap"
          data-testid="period-form-cap"
          inputmode="numeric"
          [value]="cap()"
          (input)="cap.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error(); as message) {
          <p role="alert" data-testid="period-form-error">{{ message | fig }}</p>
        }
      </form>
      <form (submit)="generate($event)">
        <label for="period-generate-from">Generate monthly from</label>
        <input
          id="period-generate-from"
          data-testid="period-generate-from"
          type="date"
          [value]="generateFrom()"
          (input)="generateFrom.set(value($event)); generateError.set(null)"
        />
        <label for="period-generate-to">to</label>
        <input
          id="period-generate-to"
          data-testid="period-generate-to"
          type="date"
          [value]="generateTo()"
          (input)="generateTo.set(value($event)); generateError.set(null)"
        />
        <button type="submit" data-testid="period-generate">Generate periods</button>
      </form>
      <form (submit)="generateFy($event)">
        <label for="period-generate-fy-year">Generate the fiscal year</label>
        <input
          id="period-generate-fy-year"
          data-testid="period-generate-fy-year"
          inputmode="numeric"
          [value]="generateFyYear()"
          (input)="generateFyYear.set(value($event)); generateError.set(null)"
        />
        <button type="submit" data-testid="period-generate-fy">Generate fiscal year</button>
      </form>
      @if (generated() !== null) {
        <p role="status">Made <span data-testid="period-generated">{{ generated() | fig }}</span> periods.</p>
      }
      @if (generateError(); as message) {
        <p role="alert" data-testid="period-generate-error">{{ message | fig }}</p>
      }
      <form aria-label="Import opening balances" (submit)="importOpenings($event)">
        <label for="opening-import">Opening balances, one per line: name,period,amount</label>
        <textarea
          id="opening-import"
          data-testid="opening-import"
          rows="4"
          [value]="openingText()"
          (input)="openingText.set(value($event)); openingError.set(null)"
        ></textarea>
        <button type="submit" data-testid="opening-import-submit">Import opening balances</button>
        @if (openingDone() !== null) {
          Added <strong data-testid="opening-import-done">{{ openingDone() | fig }}</strong>
        }
        @if (openingError(); as message) {
          <p role="alert" data-testid="opening-error">{{ message | fig }}</p>
        }
      </form>
      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due</th><th scope="col">Closed</th><th scope="col">Fiscal year</th><th scope="col">Cap</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name | fig }}</th>
              <td data-testid="period-from">{{ p.from | fig }}</td>
              <td data-testid="period-to">{{ p.to | fig }}</td>
              <td data-testid="period-due-total">{{ p.closedTotal ?? dueTotals().get(p.name) ?? '0.00' | fig }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' | fig }}</td>
              <td data-testid="period-fy">{{ p.fy | fig }}</td>
              <td data-testid="period-cap">{{ p.cap ?? '' | fig }}</td>
              <td>
                <button
                  type="button"
                  data-testid="period-close"
                  [attr.aria-label]="'Close period ' + p.name"
                  (click)="close(p)"
                >
                  Close
                </button>
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
                @if (rowError(); as e) {
                  @if (e.id === p.id) {
                    <p role="alert" data-testid="period-error">{{ e.message | fig }}</p>
                  }
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
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's periods in date order. */
  readonly periods = input.required<Period[]>();
  /** Each period's due-total by its name: the due of the counting claims dated in it. */
  readonly dueTotals = input.required<Map<string, string>>();
  /** A period was added, generated, closed or reopened. */
  readonly saved = output<Period>();
  /** Opening balances were imported: the screen is to re-read the periods. */
  readonly openingsImported = output<void>();

  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  /** The cap typed: a whole number of 1 or more, or blank for none. */
  protected readonly cap = signal('');
  protected readonly error = signal<string | null>(null);
  /** The error of the last close or reopen refused, and the period it belongs to. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);
  protected readonly generateFrom = signal('');
  protected readonly generateTo = signal('');
  /** The fiscal year typed (yyyy) to generate the twelve monthly periods of. */
  protected readonly generateFyYear = signal('');
  /** How many periods the last generate made; null while one runs or before the first. */
  protected readonly generated = signal<number | null>(null);
  protected readonly generateError = signal<string | null>(null);
  /** The opening balances pasted, one name,period,amount per line. */
  protected readonly openingText = signal('');
  /** How many lines the last import of opening balances added; null while one runs or before the first. */
  protected readonly openingDone = signal<number | null>(null);
  protected readonly openingError = signal<string | null>(null);
  /** Whether an import of opening balances is waiting for its answer: a second meanwhile is ignored. */
  private importingOpenings = false;
  /** Whether a generate is waiting for its answer: a second meanwhile is ignored. */
  private generating = false;
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Close a period, fixing its due-total; the store refuses one already closed. */
  protected async close(period: Period): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/periods/${encodeURIComponent(period.name)}/close`;
    try {
      const saved = await firstValueFrom(this.http.post<Period>(url, null, this.session.headers()));
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch {
      this.rowError.set({ id: period.id, message: `The period ${period.name} could not be closed: it is already closed.` });
    }
  }

  /** Soft-close a period, fixing its figures while its claims can still change; the store refuses one closed or soft-closed. */
  protected async softClose(period: Period): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/periods/${encodeURIComponent(period.name)}/soft-close`;
    try {
      const saved = await firstValueFrom(this.http.post<Period>(url, null, this.session.headers()));
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? `Your role may not close the period ${period.name}.`
          : `The period ${period.name} could not be soft-closed: it is already closed or soft-closed.`;
      this.rowError.set({ id: period.id, message });
    }
  }

  /** Reopen a closed or soft-closed period, making its due-total live again; the store refuses a non-steward or an open period. */
  protected async reopen(period: Period): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/periods/${encodeURIComponent(period.name)}/reopen`;
    try {
      const saved = await firstValueFrom(this.http.post<Period>(url, null, this.session.headers()));
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? `Only a steward can reopen the period ${period.name}.`
          : `The period ${period.name} could not be reopened: it is not closed or soft-closed.`;
      this.rowError.set({ id: period.id, message });
    }
  }

  /** Generate a period per calendar month between the dates; the store leaves out months taken or overlapped. */
  protected async generate(event: Event): Promise<void> {
    event.preventDefault();
    if (this.generating) return;
    this.generated.set(null);
    this.generateError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const today = this.session.today() ?? '';
    const from = this.generateFrom().trim() || today;
    const to = this.generateTo().trim() || today;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      this.generateError.set('Pick a from and a to date to generate periods.');
      return;
    }
    if (to < from) {
      this.generateError.set('The to-date cannot be before the from-date.');
      return;
    }
    this.generating = true;
    try {
      const made = await firstValueFrom(
        this.http.post<Period[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/periods/generate`,
          { from, to },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) {
        this.generated.set(made.length);
        for (const period of made) this.saved.emit(period);
      }
    } catch {
      this.generateError.set('The periods could not be generated.');
    } finally {
      this.generating = false;
    }
  }

  /** Generate the twelve monthly periods of a fiscal year, as a generate over its dates would. */
  protected async generateFy(event: Event): Promise<void> {
    event.preventDefault();
    if (this.generating) return;
    this.generated.set(null);
    this.generateError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const year = this.generateFyYear().trim();
    if (!/^\d{4}$/.test(year)) {
      this.generateError.set('Type the fiscal year as yyyy.');
      return;
    }
    this.generating = true;
    try {
      const made = await firstValueFrom(
        this.http.post<Period[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/periods/generate-fy`,
          { year },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) {
        this.generated.set(made.length);
        for (const period of made) this.saved.emit(period);
      }
    } catch {
      this.generateError.set('The fiscal year could not be generated.');
    } finally {
      this.generating = false;
    }
  }

  /**
   * Add an opening balance per pasted line of name,period,amount to the person's carried figure for the period.
   * Refused as a whole when any line names no person or period on the sheet, or its amount is not 0.00 or more.
   */
  protected async importOpenings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.importingOpenings) return;
    this.openingError.set(null);
    this.openingDone.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = this.openingText();
    if (!text.trim()) {
      this.openingError.set('Paste at least one line of name,period,amount.');
      return;
    }
    this.importingOpenings = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ count: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/periods/openings`,
          { text },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.openingText.set('');
      this.openingDone.set(result.count);
      this.openingsImported.emit();
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const detail = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' ? ` ${e.error.message}` : '';
      this.openingError.set('The opening balances could not be imported; none were.' + detail);
    } finally {
      this.importingOpenings = false;
    }
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
    const cap = this.cap().trim();
    if (cap !== '' && !/^0*[1-9]\d{0,8}$/.test(cap)) {
      this.error.set('The cap must be a whole number of 1 or more, or blank for none.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Period>(
          `/api/sheets/${encodeURIComponent(sheet)}/periods`,
          { name, from, to, cap: cap === '' ? null : cap },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) {
        this.name.set('');
        this.from.set('');
        this.to.set('');
        this.cap.set('');
        this.saved.emit(saved);
      }
    } catch {
      this.error.set('The period could not be added: its name is taken, or it overlaps another period.');
    } finally {
      this.submitting = false;
    }
  }
}
