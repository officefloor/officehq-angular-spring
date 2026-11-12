import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { People, Person, heldOn } from './people';
import { hundredths, rounded } from './rates';
import { Session, daysBetween, isDate } from './session';

export interface Claim {
  id: number;
  personId: number;
  /** Two decimals, as the server stores it. */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** Given when opened: the next on the sheet (1, 2, ...), or while numbering by period the next in its
   *  period (<period name>-1, ...) or in no period (X-1, ...); null while a draft. */
  number: string | null;
  /** The due fixed when it was settled, two decimals; null while its due is live (not settled, or
   *  settled while its grade had no rate). */
  settledDue: string | null;
  /** The user who opened it; null while a draft. That user cannot settle it. */
  openedBy: string | null;
  /** Bound by its person's last statement, which counted it: it shows and totals `statedDue` (not while
   *  PENDING, see `bound`). */
  stated: boolean;
  /** The figure the last statement bound, two decimals; null while unbound, or when that was n/a. */
  statedDue: string | null;
  /** The date it is due by (YYYY-MM-DD): fixed when it was added, its date plus the terms of its person's
   *  grade in force on its date then (the sheet's terms when the grade has none of its own). */
  dueBy: string;
  /** A correction's (a negative amount): the number of the SETTLED claim of the same person it
   *  corrects; null otherwise. */
  corrects: string | null;
  /** Its status log: one line per status it has had, oldest first. */
  statusLog: StatusLine[];
}

/** A line of a claim's status log: the status it took, who changed it, the sheet's today then
 *  (YYYY-MM-DD), and the due it had then, two decimals (null when n/a). */
export interface StatusLine {
  status: ClaimStatus;
  by: string;
  on: string;
  due: string | null;
}

/** DRAFT when added; DRAFT -> OPEN -> SETTLED, and DRAFT, OPEN or PENDING -> VOID. A clerk settling a
 *  due above the clerk limit makes it PENDING: a steward approves it (SETTLED) or rejects it (OPEN). */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

/** A status change a claim's buttons ask for. */
export type ClaimAction = 'open' | 'settle' | 'void' | 'approve' | 'reject';

/** Whether a claim in this status counts toward claimed, due-total and every total. */
export function counts(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'PENDING' || c.status === 'SETTLED';
}

/** Whether a claim is still open (OPEN or PENDING): it goes overdue, and a hold over its date takes it
 *  out of the live totals unless a statement binds it. */
export function isOpen(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'PENDING';
}

/** Whether a claim is bound by its person's last statement now: a PENDING claim shows and totals its
 *  live figure until rejected back to OPEN. */
export function bound(c: Claim): boolean {
  return c.stated && c.status !== 'PENDING';
}

export interface ClaimFields {
  amount: string;
  /** Blank takes the sheet's today. */
  date: string;
  note: string;
  /** The number of the settled claim a negative amount corrects; blank for any other claim. */
  corrects: string;
}

