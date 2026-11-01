import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, Observable, switchMap } from 'rxjs';
import { PeriodView, Periods } from '../../core/periods';
import { Sheets } from '../../core/sheet';
import { FigurePipe } from '../../core/figure';

// The sheet's periods, each with its due-total, and a form to add one.
@Component({
  selector: 'app-periods',
  imports: [ReactiveFormsModule, FigurePipe],
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>
      <ul aria-label="Periods of the sheet">
        @for (p of periods.list(); track p.id) {
          <li [attr.data-testid]="'period-row-' + p.name">
            <span data-testid="period-name">{{ p.name }}</span>
            from <span data-testid="period-from">{{ p.from }}</span>
            to <span data-testid="period-to">{{ p.to }}</span>
            — due <span data-testid="period-due-total">{{ (dueTotals().get(p.id) ?? '0.00') | figure }}</span>
            — <span data-testid="period-fy">{{ fiscalYearOf(p.from) }}</span>
            — cap <span data-testid="period-cap">{{ (p.cap ?? '') | figure }}</span>
            — closed <span data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</span>
            <button type="button" data-testid="period-close" (click)="close(p)" [disabled]="closing() === p.id"
                    [attr.aria-label]="'Close period ' + p.name">Close</button>
            <button type="button" data-testid="period-soft-close" (click)="softClose(p)" [disabled]="closing() === p.id"
                    [attr.aria-label]="'Soft-close period ' + p.name">Soft close</button>
            <button type="button" data-testid="period-reopen" (click)="reopen(p)" [disabled]="closing() === p.id"
                    [attr.aria-label]="'Reopen period ' + p.name">Reopen</button>
            — locked <span data-testid="period-locked">{{ p.locked ? 'yes' : 'no' }}</span>
            <button type="button" data-testid="period-lock" (click)="setLocked(p, true)" [disabled]="closing() === p.id"
                    [attr.aria-label]="'Lock period ' + p.name">Lock</button>
            <button type="button" data-testid="period-unlock" (click)="setLocked(p, false)" [disabled]="closing() === p.id"
                    [attr.aria-label]="'Unlock period ' + p.name">Unlock</button>
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
        <label for="period-form-cap">Open claims per person (blank: no cap)</label>
        <input id="period-form-cap" data-testid="period-form-cap" formControlName="cap" inputmode="numeric"
               autocomplete="off" />
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
        <label for="period-generate-fy-year">Fiscal year (the calendar year it starts in)</label>
        <input id="period-generate-fy-year" data-testid="period-generate-fy-year" formControlName="year"
               inputmode="numeric" autocomplete="off" />
        <button type="button" data-testid="period-generate-fy" (click)="generateFiscalYear()" [disabled]="generating()">
          Generate fiscal year
        </button>
        <label for="period-generate-quarters-year">Calendar year of the quarters</label>
        <input id="period-generate-quarters-year" data-testid="period-generate-quarters-year"
               formControlName="quartersYear" inputmode="numeric" autocomplete="off" />
        <button type="button" data-testid="period-generate-quarters" (click)="generateQuarters()"
                [disabled]="generating()">
          Generate quarters
        </button>
        @if (generated() !== null) {
          <p role="status">Made <span data-testid="period-generated">{{ generated() }}</span> periods.</p>
        }
        @if (generateError(); as err) {
          <p role="alert" data-testid="period-generate-error">{{ err }}</p>
        }
      </form>

      <form [formGroup]="rolloverForm" (ngSubmit)="rollover()" aria-label="Roll the year over">
        <label for="rollover-year">Fiscal year to roll over (the calendar year it starts in)</label>
        <input id="rollover-year" data-testid="rollover-year" formControlName="year" inputmode="numeric"
               autocomplete="off" />
        <button type="submit" data-testid="rollover" [disabled]="rollingOver()">Roll the year over</button>
        <button type="button" data-testid="rollover-undo" [disabled]="rollingOver()" (click)="undoRollover()">
          Undo the last rollover
        </button>
        @if (rolledOver() !== null) {
          <p role="status">Made <span data-testid="rollover-made">{{ rolledOver() }}</span> periods.</p>
        }
        @if (rolloverError(); as err) {
          <p role="alert" data-testid="rollover-error">{{ err }}</p>
        }
      </form>

      <form [formGroup]="openingForm" (ngSubmit)="importOpenings()" aria-label="Paste opening balances">
        <label for="opening-import">Opening balances, one per line as name,period,amount</label>
        <textarea id="opening-import" data-testid="opening-import" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="opening-import-submit" [disabled]="importingOpenings()">
          Add opening balances
        </button>
        @if (openingsAdded() !== null) {
          <p role="status">Added <span data-testid="opening-import-done">{{ openingsAdded() }}</span> lines.</p>
        }
        @if (openingError(); as err) {
          <p role="alert" data-testid="opening-error">{{ err }}</p>
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

  protected readonly rolloverError = signal<string | null>(null);
  protected readonly rollingOver = signal(false);
  /** How many periods the last rollover made; null while one runs or before any. */
  protected readonly rolledOver = signal<number | null>(null);

  protected readonly rolloverForm = new FormGroup({
    year: new FormControl('', { nonNullable: true }),
  });

  protected readonly openingError = signal<string | null>(null);
  protected readonly importingOpenings = signal(false);
  /** How many lines the last opening balance paste added; null while one runs or before any. */
  protected readonly openingsAdded = signal<number | null>(null);

  protected readonly openingForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });

  protected readonly generateForm = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
    year: new FormControl('', { nonNullable: true }),
    quartersYear: new FormControl('', { nonNullable: true }),
  });

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
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
          this.openingError.set(null);
          this.openingsAdded.set(null);
          return this.periods.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.error.set('Could not read the periods.') });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
    this.generateForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.generateError.set(null));
    this.openingForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.openingError.set(null));
  }

  /** Add an opening balance per pasted line; when any line is refused, none is added. */
  protected importOpenings(): void {
    if (this.importingOpenings()) return;
    // A press clears the last count, so two presses with the same result can be told apart.
    this.openingsAdded.set(null);
    this.openingError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const text = this.openingForm.getRawValue().text;
    if (text.trim() === '') {
      this.openingError.set('Paste at least one line of name,period,amount.');
      return;
    }
    this.importingOpenings.set(true);
    this.periods.importOpenings(sheet.id, text).subscribe({
      next: ({ added }) => {
        this.importingOpenings.set(false);
        this.openingsAdded.set(added);
      },
      error: () => {
        this.importingOpenings.set(false);
        this.openingError.set('A pasted line is not a valid opening balance; nothing was added.');
      },
    });
  }

  /** The fiscal year a date falls in, named FY<yyyy> by the calendar year its first day falls in. */
  protected fiscalYearOf(date: string): string {
    const start = this.sheets.current()?.fiscalStartMonth ?? 1;
    const year = Number(date.slice(0, 4));
    return `FY${Number(date.slice(5, 7)) >= start ? year : year - 1}`;
  }

  protected close(p: PeriodView): void {
    this.closeWith(p, (sheetId) => this.periods.close(sheetId, p.name));
  }

  protected softClose(p: PeriodView): void {
    this.closeWith(p, (sheetId) => this.periods.softClose(sheetId, p.name));
  }

  private closeWith(p: PeriodView, action: (sheetId: string) => Observable<PeriodView>): void {
    if (this.closing() === p.id) return;
    this.periodError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.closing.set(p.id);
    action(sheet.id).subscribe({
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
              ? 'That period is not closed, or it is locked.'
              : 'Could not reopen that period.',
        });
      },
    });
  }

  protected setLocked(p: PeriodView, locked: boolean): void {
    if (this.closing() === p.id) return;
    this.periodError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.closing.set(p.id);
    this.periods.setLocked(sheet.id, p.name, locked).subscribe({
      next: () => this.closing.set(null),
      error: (err: unknown) => {
        this.closing.set(null);
        const status = err instanceof HttpErrorResponse ? err.status : 0;
        this.periodError.set({
          id: p.id,
          message: status === 403
            ? `Only a steward may ${locked ? 'lock' : 'unlock'} a period.`
            : status === 409
              ? locked ? 'That period is already locked.' : 'That period is not locked.'
              : `Could not ${locked ? 'lock' : 'unlock'} that period.`,
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
    const cap = value.cap.trim();
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
    if (cap !== '' && (!/^\d+$/.test(cap) || Number(cap) < 1 || Number(cap) > 1_000_000)) {
      this.error.set('A cap is a whole number of open claims from 1, or blank for none.');
      return;
    }
    this.saving.set(true);
    this.periods.add(sheet.id, { name, from: from === '' ? null : from, to, cap: cap === '' ? null : Number(cap) }).subscribe({
      next: () => {
        this.saving.set(false);
        this.form.reset({ name: '', from: '', to: '', cap: '' }, { emitEvent: false });
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

  protected generateFiscalYear(): void {
    if (this.generating()) return;
    this.generateError.set(null);
    this.generated.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const year = this.generateForm.getRawValue().year.trim();
    if (!/^\d{4}$/.test(year) || Number(year) < 1 || Number(year) > 9998) {
      this.generateError.set('Type the fiscal year as the four-digit year it starts in.');
      return;
    }
    this.generating.set(true);
    this.periods.generateFiscalYear(sheet.id, Number(year)).subscribe({
      next: (result) => {
        this.generating.set(false);
        this.generated.set(result.made);
      },
      error: () => {
        this.generating.set(false);
        this.generateError.set('Could not generate the fiscal year\'s periods.');
      },
    });
  }

  protected rollover(): void {
    if (this.rollingOver()) return;
    this.rolloverError.set(null);
    this.rolledOver.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const year = this.rolloverForm.getRawValue().year.trim();
    if (!/^\d{4}$/.test(year) || Number(year) < 1 || Number(year) > 9998) {
      this.rolloverError.set('Type the fiscal year as the four-digit year it starts in.');
      return;
    }
    this.rollingOver.set(true);
    this.periods.rollover(sheet.id, Number(year)).subscribe({
      next: (result) => {
        this.rollingOver.set(false);
        this.rolledOver.set(result.made);
      },
      error: (err: unknown) => {
        this.rollingOver.set(false);
        this.rolloverError.set(
          err instanceof HttpErrorResponse && err.status === 403
            ? 'Only a steward may roll the year over.'
            : err instanceof HttpErrorResponse && err.status === 409
              ? 'That fiscal year has no periods on this sheet.'
              : 'Could not roll the year over.',
        );
      },
    });
  }

  protected undoRollover(): void {
    if (this.rollingOver()) return;
    this.rolloverError.set(null);
    this.rolledOver.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.rollingOver.set(true);
    this.periods.undoRollover(sheet.id).subscribe({
      next: () => this.rollingOver.set(false),
      error: (err: unknown) => {
        this.rollingOver.set(false);
        this.rolloverError.set(
          err instanceof HttpErrorResponse && err.status === 403
            ? 'Only the steward who rolled the year over may undo it.'
            : err instanceof HttpErrorResponse && err.status === 409
              ? 'The last rollover cannot be undone: it was undone already, made on another day, or a claim is in a period it made.'
              : 'Could not undo the rollover.',
        );
      },
    });
  }

  protected generateQuarters(): void {
    if (this.generating()) return;
    this.generateError.set(null);
    this.generated.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const year = this.generateForm.getRawValue().quartersYear.trim();
    if (!/^\d{4}$/.test(year) || Number(year) < 1) {
      this.generateError.set('Type the year of the quarters as four digits.');
      return;
    }
    this.generating.set(true);
    this.periods.generateQuarters(sheet.id, Number(year)).subscribe({
      next: (result) => {
        this.generating.set(false);
        this.generated.set(result.made);
      },
      error: () => {
        this.generating.set(false);
        this.generateError.set('Could not generate the quarters.');
      },
    });
  }
}
