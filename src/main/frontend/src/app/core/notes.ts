import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

/** A note on the sheet: its text, who added it and the sheet's today when it was added. */
export interface NoteView {
  id: number;
  text: string;
  by: string;
  addedOn: string;
}

// The notes on the active sheet, newest first.
@Injectable({ providedIn: 'root' })
export class Notes {
  private readonly http = inject(HttpClient);
  private readonly list = signal<NoteView[]>([]);
  readonly all = this.list.asReadonly();

  /** Re-read a sheet's notes from the store (as of the date shown, if any); null clears them. */
  load(sheetId: string | null): Observable<NoteView[]> | null {
    this.list.set([]);
    if (sheetId === null) return null;
    return this.http.get<NoteView[]>(this.base(sheetId)).pipe(tap((list) => this.list.set(list)));
  }

  /** Add a note; refused when blank. */
  add(sheetId: string, text: string): Observable<NoteView> {
    return this.http.post<NoteView>(this.base(sheetId), { text }).pipe(
      tap((saved) => this.list.update((list) => [saved, ...list])),
    );
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/notes`;
  }
}
