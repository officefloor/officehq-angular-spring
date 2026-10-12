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
  // The last issue date (yyyy-mm-dd) a tax exemption covers: invoices issued after it are taxed as
  // usual; null when the exemption does not end. Only an exempt client has one.
  taxExemptUntil: string | null;
  // Whether the client is quoted prices that include tax: the tax is taken out of its invoice amounts.
  pricesIncludeTax: boolean;
  // The currency the client is billed in: everything on its invoices is in it.
  currency: Currency;
  // An early-payment discount: the percentage of the amount before tax (0 is none) taken off an invoice
  // paid off within earlyPayDays of its issue date (null when there is none).
  earlyPayPct: number;
  earlyPayDays: number | null;
  // The percentage (0 to 100) of each invoice's amount before tax the client withholds for its tax
  // office; 0 is none. The client never pays it.
  withholdingPct: number;
  // The client's own minimum charge, in its currency (no conversion), charged on its invoices instead
  // of the workspace's; null for the workspace's, 0 for never charged a minimum.
  minimumCharge: number | null;
  // The most the client may owe, in its currency: an invoice that would take its balance over it is
  // not sent; null for no limit.
  creditLimit: number | null;
  // The percentage (0 to 100) taken off every price the client's lines take from its rate card or the
  // price list, each band's price included; 0 is none. A price typed on a line is not reduced.
  rateDiscountPct: number;
  // Days (0 to 30) past its due date an invoice is given before it is overdue and earns
  // late interest; null for the grace period of the client's region, else a week.
  graceDays: number | null;
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
  // Whether the client is on hold, by hand or while over its credit limit: their invoices are not sent,
  // their credit is not applied, and their payments pay only their overdue invoices, the rest held as credit.
  onHold: boolean;
  // Whether the client's balance is over its credit limit: it stays on hold until it is back within it.
  overLimit: boolean;
  // How late the client pays: the days past the due date its invoices paid in full were paid (0 when on
  // time), averaged oldest payment first with an exponential weight of 0.5, to one decimal; null when nothing is paid in full.
  riskScore: string | null;
  // The payment risk band: LOW under 5 days late, MEDIUM under 15, HIGH from 15. Every invoice of a HIGH
  // client needs approval, and deposits held for it worth at least 30% of its grand total, before it is sent.
  riskBand: 'LOW' | 'MEDIUM' | 'HIGH';
  // The client's tier: a tier the workspace gives its own approval limit (in US dollars) uses it
  // instead of the workspace's approval limit.
  tier: ClientTier;
  // The percentage (0 to 100) the typed unit prices of the client's recurring invoices go up by on each
  // anniversary of the plan; 0 is none. Prices from the rate card or price list are not raised.
  upliftPct: number;
  // The credit limit that counts for the client: half of what is set (rounded down to the cent) for a
  // HIGH payment risk; null when it has no limit.
  effectiveCreditLimit: string | number | null;
}

export type ClientTier = 'STANDARD' | 'KEY' | 'NEW';

export type NewClient = Omit<Client, 'id' | 'onHold' | 'overLimit' | 'riskScore' | 'riskBand' | 'effectiveCreditLimit'>;

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

  // Puts the client on hold, or lifts the hold; answers the client.
  toggleHold(clientId: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${clientId}/hold`, null);
  }
}

// Clients A to Z by name, ignoring capitals — the same order the server lists them in.
export function byName(a: Client, b: Client): number {
  const x = a.name.toLowerCase();
  const y = b.name.toLowerCase();
  return x < y ? -1 : x > y ? 1 : a.name < b.name ? -1 : a.name > b.name ? 1 : a.id - b.id;
}
