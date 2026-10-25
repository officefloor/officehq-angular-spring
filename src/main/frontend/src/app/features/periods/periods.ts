import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { PeriodView, PeriodsApi } from './periods-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's periods in date order, each with the due-total of the counting claims dated in it
// (fixed once the period is closed) and whether it is closed, with one form to add a period.
@Component({
  selector: 'app-periods',
  imports: [ReactiveFormsModule],
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
      <table data-testid="periods-list" aria-label="Periods">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due</th><th scope="col">Closed</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotals().get(p.id) ?? '0.00' }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : 'no' }}</td>
              <td>
                <button type="button" data-testid="period-close" (click)="close(p)"
                  [disabled]="closing() === p.id" [attr.aria-label]="'Close period ' + p.name">Close</button>
                <button type="button" data-testid="period-reopen" (click)="reopen(p)"
                  [disabled]="closing() === p.id" [attr.aria-label]="'Reopen period ' + p.name">Reopen</button>
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

  /** The sheet's periods in date order. */
  readonly periods = input.required<PeriodView[]>();
  /** Each period's due-total with two decimals, by period id. */
  readonly dueTotals = input.required<ReadonlyMap<number, string>>();
  readonly added = output<PeriodView>();
  /** A period that was closed or reopened. */
  readonly changed = output<PeriodView>();

  protected readonly name = new FormControl('', { nonNullable: true });
  protected readonly from = new FormControl('', { nonNullable: true });
  protected readonly to = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ name: this.name, from: this.from, to: this.to });
  protected readonly generateFrom = new FormControl('', { nonNullable: true });
  protected readonly generateTo = new FormControl('', { nonNullable: true });
  protected readonly generateForm = new FormGroup({ from: this.generateFrom, to: this.generateTo });
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
    // Each load of a sheet starts the form afresh.
    effect(() => {
      this.session.loaded();
      this.error.set('');
      this.periodError.set(null);
      this.form.reset({ name: '', from: '', to: '' }, { emitEvent: false });
      this.generateError.set('');
      this.generated.set(null);
      this.generateForm.reset({ from: '', to: '' }, { emitEvent: false });
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

  /** Close a period, fixing its due-total; the store refuses one already closed. */
  protected async close(period: PeriodView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.closing() !== null) return;
    this.periodError.set(null);
    this.closing.set(period.id);
    try {
      const closed = await this.api.close(sheet.id, period.id);
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
          : status === 409 ? 'This period is not closed.' : 'The period could not be reopened.' });
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
    this.saving.set(true);
    try {
      const period = await this.api.add(sheet.id, { name, from, to });
      if (this.session.sheet()?.id === sheet.id) {
        this.added.emit(period);
        this.form.reset({ name: '', from: '', to: '' }, { emitEvent: false });
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
