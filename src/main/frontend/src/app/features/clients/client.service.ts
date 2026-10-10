import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/** A currency a client can be billed in, by ISO 4217 code; the known ones come from the currency service. */
export type CurrencyCode = string;

export interface Client {
  id: number;
  name: string;
  email: string;
  /** A number to reach the client on; null when not known. */
  phone: string | null;
  /** The client's tax registration number; null when they are not tax registered. */
  taxNumber: string | null;
  /** Where the client's bills are sent; null when not known. */
  billingAddress: string | null;
  /** The language the client prefers to be dealt with in; null when not known. */
  language: string | null;
  /** The person in the office who looks after the client; null when not recorded. */
  accountManager: string | null;
  /** Who the client's bills should go to, when someone other than the client themselves; null when not recorded. */
  billingContact: string | null;
  /** The segment the client is grouped into (e.g. "VIP"); null when in none. */
  segment: string | null;
  /** Whether the client's prices already include tax, so it is worked back out of them rather than added on. */
  taxInclusive: boolean;
  /** Whether the client is tax exempt, so none of their invoices carry any tax whatever the lines say. */
  taxExempt: boolean;
  /** Whether the client is one of the office's key accounts, marked out wherever they are listed. */
  keyAccount: boolean;
  /** The client's standard discount, a percentage each new invoice for them starts with; 0 when none. */
  defaultDiscountPct: number;
  archived: boolean;
  /** The client's main contact; null until one is chosen. */
  primaryContact: { id: number; name: string } | null;
  /** The currency the client is billed in; all of their money is shown in it. */
  currency: CurrencyCode;
  /** The most the client may owe, in their currency; null when no limit is set. */
  creditLimit: number | null;
  /** The number of days the client has to pay an invoice (e.g. 30 for net 30); null when no terms are agreed. */
  paymentTermsDays: number | null;
  /** Part of the payment terms: the days after issue within which paying earns an invoice's early-payment discount; null when not agreed. */
  earlyPaymentWindowDays: number | null;
  /** What is left to pay on the client's sent, not yet fully paid invoices, in their currency. */
  outstanding: number;
  /** Whether the client is pinned to the top of the client list. */
  pinned: boolean;
}

/** At-a-glance counts of what one client has. */
export interface ClientSummary {
  projectCount: number;
  contactCount: number;
  /** The total billed to the client, net of credit notes and write-offs, in their currency. */
  lifetimeBilled: number;
  /** What the client has actually paid, less refunds, in their currency. */
  lifetimeValue: number;
}

export type NewClient = Omit<Client, 'id' | 'archived' | 'primaryContact' | 'currency' | 'creditLimit' | 'paymentTermsDays' | 'earlyPaymentWindowDays' | 'outstanding' | 'pinned'>;

/** A segment clients are grouped into, and how many clients are in it. */
export interface ClientSegment {
  segment: string;
  count: number;
}

/** The exported client list file and how many clients it holds. */
export interface ClientListExport {
  count: number;
  csv: string;
}

/** Where a client's contact details file is downloaded from. */
export function exportUrl(id: number): string {
  return `/api/clients/${id}/export`;
}

@Injectable({ providedIn: 'root' })
export class ClientService {
  private readonly http = inject(HttpClient);

  list(includeArchived = false): Observable<Client[]> {
    return this.http.get<Client[]>('/api/clients', { params: { includeArchived } });
  }

  /** How many clients, not archived, are in each segment. */
  segments(): Observable<ClientSegment[]> {
    return this.http.get<ClientSegment[]>('/api/clients/segments');
  }

  get(id: number): Observable<Client> {
    return this.http.get<Client>(`/api/clients/${id}`);
  }

  summary(id: number): Observable<ClientSummary> {
    return this.http.get<ClientSummary>(`/api/clients/${id}/summary`);
  }

  /** The client list as a CSV file, with how many clients it holds. */
  exportList(includeArchived = false): Observable<ClientListExport> {
    return this.http
      .get('/api/clients/export', { params: { includeArchived }, observe: 'response', responseType: 'text' })
      .pipe(map((res) => ({ count: Number(res.headers.get('X-Export-Count') ?? 0), csv: res.body ?? '' })));
  }

  /** The client's contact details as the CSV file the export downloads. */
  exportContactDetails(id: number): Observable<string> {
    return this.http.get(exportUrl(id), { responseType: 'text' });
  }

  create(client: NewClient): Observable<Client> {
    return this.http.post<Client>('/api/clients', client);
  }

  update(id: number, client: NewClient): Observable<Client> {
    return this.http.put<Client>(`/api/clients/${id}`, client);
  }

  changeCurrency(id: number, currency: CurrencyCode): Observable<Client> {
    return this.http.put<Client>(`/api/clients/${id}/currency`, { currency });
  }

  /** Sets the most the client may owe, in their currency; null removes the limit. */
  changeCreditLimit(id: number, creditLimit: number | null): Observable<Client> {
    return this.http.put<Client>(`/api/clients/${id}/credit-limit`, { creditLimit });
  }

  /** Sets the number of days the client has to pay an invoice; null removes their payment terms. */
  changePaymentTerms(id: number, paymentTermsDays: number | null): Observable<Client> {
    return this.http.put<Client>(`/api/clients/${id}/payment-terms`, { paymentTermsDays });
  }

  /** Sets the early-payment window in the client's payment terms; null removes it. */
  changeEarlyPaymentWindow(id: number, earlyPaymentWindowDays: number | null): Observable<Client> {
    return this.http.put<Client>(`/api/clients/${id}/early-payment-window`, { earlyPaymentWindowDays });
  }

  /** Merges the client into another one, which keeps everything the client had; the client is removed. */
  merge(id: number, targetId: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${id}/merge`, { targetId });
  }

  archive(id: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${id}/archive`, null);
  }

  restore(id: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${id}/restore`, null);
  }

  /** Pins the client to the top of the client list. */
  pin(id: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${id}/pin`, null);
  }

  /** Takes the client's pin off, returning it to its place in the list. */
  unpin(id: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${id}/unpin`, null);
  }
}
