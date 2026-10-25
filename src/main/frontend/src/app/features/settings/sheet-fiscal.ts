import { Injectable, signal } from '@angular/core';

/** The fiscal year start month while the sheet sets none. */
export const DEFAULT_FISCAL_START_MONTH = 1;

// The loaded sheet's fiscal year start month, shared by the screens that name a date's fiscal year.
// It is not dated.
@Injectable({ providedIn: 'root' })
export class SheetFiscal {
  /** The month, 1 to 12, each fiscal year starts on. */
  readonly startMonth = signal(DEFAULT_FISCAL_START_MONTH);
}

/** The fiscal year a date (yyyy-mm-dd) falls in, named FY<yyyy> by the calendar year its first day falls in. */
export function fiscalYearOf(date: string, startMonth: number): string {
  const year = Number(date.slice(0, 4));
  const month = Number(date.slice(5, 7));
  return `FY${month >= startMonth ? year : year - 1}`;
}
