import { Component, effect, inject, signal, untracked } from '@angular/core';
import { finalize } from 'rxjs';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { GRADES, Grade } from '../../core/people.service';
import { RatesService } from '../../core/rates.service';
import { SheetService } from '../../core/sheet.service';

// Each grade's rate in force on the sheet's today, every rate the sheet has (by grade and from-date),
// and a form that sets a grade's rate from a date (none: from the beginning of time).
@Component({
  selector: 'app-rates',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Set rate">
        <label for="rate-form-grade">Grade</label>
        <select id="rate-form-grade" data-testid="rate-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input id="rate-form-pct" data-testid="rate-form-pct" formControlName="pct" inputmode="decimal" />
        <label for="rate-form-from">From (empty: always)</label>
        <input id="rate-form-from" type="date" data-testid="rate-form-from" formControlName="from" />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error()) {
          <p role="alert" data-testid="rate-form-error">{{ error() }}</p>
        }
      </form>
      <table>
        <caption>In force today</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ rates.current().get(g)?.pct ?? 'n/a' }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>All rates</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">From</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (r of rates.rates(); track r.grade + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td>{{ r.from ?? 'always' }}</td>
              <td data-testid="rate-pct">{{ r.pct }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Rates {
  protected readonly rates = inject(RatesService);
  private readonly sheets = inject(SheetService);
  protected readonly grades = GRADES;
  protected readonly form = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
  });
  protected readonly error = signal('');
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private saving = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.sheets.loads();
      untracked(() => {
        this.reset();
      });
    });
  }

  private reset(): void {
    this.error.set('');
    this.form.reset({ grade: 'A', pct: '', from: '' }, { emitEvent: false });
  }

  protected submit(): void {
    if (this.saving) return;
    this.error.set('');
    const { grade, pct, from } = this.form.getRawValue();
    const p = pct.trim();
    if (!/^\d{1,3}(\.\d{1,2})?$/.test(p)) {
      this.error.set('Enter a rate of 0.00 or more, up to 999.99, with up to two decimals.');
      return;
    }
    this.saving = true;
    this.rates.set(grade, p, from.trim() || null).pipe(finalize(() => (this.saving = false))).subscribe({
      next: () => this.reset(),
      error: () => this.error.set('Could not set that rate.'),
    });
  }
}
