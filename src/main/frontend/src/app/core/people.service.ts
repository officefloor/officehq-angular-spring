import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
import { SessionService } from './session.service';
import { SheetService } from './sheet.service';

export type Grade = 'A' | 'B' | 'C';

export const GRADES: readonly Grade[] = ['A', 'B', 'C'];

export interface Person {
  id: number;
  name: string;
  grade: Grade;
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
  /** On hold: their open claims count in no live total (settled ones still do). */
  held: boolean;
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

  /** Put the person on hold, or take them off hold when they are held. */
  toggleHold(personId: number): Observable<Person> {
    return this.http
      .post<Person>(`${this.url(this.sheet())}/${personId}/hold`, null, { headers: this.session.headers() })
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
