import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ClaimView } from './claims';

/** A claim template on the sheet: a person's row adds a DRAFT claim with its amount and note. */
export interface TemplateView {
  id: number;
  name: string;
  amount: string;
  note: string;
}

// The claim templates on the active sheet, by name.
@Injectable({ providedIn: 'root' })
export class Templates {
  private readonly http = inject(HttpClient);
  private readonly list = signal<TemplateView[]>([]);
  readonly all = this.list.asReadonly();

  /** Re-read a sheet's templates from the store; null clears them. */
  load(sheetId: string | null): Observable<TemplateView[]> | null {
    this.list.set([]);
    if (sheetId === null) return null;
    return this.http.get<TemplateView[]>(`${this.base(sheetId)}/templates`).pipe(tap((list) => this.list.set(list)));
  }

  /** Save a template; saving a name again replaces it. Refused when the name is blank or the amount is one the
   *  claim form refuses. */
  save(sheetId: string, template: { name: string; amount: string; note: string }): Observable<TemplateView> {
    return this.http.post<TemplateView>(`${this.base(sheetId)}/templates`, template).pipe(
      tap((saved) => this.list.update((list) =>
        [...list.filter((t) => t.name !== saved.name), saved].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)))),
    );
  }

  /** Add a DRAFT claim for the person with the template's amount and note as they are now, with no date. */
  addClaim(sheetId: string, personId: number, template: string): Observable<ClaimView> {
    return this.http.post<ClaimView>(`${this.base(sheetId)}/people/${personId}/claims/from-template`, { template });
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }
}
