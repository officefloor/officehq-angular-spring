import { Component, inject, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { of, switchMap } from 'rxjs';
import { Session } from '../../core/session';
import { Grade, PeopleApi, Person } from './people-api';

// The people on the active sheet, in the order they were added, and the one form that adds a
// person or, once a person's editor is opened, saves changes to them.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="people-heading" data-testid="people">
      <h2 id="people-heading">People</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="person-form">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
      </form>
      @if (formError(); as e) {
        <p role="alert" data-testid="person-form-error">{{ e }}</p>
      }
      <table data-testid="people-list">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td>
                <button
                  type="button"
                  [attr.data-testid]="'person-edit-' + p.id"
                  [attr.aria-label]="'Edit ' + p.name"
                  (click)="edit(p)"
                >
                  Edit
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class People {
  private readonly session = inject(Session);
  private readonly api = inject(PeopleApi);

  protected readonly grades: Grade[] = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  protected readonly editingId = signal<number | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.formError.set(null));
    // Re-read the sheet's people from the store each time a sheet is loaded.
    toObservable(this.session.sheetId)
      .pipe(switchMap((id) => (id ? this.api.list(id) : of([]))))
      .subscribe((people) => {
        this.people.set(people);
        this.reset();
      });
  }

  protected edit(person: Person): void {
    this.editingId.set(person.id);
    this.form.setValue({ name: person.name, grade: person.grade }, { emitEvent: false });
    this.formError.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
    this.formError.set(null);
  }

  protected submit(): void {
    this.formError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.formError.set('Load a sheet first.');
      return;
    }
    const value = this.form.getRawValue();
    if (!value.name.trim()) {
      this.formError.set('Type a name for the person.');
      return;
    }
    const id = this.editingId();
    if (id === null) {
      this.api.add(sheetId, value).subscribe({
        next: (p) => {
          if (this.session.sheetId() === sheetId) this.people.update((ps) => [...ps, p]);
          this.reset();
        },
        error: () => this.formError.set('Could not add that person.'),
      });
    } else {
      this.api.update(sheetId, id, value).subscribe({
        next: (p) => {
          if (this.session.sheetId() === sheetId) this.people.update((ps) => ps.map((x) => (x.id === p.id ? p : x)));
          this.reset();
        },
        error: () => this.formError.set('Could not save that person.'),
      });
    }
  }
}
