import { Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { Currency, CurrencyService } from './currency.service';

/** The usual rounding steps: to the cent, to five or ten cents, and so on up to the whole unit. */
const STEPS = [0.01, 0.05, 0.1, 0.25, 0.5, 1];

// How each currency rounds: amounts in a currency are shown rounded to its step, e.g. the nearest five cents.
@Component({
  selector: 'app-currency-rounding',
  imports: [DecimalPipe],
  template: `
    <section aria-labelledby="currency-rounding-heading" data-testid="currency-rounding">
      <h2 id="currency-rounding-heading">Currency rounding</h2>
      <p id="currency-rounding-hint">Amounts in each currency are shown rounded to the nearest multiple of its step.</p>
      @if (service.currencies.error()) {
        <p role="alert" data-testid="currency-rounding-load-error">Could not load the currencies.</p>
      } @else {
        <table data-testid="currency-rounding-table">
          <caption class="visually-hidden">Rounding step for each currency</caption>
          <thead>
            <tr>
              <th scope="col">Currency</th>
              <th scope="col">Symbol</th>
              <th scope="col">Round to</th>
              <th scope="col"><span class="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (c of service.list(); track c.code) {
              <tr [attr.data-testid]="'currency-rounding-row-' + c.code">
                <th scope="row" data-testid="currency-code">{{ c.code }}</th>
                <td data-testid="currency-symbol">{{ c.symbol }}</td>
                <td>
                  <label class="visually-hidden" [for]="'currency-rounding-step-' + c.code">Round {{ c.code }} to</label>
                  <select
                    [id]="'currency-rounding-step-' + c.code"
                    [attr.data-testid]="'currency-rounding-step-' + c.code"
                    aria-describedby="currency-rounding-hint"
                    (change)="choose(c.code, $any($event.target).value)"
                  >
                    @for (step of stepsFor(c); track step) {
                      <option [value]="step" [selected]="step === chosen(c)">{{ step | number: '1.2-2' : 'en-US' }}</option>
                    }
                  </select>
                </td>
                <td>
                  <button
                    type="button"
                    [attr.data-testid]="'currency-rounding-save-' + c.code"
                    [attr.aria-label]="'Save rounding for ' + c.code"
                    [disabled]="busy() === c.code"
                    (click)="save(c)"
                  >
                    Save
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
      @if (saved()) {
        <p role="status" data-testid="currency-rounding-saved">Rounding for {{ saved() }} saved.</p>
      }
      @if (saveError()) {
        <p role="alert" data-testid="currency-rounding-error">{{ saveError() }}</p>
      }
    </section>
  `,
})
export class CurrencyRounding {
  protected readonly service = inject(CurrencyService);

  // Steps picked but not yet saved, by currency code.
  private readonly picked = signal<Record<string, number>>({});
  protected readonly busy = signal<string | null>(null);
  protected readonly saved = signal<string | null>(null);
  protected readonly saveError = signal<string | null>(null);

  protected stepsFor(currency: Currency): number[] {
    return STEPS.includes(currency.roundingStep) ? STEPS : [...STEPS, currency.roundingStep].sort((a, b) => a - b);
  }

  protected chosen(currency: Currency): number {
    return this.picked()[currency.code] ?? currency.roundingStep;
  }

  protected choose(code: string, value: string): void {
    this.picked.update((p) => ({ ...p, [code]: Number(value) }));
  }

  protected save(currency: Currency): void {
    this.busy.set(currency.code);
    this.saved.set(null);
    this.saveError.set(null);
    this.service.changeRounding(currency.code, this.chosen(currency)).subscribe({
      next: (updated) => {
        this.picked.update(({ [updated.code]: _, ...rest }) => rest);
        this.busy.set(null);
        this.saved.set(updated.code);
      },
      error: () => {
        this.saveError.set(`Could not save the rounding for ${currency.code}. Please try again.`);
        this.busy.set(null);
      },
    });
  }
}
