import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, map, of, switchMap, tap } from 'rxjs';
import { PeopleService } from './people.service';
import { due } from './rates.service';
import { SessionService } from './session.service';
import { SheetService } from './sheet.service';

export interface Claim {
  id: number;
  personId: number;
  /** Text with two decimals, e.g. "10.00". */
  amount: string;
  /** yyyy-MM-dd */
  date: string;
  note: string;
  status: ClaimStatus;
  /** Given when opened; null while a draft and once voided. */
  number: number | null;
  /** A settled claim's due, fixed when it was settled, as text with two decimals; null while live, or when its grade had no rate then (it then follows the rates). */
  due: string | null;
  /** The user who opened it; null while a draft. They may not settle it. */
  openedBy: string | null;
  /** Bound by its person's last statement: it shows and totals `statedDue` until the next one. */
  stated: boolean;
  /** The figure it had at that statement, as text with two decimals; null when it had none (n/a). */
  statedDue: string | null;
  /** A correction's (negative amount) number of the settled claim of the same person it corrects; null otherwise. */
  corrects: number | null;
}

/** What a bulk open did: how many claims were opened and how many refused. */
export interface BulkOpened {
  opened: number;
  refused: number;
}

export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** A change of status a claim's buttons ask for. */
export type ClaimAction = 'open' | 'settle' | 'void';

/** Only open and settled claims count toward claimed and due; drafts and voided claims do not. */
export function counts(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'SETTLED';
}

/** A claim keeps a figure that does not follow the rates: settled with a fixed due, or bound by a statement to one (not n/a). */
export function fixed(c: Claim): boolean {
  return c.due !== null || (c.stated && c.statedDue !== null);
}

/** What an overdue claim's late charge follows: the terms (days), the sheet's today (yyyy-MM-dd; null: none loaded) and the late percent (in hundredths of a percent). */
export interface LateTerms {
  terms: number;
  today: string | null;
  pct: number;
}

/**
 * A claim's due in cents: a settled claim's fixed figure (any late charge frozen in), else the figure
 * its person's last statement bound it to, otherwise its amount times the rate in force on its date
 * (looked up by `rateOn`, in hundredths of a percent), raised to the sheet's floor (in cents) unless a correction;
 * undefined when it has none. An overdue claim's figure gains its late charge on top (never bound).
 * Its live figures round half up when `halfUp` says so for it, else half to even.
 */
export function claimDue(
  c: Claim, rateOn: (date: string) => number | undefined, floor: number, late?: LateTerms,
  halfUp: (c: Claim) => boolean = () => false,
): number | undefined {
  if (c.due !== null) return cents(c.due);
  const up = halfUp(c);
  const figure = baseDue(c, rateOn, floor, up);
  return figure === undefined || !late || correction(c) ? figure : figure + lateCharge(c, figure, late, up);
}

function baseDue(
  c: Claim, rateOn: (date: string) => number | undefined, floor: number, halfUp: boolean,
): number | undefined {
  if (c.stated) return c.statedDue === null ? undefined : cents(c.statedDue);
  const rate = rateOn(c.date);
  if (rate === undefined) return undefined;
  const d = due(cents(c.amount), rate, halfUp);
  return correction(c) ? d : Math.max(d, floor);
}

/** A correction: a negative amount; its due has no floor and no late charge. */
export function correction(c: Claim): boolean {
  return c.amount.startsWith('-');
}

/** The late charge in cents on a figure: the late percent of it for every full 30 days the claim is overdue, rounded like due. */
export function lateCharge(c: Claim, figure: number, late: LateTerms, halfUp = false): number {
  if (late.today === null || !overdue(c, late.terms, late.today)) return 0;
  const days = (Date.parse(late.today) - Date.parse(dueBy(c, late.terms))) / 86_400_000;
  return due(figure, late.pct * Math.floor(days / 30), halfUp);
}

/** The date (yyyy-MM-dd) a claim is due by: its date plus the terms (days). */
export function dueBy(c: Claim, terms: number): string {
  const [y, m, d] = c.date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + terms)).toISOString().slice(0, 10);
}

/** A claim is overdue when it is open and its due-by is before the sheet's today (yyyy-MM-dd; null: none loaded). */
export function overdue(c: Claim, terms: number, today: string | null): boolean {
  return today !== null && c.status === 'OPEN' && dueBy(c, terms) < today;
}

export interface ClaimForm {
  amount: string;
  /** null: the sheet's today. */
  date: string | null;
  note: string;
  /** For a correction (a negative amount): the number of the settled claim it corrects. */
  corrects: number | null;
}

