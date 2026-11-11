import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/**
 * A person's plan: a claim of the amount on the start date, then every so many days, up to the end date; or, run
 * monthly, on a day of each month from the start's month (the month's last day when it is shorter).
 */
export interface PlanView {
  id: number;
  personId: number;
  /** Two decimals, e.g. "2.50". */
  amount: string;
  start: string;
  /** Every so many days, or null when the plan runs monthly. */
  every: number | null;
  /** The day of the month (1 to 31) a monthly plan runs on, or null. */
  day: number | null;
  end: string;
  /** How many claims the plan has made so far. */
  generated: number;
}

// The active sheet's plans, in the order they were added, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class Plans {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly list = signal<PlanView[]>([]);
  private reads = 0;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.list.set([]);
        if (sheet) void this.read(sheet);
      });
    });
  }

  /** Add a plan to the person on the active sheet; throws when the store refuses it. */
  async add(personId: number, plan: { amount: string; start: string; every: string; day: string; end: string }): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<PlanView>(
        `/api/sheets/${encodeURIComponent(sheet)}/people/${personId}/plans`,
        plan,
        this.session.headers(),
      ),
    );
    if (this.session.sheet() === sheet) this.list.update((list) => [...list, saved]);
  }

  /** Make the DRAFT claims of every plan date due and not yet made: how many were made; throws when refused. */
  async generate(): Promise<number> {
    const sheet = this.session.sheet();
    if (!sheet) throw new Error('No sheet loaded.');
    const result = await firstValueFrom(
      this.http.post<{ made: number }>(
        `/api/sheets/${encodeURIComponent(sheet)}/plans/generate`,
        null,
        this.session.headers(),
      ),
    );
    await this.read(sheet);
    return result.made;
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const list = await firstValueFrom(
      this.http.get<PlanView[]>(`/api/sheets/${encodeURIComponent(sheet)}/plans`, this.session.headers()),
    ).catch(() => null);
    // A later read wins over a slower earlier one.
    if (list && read === this.reads && this.session.sheet() === sheet) {
      this.list.set(list);
    }
  }
}
