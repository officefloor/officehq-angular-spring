import { HttpErrorResponse } from '@angular/common/http';
import { FigurePipe } from '../../core/figure.pipe';
import { Component, effect, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { SheetFiscal, fiscalYearOf } from '../settings/sheet-fiscal';
import { PeriodView, PeriodsApi } from './periods-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const YEAR = /^\d{4}$/;

// The sheet's periods in date order, each with the due-total of the counting claims dated in it
// (fixed once the period is closed or soft-closed), whether it is closed (or soft-closed) and the fiscal year its
// from-date falls in, with one form to add a period, one to generate monthly periods between two dates, and one to
// generate a fiscal year's twelve monthly periods, and one to generate a calendar year's four quarters.
@Component({
  selector: 'app-periods',
  imports: [FigurePipe, ReactiveFormsModule],
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Add a period">
        <label for="period-form-name">Name</label>
        <input id="period-form-name" data-testid="period-form-name" formControlName="name" />
        <label for="period-form-from">From</label>
        <input id="period-form-from" type="date" data-testid="period-form-from" formControlName="from" />
        <label for="period-form-to">To</label>
        <input id="period-form-to" type="date" data-testid="period-form-to" formControlName="to" />
        <label for="period-form-cap">Open claims cap</label>
        <input id="period-form-cap" data-testid="period-form-cap" formControlName="cap" inputmode="numeric" />
        <button type="submit" data-testid="period-form-submit" [disabled]="saving()">Add period</button>
        @if (error()) {
          <p role="alert" data-testid="period-form-error">{{ error() }}</p>
        }
      </form>
      <form [formGroup]="generateForm" (ngSubmit)="generate()" aria-label="Generate monthly periods">
        <label for="period-generate-from">From</label>
        <input id="period-generate-from" type="date" data-testid="period-generate-from" formControlName="from" />
        <label for="period-generate-to">To</label>
        <input id="period-generate-to" type="date" data-testid="period-generate-to" formControlName="to" />
        <button type="submit" data-testid="period-generate" [disabled]="generating()">Generate monthly periods</button>
        @if (generated() !== null) {
          <p role="status">Periods made: <span data-testid="period-generated">{{ generated() }}</span></p>
        }
        @if (generateError()) {
          <p role="alert" data-testid="period-generate-error">{{ generateError() }}</p>
        }
      </form>
      <form [formGroup]="fyForm" (ngSubmit)="generateFiscalYear()" aria-label="Generate a fiscal year's monthly periods">
        <label for="period-generate-fy-year">Fiscal year (yyyy, the calendar year it starts in)</label>
        <input id="period-generate-fy-year" data-testid="period-generate-fy-year" formControlName="year" inputmode="numeric" />
        <button type="submit" data-testid="period-generate-fy" [disabled]="generating()">Generate fiscal year</button>
      </form>
      <form [formGroup]="quartersForm" (ngSubmit)="generateQuarters()" aria-label="Generate a year's quarterly periods">
        <label for="period-generate-quarters-year">Year (yyyy)</label>
        <input id="period-generate-quarters-year" data-testid="period-generate-quarters-year" formControlName="year" inputmode="numeric" />
        <button type="submit" data-testid="period-generate-quarters" [disabled]="generating()">Generate quarters</button>
      </form>
      <table data-testid="periods-list" aria-label="Periods">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due</th><th scope="col">Closed</th><th scope="col">Fiscal year</th><th scope="col">Cap</th><th scope="col">Locked</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ (dueTotals().get(p.id) ?? '0.00') | figure }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</td>
              <td data-testid="period-fy">{{ fiscalYear(p) }}</td>
              <td data-testid="period-cap">{{ (p.cap ?? '') | figure }}</td>
              <td data-testid="period-locked">{{ p.locked ? 'yes' : 'no' }}</td>
              <td data-testid="period-claims-count">{{ claimCounts().get(p.id) ?? 0 }}</td>
              <td>
                <button type="button" data-testid="period-close" (click)="close(p)"
                  [disabled]="closing() === p.id" [attr.aria-label]="'Close period ' + p.name">Close</button>
                <button type="button" data-testid="period-soft-close" (click)="close(p, true)"
                  [disabled]="closing() === p.id" [attr.aria-label]="'Soft-close period ' + p.name">Soft-close</button>
                <button type="button" data-testid="period-reopen" (click)="reopen(p)"
                  [disabled]="closing() === p.id" [attr.aria-label]="'Reopen period ' + p.name">Reopen</button>
                <button type="button" data-testid="period-lock" (click)="lock(p, true)"
                  [disabled]="closing() === p.id" [attr.aria-label]="'Lock period ' + p.name">Lock</button>
                <button type="button" data-testid="period-unlock" (click)="lock(p, false)"
                  [disabled]="closing() === p.id" [attr.aria-label]="'Unlock period ' + p.name">Unlock</button>
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
  private readonly sheetFiscal = inject(SheetFiscal);

  /** The sheet's periods in date order. */
  readonly periods = input.required<PeriodView[]>();
  /** Each period's due-total with two decimals, by period id. */
  readonly dueTotals = input.required<ReadonlyMap<number, string>>();
  /** Each period's count of its claims, VOID and DELETED ones left out, by period id. */
  readonly claimCounts = input.required<ReadonlyMap<number, number>>();
  readonly added = output<PeriodView>();
  /** A period that was closed or reopened. */
  readonly changed = output<PeriodView>();

  protected readonly name = new FormControl('', { nonNullable: true });
  protected readonly from = new FormControl('', { nonNullable: true });
  protected readonly to = new FormControl('', { nonNullable: true });
  protected readonly cap = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ name: this.name, from: this.from, to: this.to, cap: this.cap });
  protected readonly generateFrom = new FormControl('', { nonNullable: true });
  protected readonly generateTo = new FormControl('', { nonNullable: true });
  protected readonly generateForm = new FormGroup({ from: this.generateFrom, to: this.generateTo });
  protected readonly fyYear = new FormControl('', { nonNullable: true });
  protected readonly fyForm = new FormGroup({ year: this.fyYear });
  protected readonly quartersYear = new FormControl('', { nonNullable: true });
  protected readonly quartersForm = new FormGroup({ year: this.quartersYear });
  protected readonly generating = signal(false);
  /** How many periods the last generate made; null before one, and while one runs. */
  protected readonly generated = signal<number | null>(null);
  protected readonly generateError = signal('');
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  /** The period being closed or reopened. */
  protected readonly closing = signal<number | null>(null);
  /** The refusal of the last action taken on a period's row, shown on that row. */
  protected readonly periodError = signal<{ id: number; message: string } | null>(null);

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.generateForm.valueChanges.subscribe(() => this.generateError.set(''));
    this.fyForm.valueChanges.subscribe(() => this.generateError.set(''));
    this.quartersForm.valueChanges.subscribe(() => this.generateError.set(''));
    // Each load of a sheet starts the form afresh.
    effect(() => {
      this.session.loaded();
      this.error.set('');
      this.periodError.set(null);
      this.form.reset({ name: '', from: '', to: '', cap: '' }, { emitEvent: false });
      this.generateError.set('');
      this.generated.set(null);
      this.generateForm.reset({ from: '', to: '' }, { emitEvent: false });
      this.fyForm.reset({ year: '' }, { emitEvent: false });
      this.quartersForm.reset({ year: '' }, { emitEvent: false });
    });
  }

  /** Generate one period per calendar month between the two dates; a blank date is the sheet's today. */
  protected async generate(): Promise<void> {
    if (this.generating()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.generateError.set('');
    this.generated.set(null);
    const value = this.generateForm.getRawValue();
    const from = value.from.trim() || sheet.today;
    const to = value.to.trim() || sheet.today;
    if (!DATE.test(from) || !DATE.test(to)) {
      this.generateError.set('Type a from and a to date as yyyy-mm-dd.');
      return;
    }
    if (to < from) {
      this.generateError.set('The to-date must be on or after the from-date.');
      return;
    }
    this.generating.set(true);
    try {
      const made = await this.api.generate(sheet.id, from, to);
      if (this.session.sheet()?.id === sheet.id) {
        for (const period of made) this.added.emit(period);
        this.generated.set(made.length);
      }
    } catch {
      if (this.session.sheet()?.id === sheet.id) this.generateError.set('The periods could not be generated.');
    } finally {
      this.generating.set(false);
    }
  }

  /** The fiscal year the period's from-date falls in. */
  protected fiscalYear(period: PeriodView): string {
    return fiscalYearOf(period.from, this.sheetFiscal.startMonth());
  }

  /** Generate the twelve monthly periods of the fiscal year starting in the typed calendar year. */
  protected async generateFiscalYear(): Promise<void> {
    if (this.generating()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.generateError.set('');
    this.generated.set(null);
    const year = this.fyYear.value.trim();
    if (!YEAR.test(year) || Number(year) < 1 || Number(year) > 9998) {
      this.generateError.set('Type the fiscal year as the four-digit calendar year it starts in.');
      return;
    }
    this.generating.set(true);
    try {
      const made = await this.api.generateFiscalYear(sheet.id, Number(year));
      if (this.session.sheet()?.id === sheet.id) {
        for (const period of made) this.added.emit(period);
        this.generated.set(made.length);
      }
    } catch {
      if (this.session.sheet()?.id === sheet.id) this.generateError.set('The fiscal year\'s periods could not be generated.');
    } finally {
      this.generating.set(false);
    }
  }

  /** Generate the four calendar quarters of the typed year. */
  protected async generateQuarters(): Promise<void> {
    if (this.generating()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.generateError.set('');
    this.generated.set(null);
    const year = this.quartersYear.value.trim();
    if (!YEAR.test(year) || Number(year) < 1) {
      this.generateError.set('Type the year as four digits.');
      return;
    }
    this.generating.set(true);
    try {
      const made = await this.api.generateQuarters(sheet.id, Number(year));
      if (this.session.sheet()?.id === sheet.id) {
        for (const period of made) this.added.emit(period);
        this.generated.set(made.length);
      }
    } catch {
      if (this.session.sheet()?.id === sheet.id) this.generateError.set('The quarters could not be generated.');
    } finally {
      this.generating.set(false);
    }
  }

  /** Close (or soft-close) a period, fixing its due-total; the store refuses one already closed, and a
   *  soft close of one already soft-closed. */
  protected async close(period: PeriodView, soft = false): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.closing() !== null) return;
    this.periodError.set(null);
    this.closing.set(period.id);
    try {
      const closed = soft ? await this.api.softClose(sheet.id, period.id) : await this.api.close(sheet.id, period.id);
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(closed);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        const status = e instanceof HttpErrorResponse ? e.status : 0;
        this.periodError.set({ id: period.id, message: status === 403
          ? 'Your role may not close a period on this sheet.'
          : status === 409 ? 'This period is already closed.' : 'The period could not be closed.' });
      }
    } finally {
      this.closing.set(null);
    }
  }

  /** Reopen a closed period, making its due-total live again; the store refuses anyone but a steward,
   *  and a period that is not closed. */
  protected async reopen(period: PeriodView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.closing() !== null) return;
    this.periodError.set(null);
    this.closing.set(period.id);
    try {
      const reopened = await this.api.reopen(sheet.id, period.id);
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(reopened);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        const status = e instanceof HttpErrorResponse ? e.status : 0;
        this.periodError.set({ id: period.id, message: status === 403
          ? 'Your role may not reopen a period on this sheet.'
          : status === 409 ? 'This period is locked, or not closed.' : 'The period could not be reopened.' });
      }
    } finally {
      this.closing.set(null);
    }
  }

  /** Lock (or unlock) a period; the store refuses anyone but a steward, a lock of a locked period and an
   *  unlock of one not locked. */
  protected async lock(period: PeriodView, locked: boolean): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.closing() !== null) return;
    this.periodError.set(null);
    this.closing.set(period.id);
    try {
      const changed = locked ? await this.api.lock(sheet.id, period.id) : await this.api.unlock(sheet.id, period.id);
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(changed);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        const status = e instanceof HttpErrorResponse ? e.status : 0;
        this.periodError.set({ id: period.id, message: status === 403
          ? 'Only a steward may lock or unlock a period.'
          : status === 409 ? (locked ? 'This period is already locked.' : 'This period is not locked.')
          : locked ? 'The period could not be locked.' : 'The period could not be unlocked.' });
      }
    } finally {
      this.closing.set(null);
    }
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const value = this.form.getRawValue();
    const name = value.name.trim();
    const from = value.from.trim();
    const to = value.to.trim();
    const capText = value.cap.trim();
    if (!name) {
      this.error.set('Type a name for the period.');
      return;
    }
    if (!DATE.test(from) || !DATE.test(to)) {
      this.error.set('Type a from and a to date as yyyy-mm-dd.');
      return;
    }
    if (to < from) {
      this.error.set('The to-date must be on or after the from-date.');
      return;
    }
    if (capText !== '' && !/^\d+$/.test(capText)) {
      this.error.set('Type the cap as a whole number of at least 1, or leave it blank for none.');
      return;
    }
    const cap = capText === '' ? null : Number(capText);
    if (cap !== null && (cap < 1 || !Number.isSafeInteger(cap))) {
      this.error.set('Type the cap as a whole number of at least 1, or leave it blank for none.');
      return;
    }
    this.saving.set(true);
    try {
      const period = await this.api.add(sheet.id, { name, from, to, cap });
      if (this.session.sheet()?.id === sheet.id) {
        this.added.emit(period);
        this.form.reset({ name: '', from: '', to: '', cap: '' }, { emitEvent: false });
      }
    } catch (e) {
      this.error.set(e instanceof HttpErrorResponse && e.status === 409
        ? 'That name is in use, or the period overlaps another period.'
        : 'The period could not be added.');
    } finally {
      this.saving.set(false);
    }
  }
}
