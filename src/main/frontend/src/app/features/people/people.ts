import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { of, switchMap } from 'rxjs';
import { GRADES, Grade, People, PersonView } from '../../core/people';
import { Sheets } from '../../core/sheet';

// The people on the active sheet: one form to add a person or edit an existing one, and the list.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>

      <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editingId() === null ? 'Add a person' : 'Edit a person'">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" autocomplete="off" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit" [disabled]="saving()">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>

      <ul data-testid="person-list">
        @for (p of people(); track p.id) {
          <li [attr.data-testid]="'person-row-' + p.id">
            <span data-testid="person-name">{{ p.name }}</span>
            — grade <span data-testid="person-grade">{{ p.grade }}</span>
            <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
                    [attr.aria-label]="'Edit ' + p.name">Edit</button>
          </li>
        }
      </ul>
    </section>
  `,
})
export class PeopleList {
  private readonly sheets = inject(Sheets);
  private readonly api = inject(People);

  protected readonly grades = GRADES;
  protected readonly people = signal<PersonView[]>([]);
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its people from the store.
    toObservable(this.sheets.current)
      .pipe(
        switchMap((sheet) => {
          this.people.set([]);
          this.reset();
          return sheet ? this.api.list(sheet.id) : of([]);
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ next: (list) => this.people.set(list) });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected edit(p: PersonView): void {
    this.editingId.set(p.id);
    this.form.setValue({ name: p.name, grade: p.grade }, { emitEvent: false });
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
    this.error.set(null);
  }

  protected submit(): void {
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.form.getRawValue();
    if (value.name.trim() === '') {
      this.error.set('Type a name for the person.');
      return;
    }
    const id = this.editingId();
    this.saving.set(true);
    const request = id === null ? this.api.add(sheet.id, value) : this.api.update(sheet.id, id, value);
    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.people.update((list) =>
          id === null ? [...list, saved] : list.map((p) => (p.id === saved.id ? saved : p)),
        );
        this.reset();
      },
      error: () => {
        this.saving.set(false);
        this.error.set(id === null ? 'Could not add that person.' : 'Could not save that person.');
      },
    });
  }
}
