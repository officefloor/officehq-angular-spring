import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
}

// The signed-in user and the active sheet. Signing in only takes a name; the name is sent with
// every request in the X-User header.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);
  private loads = 0;

  signIn(name: string): void {
    this.user.set(name);
    this.sheet.set(null);
  }

  /** Load a sheet by id; an id never used before starts an empty sheet. */
  async loadSheet(id: string): Promise<void> {
    const load = ++this.loads;
    const loaded = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: { 'X-User': this.user() ?? '' },
      }),
    );
    // A later load wins over a slower earlier one.
    if (load === this.loads) this.sheet.set(loaded.id);
  }
}
