import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { GRADES, Grade } from '../people/people-api';
import { Grades } from './grades-api';

// The sheet's grades, each showing whether it is retired, with a choice to retire one. The people on
// a retired grade keep it, but no one can be given it any more.
@Component({
  selector: 'app-grades',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="grades-heading">
      <h2 id="grades-heading">Grades</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Retire a grade">
        <label for="grade-retire-select">Grade</label>
        <select id="grade-retire-select" data-testid="grade-retire-select" formControlName="grade">
          @for (g of choices; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire" [disabled]="saving()">Retire grade</button>
        @if (error() || store.loadError()) {
          <p role="alert" data-testid="grade-error">{{ error() || store.loadError() }}</p>
        }
      </form>
      <table aria-label="Grades">
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Retired</th></tr>
        </thead>
        <tbody>
          @for (g of store.grades(); track g.grade) {
            <tr [attr.data-testid]="'grade-row-' + g.grade">
              <td>{{ g.grade }}</td>
              <td data-testid="grade-retired">{{ g.retired ? 'yes' : 'no' }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class GradesList {
  private readonly session = inject(Session);
  protected readonly store = inject(Grades);

  protected readonly choices = GRADES;
  protected readonly grade = new FormControl<Grade>('A', { nonNullable: true });
  protected readonly form = new FormGroup({ grade: this.grade });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.grade.valueChanges.subscribe(() => this.error.set(''));
    // Each load of a sheet starts the choice afresh.
    effect(() => {
      this.session.loaded();
      this.error.set('');
      this.grade.reset('A', { emitEvent: false });
    });
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const grade = this.grade.value;
    if (!GRADES.includes(grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    if (this.store.retired().has(grade)) {
      this.error.set(`Grade ${grade} is already retired.`);
      return;
    }
    this.saving.set(true);
    try {
      await this.store.retire(sheet.id, grade);
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(status === 409 ? `Grade ${grade} is already retired.`
        : status === 403 ? 'Your role may not retire a grade on this sheet.' : 'The grade could not be retired.');
    } finally {
      this.saving.set(false);
    }
  }
}
