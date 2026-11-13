import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { People, Person, gradeOn, heldOn, retiredBy } from './people';
import { hundredths, rounded } from './rates';
import { Session, addDays, daysBetween, isDate } from './session';

export interface Claim {
  id: number;
  personId: number;
  /** Two decimals, as the server stores it. */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** Given when opened: the next on the sheet in its fiscal year (FY2026-1, FY2026-2, ...), or while numbering by period the next in its
   *  period (<period name>-1, ...) or in no period (X-1, ...); null while a draft. */
  number: string | null;
  /** The due fixed when it was settled, two decimals; null while its due is live (not settled, or
   *  settled while its grade had no rate). */
  settledDue: string | null;
  /** The user who opened it; null while a draft. That user cannot settle it. */
  openedBy: string | null;
  /** Bound by its person's last statement, which counted it: it shows and totals `statedDue` (see `bound`). */
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
  /** The date it was deferred until (YYYY-MM-DD); null when never deferred. It is deferred only while
   *  OPEN and the sheet's today is before it (see `Claims.deferred`). */
  deferredUntil: string | null;
  /** How many proofs it has. One whose due is above the proof threshold in force on its date cannot be
   *  settled or approved with none. */
  proofs: number;
  /** The number of the claim it was split from; null when it was not split off another. */
  splitFrom: string | null;
  /** The user whose editor is open on it, locking it for every other user until they save or cancel it,
   *  or the sheet's today changes; null while unlocked. */
  lockedBy: string | null;
  /** Whether it was added while its period was soft-closed. */
  lateEntry: boolean;
  /** The last day (YYYY-MM-DD) a reminder was written for it; null when never reminded. */
  remindedOn: string | null;
  /** Its external reference, unique on the sheet without regard to case; null when not given. */
  ref: string | null;
  /** Its tags: trimmed, lower-cased, without repeats, sorted; empty when it carries none. */
  tags: string[];
  /** The reason it was voided with, from the sheet's list; null when not voided, or voided without one. */
  voidReason: string | null;
  /** Its comments, in the order written. */
  comments: CommentLine[];
  /** The number of the bulk settle batch that settled it; null when settled one at a time, or never. */
  settleBatch: number | null;
  /** Its lines, in the order added; while it has any, its amount is what they add up to. */
  lines: ClaimLine[];
  /** Whether a steward waived its late charge: from then on it bears none, whatever its overdue state. */
  waived: boolean;
}

/** A line of a claim: its label, a whole quantity of at least 1 and a unit amount, two decimals. */
export interface ClaimLine {
  label: string;
  qty: number;
  unit: string;
}

/** A comment on a claim: who wrote it, the sheet's today then (YYYY-MM-DD), and its text. */
export interface CommentLine {
  by: string;
  on: string;
  text: string;
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
 *  due above the clerk limit makes it PENDING: a steward approves it (SETTLED) or rejects it (OPEN). A
 *  steward can re-settle a SETTLED claim at its figure today. A DRAFT can be deleted (DELETED, counting
 *  nowhere) and restored to DRAFT on the sheet's today it was deleted. A claim whose due is above the review
 *  threshold goes to REVIEW when opened (counting nowhere, unnumbered) until a steward reviews it (OPEN). */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID' | 'DELETED' | 'REVIEW';

/** A status change a claim's buttons ask for. */
export type ClaimAction = 'open' | 'settle' | 'void' | 'approve' | 'reject' | 'resettle' | 'delete' | 'restore' | 'review';

/** Whether a claim in this status counts toward claimed, due-total and every total. */
export function counts(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'PENDING' || c.status === 'SETTLED';
}

/** Whether a claim in this status counts toward the totals; no status (not yet added) does not. */
export function countsAt(status: ClaimStatus | null): boolean {
  return status === 'OPEN' || status === 'PENDING' || status === 'SETTLED';
}

/** The status a claim had at the end of a day (YYYY-MM-DD), as its status log records it: its latest line
 *  dated on or before it; null when it was added after it. A claim with no log has its status now. */
export function statusAt(c: Claim, day: string): ClaimStatus | null {
  if (c.statusLog.length === 0) return c.status;
  let status: ClaimStatus | null = null;
  for (const l of c.statusLog) if (l.on <= day) status = l.status;
  return status;
}

/** Whether a claim is still open (OPEN or PENDING): it goes overdue, and a hold over its date takes it
 *  out of the live totals unless a statement binds it. */
export function isOpen(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'PENDING';
}

/** Whether a claim is bound by its person's last statement now: a PENDING claim too shows and totals
 *  the bound figure. */
export function bound(c: Claim): boolean {
  return c.stated;
}

export interface ClaimFields {
  amount: string;
  /** Blank takes the sheet's today. */
  date: string;
  note: string;
  /** The number of the settled claim a negative amount corrects; blank for any other claim. */
  corrects: string;
  /** The external reference, surrounding spaces removed; blank for none. */
  ref: string;
  /** The tags, comma separated; blank for none. */
  tags: string;
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
  /** The tags the sheet's claims have carried, as last read from the server. */
  private readonly storedTags = signal<string[]>([]);
  /** Every tag ever used on the sheet, sorted: those stored and those its claims carry now. */
  readonly usedTags = computed(() =>
    [...new Set([...this.storedTags(), ...this.claims().flatMap((c) => c.tags)])].sort(),
  );
  /** The ids of the claims chosen for a bulk action. */
  readonly selected = signal<ReadonlySet<number>>(new Set());
  private loads = 0;

