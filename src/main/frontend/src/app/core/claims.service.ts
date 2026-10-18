import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
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
 * (looked up by `rateOn`, in hundredths of a percent), raised to the sheet's floor (in cents);
 * undefined when it has none. An overdue claim's figure gains its late charge on top (never bound).
 */
export function claimDue(
  c: Claim, rateOn: (date: string) => number | undefined, floor: number, late?: LateTerms,
): number | undefined {
  if (c.due !== null) return cents(c.due);
  const figure = baseDue(c, rateOn, floor);
  return figure === undefined || !late ? figure : figure + lateCharge(c, figure, late);
}

function baseDue(c: Claim, rateOn: (date: string) => number | undefined, floor: number): number | undefined {
  if (c.stated) return c.statedDue === null ? undefined : cents(c.statedDue);
  const rate = rateOn(c.date);
  return rate === undefined ? undefined : Math.max(due(cents(c.amount), rate), floor);
}

/** The late charge in cents on a figure: the late percent of it for every full 30 days the claim is overdue, rounded like due. */
export function lateCharge(c: Claim, figure: number, late: LateTerms): number {
  if (late.today === null || !overdue(c, late.terms, late.today)) return 0;
  const days = (Date.parse(late.today) - Date.parse(dueBy(c, late.terms))) / 86_400_000;
  return due(figure, late.pct * Math.floor(days / 30));
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
        if (this.sheets.sheetId() === id) this.list.set(claims);
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
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2));
}

/** 1234 as "12.34". */
export function money(cents: number): string {
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}
