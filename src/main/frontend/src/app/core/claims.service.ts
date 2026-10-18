import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { SessionService } from './session.service';
import { SheetService } from './sheet.service';

export interface Claim {
  id: number;
  personId: number;
  /** Text with two decimals, e.g. "10.00". */
  amount: string;
  /** yyyy-MM-dd */
  date: string;
  note: string;
}

export interface ClaimForm {
  amount: string;
  /** null: the sheet's today. */
  date: string | null;
  note: string;
}

// The active sheet's claims, newest first, and what they add up to (kept in cents so sums are exact).
@Injectable({ providedIn: 'root' })
export class ClaimsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly sheets = inject(SheetService);
  private readonly list = signal<Claim[]>([]);

  readonly claims = this.list.asReadonly();

  /** Each person's claims, newest first. */
  readonly byPerson = computed(() => {
    const out = new Map<number, Claim[]>();
    for (const c of this.list()) out.set(c.personId, [...(out.get(c.personId) ?? []), c]);
    return out;
  });

  /** Each person's claimed, in cents. */
  readonly claimedCents = computed(() => {
    const out = new Map<number, number>();
    for (const c of this.list()) out.set(c.personId, (out.get(c.personId) ?? 0) + cents(c.amount));
    return out;
  });

  /** Everyone's claimed added up, in cents. */
  readonly totalCents = computed(() => this.list().reduce((sum, c) => sum + cents(c.amount), 0));

  reload(): void {
    const id = this.sheets.sheetId();
    if (!id) {
      this.list.set([]);
      return;
    }
    this.http
      .get<Claim[]>(this.url(id), { headers: this.session.headers() })
      .subscribe((claims) => {
        if (this.sheets.sheetId() === id) this.list.set(claims);
      });
  }

  add(personId: number, form: ClaimForm): Observable<Claim> {
    const sheet = this.sheet();
    return this.http
      .post<Claim>(`/api/sheets/${encodeURIComponent(sheet)}/people/${personId}/claims`, form, {
        headers: this.session.headers(),
      })
      .pipe(tap((c) => this.list.update((all) => [c, ...all])));
  }

  edit(claimId: number, form: ClaimForm): Observable<Claim> {
    return this.http
      .put<Claim>(`${this.url(this.sheet())}/${claimId}`, form, { headers: this.session.headers() })
      .pipe(tap((c) => this.list.update((all) => all.map((o) => (o.id === c.id ? c : o)))));
  }

  private sheet(): string {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return id;
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/claims`;
  }
}

/** "12.34" as 1234. */
export function cents(amount: string): number {
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2));
}

/** 1234 as "12.34". */
export function money(cents: number): string {
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}
