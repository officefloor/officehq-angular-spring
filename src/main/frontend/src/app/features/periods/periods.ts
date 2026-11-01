import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
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
  /** Once closed, no claim dated inside it can change. */
  closed: boolean;
  /** The due-total fixed when it was closed, two decimals; null while open. */
  closedDueTotal: string | null;
  /** Each person's due-total for it fixed when it was closed, two decimals, by person id; empty while open. */
  closedPersonDues: Record<string, string>;
}

/** Of the sheet's periods, the one whose dates contain the date, or null. */
export function periodOn(periods: Period[], date: string): Period | null {
  return periods.find((p) => p.from <= date && date <= p.to) ?? null;
}

/** Whether the date falls inside a closed period. */
export function closedOn(periods: Period[], date: string): boolean {
  return periodOn(periods, date)?.closed ?? false;
}

// The sheet's periods, with one form to add one, each period's due-total and whether it is
// closed, and a button to close each and to reopen each (a steward only).
@Component({
  selector: 'app-periods',
  imports: [ReactiveFormsModule],
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
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="period-form-error">{{ error() }}</p>
        }
      </form>
      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due total</th><th scope="col">Closed</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <td data-testid="period-name">{{ p.name }}</td>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotalOf()(p) }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : 'no' }}</td>
              <td>
                <button type="button" data-testid="period-close" (click)="close(p)"
                        [attr.aria-label]="'Close period ' + p.name">Close</button>
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
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  /** Close a period, fixing its due-total; a closed one cannot be closed again. */
  protected async close(p: Period): Promise<void> {
    this.periodError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const period = await firstValueFrom(
        this.http.post<Period>(`/api/sheets/${encodeURIComponent(sheet)}/periods/${p.id}/close`, null, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(period);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 423
        ? 'That period is already closed.'
        : 'Could not close that period.';
      this.periodError.set({ id: p.id, message });
    }
  }

  /** Reopen a closed period, making its due-total live again; a steward only. */
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
        ? 'Only a steward can reopen a period.'
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
    if (this.periods().some((p) => p.name === name)) {
      this.error.set('That name is already in use on this sheet.');
      return;
    }
    this.saving = true;
    try {
      const period = await firstValueFrom(
        this.http.post<Period>(`/api/sheets/${encodeURIComponent(sheet)}/periods`, { name, from, to }, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(period);
      this.form.reset({ name: '', from: '', to: '' }, { emitEvent: false });
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
