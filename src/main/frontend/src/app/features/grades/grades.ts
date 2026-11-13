import { Component, inject, signal } from '@angular/core';
import { GRADES } from '../../core/people';
import { Session } from '../../core/session';

// The grades: each grade and whether it is retired, and a select and a button to retire one (a grade already
// retired is refused). A retired grade cannot be chosen for a person; the people on it keep it.
@Component({
  selector: 'app-grades',
  template: `
    <section aria-label="Grades" data-testid="grades">
      <h2>Grades</h2>
      <table>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Retired</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="'grade-row-' + g">
              <td data-testid="grade-code">{{ g }}</td>
              <td data-testid="grade-retired">{{ session.retiredGrades().includes(g) ? 'yes' : 'no' }}</td>
            </tr>
          }
        </tbody>
      </table>
      <form (submit)="retire($event)">
        <label for="grade-retire-select">Grade to retire</label>
        <select
          id="grade-retire-select"
          data-testid="grade-retire-select"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire" [disabled]="saving()">Retire grade</button>
        @if (error(); as err) {
          <p role="alert" data-testid="grade-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class GradesPanel {
  protected readonly session = inject(Session);
  protected readonly grades = GRADES;
  protected readonly grade = signal<string>(GRADES[0]);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLSelectElement).value;
  }

  protected async retire(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.session.retireGrade(this.grade());
      if (err) this.error.set(err);
    } finally {
      this.saving.set(false);
    }
  }
}
