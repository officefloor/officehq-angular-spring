import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
  today: string;
  floor: string;
  terms: number;
  latePct: string;
  policyDate: string | null;
  cap: string | null;
}

/**
 * What a claim's late charge and rounding depend on: the sheet's late percent and today, and
 * the policy date in force (the sheet's today when blank), before which a claim's live figures round half up.
 */
export interface Late {
  pct: string;
  today: string | null;
  policy: string | null;
}

// The signed-in user and the active sheet. Every other feature reads these to scope its calls.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The sheet's today (yyyy-mm-dd): every date decision and default date uses it, never the clock. */
  readonly today = signal<string | null>(null);
  /** The sheet's floor, two decimals: a claim's live due is at least it. */
  readonly floor = signal('1.00');
  /** The sheet's terms in days: a claim of a grade without its own terms is due by its date plus them, fixed when added. */
  readonly terms = signal(14);
  /** The sheet's late percent, two decimals: an overdue claim's due gains it of its figure for every full 30 days overdue. */
  readonly latePct = signal('0.00');
  /** The sheet's policy date (yyyy-mm-dd) as set; null when blank, which means the sheet's today. */
  readonly policyDate = signal<string | null>(null);
  /** The sheet's cap, two decimals: a person's due-total is at most it; null when blank, which means none. */
  readonly cap = signal<string | null>(null);
  /** The late percent, today and policy date together, as a claim's late charge and rounding need them. */
  readonly late = computed<Late>(() => ({
    pct: this.latePct(),
    today: this.today(),
    policy: this.policyDate() ?? this.today(),
  }));
  /** Bumped on every sheet load, so features re-read the sheet from the store even for the same id. */
  readonly loads = signal(0);
  /** Whether retired people are listed: off when the screen opens, left as it is by loading a sheet. */
  readonly showRetired = signal(false);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ name: string }>('/api/session', { name }));
    this.user.set(res.name);
  }

  async loadSheet(id: string): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    const res = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: { 'X-User': user },
      }),
    );
    this.sheetId.set(res.id);
    this.today.set(res.today);
    this.floor.set(res.floor);
    this.terms.set(res.terms);
    this.latePct.set(res.latePct);
    this.policyDate.set(res.policyDate);
    this.cap.set(res.cap);
    this.loads.update((n) => n + 1);
  }

  async setToday(today: string): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const res = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }, {
        headers: { 'X-User': user },
      }),
    );
    if (this.sheetId() === res.id) this.today.set(res.today);
  }
}
