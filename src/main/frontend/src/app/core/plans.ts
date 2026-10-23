import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ClaimView } from './claims';
import { Session } from './session';

/**
 * A plan on a person: an amount claimed every N days, or monthly on a day of the month, from a start date to
 * an end date (yyyy-mm-dd).
 */
export interface PlanView {
  id: number;
  personId: number;
  /** The amount with two decimals, e.g. "12.50". */
  amount: string;
  start: string;
  /** Every N days, or null for a monthly plan. */
  every: number | null;
  /** The day of the month (1 to 31) of a monthly plan, or null for one every N days. */
  day: number | null;
  end: string;
  /** How many claims the plan has made so far. */
  generated: number;
  /** The first five plan dates after the sheet's today and on or before the end, oldest first. */
  nextDates: string[];
  /** Whether the plan is paused: its dates from the day paused until it is resumed are skipped. */
  paused: boolean;
}

/** A plan as typed on the form. */
export interface PlanForm {
  amount: string;
  start: string;
  /** Every N days, blank for a monthly plan. */
  every: string;
  /** The day of the month, blank for a plan every N days. */
  day: string;
  end: string;
}

/** What a generate made: how many claims, those claims in the order made, and the sheet's plans after it. */
export interface GeneratedView {
  count: number;
  claims: ClaimView[];
  plans: PlanView[];
}

// The sheet's plans, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class PlansApi {
  private readonly http = inject(HttpClient);

  /** The sheet's plans in the order they were added. */
  list(sheet: string): Promise<PlanView[]> {
    return firstValueFrom(this.http.get<PlanView[]>(`${this.url(sheet)}/plans`));
  }

  /** Add a plan on a person; refused (400) for a bad amount, interval or day of the month, or an end before the start. */
  add(sheet: string, personId: number, form: PlanForm): Promise<PlanView> {
    return firstValueFrom(this.http.post<PlanView>(`${this.url(sheet)}/people/${personId}/plans`, form));
  }

  /** Pause a running plan from the sheet's today; refused (400) when it is paused already. */
  pause(sheet: string, planId: number): Promise<PlanView> {
    return firstValueFrom(this.http.post<PlanView>(`${this.url(sheet)}/plans/${planId}/pause`, null));
  }

  /** Resume a paused plan on the sheet's today; refused (400) when it is running. */
  resume(sheet: string, planId: number): Promise<PlanView> {
    return firstValueFrom(this.http.post<PlanView>(`${this.url(sheet)}/plans/${planId}/resume`, null));
  }

  /** Make a DRAFT claim for every plan date up to the sheet's today that has none yet. */
  generate(sheet: string): Promise<GeneratedView> {
    return firstValueFrom(this.http.post<GeneratedView>(`${this.url(sheet)}/plans/generate`, null));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}

// The active sheet's plans, shared by each person's plans and the generate button; re-read from the
// store on every sheet load and every change of the sheet's today, which their next dates count from.
@Injectable({ providedIn: 'root' })
export class Plans {
  private readonly session = inject(Session);
  private readonly api = inject(PlansApi);

  readonly list = signal<PlanView[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      const loads = this.session.loads();
      untracked(() => {
        this.list.set([]);
        if (sheet) void this.reload(sheet, loads);
      });
    });
    effect(() => {
      const today = this.session.today();
      untracked(() => {
        const sheet = this.session.sheet();
        if (sheet && today) void this.reload(sheet, this.session.loads());
      });
    });
  }

  /** Add a plan on a person on the active sheet: listed last. */
  async add(sheet: string, personId: number, form: PlanForm): Promise<void> {
    const added = await this.api.add(sheet, personId, form);
    if (this.session.sheet() === sheet) this.list.update((all) => [...all, added]);
  }

  /** Pause (true) or resume (false) a plan on the active sheet: shown as it is after it. */
  async pause(sheet: string, planId: number, on: boolean): Promise<void> {
    const changed = on ? await this.api.pause(sheet, planId) : await this.api.resume(sheet, planId);
    if (this.session.sheet() === sheet) this.list.update((all) => all.map((p) => (p.id === changed.id ? changed : p)));
  }

  /** Generate the plans' claims on the active sheet: what was made, the plans shown as they are after it. */
  async generate(sheet: string): Promise<GeneratedView> {
    const generated = await this.api.generate(sheet);
    if (this.session.sheet() === sheet) this.list.set(generated.plans);
    return generated;
  }

  private async reload(sheet: string, loads: number): Promise<void> {
    const plans = await this.api.list(sheet);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.list.set(plans);
  }
}
