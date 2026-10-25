import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RateView, dueCents, rateInForce } from '../rates/rates-api';

export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';
export type ClaimAction = 'open' | 'settle' | 'void';

/** An amount as a form takes it: 0.00 or more, with up to two decimals. */
export const AMOUNT = /^\d+(\.\d{1,2})?$/;

/** An amount as the claim form takes it: as AMOUNT, or negative for a correction. */
export const CLAIM_AMOUNT = /^-?\d+(\.\d{1,2})?$/;

/** A claim number as a form takes it: a sheet-wide whole number of 1 or more, or a number within a
 *  period, <period name>-<n> (X-<n> for a claim in no period). */
export const CLAIM_NUMBER = /^([1-9]\d{0,8}|.{1,200}-[1-9]\d{0,8})$/;

/** Whether the claim is a correction: its amount is negative. */
export function isCorrection(claim: ClaimView): boolean {
  return cents(claim.amount) < 0;
}

/** Only open and settled claims count toward what is claimed and due. */
export function counts(claim: ClaimView): boolean {
  return claim.status === 'OPEN' || claim.status === 'SETTLED';
}

export interface ClaimView {
  id: number;
  personId: number;
  /** The amount with two decimals, as the store holds it. */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number, given when it is opened and kept once void; null while a draft. The sheet-wide
   *  1, 2, ..., or while period numbering is in force <period name>-<n>, or X-<n> in no period. */
  number: string | null;
  /** The due fixed when the claim was settled, with two decimals; null while its due is live. */
  settledDue: string | null;
  /** Who opened the claim; null when it was never opened. */
  openedBy: string | null;
  /** Whether the person's last statement bound the claim to a figure. */
  stated: boolean;
  /** The figure the last statement bound the claim to, with two decimals; null when unbound or n/a then. */
  statedDue: string | null;
  /** The number of the settled claim a correction corrects; null when the claim is not a correction. */
  corrects: string | null;
}

/** What opening selected claims together did: how many were opened and refused, and the claims opened. */
export interface BulkOpenView {
  opened: number;
  refused: number;
  claims: ClaimView[];
}

export interface ClaimForm {
  amount: string;
  /** null takes the sheet's today. */
  date: string | null;
  note: string;
  /** The number of the settled claim of the same person a correction corrects; null for none. */
  corrects: string | null;
}

/** Whether the claim keeps a figure whatever the rates: one fixed when it was settled, or bound by a statement. */
export function keepsFigure(claim: ClaimView): boolean {
  return claim.settledDue !== null || (claim.stated && claim.statedDue !== null);
}

/** An amount in whole cents, so sums of amounts are exact. */
export function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** Cents as the screen shows an amount: two decimals. */
export function money(total: number): string {
  return (total / 100).toFixed(2);
}

/** Whether the claim's live figures round half up: the date that decides it (for a correction, the
 *  date of the claim of `claims` it corrects; else its own) is before the policy date. Otherwise they
 *  round half to even. */
export function roundsHalfUp(claim: ClaimView, claims: readonly ClaimView[], policyDate: string | undefined): boolean {
  if (policyDate === undefined) return false;
  const corrected = isCorrection(claim) && claim.corrects !== null
    ? claims.find((c) => c.personId === claim.personId && c.number === claim.corrects)
    : undefined;
  return (corrected ?? claim).date < policyDate;
}

/** What is due on the claim, in cents: the figure fixed when it was settled, else the figure its
 *  person's last statement bound it to, else at the rate of `gradeRates` (its person's grade's rates)
 *  in force on the claim's date, rounded half up when `halfUp` (else half to even), and at least
 *  `floorCents` (the sheet's floor) unless a correction; null when there is none. */
export function claimDueCents(claim: ClaimView, gradeRates: readonly RateView[], floorCents: number, halfUp = false): number | null {
  if (claim.settledDue !== null) return cents(claim.settledDue);
  if (claim.stated) return claim.statedDue === null ? null : cents(claim.statedDue);
  const rate = rateInForce(gradeRates, claim.date);
  if (rate === null) return null;
  const due = dueCents(cents(claim.amount), rate.pct, halfUp);
  return isCorrection(claim) ? due : Math.max(due, floorCents);
}

