import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { Session } from '../../core/session';
import { GRADES } from '../rates/rates';
import { SettingsStore } from '../settings/settings';

// The grades of the active sheet and whether each is retired. A retired grade cannot be given to a new person or
// chosen in an edit; the people already on it keep it. Retiring a grade already retired is refused.
@Component({
  selector: 'app-grades',
  imports: [FigPipe],
  template: `
    <section aria-label="Grades">
      <h2>Grades</h2>
      <form (submit)="retire($event)" aria-label="Retire a grade">
        <label for="grade-retire-select">Grade to retire</label>
        <select
          id="grade-retire-select"
          data-testid="grade-retire-select"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
        @if (error(); as err) {
          <p role="alert" data-testid="grade-error">{{ err | fig }}</p>
        }
      </form>

      <table>
        <caption>Grades</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Retired</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr [attr.data-testid]="'grade-row-' + g">
              <th scope="row" data-testid="grade-code">{{ g }}</th>
              <td data-testid="grade-retired">{{ store.retiredGrades().includes(g) ? 'yes' : 'no' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Grades {
  private readonly session = inject(Session);
  protected readonly store = inject(SettingsStore);

  protected readonly grades = GRADES;
  protected readonly grade = signal(GRADES[0]);
  protected readonly error = signal<string | null>(null);
  // A submit in flight: a second one before it answers is ignored.
  private submitting = false;

  constructor() {
    // A freshly loaded sheet shows no old error.
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => this.error.set(null));
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLSelectElement).value;
  }

  protected async retire(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const grade = this.grade();
    if (this.store.retiredGrades().includes(grade)) {
      this.error.set(`Grade ${grade} is already retired.`);
      return;
    }
    this.submitting = true;
    try {
      await this.store.retireGrade(sheet, grade);
    } catch (err) {
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 409
          ? `Grade ${grade} is already retired.`
          : 'Could not retire that grade.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
