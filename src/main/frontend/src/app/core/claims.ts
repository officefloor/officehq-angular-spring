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
  /**
   * The number given when the claim was opened: the sheet's next number, or its number within its
   * period (such as P1-1, or X-1 in no period); null for a draft; a voided claim keeps it.
   */
  number: string | null;
  /** The due fixed when the claim was settled, two decimals; null if not settled or it had no rate then. */
  settledDue: string | null;
  /** The user who opened the claim; null while it has not been opened. That user cannot settle it. */
  openedBy: string | null;
  /** The number of the settled claim this correction (a negative amount) corrects; null for any other claim. */
  corrects: string | null;
}

/** How many selected claims a bulk open opened, and how many it refused. */
export interface BulkResult {
  opened: number;
  refused: number;
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

/** How many days the claim's due-by is before the sheet's today; 0 when it is not. */
export function daysOverdue(claim: Claim, terms: number, today: string): number {
  return Math.max(0, epochDay(today) - epochDay(claim.date) - terms);
}

export interface ClaimFields {
  amount: string;
  date: string;
  note: string;
  /** The number of the settled claim a correction corrects, as typed; empty for none. */
  corrects: string;
}

/** An amount the store accepts: 0 or more, with at most two decimals. */
export const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;

/** A claim amount the store accepts: at most two decimals; a negative one is a correction. */
export const CLAIM_AMOUNT = /^-?\d{1,10}(\.\d{1,2})?$/;

/** A claim number, as typed in the claim form's corrects field: a sheet-wide 1, 2, ... or a period number such as P1-1. */
export const CLAIM_NUMBER = /^(\d{1,15}|\S.{0,99}-\d{1,15})$/;

/** Whether the claim is a correction: its amount is below 0. */
export function isCorrection(claim: { amount: string }): boolean {
  return claim.amount.trim().startsWith('-') && toCents(claim.amount) !== 0;
}

/** Whole cents of a valid amount (negative for a correction), so sums are exact. */
export function toCents(amount: string): number {
  const negative = amount.trim().startsWith('-');
  const [whole, frac = ''] = amount.trim().replace(/^-/, '').split('.');
  const cents = Number(whole) * 100 + Number(frac.padEnd(2, '0'));
  return negative ? -cents : cents;
}

export function formatCents(cents: number): string {
  const abs = Math.abs(cents);
  return `${cents < 0 ? '-' : ''}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

// The claims kept on the active sheet, newest first, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly all = signal<Claim[]>([]);
  /** The ids of the claims selected to be opened together. */
  readonly selected = signal<ReadonlySet<number>>(new Set());

  /** Re-read the store; until it answers no claims are shown, so none is shown out of date. */
  async reload(): Promise<void> {
    this.all.set([]);
    this.selected.set(new Set());
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

  select(id: number, on: boolean): void {
    this.selected.update((ids) => {
      const next = new Set(ids);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /**
   * Open the selected claims together; the store numbers them in list order and leaves each one it
   * cannot open as it is. Re-reads the claims and clears the selection.
   */
  async bulkOpen(): Promise<BulkResult> {
    const result = await firstValueFrom(
      this.http.post<BulkResult>(`${this.sheetUrl()}/claims/bulk-open`, { claimIds: [...this.selected()] }, { headers: this.headers() }),
    );
    await this.reload();
    return result;
  }

  private body(fields: ClaimFields) {
    const corrects = fields.corrects.trim();
    return { amount: fields.amount.trim(), date: fields.date || null, note: fields.note, corrects: corrects || null };
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
