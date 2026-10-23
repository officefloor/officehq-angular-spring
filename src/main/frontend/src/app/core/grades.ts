import { HttpClient } from '@angular/common/http';
import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { GRADES, Grade } from './people';
import { Session } from './session';

/** A grade of the sheet, and whether it is retired there. */
export interface GradeStateView {
  grade: Grade;
  retired: boolean;
}

/** A grade merged into another, and how many people were given the other. */
export interface GradeMergeView {
  from: Grade;
  into: Grade;
  people: number;
}

// The sheet's grades, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class GradesApi {
  private readonly http = inject(HttpClient);

  /** The sheet's grades, in order. */
  list(sheet: string): Promise<GradeStateView[]> {
    return firstValueFrom(this.http.get<GradeStateView[]>(this.url(sheet)));
  }

  /** Add a grade (its code trimmed): the sheet's grades as they are now. Refused when it is not one to three capital letters, or is already a grade. */
  add(sheet: string, grade: string): Promise<GradeStateView[]> {
    return firstValueFrom(this.http.post<GradeStateView[]>(this.url(sheet), { grade }));
  }

  /** Retire a grade: the sheet's grades as they are now. Refused when it is already retired. */
  retire(sheet: string, grade: Grade): Promise<GradeStateView[]> {
    return firstValueFrom(this.http.post<GradeStateView[]>(`${this.url(sheet)}/${encodeURIComponent(grade)}/retire`, {}));
  }

  /** Merge a grade into another: who now has the other grade. Refused for the same grade twice, or anyone but a steward. */
  merge(sheet: string, from: Grade, into: Grade): Promise<GradeMergeView> {
    return firstValueFrom(this.http.post<GradeMergeView>(`${this.url(sheet)}/merge`, { from, into }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/grades`;
  }
}

/**
 * The loaded sheet's grades, re-read on every sheet load: people on a retired grade keep it, but it
 * cannot be chosen for anyone.
 */
@Injectable({ providedIn: 'root' })
export class GradesStore {
  private readonly session = inject(Session);
  private readonly api = inject(GradesApi);

  readonly grades = signal<GradeStateView[]>([]);
  /** The sheet's grade codes, in order: A, B and C until the sheet's own list is read. */
  readonly codes = computed<readonly Grade[]>(() => {
    const grades = this.grades();
    return grades.length ? grades.map((g) => g.grade) : GRADES;
  });

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      const loads = this.session.loads();
      untracked(() => {
        this.grades.set([]);
        if (sheet) void this.reload(sheet, loads);
      });
    });
  }

  /** Whether the grade is retired on the loaded sheet. */
  retired(grade: Grade): boolean {
    return this.grades().some((g) => g.grade === grade && g.retired);
  }

  /** Add a grade to the loaded sheet; throws when the store refuses it. */
  async add(sheet: string, grade: string): Promise<void> {
    const grades = await this.api.add(sheet, grade);
    if (this.session.sheet() === sheet) this.grades.set(grades);
  }

  /** Retire a grade on the loaded sheet; throws when the store refuses it. */
  async retire(sheet: string, grade: Grade): Promise<void> {
    const grades = await this.api.retire(sheet, grade);
    if (this.session.sheet() === sheet) this.grades.set(grades);
  }

  /** Merge a grade into another on the loaded sheet, then re-read the sheet; throws when the store refuses it. */
  async merge(sheet: string, from: Grade, into: Grade): Promise<GradeMergeView> {
    const merged = await this.api.merge(sheet, from, into);
    if (this.session.sheet() === sheet) await this.session.loadSheet(sheet);
    return merged;
  }

  private async reload(sheet: string, loads: number): Promise<void> {
    const grades = await this.api.list(sheet);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.grades.set(grades);
  }
}
