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
      </form>
      <form data-testid="grade-merge-form" [formGroup]="mergeForm" (ngSubmit)="merge()">
        <label for="grade-merge-from">Grade to merge</label>
        <select id="grade-merge-from" data-testid="grade-merge-from" formControlName="from">
          @for (g of codes; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="grade-merge-into">Merge into</label>
        <select id="grade-merge-into" data-testid="grade-merge-into" formControlName="into">
          @for (g of codes; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-merge">Merge grade</button>
        <output data-testid="grade-merged">{{ merged() }}</output>
      </form>
      @if (error()) {
        <p role="alert" data-testid="grade-error">{{ error() }}</p>
      }
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
  protected readonly mergeForm = new FormGroup({
    from: new FormControl<string>('A', { nonNullable: true }),
    into: new FormControl<string>('B', { nonNullable: true }),
  });
  /** How many people the last merge gave the other grade; empty until one is done. */
  protected readonly merged = signal('');
  private submitting = false;
  private reads = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.mergeForm.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.session.loads();
      const sheetId = this.session.sheetId();
      this.grades.set([]);
      this.merged.set('');
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

  protected async merge(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    this.merged.set('');
    const { from, into } = this.mergeForm.getRawValue();
    if (from === into) {
      this.error.set('Choose two different grades to merge.');
      return;
    }
    this.submitting = true;
    try {
      this.merged.set(String(await this.api.merge(from, into)));
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward may merge a grade.'
          : e instanceof HttpErrorResponse && e.status === 409
            ? 'The sheet may not be changed.'
            : 'The grade could not be merged.',
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
