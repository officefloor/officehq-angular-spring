import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
  today: string;
}

// The signed-in user and the active sheet. Signing in only takes a name; the name is sent with
// every request in the X-User header.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd): every date-dependent decision and default uses it. */
  readonly today = signal<string | null>(null);
  /** Whether retired people are listed: off when the screen opens, and left as it is by loading a sheet. */
  readonly showRetired = signal(false);
  /** Counts completed sheet loads, so the screen re-reads the sheet's data even for the same id. */
  readonly loaded = signal(0);
  private loads = 0;

  signIn(name: string): void {
    this.user.set(name);
    this.sheet.set(null);
    this.today.set(null);
  }

  /** Request options carrying the signed-in user. */
  headers(): { headers: Record<string, string> } {
    return { headers: { 'X-User': this.user() ?? '' } };
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
    if (load === this.loads) {
      this.show(loaded);
      this.loaded.update((n) => n + 1);
    }
  }

  /** Change the active sheet's today. */
  async setToday(today: string): Promise<void> {
    const id = this.sheet();
    if (!id) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SheetView>(
        `/api/sheets/${encodeURIComponent(id)}/today`,
        { today },
        { headers: { 'X-User': this.user() ?? '' } },
      ),
    );
    if (this.sheet() === saved.id) this.show(saved);
  }

  private show(view: SheetView): void {
    this.sheet.set(view.id);
    this.today.set(view.today);
  }
}
