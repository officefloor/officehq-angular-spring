import { Component, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { FxRateService } from './fx-rate.service';
import { CurrencyService } from '../currencies/currency.service';

// The exchange rate history: record what one unit of a currency is worth in the home currency from a date.
@Component({
  selector: 'app-fx-rates',
  imports: [ReactiveFormsModule, DecimalPipe],
  template: `
    <section aria-labelledby="fx-rates-heading" data-testid="fx-rates">
      <h2 id="fx-rates-heading">Exchange rates</h2>
      <p id="fx-rates-hint">The value of one unit of the currency in the home currency, from the date onwards.</p>
      <form [formGroup]="form" (ngSubmit)="save()" data-testid="fx-rate-form" novalidate>
        <div>
          <label for="fx-rate-form-currency">Currency</label>
          <input
            id="fx-rate-form-currency"
            type="text"
            list="fx-rate-currencies"
            autocomplete="off"
            maxlength="3"
            formControlName="currency"
            data-testid="fx-rate-form-currency"
            [attr.aria-invalid]="invalid('currency')"
            [attr.aria-describedby]="invalid('currency') ? 'fx-rate-form-currency-error' : null"
          />
          <datalist id="fx-rate-currencies">
            @for (c of currencies.list(); track c.code) {
              <option [value]="c.code"></option>
            }
          </datalist>
          @if (invalid('currency')) {
            <p id="fx-rate-form-currency-error" role="alert" data-testid="fx-rate-form-currency-error">
              Enter a three-letter currency code.
            </p>
          }
        </div>
        <div>
          <label for="fx-rate-form-date">Date</label>
          <input
            id="fx-rate-form-date"
            type="date"
            formControlName="date"
            data-testid="fx-rate-form-date"
            [attr.aria-invalid]="invalid('date')"
            [attr.aria-describedby]="invalid('date') ? 'fx-rate-form-date-error' : null"
          />
          @if (invalid('date')) {
            <p id="fx-rate-form-date-error" role="alert" data-testid="fx-rate-form-date-error">Enter a date.</p>
          }
        </div>
        <div>
          <label for="fx-rate-form-rate">Rate</label>
          <input
            id="fx-rate-form-rate"
            type="number"
            inputmode="decimal"
            min="0"
            step="any"
            formControlName="rate"
            data-testid="fx-rate-form-rate"
            [attr.aria-invalid]="invalid('rate')"
            [attr.aria-describedby]="invalid('rate') ? 'fx-rates-hint fx-rate-form-rate-error' : 'fx-rates-hint'"
          />
          @if (invalid('rate')) {
            <p id="fx-rate-form-rate-error" role="alert" data-testid="fx-rate-form-rate-error">
              Enter a rate above zero with at most six decimal places.
            </p>
          }
        </div>
        <button type="submit" data-testid="fx-rate-form-submit" [disabled]="saving()">Record rate</button>
        @if (saved()) {
          <p role="status" data-testid="fx-rate-saved">Rate recorded.</p>
        }
        @if (saveError()) {
          <p role="alert" data-testid="fx-rate-form-error">{{ saveError() }}</p>
        }
      </form>
      @if (rates.error()) {
        <p role="alert" data-testid="fx-rates-load-error">Could not load the exchange rates.</p>
      } @else if (rates.hasValue() && rates.value().length === 0) {
        <p data-testid="fx-rates-empty">No exchange rates recorded yet.</p>
      } @else if (rates.hasValue()) {
        <table data-testid="fx-rates-table">
          <caption class="visually-hidden">Exchange rate history</caption>
          <thead>
            <tr>
              <th scope="col">Currency</th>
              <th scope="col">From</th>
              <th scope="col">Rate</th>
            </tr>
          </thead>
          <tbody>
            @for (r of rates.value(); track r.id) {
              <tr [attr.data-testid]="'fx-rate-row-' + r.id">
                <th scope="row" data-testid="fx-rate-currency">{{ r.currency }}</th>
                <td data-testid="fx-rate-date">{{ r.date }}</td>
                <td data-testid="fx-rate-value">{{ r.rate | number: '1.2-6' : 'en-US' }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
})
export class FxRates {
  private readonly service = inject(FxRateService);
  protected readonly currencies = inject(CurrencyService);

  protected readonly rates = rxResource({ stream: () => this.service.list() });

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    currency: ['', [Validators.required, Validators.pattern(/^\s*[A-Za-z]{3}\s*$/)]],
    date: ['', Validators.required],
    rate: ['', [Validators.required, Validators.pattern(/^\d+(\.\d{1,6})?$/), Validators.min(0.000001)]],
  });

  protected invalid(name: 'currency' | 'date' | 'rate'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || control.dirty);
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saved.set(false);
    this.saveError.set(null);
    const { currency, date, rate } = this.form.getRawValue();
    this.service.record({ currency: currency.trim().toUpperCase(), date, rate: Number(rate) }).subscribe({
      next: () => {
        this.rates.reload();
        this.form.reset();
        this.saving.set(false);
        this.saved.set(true);
      },
      error: (err: HttpErrorResponse) => {
        this.saveError.set(
          err.status === 409
            ? 'A rate for this currency on this date is already recorded.'
            : err.status === 400
              ? 'Could not record the rate: check the currency is known and is not the home currency.'
              : 'Could not record the rate. Please try again.',
        );
        this.saving.set(false);
      },
    });
  }
}
