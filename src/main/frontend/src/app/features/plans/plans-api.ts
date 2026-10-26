import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { ClaimView } from '../claims/claims-api';

/** A person's plan: a claim of the amount (two decimals) on the start date and every `every` days after
 *  it, or, for a monthly plan, on its `day` of every month from the start's month (a day beyond the
 *  month's length falling on its last day, those before the start left out), up to the end date
 *  (yyyy-mm-dd). Generated is how many claims it has made so far; next dates are its first five plan
 *  dates after the sheet's today and on or before the end, those a pause skips left out, oldest first.
 *  Paused is whether it is paused now: a plan date on or after the day it was paused and before the day
 *  it was resumed is skipped, not postponed. */
export interface PlanView {
  id: number;
  personId: number;
  amount: string;
  start: string;
  every: number | null;
  day: number | null;
  end: string;
  generated: number;
  nextDates: string[];
  paused: boolean;
  /** Its amount changes, oldest first, each as `<date> amount=<amount>`. */
  changes: string[];
  /** The descendant sheet its claims are made on, for the person of the same name there; null for this one. */
  sheet: string | null;
}

/** A plan's fields as entered; a null start is the sheet's today. A plan has either every or day. A
 *  sheet names one of the sheet's descendants to make its claims on; null is the sheet itself. */
export interface PlanFields {
  amount: string;
  start: string | null;
  every: string | null;
  day: string | null;
  end: string;
  sheet: string | null;
}

/** A plan after its amount was changed, and the DRAFT claims it generated that took the new amount,
 *  oldest first (on the sheet its claims are made on). */
export interface AmountView {
  plan: PlanView;
  claims: ClaimView[];
}

/** What a generate made: how many claims, the claims oldest first, and the sheet's plans then. */
export interface GenerateView {
  generated: number;
  claims: ClaimView[];
  plans: PlanView[];
}

// The plans of a sheet, as the store holds them.
@Injectable({ providedIn: 'root' })
export class PlansApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/plans`;
  }

  /** The sheet's plans in the order they were added. */
  list(sheetId: string): Promise<PlanView[]> {
    return firstValueFrom(this.http.get<PlanView[]>(this.url(sheetId)));
  }

  /** Add a plan to a person: refused for an amount not above 0.00 with up to two decimals, neither or
   *  both of an interval and a day of the month, an interval that is not a whole number of at least 1, a
   *  day of the month outside 1 to 31, an end before the start, or a sheet that is not a descendant. */
  add(sheetId: string, personId: number, f: PlanFields): Promise<PlanView> {
    return firstValueFrom(this.http.post<PlanView>(this.url(sheetId),
      { personId, amount: f.amount, start: f.start, every: f.every, day: f.day, end: f.end, sheet: f.sheet }));
  }

  /** Pause (true) or resume (false) a plan on the sheet's today: refused for pausing a paused plan or
   *  resuming a running one. */
  pause(sheetId: string, planId: number, on: boolean): Promise<PlanView> {
    return firstValueFrom(this.http.post<PlanView>(`${this.url(sheetId)}/${planId}/${on ? 'pause' : 'resume'}`, {}));
  }

  /** Change a plan's amount on the sheet's today: refused for an amount not above 0.00 with up to two
   *  decimals. The claims it generated that are still DRAFT take the new amount too. */
  changeAmount(sheetId: string, planId: number, amount: string): Promise<AmountView> {
    return firstValueFrom(this.http.post<AmountView>(`${this.url(sheetId)}/${planId}/amount`, { amount }));
  }

  /** Make every plan's claims due by the sheet's today. */
  generate(sheetId: string): Promise<GenerateView> {
    return firstValueFrom(this.http.post<GenerateView>(`${this.url(sheetId)}/generate`, {}));
  }
}

// The active sheet's plans. Each load of a sheet re-reads them from the store.
@Injectable({ providedIn: 'root' })
export class Plans {
  private readonly session = inject(Session);
  private readonly api = inject(PlansApi);

  private readonly state = signal<PlanView[]>([]);
  readonly plans = this.state.asReadonly();

  constructor() {
    effect(() => {
      const sheet = this.session.loaded();
      this.state.set([]);
      if (sheet) void this.load(sheet.id);
    });
  }

  private async load(sheetId: string): Promise<void> {
    try {
      const list = await this.api.list(sheetId);
      if (this.session.sheet()?.id === sheetId) this.state.set(list);
    } catch {
      // The list stays empty; the next load of the sheet re-reads it.
    }
  }

  /** Add a plan to a person of the active sheet. */
  async add(sheetId: string, personId: number, f: PlanFields): Promise<void> {
    const added = await this.api.add(sheetId, personId, f);
    if (this.session.sheet()?.id !== sheetId) return;
    this.state.update((list) => [...list.filter((p) => p.id !== added.id), added]);
  }

  /** Pause (true) or resume (false) a plan of the active sheet. */
  async pause(sheetId: string, planId: number, on: boolean): Promise<void> {
    const changed = await this.api.pause(sheetId, planId, on);
    if (this.session.sheet()?.id !== sheetId) return;
    this.state.update((list) => list.map((p) => (p.id === changed.id ? changed : p)));
  }

  /** Change the amount of a plan of the active sheet: the DRAFT claims it generated on this sheet that
   *  took the new amount, or null once another sheet is loaded. */
  async changeAmount(sheetId: string, planId: number, amount: string): Promise<ClaimView[] | null> {
    const { plan: changed, claims } = await this.api.changeAmount(sheetId, planId, amount);
    if (this.session.sheet()?.id !== sheetId) return null;
    this.state.update((list) => list.map((p) => (p.id === changed.id ? changed : p)));
    return changed.sheet ? [] : claims;
  }

  /** Generate the plans' claims: the store's plans replace those shown. */
  async generate(sheetId: string): Promise<GenerateView | null> {
    const result = await this.api.generate(sheetId);
    if (this.session.sheet()?.id !== sheetId) return null;
    this.state.set(result.plans);
    return result;
  }
}
