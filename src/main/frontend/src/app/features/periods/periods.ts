import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Period {
  id: number;
  /** Unique on the sheet. */
  name: string;
  /** The first and last dates (yyyy-mm-dd) in it, both inclusive. */
  from: string;
  to: string;
  /** Once closed, no claim due inside it can change. */
  closed: boolean;
  /** Soft-closed: its figures are fixed as for a close, but claims due inside it can still change. */
  softClosed: boolean;
  /** The due-total fixed when it was closed or soft-closed, two decimals; null while open. */
  closedDueTotal: string | null;
  /** Each person's due-total for it fixed when it was closed, two decimals, by person id; empty while open. */
  closedPersonDues: Record<string, string>;
  /** Each person's carried figure for it fixed when it was closed, two decimals, by person id; empty while open. */
  closedPersonCarried: Record<string, string>;
  /** The most OPEN or PENDING claims a person may have in it; null for no cap. */
  cap: number | null;
  /** Each person's opening balance for it, two decimals, by person id; part of their carried figure. */
  openings: Record<string, string>;
}

/** Of the sheet's periods, the one whose dates contain the date, or null. */
export function periodOn(periods: Period[], date: string): Period | null {
  return periods.find((p) => p.from <= date && date <= p.to) ?? null;
}

/**
 * The fiscal year a date (yyyy-mm-dd) falls in, named FY<yyyy> by the calendar year its first day (the
 * first of the start month, 1 to 12) falls in.
 */
export function fiscalYearOf(date: string, startMonth: number): string {
  const year = Number(date.slice(0, 4));
  const month = Number(date.slice(5, 7));
  return `FY${month >= startMonth ? year : year - 1}`;
}

/** Whether the date falls inside a closed period. */
export function closedOn(periods: Period[], date: string): boolean {
  return periodOn(periods, date)?.closed ?? false;
}

