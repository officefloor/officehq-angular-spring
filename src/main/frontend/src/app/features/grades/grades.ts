import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { GRADES, SettingsApi, retireGradeError } from '../settings/settings-api';

// The grades and whether each is retired, with the form that retires one: a retired grade cannot be
// chosen for a new person or in an edit, while the people already on it keep it.
@Component({
  selector: 'app-grades',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="grades-heading">
      <h2 id="grades-heading">Grades</h2>
      <ul aria-label="Grades">
        @for (g of grades; track g) {
          <li [attr.data-testid]="'grade-row-' + g">
            Grade {{ g }}, retired <strong data-testid="grade-retired">{{ session.retiredGrades().includes(g) ? 'yes' : 'no' }}</strong>
          </li>
        }
      </ul>
      <form [formGroup]="retireForm" (ngSubmit)="retire()" aria-label="Retire a grade">
        <label for="grade-retire-select">Grade to retire</label>
        <select id="grade-retire-select" data-testid="grade-retire-select" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
        @if (error(); as err) {
          <p role="alert" data-testid="grade-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class Grades {
  protected readonly session = inject(Session);
  private readonly api = inject(SettingsApi);
  protected readonly grades = GRADES;
  protected readonly error = signal<string | null>(null);
  private retiring = false;

  protected readonly retireForm = new FormGroup({
    grade: new FormControl<string>('A', { nonNullable: true }),
  });

  constructor() {
    this.retireForm.valueChanges.subscribe(() => this.error.set(null));
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => this.error.set(null));
    });
  }

  protected async retire(): Promise<void> {
    if (this.retiring) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.retiring = true;
    try {
      const saved = await this.api.retireGrade(sheetId, this.retireForm.getRawValue().grade);
      if (this.session.sheetId() === sheetId) this.session.retiredGrades.set(saved.retiredGrades);
    } catch (e) {
      this.error.set(retireGradeError(e));
    } finally {
      this.retiring = false;
    }
  }
}
