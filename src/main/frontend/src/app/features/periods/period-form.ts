import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Period, Periods } from '../../core/periods';

const DAY = /^\d{4}-\d{2}-\d{2}$/;

// The periods on the sheet with each one's due-total, and the form that adds a period.
@Component({
  selector: 'app-period-form',
  imports: [ReactiveFormsModule],
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
        @if (generated() !== null) {
          <p role="status">Made <span data-testid="period-generated">{{ generated() }}</span></p>
        }
        @if (generateError()) {
          <p role="alert" data-testid="period-generate-error">{{ generateError() }}</p>
        }
      </form>
      <table data-testid="periods">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due</th><th scope="col">Closed</th><th scope="col">Fiscal year</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotals().get(p.id) ?? '0.00' }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</td>
              <td data-testid="period-fy">{{ p.fy }}</td>
              <td>
                @if (!p.closed) {
                  <button type="button" data-testid="period-close" (click)="close(p)"
                    [attr.aria-label]="'Close period ' + p.name">Close</button>
                  <button type="button" data-testid="period-soft-close" (click)="softClose(p)"
                    [attr.aria-label]="'Soft-close period ' + p.name">Soft-close</button>
                }
                <button type="button" data-testid="period-reopen" (click)="reopen(p)"
                  [attr.aria-label]="'Reopen period ' + p.name">Reopen</button>
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
  /** A period added through the form, or closed or reopened from its row. */
  readonly saved = output<Period>();

  protected readonly error = signal('');
  /** The refusal of the last action taken on a period's row, shown on that row. */
  protected readonly periodError = signal<{ id: number; message: string } | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  protected readonly generateError = signal('');
  /** How many periods the last generate made; null while one runs or before the first. */
  protected readonly generated = signal<number | null>(null);
  private generating = false;
  protected readonly generateForm = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
    year: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.generateForm.valueChanges.subscribe(() => this.generateError.set(''));
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
            : 'The period could not be reopened.',
      });
    }
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { name, from, to } = this.form.getRawValue();
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
    this.submitting = true;
    try {
      this.saved.emit(await this.api.add({ name: name.trim(), from: from.trim(), to: to.trim() }));
      this.form.reset({ name: '', from: '', to: '' }, { emitEvent: false });
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
