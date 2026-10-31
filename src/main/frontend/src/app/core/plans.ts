import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ClaimView } from './claims';

/** A person's plan: an amount claimed on its start date and every `every` days after, or monthly on day `day` of each
 *  month from the start's, up to its end date, with how many claims it has made so far. */
export interface PlanView {
  id: number;
  personId: number;
  amount: string;
  start: string;
  every: number | null;
  day: number | null;
  end: string;
  generated: number;
}

/** A plan as its form submits it, with either an interval or a day of the month; a blank start takes the sheet's
 *  today. */
export interface PlanForm {
  amount: string;
  start: string | null;
  every: number | null;
  day: number | null;
  end: string;
}

/** What a generate did: how many claims it made, the claims in the order made, and the sheet's plans. */
export interface GeneratedView {
  made: number;
  claims: ClaimView[];
  plans: PlanView[];
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
