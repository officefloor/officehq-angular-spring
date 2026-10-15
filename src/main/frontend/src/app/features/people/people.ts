import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PeopleService, Person } from './people.service';

// The people list: every person with their name and alpha, plus a form to add one.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule],
  template: `
    <h1>People</h1>

    <form [formGroup]="form" (ngSubmit)="add()" data-testid="person-form">
      <label for="person-name">Name</label>
      <input id="person-name" formControlName="name" data-testid="person-form-name" />
      <label for="person-alpha">Alpha</label>
      <input
        id="person-alpha"
        type="number"
        step="1"
        formControlName="alpha"
        data-testid="person-form-alpha"
      />
      <button type="submit" [disabled]="form.invalid || saving()" data-testid="person-form-submit">
        Add person
      </button>
    </form>

    @if (people().length === 0) {
      <p data-testid="people-empty">No people yet.</p>
    }
    <table data-testid="people-table">
      <thead>
        <tr>
          <th scope="col">Name</th>
          <th scope="col">Alpha</th>
        </tr>
      </thead>
      <tbody>
        @for (p of people(); track p.id) {
          <tr [attr.data-testid]="'person-row-' + p.id">
            <td data-testid="person-name">{{ p.name }}</td>
            <td data-testid="person-alpha">{{ p.alpha }}</td>
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class People {
  private readonly service = inject(PeopleService);

  protected readonly people = signal<Person[]>([]);
  protected readonly saving = signal(false);

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.pattern(/\S/)]],
    alpha: [null as number | null, [Validators.required, Validators.pattern(/^-?\d+$/)]],
  });

  constructor() {
    this.load();
  }

  protected add(): void {
    const { name, alpha } = this.form.getRawValue();
    if (this.form.invalid || alpha === null) return;
    this.saving.set(true);
    this.service.add({ name, alpha }).subscribe({
      next: () => {
        this.form.reset();
        this.saving.set(false);
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  private load(): void {
    this.service.list().subscribe((people) => this.people.set(people));
  }
}
