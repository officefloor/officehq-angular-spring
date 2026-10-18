import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { GRADES, Grade } from '../../core/people.service';
import { RatesService } from '../../core/rates.service';
import { SheetService } from '../../core/sheet.service';

// The sheet's rate for each grade, and a form that sets a grade's rate.
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
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error()) {
          <p role="alert" data-testid="rate-form-error">{{ error() }}</p>
        }
      </form>
      <table>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="pctOf(g) === null ? null : 'rate-row-' + g + '-start'">
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">
                @if (pctOf(g); as pct) {
                  <span data-testid="rate-pct">{{ pct }}</span>
                } @else {
                  n/a
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Rates {
  private readonly rates = inject(RatesService);
  private readonly sheets = inject(SheetService);
  protected readonly grades = GRADES;
  protected readonly form = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
  });
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.sheets.loads();
      untracked(() => {
        this.reset();
        this.rates.reload();
      });
    });
  }

  protected pctOf(grade: Grade): string | null {
    return this.rates.rates().find((r) => r.grade === grade)?.pct ?? null;
  }

  private reset(): void {
    this.error.set('');
    this.form.reset({ grade: 'A', pct: '' }, { emitEvent: false });
  }

  protected submit(): void {
    this.error.set('');
    const { grade, pct } = this.form.getRawValue();
    const p = pct.trim();
    if (!/^\d{1,3}(\.\d{1,2})?$/.test(p)) {
      this.error.set('Enter a rate of 0.00 or more, up to 999.99, with up to two decimals.');
      return;
    }
    this.rates.set(grade, p).subscribe({
      next: () => this.reset(),
      error: () => this.error.set('Could not set that rate.'),
    });
  }
}
