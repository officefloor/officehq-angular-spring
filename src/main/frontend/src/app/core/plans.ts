import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ClaimView } from './claims';

/** A person's plan: an amount claimed on its start date and every `every` days after, or monthly on day `day` of each
 *  month from the start's, up to its end date, with how many claims it has made so far and its next dates. */
export interface PlanView {
  id: number;
  personId: number;
  amount: string;
  start: string;
  every: number | null;
  day: number | null;
  end: string;
  /** The descendant sheet the plan makes its claims on; null for the plan's own sheet. */
  sheet: string | null;
  generated: number;
  /** The first five plan dates after the sheet's today and on or before the end, as of the last (re)load. */
  nextDates: string[];
  /** Whether the plan is paused now: its dates from the day it was paused are skipped until it is resumed. */
  paused: boolean;
  /** The changes of the plan's amount, oldest first. */
  changes: PlanChangeView[];
}

/** A change of a plan's amount: the sheet's today when it was made and the new amount. */
export interface PlanChangeView {
  date: string;
  amount: string;
}

/** A plan as its form submits it, with either an interval or a day of the month; a blank start takes the sheet's
 *  today. */
export interface PlanForm {
  amount: string;
  start: string | null;
  every: number | null;
  day: number | null;
  end: string;
  /** The descendant sheet to make the claims on; null for the plan's own sheet. */
  sheet: string | null;
}

/** What a generate did: how many claims it made, the claims in the order made, and the sheet's plans. */
export interface GeneratedView {
  made: number;
  claims: ClaimView[];
  plans: PlanView[];
}

/** What a change of a plan's amount did: the plan as it is now, and the DRAFT claims on this sheet it generated that
 *  took the new amount. */
export interface ChangedView {
  plan: PlanView;
  claims: ClaimView[];
}

const NO_PLANS: PlanView[] = [];

// The plans on the active sheet, in the order they were added.
@Injectable({ providedIn: 'root' })
export class Plans {
  private readonly http = inject(HttpClient);
  private readonly list = signal<PlanView[]>([]);
  readonly all = this.list.asReadonly();

  /** Each person's plans, by person. */
  private readonly byPerson = computed(() => {
    const map = new Map<number, PlanView[]>();
    for (const p of this.list()) map.set(p.personId, [...(map.get(p.personId) ?? []), p]);
    return map;
  });

  of(personId: number): PlanView[] {
    return this.byPerson().get(personId) ?? NO_PLANS;
  }

  /** Re-read a sheet's plans from the store. */
  load(sheetId: string): Observable<PlanView[]> {
    return this.http.get<PlanView[]>(`${this.base(sheetId)}/plans`).pipe(tap((list) => this.list.set(list)));
  }

  clear(): void {
    this.list.set([]);
  }

  /** Add a plan for the person; refused when the amount or interval is bad or the end is before the start. */
  add(sheetId: string, personId: number, plan: PlanForm): Observable<PlanView> {
    return this.http.post<PlanView>(`${this.base(sheetId)}/people/${personId}/plans`, plan).pipe(
      tap((added) => this.list.update((list) => [...list, added])),
    );
  }

  /** Pause a running plan (on) or resume a paused one (off) from the sheet's today; refused when it already is. */
  pause(sheetId: string, planId: number, on: boolean): Observable<PlanView> {
    return this.http.post<PlanView>(`${this.base(sheetId)}/plans/${planId}/${on ? 'pause' : 'resume'}`, null).pipe(
      tap((changed) => this.list.update((list) => list.map((p) => (p.id === changed.id ? changed : p)))),
    );
  }

  /** Change the plan's amount for the claims generated from now on and its DRAFT claims; refused when the amount is
   *  bad or one of those drafts cannot be edited. */
  changeAmount(sheetId: string, planId: number, amount: string): Observable<ChangedView> {
    return this.http.post<ChangedView>(`${this.base(sheetId)}/plans/${planId}/amount`, { amount }).pipe(
      tap(({ plan }) => this.list.update((list) => list.map((p) => (p.id === plan.id ? plan : p)))),
    );
  }

  /** Make the DRAFT claims every plan on the sheet is due, up to its end and the sheet's today. */
  generate(sheetId: string): Observable<GeneratedView> {
    return this.http.post<GeneratedView>(`${this.base(sheetId)}/plans/generate`, null).pipe(
      tap((done) => this.list.set(done.plans)),
    );
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }
}