/** Days since 1970-01-01 of a yyyy-mm-dd date, worked out from the calendar, not a clock. */
function epochDay(date: string): number {
  const [y0, m, d] = date.split('-').map(Number);
  const y = m <= 2 ? y0 - 1 : y0;
  const era = Math.floor(y / 400);
  const yoe = y - era * 400;
  const doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

/** The yyyy-mm-dd date of days since 1970-01-01. */
function dateOf(epoch: number): string {
  const z = epoch + 719468;
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

/** The date the claim is due by: its date plus the sheet's terms in days. */
export function dueBy(claim: ClaimView, terms: number): string {
  return dateOf(epochDay(claim.date) + terms);
}

/** Whether the claim is overdue: open, with its due-by before the sheet's today. */
export function isOverdue(claim: ClaimView, terms: number, today: string | undefined): boolean {
  return claim.status === 'OPEN' && today !== undefined && dueBy(claim, terms) < today;
}

/** The days a claim must be overdue for each step of the late charge. */
export const DAYS_PER_CHARGE = 30;

/** What the late charge depends on: the sheet's terms in days, its today and its late percent; and the
 *  policy date in force (the sheet's, else its today) that decides how a claim's figures round. */
export interface Lateness {
  terms: number;
  today: string | undefined;
  latePct: string;
  policyDate: string | undefined;
}

/** The late charge on the claim of `figureCents`, in cents: the late percent of the figure for every
 *  full 30 days the claim is overdue, rounded like its due; none unless it is overdue, and
 *  none on a correction. */
export function lateChargeCents(claim: ClaimView, figureCents: number, late: Lateness, halfUp = false): number {
  if (late.today === undefined || isCorrection(claim) || !isOverdue(claim, late.terms, late.today)) return 0;
  const steps = Math.floor((epochDay(late.today) - epochDay(claim.date) - late.terms) / DAYS_PER_CHARGE);
  return steps <= 0 ? 0 : dueCents(figureCents * steps, late.latePct, halfUp);
}

/** What is due on the claim now, in cents: its figure (see claimDueCents) with the late charge on top
 *  while it is open and overdue, as no statement binds the charge; both round as roundsHalfUp decides
 *  by the policy date, `claims` holding the claim a correction corrects. Null when the figure is n/a. */
export function claimOwedCents(claim: ClaimView, gradeRates: readonly RateView[], floorCents: number, late: Lateness,
  claims: readonly ClaimView[]): number | null {
  const halfUp = roundsHalfUp(claim, claims, late.policyDate);
  const figure = claimDueCents(claim, gradeRates, floorCents, halfUp);
  return figure === null ? null : figure + lateChargeCents(claim, figure, late, halfUp);
}

// The claims on a sheet, as the store holds them.
@Injectable({ providedIn: 'root' })
export class ClaimsApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  /** The sheet's claims, newest first. */
  list(sheetId: string): Promise<ClaimView[]> {
    return firstValueFrom(this.http.get<ClaimView[]>(`${this.url(sheetId)}/claims`));
  }

  add(sheetId: string, personId: number, form: ClaimForm): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/people/${personId}/claims`, form));
  }

  update(sheetId: string, id: number, form: ClaimForm): Promise<ClaimView> {
    return firstValueFrom(this.http.put<ClaimView>(`${this.url(sheetId)}/claims/${id}`, form));
  }

  /** Move a claim to its next status: refused when its status does not allow it, and settling is
   *  refused (403) to the user who opened the claim. */
  act(sheetId: string, id: number, action: ClaimAction): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/${action}`, {}));
  }

  /** Open the claims of these ids together in list order; each that cannot be opened is left as it is. */
  openSelected(sheetId: string, ids: readonly number[]): Promise<BulkOpenView> {
    return firstValueFrom(this.http.post<BulkOpenView>(`${this.url(sheetId)}/claims/open`, { ids }));
  }
}
