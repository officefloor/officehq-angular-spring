import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Currency } from '../invoices/invoice-format';

export interface Client {
  id: number;
  name: string;
  email: string;
  // Payment terms in days; null means due at the end of the month after issue.
  termsDays: number | null;
  // The standard percentage discount new invoices for the client start with; 0 is none.
  discountPct: number;
  // Whether the client is tax exempt (a charity, say): its invoices charge no tax on any line.
  taxExempt: boolean;
  // Whether the client is quoted prices that include tax: the tax is taken out of its invoice amounts.
  pricesIncludeTax: boolean;
  // The currency the client is billed in: everything on its invoices is in it.
  currency: Currency;
  // An early-payment discount: the percentage of the amount before tax (0 is none) taken off an invoice
  // paid off within earlyPayDays of its issue date (null when there is none).
  earlyPayPct: number;
  earlyPayDays: number | null;
  // The standard retention percentage (0 to 20) new invoices for the client start with; 0 is none.
  retentionPct: number;
  // The percentage (0 to 100) of each invoice's amount before tax the client withholds for its tax
  // office; 0 is none. The client never pays it.
  withholdingPct: number;
  // The client's own minimum charge, in its currency (no conversion), charged on its invoices instead
  // of the workspace's; null for the workspace's, 0 for never charged a minimum.
  minimumCharge: number | null;
  // Days (0 to 30, a week as standard) past its due date an invoice or instalment is given before it
  // is overdue and earns late interest.
  graceDays: number;
  // The client's own annual late-interest rate (0 to 30 percent) charged instead of the workspace's;
  // null for the workspace's, 0 for never charged late interest.
  interestRate: number | null;
  // The region the client is in (a state, say): its invoices are taxed at the region's rate from the
  // workspace's regional rates when one is listed; null for no region.
  region: string | null;
  // The client's tax number without spaces: two letters then ten digits, the last two a check on the
  // first eight. With a valid one whose two letters are its region (outside the workspace's home region) its invoices are
  // reverse charged; null for no number.
  taxNumber: string | null;
}

export type NewClient = Omit<Client, 'id'>;

// The clients REST API.
@Injectable({ providedIn: 'root' })
export class ClientService {
  private readonly http = inject(HttpClient);

  list(): Observable<Client[]> {
    return this.http.get<Client[]>('/api/clients');
  }

  add(client: NewClient): Observable<Client> {
    return this.http.post<Client>('/api/clients', client);
  }
}

// Clients A to Z by name, ignoring capitals — the same order the server lists them in.
export function byName(a: Client, b: Client): number {
  const x = a.name.toLowerCase();
  const y = b.name.toLowerCase();
  return x < y ? -1 : x > y ? 1 : a.name < b.name ? -1 : a.name > b.name ? 1 : a.id - b.id;
}