  /** The claims that count in the live totals: those that count, less the OPEN and PENDING claims bound
   *  by no statement and dated within one of their person's holds or whose person's retirement date is
   *  reached, and the deferred claims. */
  readonly live = computed(() => {
    const leftOut = this.leftOut();
    return this.claims().filter((c) => counts(c) && !leftOut(c) && !this.deferred(c));
  });

  /** The claims a period's figures may count, whatever their status: all but the OPEN and PENDING claims
   *  bound by no statement and dated within one of their person's holds, and the deferred claims, as the
   *  live totals leave those out. */
  readonly periodCandidates = computed(() => {
    const leftOut = this.leftOut();
    return this.claims().filter((c) => !leftOut(c) && !this.deferred(c));
  });

  /** Whether a hold or a retirement leaves a claim out of the live totals: it is OPEN or PENDING, bound
   *  by no statement, and dated within one of its person's holds or its person's retirement date is
   *  reached on the sheet's today. */
  private readonly leftOut = computed(() => {
    const today = this.session.today();
    const byId = new Map(this.people.people().map((p) => [p.id, p]));
    return (c: Claim): boolean => {
      const p = byId.get(c.personId);
      return isOpen(c) && !bound(c) && p !== undefined
        && (heldOn(p.holds, c.date) || retiredBy(p, today));
    };
  });

  /** The claims that count toward claimed: the live ones, leaving out claims that correct another. */
  private readonly counted = computed(() => this.live().filter((c) => !c.corrects));

  /** Each person's claimed, in cents, by person id. */
  readonly claimedCents = computed(() => {
    const out = new Map<number, number>();
    for (const c of this.counted()) out.set(c.personId, (out.get(c.personId) ?? 0) + cents(c.amount));
    return out;
  });

  /** Everyone's claimed added up, with two decimals. */
  readonly total = computed(() => money(this.counted().reduce((sum, c) => sum + cents(c.amount), 0)));

  /** The date a claim is due by: fixed when it was added, from the terms of its person's grade then. */
  dueBy(c: Claim): string {
    return c.dueBy;
  }

  /** The date a claim's lateness runs from: its due-by plus the grace days in force on its date of its
   *  person's grade in force then (none: the due-by). */
  lateFrom(c: Claim): string {
    const p = this.people.people().find((x) => x.id === c.personId);
    const grace = p === undefined ? 0 : this.session.graceOn(gradeOn(p.grades, c.date), c.date);
    return grace === 0 ? this.dueBy(c) : addDays(this.dueBy(c), grace);
  }

  /** Whether a claim is overdue: OPEN or PENDING, not deferred, with its due-by plus its grace (see
   *  lateFrom) before the sheet's today. */
  overdue(c: Claim): boolean {
    const today = this.session.today();
    return isOpen(c) && !this.deferred(c) && today !== null && this.lateFrom(c) < today;
  }

