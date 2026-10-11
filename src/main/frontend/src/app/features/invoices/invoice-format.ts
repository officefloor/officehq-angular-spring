import { InvoiceStatus } from './invoice.service';

const STATUS_LABEL: Record<InvoiceStatus, string> = {
  DRAFT: 'Draft',
  SENT: 'Sent',
  PART_PAID: 'Part paid',
  PAID: 'Paid',
  OVERDUE: 'Overdue',
  VOID: 'Void',
};
const MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

export function statusLabel(status: InvoiceStatus): string {
  return STATUS_LABEL[status];
}

// Money from the server is already to the cent, so the number shown is exact.
export function money(amount: string): string {
  return MONEY.format(Number(amount));
}

// A positive decimal with at most two decimals, e.g. 1, 1.5 or 12.35. The server applies the same rules.
export const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;

// A decimal string with at most two decimals in whole cents, so amounts add up exactly.
export function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}
