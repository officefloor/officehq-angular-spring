import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { SettingsApi, addGradeError, retireGradeError } from '../settings/settings-api';
import { PeopleApi, mergeGradeError } from '../people/people-api';

// The grades and whether each is retired, with the form that adds one (a code of one to three
// capital letters, not yet a grade), the form that retires one: a retired grade cannot be
// chosen for a new person or in an edit, while the people already on it keep it; and the form by which
// a steward merges a grade into another, giving everyone on it today the other, retroactively.
@Component({
  selector: 'app-grades',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="grades-heading">
      <h2 id="grades-heading">Grades</h2>
      <ul aria-label="Grades">
        @for (g of grades(); track g) {
          <li [attr.data-testid]="'grade-row-' + g">
            Grade {{ g }}, retired <strong data-testid="grade-retired">{{ session.retiredGrades().includes(g) ? 'yes' : 'no' }}</strong>
          </li>
        }
      </ul>
      <form [formGroup]="addForm" (ngSubmit)="add()" aria-label="Add a grade">
        <label for="grade-add-code">Grade to add (one to three capital letters)</label>
        <input id="grade-add-code" data-testid="grade-add-code" formControlName="code" autocomplete="off" />
        <button type="submit" data-testid="grade-add">Add grade</button>
        @if (addError(); as err) {
          <p role="alert" data-testid="grade-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="retireForm" (ngSubmit)="retire()" aria-label="Retire a grade">
        <label for="grade-retire-select">Grade to retire</label>
        <select id="grade-retire-select" data-testid="grade-retire-select" formControlName="grade">
          @for (g of grades(); track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire">Retire grade</button>
        @if (error(); as err) {
          <p role="alert" data-testid="grade-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="mergeForm" (ngSubmit)="merge()" aria-label="Merge a grade">
        <label for="grade-merge-from">Grade to merge</label>
        <select id="grade-merge-from" data-testid="grade-merge-from" formControlName="from">
          @for (g of grades(); track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="grade-merge-into">Merge into</label>
        <select id="grade-merge-into" data-testid="grade-merge-into" formControlName="into">
          @for (g of grades(); track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-merge">Merge grade</button>
        @if (merged() !== null) {
          <p role="status">Changed <strong data-testid="grade-merged">{{ merged() }}</strong></p>
        }
        @if (mergeError(); as err) {
          <p role="alert" data-testid="grade-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class Grades {
  protected readonly session = inject(Session);
  private readonly api = inject(SettingsApi);
  protected readonly grades = this.session.grades;
  protected readonly error = signal<string | null>(null);
  protected readonly addError = signal<string | null>(null);
  private adding = false;
  private retiring = false;
  private readonly people = inject(PeopleApi);
  protected readonly mergeError = signal<string | null>(null);
  /** How many people the last merge changed; cleared when the form is submitted again or a sheet loaded. */
  protected readonly merged = signal<number | null>(null);
  private merging = false;
  /** The sheet load this screen's own merge made, so loading it does not clear the merge's count. */
  private mergeLoad = -1;

  protected readonly mergeForm = new FormGroup({
    from: new FormControl<string>('A', { nonNullable: true }),
    into: new FormControl<string>('B', { nonNullable: true }),
  });

  protected readonly addForm = new FormGroup({
    code: new FormControl<string>('', { nonNullable: true }),
  });

  protected readonly retireForm = new FormGroup({
    grade: new FormControl<string>('A', { nonNullable: true }),
  });

  constructor() {
    this.addForm.valueChanges.subscribe(() => this.addError.set(null));
    this.retireForm.valueChanges.subscribe(() => this.error.set(null));
    this.mergeForm.valueChanges.subscribe(() => this.mergeError.set(null));
    effect(() => {
      this.session.sheetId();
      const loads = this.session.loads();
      untracked(() => {
        this.addError.set(null);
        this.error.set(null);
        this.mergeError.set(null);
        if (loads !== this.mergeLoad) this.merged.set(null);
      });
    });
  }

  /** Add the typed grade to the sheet; refused, without asking the store, when it is not one to
   *  three capital letters or is a grade already. */
  protected async add(): Promise<void> {
    if (this.adding) return;
    this.addError.set(null);
    this.error.set(null);
    this.mergeError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const code = this.addForm.getRawValue().code.trim();
    if (!/^[A-Z]{1,3}$/.test(code)) {
      this.addError.set('Enter a grade of one to three capital letters.');
      return;
    }
    if (this.grades().includes(code)) {
      this.addError.set('That grade is a grade already.');
      return;
    }
    this.adding = true;
    try {
      const saved = await this.api.addGrade(sheetId, code);
      if (this.session.sheetId() === sheetId) this.session.grades.set(saved.grades);
    } catch (e) {
      this.addError.set(addGradeError(e));
    } finally {
      this.adding = false;
    }
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

  /** Merge the chosen grade into the other, then re-load the sheet to show everyone as the store now holds them. */
  protected async merge(): Promise<void> {
    if (this.merging) return;
    this.mergeError.set(null);
    this.merged.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const { from, into } = this.mergeForm.getRawValue();
    this.merging = true;
    try {
      const result = await this.people.mergeGrade(sheetId, from, into);
      if (this.session.sheetId() !== sheetId) return;
      this.mergeLoad = this.session.loads() + 1;
      await this.session.loadSheet(sheetId);
      this.merged.set(result.changed);
    } catch (e) {
      this.mergeError.set(mergeGradeError(e));
    } finally {
      this.merging = false;
    }
  }
}