// The active sheet's claims, newest first, and what they add up to (kept in cents so sums are exact).
@Injectable({ providedIn: 'root' })
export class ClaimsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly sheets = inject(SheetService);
  private readonly people = inject(PeopleService);
  private readonly list = signal<Claim[]>([]);

  readonly claims = this.list.asReadonly();

  /** The ids of the claims chosen for a bulk open. */
  readonly selected = signal<ReadonlySet<number>>(new Set());

  /** Choose a claim for a bulk open, or leave it out. */
  select(claimId: number, on: boolean): void {
    this.selected.update((all) => {
      const out = new Set(all);
      if (on) out.add(claimId);
      else out.delete(claimId);
      return out;
    });
  }

  /** The ids of the people on hold. */
  private readonly heldIds = computed(
    () => new Set(this.people.people().filter((p) => p.held).map((p) => p.id)),
  );

  /** Whether a claim counts in the live totals: open and settled ones do, except a held person's open ones. */
  readonly live = computed(() => {
    const held = this.heldIds();
    return (c: Claim) => counts(c) && !(c.status === 'OPEN' && held.has(c.personId));
  });

  /** Each person's claims, newest first. */
  readonly byPerson = computed(() => {
    const out = new Map<number, Claim[]>();
    for (const c of this.list()) out.set(c.personId, [...(out.get(c.personId) ?? []), c]);
    return out;
  });

  /** Each person's claimed (live claims), in cents. */
  readonly claimedCents = computed(() => {
    const out = new Map<number, number>();
    for (const c of this.list().filter(this.live())) out.set(c.personId, (out.get(c.personId) ?? 0) + cents(c.amount));
    return out;
  });

  /** Everyone's claimed (live claims) added up, in cents. */
  readonly totalCents = computed(() => this.list().filter(this.live()).reduce((sum, c) => sum + cents(c.amount), 0));

  /**
   * The date a claim rounds by: a correction's is the date of the claim it corrects (its own when
   * that is not found), any other claim's its own.
   */
  readonly roundingDate = computed(() => {
    const byNumber = new Map<string, string>();
    for (const c of this.list()) if (c.number !== null) byNumber.set(`${c.personId}:${c.number}`, c.date);
    return (c: Claim) => (c.corrects === null ? c.date : byNumber.get(`${c.personId}:${c.corrects}`) ?? c.date);
  });

  /** Re-read the active sheet's claims from the store. */
  reload(): Observable<unknown> {
    const id = this.sheets.sheetId();
    if (!id) {
      this.list.set([]);
      return of(null);
    }
    return this.http
      .get<Claim[]>(this.url(id), { headers: this.session.headers() })
      .pipe(tap((claims) => {
        if (this.sheets.sheetId() !== id) return;
        this.list.set(claims);
        const ids = new Set(claims.map((c) => c.id));
        this.selected.update((all) => new Set([...all].filter((c) => ids.has(c))));
      }));
  }

  add(personId: number, form: ClaimForm): Observable<Claim> {
    const sheet = this.sheet();
    return this.http
      .post<Claim>(`/api/sheets/${encodeURIComponent(sheet)}/people/${personId}/claims`, form, {
        headers: this.session.headers(),
      })
      .pipe(tap((c) => this.list.update((all) => [c, ...all])));
  }

  /**
   * Add a draft claim of this amount, dated the sheet's today, for the person with this name; one
   * not on the sheet is added first with grade A. The caller re-reads the people.
   */
  quickAdd(name: string, amount: string): Observable<Claim> {
    const sheet = this.sheet();
    return this.http
      .post<Claim>(`/api/sheets/${encodeURIComponent(sheet)}/quick-add`, { name, amount }, {
        headers: this.session.headers(),
      })
      .pipe(tap((c) => this.list.update((all) => [c, ...all])));
  }

  /** Take a statement for a person, binding their counting claims; the caller re-reads the people. */
  statement(personId: number): Observable<Claim[]> {
    const sheet = this.sheet();
    return this.http
      .post<Claim[]>(`/api/sheets/${encodeURIComponent(sheet)}/people/${personId}/statement`, null, {
        headers: this.session.headers(),
      })
      .pipe(tap((theirs) => {
        const byId = new Map(theirs.map((c) => [c.id, c]));
        this.list.update((all) => all.map((o) => byId.get(o.id) ?? o));
      }));
  }

  edit(claimId: number, form: ClaimForm): Observable<Claim> {
    return this.http
      .put<Claim>(`${this.url(this.sheet())}/${claimId}`, form, { headers: this.session.headers() })
      .pipe(tap((c) => this.list.update((all) => all.map((o) => (o.id === c.id ? c : o)))));
  }

  changeStatus(claimId: number, action: ClaimAction): Observable<Claim> {
    return this.http
      .post<Claim>(`${this.url(this.sheet())}/${claimId}/${action}`, null, { headers: this.session.headers() })
      .pipe(tap((c) => this.list.update((all) => all.map((o) => (o.id === c.id ? c : o)))));
  }

  /** Open the chosen claims that can be opened, numbered in list order; then re-read the claims. */
  bulkOpen(claimIds: readonly number[]): Observable<BulkOpened> {
    return this.http
      .post<BulkOpened>(`${this.url(this.sheet())}/bulk-open`, { claimIds }, { headers: this.session.headers() })
      .pipe(switchMap((r) => this.reload().pipe(map(() => r))));
  }

  private sheet(): string {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return id;
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/claims`;
  }
}

/** "12.34" as 1234. */
export function cents(amount: string): number {
  const negative = amount.startsWith('-');
  const [whole, frac = ''] = (negative ? amount.slice(1) : amount).split('.');
  const out = Number(whole) * 100 + Number((frac + '00').slice(0, 2));
  return negative ? -out : out;
}

/** 1234 as "12.34". */
export function money(cents: number): string {
  const abs = Math.abs(cents);
  return `${cents < 0 ? '-' : ''}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}
