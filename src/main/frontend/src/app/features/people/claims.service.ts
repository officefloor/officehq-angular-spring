import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Claim {
  id: number;
  personId: number;
  /** The amount with two decimals, as text (e.g. "10.50"). */
  amount: string;
  /** YYYY-MM-DD */
  date: string;
  /** The date (YYYY-MM-DD) the claim is due by: the terms-th business day after its date when it was
   *  added, with the terms fixed then, skipping weekends and the sheet's holidays now. */
  dueBy: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number on the sheet, given when it is opened (sheet-wide, e.g. "3", or within its
   *  period, e.g. "P1-2", or "X-1" in no period); null while DRAFT. */
  number: string | null;
  /** What was due on the claim when it was settled, with two decimals; null otherwise, and when its
   *  person's grade had no rate then. */
  settledDue: string | null;
  /** The user who opened the claim; null until it is opened. */
  openedBy: string | null;
  /** The user who last edited the claim; null when it was never edited. */
  editedBy: string | null;
  /** The number of the settle batch (bulk settle) that settled the claim; null when it was settled one
   *  at a time, or not settled. */
  settleBatch: number | null;
  /** Whether the claim is bound to the figure it had at its person's last statement. */
  stated: boolean;
  /** The figure it is bound to, with two decimals; null while unbound, and when it had no figure
   *  (no rate) then. */
  statedDue: string | null;
  /** What was due on it as it showed when bound, late charge and floor included, with two decimals;
   *  null while unbound, when it had no figure then, and when bound before this was kept. */
  statedShown: string | null;
  /** On a correction (a claim with a negative amount), the number of the SETTLED claim of the same
   *  person it corrects; null on any other claim. */
  corrects: string | null;
  /** The claim's status log, oldest first: one line per status it has had, as
   *  "<STATUS> by <user> on <date> due=<due>", with the sheet's today and what was due on it then. */
  log: string[];
  /** The date (YYYY-MM-DD) the claim was last deferred until; null when it was never deferred. */
  deferredUntil: string | null;
  /** How many proofs the claim has been given. */
  proofs: number;
  /** The number of the claim this one was split off; null when it was not split off another. */
  splitFrom: string | null;
  /** Whether the claim was added while the period its date falls in was soft-closed. */
  lateEntry: boolean;
  /** The sheet's today (YYYY-MM-DD) on which a reminder of the claim was last written; null when
   *  none ever was. */
  remindedOn: string | null;
  /** The claim's external reference; null when it has none. */
  ref: string | null;
  /** The claim's tags, trimmed, lower-cased, without repeats and sorted; empty when it has none. */
  tags: string[];
  /** The reason from the sheet's list the claim was voided with; null unless it was voided with one. */
  voidReason: string | null;
  /** The claim's comments, oldest first, each as `<user> on <date>: <text>`. */
  comments: string[];
  /** The claim's lines, in the order they were added, each as `<label> <qty> x <unit>`; a claim with
   *  lines has the amount they add up to. */
  lines: string[];
  /** When the claim was first added, as a claim id: a rate saved forward-only applies to it only when
   *  its mark is below this (see `Rate.terms`). */
  added: number;
  /** Whether the claim is in REVIEW past the sheet's review SLA: its review day plus the SLA days is
   *  before the sheet's today. */
  reviewLate: boolean;
  /** Whether a steward waived the claim's late charge: it bears none from then on. */
  waived: boolean;
}

/** A line typed for a claim: its label, quantity and unit amount, as text. */
export interface LineFields {
  label: string;
  qty: string;
  unit: string;
}