  /** Whether a REVIEW claim is late for review: its review day (the sheet's today when it last went to
   *  REVIEW, as its status log records it) plus the sheet's review SLA is before the sheet's today. Never
   *  while the sheet has no SLA. */
  reviewLate(c: Claim): boolean {
    const today = this.session.today();
    const sla = this.session.reviewSla();
    if (c.status !== 'REVIEW' || today === null || sla === null) return false;
    const went = c.statusLog.filter((l) => l.status === 'REVIEW').at(-1);
    return went !== undefined && addDays(went.on, sla) < today;
  }

  /** Whether a claim shows as deferred: its deferred-until date is after the sheet's today, whatever its
   *  status (a deferred claim voided keeps showing it). Only an OPEN one is left out of totals (`deferred`). */
  deferredShown(c: Claim): boolean {
    const today = this.session.today();
    return c.deferredUntil !== null && today !== null && today < c.deferredUntil;
  }

  /** Whether a claim is deferred: OPEN, with a deferred-until date after the sheet's today. Until then it
   *  counts in no total, is not overdue and cannot be settled. */
  deferred(c: Claim): boolean {
    const today = this.session.today();
    return c.status === 'OPEN' && c.deferredUntil !== null && today !== null && today < c.deferredUntil;
  }

  /** The late charge, in cents, on a claim's figure (in cents): while it is overdue, for each full 30 days
   *  past its due-by plus its grace (see lateFrom), the sheet's late percent in force on that boundary date
   *  (that date plus 30, 60, 90 ... days) of the figure plus the charges of the boundaries before it, rounded to cents as its date rounds, the charges added up; none otherwise, and none
   *  on a correction or a waived claim. Frozen into the due when bound by a statement or settled. */
  lateCents(c: Claim, figureCents: bigint): bigint {
    const today = this.session.today();
    if (!this.overdue(c) || today === null || c.amount.startsWith('-') || c.waived) return 0n;
    const from = this.lateFrom(c);
    const periods = Math.floor(daysBetween(from, today) / 30);
    const halfUp = this.session.roundsHalfUp(c.date);
    let total = 0n;
    for (let k = 1; k <= periods; k++) {
      total += rounded((figureCents + total) * hundredths(this.session.latePctOn(addDays(from, 30 * k))), 10000n, halfUp);
    }
    return total;
  }

