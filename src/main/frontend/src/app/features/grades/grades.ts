import { Component, inject, signal } from '@angular/core';
import { GradesStore } from '../../core/grades';
import { GRADES, Grade } from '../../core/people';
import { Session } from '../../core/session';

// The sheet's grades, each shown retired or not, and a form to retire one; a retired grade is refused.
@Component({
  selector: 'app-grades',
  template: `
    <section aria-labelledby="grades-heading">
      <h2 id="grades-heading">Grades</h2>
      <form (submit)="retire($event)" aria-label="Retire a grade">
        <label for="grade-retire-select">Grade to retire</label>
        <select
          id="grade-retire-select"
          data-testid="grade-retire-select"
          [value]="grade()"
          (change)="grade.set(chosen($event)); error.set(null)"
        >
          @for (g of codes; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
        @if (error(); as message) {
          <p role="alert" data-testid="grade-error">{{ message }}</p>
        }
      </form>
      <table>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Retired</th>
          </tr>
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
  private readonly session = inject(Session);
  protected readonly store = inject(GradesStore);

  protected readonly codes = GRADES;
  protected readonly grade = signal<Grade>('A');
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  protected chosen(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  protected async retire(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const grade = this.grade();
    if (this.store.retired(grade)) {
      this.error.set(`The grade ${grade} is already retired.`);
      return;
    }
    this.busy = true;
    try {
      await this.store.retire(sheet, grade);
    } catch {
      this.error.set(`The grade ${grade} could not be retired.`);
    } finally {
      this.busy = false;
    }
  }
}
