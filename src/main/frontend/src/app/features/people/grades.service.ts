import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Grade } from './people.service';

// The grades retired on the active sheet, read from and written to the server. The people on a retired
// grade keep it, but it is given to no one from then on: not to a new person, nor in an edit.
@Injectable({ providedIn: 'root' })
export class GradesService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

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

  /** Retire a grade. Refused (409) when it is already retired. */
  async retire(sheet: string, grade: Grade): Promise<void> {
    const all = await firstValueFrom(
      this.http.post<Grade[]>(this.url(sheet), { grade }, { headers: this.session.headers() }),
    );
    if (this.session.sheetId() === sheet) this.retired.set(all);
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/grades/retired`;
  }
}