  /** A claim's due from its figure (the rate figure, or the one its statement bound), in cents: the figure
   *  with its late charge on top, at least the sheet's floor in force on the claim's date (the floor applied
   *  last); a correction takes neither charge nor floor, and a bound figure already holds both. */
  chargedCents(c: Claim, figureCents: bigint): bigint {
    if (c.amount.startsWith('-') || bound(c)) return figureCents;
    const due = figureCents + this.lateCents(c, figureCents);
    const floor = hundredths(this.session.floorOn(c.date));
    return due < floor ? floor : due;
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
    this.storedTags.set([]);
    this.selected.set(new Set());
    if (!user || !sid) return null;
    try {
      const [claims, tags] = await Promise.all([
        firstValueFrom(this.http.get<Claim[]>(`${this.url(sid)}/claims`, { headers: { 'X-User': user } })),
        firstValueFrom(this.http.get<string[]>(`${this.url(sid)}/claim-tags`, { headers: { 'X-User': user } })),
      ]);
      if (load === this.loads) {
        this.claims.set(claims);
        this.storedTags.set(tags);
      }
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

  /** Add a DRAFT claim for a person with the named template's amount and note as they are now, dated as a
   *  claim added with no date; an error message when refused. */
  async addFromTemplate(personId: number, template: string): Promise<string | null> {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    if (!template) return 'Choose a claim template.';
    const sid = this.session.sheetId()!;
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(`${this.url(sid)}/people/${personId}/template-claims`, { template }, {
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
    const refused = this.check({ amount, date: '', note: '', corrects: '', ref: '', tags: '' });
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

  /** Add and open a claim for each pasted line of name,amount,date,note, in order, for the person on the
   *  sheet with the name (a line matching a DRAFT updates and opens it instead); how many lines went
   *  through, or an error message when any line is refused (then the server changes nothing). */
  async paste(text: string): Promise<number | string> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    if (!text.trim()) return 'Paste at least one line of name,amount,date,note.';
    try {
      const done = await firstValueFrom(
        this.http.post<{ added: number; claims: Claim[] }>(
          `${this.url(sid)}/claims/paste`,
          { text },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.session.sheetId() === sid) {
        this.claims.update((cs) => {
          const byId = new Map(done.claims.map((c) => [c.id, c]));
          const kept = cs.map((c) => byId.get(c.id) ?? c);
          const fresh = done.claims.filter((c) => !cs.some((k) => k.id === c.id));
          return [...fresh.reverse(), ...kept];
        });
      }
      return done.added;
    } catch (e) {
      return failed('add', e).replace(' claim', ' claims');
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
      if (this.session.sheetId() === sid) {
        // a tag the edit took off stays among those used on the sheet
        const was = this.claims().find((c) => c.id === id)?.tags ?? [];
        this.storedTags.update((ts) => [...new Set([...ts, ...was])]);
        this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      }
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        return { message: message(e) ?? 'Only a DRAFT claim can be edited.', onClaim: !onForm(e) };
      }
      return { message: failed('save', e), onClaim: false };
    }
  }

  /** Open a DRAFT claim's editor, locking it for every other user; an error message when refused (not a
   *  DRAFT, in a closed period, another user's editor is open on it, or the user's role may not edit). */
  async lock(id: number): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(`${this.url(sid)}/claims/${id}/lock`, null, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 403) return message(e) ?? 'Your role may not edit a claim.';
      return failed('edit', e);
    }
  }

  /** Close a claim's editor without saving, releasing the user's lock on it at once on the screen and then
   *  on the server. */
  async unlock(id: number): Promise<void> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return;
    this.claims.update((cs) => cs.map((c) => (c.id === id && c.lockedBy === user ? { ...c, lockedBy: null } : c)));
    try {
      const claim = await firstValueFrom(
        this.http.delete<Claim>(`${this.url(sid)}/claims/${id}/lock`, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
    } catch {
      // the lock is gone or was never held: the editor is closed either way
    }
  }

  /** The sheet's today changed: the server released every claim's lock, so every editor closes. */
  unlockAll(): void {
    this.claims.update((cs) => cs.map((c) => (c.lockedBy === null ? c : { ...c, lockedBy: null })));
  }

  /** Open, settle, void, approve, reject, re-settle, delete or restore a claim (a rejection with its reason typed, a void with
   *  its reason chosen from the sheet's list); an error message when refused. */
  async act(id: number, action: ClaimAction, reason = ''): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    if (action === 'reject' && !reason.trim()) return 'Give a reason to reject the claim.';
    try {
      const body = action === 'reject' || action === 'void' ? { reason: reason.trim() } : null;
      const claim = await firstValueFrom(
        this.http.post<Claim>(`${this.url(sid)}/claims/${id}/${action}`, body, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) return refusal(e, action);
      if (e instanceof HttpErrorResponse && e.status === 403) return `Your role may not ${action} a claim.`;
      return failed(action, e);
    }
  }

  /** Raise a claim's proofs count by one; an error message when refused (a SETTLED or VOID claim, or one in
   *  a closed period). */
  async addProof(id: number): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(`${this.url(sid)}/claims/${id}/proof`, null, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      return null;
    } catch (e) {
      return failed('add a proof to', e);
    }
  }

  /** Remind an OPEN or PENDING claim, once per sheet's today; an error message when refused. */
  async remind(id: number): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(`${this.url(sid)}/claims/${id}/remind`, null, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      return null;
    } catch (e) {
      return failed('remind', e);
    }
  }

  /** Waive an OPEN or PENDING claim's late charge, by a steward; an error message when refused. */
  async waive(id: number): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(`${this.url(sid)}/claims/${id}/waive`, null, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      return null;
    } catch (e) {
      return failed('waive', e);
    }
  }

