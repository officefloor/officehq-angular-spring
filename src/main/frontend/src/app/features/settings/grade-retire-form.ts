import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { GradeState, Grades } from '../../core/grades';
import { Grade } from '../../core/people';
import { Session } from '../../core/session';

// The sheet's grades and the select a grade is retired with. A retired grade can no longer be
// chosen for a person; the people already on it keep it.
@Component({
  selector: 'app-grade-retire-form',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="grades-heading">
      <h3 id="grades-heading">Grades</h3>
      <form data-testid="grade-retire-form" [formGroup]="form" (ngSubmit)="submit()">
        <label for="grade-retire-select">Grade to retire</label>
        <select id="grade-retire-select" data-testid="grade-retire-select" formControlName="grade">
          @for (g of codes; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
        @if (error()) {
          <p role="alert" data-testid="grade-error">{{ error() }}</p>
        }
      </form>
      <table data-testid="grades">
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Retired</th></tr>
        </thead>
        <tbody>
          @for (g of grades(); track g.grade) {
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
export class GradeRetireForm {
  private readonly api = inject(Grades);
  private readonly session = inject(Session);

  protected readonly codes: Grade[] = ['A', 'B', 'C'];
  protected readonly grades = signal<GradeState[]>([]);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    grade: new FormControl<string>('A', { nonNullable: true }),
  });
  private submitting = false;
  private reads = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.session.loads();
      const sheetId = this.session.sheetId();
      this.grades.set([]);
      if (sheetId) void this.reload(sheetId);
    });
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { grade } = this.form.getRawValue();
    if (!this.codes.includes(grade as Grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    if (this.grades().some((g) => g.grade === grade && g.retired)) {
      this.error.set('That grade is already retired.');
      return;
    }
    this.submitting = true;
    try {
      this.grades.set(await this.api.retire(grade));
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That grade is already retired, or the sheet may not be changed.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'A viewer may not retire a grade.'
            : 'The grade could not be retired.',
      );
    } finally {
      this.submitting = false;
    }
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const grades = await this.api.list(sheetId);
      if (read === this.reads) this.grades.set(grades);
    } catch {
      // the list stays empty; the next load reads it again
    }
  }
}
