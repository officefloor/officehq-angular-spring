import { HttpClient } from '@angular/common/http';
import { Component, Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { GRADES } from '../rates/rates';

export interface Grade {
  /** The grade (A, B or C); it names the grade's row. */
  grade: string;
  /** Whether the grade is retired on the sheet: no one can be given it any more. */
  retired: boolean;
}

// The active sheet's grades, each with whether it is retired, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class GradeStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly grades = signal<Grade[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.grades.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Retire a grade on the active sheet; the server refuses one retired already. */
  async retire(grade: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const list = await firstValueFrom(
      this.http.post<Grade[]>(`/api/sheets/${encodeURIComponent(sheet)}/grades/retire`, { grade }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.grades.set(list);
  }

  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(this.http.get<Grade[]>(`/api/sheets/${encodeURIComponent(sheet)}/grades`));
    if (this.session.loads() !== load) return;
    this.grades.set(list);
  }
}

// The sheet's grades, each shown with whether it is retired, and the entry that retires one. People on
// a retired grade keep it; it cannot be chosen for anyone any more.
@Component({
  selector: 'app-grades',
  template: `
    <section aria-labelledby="grades-heading">
      <h2 id="grades-heading">Grades</h2>
      <form (submit)="submit($event)">
        <label for="grade-retire-select">Grade to retire</label>
        <select
          id="grade-retire-select"
          data-testid="grade-retire-select"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="grade() === g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
        @if (error(); as message) {
          <p role="alert" data-testid="grade-error">{{ message }}</p>
        }
      </form>
      <table>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Retired</th></tr>
        </thead>
        <tbody>
          @for (g of store.grades(); track g.grade) {
            <tr [attr.data-testid]="'grade-row-' + g.grade">
              <td data-testid="grade-code">{{ g.grade }}</td>
              <td data-testid="grade-retired">{{ g.retired ? 'yes' : 'no' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Grades {
  protected readonly store = inject(GradeStore);
  protected readonly grades = GRADES;
  protected readonly grade = signal(GRADES[0]);
  protected readonly error = signal<string | null>(null);
  /** Whether a retire is waiting for its answer; a second one meanwhile is dropped. */
  private readonly busy = signal(false);

  protected value(event: Event): string {
    return (event.target as HTMLSelectElement).value;
  }

  /** Retire the chosen grade; one retired already is refused beside the entry. */
  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const grade = this.grade();
    this.busy.set(true);
    try {
      await this.store.retire(grade);
    } catch {
      this.error.set(`The grade ${grade} could not be retired: it may be retired already.`);
    } finally {
      this.busy.set(false);
    }
  }
}
