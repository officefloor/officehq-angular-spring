import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Grade } from './people';
import { Session } from './session';

/** A grade of the sheet: its code, and whether it is retired (no longer chosen for anyone; the
 *  people on it keep it). */
export interface GradeState {
  grade: Grade;
  retired: boolean;
}

/** The grades every sheet has; a sheet's own list adds to them. */
export const BUILT_IN_GRADES: readonly Grade[] = ['A', 'B', 'C'];

/** A grade's code: one to three capital letters. */
export const GRADE_CODE = /^[A-Z]{1,3}$/;

// The grades of the active sheet and which of them are retired, re-read whenever the sheet is
// loaded. Every grade select, summary and rate table lists the sheet's grades from here.
@Injectable({ providedIn: 'root' })
export class Grades {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** Every grade of the active sheet, in order, and whether it is retired. */
  readonly states = signal<GradeState[]>([]);
  /** The codes of the active sheet's grades, in order: A, B and C until the list is read. */
  readonly codes = computed<Grade[]>(() => {
    const states = this.states();
    return states.length ? states.map((g) => g.grade) : [...BUILT_IN_GRADES];
  });
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.loads();
      const sheetId = this.session.sheetId();
      this.states.set([]);
      if (sheetId) void this.reload(sheetId);
    });
  }

  /** Every grade of the sheet, in order. */
  list(sheetId: string): Promise<GradeState[]> {
    return firstValueFrom(this.http.get<GradeState[]>(this.url(sheetId)));
  }

  /** Add a grade; refused (400) unless one to three capital letters, (409) when it is a grade of
   *  the sheet already. Every grade, as it is now. */
  async add(grade: string): Promise<GradeState[]> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    const grades = await firstValueFrom(
      this.http.post<GradeState[]>(this.url(sheetId), { grade }, { headers: { 'X-User': user } }),
    );
    this.reads++;
    this.states.set(grades);
    return grades;
  }

  /** Retire a grade; refused (409) when it is retired already. Every grade, as it is now. */
  async retire(grade: string): Promise<GradeState[]> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    const grades = await firstValueFrom(
      this.http.post<GradeState[]>(`${this.url(sheetId)}/retire`, { grade }, { headers: { 'X-User': user } }),
    );
    this.reads++;
    this.states.set(grades);
    return grades;
  }

  /** Merge a grade into another: everyone on it is given the other from the sheet's today. Refused
   *  (403) unless the user is a steward, (400) when the grades are the same. How many it changed. */
  async merge(from: string, into: string): Promise<number> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    const res = await firstValueFrom(
      this.http.post<{ merged: number }>(`${this.url(sheetId)}/merge`, { from, into }, { headers: { 'X-User': user } }),
    );
    return res.merged;
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const grades = await this.list(sheetId);
      if (read === this.reads) this.states.set(grades);
    } catch {
      // the list stays as A, B and C; the next load reads it again
    }
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/grades`;
  }
}
