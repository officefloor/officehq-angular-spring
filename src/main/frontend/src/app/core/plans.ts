import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Claim, Claims } from './claims';
import { Session, isDate } from './session';

/** A person's plan: a DRAFT claim of its amount on every plan date (the start, then every so many days; or
 *  monthly on a day of the month) up to its end, and how many claims it has made so far. */
export interface Plan {
  id: number;
  personId: number;
  amount: string;
  start: string;
  /** Every so many days; null for a monthly plan. */
  every: number | null;
  /** The day of the month (1 to 31) of a monthly plan; null for one run every so many days. */
  day: number | null;
  end: string;
  generated: number;
  /** The first five plan dates after the sheet's today and on or before the end, earliest first. */
  nextDates: string[];
  /** Paused and not yet resumed: its dates from the day paused until the day resumed are skipped. */
  paused: boolean;
  /** The changes of its amount, oldest first: the day made and the new amount. */
  changes: { on: string; amount: string }[];
}

/** A plan's fields as typed. */
export interface PlanFields {
  amount: string;
  start: string;
  every: string;
  day: string;
  end: string;
}

// The active sheet's plans, in the order they were added, re-read from the server each time the sheet is
// loaded.
@Injectable({ providedIn: 'root' })
export class Plans {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  private readonly claims = inject(Claims);

  readonly plans = signal<Plan[]>([]);
  private loads = 0;

  /** Re-read the active sheet's plans from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.plans.set([]);
    if (!user || !sid) return null;
    try {
      const plans = await firstValueFrom(this.http.get<Plan[]>(this.url(sid), { headers: { 'X-User': user } }));
      if (load === this.loads) this.plans.set(plans);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load plans (${e.status}).` : 'Could not load plans.';
    }
  }

  /** Add a plan to a person, run every so many days or, with a day of the month typed, monthly on that day;
   *  an error message when refused (a bad amount, interval or day, or an end before the start). */
  async add(personId: number, f: PlanFields): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    if (!user || !sid) return 'Load a sheet first.';
    const amount = f.amount.trim();
    const start = f.start.trim() || (this.session.today() ?? '');
    const every = f.every.trim();
    const day = f.day.trim();
    const end = f.end.trim();
    if (!/^\d+(\.\d{1,2})?$/.test(amount)) return 'Enter an amount of 0.00 or more with up to two decimals.';
    if (!isDate(start)) return 'Enter a start date as YYYY-MM-DD.';
    if (day) {
      if (every) return 'Enter either every how many days or a day of the month, not both.';
      if (!/^\d+$/.test(day) || Number(day) < 1 || Number(day) > 31) return 'Enter a day of the month from 1 to 31.';
    } else if (!/^\d+$/.test(every) || Number(every) < 1) return 'Enter every how many days, 1 or more.';
    if (!isDate(end)) return 'Enter an end date as YYYY-MM-DD.';
    if (end < start) return 'The end cannot be before the start.';
    try {
      const plan = await firstValueFrom(
        this.http.post<Plan>(
          `/api/sheets/${encodeURIComponent(sid)}/people/${personId}/plans`,
          day ? { amount, start, day: Number(day), end } : { amount, start, every: Number(every), end },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.session.sheetId() === sid) this.plans.update((ps) => [...ps, plan]);
      return null;
    } catch (e) {
      return failed('add plan', e);
    }
  }

  /** Pause a running plan or resume a paused one from the sheet's today; an error message when refused. */
  async setPaused(planId: number, paused: boolean): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const plan = await firstValueFrom(
        this.http.post<Plan>(`${this.url(sid)}/${planId}/${paused ? 'pause' : 'resume'}`, {}, {
          headers: { 'X-User': user },
        }),
      );
      if (this.session.sheetId() === sid) this.plans.update((ps) => ps.map((p) => (p.id === plan.id ? plan : p)));
      return null;
    } catch (e) {
      return failed(paused ? 'pause plan' : 'resume plan', e);
    }
  }

  /** Change a plan's amount from the sheet's today, for the claims it makes from then on; an error message when
   *  refused (a bad amount). */
  async changeAmount(planId: number, amount: string): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    if (!user || !sid) return 'Load a sheet first.';
    const a = amount.trim();
    if (!/^\d+(\.\d{1,2})?$/.test(a)) return 'Enter an amount of 0.00 or more with up to two decimals.';
    try {
      const plan = await firstValueFrom(
        this.http.post<Plan>(`${this.url(sid)}/${planId}/amount`, { amount: a }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.plans.update((ps) => ps.map((p) => (p.id === plan.id ? plan : p)));
      return null;
    } catch (e) {
      return failed('change plan amount', e);
    }
  }

  /** Make the plans' claims due by the sheet's today: how many were made, or an error message when refused. */
  async generate(): Promise<number | string> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const done = await firstValueFrom(
        this.http.post<{ made: number; claims: Claim[]; plans: Plan[] }>(
          `${this.url(sid)}/generate`,
          {},
          { headers: { 'X-User': user } },
        ),
      );
      if (this.session.sheetId() === sid) {
        this.claims.claims.update((cs) => [...[...done.claims].reverse(), ...cs]);
        this.plans.set(done.plans);
      }
      return done.made;
    } catch (e) {
      return failed('generate plans', e);
    }
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/plans`;
  }
}

function failed(what: string, e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    const err: unknown = e.error;
    if (err && typeof err === 'object' && 'message' in err && typeof err.message === 'string' && err.message) {
      return err.message;
    }
    if (e.status === 400) return 'Enter an amount with up to two decimals, dates as YYYY-MM-DD and every 1 or more days.';
    return `Could not ${what} (${e.status}).`;
  }
  return `Could not ${what}.`;
}
