import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { GRADES, Grade, PeopleService, Person } from '../../core/people.service';
import { SheetService } from '../../core/sheet.service';

// The sheet's people: a form that adds a person or edits the one opened, and the list.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editing() === null ? 'Add person' : 'Edit person'">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">{{ editing() === null ? 'Add' : 'Save' }}</button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error()) {
          <p role="alert" data-testid="person-form-error">{{ error() }}</p>
        }
      </form>
      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of people.people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="open(p)"
                  [attr.aria-label]="'Edit ' + p.name">Edit</button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class People {
  protected readonly people = inject(PeopleService);
  private readonly sheets = inject(SheetService);
  protected readonly grades = GRADES;
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
  });
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.sheets.loads();
      untracked(() => {
        this.reset();
        this.people.reload();
      });
    });
  }

  protected open(p: Person): void {
    this.error.set('');
    this.editing.set(p.id);
    this.form.setValue({ name: p.name, grade: p.grade }, { emitEvent: false });
  }

  protected reset(): void {
    this.error.set('');
    this.editing.set(null);
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
  }

  protected submit(): void {
    this.error.set('');
    const { name, grade } = this.form.getRawValue();
    if (!name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    const id = this.editing();
    const saved = id === null ? this.people.add({ name, grade }) : this.people.edit(id, { name, grade });
    saved.subscribe({
      next: () => this.reset(),
      error: (e: { status?: number }) =>
        this.error.set(
          e.status === 409
            ? 'That name is already in use.'
            : id === null ? 'Could not add that person.' : 'Could not save that person.',
        ),
    });
  }
}
