import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PeopleService, Person } from './people.service';

// The people list: every person with their name, alpha, beta and thrust (alpha × beta), plus a
// form to add one. Beta is optional on add and can be filled in (or changed) on the row later.
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
      <label for="person-beta">Beta</label>
      <input
        id="person-beta"
        type="number"
        step="1"
        formControlName="beta"
        data-testid="person-form-beta"
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
          <th scope="col">Beta</th>
          <th scope="col">Thrust</th>
          <th scope="col">Set beta</th>
        </tr>
      </thead>
      <tbody>
        @for (p of people(); track p.id) {
          <tr [attr.data-testid]="'person-row-' + p.id">
            <td data-testid="person-name">{{ p.name }}</td>
            <td data-testid="person-alpha">{{ p.alpha }}</td>
            <td data-testid="person-beta">{{ p.beta }}</td>
            <td data-testid="concept-thrust">{{ p.thrust }}</td>
            <td>
              <form (submit)="$event.preventDefault(); setBeta(p, betaInput.value)">
                <label class="visually-hidden" [for]="'person-beta-edit-' + p.id">
                  Beta for {{ p.name }}
                </label>
                <input
                  #betaInput
                  [id]="'person-beta-edit-' + p.id"
                  type="number"
                  step="1"
                  [value]="p.beta ?? ''"
                  data-testid="person-beta-input"
                />
                <button type="submit" data-testid="person-beta-save">Save</button>
              </form>
            </td>
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
    beta: [null as number | null, [Validators.pattern(/^-?\d+$/)]],
  });

  constructor() {
    this.load();
  }

  protected add(): void {
    const { name, alpha, beta } = this.form.getRawValue();
    if (this.form.invalid || alpha === null) return;
    this.saving.set(true);
    this.service.add({ name, alpha, beta }).subscribe({
      next: () => {
        this.form.reset();
        this.saving.set(false);
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  protected setBeta(person: Person, value: string): void {
    const text = value.trim();
    if (text !== '' && !/^-?\d+$/.test(text)) return;
    const beta = text === '' ? null : Number(text);
    this.service.setBeta(person.id, beta).subscribe(() => this.load());
  }

  private load(): void {
    this.service.list().subscribe((people) => this.people.set(people));
  }
}
