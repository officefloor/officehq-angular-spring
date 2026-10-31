import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { PersonView } from './people';

/** DRAFT when added, then OPEN, then SETTLED; a DRAFT or OPEN claim can be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

export interface ClaimView {
  id: number;
  personId: number;
  /** The amount with exactly two decimals, e.g. "10.50". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number on its sheet, given when opened: sheet-wide ("1", "2", ...) or within its period
   *  ("P1-1", or "X-1" in no period) while the sheet numbers per period; null while a draft. */
  number: string | null;
  /** The due fixed when the claim was settled, with exactly two decimals; null while it is not
   *  settled, or if its grade had no rate then (its due then follows the rate as an open one does). */
  settledDue: string | null;
  /** The signed-in name of the user who opened the claim; null while a draft. */
  openedBy: string | null;
  /** Whether its person's last statement binds the claim, to `statedDue`. */
  stated: boolean;
  /** The figure the claim had at its person's last statement, while bound by it, with exactly two
   *  decimals; null when not bound, or if it had no figure then (it then follows the rate). */
  statedDue: string | null;
  /** The number of the SETTLED claim of the same person a correction (a negative amount) corrects; null on
   *  any other claim. */
  corrects: string | null;
}

/** Only OPEN and SETTLED claims count toward what is claimed and due. */
export function counts(c: ClaimView): boolean {
  return c.status === 'OPEN' || c.status === 'SETTLED';
}

/** Whether the claim counts in the live totals for a person held or not: while held, only SETTLED ones do. */
export function countsWhile(held: boolean): (c: ClaimView) => boolean {
  return held ? (c) => c.status === 'SETTLED' : counts;
}

/** The date (yyyy-mm-dd) that decides how a claim's live figures round: its own, or for a correction the date
 *  of the claim of the same person it corrects, found among that person's claims. */
export function roundingDate(
  c: Pick<ClaimView, 'date' | 'corrects'>,
  theirs: readonly Pick<ClaimView, 'date' | 'number'>[],
): string {
  if (c.corrects === null) return c.date;
  return theirs.find((o) => o.number === c.corrects)?.date ?? c.date;
}

/** The day a claim is due by (yyyy-mm-dd): its date plus the sheet's terms in days. */
export function dueBy(c: Pick<ClaimView, 'date'>, terms: number): string {
  const [y, m, d] = c.date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + terms)).toISOString().slice(0, 10);
}

/** Whether a claim is overdue on the sheet's today: OPEN and due by a day before it. */
export function isOverdue(c: Pick<ClaimView, 'date' | 'status'>, terms: number, today: string): boolean {
  return c.status === 'OPEN' && dueBy(c, terms) < today;
}

/** A claim as the form submits it: a blank date takes the sheet's today. A negative amount is a correction,
 *  which names in `corrects` the number of the claim it corrects. */
export interface ClaimForm {
  amount: string;
  date: string | null;
  note: string;
  corrects: string | null;
}

/** What a quick-add made: the person the claim is for, whether they were added by it, and the claim. */
export interface QuickAddView {
  person: PersonView;
  personAdded: boolean;
  claim: ClaimView;
}

/** What a bulk open did: how many it opened and refused, and the claims it opened. */
export interface BulkOpenView {
  opened: number;
  refused: number;
  claims: ClaimView[];
}

/** An amount the form accepts: 0.00 or more, with up to two decimals. */
export const AMOUNT_PATTERN = /^\d+(\.\d{1,2})?$/;

/** An amount the claim form accepts: AMOUNT_PATTERN, or a negative one (a correction). */
export const CLAIM_AMOUNT_PATTERN = /^-?\d+(\.\d{1,2})?$/;

/** An amount ("12.3", "4", "-0.5") in whole cents, so sums are exact. */
export function cents(amount: string): number {
  const negative = amount.startsWith('-');
  const [whole, frac = ''] = (negative ? amount.slice(1) : amount).split('.');
  const total = Number(whole) * 100 + Number(frac.padEnd(2, '0').slice(0, 2));
  return negative ? -total : total;
}

/** Whole cents as an amount with two decimals, e.g. "-0.30". */
export function money(total: number): string {
  const abs = Math.abs(total);
  return `${total < 0 ? '-' : ''}${Math.trunc(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

// The claims people on a sheet have made, listed newest first.
@Injectable({ providedIn: 'root' })
export class Claims {
  private readonly http = inject(HttpClient);

  list(sheetId: string): Observable<ClaimView[]> {
    return this.http.get<ClaimView[]>(`${this.base(sheetId)}/claims`);
  }

  add(sheetId: string, personId: number, claim: ClaimForm): Observable<ClaimView> {
    return this.http.post<ClaimView>(`${this.base(sheetId)}/people/${personId}/claims`, claim);
  }

  /** Add a DRAFT claim for the person named `name`, dated the sheet's today; one not on the sheet is
   *  added first with grade A. Refused as a whole when the person or the claim is. */
  quickAdd(sheetId: string, name: string, amount: string): Observable<QuickAddView> {
    return this.http.post<QuickAddView>(`${this.base(sheetId)}/quick-add`, { name, amount });
  }

  update(sheetId: string, id: number, claim: ClaimForm): Observable<ClaimView> {
    return this.http.put<ClaimView>(`${this.base(sheetId)}/claims/${id}`, claim);
  }

  changeStatus(sheetId: string, id: number, status: ClaimStatus): Observable<ClaimView> {
    return this.http.put<ClaimView>(`${this.base(sheetId)}/claims/${id}/status`, { status });
  }

  /** Open each of the claims that can be opened, numbered in list order; the rest are refused and left as they are. */
  bulkOpen(sheetId: string, ids: readonly number[]): Observable<BulkOpenView> {
    return this.http.post<BulkOpenView>(`${this.base(sheetId)}/claims/bulk-open`, { ids });
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }
}
