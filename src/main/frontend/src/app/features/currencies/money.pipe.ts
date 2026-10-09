import { Pipe, PipeTransform, inject } from '@angular/core';
import { formatNumber, getCurrencySymbol } from '@angular/common';
import { CurrencyService } from './currency.service';

/** Rounds an amount to the nearest multiple of the step (both in whole cents), halves away from zero. */
export function roundToStep(amount: number, step: number): number {
  const stepCents = Math.max(1, Math.round(step * 100));
  const cents = Math.round(Math.abs(amount) * 100);
  return (Math.sign(amount) * Math.round(cents / stepCents) * stepCents) / 100;
}

/**
 * Shows an amount in a currency: with the currency's symbol and rounded to the currency's rounding step
 * (e.g. to the nearest five cents). Pass `rounded` false for rates such as unit prices, which are shown as
 * they are. Impure so it picks up the currencies once they load or their rounding changes.
 */
@Pipe({ name: 'money', pure: false })
export class MoneyPipe implements PipeTransform {
  private readonly currencies = inject(CurrencyService);

  transform(value: number | null | undefined, code: string, digitsInfo = '1.2-2', rounded = true): string | null {
    if (value === null || value === undefined) {
      return null;
    }
    const currency = this.currencies.find(code);
    const symbol = currency?.symbol ?? getCurrencySymbol(code, 'wide', 'en-US');
    const amount = rounded ? roundToStep(value, currency?.roundingStep ?? 0.01) : value;
    const sign = amount < 0 ? '-' : '';
    return sign + symbol + formatNumber(Math.abs(amount), 'en-US', digitsInfo);
  }
}
