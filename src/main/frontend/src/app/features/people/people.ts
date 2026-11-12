import { Component, inject, signal } from '@angular/core';
import { GRADES, People, Person } from '../../core/people';

// The people on the sheet: one form adds a person, or edits the one whose editor is open, and the
// list shows everyone in the order they were added.
@Component({
  selector: 'app-people',
  template: `
    <section aria-label="People" data-testid="people">
      <h2>People</h2>
      <form (submit)="submit($event)" data-testid="person-form">
        <label for="person-form-name">Name</label>
        <input
          id="person-form-name"
          data-testid="person-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="person-form-grade">Grade</label>
        <select
          id="person-form-grade"
          data-testid="person-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit" [disabled]="saving()">
          {{ editing() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>
      <table data-testid="person-list">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of people.people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class PeopleList {
  protected readonly people = inject(People);
  protected readonly grades = GRADES;
  protected readonly name = signal('');
  protected readonly grade = signal<string>('A');
  /** The id of the person whose editor is open, or null when the form adds. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected edit(p: Person): void {
    this.editing.set(p.id);
    this.name.set(p.name);
    this.grade.set(p.grade);
    this.error.set(null);
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.error.set(null);
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    this.error.set(null);
    this.saving.set(true);
    try {
      const f = { name: this.name(), grade: this.grade() };
      const id = this.editing();
      const err = id === null ? await this.people.add(f) : await this.people.update(id, f);
      if (err) this.error.set(err);
      else this.reset();
    } finally {
      this.saving.set(false);
    }
  }
}
