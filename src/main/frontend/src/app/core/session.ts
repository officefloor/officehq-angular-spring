import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface SheetView {
  id: string;
}

// The signed-in user (a typed name, no password) and the active sheet. Everything else in the
// app is kept per sheet, so features read the active sheet id from here.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);

  signIn(name: string): void {
    this.user.set(name);
  }

  /** Load a sheet by id from the store; an id never used before starts an empty sheet. */
  async loadSheet(id: string): Promise<void> {
    const view = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null),
    );
    this.sheet.set(view.id);
  }
}