// The people's claims on the active sheet, newest first, re-read from the server each time the
// sheet is loaded. Sums are kept in whole cents so they add up exactly, and count only OPEN, PENDING
// and SETTLED claims.
@Injectable({ providedIn: 'root' })
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  private readonly people = inject(People);

  readonly claims = signal<Claim[]>([]);
  /** The ids of the claims chosen for a bulk action. */
  readonly selected = signal<ReadonlySet<number>>(new Set());
  private loads = 0;

  /** The claims that count in the live totals: those that count, less the OPEN and PENDING claims bound
   *  by no statement and dated within one of their person's holds. */
  readonly live = computed(() => {
    const holds = new Map(this.people.people().map((p) => [p.id, p.holds]));
    return this.claims().filter((c) => counts(c) && !(isOpen(c) && !bound(c) && heldOn(holds.get(c.personId) ?? [], c.date)));
  });

  /** Each person's claimed, in cents, by person id. */
  readonly claimedCents = computed(() => {
    const out = new Map<number, number>();
    for (const c of this.live()) out.set(c.personId, (out.get(c.personId) ?? 0) + cents(c.amount));
    return out;
  });

  /** Everyone's claimed added up, with two decimals. */
  readonly total = computed(() => money(this.live().reduce((sum, c) => sum + cents(c.amount), 0)));

  /** The date a claim is due by: fixed when it was added, from the terms of its person's grade then. */
  dueBy(c: Claim): string {
    return c.dueBy;
  }

  /** Whether a claim is overdue: OPEN or PENDING, with its due-by before the sheet's today. */
  overdue(c: Claim): boolean {
    const today = this.session.today();
    return isOpen(c) && today !== null && this.dueBy(c) < today;
  }

  /** The late charge, in cents, on a claim's figure (in cents): while it is overdue, the sheet's late
   *  percent of the figure for every full 30 days past its due-by, rounded to cents as its date rounds; none
   *  otherwise, and none on a correction. Never bound by a statement; frozen into the due when settled. */
  lateCents(c: Claim, figureCents: bigint): bigint {
    const today = this.session.today();
    if (!this.overdue(c) || today === null || c.amount.startsWith('-')) return 0n;
    const periods = BigInt(Math.floor(daysBetween(this.dueBy(c), today) / 30));
    return rounded(figureCents * hundredths(this.session.latePct()) * periods, 10000n, this.session.roundsHalfUp(c.date));
  }

  /** The date a claim's figures round by: its own, or for a correction the date of the claim of the same
   *  person it corrects (its own when that is not found). */
  roundingDate(c: Claim): string {
    if (c.corrects === null) return c.date;
    return this.claims().find((o) => o.personId === c.personId && o.number === c.corrects)?.date ?? c.date;
  }

  /** A person's claims, newest first. */
  of(personId: number): Claim[] {
    return this.claims().filter((c) => c.personId === personId);
  }

  /** A person's claimed, with two decimals. */
  claimed(personId: number): string {
    return money(this.claimedCents().get(personId) ?? 0);
  }

  /** Replace a person's claims with these, as the server answered them (newest first). */
  replaceOf(personId: number, theirs: Claim[]): void {
    const byId = new Map(theirs.map((c) => [c.id, c]));
    this.claims.update((cs) => cs.map((c) => (c.personId === personId ? (byId.get(c.id) ?? c) : c)));
  }

  /** Re-read the active sheet's claims from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.claims.set([]);
    this.selected.set(new Set());
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

  /** Quick-add a DRAFT claim dated the sheet's today, with an empty note, for the person with the
   *  name, the server adding them with grade A first when the sheet has no one by it; an error
   *  message when refused. */
  async quickAdd(name: string, amount: string): Promise<string | null> {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    if (!name.trim()) return 'Enter a name.';
    const refused = this.check({ amount, date: '', note: '', corrects: '' });
    if (refused) return refused;
    const sid = this.session.sheetId()!;
    try {
      const added = await firstValueFrom(
        this.http.post<{ person: Person; personAdded: boolean; claim: Claim }>(
          `${this.url(sid)}/quick-claims`,
          { name: name.trim(), amount: amount.trim() },
          { headers: { 'X-User': this.session.user()! } },
        ),
      );
      if (this.session.sheetId() === sid) {
        this.people.people.update((ps) =>
          ps.some((p) => p.id === added.person.id)
            ? ps.map((p) => (p.id === added.person.id ? added.person : p))
            : [...ps, added.person],
        );
        this.claims.update((cs) => [added.claim, ...cs]);
      }
      return null;
    } catch (e) {
      return failed('add', e);
    }
  }

  /** Change a DRAFT claim's amount, date and note; when refused, the reason and whether it is the
   *  claim itself that refused it (it is no longer a DRAFT) rather than the fields typed. */
  async update(id: number, f: ClaimFields): Promise<{ message: string; onClaim: boolean } | null> {
    const refused = this.check(f);
    if (refused) return { message: refused, onClaim: false };
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
      if (e instanceof HttpErrorResponse && e.status === 409) {
        return { message: message(e) ?? 'Only a DRAFT claim can be edited.', onClaim: true };
      }
      return { message: failed('save', e), onClaim: false };
    }
  }

  /** Open, settle, void, approve or reject a claim (a rejection with its reason); an error message
   *  when refused. */
  async act(id: number, action: ClaimAction, reason = ''): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    if (action === 'reject' && !reason.trim()) return 'Give a reason to reject the claim.';
    try {
      const body = action === 'reject' ? { reason: reason.trim() } : null;
      const claim = await firstValueFrom(
        this.http.post<Claim>(`${this.url(sid)}/claims/${id}/${action}`, body, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) return refusal(e, action);
      return failed(action, e);
    }
  }

  /** Move a DRAFT, OPEN or PENDING claim to another person (a steward's action): it becomes their newest
   *  claim, unbound from any statement; an error message when refused. */
  async reassign(id: number, personId: number | null): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    if (personId === null) return 'Choose the person to move the claim to.';
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(`${this.url(sid)}/claims/${id}/reassign`, { personId }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => [claim, ...cs.filter((c) => c.id !== id)]);
      return null;
    } catch (e) {
      return failed('reassign', e);
    }
  }

  /** Choose a claim for a bulk action, or leave it out. */
  select(id: number, on: boolean): void {
    this.selected.update((s) => {
      const next = new Set(s);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Open the chosen claims together, the server numbering them in list order and leaving each that
   *  cannot be opened as it is; how many were opened and refused, or an error message. */
  async bulkOpen(): Promise<{ opened: number; refused: number } | string> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    const claimIds = [...this.selected()];
    if (claimIds.length === 0) return 'Select the claims to open.';
    try {
      const done = await firstValueFrom(
        this.http.post<{ opened: number; refused: number; claims: Claim[] }>(
          `${this.url(sid)}/claims/bulk-open`,
          { claimIds },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.session.sheetId() === sid) {
        const byId = new Map(done.claims.map((c) => [c.id, c]));
        this.claims.update((cs) => cs.map((c) => byId.get(c.id) ?? c));
        this.selected.set(new Set());
      }
      return { opened: done.opened, refused: done.refused };
    } catch (e) {
      return failed('open', e).replace(' claim', ' claims');
    }
  }

  private check(f: ClaimFields): string | null {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    if (!/^-?\d+(\.\d{1,2})?$/.test(f.amount.trim())) return 'Enter an amount with up to two decimals.';
    if (f.date.trim() && !isDate(f.date.trim())) return 'Enter a date as YYYY-MM-DD.';
    const corrects = f.corrects.trim();
    if (corrects.length > 220) return 'Enter the number of the claim it corrects.';
    if (f.amount.trim().startsWith('-') && !corrects) {
      return 'A negative amount is a correction: name the number of the settled claim it corrects.';
    }
    return null;
  }

  private body(f: ClaimFields): { amount: string; date: string | null; note: string; corrects: string | null } {
    const corrects = f.corrects.trim();
    return {
      amount: f.amount.trim(),
      date: f.date.trim() || null,
      note: f.note.trim(),
      corrects: corrects || null,
    };
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}`;
  }
}

/** An amount written with up to two decimals, perhaps negative, in whole cents. */
function cents(amount: string): number {
  if (amount.startsWith('-')) return -cents(amount.slice(1));
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2));
}

/** Cents as an amount with two decimals. */
function money(cents: number): string {
  if (cents < 0) return `-${money(-cents)}`;
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}

function refusal(e: HttpErrorResponse, action: ClaimAction): string {
  return message(e) ?? `This claim's status does not allow it to ${action}.`;
}

/** The reason the server gave for a refusal, when it gave one. */
function message(e: HttpErrorResponse): string | null {
  const err: unknown = e.error;
  if (err && typeof err === 'object' && 'message' in err && typeof err.message === 'string' && err.message) {
    return err.message;
  }
  return null;
}

function failed(what: string, e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return message(e) ?? `Could not ${what} claim (409).`;
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter an amount with up to two decimals.';
  return e instanceof HttpErrorResponse ? `Could not ${what} claim (${e.status}).` : `Could not ${what} claim.`;
}
