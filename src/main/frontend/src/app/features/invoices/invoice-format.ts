import { InvoiceStatus, MilestoneStatus } from './invoice.service';

const STATUS_LABEL: Record<InvoiceStatus, string> = {
  DRAFT: 'Draft',
  SENT: 'Sent',
  PART_PAID: 'Part paid',
  PAID: 'Paid',
  PAID_LATE: 'Paid late',
  OVERDUE: 'Overdue',
  VOID: 'Void',
  CREDIT_NOTE: 'Credit note',
  CREDITED: 'Credited',
  WRITTEN_OFF: 'Written off',
  DISPUTED: 'Disputed',
  AWAITING_MILESTONE: 'Awaiting milestone',
  PROMISED: 'Promised',
};
const MILESTONE_STATUS_LABEL: Record<MilestoneStatus, string> = {
  PENDING: 'Pending',
  PAID: 'Paid',
  PART_PAID: 'Part paid',
  OVERDUE: 'Overdue',
  DUE: 'Due',
  DISPUTED: 'Disputed',
};
// The currencies a client can be billed in.
export type Currency = 'USD' | 'EUR' | 'GBP' | 'JPY' | 'CHF';
export const CURRENCIES: readonly Currency[] = ['USD', 'EUR', 'GBP', 'JPY', 'CHF'];
// The symbol each currency's amounts are shown with, and how many decimals they have (yen none).
const SYMBOL: Record<Currency, string> = { USD: '$', EUR: '€', GBP: '£', JPY: '¥', CHF: 'CHF ' };
const NUMBER: Record<number, Intl.NumberFormat> = {
  0: new Intl.NumberFormat('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 0 }),
  2: new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
};

// How many decimals amounts in the currency have: 2, to the cent, or 0 for whole yen.
export function decimalsOf(currency: Currency): number {
  return currency === 'JPY' ? 0 : 2;
}

export function statusLabel(status: InvoiceStatus): string {
  return STATUS_LABEL[status];
}

export function milestoneStatusLabel(status: MilestoneStatus): string {
  return MILESTONE_STATUS_LABEL[status];
}

// Money from the server is already to the cent (whole yen for yen), so the number shown is exact:
// $1,234.50, €1,234.50, £1,234.50, ¥1,235, CHF 1,234.50.
export function money(amount: string, currency: Currency = 'USD'): string {
  const value = Number(amount);
  const shown = NUMBER[decimalsOf(currency)].format(Math.abs(value));
  return (value < 0 ? '-' : '') + SYMBOL[currency] + shown;
}

// The symbol amounts in the currency are entered in, for form labels.
export function symbolOf(currency: Currency): string {
  return SYMBOL[currency].trim();
}

// A positive decimal with at most two decimals, e.g. 1, 1.5 or 12.35. The server applies the same rules.
export const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;

// A decimal string with at most two decimals in whole cents, so amounts add up exactly.
export function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}
