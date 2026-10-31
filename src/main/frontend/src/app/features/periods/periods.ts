import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, switchMap } from 'rxjs';
import { PeriodView, Periods } from '../../core/periods';
import { Sheets } from '../../core/sheet';

// The sheet's periods, each with its due-total, and a form to add one.
@Component({
  selector: 'app-periods',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>
      <ul aria-label="Periods of the sheet">
        @for (p of periods.list(); track p.id) {
          <li [attr.data-testid]="'period-row-' + p.name">
            <span data-testid="period-name">{{ p.name }}</span>
            from <span data-testid="period-from">{{ p.from }}</span>
            to <span data-testid="period-to">{{ p.to }}</span>
            — due <span data-testid="period-due-total">{{ dueTotals().get(p.id) ?? '0.00' }}</span>
            — closed <span data-testid="period-closed">{{ p.closed ? 'yes' : 'no' }}</span>
            <button type="button" data-testid="period-close" (click)="close(p)" [disabled]="closing() === p.id"
                    [attr.aria-label]="'Close period ' + p.name">Close</button>
            <button type="button" data-testid="period-reopen" (click)="reopen(p)" [disabled]="closing() === p.id"
                    [attr.aria-label]="'Reopen period ' + p.name">Reopen</button>
            @if (periodError()?.id === p.id) {
              <p role="alert" data-testid="period-error">{{ periodError()?.message }}</p>
            }
          </li>
        }
      </ul>

      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Add a period">
        <label for="period-form-name">Name</label>
        <input id="period-form-name" data-testid="period-form-name" formControlName="name" autocomplete="off" />
        <label for="period-form-from">From (blank: today)</label>
        <input id="period-form-from" type="date" data-testid="period-form-from" formControlName="from" />
        <label for="period-form-to">To</label>
        <input id="period-form-to" type="date" data-testid="period-form-to" formControlName="to" />
        <button type="submit" data-testid="period-form-submit" [disabled]="saving()">Add period</button>
        @if (error(); as err) {
          <p role="alert" data-testid="period-form-error">{{ err }}</p>
        }
      </form>

      <form [formGroup]="generateForm" (ngSubmit)="generate()" aria-label="Generate monthly periods">
        <label for="period-generate-from">From (blank: today)</label>
        <input id="period-generate-from" type="date" data-testid="period-generate-from" formControlName="from" />
        <label for="period-generate-to">To</label>
        <input id="period-generate-to" type="date" data-testid="period-generate-to" formControlName="to" />
        <button type="submit" data-testid="period-generate" [disabled]="generating()">Generate monthly periods</button>
        @if (generated() !== null) {
          <p role="status">Made <span data-testid="period-generated">{{ generated() }}</span> periods.</p>
        }
        @if (generateError(); as err) {
          <p role="alert" data-testid="period-generate-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class PeriodsPanel {
  private readonly sheets = inject(Sheets);
  protected readonly periods = inject(Periods);

  /** Each period's due-total, with two decimals, by period id. */
  readonly dueTotals = input.required<ReadonlyMap<number, string>>();

  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  /** The period being closed or reopened, so a second press waits for the first. */
  protected readonly closing = signal<number | null>(null);
  /** The error of the last action taken on a period's row, until the next action on that row. */
  protected readonly periodError = signal<{ id: number; message: string } | null>(null);

  protected readonly generateError = signal<string | null>(null);
  protected readonly generating = signal(false);
  /** How many periods the last generate made; null while one runs or before any. */
  protected readonly generated = signal<number | null>(null);

  protected readonly generateForm = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its periods from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.error.set(null);
          this.periodError.set(null);
          this.generateError.set(null);
          this.generated.set(null);
          return this.periods.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.error.set('Could not read the periods.') });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
    this.generateForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.generateError.set(null));
  }

  protected close(p: PeriodView): void {
    if (this.closing() === p.id) return;
    this.periodError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.closing.set(p.id);
    this.periods.close(sheet.id, p.name).subscribe({
      next: () => this.closing.set(null),
      error: (err: unknown) => {
        this.closing.set(null);
        this.periodError.set({
          id: p.id,
          message: err instanceof HttpErrorResponse && err.status === 403
            ? 'Your role may not close a period.'
            : err instanceof HttpErrorResponse && err.status === 409
            ? 'That period is already closed.'
            : 'Could not close that period.',
        });
      },
    });
  }

  protected reopen(p: PeriodView): void {
    if (this.closing() === p.id) return;
    this.periodError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.closing.set(p.id);
    this.periods.reopen(sheet.id, p.name).subscribe({
      next: () => this.closing.set(null),
      error: (err: unknown) => {
        this.closing.set(null);
        const status = err instanceof HttpErrorResponse ? err.status : 0;
        this.periodError.set({
          id: p.id,
          message: status === 403
            ? 'Your role may not reopen a period.'
            : status === 409
              ? 'That period is not closed.'
              : 'Could not reopen that period.',
        });
      },
    });
  }

  protected submit(): void {
    // A second submit before the first has answered is ignored, so it stores one record.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.form.getRawValue();
    const name = value.name.trim();
    const from = value.from.trim();
    const to = value.to.trim();
    if (name === '') {
      this.error.set('Type a name for the period.');
      return;
    }
    if (from !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.error.set('Choose a from-date, or leave it blank for today.');
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      this.error.set('Choose a to-date for the period.');
      return;
    }
    if (to < (from === '' ? sheet.today : from)) {
      this.error.set('A period cannot end before it starts.');
      return;
    }
    this.saving.set(true);
    this.periods.add(sheet.id, { name, from: from === '' ? null : from, to }).subscribe({
      next: () => {
        this.saving.set(false);
        this.form.reset({ name: '', from: '', to: '' }, { emitEvent: false });
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.error.set(
          err instanceof HttpErrorResponse && err.status === 409
            ? 'That period’s name is in use, or it overlaps another period.'
            : 'Could not add that period.',
        );
      },
    });
  }

  protected generate(): void {
    if (this.generating()) return;
    this.generateError.set(null);
    this.generated.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.generateForm.getRawValue();
    const from = value.from.trim();
    const to = value.to.trim();
    if (from !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.generateError.set('Choose a from-date, or leave it blank for today.');
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      this.generateError.set('Choose a to-date.');
      return;
    }
    if (to < (from === '' ? sheet.today : from)) {
      this.generateError.set('The to-date cannot be before the from-date.');
      return;
    }
    this.generating.set(true);
    this.periods.generate(sheet.id, from === '' ? null : from, to).subscribe({
      next: (result) => {
        this.generating.set(false);
        this.generated.set(result.made);
      },
      error: (err: unknown) => {
        this.generating.set(false);
        this.generateError.set(
          err instanceof HttpErrorResponse && err.status === 400
            ? 'The to-date cannot be before the from-date.'
            : 'Could not generate the periods.',
        );
      },
    });
  }
}
