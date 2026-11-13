import { Component, inject, signal } from '@angular/core';
import { GRADES, People } from '../../core/people';
import { Session } from '../../core/session';

// The grades: each grade and whether it is retired, and a select and a button to retire one (a grade already
// retired is refused). A retired grade cannot be chosen for a person; the people on it keep it. Two selects
// and a button merge a grade into another (a steward's only): each person on the merged grade today is given
// the other from today; the screen shows the merge done and how many people it changed. An entry and a button
// add a grade: a code of one to three capital letters not yet a grade (surrounding blanks ignored); the new
// grade can be chosen wherever a grade can.
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
          @for (g of grades(); track g) {
            <tr [attr.data-testid]="'grade-row-' + g">
              <td data-testid="grade-code">{{ g }}</td>
              <td data-testid="grade-retired">{{ session.retiredGrades().includes(g) ? 'yes' : 'no' }}</td>
            </tr>
          }
        </tbody>
      </table>
      <form (submit)="add($event)">
        <label for="grade-add-code">New grade</label>
        <input
          id="grade-add-code"
          data-testid="grade-add-code"
          [value]="code()"
          (input)="code.set(text($event)); addError.set(null)"
        />
        <button type="submit" data-testid="grade-add" [disabled]="adding()">Add grade</button>
        @if (addError(); as err) {
          <p role="alert" data-testid="grade-error">{{ err }}</p>
        }
      </form>
      <form (submit)="retire($event)">
        <label for="grade-retire-select">Grade to retire</label>
        <select
          id="grade-retire-select"
          data-testid="grade-retire-select"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire" [disabled]="saving()">Retire grade</button>
        @if (error(); as err) {
          <p role="alert" data-testid="grade-error">{{ err }}</p>
        }
      </form>
      <form (submit)="merge($event)">
        <label for="grade-merge-from">Merge grade</label>
        <select
          id="grade-merge-from"
          data-testid="grade-merge-from"
          [value]="mergeFrom()"
          (change)="mergeFrom.set(value($event)); mergeError.set(null)"
        >
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === mergeFrom()">{{ g }}</option>
          }
        </select>
        <label for="grade-merge-into">into</label>
        <select
          id="grade-merge-into"
          data-testid="grade-merge-into"
          [value]="mergeInto()"
          (change)="mergeInto.set(value($event)); mergeError.set(null)"
        >
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === mergeInto()">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-merge" [disabled]="merging()">Merge grade</button>
        <output data-testid="grade-merged">{{ merged() }}</output>
        @if (mergeError(); as err) {
          <p role="alert" data-testid="grade-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class GradesPanel {
  protected readonly session = inject(Session);
  protected readonly grades = this.session.grades;
  protected readonly grade = signal<string>(GRADES[0]);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  private readonly people = inject(People);
  protected readonly mergeFrom = signal<string>(GRADES[0]);
  protected readonly mergeInto = signal<string>(GRADES[1]);
  /** The merge last done, and how many people it changed; blank while a merge runs. */
  protected readonly merged = signal('');
  protected readonly mergeError = signal<string | null>(null);
  protected readonly merging = signal(false);
  protected readonly code = signal('');
  protected readonly addError = signal<string | null>(null);
  protected readonly adding = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLSelectElement).value;
  }

  protected text(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.adding()) return; // a second submit before the first has answered
    this.addError.set(null);
    this.error.set(null);
    this.mergeError.set(null);
    this.adding.set(true);
    try {
      const err = await this.session.addGrade(this.code());
      if (err) this.addError.set(err);
      else this.code.set('');
    } finally {
      this.adding.set(false);
    }
  }

  protected async retire(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.mergeError.set(null);
    this.addError.set(null);
    this.saving.set(true);
    try {
      const err = await this.session.retireGrade(this.grade());
      if (err) this.error.set(err);
    } finally {
      this.saving.set(false);
    }
  }

  protected async merge(e: Event): Promise<void> {
    e.preventDefault();
    if (this.merging()) return; // a second submit before the first has answered
    this.error.set(null);
    this.mergeError.set(null);
    this.addError.set(null);
    this.merged.set('');
    this.merging.set(true);
    const from = this.mergeFrom();
    const into = this.mergeInto();
    try {
      const done = await this.people.mergeGrade(from, into);
      if (typeof done === 'string') this.mergeError.set(done);
      else this.merged.set(`${from} into ${into}: ${done}`);
    } finally {
      this.merging.set(false);
    }
  }
}
