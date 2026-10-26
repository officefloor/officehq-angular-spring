import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Grade } from '../people/people-api';

export interface GradeView {
  grade: Grade;
  /** Whether the grade is retired on the sheet: no one can be given it any more. */
  retired: boolean;
}

// The grades of a sheet and which are retired, as the store holds them.
@Injectable({ providedIn: 'root' })
export class GradesApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/grades`;
  }

  /** Every grade in order, with whether it is retired. */
  list(sheetId: string): Promise<GradeView[]> {
    return firstValueFrom(this.http.get<GradeView[]>(this.url(sheetId)));
  }

  /** Retire a grade: refused when it is already retired. Answers every grade as it is now. */
  retire(sheetId: string, grade: Grade): Promise<GradeView[]> {
    return firstValueFrom(this.http.post<GradeView[]>(`${this.url(sheetId)}/retire`, { grade }));
  }

  /** Merge a grade into another: refused for the same grade twice, or unless a steward. Answers how
   *  many people were given the other grade. */
  merge(sheetId: string, from: Grade, into: Grade): Promise<{ merged: number }> {
    return firstValueFrom(this.http.post<{ merged: number }>(`${this.url(sheetId)}/merge`, { from, into }));
  }
}

// The active sheet's grades. Each load of a sheet re-reads them from the store.
@Injectable({ providedIn: 'root' })
export class Grades {
  private readonly session = inject(Session);
  private readonly api = inject(GradesApi);

  private readonly state = signal<GradeView[]>([]);
  readonly grades = this.state.asReadonly();
  /** The grades retired on the active sheet: none can be chosen for a new person or in an edit. */
  readonly retired = computed(() => new Set(this.state().filter((g) => g.retired).map((g) => g.grade)));
  /** The refusal of the last loading of the grades, or blank. */
  readonly loadError = signal('');
  /** How many grade merges this screen has made: the people's grades are re-read on each. */
  readonly merges = signal(0);

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
      const list = await this.session.reading(this.api.list(sheetId));
      if (this.session.sheet()?.id === sheetId) this.state.set(list);
    } catch {
      if (this.session.sheet()?.id === sheetId) this.loadError.set('The grades could not be loaded.');
    }
  }

  /** Retire a grade on the active sheet; the store's answer replaces the grades. */
  async retire(sheetId: string, grade: Grade): Promise<void> {
    const list = await this.api.retire(sheetId, grade);
    if (this.session.sheet()?.id === sheetId) this.state.set(list);
  }

  /** Merge a grade into another on the active sheet; how many people were given the other grade. */
  async merge(sheetId: string, from: Grade, into: Grade): Promise<number> {
    const result = await this.api.merge(sheetId, from, into);
    this.merges.update((n) => n + 1);
    return result.merged;
  }
}
