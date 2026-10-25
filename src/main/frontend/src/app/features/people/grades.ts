import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { GRADES, Grade } from './people.service';
import { GradesService } from './grades.service';
import { FigurePipe } from '../../core/figure.pipe';

// The sheet's grades, each shown as retired or not, and the entry by which one is retired (a grade
// already retired is refused). Read from the store every time a sheet is loaded.
@Component({
  selector: 'app-grades',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="grades-heading">
      <h2 id="grades-heading">Grades</h2>
      <form (submit)="submit($event)" aria-label="Retire a grade" data-testid="grade-retire-form">
        <label for="grade-retire-select">Grade</label>
        <select
          id="grade-retire-select"
          data-testid="grade-retire-select"
          [value]="grade()"
          (change)="grade.set(gradeOf($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ (g) | figure }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="grade-error">{{ (message) | figure }}</p>
        }
      </form>
      <table data-testid="grade-list">
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Retired</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="'grade-row-' + g">
              <td>{{ (g) | figure }}</td>
              <td data-testid="grade-retired">{{ (service.isRetired(g) ? 'yes' : 'no') | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class Grades {
  private readonly session = inject(Session);
  protected readonly service = inject(GradesService);

  protected readonly grades = GRADES;
  protected readonly grade = signal<Grade>('A');
  protected readonly error = signal<string | null>(null);
  private saving = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      this.session.loads();
      this.grade.set('A');
      this.error.set(null);
      if (sheet) void this.service.read(sheet).catch(() => undefined);
    });
  }

  protected gradeOf(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const grade = this.grade();
    if (this.service.isRetired(grade)) {
      this.error.set('That grade is already retired.');
      return;
    }
    this.saving = true;
    try {
      await this.service.retire(sheet, grade);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('That grade is already retired, or is not a grade.');
    } finally {
      this.saving = false;
    }
  }
}
