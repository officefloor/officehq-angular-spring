import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// The sheet's audit lines, read from the audit file as it holds them.
@Injectable({ providedIn: 'root' })
export class AuditApi {
  private readonly http = inject(HttpClient);

  /** The sheet's audit lines, newest first; with a name, only the lines about that person. */
  lines(sheet: string, name: string): Promise<string[]> {
    const params: Record<string, string> = name.trim() ? { name } : {};
    return firstValueFrom(
      this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/audit`, { params }),
    );
  }
}
