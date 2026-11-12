import { HttpClient } from '@angular/common/http';
import { FigurePipe } from '../../core/locale';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** The part of the sheet's settings this screen reads: the grades retired on it. */
interface GradesView {
  retiredGrades: string[] | null;
}

const GRADES = ['A', 'B', 'C'];

// The sheet's grades: a retired grade cannot be chosen for a new person or in an edit.
@Component({
  selector: 'app-grades',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="grades-heading">
      <h2 id="grades-heading">Grades</h2>
      <form (submit)="retire($event)" aria-labelledby="grades-heading">
        <label for="grade-retire-select">Grade to retire</label>
        <select
          id="grade-retire-select"
          data-testid="grade-retire-select"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g | fig }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
        @if (error(); as message) {
          <p role="alert" data-testid="grade-error">{{ message | fig }}</p>
        }
      </form>
      <table aria-label="Grades">
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Retired</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="'grade-row-' + g">
              <td>{{ g | fig }}</td>
              <td data-testid="grade-retired">{{ (retired().includes(g) ? 'yes' : 'no') | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Grades {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly retired = signal<string[]>([]);
  protected readonly grade = signal(GRADES[0]);
  protected readonly error = signal<string | null>(null);
  /** Whether a retire is waiting for its answer: a second one meanwhile is ignored. */
  private retiring = false;
  private reads = 0;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.retired.set([]);
        this.error.set(null);
        if (sheet) void this.read(sheet);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLSelectElement).value;
  }

  protected async retire(event: Event): Promise<void> {
    event.preventDefault();
    if (this.retiring) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const grade = this.grade();
    if (this.retired().includes(grade)) {
      this.error.set(`The grade ${grade} is already retired.`);
      return;
    }
    this.retiring = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<GradesView>(
          `/api/sheets/${encodeURIComponent(sheet)}/settings/grades/${encodeURIComponent(grade)}/retire`,
          {},
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) this.retired.set(saved.retiredGrades ?? []);
    } catch {
      this.error.set(`The grade ${grade} could not be retired: it may already be retired.`);
    } finally {
      this.retiring = false;
    }
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const settings = await firstValueFrom(
      this.http.get<GradesView>(`/api/sheets/${encodeURIComponent(sheet)}/settings`, this.session.headers()),
    ).catch(() => null);
    // A later read wins over a slower earlier one.
    if (settings && read === this.reads && this.session.sheet() === sheet) {
      this.retired.set(settings.retiredGrades ?? []);
    }
  }
}
