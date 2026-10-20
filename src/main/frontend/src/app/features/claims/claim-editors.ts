import { Injectable } from '@angular/core';

// Which claim's editor each person's claim form has open, per signed-in user and sheet, so that an editor left
// open is open again when the sheet is loaded again.
@Injectable({ providedIn: 'root' })
export class ClaimEditors {
  private readonly open = new Map<string, number>();

  get(user: string | null, sheetId: string | null, personId: number): number | null {
    return this.open.get(this.key(user, sheetId, personId)) ?? null;
  }

  set(user: string | null, sheetId: string | null, personId: number, claimId: number | null): void {
    const key = this.key(user, sheetId, personId);
    if (claimId === null) this.open.delete(key);
    else this.open.set(key, claimId);
  }

  private key(user: string | null, sheetId: string | null, personId: number): string {
    return JSON.stringify([user, sheetId, personId]);
  }
}
