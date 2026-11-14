import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Session } from '../../core/session';
import { GRADES, PeopleApi, Person, personError } from './people-api';

// The people on the active sheet, in the order they were added, and the one form that adds a
// person or, with a person's editor open, saves that person.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editingId() === null ? 'Add person' : 'Edit person'">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" autocomplete="off" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">{{ editingId() === null ? 'Add' : 'Save' }}</button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>
      <table data-testid="people-list">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
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
  private readonly session = inject(Session);
  private readonly api = inject(PeopleApi);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
    grade: new FormControl<string>('A', { nonNullable: true, validators: Validators.required }),
  });

  /** Guards against a slower, older read landing over a newer one. */
  private readSeq = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.reset();
        this.people.set([]);
        if (id) void this.read(id);
      });
    });
  }

  protected edit(p: Person): void {
    this.error.set(null);
    this.editingId.set(p.id);
    this.form.setValue({ name: p.name, grade: p.grade });
  }

  protected cancel(): void {
    this.reset();
  }

  protected async submit(): Promise<void> {
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.form.getRawValue();
    if (!value.name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    if (!(GRADES as readonly string[]).includes(value.grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    const editing = this.editingId();
    try {
      if (editing === null) {
        const added = await this.api.add(sheetId, value);
        if (this.session.sheetId() === sheetId) this.people.update((ps) => [...ps, added]);
      } else {
        const saved = await this.api.update(sheetId, editing, value);
        if (this.session.sheetId() === sheetId) this.people.update((ps) => ps.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      this.error.set(personError(e));
    }
  }

  private reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A' });
    this.error.set(null);
  }

  private async read(sheetId: string): Promise<void> {
    const seq = ++this.readSeq;
    try {
      const people = await this.api.list(sheetId);
      if (seq === this.readSeq) this.people.set(people);
    } catch {
      if (seq === this.readSeq) this.error.set('Something went wrong loading people; try again.');
    }
  }
}
