import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** DRAFT when added; DRAFT → OPEN → SETTLED, and DRAFT or OPEN → VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** A move of a claim's status: open, settle or void. */
export type ClaimAction = 'open' | 'settle' | 'void';

export interface ClaimView {
  id: number;
  personId: number;
  /** The amount with two decimals, e.g. "10.25". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
  /**
   * The number given when the claim was opened: sheet-wide (e.g. "3"), or under period numbering
   * within its period (e.g. "P1-2", or "X-1" in no period); null while DRAFT, and kept once VOID.
   */
  number: string | null;
  /**
   * The due fixed when the claim was settled, with two decimals; null when it is not fixed (not
   * settled, or the grade had no rate then), so its due follows the current rate.
   */
  settledDue: string | null;
  /** The user who opened the claim; null until it is opened. The opener cannot settle it. */
  openedBy: string | null;
  /** Whether the claim is bound by its person's last statement to the figure it had then. */
  stated: boolean;
  /** The figure the statement bound the claim to, with two decimals; null when unbound, or when it was n/a. */
  statedDue: string | null;
  /** The number of the SETTLED claim this one corrects; null unless it is a correction. */
  corrects: string | null;
}

/** Whether a claim is a correction: its amount is below 0.00. It has no floor and no late charge. */
export function isCorrection(claim: ClaimView): boolean {
  return claim.amount.startsWith('-') && cents(claim.amount) !== 0;
}

/** Whether a claim counts toward what is claimed and due: only OPEN and SETTLED claims do. */
export function counts(claim: ClaimView): boolean {
  return claim.status === 'OPEN' || claim.status === 'SETTLED';
}

/** A claim's due-by (yyyy-mm-dd): its date plus the sheet's terms in days. */
export function dueBy(claim: ClaimView, terms: number): string {
  const [y, m, d] = claim.date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + terms)).toISOString().slice(0, 10);
}

/** Whether a claim is overdue: it is OPEN and its due-by is before the sheet's today. */
export function overdue(claim: ClaimView, terms: number, today: string): boolean {
  return claim.status === 'OPEN' && today !== '' && dueBy(claim, terms) < today;
}

/** What a late charge is worked from: the sheet's late percent, terms and today. */
export interface LateTerms {
  /** The late percent with two decimals. */
  pct: string;
  terms: number;
  /** The sheet's today (yyyy-mm-dd); empty when none is loaded. */
  today: string;
}

/**
 * Whether a claim's live figures round half up (else half to even): when the date it rounds by is
 * before the policy date, or the sheet's today when no policy date is set. A correction rounds by the
 * date of the claim it corrects, found by number among its person's claims (its own when not found).
 */
export function roundsHalfUp(claim: ClaimView, claims: ClaimView[], policyDate: string, today: string): boolean {
  const corrected = claim.corrects === null
    ? undefined
    : claims.find((c) => c.personId === claim.personId && c.number === claim.corrects);
  const date = corrected?.date ?? claim.date;
  const policy = policyDate || today;
  return policy !== '' && date < policy;
}

/** How many full 30 days an overdue claim is past its due-by on the sheet's today; 0 when not overdue. */
export function latePeriods(claim: ClaimView, late: LateTerms): number {
  if (!overdue(claim, late.terms, late.today)) return 0;
  const days = (Date.parse(late.today) - Date.parse(dueBy(claim, late.terms))) / 86_400_000;
  return Math.floor(days / 30);
}

/** What a bulk open did: the claims opened, as they are now, and how many were refused. */
export interface BulkOpenView {
  opened: ClaimView[];
  refused: number;
}

export interface ClaimForm {
  amount: string;
  /** Empty means the sheet's today (or, on an edit, the claim's own date). */
  date: string | null;
  note: string;
  /** The number of the claim a correction corrects; null on any other claim. */
  corrects: string | null;
}

/** An amount as a claim takes it: up to two decimals, below 0.00 only on a correction. */
export const AMOUNT = /^-?\d{1,12}(\.\d{1,2})?$/;

/** A claim number as the form takes it: sheet-wide (e.g. "3") or within a period (e.g. "P1-2", "X-1"). */
export const CLAIM_NUMBER = /^([1-9]\d{0,8}|\S.{0,200}-[1-9]\d{0,8})$/;

/** A two-decimal amount in whole cents, so sums are exact. */
export function cents(amount: string): number {
  const negative = amount.startsWith('-');
  const [whole, frac = ''] = (negative ? amount.slice(1) : amount).split('.');
  const abs = Number(whole) * 100 + Number(frac.padEnd(2, '0'));
  return negative ? -abs : abs;
}

/** Whole cents shown with two decimals. */
export function money(cents: number): string {
  const abs = Math.abs(cents);
  return `${cents < 0 ? '-' : ''}${Math.trunc(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

// The claims people on a sheet make, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class ClaimsApi {
  private readonly http = inject(HttpClient);

  /** The sheet's claims, newest first. */
  list(sheet: string): Promise<ClaimView[]> {
    return firstValueFrom(this.http.get<ClaimView[]>(`${this.url(sheet)}/claims`));
  }

  add(sheet: string, personId: number, form: ClaimForm): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/people/${personId}/claims`, form));
  }

  edit(sheet: string, id: number, form: ClaimForm): Promise<ClaimView> {
    return firstValueFrom(this.http.put<ClaimView>(`${this.url(sheet)}/claims/${id}`, form));
  }

  /**
   * Move a claim's status; refused (409) when its status does not allow the move,
   * or when the user settling it is the one who opened it.
   */
  act(sheet: string, id: number, action: ClaimAction): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/${action}`, null));
  }

  /**
   * Open the selected claims together, in list order (people in the order they were added, each
   * person's claims newest first); those that cannot be opened are refused and left as they are.
   */
  openAll(sheet: string, claimIds: number[]): Promise<BulkOpenView> {
    return firstValueFrom(this.http.post<BulkOpenView>(`${this.url(sheet)}/claims/open`, { claimIds }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
