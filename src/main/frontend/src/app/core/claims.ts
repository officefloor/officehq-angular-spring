import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

export interface Claim {
  id: number;
  personId: number;
  /** Always with two decimals, e.g. "2.50". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
  /** The sheet's number given when the claim was opened; null for a draft; a voided claim keeps it. */
  number: number | null;
  /** The due fixed when the claim was settled, two decimals; null if not settled or it had no rate then. */
  settledDue: string | null;
  /** The user who opened the claim; null while it has not been opened. That user cannot settle it. */
  openedBy: string | null;
}

/** DRAFT, then OPEN, then SETTLED; a DRAFT or OPEN claim can be VOID instead. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** The status changes a claim can be asked for, as named in the store's API. */
export type ClaimAction = 'open' | 'settle' | 'void';

/**
 * Whether the claim counts toward claimed, due-total and every total: an OPEN or SETTLED one, but
 * not an OPEN one while its person is on hold.
 */
export function counts(claim: Claim, person: { held: boolean }): boolean {
  return claim.status === 'SETTLED' || (claim.status === 'OPEN' && !person.held);
}

/** Days since 1970-01-01 of a yyyy-mm-dd date (proleptic Gregorian), without reading any clock. */
function epochDay(date: string): number {
  const [y0, m, d] = date.split('-').map(Number);
  const y = m <= 2 ? y0 - 1 : y0;
  const era = Math.floor(y / 400);
  const yoe = y - era * 400;
  const doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

/** The yyyy-mm-dd date of a count of days since 1970-01-01. */
function fromEpochDay(days: number): string {
  const z = days + 719468;
  const era = Math.floor(z / 146097);
  const doe = z - era * 146097;
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365);
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  const mp = Math.floor((5 * doy + 2) / 153);
  const d = doy - Math.floor((153 * mp + 2) / 5) + 1;
  const m = mp < 10 ? mp + 3 : mp - 9;
  const y = yoe + era * 400 + (m <= 2 ? 1 : 0);
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

/** A claim's due-by (yyyy-mm-dd): its date plus the sheet's terms in days. */
export function dueBy(claim: Claim, terms: number): string {
  return fromEpochDay(epochDay(claim.date) + terms);
}

/** Whether the claim is overdue: OPEN, with its due-by before the sheet's today. */
export function overdue(claim: Claim, terms: number, today: string): boolean {
  return claim.status === 'OPEN' && dueBy(claim, terms) < today;
}

export interface ClaimFields {
  amount: string;
  date: string;
  note: string;
}

/** An amount the store accepts: 0 or more, with at most two decimals. */
export const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;

/** Whole cents of a valid amount, so sums are exact. */
export function toCents(amount: string): number {
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number(frac.padEnd(2, '0'));
}

export function formatCents(cents: number): string {
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}

// The claims kept on the active sheet, newest first, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly all = signal<Claim[]>([]);

  /** Re-read the store; until it answers no claims are shown, so none is shown out of date. */
  async reload(): Promise<void> {
    this.all.set([]);
    try {
      this.all.set(await firstValueFrom(this.http.get<Claim[]>(`${this.sheetUrl()}/claims`, { headers: this.headers() })));
    } catch {
      this.all.set([]);
    }
  }

  async add(personId: number, fields: ClaimFields): Promise<void> {
    const added = await firstValueFrom(
      this.http.post<Claim>(`${this.sheetUrl()}/people/${personId}/claims`, this.body(fields), { headers: this.headers() }),
    );
    this.all.update((list) => [added, ...list]);
  }

  /**
   * The quick-add bar: a DRAFT claim of the amount, dated the sheet's today, for the person of the
   * name; the store adds the person first (grade A) when the name is not on the sheet.
   */
  async quickAdd(name: string, amount: string): Promise<Claim> {
    const added = await firstValueFrom(
      this.http.post<Claim>(`${this.sheetUrl()}/quick-add`, { name, amount: amount.trim() }, { headers: this.headers() }),
    );
    this.all.update((list) => [added, ...list]);
    return added;
  }

  async update(id: number, fields: ClaimFields): Promise<void> {
    const saved = await firstValueFrom(
      this.http.put<Claim>(`${this.sheetUrl()}/claims/${id}`, this.body(fields), { headers: this.headers() }),
    );
    this.all.update((list) => list.map((c) => (c.id === saved.id ? saved : c)));
  }

  /** Move a claim to its next status; the store refuses a change its status does not allow. */
  async act(id: number, action: ClaimAction): Promise<void> {
    const saved = await firstValueFrom(
      this.http.post<Claim>(`${this.sheetUrl()}/claims/${id}/${action}`, null, { headers: this.headers() }),
    );
    this.all.update((list) => list.map((c) => (c.id === saved.id ? saved : c)));
  }

  private body(fields: ClaimFields) {
    return { amount: fields.amount.trim(), date: fields.date || null, note: fields.note };
  }

  private sheetUrl(): string {
    const id = this.session.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return `/api/sheets/${encodeURIComponent(id)}`;
  }

  private headers(): Record<string, string> {
    const user = this.session.user();
    if (!user) throw new Error('not signed in');
    return { 'X-User': user };
  }
}
