import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A steward's dashboard of a sheet; every count is null for a user who is not a steward. */
export interface DashboardView {
  pending: number | null;
  review: number | null;
  overdue: number | null;
}

// The signed-in user's steward dashboard of a sheet: while the screen shows the sheet as of a date, as it
// stood then, for the role the user had then.
@Injectable({ providedIn: 'root' })
export class DashboardApi {
  private readonly http = inject(HttpClient);

  dashboard(sheetId: string): Promise<DashboardView> {
    return firstValueFrom(this.http.get<DashboardView>(`/api/sheets/${encodeURIComponent(sheetId)}/dashboard`));
  }
}
