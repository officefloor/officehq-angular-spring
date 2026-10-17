import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { Claims, counts, formatCents } from '../../core/claims';
import { GRADES, Grade, PeopleApi, Person } from '../../core/people';
import { Rates } from '../../core/rates';
import { Session } from '../../core/session';
import { PersonClaims } from '../claims/claims';

// The sheet's people, in the order they were added, and the one form that adds a person or edits
// an existing one.
@Component({
  selector: 'app-people',
  imports: [PersonClaims],
  template: `
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>

      <form (submit)="save($event)">
        <label for="person-name">Name</label>
        <input
          id="person-name"
          data-testid="person-form-name"
          [value]="name()"
          (input)="name.set($any($event.target).value); error.set(null)"
        />
        <label for="person-grade">Grade</label>
        <select
          id="person-grade"
          data-testid="person-form-grade"
          [value]="grade()"
          (change)="grade.set($any($event.target).value); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>

      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotal(p) }}</td>
              <td><app-person-claims [personId]="p.id" [personName]="p.name" [grade]="p.grade" /></td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>

      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotalAll() }}</strong></p>
    </section>
  `,
})
export class People {
  private readonly api = inject(PeopleApi);
  private readonly session = inject(Session);
  private readonly claims = inject(Claims);
  private readonly rates = inject(Rates);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  protected readonly name = signal('');
  protected readonly grade = signal<Grade>('A');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly claimedTotal = computed(() => formatCents(this.claims.totalCents()));

  /**
   * Each person's due-total in cents, by person id: the sum of their counted claims' due figures as
   * shown (each already rounded), skipping those that are n/a. Absent when none has a figure and
   * their grade has no rate today.
   */
  private readonly dueCents = computed(() => {
    const out = new Map<number, number>();
    for (const p of this.people()) {
      const figures = this.claims
        .all()
        .filter((c) => c.personId === p.id && counts(c))
        .map((c) => this.rates.dueOf(c, p.grade))
        .filter((c) => c !== undefined);
      if (figures.length > 0 || this.rates.byGrade().has(p.grade)) {
        out.set(p.id, figures.reduce((sum, c) => sum + c, 0));
      }
    }
    return out;
  });

  /** Everyone's due-total added up, skipping those that are n/a. */
  protected readonly dueTotalAll = computed(() =>
    formatCents([...this.dueCents().values()].reduce((sum, c) => sum + c, 0)),
  );

  constructor() {
    // Re-read the store each time the sheet is (re-)loaded.
    effect(() => {
      this.session.loads();
      untracked(() => {
        this.reset();
        void this.reload();
        void this.claims.reload();
      });
    });
  }

  protected claimed(personId: number): string {
    return formatCents(this.claims.claimedCents().get(personId) ?? 0);
  }

  protected dueTotal(person: Person): string {
    const cents = this.dueCents().get(person.id);
    return cents === undefined ? 'n/a' : formatCents(cents);
  }

  protected edit(person: Person): void {
    this.editingId.set(person.id);
    this.name.set(person.name);
    this.grade.set(person.grade);
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.name.set('');
    this.grade.set('A');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const fields = { name: this.name(), grade: this.grade() };
    if (!fields.name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    const id = this.editingId();
    try {
      if (id === null) {
        const added = await this.api.add(fields);
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await this.api.update(id, fields);
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on this sheet.');
        return;
      }
      this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
    }
  }

  private async reload(): Promise<void> {
    try {
      this.people.set(await this.api.list());
    } catch {
      this.people.set([]);
    }
  }
}
