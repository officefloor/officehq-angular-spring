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
      <form (submit)="merge($event)" aria-labelledby="grade-merge-heading">
        <h3 id="grade-merge-heading">Merge a grade</h3>
        <label for="grade-merge-from">Grade to merge</label>
        <select
          id="grade-merge-from"
          data-testid="grade-merge-from"
          [value]="mergeFrom()"
          (change)="mergeFrom.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === mergeFrom()">{{ g | fig }}</option>
          }
        </select>
        <label for="grade-merge-into">Into grade</label>
        <select
          id="grade-merge-into"
          data-testid="grade-merge-into"
          [value]="mergeInto()"
          (change)="mergeInto.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === mergeInto()">{{ g | fig }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-merge">Merge grade</button>
        @if (merged() !== null) {
          <p>Regraded <strong data-testid="grade-merged">{{ merged() | fig }}</strong></p>
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
  protected readonly mergeFrom = signal(GRADES[0]);
  protected readonly mergeInto = signal(GRADES[1]);
  /** How many people the last merge regraded; null before one, while one runs, and after a sheet load. */
  protected readonly merged = signal<number | null>(null);
  /** The count of a merge done, shown once the sheet load it asked for has come in. */
  private mergedPending: number | null = null;
  private merging = false;
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
        this.merged.set(this.mergedPending);
        this.mergedPending = null;
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

  /**
   * Merge the chosen grade into the other: everyone on it today is given the other from the sheet's today. Then the
   * sheet is re-loaded, so every part shows what the store holds.
   */
  protected async merge(event: Event): Promise<void> {
    event.preventDefault();
    if (this.merging) return;
    this.error.set(null);
    this.merged.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const from = this.mergeFrom();
    const into = this.mergeInto();
    if (from === into) {
      this.error.set(`The grade ${from} cannot be merged into itself.`);
      return;
    }
    this.merging = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ changed: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/grade-merge`,
          { from, into },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.mergedPending = result.changed;
      await this.session.loadSheet(sheet).catch(() => {
        this.merged.set(this.mergedPending);
        this.mergedPending = null;
      });
    } catch {
      if (this.session.sheet() === sheet) {
        this.error.set(`The grade ${from} could not be merged into ${into}: only a steward may, and not into a retired grade.`);
      }
    } finally {
      this.merging = false;
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
