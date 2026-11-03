import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Period, Periods } from '../../core/periods';
import { FigurePipe } from '../../core/figure';

const DAY = /^\d{4}-\d{2}-\d{2}$/;

// The periods on the sheet with each one's due-total, and the form that adds a period.
@Component({
  selector: 'app-period-form',
  imports: [ReactiveFormsModule, FigurePipe],
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>
      <form data-testid="period-form" [formGroup]="form" (ngSubmit)="submit()">
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
          <p role="alert" data-testid="period-form-error">{{ error() }}</p>
        }
      </form>
      <form data-testid="period-generate-form" [formGroup]="generateForm" (ngSubmit)="generate()">
        <label for="period-generate-from">Generate from</label>
        <input id="period-generate-from" type="date" data-testid="period-generate-from" formControlName="from" />
        <label for="period-generate-to">Generate to</label>
        <input id="period-generate-to" type="date" data-testid="period-generate-to" formControlName="to" />
        <button type="submit" data-testid="period-generate">Generate monthly periods</button>
        <label for="period-generate-fy-year">Fiscal year (yyyy)</label>
        <input id="period-generate-fy-year" data-testid="period-generate-fy-year" formControlName="year"
               inputmode="numeric" />
        <button type="button" data-testid="period-generate-fy" (click)="generateFy()">Generate fiscal year</button>
        <label for="period-generate-quarters-year">Calendar year (yyyy)</label>
        <input id="period-generate-quarters-year" data-testid="period-generate-quarters-year"
               formControlName="quartersYear" inputmode="numeric" />
        <button type="button" data-testid="period-generate-quarters" (click)="generateQuarters()">Generate quarters</button>
        @if (generated() !== null) {
          <p role="status">Made <span data-testid="period-generated">{{ generated() }}</span></p>
        }
        @if (generateError()) {
          <p role="alert" data-testid="period-generate-error">{{ generateError() }}</p>
        }
      </form>
      <form data-testid="rollover-form" [formGroup]="rolloverForm" (ngSubmit)="rollover()">
        <label for="rollover-year">Fiscal year to roll over (yyyy)</label>
        <input id="rollover-year" data-testid="rollover-year" formControlName="year" inputmode="numeric" />
        <button type="submit" data-testid="rollover">Roll year over</button>
        <button type="button" data-testid="rollover-undo" (click)="undoRollover()">Undo last rollover</button>
        @if (rolledOver() !== null) {
          <p role="status">Made <span data-testid="rollover-made">{{ rolledOver() }}</span></p>
        }
        @if (rolloverError()) {
          <p role="alert" data-testid="rollover-error">{{ rolloverError() }}</p>
        }
      </form>
      <form data-testid="opening-import-form" [formGroup]="openingForm" (ngSubmit)="importOpenings()">
        <label for="opening-import">Opening balances (name,period,amount per line)</label>
        <textarea id="opening-import" data-testid="opening-import" formControlName="text" rows="3"></textarea>
        <button type="submit" data-testid="opening-import-submit">Paste opening balances</button>
        @if (openingsDone() !== null) {
          <p role="status">Went through <span data-testid="opening-import-done">{{ openingsDone() }}</span></p>
        }
        @if (openingError()) {
          <p role="alert" data-testid="opening-error">{{ openingError() }}</p>
        }
      </form>
      <table data-testid="periods">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due</th><th scope="col">Closed</th><th scope="col">Fiscal year</th><th scope="col">Cap</th><th scope="col">Locked</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotals().get(p.id) ?? '0.00' | figure }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</td>
              <td data-testid="period-fy">{{ p.fy }}</td>
              <td data-testid="period-cap">{{ p.cap ?? '' | figure }}</td>
              <td data-testid="period-locked">{{ p.locked ? 'yes' : 'no' }}</td>
              <td data-testid="period-claims-count">{{ claimCounts().get(p.id) ?? 0 }}</td>
              <td>
                @if (!p.closed) {
                  <button type="button" data-testid="period-close" (click)="close(p)"
                    [attr.aria-label]="'Close period ' + p.name">Close</button>
                  <button type="button" data-testid="period-soft-close" (click)="softClose(p)"
                    [attr.aria-label]="'Soft-close period ' + p.name">Soft-close</button>
                }
                <button type="button" data-testid="period-reopen" (click)="reopen(p)"
                  [attr.aria-label]="'Reopen period ' + p.name">Reopen</button>
                <button type="button" data-testid="period-lock" (click)="setLocked(p, true)"
                  [attr.aria-label]="'Lock period ' + p.name">Lock</button>
                <button type="button" data-testid="period-unlock" (click)="setLocked(p, false)"
                  [attr.aria-label]="'Unlock period ' + p.name">Unlock</button>
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
export class PeriodForm {
  private readonly api = inject(Periods);

