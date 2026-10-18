import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, forkJoin, of, tap } from 'rxjs';
import { cents } from './claims.service';
import { SessionService } from './session.service';
import { SheetService } from './sheet.service';

export type Role = 'clerk' | 'steward';

export const ROLES: readonly Role[] = ['clerk', 'steward'];

export interface RoleEntry {
  user: string;
  role: Role;
}

interface SettingsView {
  /** Two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** Two decimals: a claim's live due is at least this. */
  floor: string;
  /** Days: a claim is due by its date plus these. */
  terms: number;
}

/** The settings to save; one left out keeps its value. The clerk limit is '' for no limit. */
export interface SettingsChange {
  clerkLimit?: string;
  floor?: string;
  terms?: string;
}

/** The floor a sheet starts with. */
const DEFAULT_FLOOR = '1.00';

/** The terms (days) a sheet starts with. */
const DEFAULT_TERMS = 14;

// The active sheet's settings and the roles it gives user names. A name with no role is a clerk; a
// clerk may settle a claim only when its due is at or below the clerk limit (none: no limit), a
// steward any claim. A claim's live due is at least the floor; it is due by its date plus the terms.
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly sheets = inject(SheetService);
  private readonly limit = signal<string | null>(null);
  private readonly floorValue = signal<string>(DEFAULT_FLOOR);
  private readonly termsValue = signal<number>(DEFAULT_TERMS);
  private readonly list = signal<RoleEntry[]>([]);

  readonly clerkLimit = this.limit.asReadonly();
  readonly floor = this.floorValue.asReadonly();
  /** The floor in cents. */
  readonly floorCents = computed(() => cents(this.floorValue()));
  /** The terms in days. */
  readonly terms = this.termsValue.asReadonly();
  readonly roles = this.list.asReadonly();

  /** Re-read the active sheet's settings and roles from the store. */
  reload(): Observable<unknown> {
    const id = this.sheets.sheetId();
    if (!id) {
      this.limit.set(null);
      this.floorValue.set(DEFAULT_FLOOR);
      this.termsValue.set(DEFAULT_TERMS);
      this.list.set([]);
      return of(null);
    }
    const headers = this.session.headers();
    return forkJoin([
      this.http.get<SettingsView>(`${this.url(id)}/settings`, { headers }),
      this.http.get<RoleEntry[]>(`${this.url(id)}/roles`, { headers }),
    ]).pipe(tap(([settings, roles]) => {
      if (this.sheets.sheetId() !== id) return;
      this.set(settings);
      this.list.set(roles);
    }));
  }

  /** Save the settings given (the others keep their values). */
  save(change: SettingsChange): Observable<SettingsView> {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return this.http
      .put<SettingsView>(`${this.url(id)}/settings`, change, { headers: this.session.headers() })
      .pipe(tap((s) => this.set(s)));
  }

  private set(settings: SettingsView): void {
    this.limit.set(settings.clerkLimit);
    this.floorValue.set(settings.floor);
    this.termsValue.set(settings.terms);
  }

  /** Give a user name a role, replacing the one it had; refused for any role but clerk or steward. */
  setRole(user: string, role: string): Observable<RoleEntry> {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return this.http
      .put<RoleEntry>(`${this.url(id)}/roles`, { user, role }, { headers: this.session.headers() })
      .pipe(tap((r) => this.list.update((all) => [...all.filter((o) => o.user !== r.user), r]
        .sort((a, b) => a.user.localeCompare(b.user)))));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }
}
