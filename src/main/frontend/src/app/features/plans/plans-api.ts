import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { ClaimView } from '../claims/claims-api';

/** A person's plan: a claim of the amount (two decimals) on the start date and every `every` days after
 *  it, up to the end date (yyyy-mm-dd). Generated is how many claims it has made so far. */
export interface PlanView {
  id: number;
  personId: number;
  amount: string;
  start: string;
  every: number;
  end: string;
  generated: number;
}

/** A plan's fields as entered; a null start is the sheet's today. */
export interface PlanFields {
  amount: string;
  start: string | null;
  every: string;
  end: string;
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

  /** Add a plan to a person: refused for an amount not above 0.00 with up to two decimals, an interval
   *  that is not a whole number of at least 1, or an end before the start. */
  add(sheetId: string, personId: number, f: PlanFields): Promise<PlanView> {
    return firstValueFrom(this.http.post<PlanView>(this.url(sheetId),
      { personId, amount: f.amount, start: f.start, every: f.every, end: f.end }));
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

  /** Generate the plans' claims: the store's plans replace those shown. */
  async generate(sheetId: string): Promise<GenerateView | null> {
    const result = await this.api.generate(sheetId);
    if (this.session.sheet()?.id !== sheetId) return null;
    this.state.set(result.plans);
    return result;
  }
}