  /** The periods on the sheet, earliest first. */
  readonly periods = input.required<Period[]>();
  /** Each period's due-total by its id. */
  readonly dueTotals = input.required<Map<number, string>>();
  /** Each period's count of its claims (void and deleted left out), by the period's id. */
  readonly claimCounts = input.required<Map<number, number>>();
  /** A period added through the form, or closed, reopened, locked or unlocked from its row. */
  readonly saved = output<Period>();
  /** The id of a period removed, by undoing the rollover that generated it. */
  readonly removed = output<number>();

  protected readonly error = signal('');
  /** The refusal of the last action taken on a period's row, shown on that row. */
  protected readonly periodError = signal<{ id: number; message: string } | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
  });

  protected readonly generateError = signal('');
  /** How many periods the last generate made; null while one runs or before the first. */
  protected readonly generated = signal<number | null>(null);
  private generating = false;
  protected readonly generateForm = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
    year: new FormControl('', { nonNullable: true }),
    quartersYear: new FormControl('', { nonNullable: true }),
  });

  protected readonly rolloverError = signal('');
  /** How many periods the last rollover made; null while one runs or before the first. */
  protected readonly rolledOver = signal<number | null>(null);
  private rollingOver = false;
  protected readonly rolloverForm = new FormGroup({
    year: new FormControl('', { nonNullable: true }),
  });

  protected readonly openingError = signal('');
  /** How many lines the last opening balances paste put through; null while one runs or before the first. */
  protected readonly openingsDone = signal<number | null>(null);
  private importing = false;
  protected readonly openingForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.openingForm.valueChanges.subscribe(() => this.openingError.set(''));
    this.generateForm.valueChanges.subscribe(() => this.generateError.set(''));
    this.rolloverForm.valueChanges.subscribe(() => this.rolloverError.set(''));
  }

  protected async generate(): Promise<void> {
    if (this.generating) return;
    this.generateError.set('');
    this.generated.set(null);
    const from = this.generateForm.getRawValue().from.trim();
    const to = this.generateForm.getRawValue().to.trim();
    if ((from && !DAY.test(from)) || !DAY.test(to)) {
      this.generateError.set('Enter the day to generate to.');
      return;
    }
    if (from && to < from) {
      this.generateError.set('The to-date cannot be before the from-date.');
      return;
    }
    this.generating = true;
    try {
      const made = await this.api.generate(from, to);
      for (const p of made) this.saved.emit(p);
      this.generated.set(made.length);
    } catch {
      this.generateError.set('The periods could not be generated.');
    } finally {
      this.generating = false;
    }
  }

  /** Generate the twelve monthly periods of the fiscal year typed. */
  protected async generateFy(): Promise<void> {
    if (this.generating) return;
    this.generateError.set('');
    this.generated.set(null);
    const year = this.generateForm.getRawValue().year.trim();
    if (!/^\d{4}$/.test(year)) {
      this.generateError.set('Enter the fiscal year (yyyy) to generate.');
      return;
    }
    this.generating = true;
    try {
      const made = await this.api.generateFy(year);
      for (const p of made) this.saved.emit(p);
      this.generated.set(made.length);
    } catch {
      this.generateError.set('The fiscal year could not be generated.');
    } finally {
      this.generating = false;
    }
  }

  /** Generate the four calendar quarters of the year typed. */
  protected async generateQuarters(): Promise<void> {
    if (this.generating) return;
    this.generateError.set('');
    this.generated.set(null);
    const year = this.generateForm.getRawValue().quartersYear.trim();
    if (!/^\d{4}$/.test(year)) {
      this.generateError.set('Enter the calendar year (yyyy) to generate quarters for.');
      return;
    }
    this.generating = true;
    try {
      const made = await this.api.generateQuarters(year);
      for (const p of made) this.saved.emit(p);
      this.generated.set(made.length);
    } catch {
      this.generateError.set('The quarters could not be generated.');
    } finally {
      this.generating = false;
    }
  }

  /** Roll the fiscal year typed over into the next one. */
  protected async rollover(): Promise<void> {
    if (this.rollingOver) return;
    this.rolloverError.set('');
    this.rolledOver.set(null);
    const year = this.rolloverForm.getRawValue().year.trim();
    if (!/^\d{4}$/.test(year)) {
      this.rolloverError.set('Enter the fiscal year (yyyy) to roll over.');
      return;
    }
    this.rollingOver = true;
    try {
      const done = await this.api.rollover(year);
      for (const p of done.periods) this.saved.emit(p);
      this.rolledOver.set(done.made);
    } catch (e) {
      this.rolloverError.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward may roll a year over.'
          : 'The year could not be rolled over: it has no periods.',
      );
    } finally {
      this.rollingOver = false;
    }
  }

  /** Undo the last rollover: its periods go and the opening balances it set are taken back. */
  protected async undoRollover(): Promise<void> {
    if (this.rollingOver) return;
    this.rolloverError.set('');
    this.rollingOver = true;
    try {
      const done = await this.api.undoRollover();
      for (const id of done.removed) this.removed.emit(id);
      if (done.changed) this.saved.emit(done.changed);
    } catch (e) {
      this.rolloverError.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only the steward who made the rollover may undo it.'
          : 'The rollover could not be undone: there is none to undo today, or a claim now belongs to a period it made.',
      );
    } finally {
      this.rollingOver = false;
    }
  }

  /** Paste the opening balances typed: every line is checked first, so any error refuses them all. */
  protected async importOpenings(): Promise<void> {
    if (this.importing) return;
    this.openingError.set('');
    this.openingsDone.set(null);
    const text = this.openingForm.getRawValue().text;
    if (!text.trim()) {
      this.openingError.set('Paste at least one line of name,period,amount.');
      return;
    }
    this.importing = true;
    try {
      const imported = await this.api.importOpenings(text);
      for (const p of imported.periods) this.saved.emit(p);
      this.openingsDone.set(imported.added);
    } catch (e) {
      this.openingError.set(
        e instanceof HttpErrorResponse && typeof e.error?.message === 'string' && e.error.message.startsWith('Line ')
          ? e.error.message
          : 'The opening balances could not be pasted.',
      );
    } finally {
      this.importing = false;
    }
  }

  protected async close(p: Period): Promise<void> {
    this.periodError.set(null);
    try {
      this.saved.emit(await this.api.close(p.id));
    } catch {
      this.periodError.set({ id: p.id, message: 'The period could not be closed.' });
    }
  }

  protected async softClose(p: Period): Promise<void> {
    this.periodError.set(null);
    try {
      this.saved.emit(await this.api.softClose(p.id));
    } catch {
      this.periodError.set({ id: p.id, message: 'The period could not be soft-closed.' });
    }
  }

  protected async reopen(p: Period): Promise<void> {
    this.periodError.set(null);
    try {
      this.saved.emit(await this.api.reopen(p.id));
    } catch (e) {
      this.periodError.set({
        id: p.id,
        message:
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Only a steward may reopen a period.'
            : e instanceof HttpErrorResponse && e.status === 409 && p.locked
              ? 'A locked period cannot be reopened.'
              : 'The period could not be reopened.',
      });
    }
  }

  protected async setLocked(p: Period, lock: boolean): Promise<void> {
    this.periodError.set(null);
    const verb = lock ? 'lock' : 'unlock';
    try {
      this.saved.emit(await this.api.setLocked(p.id, lock));
    } catch (e) {
      this.periodError.set({
        id: p.id,
        message:
          e instanceof HttpErrorResponse && e.status === 403
            ? `Only a steward may ${verb} a period.`
            : lock ? 'The period is already locked.' : 'The period is not locked.',
      });
    }
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { name, from, to, cap } = this.form.getRawValue();
    if (!name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    if (!DAY.test(from.trim()) || !DAY.test(to.trim())) {
      this.error.set('Enter the day the period runs from and the day it runs to.');
      return;
    }
    if (to.trim() < from.trim()) {
      this.error.set('A period cannot end before it starts.');
      return;
    }
    if (cap.trim() && !(/^\d+$/.test(cap.trim()) && Number(cap.trim()) >= 1)) {
      this.error.set('The cap must be a whole number of 1 or more, or blank for none.');
      return;
    }
    this.submitting = true;
    try {
      this.saved.emit(
        await this.api.add({
          name: name.trim(),
          from: from.trim(),
          to: to.trim(),
          cap: cap.trim() ? Number(cap.trim()) : null,
        }),
      );
      this.form.reset({ name: '', from: '', to: '', cap: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That name is in use, or the period overlaps another on this sheet.'
          : 'The period could not be added.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
