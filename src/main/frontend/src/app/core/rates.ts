import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { toCents } from './claims';
import { Grade } from './people';
import { Session } from './session';

export interface Rate {
  grade: Grade;
  /** A percent, always with two decimals, e.g. "12.50". */
  rate: string;
}

/** A rate the store accepts: a percent of 0 or more, below 1000, with at most two decimals. */
export const RATE = /^\d{1,3}(\.\d{1,2})?$/;

/**
 * Cents due on an amount in cents at a rate (a percent with two decimals): amount times rate
 * divided by 100, rounded half up to whole cents. Exact, as the rate is taken in hundredths.
 */
export function dueCents(cents: number, rate: string): number {
  return Number((BigInt(cents) * BigInt(toCents(rate)) + 5000n) / 10000n);
}

// The rate for each grade on the active sheet, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class Rates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly all = signal<Rate[]>([]);

  /** Each grade's rate; a grade with no rate is absent. */
  readonly byGrade = computed(() => new Map(this.all().map((r) => [r.grade, r.rate])));

  async reload(): Promise<void> {
    try {
      this.all.set(await firstValueFrom(this.http.get<Rate[]>(this.url(), { headers: this.headers() })));
    } catch {
      this.all.set([]);
    }
  }

  async set(grade: Grade, rate: string): Promise<void> {
    const saved = await firstValueFrom(
      this.http.put<Rate>(`${this.url()}/${grade}`, { rate: rate.trim() }, { headers: this.headers() }),
    );
    this.all.update((list) => [...list.filter((r) => r.grade !== saved.grade), saved]);
  }

  private url(): string {
    const id = this.session.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return `/api/sheets/${encodeURIComponent(id)}/rates`;
  }

  private headers(): Record<string, string> {
    const user = this.session.user();
    if (!user) throw new Error('not signed in');
    return { 'X-User': user };
  }
}