  /** Comment on a claim; an error message when refused (an empty comment). */
  async comment(id: number, text: string): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    if (!text.trim()) return 'Enter a comment.';
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(`${this.url(sid)}/claims/${id}/comments`, { text: text.trim() }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      return null;
    } catch (e) {
      return failed('comment on', e);
    }
  }

  /** Add a line to a DRAFT claim; an error message when refused (no label, a quantity that is not a whole
   *  number of at least 1, a unit amount not of up to two decimals, a claim that is not a DRAFT, in a closed
   *  period, or another user's editor is open on it). */
  async addLine(id: number, label: string, qty: string, unit: string): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    if (!label.trim()) return 'Enter a label for the line.';
    if (!/^\d+$/.test(qty.trim()) || Number(qty.trim()) < 1) return 'Enter a whole quantity of at least 1.';
    if (!/^\d+(\.\d{1,2})?$/.test(unit.trim())) return 'Enter a unit amount with up to two decimals.';
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(
          `${this.url(sid)}/claims/${id}/lines`,
          { label: label.trim(), qty: Number(qty.trim()), unit: unit.trim() },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      return null;
    } catch (e) {
      return failed('add a line to', e);
    }
  }

  /** Defer an OPEN claim to a date after the sheet's today; an error message when refused. */
  async defer(id: number, until: string): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    if (!isDate(until.trim())) return 'Enter the date to defer to as YYYY-MM-DD.';
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(`${this.url(sid)}/claims/${id}/defer`, { until: until.trim() }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      return null;
    } catch (e) {
      return failed('defer', e);
    }
  }

  /** Re-date an OPEN or PENDING claim (a steward's action); a blank date is the sheet's today. Its due-by
   *  and any bound figure stay; its number may follow the new date's period. An error message when refused. */
  async redate(id: number, date: string): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    if (date.trim() !== '' && !isDate(date.trim())) return 'Enter the new date as YYYY-MM-DD.';
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(
          `${this.url(sid)}/claims/${id}/redate`,
          { date: date.trim() === '' ? null : date.trim() },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.session.sheetId() === sid) this.claims.update((cs) => cs.map((c) => (c.id === id ? claim : c)));
      return null;
    } catch (e) {
      return failed('re-date', e);
    }
  }

  /** Split an amount (above 0.00, below the claim's) off an OPEN claim: the claim keeps the rest, unbound
   *  from any statement, and a new OPEN claim of the same person with the amount split off is listed
   *  newest; an error message when refused. */
  async split(id: number, amount: string): Promise<string | null> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    if (!/^\d+(\.\d{1,2})?$/.test(amount.trim())) return 'Enter the amount to split off, like 12.50.';
    try {
      const done = await firstValueFrom(
        this.http.post<{ kept: Claim; split: Claim }>(
          `${this.url(sid)}/claims/${id}/split`,
          { amount: amount.trim() },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.session.sheetId() === sid) {
        this.claims.update((cs) => [done.split, ...cs.map((c) => (c.id === id ? done.kept : c))]);
      }
      return null;
    } catch (e) {
      return failed('split', e);
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

  /** Settle the chosen claims together, the server settling them in list order as a single settle would
   *  (one a clerk puts PENDING counts as settled) and leaving each that cannot be settled as it is; how
   *  many were settled and refused, or an error message. One that settles any is the sheet's next batch,
   *  which becomes its last. */
  async bulkSettle(): Promise<{ settled: number; refused: number } | string> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    const claimIds = [...this.selected()];
    if (claimIds.length === 0) return 'Select the claims to settle.';
    try {
      const done = await firstValueFrom(
        this.http.post<{ settled: number; refused: number; claims: Claim[]; batch: number | null }>(
          `${this.url(sid)}/claims/bulk-settle`,
          { claimIds },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.session.sheetId() === sid) {
        const byId = new Map(done.claims.map((c) => [c.id, c]));
        this.claims.update((cs) => cs.map((c) => byId.get(c.id) ?? c));
        this.selected.set(new Set());
        if (done.batch !== null) this.session.lastSettleBatch.set(done.batch);
      }
      return { settled: done.settled, refused: done.refused };
    } catch (e) {
      return failed('settle', e).replace(' claim', ' claims');
    }
  }

  /** Move the chosen claims to a person together, the server moving them in list order as a single reassign
   *  would and leaving each that cannot be moved as it is; how many were moved and refused, or an error
   *  message. */
  async bulkReassign(personId: number | null): Promise<{ reassigned: number; refused: number } | string> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    const claimIds = [...this.selected()];
    if (claimIds.length === 0) return 'Select the claims to move.';
    if (personId === null) return 'Choose the person to move the claims to.';
    try {
      const done = await firstValueFrom(
        this.http.post<{ reassigned: number; refused: number; claims: Claim[] }>(
          `${this.url(sid)}/claims/bulk-reassign`,
          { claimIds, personId },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.session.sheetId() === sid) {
        // each moved claim became the sheet's newest in turn, so the last moved is listed first
        const moved = new Set(done.claims.map((c) => c.id));
        this.claims.update((cs) => [...done.claims].reverse().concat(cs.filter((c) => !moved.has(c.id))));
        this.selected.set(new Set());
      }
      return { reassigned: done.reassigned, refused: done.refused };
    } catch (e) {
      return failed('reassign', e).replace(' claim', ' claims');
    }
  }

  /** Re-date the chosen claims to one date together, the server re-dating them in list order as a single
   *  re-date would and leaving each that cannot be re-dated as it is; a blank date is the sheet's today.
   *  How many were re-dated and refused, or an error message. */
  async bulkRedate(date: string): Promise<{ redated: number; refused: number } | string> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    const claimIds = [...this.selected()];
    if (claimIds.length === 0) return 'Select the claims to re-date.';
    if (date.trim() !== '' && !isDate(date.trim())) return 'Enter the new date as YYYY-MM-DD.';
    try {
      const done = await firstValueFrom(
        this.http.post<{ redated: number; refused: number; claims: Claim[] }>(
          `${this.url(sid)}/claims/bulk-redate`,
          { claimIds, date: date.trim() === '' ? null : date.trim() },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.session.sheetId() === sid) {
        const byId = new Map(done.claims.map((c) => [c.id, c]));
        this.claims.update((cs) => cs.map((c) => byId.get(c.id) ?? c));
        this.selected.set(new Set());
      }
      return { redated: done.redated, refused: done.refused };
    } catch (e) {
      return failed('re-date', e).replace(' claim', ' claims');
    }
  }

  private check(f: ClaimFields): string | null {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    if (!/^-?\d+(\.\d{1,2})?$/.test(f.amount.trim())) return 'Enter an amount with up to two decimals.';
    if (f.date.trim() && !isDate(f.date.trim())) return 'Enter a date as YYYY-MM-DD.';
    const corrects = f.corrects.trim();
    if (corrects.length > 220) return 'Enter the number of the claim it corrects.';
    if (f.ref.trim().length > 200) return 'Enter a reference of up to 200 characters.';
    if (f.tags.length > 2000) return 'Enter tags of up to 2000 characters.';
    if (f.amount.trim().startsWith('-') && !corrects) {
      return 'A negative amount is a correction: name the number of the settled claim it corrects.';
    }
    return null;
  }

  private body(f: ClaimFields): {
    amount: string;
    date: string | null;
    note: string;
    corrects: string | null;
    ref: string | null;
    tags: string;
  } {
    const corrects = f.corrects.trim();
    return {
      amount: f.amount.trim(),
      date: f.date.trim() || null,
      note: f.note.trim(),
      corrects: corrects || null,
      ref: f.ref.trim() || null,
      tags: f.tags,
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

/** Whether a refusal is of a field typed in the form (a reference already used) rather than of the claim. */
function onForm(e: HttpErrorResponse): boolean {
  const err: unknown = e.error;
  return !!err && typeof err === 'object' && 'field' in err && err.field === 'ref';
}

function failed(what: string, e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return message(e) ?? `Could not ${what} claim (409).`;
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter an amount with up to two decimals.';
  return e instanceof HttpErrorResponse ? `Could not ${what} claim (${e.status}).` : `Could not ${what} claim.`;
}
