import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface VoidReasonView {
  id: number;
  reason: string;
}

// The void reasons of a sheet, as the store holds them.
@Injectable({ providedIn: 'root' })
export class VoidReasonsApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/void-reasons`;
  }

  /** The sheet's void reasons in the order they were added. */
  list(sheetId: string): Promise<VoidReasonView[]> {
    return firstValueFrom(this.http.get<VoidReasonView[]>(this.url(sheetId)));
  }

  /** Add a void reason: refused when it is blank or already on the list. */
  add(sheetId: string, reason: string): Promise<VoidReasonView> {
    return firstValueFrom(this.http.post<VoidReasonView>(this.url(sheetId), { reason }));
  }
}

// The active sheet's list of void reasons, shared by its entry, each claim's void and the summary.
// Each load of a sheet re-reads it from the store.
@Injectable({ providedIn: 'root' })
export class VoidReasons {
  private readonly session = inject(Session);
  private readonly api = inject(VoidReasonsApi);

  private readonly state = signal<VoidReasonView[]>([]);
  readonly reasons = this.state.asReadonly();
  /** The refusal of the last loading of the list, or blank. */
  readonly loadError = signal('');

  constructor() {
    effect(() => {
      const sheet = this.session.loaded();
      this.state.set([]);
      this.loadError.set('');
      if (sheet) void this.load(sheet.id);
    });
  }

  private async load(sheetId: string): Promise<void> {
    try {
      const list = await this.api.list(sheetId);
      if (this.session.sheet()?.id === sheetId) this.state.set(list);
    } catch {
      if (this.session.sheet()?.id === sheetId) this.loadError.set('The void reasons could not be loaded.');
    }
  }

  /** Add a reason to the active sheet's list; the store's answer is added to the list. */
  async add(sheetId: string, reason: string): Promise<void> {
    const added = await this.api.add(sheetId, reason);
    if (this.session.sheet()?.id === sheetId) this.state.update((list) => [...list, added]);
  }
}
