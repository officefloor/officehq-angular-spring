import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, computed, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { ClaimLogLine, ClaimStatus, statusAtEndOf } from '../claims/claims';
import { sumSkippingNa } from '../rates/rates';
import { SettingsStore } from '../settings/settings';

export interface PeriodView {
  id: number;
  name: string;
  // Both inclusive (yyyy-mm-dd).
  from: string;
  to: string;
  // The most OPEN or PENDING claims a person may have in it (those due by a date in it); null for no cap.
  cap: number | null;
  // A closed period keeps the due-total fixed when it was closed; no claim due by a date in it can change.
  closed: boolean;
  // A soft-closed period keeps its due-total fixed the same way, but claims due by a date in it can still change.
  softClosed: boolean;
  closedTotal: string | null;
  // Each person's due-total fixed when it was closed or soft-closed, by person id; empty while it is open.
  closedPersonTotals: Record<string, string>;
}

/**
 * A claim the live rules do not leave out (deferred or held): its person, its due-by (which decides its period), the
 * figure it totals now (null for n/a), and its status and log, which decide whether it counted at the end of a period.
 */
export interface DatedFigure {
  personId: number;
  dueBy: string;
  figure: string | null;
  status: ClaimStatus;
  log: ClaimLogLine[];
}

/** Whether the claim counted at the end of the day: its status then is open, pending or settled. */
function countedAtEndOf(f: DatedFigure, day: string): boolean {
  const then = statusAtEndOf(f, day);
  return then === 'OPEN' || then === 'PENDING' || then === 'SETTLED';
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The fiscal year the date falls in, named FY<yyyy> by the calendar year its first day falls in: fiscal years start on
 * the first of the start month (1 to 12).
 */
export function fiscalYearOf(date: string, startMonth: number): string {
  const year = Number(date.slice(0, 4));
  const month = Number(date.slice(5, 7));
  return `FY${month >= startMonth ? year : year - 1}`;
}

/** The period whose dates contain the date; undefined when none does. */
export function periodOn(periods: readonly PeriodView[], date: string): PeriodView | undefined {
  return periods.find((p) => p.from <= date && date <= p.to);
}

/** Whether a closed period contains the date. */
export function inClosedPeriod(periods: readonly PeriodView[], date: string): boolean {
  return periodOn(periods, date)?.closed ?? false;
}

/**
 * The person's due-total for each period, by period id: a closed one's figure fixed for them when it was closed
 * (0.00 for a person added since), else the figures now of their claims due by a date in it that counted at the end of its
 * last day, skipping n/a.
 */
export function personPeriodDueTotals(
  periods: readonly PeriodView[],
  personId: number,
  figures: readonly DatedFigure[],
): Map<number, string> {
  const by = new Map<number, (string | null)[]>();
  for (const p of periods) by.set(p.id, []);
  for (const f of figures) {
    if (f.personId !== personId) continue;
    const p = periodOn(periods, f.dueBy);
    if (p && countedAtEndOf(f, p.to)) by.get(p.id)?.push(f.figure);
  }
  return new Map(
    periods.map((p) => [
      p.id,
      p.closed || p.softClosed ? (p.closedPersonTotals[personId] ?? '0.00') : sumSkippingNa(by.get(p.id) ?? []),
    ]),
  );
}

/** Each period's due-total, by period id: the sum of the people's due-totals for it. */
export function periodDueTotals(
  periods: readonly PeriodView[],
  figures: readonly DatedFigure[],
): Map<number, string> {
  // everyone with a figure for some period: a counted claim, or one fixed when a period closed
  const people = new Set(figures.map((f) => f.personId));
  for (const p of periods) for (const id of Object.keys(p.closedPersonTotals)) people.add(Number(id));
  const each = [...people].map((id) => personPeriodDueTotals(periods, id, figures));
  return new Map(periods.map((p) => [p.id, sumSkippingNa(each.map((m) => m.get(p.id) ?? null))]));
}

// The periods of the active sheet, by from-date.
@Injectable({ providedIn: 'root' })
export class PeriodStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly list = signal<readonly PeriodView[]>([]);

  /** Re-read the sheet's periods from the store. */
  async load(sheet: string): Promise<void> {
    this.list.set([]);
    const list = await firstValueFrom(this.http.get<PeriodView[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods`));
    if (this.session.sheetId() === sheet) this.list.set(list);
  }

  async add(sheet: string, name: string, from: string, to: string, cap: number | null): Promise<void> {
    const p = await firstValueFrom(
      this.http.post<PeriodView>(`/api/sheets/${encodeURIComponent(sheet)}/periods`, { name, from, to, cap }),
    );
    this.put(sheet, p);
  }

  /** Generate one period per month from the from-date's month to the to-date's; answers how many were made. */
  async generate(sheet: string, from: string, to: string): Promise<number> {
    const made = await firstValueFrom(
      this.http.post<PeriodView[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods/generate`, { from, to }),
    );
    for (const p of made) this.put(sheet, p);
    return made.length;
  }

  /** Generate the fiscal year FY<year>'s twelve monthly periods as generate does; answers how many were made. */
  async generateFiscalYear(sheet: string, year: number): Promise<number> {
    const made = await firstValueFrom(
      this.http.post<PeriodView[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods/generate-fy`, { year }),
    );
    for (const p of made) this.put(sheet, p);
    return made.length;
  }

  async close(sheet: string, id: number): Promise<void> {
    const p = await firstValueFrom(
      this.http.post<PeriodView>(`/api/sheets/${encodeURIComponent(sheet)}/periods/${id}/close`, null),
    );
    this.put(sheet, p);
  }

  async softClose(sheet: string, id: number): Promise<void> {
    const p = await firstValueFrom(
      this.http.post<PeriodView>(`/api/sheets/${encodeURIComponent(sheet)}/periods/${id}/soft-close`, null),
    );
    this.put(sheet, p);
  }

  async reopen(sheet: string, id: number): Promise<void> {
    const p = await firstValueFrom(
      this.http.post<PeriodView>(`/api/sheets/${encodeURIComponent(sheet)}/periods/${id}/reopen`, null),
    );
    this.put(sheet, p);
  }

  private put(sheet: string, p: PeriodView): void {
    if (this.session.sheetId() === sheet) {
      this.list.update((l) => [...l.filter((x) => x.id !== p.id), p].sort((a, b) => a.from.localeCompare(b.from)));
    }
  }
}

// The sheet's periods, each with its due-total, and the form that adds one.
@Component({
  selector: 'app-periods',
  template: `
    <section aria-label="Periods">
      <h2>Periods</h2>
      <form (submit)="submit($event)" aria-label="Add period">
        <label for="period-name">Name</label>
        <input
          id="period-name"
          data-testid="period-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="period-from">From</label>
        <input
          id="period-from"
          type="date"
          data-testid="period-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="period-to">To</label>
        <input
          id="period-to"
          type="date"
          data-testid="period-form-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <label for="period-cap">Open claims per person (blank for no cap)</label>
        <input
          id="period-cap"
          data-testid="period-form-cap"
          inputmode="numeric"
          [value]="cap()"
          (input)="cap.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error(); as err) {
          <p role="alert" data-testid="period-form-error">{{ err }}</p>
        }
      </form>

      <form (submit)="generate($event)" aria-label="Generate monthly periods">
        <label for="period-generate-from">From</label>
        <input
          id="period-generate-from"
          type="date"
          data-testid="period-generate-from"
          [value]="genFrom()"
          (input)="genFrom.set(value($event)); genError.set(null)"
        />
        <label for="period-generate-to">To</label>
        <input
          id="period-generate-to"
          type="date"
          data-testid="period-generate-to"
          [value]="genTo()"
          (input)="genTo.set(value($event)); genError.set(null)"
        />
        <button type="submit" data-testid="period-generate">Generate monthly periods</button>
        @if (generated() !== null) {
          <p role="status">Made: <strong data-testid="period-generated">{{ generated() }}</strong></p>
        }
        @if (genError(); as err) {
          <p role="alert" data-testid="period-generate-error">{{ err }}</p>
        }
      </form>

      <form (submit)="generateFiscalYear($event)" aria-label="Generate a fiscal year's monthly periods">
        <label for="period-generate-fy-year">Fiscal year (yyyy)</label>
        <input
          id="period-generate-fy-year"
          data-testid="period-generate-fy-year"
          inputmode="numeric"
          [value]="fyYear()"
          (input)="fyYear.set(value($event)); genError.set(null)"
        />
        <button type="submit" data-testid="period-generate-fy">Generate fiscal year</button>
      </form>

      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Cap</th><th scope="col">Due</th><th scope="col">Closed</th><th scope="col">Fiscal year</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of store.list(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-cap">{{ p.cap ?? '' }}</td>
              <td data-testid="period-due-total">{{ dueBy().get(p.id) }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</td>
              <td data-testid="period-fy">{{ fiscalYear(p) }}</td>
              <td>
                <button type="button" data-testid="period-close" (click)="close(p)">
                  Close<span class="visually-hidden"> period {{ p.name }}</span>
                </button>
                <button type="button" data-testid="period-soft-close" (click)="softClose(p)">
                  Soft-close<span class="visually-hidden"> period {{ p.name }}</span>
                </button>
                <button type="button" data-testid="period-reopen" (click)="reopen(p)">
                  Reopen<span class="visually-hidden"> period {{ p.name }}</span>
                </button>
                @if (rowErrors()[p.id]; as err) {
                  <p role="alert" data-testid="period-error">{{ err }}</p>
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
  protected readonly store = inject(PeriodStore);
  private readonly settings = inject(SettingsStore);

  // The claims of the sheet the live rules do not leave out: each one's date, figure and log.
  readonly figures = input<readonly DatedFigure[]>([]);

  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly cap = signal('');
  protected readonly error = signal<string | null>(null);
  // Each period's refused action, by period id.
  protected readonly rowErrors = signal<Record<number, string>>({});
  protected readonly genFrom = signal('');
  protected readonly genTo = signal('');
  protected readonly genError = signal<string | null>(null);
  protected readonly fyYear = signal('');
  // How many periods the last generate made; null while one runs or before any.
  protected readonly generated = signal<number | null>(null);
  private generating = false;
  // A submit in flight: a second submit before it answers is ignored, so it stores one period.
  private submitting = false;

  // Each period's due-total: a closed or soft-closed one's fixed total, else the figures of the claims due by a date in it that counted at
  // the end of its last day, skipping n/a.
  protected readonly dueBy = computed(() => periodDueTotals(this.store.list(), this.figures()));

  /** The fiscal year the period's from-date falls in. */
  protected fiscalYear(p: PeriodView): string {
    return fiscalYearOf(p.from, this.settings.fiscalStartMonth());
  }

  protected async close(p: PeriodView): Promise<void> {
    this.rowErrors.update(({ [p.id]: _, ...rest }) => rest);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (p.closed) {
      this.setRowError(p.id, 'That period is already closed.');
      return;
    }
    try {
      await this.store.close(sheet, p.id);
    } catch (err) {
      this.setRowError(
        p.id,
        err instanceof HttpErrorResponse && err.status === 403
          ? 'Your role may not close a period on this sheet.'
          : 'Could not close that period.',
      );
    }
  }

  /** Soft-closes the period: its figures are fixed now, but claims due by a date in it can still change. */
  protected async softClose(p: PeriodView): Promise<void> {
    this.rowErrors.update(({ [p.id]: _, ...rest }) => rest);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (p.closed || p.softClosed) {
      this.setRowError(p.id, 'That period is already closed.');
      return;
    }
    try {
      await this.store.softClose(sheet, p.id);
    } catch (err) {
      this.setRowError(
        p.id,
        err instanceof HttpErrorResponse && err.status === 403
          ? 'Your role may not close a period on this sheet.'
          : 'Could not soft-close that period.',
      );
    }
  }

  protected async reopen(p: PeriodView): Promise<void> {
    this.rowErrors.update(({ [p.id]: _, ...rest }) => rest);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (!p.closed && !p.softClosed) {
      this.setRowError(p.id, 'That period is not closed.');
      return;
    }
    try {
      await this.store.reopen(sheet, p.id);
    } catch (err) {
      this.setRowError(
        p.id,
        err instanceof HttpErrorResponse && err.status === 403
          ? 'Your role may not reopen a period on this sheet.'
          : 'Could not reopen that period.',
      );
    }
  }

  private setRowError(id: number, err: string): void {
    this.rowErrors.update((e) => ({ ...e, [id]: err }));
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  /** Generates a period per month between the dates, leaving out months already named or overlapped; shows how many were made. */
  protected async generate(e: Event): Promise<void> {
    e.preventDefault();
    if (this.generating) return;
    this.generated.set(null);
    this.genError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const from = this.genFrom().trim() || (this.session.sheetToday() ?? '');
    const to = this.genTo().trim();
    if (!DATE.test(from) || !DATE.test(to)) {
      this.genError.set('Enter a from and a to date as yyyy-mm-dd.');
      return;
    }
    if (to < from) {
      this.genError.set('The to-date cannot be before the from-date.');
      return;
    }
    this.generating = true;
    try {
      const made = await this.store.generate(sheet, from, to);
      if (this.session.sheetId() !== sheet) return;
      this.generated.set(made);
    } catch {
      this.genError.set('Could not generate those periods.');
    } finally {
      this.generating = false;
    }
  }

  /** Generates the fiscal year's twelve monthly periods as generate does; shows how many were made. */
  protected async generateFiscalYear(e: Event): Promise<void> {
    e.preventDefault();
    if (this.generating) return;
    this.generated.set(null);
    this.genError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const year = this.fyYear().trim();
    if (!/^\d{4}$/.test(year) || year === '0000' || year === '9999') {
      this.genError.set('Enter a fiscal year as yyyy.');
      return;
    }
    this.generating = true;
    try {
      const made = await this.store.generateFiscalYear(sheet, Number(year));
      if (this.session.sheetId() !== sheet) return;
      this.generated.set(made);
    } catch {
      this.genError.set('Could not generate that fiscal year\'s periods.');
    } finally {
      this.generating = false;
    }
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.name().trim();
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    const from = this.from().trim() || (this.session.sheetToday() ?? '');
    const to = this.to().trim();
    if (!DATE.test(from) || !DATE.test(to)) {
      this.error.set('Enter a from and a to date as yyyy-mm-dd.');
      return;
    }
    if (to < from) {
      this.error.set('A period cannot end before it starts.');
      return;
    }
    const capText = this.cap().trim();
    if (capText && (!/^\d+$/.test(capText) || Number(capText) < 1 || Number(capText) > 1000000)) {
      this.error.set('Enter the cap as a whole number of at least 1, or leave it blank for none.');
      return;
    }
    const cap = capText ? Number(capText) : null;
    if (this.store.list().some((p) => p.name === name)) {
      this.error.set('That period name is already in use on this sheet.');
      return;
    }
    if (this.store.list().some((p) => p.from <= to && from <= p.to)) {
      this.error.set('That period overlaps another.');
      return;
    }
    this.submitting = true;
    try {
      await this.store.add(sheet, name, from, to, cap);
      this.name.set('');
      this.from.set('');
      this.to.set('');
      this.cap.set('');
    } catch (err) {
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 409
          ? 'That period name is in use, or its dates overlap another period.'
          : 'Could not add that period.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
