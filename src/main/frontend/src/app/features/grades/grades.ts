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
      <form [formGroup]="mergeForm" (ngSubmit)="merge()" aria-label="Merge a grade into another">
        <label for="grade-merge-from">Merge grade</label>
        <select id="grade-merge-from" data-testid="grade-merge-from" formControlName="from">
          @for (g of choices; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="grade-merge-into">into</label>
        <select id="grade-merge-into" data-testid="grade-merge-into" formControlName="into">
          @for (g of choices; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-merge" [disabled]="merging()">Merge grade</button>
        <span aria-live="polite">
          @if (merged() !== null) {
            Merged <strong data-testid="grade-merged">{{ merged() }}</strong>
          }
        </span>
        @if (mergeError()) {
          <p role="alert" data-testid="grade-error">{{ mergeError() }}</p>
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

  protected readonly mergeFrom = new FormControl<Grade>('A', { nonNullable: true });
  protected readonly mergeInto = new FormControl<Grade>('B', { nonNullable: true });
  protected readonly mergeForm = new FormGroup({ from: this.mergeFrom, into: this.mergeInto });
  protected readonly merging = signal(false);
  /** How many people the last merge gave the other grade; null before one, and while one runs. */
  protected readonly merged = signal<number | null>(null);
  protected readonly mergeError = signal('');

  constructor() {
    this.grade.valueChanges.subscribe(() => this.error.set(''));
    this.mergeForm.valueChanges.subscribe(() => this.mergeError.set(''));
    // Each load of a sheet starts the choice afresh.
    effect(() => {
      this.session.loaded();
      this.error.set('');
      this.grade.reset('A', { emitEvent: false });
      this.mergeError.set('');
      this.merged.set(null);
      this.mergeForm.reset({ from: 'A', into: 'B' }, { emitEvent: false });
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

  protected async merge(): Promise<void> {
    // A second press before the first has answered is ignored, so the grade is merged once.
    if (this.merging()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.mergeError.set('');
    this.merged.set(null);
    const from = this.mergeFrom.value;
    const into = this.mergeInto.value;
    if (!GRADES.includes(from) || !GRADES.includes(into)) {
      this.mergeError.set('Choose grades of A, B or C.');
      return;
    }
    if (from === into) {
      this.mergeError.set('Choose two different grades to merge.');
      return;
    }
    this.merging.set(true);
    try {
      const merged = await this.store.merge(sheet.id, from, into);
      if (this.session.sheet()?.id === sheet.id) this.merged.set(merged);
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.mergeError.set(status === 403 ? 'Only a steward can merge grades.'
        : status === 422 ? `Grade ${into} is retired; choose another grade.`
        : status === 400 ? 'Choose two different grades to merge.' : 'The grade could not be merged.');
    } finally {
      this.merging.set(false);
    }
  }
}
