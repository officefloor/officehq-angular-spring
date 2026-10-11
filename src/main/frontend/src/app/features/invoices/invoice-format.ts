import { InvoiceStatus } from './invoice.service';

const STATUS_LABEL: Record<InvoiceStatus, string> = {
  DRAFT: 'Draft',
  SENT: 'Sent',
  PART_PAID: 'Part paid',
  PAID: 'Paid',
};
const MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

export function statusLabel(status: InvoiceStatus): string {
  return STATUS_LABEL[status];
}

// Money from the server is already to the cent, so the number shown is exact.
export function money(amount: string): string {
  return MONEY.format(Number(amount));
}
