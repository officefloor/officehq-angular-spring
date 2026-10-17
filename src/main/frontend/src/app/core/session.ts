import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
  /** The sheet's "today" (yyyy-mm-dd): every date decision and default date uses it. */
  today: string;
  /** The most due a clerk can settle a claim for, two decimals; null for no limit. */
  clerkLimit: string | null;
  /** The least any live due can be, two decimals. */
  floor: string;
  /** The terms: a claim is due by its date plus this many days. */
  terms: number;
  /** The late percent, two decimals: an overdue claim's live due gains it per full 30 days overdue. */
  latePct: string;
  /** The policy date (yyyy-mm-dd) that decides how a claim's live figures round; null for the sheet's today. */
  policyDate: string | null;
  /** From this date (by the sheet's today) a claim opened is numbered within its period; null for off. */
  periodNumberingFrom: string | null;
}

// The signed-in user (a typed name, no password) and the active sheet. Everything else in the app
// is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The active sheet's "today" — use this, never the real clock, for dates in the sheet. */
  readonly today = signal<string | null>(null);
  /** The active sheet's clerk limit (two decimals); null for no limit. */
  readonly clerkLimit = signal<string | null>(null);
  /** The active sheet's floor (two decimals): every live due is at least it. */
  readonly floor = signal<string | null>(null);
  /** The active sheet's terms in days: a claim is due by its date plus these. */
  readonly terms = signal<number | null>(null);
  /** The active sheet's late percent (two decimals). */
  readonly latePct = signal<string | null>(null);
  /** The active sheet's policy date (yyyy-mm-dd); null when none is set (the sheet's today is used). */
  readonly policyDate = signal<string | null>(null);
  /** The active sheet's period numbering from (yyyy-mm-dd); null when off (claims take the sheet-wide number). */
  readonly periodNumberingFrom = signal<string | null>(null);
  /** Counts sheet loads, so per-sheet data re-reads the store on every (re-)load. */
  readonly loads = signal(0);
  /** True while a sheet is being (re-)loaded, so nothing read from the store before is shown. */
  readonly loading = signal(false);
  /** How many re-reads of the sheet's data, started by a (re-)load, have not answered yet. */
  private readonly reading = signal(0);
  /** True once the sheet is loaded and every re-read its load started has answered. */
  readonly ready = computed(() => !this.loading() && this.reading() === 0);

  signIn(name: string): void {
    this.user.set(name.trim());
  }

  /** Load (or re-load) a sheet by id; a never-used id starts an empty sheet. */
  async loadSheet(id: string): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    this.loading.set(true);
    try {
      const sheet = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id.trim())}/load`, null, {
          headers: { 'X-User': user },
        }),
      );
      this.show(sheet);
      this.loads.update((n) => n + 1);
    } finally {
      this.loading.set(false);
    }
  }

  /** Count a re-read of the sheet's data, started by a (re-)load, until it answers. */
  async track(reread: Promise<unknown>): Promise<void> {
    this.reading.update((n) => n + 1);
    try {
      await reread;
    } finally {
      this.reading.update((n) => n - 1);
    }
  }

  /** Change the active sheet's "today". */
  async setToday(today: string): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const sheet = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }, {
        headers: { 'X-User': user },
      }),
    );
    this.show(sheet);
  }

  /**
   * Change the active sheet's clerk limit (null for no limit), floor, terms (days), late percent and
   * policy date (blank for none) and period numbering from (blank for off).
   */
  async setSettings(clerkLimit: string | null, floor: string, terms: string, latePct: string, policyDate: string,
      periodNumberingFrom: string): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const sheet = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings`, { clerkLimit, floor, terms, latePct, policyDate, periodNumberingFrom }, {
        headers: { 'X-User': user },
      }),
    );
    this.show(sheet);
  }

  private show(sheet: SheetView): void {
    this.sheetId.set(sheet.id);
    this.today.set(sheet.today);
    this.clerkLimit.set(sheet.clerkLimit);
    this.floor.set(sheet.floor);
    this.terms.set(sheet.terms);
    this.latePct.set(sheet.latePct);
    this.policyDate.set(sheet.policyDate);
    this.periodNumberingFrom.set(sheet.periodNumberingFrom);
  }
}
