import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, isDate } from './session';

export interface Claim {
  id: number;
  personId: number;
  /** Two decimals, as the server stores it. */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** Given when opened, the next on the sheet; null while a draft or once voided. */
  number: number | null;
  /** The due fixed when it was settled, two decimals; null while its due is live (not settled, or
   *  settled while its grade had no rate). */
  settledDue: string | null;
  /** The user who opened it; null while a draft. That user cannot settle it. */
  openedBy: string | null;
}

/** DRAFT when added; DRAFT -> OPEN -> SETTLED, and DRAFT or OPEN -> VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** A status change a claim's buttons ask for. */
export type ClaimAction = 'open' | 'settle' | 'void';

/** Whether a claim in this status counts toward claimed, due-total and every total. */
export function counts(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'SETTLED';
}

export interface ClaimFields {
  amount: string;
  /** Blank takes the sheet's today. */
  date: string;
  note: string;
}

// The people's claims on the active sheet, newest first, re-read from the server each time the
// sheet is loaded. Sums are kept in whole cents so they add up exactly, and count only OPEN and
// SETTLED claims.
@Injectable({ providedIn: 'root' })
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly claims = signal<Claim[]>([]);
  private loads = 0;

  /** Each person's claimed, in cents, by person id. */
  readonly claimedCents = computed(() => {
    const out = new Map<number, number>();
    for (const c of this.claims().filter(counts)) out.set(c.personId, (out.get(c.personId) ?? 0) + cents(c.amount));
    return out;
  });

  /** Everyone's claimed added up, with two decimals. */
  readonly total = computed(() => money(this.claims().filter(counts).reduce((sum, c) => sum + cents(c.amount), 0)));

  /** A person's claims, newest first. */
  of(personId: number): Claim[] {
    return this.claims().filter((c) => c.personId === personId);
  }

  /** A person's claimed, with two decimals. */
  claimed(personId: number): string {
    return money(this.claimedCents().get(personId) ?? 0);
  }

  /** Re-read the active sheet's claims from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.claims.set([]);
    if (!user || !sid) return null;
    try {
      const claims = await firstValueFrom(
        this.http.get<Claim[]>(`${this.url(sid)}/claims`, { headers: { 'X-User': user } }),
      );
      if (load === this.loads) this.claims.set(claims);
      return null;
    } catch (e) {
      return failed('load', e).replace(' claim', ' claims');
    }
  }

  /** Add a claim for a person; an error message when refused. */
  async add(personId: number, f: ClaimFields): Promise<string | null> {
    const refused = this.check(f);
    if (refused) return refused;
    const sid = this.session.sheetId()!;
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(`${this.url(sid)}/people/${personId}/claims`, this.body(f), {
          headers: { 'X-User': this.session.user()! },
        }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => [claim, ...cs]);
      return null;
    } catch (e) {
      return failed('add', e);
    }
  }

  /** Change a claim's amount, date and note; an error message when refused. */
  async update(id: number, f: ClaimFields): Promise<string | null> {
    const refused = this.check(f);
    if (refused) return refused;
    const sid = this.session.sheetId()!;
    try {
      const claim = await firstValueFrom(
        this.http.put<Claim>(`${this.url(sid)}/claims/${id}`, this.body(f), {
          headers: { 'X-User': this.session.user()! },
        }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      return null;
    } catch (e) {
      return failed('save', e);
    }
  }

  /** Open, settle or void a claim; an error message when refused. */
  async act(id: number, action: ClaimAction): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(`${this.url(sid)}/claims/${id}/${action}`, null, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) return refusal(e, action);
      return failed(action, e);
    }
  }

  private check(f: ClaimFields): string | null {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    if (!/^\d+(\.\d{1,2})?$/.test(f.amount.trim())) return 'Enter an amount of 0.00 or more, with up to two decimals.';
    if (f.date.trim() && !isDate(f.date.trim())) return 'Enter a date as YYYY-MM-DD.';
    return null;
  }

  private body(f: ClaimFields): { amount: string; date: string | null; note: string } {
    return { amount: f.amount.trim(), date: f.date.trim() || null, note: f.note.trim() };
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}`;
  }
}

/** An amount written with up to two decimals, in whole cents. */
function cents(amount: string): number {
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2));
}

/** Cents as an amount with two decimals. */
function money(cents: number): string {
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}

function refusal(e: HttpErrorResponse, action: ClaimAction): string {
  const err: unknown = e.error;
  if (err && typeof err === 'object' && 'message' in err && typeof err.message === 'string' && err.message) {
    return err.message;
  }
  return `This claim's status does not allow it to ${action}.`;
}

function failed(what: string, e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter an amount of 0.00 or more, with up to two decimals.';
  return e instanceof HttpErrorResponse ? `Could not ${what} claim (${e.status}).` : `Could not ${what} claim.`;
}
