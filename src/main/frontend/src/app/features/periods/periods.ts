import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { money } from '../claims/claims-api';
import { Period, PeriodsApi, closeError, generateError, periodDueCents, periodError, reopenError } from './periods-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's periods, by from-date, each with its due-total and whether it is closed (with the
// buttons that close and reopen it), and the form that adds a period.
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
      <form [formGroup]="generateForm" (ngSubmit)="generate()" aria-label="Generate monthly periods">
        <label for="period-generate-from">From</label>
        <input id="period-generate-from" type="date" data-testid="period-generate-from" formControlName="from" />
        <label for="period-generate-to">To</label>
        <input id="period-generate-to" type="date" data-testid="period-generate-to" formControlName="to" />
        <button type="submit" data-testid="period-generate">Generate monthly periods</button>
        @if (generated() !== null) {
          <p role="status">Made <strong data-testid="period-generated">{{ generated() }}</strong></p>
        }
        @if (generateErr(); as err) {
          <p role="alert" data-testid="period-generate-error">{{ err }}</p>
        }
      </form>
      <table data-testid="periods-list">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due</th><th scope="col">Closed</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueOf(p) }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</td>
              <td>
                <button type="button" data-testid="period-close" (click)="close(p)"
                  [attr.aria-label]="'Close period ' + p.name">Close</button>
                <button type="button" data-testid="period-soft-close" (click)="softClose(p)"
                  [attr.aria-label]="'Soft-close period ' + p.name">Soft-close</button>
                <button type="button" data-testid="period-reopen" (click)="reopen(p)"
                  [attr.aria-label]="'Reopen period ' + p.name">Reopen</button>
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
  });

  /** The month range that generates a period per month. */
  protected readonly generateForm = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });
  /** How many periods the last generate made; null while one runs or before the first. */
  protected readonly generated = signal<number | null>(null);
  protected readonly generateErr = signal<string | null>(null);
  private generating = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    this.generateForm.valueChanges.subscribe(() => this.generateErr.set(null));
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.generateForm.reset({ from: '', to: '' });
        this.generated.set(null);
        this.generateErr.set(null);
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
      if (this.session.sheetId() === sheetId) this.saved.emit(period);
      this.form.reset({ name: '', from: '', to: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(periodError(e));
    } finally {
      this.submitting = false;
    }
  }
}
