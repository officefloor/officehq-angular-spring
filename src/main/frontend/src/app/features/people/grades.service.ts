import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { GRADES, Grade, Person } from './people.service';

/** How a grade merge went: how many people it gave the other grade, and those people. */
export interface GradeMerged {
  merged: number;
  people: Person[];
}

// The active sheet's grades, and those retired on it, read from and written to the server. A sheet has A,
// B and C, then the grades added to it. The people on a retired grade keep it, but it is given to no one
// from then on: not to a new person, nor in an edit.
@Injectable({ providedIn: 'root' })
export class GradesService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The active sheet's grades, as last read or written: A, B and C, then those added, in the order added. */
  readonly all = signal<readonly Grade[]>(GRADES);

  /** Read the sheet's grades. */
  async readAll(sheet: string): Promise<void> {
    this.all.set(GRADES);
    const all = await firstValueFrom(this.http.get<Grade[]>(this.gradesUrl(sheet), { headers: this.session.headers() }));
    if (this.session.sheetId() === sheet) this.all.set(all);
  }

  /** Add a grade. Refused (409) unless it is one to three capital letters, not yet a grade. */
  async add(sheet: string, grade: Grade): Promise<void> {
    const all = await firstValueFrom(
      this.http.post<Grade[]>(this.gradesUrl(sheet), { grade }, { headers: this.session.headers() }),
    );
    if (this.session.sheetId() === sheet) this.all.set(all);
  }

  /** The active sheet's retired grades, as last read or written. */
  readonly retired = signal<readonly Grade[]>([]);

  isRetired(grade: string): boolean {
    return (this.retired() as readonly string[]).includes(grade);
  }

  async read(sheet: string): Promise<void> {
    this.retired.set([]);
    const all = await firstValueFrom(this.http.get<Grade[]>(this.url(sheet), { headers: this.session.headers() }));
    if (this.session.sheetId() === sheet) this.retired.set(all);
  }

  /** The people the last grade merge on this screen gave the other grade, for the people list to show. */
  readonly regraded = signal<readonly Person[]>([]);

  /** Merge grade `from` into `into`: everyone whose grade in force today is `from` is given `into`
   *  from the sheet's today, each as the person form would. Refused (409) unless a steward asks, for
   *  the same grade twice, and into a retired grade. */
  async merge(sheet: string, from: Grade, into: Grade): Promise<GradeMerged> {
    const result = await firstValueFrom(
      this.http.post<GradeMerged>(`/api/sheets/${encodeURIComponent(sheet)}/people/grade-merge`, { from, into }, {
        headers: this.session.headers(),
      }),
    );
    if (this.session.sheetId() === sheet) this.regraded.set(result.people);
    return result;
  }

  /** Retire a grade. Refused (409) when it is already retired. */
  async retire(sheet: string, grade: Grade): Promise<void> {
    const all = await firstValueFrom(
      this.http.post<Grade[]>(this.url(sheet), { grade }, { headers: this.session.headers() }),
    );
    if (this.session.sheetId() === sheet) this.retired.set(all);
  }

  private url(sheet: string): string {
    return `${this.gradesUrl(sheet)}/retired`;
  }

  private gradesUrl(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/grades`;
  }
}
