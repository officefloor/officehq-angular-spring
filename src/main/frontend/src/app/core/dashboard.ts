import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/**
 * A steward's dashboard over the sheet: how many claims are PENDING, how many in REVIEW, and how many
 * of those that count are overdue. Every count is null for a user who is not a steward.
 */
export interface DashboardView {
  pendingCount: number | null;
  reviewCount: number | null;
  overdueCount: number | null;
}

// The steward's dashboard, read from the store for the signed-in user.
@Injectable({ providedIn: 'root' })
export class DashboardApi {
  private readonly http = inject(HttpClient);

  get(sheet: string): Promise<DashboardView> {
    return firstValueFrom(this.http.get<DashboardView>(`/api/sheets/${encodeURIComponent(sheet)}/dashboard`));
  }
}