// The sheet's periods, with one form to add one, each period's due-total and whether it is
// closed (or soft-closed) and its fiscal year, a form to generate one per month over a date range, one to generate a
// fiscal year's months, and a button to close, to soft-close
// and to reopen each (by the roles the sheet's permission matrix allows).
@Component({
  selector: 'app-periods',
  imports: [FigurePipe, ReactiveFormsModule],
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>
      <form [formGroup]="form" (ngSubmit)="save()" aria-label="Add period">
        <label for="period-form-name">Name</label>
        <input id="period-form-name" data-testid="period-form-name" formControlName="name" />
        <label for="period-form-from">From</label>
        <input id="period-form-from" type="date" data-testid="period-form-from" formControlName="from" />
        <label for="period-form-to">To</label>
        <input id="period-form-to" type="date" data-testid="period-form-to" formControlName="to" />
        <label for="period-form-cap">Open claims cap per person</label>
        <input id="period-form-cap" data-testid="period-form-cap" formControlName="cap" inputmode="numeric" />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="period-form-error">{{ error() }}</p>
        }
      </form>
      <form [formGroup]="generateForm" (ngSubmit)="generate()" aria-label="Generate monthly periods">
        <label for="period-generate-from">From</label>
        <input id="period-generate-from" type="date" data-testid="period-generate-from" formControlName="from" />
        <label for="period-generate-to">To</label>
        <input id="period-generate-to" type="date" data-testid="period-generate-to" formControlName="to" />
        <button type="submit" data-testid="period-generate">Generate monthly periods</button>
        @if (generated() !== null) {
          <output data-testid="period-generated" aria-live="polite">{{ generated() }}</output>
        }
        @if (generateError()) {
          <p role="alert" class="error" data-testid="period-generate-error">{{ generateError() }}</p>
        }
      </form>
      <form [formGroup]="fyForm" (ngSubmit)="generateFy()" aria-label="Generate a fiscal year's monthly periods">
        <label for="period-generate-fy-year">Fiscal year (yyyy)</label>
        <input id="period-generate-fy-year" data-testid="period-generate-fy-year" formControlName="year" inputmode="numeric" />
        <button type="submit" data-testid="period-generate-fy">Generate fiscal year</button>
      </form>
      <form [formGroup]="openingForm" (ngSubmit)="importOpenings()" aria-label="Paste opening balances">
        <label for="opening-import">Opening balances (name,period,amount per line)</label>
        <textarea id="opening-import" data-testid="opening-import" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="opening-import-submit">Paste opening balances</button>
        @if (openingDone() !== null) {
          <output data-testid="opening-import-done" aria-live="polite">{{ openingDone() }}</output>
        }
        @if (openingError()) {
          <p role="alert" class="error" data-testid="opening-error">{{ openingError() }}</p>
        }
      </form>
      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due total</th><th scope="col">Closed</th><th scope="col">Fiscal year</th><th scope="col">Cap</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <td data-testid="period-name">{{ p.name }}</td>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotalOf()(p) | figure }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</td>
              <td data-testid="period-fy">{{ fyOf(p) }}</td>
              <td data-testid="period-cap">{{ p.cap ?? '' | figure }}</td>
              <td>
                <button type="button" data-testid="period-close" (click)="close(p)"
                        [attr.aria-label]="'Close period ' + p.name">Close</button>
                <button type="button" data-testid="period-soft-close" (click)="softClose(p)"
                        [attr.aria-label]="'Soft-close period ' + p.name">Soft close</button>
                <button type="button" data-testid="period-reopen" (click)="reopen(p)"
                        [attr.aria-label]="'Reopen period ' + p.name">Reopen</button>
                @if (periodError()?.id === p.id) {
                  <p role="alert" class="error" data-testid="period-error">{{ periodError()?.message }}</p>
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

  /** The sheet's periods, earliest first. */
  readonly periods = input.required<Period[]>();
  /** A period's due-total, two decimals: the people's figures for it added up, fixed ones once closed. */
  readonly dueTotalOf = input.required<(p: Period) => string>();
  /** A period added, closed or reopened, as the store now holds it. */
  readonly saved = output<Period>();

  protected readonly error = signal('');
  /** The error from the last action on a period's row, if it was refused. */
  protected readonly periodError = signal<{ id: number; message: string } | null>(null);
  /** A submit is under way; another one waits for its answer rather than storing twice. */
  private saving = false;
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
  });

  protected readonly generateError = signal('');
  /** How many periods the last generate made; null while one runs or before any. */
  protected readonly generated = signal<number | null>(null);
  private generating = false;
  protected readonly generateForm = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  protected readonly fyForm = new FormGroup({
    year: new FormControl('', { nonNullable: true }),
  });

  protected readonly openingError = signal('');
  /** How many lines the last opening paste took; null while one runs or before any. */
  protected readonly openingDone = signal<number | null>(null);
  private importingOpenings = false;
  protected readonly openingForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.openingForm.valueChanges.subscribe(() => this.openingError.set(''));
    this.fyForm.valueChanges.subscribe(() => this.generateError.set(''));
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.generateForm.valueChanges.subscribe(() => this.generateError.set(''));
  }

  /** The fiscal year the period's from-date falls in, by the sheet's fiscal start month. */
  protected fyOf(p: Period): string {
    return fiscalYearOf(p.from, this.session.fiscalStartMonth());
  }

  /** Make the fiscal year's twelve monthly periods as the generator does, leaving out taken or overlapped months. */
  protected async generateFy(): Promise<void> {
    if (this.generating) return;
    this.generateError.set('');
    this.generated.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const year = this.fyForm.getRawValue().year.trim();
    if (!/^\d{4}$/.test(year)) {
      this.generateError.set('Enter a fiscal year as yyyy.');
      return;
    }
    this.generating = true;
    try {
      const made = await firstValueFrom(
        this.http.post<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods/generate-fy`, { year }, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const period of made) this.saved.emit(period);
      this.generated.set(made.length);
    } catch {
      if (this.session.sheetId() !== sheet) return;
      this.generateError.set('Could not generate that fiscal year.');
    } finally {
      this.generating = false;
    }
  }

  /** Make a period for each month from the from-date's to the to-date's, leaving out taken or overlapped months. */
  protected async generate(): Promise<void> {
    if (this.generating) return;
    this.generateError.set('');
    this.generated.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.generateForm.getRawValue();
    const today = this.session.today() ?? '';
    const from = raw.from.trim() || today;
    const to = raw.to.trim() || today;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      this.generateError.set('Enter a from and a to date.');
      return;
    }
    if (to < from) {
      this.generateError.set('The to date cannot be before the from date.');
      return;
    }
    this.generating = true;
    try {
      const made = await firstValueFrom(
        this.http.post<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods/generate`, { from, to }, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const period of made) this.saved.emit(period);
      this.generated.set(made.length);
    } catch {
      if (this.session.sheetId() !== sheet) return;
      this.generateError.set('Could not generate those periods.');
    } finally {
      this.generating = false;
    }
  }

  /**
   * Add each pasted line of name,period,amount to the person's opening balance for the period. Every line
   * is checked first; if any is refused, nothing is added.
   */
  protected async importOpenings(): Promise<void> {
    if (this.importingOpenings) return;
    this.openingError.set('');
    this.openingDone.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = this.openingForm.getRawValue().text;
    const lines = text.split(/\r?\n/).filter((line) => line.trim() !== '');
    if (lines.length === 0) {
      this.openingError.set('Paste at least one line of name,period,amount.');
      return;
    }
    const names = new Set(this.periods().map((p) => p.name));
    for (const line of lines) {
      const fields = line.split(',');
      if (fields.length !== 3 || fields[0].trim() === '' || !names.has(fields[1].trim()) || !/^\d{1,12}\.\d{2}$/.test(fields[2].trim())) {
        this.openingError.set('Every line must be a person, a period on this sheet and an amount of 0.00 or more; nothing was added.');
        return;
      }
    }
    this.importingOpenings = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ lines: number; periods: Period[] }>(`/api/sheets/${encodeURIComponent(sheet)}/periods/openings`, { text }, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const period of result.periods) this.saved.emit(period);
      this.openingDone.set(result.lines);
    } catch {
      if (this.session.sheetId() !== sheet) return;
      this.openingError.set('Every line must be a person on this sheet, a period on it and an amount of 0.00 or more; nothing was added.');
    } finally {
      this.importingOpenings = false;
    }
  }

  /** Close a period, fixing its due-total (afresh when soft-closed); a closed one cannot be closed again. */
  protected close(p: Period): Promise<void> {
    return this.fix(p, 'close');
  }

  /** Soft-close a period, fixing its due-total while its claims can still change; not one already closed or soft-closed. */
  protected softClose(p: Period): Promise<void> {
    return this.fix(p, 'soft-close');
  }

  private async fix(p: Period, action: 'close' | 'soft-close'): Promise<void> {
    this.periodError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const period = await firstValueFrom(
        this.http.post<Period>(`/api/sheets/${encodeURIComponent(sheet)}/periods/${p.id}/${action}`, null, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(period);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const message = status === 423
        ? (action === 'close' ? 'That period is already closed.' : 'That period is already closed or soft-closed.')
        : status === 403
        ? 'Your role may not close a period on this sheet.'
        : 'Could not close that period.';
      this.periodError.set({ id: p.id, message });
    }
  }

  /** Reopen a closed period, making its due-total live again; by the roles the permission matrix allows. */
  protected async reopen(p: Period): Promise<void> {
    this.periodError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const period = await firstValueFrom(
        this.http.post<Period>(`/api/sheets/${encodeURIComponent(sheet)}/periods/${p.id}/reopen`, null, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(period);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const message = status === 403
        ? 'Your role may not reopen a period on this sheet.'
        : status === 409
          ? 'That period is not closed.'
          : 'Could not reopen that period.';
      this.periodError.set({ id: p.id, message });
    }
  }

  protected async save(): Promise<void> {
    if (this.saving) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.form.getRawValue();
    const name = raw.name.trim();
    const from = raw.from.trim() || (this.session.today() ?? '');
    const to = raw.to.trim();
    const capText = raw.cap.trim();
    if (!/^[A-Za-z0-9._-]{1,100}$/.test(name)) {
      this.error.set('Enter a name of letters, digits, ".", "_" or "-".');
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      this.error.set('Enter a from and a to date.');
      return;
    }
    if (to < from) {
      this.error.set('The to date cannot be before the from date.');
      return;
    }
    if (capText !== '' && (!/^\d{1,9}$/.test(capText) || Number(capText) < 1)) {
      this.error.set('Enter a cap of a whole number of at least 1, or leave it blank.');
      return;
    }
    const cap = capText === '' ? null : Number(capText);
    if (this.periods().some((p) => p.name === name)) {
      this.error.set('That name is already in use on this sheet.');
      return;
    }
    this.saving = true;
    try {
      const period = await firstValueFrom(
        this.http.post<Period>(`/api/sheets/${encodeURIComponent(sheet)}/periods`, { name, from, to, cap }, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(period);
      this.form.reset({ name: '', from: '', to: '', cap: '' }, { emitEvent: false });
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That name is in use, or those dates overlap another period.'
          : 'Could not add that period.',
      );
    } finally {
      this.saving = false;
    }
  }
}
