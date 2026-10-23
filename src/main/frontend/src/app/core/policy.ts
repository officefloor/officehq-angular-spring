import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A policy version of the sheet. */
export interface PolicyVersionView {
  number: number;
  /** yyyy-mm-dd: the date it is in force from; null when in force always. */
  from: string | null;
  /** The policy setting its change set. */
  key: string;
  /** The value it set the setting to; null for none. */
  value: string | null;
  /** How many of the sheet's claims have it as their policy version. */
  claims: number;
}

// The sheet's policy versions, read from the store.
@Injectable({ providedIn: 'root' })
export class PolicyApi {
  private readonly http = inject(HttpClient);

  /** The sheet's policy versions, oldest first. */
  list(sheet: string): Promise<PolicyVersionView[]> {
    return firstValueFrom(this.http.get<PolicyVersionView[]>(`/api/sheets/${encodeURIComponent(sheet)}/policy-versions`));
  }
}
