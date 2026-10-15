import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PeopleService, Person } from './people.service';

// The people list: every person with their name, alpha, beta, gamma, thrust (alpha × beta), drift
// (gamma − alpha) and momentum (thrust + 3 × drift) and its tier, plus a form to add one. Alpha, beta and
// gamma are optional on add and can be filled in (or changed) on the row later; while any is missing the
// derived values show "n/a". Each row's Edit button loads that person into the form, which then saves
// their alpha, beta and gamma instead of adding.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule],
  template: `
    <h1>People</h1>

    <form [formGroup]="form" (ngSubmit)="submit()" data-testid="person-form">
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
      <label for="person-gamma">Gamma</label>
      <input
        id="person-gamma"
        type="number"
        step="1"
        formControlName="gamma"
        data-testid="person-form-gamma"
      />
      <button type="submit" [disabled]="form.invalid || saving()" data-testid="person-form-submit">
        {{ editing() ? 'Save ' + editing()!.name : 'Add person' }}
      </button>
      @if (editing()) {
        <button type="button" (click)="cancelEdit()" data-testid="person-form-cancel">Cancel</button>
      }
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
          <th scope="col">Gamma</th>
          <th scope="col">Thrust</th>
          <th scope="col">Drift</th>
          <th scope="col">Momentum</th>
          <th scope="col">Tier</th>
          <th scope="col">Set alpha</th>
          <th scope="col">Set beta</th>
          <th scope="col">Set gamma</th>
          <th scope="col">Edit</th>
        </tr>
      </thead>
      <tbody>
        @for (p of people(); track p.id) {
          <tr [attr.data-testid]="'person-row-' + p.id">
            <td data-testid="person-name">{{ p.name }}</td>
            <td data-testid="person-alpha">{{ p.alpha }}</td>
            <td data-testid="person-beta">{{ p.beta }}</td>
            <td data-testid="person-gamma">{{ p.gamma }}</td>
            <td data-testid="concept-thrust">{{ p.thrust ?? 'n/a' }}</td>
            <td data-testid="concept-drift">{{ p.drift ?? 'n/a' }}</td>
            <td data-testid="concept-momentum">{{ p.momentum ?? 'n/a' }}</td>
            <td data-testid="concept-tier">{{ p.tier ?? 'n/a' }}</td>
            <td>
              <form (submit)="$event.preventDefault(); setAlpha(p, alphaInput.value)">
                <label class="visually-hidden" [for]="'person-alpha-edit-' + p.id">
                  Alpha for {{ p.name }}
                </label>
                <input
                  #alphaInput
                  [id]="'person-alpha-edit-' + p.id"
                  type="number"
                  step="1"
                  [value]="p.alpha ?? ''"
                  data-testid="person-alpha-input"
                />
                <button type="submit" data-testid="person-alpha-save">Save</button>
              </form>
            </td>
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
            <td>
              <form (submit)="$event.preventDefault(); setGamma(p, gammaInput.value)">
                <label class="visually-hidden" [for]="'person-gamma-edit-' + p.id">
                  Gamma for {{ p.name }}
                </label>
                <input
                  #gammaInput
                  [id]="'person-gamma-edit-' + p.id"
                  type="number"
                  step="1"
                  [value]="p.gamma ?? ''"
                  data-testid="person-gamma-input"
                />
                <button type="submit" data-testid="person-gamma-save">Save</button>
              </form>
            </td>
            <td>
              <button
                type="button"
                (click)="edit(p)"
                [attr.aria-label]="'Edit ' + p.name"
                [attr.data-testid]="'person-edit-' + p.id"
              >
                Edit
              </button>
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
  protected readonly editing = signal<Person | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.pattern(/\S/)]],
    alpha: [null as number | null, [Validators.pattern(/^-?\d+$/)]],
    beta: [null as number | null, [Validators.pattern(/^-?\d+$/)]],
    gamma: [null as number | null, [Validators.pattern(/^-?\d+$/)]],
  });

  constructor() {
    this.load();
  }

  protected edit(person: Person): void {
    this.editing.set(person);
    this.form.reset({ name: person.name, alpha: person.alpha, beta: person.beta, gamma: person.gamma });
    this.form.controls.name.disable();
  }

  protected cancelEdit(): void {
    this.editing.set(null);
    this.form.controls.name.enable();
    this.form.reset();
  }

  protected submit(): void {
    const { name, alpha, beta, gamma } = this.form.getRawValue();
    if (this.form.invalid) return;
    const editing = this.editing();
    const request = editing
      ? this.service.update(editing.id, { alpha: toNumber(alpha), beta: toNumber(beta), gamma: toNumber(gamma) })
      : this.service.add({ name, alpha, beta, gamma });
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.saving.set(false);
        this.cancelEdit();
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  protected setAlpha(person: Person, value: string): void {
    const text = value.trim();
    if (text !== '' && !/^-?\d+$/.test(text)) return;
    const alpha = text === '' ? null : Number(text);
    this.service.setAlpha(person.id, alpha).subscribe(() => this.load());
  }

  protected setBeta(person: Person, value: string): void {
    const text = value.trim();
    if (text !== '' && !/^-?\d+$/.test(text)) return;
    const beta = text === '' ? null : Number(text);
    this.service.setBeta(person.id, beta).subscribe(() => this.load());
  }

  protected setGamma(person: Person, value: string): void {
    const text = value.trim();
    if (text !== '' && !/^-?\d+$/.test(text)) return;
    const gamma = text === '' ? null : Number(text);
    this.service.setGamma(person.id, gamma).subscribe(() => this.load());
  }

  private load(): void {
    this.service.list().subscribe((people) => this.people.set(people));
  }
}

// A number input's value as a whole number, or null when it is empty.
function toNumber(value: number | string | null): number | null {
  return value === null || value === '' ? null : Number(value);
}
