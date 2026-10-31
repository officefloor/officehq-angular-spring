import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// The audit file as it holds a sheet's lines; reading it records nothing.
@Injectable({ providedIn: 'root' })
export class AuditLog {
  private readonly http = inject(HttpClient);

  /** The sheet's audit lines, newest first; with a name, only those about that person. */
  lines(sheetId: string, name: string): Observable<string[]> {
    const params = name.trim() ? new HttpParams().set('name', name) : undefined;
    return this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheetId)}/audit`, { params });
  }
}
