import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { finalize } from 'rxjs';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ClaimsService, claimDue, money } from '../../core/claims.service';
import { PeopleService } from '../../core/people.service';
import { Period, PeriodsService } from '../../core/periods.service';
import { RatesService } from '../../core/rates.service';
import { SettingsService } from '../../core/settings.service';
import { SheetService } from '../../core/sheet.service';

// The sheet's periods, each with its due-total: the sum of the dues (those with one) of the open and
// settled claims dated in it; once the period is closed, the due-total fixed when it closed. A form adds a period: a name unique on the sheet and a from and to date,
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
              <td data-testid="period-due-total">{{ p.closed ? p.closedTotal : money(dueTotals().get(p.id) ?? 0) }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : 'no' }}</td>
              <td>
                @if (!p.closed) {
                  <button type="button" data-testid="period-close" (click)="close(p)">Close</button>
                }
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
  private readonly claims = inject(ClaimsService);
  private readonly people = inject(PeopleService);
  private readonly rates = inject(RatesService);
  private readonly sheets = inject(SheetService);
  private readonly settings = inject(SettingsService);
  protected readonly money = money;
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
  /** Each period's due-total in cents, by period id: the dues of the counting claims dated in it. */
  protected readonly dueTotals = computed(() => {
    const grades = new Map(this.people.people().map((p) => [p.id, p.grade]));
    const out = new Map<number, number>();
    const floor = this.settings.floorCents();
    for (const c of this.claims.claims().filter(this.claims.live())) {
      const period = this.periods.periodOn(c.date);
      const grade = grades.get(c.personId);
      if (!period || !grade) continue;
      const d = claimDue(c, (date) => this.rates.rateOn(grade, date), floor);
      out.set(period.id, (out.get(period.id) ?? 0) + (d ?? 0));
    }
    return out;
  });

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
