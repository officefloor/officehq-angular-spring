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
 * Fits digits info such as '1.2-4' to a currency's decimal places: the minimum fraction digits become the
 * currency's, and the maximum is never fewer than that.
 */
export function digitsFor(digitsInfo: string, decimals: number): string {
  const match = /^(\d+)\.(\d+)-(\d+)$/.exec(digitsInfo);
  if (!match) {
    return digitsInfo;
  }
  return `${match[1]}.${decimals}-${Math.max(decimals, Number(match[3]))}`;
}

/**
 * Shows an amount in a currency: with the currency's symbol and decimal places (none for yen), rounded to
 * the currency's rounding step (e.g. to the nearest five cents) and never finer than its smallest unit.
 * Pass `rounded` false for rates such as unit prices, which are shown as they are. Without explicit digits
 * info an amount is shown with exactly the currency's decimal places. Impure so it picks up the currencies
 * once they load or their rounding changes.
 */
@Pipe({ name: 'money', pure: false })
export class MoneyPipe implements PipeTransform {
  private readonly currencies = inject(CurrencyService);

  transform(value: number | null | undefined, code: string, digitsInfo?: string, rounded = true): string | null {
    if (value === null || value === undefined) {
      return null;
    }
    const currency = this.currencies.find(code);
    const symbol = currency?.symbol ?? getCurrencySymbol(code, 'wide', 'en-US');
    const decimals = currency?.decimals ?? 2;
    const step = Math.max(currency?.roundingStep ?? 0.01, 10 ** -decimals);
    const amount = rounded ? roundToStep(value, step) : value;
    const digits = digitsInfo ? digitsFor(digitsInfo, decimals) : `1.${decimals}-${decimals}`;
    const sign = amount < 0 ? '-' : '';
    return sign + symbol + formatNumber(Math.abs(amount), 'en-US', digits);
  }
}
