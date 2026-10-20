import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Period, PeriodsApi } from './periods-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's periods by from-date, each with the due-total of the claims that count dated in it (fixed once
// it is closed) and whether it is closed, and the form that adds one.
@Component({
  selector: 'app-periods',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="periods-heading" data-testid="periods">
      <h2 id="periods-heading">Periods</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="period-form">
        <label for="period-form-name">Name</label>
        <input id="period-form-name" data-testid="period-form-name" formControlName="name" />
        <label for="period-form-from">From</label>
        <input id="period-form-from" data-testid="period-form-from" type="date" formControlName="from" />
        <label for="period-form-to">To</label>
        <input id="period-form-to" data-testid="period-form-to" type="date" formControlName="to" />
        <button type="submit" data-testid="period-form-submit">Add period</button>
      </form>
      @if (formError(); as e) {
        <p role="alert" data-testid="period-form-error">{{ e }}</p>
      }
      <form [formGroup]="generateForm" (ngSubmit)="generate()" data-testid="period-generate-form">
        <label for="period-generate-from">Months from</label>
        <input id="period-generate-from" data-testid="period-generate-from" type="date" formControlName="from" />
        <label for="period-generate-to">To</label>
        <input id="period-generate-to" data-testid="period-generate-to" type="date" formControlName="to" />
        <button type="submit" data-testid="period-generate">Generate monthly periods</button>
      </form>
      @if (generateError(); as e) {
        <p role="alert" data-testid="period-generate-error">{{ e }}</p>
      }
      @if (generated() !== null) {
        <p role="status">Periods made: <span data-testid="period-generated">{{ generated() }}</span></p>
      }
      <table data-testid="period-list">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">From</th>
            <th scope="col">To</th>
            <th scope="col">Due</th>
            <th scope="col">Closed</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <td data-testid="period-name">{{ p.name }}</td>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotals().get(p.id) ?? '0.00' }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : 'no' }}</td>
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
                  data-testid="period-reopen"
                  [attr.aria-label]="'Reopen period ' + p.name"
                  (click)="reopen(p)"
                >
                  Reopen
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
  private readonly session = inject(Session);
  private readonly api = inject(PeriodsApi);

  /** The sheet's periods by from-date. */
  readonly periods = input.required<readonly Period[]>();
  /** Each period's due-total, by id. */
  readonly dueTotals = input.required<ReadonlyMap<number, string>>();
  /** A period was added, closed or reopened. */
  readonly saved = output<Period>();

  protected readonly formError = signal<string | null>(null);
  /** The error of the last action taken on a period, shown on that period's row. */
  protected readonly periodError = signal<{ id: number; message: string } | null>(null);
  /** Whether a submit is waiting on the server; another submit is ignored until it answers. */
  private submitting = false;
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  protected readonly generateError = signal<string | null>(null);
  /** How many periods the last generate made; null before one, or after one is refused. */
  protected readonly generated = signal<number | null>(null);
  private generating = false;
  protected readonly generateForm = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.formError.set(null));
    this.generateForm.valueChanges.subscribe(() => this.generateError.set(null));
  }

  protected generate(): void {
    if (this.generating) return;
    this.generateError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.generateError.set('Load a sheet first.');
      return;
    }
    const value = this.generateForm.getRawValue();
    if (!DATE.test(value.from) || !DATE.test(value.to)) {
      this.generated.set(null);
      this.generateError.set('Pick a from and a to date.');
      return;
    }
    if (value.to < value.from) {
      this.generated.set(null);
      this.generateError.set('The to-date cannot be before the from-date.');
      return;
    }
    this.generating = true;
    this.api.generate(sheetId, value.from, value.to).subscribe({
      next: (made) => {
        this.generating = false;
        if (this.session.sheetId() === sheetId) made.forEach((p) => this.saved.emit(p));
        this.generated.set(made.length);
      },
      error: () => {
        this.generating = false;
        this.generated.set(null);
        this.generateError.set('Could not generate those periods.');
      },
    });
  }

  protected close(period: Period): void {
    this.periodError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.api.close(sheetId, period.id).subscribe({
      next: (p) => {
        if (this.session.sheetId() === sheetId) this.saved.emit(p);
      },
      error: (e: HttpErrorResponse) =>
        this.periodError.set({
          id: period.id,
          message:
            e.status === 409
              ? 'That period is already closed.'
              : e.status === 404
                ? 'That period is no longer on this sheet.'
                : 'Could not close that period.',
        }),
    });
  }

  protected reopen(period: Period): void {
    this.periodError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.api.reopen(sheetId, period.id).subscribe({
      next: (p) => {
        if (this.session.sheetId() === sheetId) this.saved.emit(p);
      },
      error: (e: HttpErrorResponse) =>
        this.periodError.set({
          id: period.id,
          message:
            e.status === 403
              ? 'Only a steward can reopen a period.'
              : e.status === 409
                ? 'That period is not closed.'
                : e.status === 404
                  ? 'That period is no longer on this sheet.'
                  : 'Could not reopen that period.',
        }),
    });
  }

  protected submit(): void {
    if (this.submitting) return;
    this.formError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.formError.set('Load a sheet first.');
      return;
    }
    const value = this.form.getRawValue();
    const name = value.name.trim();
    if (!name) {
      this.formError.set('Type a name for the period.');
      return;
    }
    if (!DATE.test(value.from) || !DATE.test(value.to)) {
      this.formError.set('Pick a from and a to date.');
      return;
    }
    if (value.to < value.from) {
      this.formError.set('A period cannot end before it starts.');
      return;
    }
    this.submitting = true;
    this.api.add(sheetId, { name, from: value.from, to: value.to }).subscribe({
      next: (p) => {
        this.submitting = false;
        if (this.session.sheetId() === sheetId) this.saved.emit(p);
        this.form.reset({ name: '', from: '', to: '' }, { emitEvent: false });
      },
      error: (e: HttpErrorResponse) => {
        this.submitting = false;
        this.formError.set(
          e.status === 409
            ? 'That name is in use, or those dates overlap another period.'
            : 'Could not add that period.',
        );
      },
    });
  }
}
