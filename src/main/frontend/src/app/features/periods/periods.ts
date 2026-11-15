import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { FigurePipe } from '../../core/figure.pipe';
import { Session } from '../../core/session';
import { money } from '../claims/claims-api';
import { Period, PeriodsApi, closeError, generateError, generateFiscalYearError, lockError, openingsError, periodDueCents, periodError, reopenError } from './periods-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's periods, by from-date, each with its due-total and whether it is closed (with the
// buttons that close and reopen it), and the form that adds a period.
@Component({
  selector: 'app-periods',
  imports: [FigurePipe, ReactiveFormsModule],
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
        <label for="period-form-cap">Open claims cap per person</label>
        <input id="period-form-cap" data-testid="period-form-cap" formControlName="cap" inputmode="numeric" autocomplete="off" />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error(); as err) {
          <p role="alert" data-testid="period-form-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="generateForm" (ngSubmit)="generate()" aria-label="Generate monthly periods">
        <label for="period-generate-from">From</label>
        <input id="period-generate-from" type="date" data-testid="period-generate-from" formControlName="from" />
        <label for="period-generate-to">To</label>
        <input id="period-generate-to" type="date" data-testid="period-generate-to" formControlName="to" />
        <button type="submit" data-testid="period-generate">Generate monthly periods</button>
        <label for="period-generate-fy-year">Fiscal year (yyyy)</label>
        <input id="period-generate-fy-year" data-testid="period-generate-fy-year" [formControl]="fyForm.controls.year" inputmode="numeric" autocomplete="off" />
        <button type="button" data-testid="period-generate-fy" (click)="generateFiscalYear()">Generate fiscal year</button>
        @if (generated() !== null) {
          <p role="status">Made <strong data-testid="period-generated">{{ generated() }}</strong></p>
        }
        @if (generateErr(); as err) {
          <p role="alert" data-testid="period-generate-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="openingForm" (ngSubmit)="importOpenings()" aria-label="Paste opening balances">
        <label for="opening-import">Opening balances (one name,period,amount per line)</label>
        <textarea id="opening-import" data-testid="opening-import" formControlName="text" rows="3"></textarea>
        <button type="submit" data-testid="opening-import-submit">Add opening balances</button>
        @if (openingsDone() !== null) {
          <p role="status">Added <strong data-testid="opening-import-done">{{ openingsDone() }}</strong></p>
        }
        @if (openingErr(); as err) {
          <p role="alert" data-testid="opening-error">{{ err }}</p>
        }
      </form>
      <table data-testid="periods-list">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due</th><th scope="col">Closed</th><th scope="col">Fiscal year</th><th scope="col">Cap</th><th scope="col">Locked</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueOf(p) | figure }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</td>
              <td data-testid="period-fy">{{ session.fiscalYearOf(p.from) }}</td>
              <td data-testid="period-cap">{{ (p.cap ?? '') | figure }}</td>
              <td data-testid="period-locked">{{ p.locked ? 'yes' : 'no' }}</td>
              <td>
                <button type="button" data-testid="period-close" (click)="close(p)"
                  [attr.aria-label]="'Close period ' + p.name">Close</button>
                <button type="button" data-testid="period-soft-close" (click)="softClose(p)"
                  [attr.aria-label]="'Soft-close period ' + p.name">Soft-close</button>
                <button type="button" data-testid="period-reopen" (click)="reopen(p)"
                  [attr.aria-label]="'Reopen period ' + p.name">Reopen</button>
                <button type="button" data-testid="period-lock" (click)="lock(p, true)"
                  [attr.aria-label]="'Lock period ' + p.name">Lock</button>
                <button type="button" data-testid="period-unlock" (click)="lock(p, false)"
                  [attr.aria-label]="'Unlock period ' + p.name">Unlock</button>
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
  protected readonly session = inject(Session);
  private readonly api = inject(PeriodsApi);

  /** The sheet's periods, by from-date. */
  readonly periods = input.required<Period[]>();
  /** Each period's due-total in cents, by period id. */
  readonly dueTotals = input.required<Map<number, number>>();
  /** A period just added or closed, for the sheet to take in. */
  readonly saved = output<Period>();

  protected readonly error = signal<string | null>(null);
  /** The error of each period's last refused action, by period id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** Whether a submit is waiting for its answer; a second submit meanwhile is ignored. */
  private submitting = false;

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
  });

  /** The month range that generates a period per month. */
  protected readonly generateForm = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });
  /** The fiscal year whose twelve monthly periods are generated. */
  protected readonly fyForm = new FormGroup({
    year: new FormControl('', { nonNullable: true }),
  });
  /** How many periods the last generate made; null while one runs or before the first. */
  protected readonly generated = signal<number | null>(null);
  protected readonly generateErr = signal<string | null>(null);
  private generating = false;

  /** The pasted opening balances, one name,period,amount per line. */
  protected readonly openingForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  /** How many lines the last opening paste added; null while one runs or before the first. */
  protected readonly openingsDone = signal<number | null>(null);
  protected readonly openingErr = signal<string | null>(null);
  private importing = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    this.generateForm.valueChanges.subscribe(() => this.generateErr.set(null));
    this.fyForm.valueChanges.subscribe(() => this.generateErr.set(null));
    this.openingForm.valueChanges.subscribe(() => this.openingErr.set(null));
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.generateForm.reset({ from: '', to: '' });
        this.fyForm.reset({ year: '' });
        this.generated.set(null);
        this.generateErr.set(null);
        this.openingForm.reset({ text: '' });
        this.openingsDone.set(null);
        this.openingErr.set(null);
      });
    });
  }

  /** Make a period per calendar month in the range; then show how many were made. */
  protected async generate(): Promise<void> {
    if (this.generating) return;
    this.generated.set(null);
    this.generateErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const from = this.generateForm.controls.from.value.trim() || (this.session.today() ?? '');
    const to = this.generateForm.controls.to.value.trim();
    if (!DATE.test(from) || !DATE.test(to)) {
      this.generateErr.set('Enter a from-date and a to-date as yyyy-mm-dd.');
      return;
    }
    if (to < from) {
      this.generateErr.set('The to-date must be on or after the from-date.');
      return;
    }
    this.generating = true;
    try {
      const made = await this.api.generate(sheetId, from, to);
      if (this.session.sheetId() !== sheetId) return;
      for (const period of made) this.saved.emit(period);
      this.generated.set(made.length);
    } catch (e) {
      this.generateErr.set(generateError(e));
    } finally {
      this.generating = false;
    }
  }

  /** Make the twelve monthly periods of a fiscal year as the month range generate does; then show
   *  how many were made. */
  protected async generateFiscalYear(): Promise<void> {
    if (this.generating) return;
    this.generated.set(null);
    this.generateErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const year = this.fyForm.controls.year.value.trim();
    if (!/^\d{4}$/.test(year)) {
      this.generateErr.set('Enter a fiscal year as yyyy.');
      return;
    }
    this.generating = true;
    try {
      const made = await this.api.generateFiscalYear(sheetId, year);
      if (this.session.sheetId() !== sheetId) return;
      for (const period of made) this.saved.emit(period);
      this.generated.set(made.length);
    } catch (e) {
      this.generateErr.set(generateFiscalYearError(e));
    } finally {
      this.generating = false;
    }
  }

  /** Add the pasted opening balances, all or none; then show how many lines went through. */
  protected async importOpenings(): Promise<void> {
    if (this.importing) return;
    this.openingsDone.set(null);
    this.openingErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const text = this.openingForm.controls.text.value;
    if (text.trim() === '') {
      this.openingErr.set('Paste at least one line of name,period,amount.');
      return;
    }
    this.importing = true;
    try {
      const done = await this.api.importOpenings(sheetId, text);
      if (this.session.sheetId() !== sheetId) return;
      for (const period of done.periods) this.saved.emit(period);
      this.openingsDone.set(done.imported);
    } catch (e) {
      this.openingErr.set(openingsError(e));
    } finally {
      this.importing = false;
    }
  }

  /** A closed period shows the due-total fixed when it was closed; an open one its live total. */
  protected dueOf(p: Period): string {
    return money(periodDueCents(p, this.dueTotals()));
  }

  /** Close a period, fixing its due-total; a refusal shows an error on the period. */
  protected async close(p: Period): Promise<void> {
    this.rowErrors.update(({ [p.id]: _, ...rest }) => rest);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const closed = await this.api.close(sheetId, p.id);
      if (this.session.sheetId() === sheetId) this.saved.emit(closed);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: closeError(e) }));
    }
  }

  /** Soft-close a period, fixing its due-total while its claims can still be changed; a refusal
   *  shows an error on the period. */
  protected async softClose(p: Period): Promise<void> {
    this.rowErrors.update(({ [p.id]: _, ...rest }) => rest);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const closed = await this.api.softClose(sheetId, p.id);
      if (this.session.sheetId() === sheetId) this.saved.emit(closed);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: closeError(e) }));
    }
  }

  /** Reopen a closed or soft-closed period, its due-total live again; a refusal shows an error on the period. */
  protected async reopen(p: Period): Promise<void> {
    this.rowErrors.update(({ [p.id]: _, ...rest }) => rest);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const reopened = await this.api.reopen(sheetId, p.id);
      if (this.session.sheetId() === sheetId) this.saved.emit(reopened);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: reopenError(e) }));
    }
  }

  /** Lock a period so it cannot be reopened, or unlock it; a refusal shows an error on the period. */
  protected async lock(p: Period, locked: boolean): Promise<void> {
    this.rowErrors.update(({ [p.id]: _, ...rest }) => rest);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const changed = locked ? await this.api.lock(sheetId, p.id) : await this.api.unlock(sheetId, p.id);
      if (this.session.sheetId() === sheetId) this.saved.emit(changed);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: lockError(e) }));
    }
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const name = this.form.controls.name.value.trim();
    const from = this.form.controls.from.value.trim() || (this.session.today() ?? '');
    const to = this.form.controls.to.value.trim();
    const capText = this.form.controls.cap.value.trim();
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
    if (capText !== '' && !/^\d+$/.test(capText)) {
      this.error.set('Enter the cap as a whole number, or leave it blank for none.');
      return;
    }
    const cap = capText === '' ? null : Number(capText);
    if (cap !== null && cap < 1) {
      this.error.set('The cap must be 1 or more.');
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
      const period = await this.api.add(sheetId, name, from, to, cap);
      if (this.session.sheetId() === sheetId) this.saved.emit(period);
      this.form.reset({ name: '', from: '', to: '', cap: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(periodError(e));
    } finally {
      this.submitting = false;
    }
  }
}
