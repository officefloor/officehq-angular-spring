import { Injectable, signal } from '@angular/core';

// Which claim each person's claim editor has open, per sheet: an editor stays open while the sheet is
// re-loaded, until it is saved or cancelled.
@Injectable({ providedIn: 'root' })
export class ClaimEditors {
  private readonly open = signal<ReadonlyMap<string, number>>(new Map());

  private static key(sheetId: string, personId: number): string {
    return `${sheetId}\n${personId}`;
  }

  /** The id of the claim the person's editor has open on the sheet, or null when it is adding. */
  editing(sheetId: string, personId: number): number | null {
    return this.open().get(ClaimEditors.key(sheetId, personId)) ?? null;
  }

  /** Open the person's editor on the claim, or (null) close it. */
  set(sheetId: string, personId: number, claimId: number | null): void {
    this.open.update((open) => {
      const next = new Map(open);
      if (claimId === null) next.delete(ClaimEditors.key(sheetId, personId));
      else next.set(ClaimEditors.key(sheetId, personId), claimId);
      return next;
    });
  }
}
