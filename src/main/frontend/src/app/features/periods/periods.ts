import { Component, effect, inject, signal, untracked } from '@angular/core';
import { finalize } from 'rxjs';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Period, PeriodsService } from '../../core/periods.service';
import { SheetService } from '../../core/sheet.service';
import { TotalsService } from '../../core/totals.service';

// The sheet's periods, each with its due-total: the sum of the dues (those with one) of the open and
// settled claims dated in it; once the period is closed, the due-total fixed when it closed (live again if a steward reopens it). A form adds a period: a name unique on the sheet and a from and to date,
// both inclusive, overlapping no other period.
@Component({
  selector: 'app-periods',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="periods-heading">
      <h2 id="periods-heading">Periods</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Add period">
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
      <table>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due</th><th scope="col">Closed</th><th scope="col">Actions</th></tr>
        </thead>
        <tbody>
          @for (p of periods.periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotals().get(p.id) }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : 'no' }}</td>
              <td>
                @if (!p.closed) {
                  <button type="button" data-testid="period-close" (click)="close(p)">Close</button>
                }
                <button type="button" data-testid="period-reopen" (click)="reopen(p)">Reopen</button>
                @if (rowErrors()[p.id]) {
                  <p role="alert" data-testid="period-error">{{ rowErrors()[p.id] }}</p>
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
  protected readonly periods = inject(PeriodsService);
  private readonly sheets = inject(SheetService);
  private readonly totals = inject(TotalsService);
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });
  protected readonly error = signal('');
  /** Each period's refused-action message, by period id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private saving = false;
  /** Each period's due-total as shown, by period id. */
  protected readonly dueTotals = this.totals.periodDueTotals;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.sheets.loads();
      untracked(() => {
        this.reset();
      });
    });
  }

  protected close(p: Period): void {
    this.rowErrors.update((all) => ({ ...all, [p.id]: '' }));
    this.periods.close(p.id).subscribe({
      error: () => this.rowErrors.update((all) => ({ ...all, [p.id]: 'Could not close that period.' })),
    });
  }

  protected reopen(p: Period): void {
    this.rowErrors.update((all) => ({ ...all, [p.id]: '' }));
    this.periods.reopen(p.id).subscribe({
      error: () => this.rowErrors.update((all) => ({
        ...all, [p.id]: 'Could not reopen that period: only a steward can reopen a closed period.',
      })),
    });
  }

  private reset(): void {
    this.error.set('');
    this.rowErrors.set({});
    this.form.reset({ name: '', from: '', to: '' }, { emitEvent: false });
  }

  protected submit(): void {
    if (this.saving) return;
    this.error.set('');
    const { name, from, to } = this.form.getRawValue();
    const n = name.trim();
    if (!n || !from || !to) {
      this.error.set('Enter a name, a from date and a to date.');
      return;
    }
    if (to < from) {
      this.error.set('A period cannot end before it starts.');
      return;
    }
    this.saving = true;
    this.periods.add({ name: n, from, to }).pipe(finalize(() => (this.saving = false))).subscribe({
      next: () => this.reset(),
      error: () => this.error.set('Could not add that period: its name is in use or it overlaps another.'),
    });
  }
}
