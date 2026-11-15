import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, otherError } from '../../core/session';

/** A claim template: a claim made from it is given its amount (two decimals) and note as they are then. */
export interface ClaimTemplate {
  name: string;
  amount: string;
  note: string;
}

@Injectable({ providedIn: 'root' })
export class TemplatesApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's templates, by name. */
  list(sheetId: string): Promise<ClaimTemplate[]> {
    return firstValueFrom(this.http.get<ClaimTemplate[]>(`${this.url(sheetId)}/templates`, { headers: this.headers() }));
  }

  /** Save a template, replacing one of the same name; resolves to the sheet's templates with it. */
  save(sheetId: string, form: ClaimTemplate): Promise<ClaimTemplate[]> {
    return firstValueFrom(this.http.put<ClaimTemplate[]>(`${this.url(sheetId)}/templates`, form, { headers: this.headers() }));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export function templateError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a name and an amount of 0.00 or more with up to two decimals.';
  return otherError(e);
}
