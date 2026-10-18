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

  private sheet(): string {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return id;
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/people`;
  }
}
