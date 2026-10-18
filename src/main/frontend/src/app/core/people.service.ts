import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
import { SessionService } from './session.service';
import { SheetService } from './sheet.service';

export type Grade = 'A' | 'B' | 'C';

export const GRADES: readonly Grade[] = ['A', 'B', 'C'];

/** A hold on a person, from a date to a date (yyyy-MM-dd; null from: the beginning of time; null to: open-ended), both days in. */
export interface Hold {
  from: string | null;
  to: string | null;
}

/** Whether a hold covers a date (yyyy-MM-dd). */
export function holdCovers(h: Hold, date: string): boolean {
  return (h.from === null || h.from <= date) && (h.to === null || date <= h.to);
}

/** A grade a person was given, from a date (yyyy-MM-dd; null: from the beginning of time). */
export interface GradeFrom {
  grade: Grade;
  from: string | null;
}

export interface Person {
  id: number;
  name: string;
  /** The grade in force on the sheet's today. */
  grade: Grade;
  /** Every grade they were given, by from-date; the first one from the beginning of time. */
  grades: GradeFrom[];
  /** Moved on by every save; an edit names the version it was made from. */
  version: number;
  /** How many statements were taken for them. */
  statementCount: number;
  /** The last statement's due-total, as text with two decimals ("n/a" when it had none); null before the first. */
  statedTotal: string | null;
  /** The sheet's today (yyyy-MM-dd) when the last statement was taken; null before the first. */
  statementDate: string | null;
  /** Retired from this date (yyyy-MM-dd): claims dated after it are refused; null when not retired. */
  retiredFrom: string | null;
  /** Has any hold, whatever its dates. */
  held: boolean;
  /** Their holds, by from-date: their open claims dated within one, not bound by a statement, count in no live total. */
  holds: Hold[];
  /** The other people on the sheet they have named as patrons, sorted by name. */
  patrons: Patron[];
}

export interface Patron {
  id: number;
  name: string;
}

export interface PersonForm {
  name: string;
  grade: Grade;
  /** An edit gives the grade from this date (yyyy-MM-dd); the first grade applies from the beginning of time. */
  gradeFrom?: string;
}

/** The grade in force on a date (yyyy-MM-dd): the one given with the latest from-date on or before it. */
export function gradeOn(grades: readonly GradeFrom[], date: string): Grade | undefined {
  let best: GradeFrom | undefined;
  for (const g of grades) {
    if (g.from !== null && g.from > date) continue;
    if (best === undefined || (best.from ?? '') < (g.from ?? '')) best = g;
  }
  return best?.grade;
}

// The active sheet's people, in the order they were added.
@Injectable({ providedIn: 'root' })
export class PeopleService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly sheets = inject(SheetService);
  private readonly list = signal<Person[]>([]);

  readonly people = this.list.asReadonly();
  /** Whether retired people are listed: off when the screen opens, left as it is by loading a sheet. */
  readonly showRetired = signal(false);

  /** Re-read the active sheet's people from the store. */
  reload(): Observable<unknown> {
    const id = this.sheets.sheetId();
    if (!id) {
      this.list.set([]);
      return of(null);
    }
    return this.http
      .get<Person[]>(this.url(id), { headers: this.session.headers() })
      .pipe(tap((people) => {
        if (this.sheets.sheetId() === id) this.list.set(people);
      }));
  }

  add(form: PersonForm): Observable<Person> {
    return this.http
      .post<Person>(this.url(this.sheet()), form, { headers: this.session.headers() })
      .pipe(tap((p) => this.list.update((all) => [...all, p])));
  }

  /** Save the person from the version the editor loaded; refused (412) when someone saved them since. */
  edit(personId: number, version: number, form: PersonForm): Observable<Person> {
    return this.http
      .put<Person>(`${this.url(this.sheet())}/${personId}`, { ...form, version }, { headers: this.session.headers() })
      .pipe(tap((p) => this.list.update((all) => all.map((o) => (o.id === p.id ? p : o)))));
  }

  /** Retire the person from a date (yyyy-MM-dd); without one, from the sheet's today. */
  retire(personId: number, from: string | null): Observable<Person> {
    return this.http
      .post<Person>(`${this.url(this.sheet())}/${personId}/retire`, { from }, { headers: this.session.headers() })
      .pipe(tap((p) => this.list.update((all) => all.map((o) => (o.id === p.id ? p : o)))));
  }

  /** Put the person on hold from a date (yyyy-MM-dd; without one, the sheet's today) to a date (none: open-ended). */
  hold(personId: number, from: string | null, to: string | null): Observable<Person> {
    return this.http
      .post<Person>(`${this.url(this.sheet())}/${personId}/hold`, { from, to }, { headers: this.session.headers() })
      .pipe(tap((p) => this.list.update((all) => all.map((o) => (o.id === p.id ? p : o)))));
  }

  /** Name another person on the sheet as the person's patron; refused (409) for one already named. */
  addPatron(personId: number, patronId: number): Observable<Person> {
    return this.http
      .post<Person>(`${this.url(this.sheet())}/${personId}/patrons`, { patronId }, { headers: this.session.headers() })
      .pipe(tap((p) => this.list.update((all) => all.map((o) => (o.id === p.id ? p : o)))));
  }

  private sheet(): string {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return id;
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }
}
