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
      <form (submit)="merge($event)" aria-label="Merge a grade into another">
        <label for="grade-merge-from">Merge grade</label>
        <select
          id="grade-merge-from"
          data-testid="grade-merge-from"
          [value]="mergeFrom()"
          (change)="mergeFrom.set(chosen($event)); error.set(null)"
        >
          @for (g of codes; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="grade-merge-into">into</label>
        <select
          id="grade-merge-into"
          data-testid="grade-merge-into"
          [value]="mergeInto()"
          (change)="mergeInto.set(chosen($event)); error.set(null)"
        >
          @for (g of codes; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-merge">Merge grade</button>
        <span role="status">
          @if (merged(); as m) {
            Merged <strong data-testid="grade-merged">{{ m }}</strong>
          }
        </span>
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
  protected readonly mergeFrom = signal<Grade>('A');
  protected readonly mergeInto = signal<Grade>('B');
  /** The last merge done from this screen, as "from into into". */
  protected readonly merged = signal('');
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

  protected async merge(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const from = this.mergeFrom();
    const into = this.mergeInto();
    if (from === into) {
      this.error.set('A grade cannot be merged into itself.');
      return;
    }
    this.busy = true;
    try {
      const done = await this.store.merge(sheet, from, into);
      this.merged.set(`${done.from} into ${done.into}`);
    } catch {
      this.error.set(`The grade ${from} could not be merged into ${into}.`);
    } finally {
      this.busy = false;
    }
  }
}