/** Added as DRAFT, then OPEN, then SETTLED; a clerk's settle above the clerk limit leaves it PENDING
 *  until a steward approves it (SETTLED) or rejects it (back to OPEN). A DRAFT, OPEN or PENDING claim
 *  can be VOID, and a SETTLED one by a steward within 7 days of its settlement (it keeps its settled
 *  figure, which leaves every total). A DRAFT can be DELETED (it counts nowhere), and restored to a
 *  DRAFT on the same sheet-today it was deleted. An open of a claim whose due is above the review
 *  threshold in force on its date leaves it in REVIEW (counted nowhere, no number yet) until a steward
 *  reviews it (OPEN); a REVIEW claim can be voided. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID' | 'DELETED' | 'REVIEW';

/** A status change asked of a claim. */
export type ClaimAction = 'open' | 'review' | 'settle' | 'approve' | 'reject' | 'void' | 'delete' | 'restore';

/** Whether a claim in this status counts toward what is claimed and due. */
export function counts(claim: Claim): boolean {
  return isOpen(claim) || claim.status === 'SETTLED';
}

/** The status the claim had at the end of the day (YYYY-MM-DD), as its status log records it
 *  (each line dated with the sheet's today when the change was made); null when it was added after. */
export function statusOn(claim: Claim, day: string): ClaimStatus | null {
  let status: ClaimStatus | null = null;
  for (const line of claim.log) {
    const [, logged, on] = /^(\S+) by .* on (\d{4}-\d{2}-\d{2}) due=\S+$/.exec(line) ?? [];
    if (on === undefined || on > day) break;
    status = logged as ClaimStatus;
  }
  return status;
}

/** Whether a claim is still open: OPEN, or PENDING, which is handled like OPEN (it goes overdue,
 *  bears the late charge and leaves the totals while its person is on hold). */
export function isOpen(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'PENDING';
}

/** Whether a claim is deferred on the sheet's today: OPEN and deferred until a date after it. Until
 *  then it counts in no total, is not overdue, cannot be settled and no statement binds it. */
export function deferred(claim: Claim, today: string | null): boolean {
  return claim.status === 'OPEN' && claim.deferredUntil !== null && today !== null && today < claim.deferredUntil;
}

/** Whether a claim is overdue: OPEN or PENDING, not deferred, and overdue from a date before the
 *  sheet's today — its due-by, or that plus its grade's grace (see overdueFrom). */
export function overdue(claim: Claim, today: string | null, from: string = claim.dueBy): boolean {
  return isOpen(claim) && today !== null && !deferred(claim, today) && from < today;
}

/** How a bulk open went: how many claims it opened and refused, and the claims it opened. */
export interface BulkOpened {
  opened: number;
  refused: number;
  claims: Claim[];
}

/** How a bulk settle went: how many it settled (or left PENDING) and refused, and those claims. */
export interface BulkSettled {
  settled: number;
  refused: number;
  claims: Claim[];
  /** The number of the settle batch it made; null when it settled none. */
  batch: number | null;
}

/** How a bulk re-date went: how many claims it re-dated and refused, and the claims it re-dated. */
export interface BulkRedated {
  redated: number;
  refused: number;
  claims: Claim[];
}

/** How a bulk move went: how many claims it moved and refused, and the claims it moved. */
export interface BulkReassigned {
  reassigned: number;
  refused: number;
  claims: Claim[];
}

/** How a paste of claims went: how many lines went through, and the claims, in the order of their lines. */
export interface Pasted {
  added: number;
  claims: Claim[];
}

export interface ClaimFields {
  amount: string;
  /** null takes the sheet's today. */
  date: string | null;
  note: string;
  /** The number of the claim a correction corrects; null on a claim that is not a correction. */
  corrects: string | null;
  /** The claim's external reference (trimmed by the server); null or blank for none. */
  ref: string | null;
  /** The claim's tags, comma separated (each trimmed and lower-cased by the server); null or blank for none. */
  tags: string | null;
}

/** Whether a claim is a correction: one with a negative amount. */
export function isCorrection(claim: Claim): boolean {
  return cents(claim.amount) < 0;
}

/** Amounts in whole cents, so sums of two-decimal amounts are exact. */
export function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

