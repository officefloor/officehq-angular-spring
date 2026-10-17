import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

export interface Claim {
  id: number;
  personId: number;
  /** Always with two decimals, e.g. "2.50". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
}

export interface ClaimFields {
  amount: string;
  date: string;
  note: string;
}

/** An amount the store accepts: 0 or more, with at most two decimals. */
export const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;

/** Whole cents of a valid amount, so sums are exact. */
export function toCents(amount: string): number {
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number(frac.padEnd(2, '0'));
}

export function formatCents(cents: number): string {
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}

// The claims kept on the active sheet, newest first, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly all = signal<Claim[]>([]);

  /** Each person's claimed, in cents, by person id. */
  readonly claimedCents = computed(() => {
    const out = new Map<number, number>();
    for (const c of this.all()) out.set(c.personId, (out.get(c.personId) ?? 0) + toCents(c.amount));
    return out;
  });

  readonly totalCents = computed(() => this.all().reduce((sum, c) => sum + toCents(c.amount), 0));

  async reload(): Promise<void> {
    try {
      this.all.set(await firstValueFrom(this.http.get<Claim[]>(`${this.sheetUrl()}/claims`, { headers: this.headers() })));
    } catch {
      this.all.set([]);
    }
  }

  async add(personId: number, fields: ClaimFields): Promise<void> {
    const added = await firstValueFrom(
      this.http.post<Claim>(`${this.sheetUrl()}/people/${personId}/claims`, this.body(fields), { headers: this.headers() }),
    );
    this.all.update((list) => [added, ...list]);
  }

  async update(id: number, fields: ClaimFields): Promise<void> {
    const saved = await firstValueFrom(
      this.http.put<Claim>(`${this.sheetUrl()}/claims/${id}`, this.body(fields), { headers: this.headers() }),
    );
    this.all.update((list) => list.map((c) => (c.id === saved.id ? saved : c)));
  }

  private body(fields: ClaimFields) {
    return { amount: fields.amount.trim(), date: fields.date || null, note: fields.note };
  }

  private sheetUrl(): string {
    const id = this.session.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return `/api/sheets/${encodeURIComponent(id)}`;
  }

  private headers(): Record<string, string> {
    const user = this.session.user();
    if (!user) throw new Error('not signed in');
    return { 'X-User': user };
  }
}
