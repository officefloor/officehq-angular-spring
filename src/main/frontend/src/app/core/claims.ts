import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { HoldView, PersonView } from './people';

/** DRAFT when added, then OPEN, then SETTLED; a DRAFT, OPEN or PENDING claim can be VOID. A clerk settling a
 *  claim above the clerk limit makes it PENDING, which a steward approves (SETTLED) or rejects (OPEN again). */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

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
   *  decimals; null when not bound, while PENDING (it then follows the rate), or if it had no figure then (it stays n/a). */
  statedDue: string | null;
  /** The number of the SETTLED claim of the same person a correction (a negative amount) corrects; null on
   *  any other claim. */
  corrects: string | null;
  /** The day the claim is due by (yyyy-mm-dd): fixed when it was added, from its date and the terms of its
   *  person's grade in force then. */
  dueBy: string;
  /** The day the claim was deferred until (yyyy-mm-dd); null if it was never deferred. */
  deferredUntil: string | null;
  /** The claim's status log, oldest first: a line for each status it has had, the DRAFT it was added as first. */
  log: ClaimLogLine[];
  /** How many proofs have been given for the claim. */
  proofs: number;
}

/** A line of a claim's status log: the status it took, the signed-in name of who moved it there, the sheet's
 *  today (yyyy-mm-dd) then, and the due it had at that moment with exactly two decimals (null when it had none). */
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  on: string;
  due: string | null;
}

/** Only OPEN, PENDING and SETTLED claims count toward what is claimed and due. */
export function counts(c: ClaimView): boolean {
  return c.status === 'OPEN' || c.status === 'PENDING' || c.status === 'SETTLED';
}

/** Whether a claim is deferred on the sheet's today: OPEN and deferred until a later day. While deferred it
 *  counts in no total, is not overdue and cannot be settled. */
export function isDeferred(c: Pick<ClaimView, 'deferredUntil' | 'status'>, today: string): boolean {
  return c.status === 'OPEN' && c.deferredUntil !== null && today < c.deferredUntil;
}

/** Whether the claim counts in the live totals on the sheet's today for a person with these holds: a deferred
 *  one does not, nor does an OPEN or PENDING one dated within a hold, unless a statement binds it. */
export function countsWithHolds(holds: readonly HoldView[], today: string): (c: ClaimView) => boolean {
  return (c) =>
    counts(c) &&
    !isDeferred(c, today) &&
    (c.stated ||
      (c.status !== 'OPEN' && c.status !== 'PENDING') ||
      !holds.some((h) => h.from <= c.date && (h.to === null || c.date <= h.to)));
}

/** The status a claim had at the end of a day (yyyy-mm-dd), as its status log records it: the last line dated on
 *  or before it; null when the claim was added after it. */
export function statusAt(c: Pick<ClaimView, 'log'>, day: string): ClaimStatus | null {
  let status: ClaimStatus | null = null;
  for (const line of c.log) if (line.on <= day) status = line.status;
  return status;
}

/** Whether the claim counts in a period's figures for a person with these holds: by the status it had at the end
 *  of the period's last day (`end`), and left out when deferred or held as the live totals on the sheet's today
 *  say. */
export function countsAtEndWithHolds(
  holds: readonly HoldView[],
  today: string,
  end: string,
): (c: ClaimView) => boolean {
  return (c) => {
    const status = statusAt(c, end);
    return (
      (status === 'OPEN' || status === 'PENDING' || status === 'SETTLED') &&
      !isDeferred(c, today) &&
      (c.stated ||
        (c.status !== 'OPEN' && c.status !== 'PENDING') ||
        !holds.some((h) => h.from <= c.date && (h.to === null || c.date <= h.to)))
    );
  };
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

/** Whether a claim is overdue on the sheet's today: OPEN or PENDING, not deferred, and due by a day before it. */
export function isOverdue(c: Pick<ClaimView, 'dueBy' | 'status' | 'deferredUntil'>, today: string): boolean {
  return (c.status === 'OPEN' || c.status === 'PENDING') && !isDeferred(c, today) && c.dueBy < today;
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

/** What a paste did: how many claims it added and the claims, in the order added. */
export interface PasteView {
  added: number;
  claims: ClaimView[];
}

/** What a bulk open did: how many it opened and refused, and the claims it opened. */
export interface BulkOpenView {
  opened: number;
  refused: number;
  claims: ClaimView[];
}

/** What a bulk settle did: how many it settled (PENDING ones included) and refused, and the claims it settled. */
export interface BulkSettleView {
  settled: number;
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

  /** Add a DRAFT claim per line of name,amount,date,note, in order, for people already on the sheet.
   *  Refused as a whole when any line is. */
  paste(sheetId: string, text: string): Observable<PasteView> {
    return this.http.post<PasteView>(`${this.base(sheetId)}/claims/paste`, { text });
  }

  update(sheetId: string, id: number, claim: ClaimForm): Observable<ClaimView> {
    return this.http.put<ClaimView>(`${this.base(sheetId)}/claims/${id}`, claim);
  }

  changeStatus(sheetId: string, id: number, status: ClaimStatus): Observable<ClaimView> {
    return this.http.put<ClaimView>(`${this.base(sheetId)}/claims/${id}/status`, { status });
  }

  /** Defer an OPEN claim until `until` (yyyy-mm-dd), a day after the sheet's today. */
  defer(sheetId: string, id: number, until: string): Observable<ClaimView> {
    return this.http.put<ClaimView>(`${this.base(sheetId)}/claims/${id}/defer`, { until });
  }

  /** Raise a claim's proofs count by one; refused for a SETTLED or VOID claim. */
  addProof(sheetId: string, id: number): Observable<ClaimView> {
    return this.http.post<ClaimView>(`${this.base(sheetId)}/claims/${id}/proofs`, {});
  }

  /** A steward approves a PENDING claim: it is SETTLED, its due fixed as it is now. */
  approve(sheetId: string, id: number): Observable<ClaimView> {
    return this.http.put<ClaimView>(`${this.base(sheetId)}/claims/${id}/approve`, {});
  }

  /** A steward re-settles a SETTLED claim: its due fixed again as it would be today as an open, unbound claim. */
  resettle(sheetId: string, id: number): Observable<ClaimView> {
    return this.http.put<ClaimView>(`${this.base(sheetId)}/claims/${id}/resettle`, {});
  }

  /** A steward rejects a PENDING claim with a reason: it is OPEN again. */
  reject(sheetId: string, id: number, reason: string): Observable<ClaimView> {
    return this.http.put<ClaimView>(`${this.base(sheetId)}/claims/${id}/reject`, { reason });
  }

  /** A steward moves a DRAFT, OPEN or PENDING claim to another person: it keeps its number and date, is no
   *  longer bound by a statement, and is listed as that person's newest claim. */
  reassign(sheetId: string, id: number, personId: number): Observable<ClaimView> {
    return this.http.put<ClaimView>(`${this.base(sheetId)}/claims/${id}/person`, { personId });
  }

  /** Open each of the claims that can be opened, numbered in list order; the rest are refused and left as they are. */
  bulkOpen(sheetId: string, ids: readonly number[]): Observable<BulkOpenView> {
    return this.http.post<BulkOpenView>(`${this.base(sheetId)}/claims/bulk-open`, { ids });
  }

  /** Settle each of the claims that can be settled, in list order, as a single settle would; the rest are refused
   *  and left as they are. */
  bulkSettle(sheetId: string, ids: readonly number[]): Observable<BulkSettleView> {
    return this.http.post<BulkSettleView>(`${this.base(sheetId)}/claims/bulk-settle`, { ids });
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }
}