export function money(cents: number): string {
  return (cents / 100).toFixed(2);
}

// The claims kept on a sheet, read from and written to the server.
@Injectable({ providedIn: 'root' })
export class ClaimsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  /** The claim whose editor is open, by sheet and person. */
  private readonly editors = new Map<string, number>();

  /** The sheet's claims, newest first. */
  list(sheet: string): Promise<Claim[]> {
    return firstValueFrom(
      this.http.get<Claim[]>(`${this.url(sheet)}/claims`, { headers: this.session.headers() }),
    );
  }

  /** Every tag ever used on the sheet's claims, sorted. */
  tags(sheet: string): Promise<string[]> {
    return firstValueFrom(
      this.http.get<string[]>(`${this.url(sheet)}/tags`, { headers: this.session.headers() }),
    );
  }

  add(sheet: string, personId: number, fields: ClaimFields): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/people/${personId}/claims`, fields, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Open the claim's editor, locking the claim for other users until it is saved or cancelled, or
   *  the sheet's today changes; refused (409) when the claim is not a DRAFT, (423) when it is dated in
   *  a closed period, (412) when another user holds its lock, (403) for a viewer. */
  lock(sheet: string, id: number): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/lock`, null, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Close the claim's editor without saving, releasing the lock the user holds on it. */
  unlock(sheet: string, id: number): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/unlock`, null, {
        headers: this.session.headers(),
      }),
    );
  }

  /** The claim whose editor is open on the person's claim form on the sheet; null when none is. Kept
   *  here, not in the form, so the editor is still open when the sheet is loaded again. */
  editing(sheet: string, personId: number): number | null {
    return this.editors.get(`${sheet}\n${personId}`) ?? null;
  }

  setEditing(sheet: string, personId: number, claimId: number | null): void {
    const key = `${sheet}\n${personId}`;
    if (claimId === null) this.editors.delete(key);
    else this.editors.set(key, claimId);
  }

  update(sheet: string, id: number, fields: ClaimFields): Promise<Claim> {
    return firstValueFrom(
      this.http.put<Claim>(`${this.url(sheet)}/claims/${id}`, fields, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Change the claim's status; refused (409) when its status does not allow the change, (403) when
   *  the user who opened it asks to settle or approve it or one who is not a steward asks to approve or
   *  reject it or review a REVIEW claim, (400) when a reject gives no one-word reason, and (422) when a void gives no reason
   *  from the sheet's list while it is not empty, and (410) when a restore is asked on a later
   *  sheet-today than the claim was deleted on. A reject's or void's reason travels with it. */
  act(sheet: string, id: number, action: ClaimAction, reason?: string): Promise<Claim> {
    const body = action === 'reject' || action === 'void' ? { reason: reason ?? null } : null;
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/${action}`, body, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Defer an OPEN claim until a date after the sheet's today; refused (409) when it is not OPEN,
   *  (422) when the date is not after the sheet's today and (423) when it is dated in a closed period. */
  defer(sheet: string, id: number, until: string): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/defer`, { until }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Re-date an OPEN or PENDING claim; refused (403) when the user is not a steward, (422) when it is
   *  not OPEN or PENDING, (409) when the date is after its person's retirement and (423) when the claim
   *  or the date is in a closed period. */
  redate(sheet: string, id: number, date: string): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/redate`, { date }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Raise the claim's proofs count by one; refused (409) when it is SETTLED or VOID and (423) when it
   *  is dated in a closed period. */
  addProof(sheet: string, id: number): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/proof`, null, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Add a line to a DRAFT claim, making its amount what its lines add up to; refused (400) when the
   *  label is blank, the quantity not a whole number of at least 1 or the unit amount not one with up to
   *  two decimals, (409) when it is not a DRAFT, (412) when another user's editor holds it and (423)
   *  when it is dated in a closed period. */
  addLine(sheet: string, id: number, line: LineFields): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/lines`, line, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Comment on a claim with `text` (not blank), kept with the user and the sheet's today; refused
   *  (400) when blank and (403) for a viewer. */
  comment(sheet: string, id: number, text: string): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/comments`, { text }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Write a reminder of an OPEN or PENDING claim, reminding it on the sheet's today; refused (403)
   *  for a viewer, (409) when it is not OPEN or PENDING, (422) when it was already reminded on the
   *  sheet's today and (423) when it is dated in a closed period. */
  remind(sheet: string, id: number): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/remind`, null, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Waive an OPEN or PENDING claim's late charge; refused (403) when the user is not a steward, (409)
   *  when it is not OPEN or PENDING and (422) when it is already waived. */
  waive(sheet: string, id: number): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/waive`, null, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Fix a SETTLED claim again at what would be due on it now were it OPEN and bound by no statement;
   *  refused (403) when the user is not a steward, (409) when it is not SETTLED and (423) when it is
   *  dated in a closed period. */
  resettle(sheet: string, id: number): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/resettle`, null, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Split an amount off an OPEN claim into a new OPEN claim: the claim as it is now, then the new
   *  claim; refused (400) when the amount is not more than 0.00 with up to two decimals, (422) when it
   *  is not less than the claim's amount, (409) when the claim is not OPEN or is deferred and (423) when
   *  it is dated in a closed period. */
  split(sheet: string, id: number, amount: string): Promise<Claim[]> {
    return firstValueFrom(
      this.http.post<Claim[]>(`${this.url(sheet)}/claims/${id}/split`, { amount }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Move the claim to another person on the sheet; refused (403) when the user is not a steward,
   *  (409) when it is SETTLED or VOID, a correction or already theirs, (423) when it is dated in a
   *  closed period and (422) when it is dated after the person retired. */
  reassign(sheet: string, id: number, personId: number): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/reassign`, { personId }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Open each of the claims that can be opened, numbered in list order; the rest are left as they
   *  are and counted as refused. */
  bulkOpen(sheet: string, claimIds: number[]): Promise<BulkOpened> {
    return firstValueFrom(
      this.http.post<BulkOpened>(`${this.url(sheet)}/claims/bulk-open`, { claimIds }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Settle each of the claims that can be settled, in list order, by the rules of settling one;
   *  one left PENDING counts as settled, the rest are left as they are and counted as refused. */
  bulkSettle(sheet: string, claimIds: number[]): Promise<BulkSettled> {
    return firstValueFrom(
      this.http.post<BulkSettled>(`${this.url(sheet)}/claims/bulk-settle`, { claimIds }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Move each of the claims that can be moved to the person, in list order, by the rules of moving
   *  one; the rest are left as they are and counted as refused. */
  bulkReassign(sheet: string, claimIds: number[], personId: number): Promise<BulkReassigned> {
    return firstValueFrom(
      this.http.post<BulkReassigned>(`${this.url(sheet)}/claims/bulk-reassign`, { claimIds, personId }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Re-date each of the claims that can be re-dated to the date, in list order, by the rules of
   *  re-dating one; the rest are left as they are and counted as refused. */
  bulkRedate(sheet: string, claimIds: number[], date: string): Promise<BulkRedated> {
    return firstValueFrom(
      this.http.post<BulkRedated>(`${this.url(sheet)}/claims/bulk-redate`, { claimIds, date }, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Add (or update a matching DRAFT) and open a claim per line of name,amount,date,note, in order,
   *  for the person named; refused (400, 403, 404, 409, 412, 422, 423) with nothing changed when any
   *  line breaks the claim form's or the open button's rules. */
  paste(sheet: string, text: string): Promise<Pasted> {
    return firstValueFrom(
      this.http.post<Pasted>(`${this.url(sheet)}/claims/paste`, { text }, {
        headers: this.session.headers(),
      }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
