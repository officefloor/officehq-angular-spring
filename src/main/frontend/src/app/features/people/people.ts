import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claims, PersonWithClaims as Person } from '../claims/claims';
import { Rates } from '../rates/rates';

const GRADES = ['A', 'B', 'C'];

/** Cents in an amount shown with two decimals ("12.50" is 1250). */
function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

// The people on the active sheet, in the order they were added, each with their claims, and the
// one form that adds a person or, once a person's editor is opened, edits them.
@Component({
  selector: 'app-people',
  imports: [Claims, Rates],
  template: `
    <app-rates (changed)="reloadSheet()" />
    <section aria-label="People">
      <h2>People</h2>
      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <form (submit)="save($event)" [attr.aria-label]="editing() === null ? 'Add person' : 'Edit person'">
        <label for="person-form-name">Name</label>
        <input
          id="person-form-name"
          data-testid="person-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set('')"
        />
        <label for="person-form-grade">Grade</label>
        <select
          id="person-form-grade"
          data-testid="person-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set('')"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">
          {{ editing() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error()) {
          <p role="alert" data-testid="person-form-error">{{ error() }}</p>
        }
      </form>
      <table>
        <thead>
          <tr>
            <th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th><th scope="col">Claims</th>
          </tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ p.claimed }}</td>
              <td data-testid="person-due-total">{{ p.dueTotal ?? 'n/a' }}</td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
              </td>
              <td><app-claims [person]="p" (saved)="replace($event)" /></td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class People {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');
  protected readonly claimedTotal = computed(() =>
    (this.people().reduce((sum, p) => sum + cents(p.claimed), 0) / 100).toFixed(2),
  );
  /** Everyone's due-total added up; a person whose grade has no rate is skipped. */
  protected readonly dueTotal = computed(() =>
    (this.people().reduce((sum, p) => sum + (p.dueTotal === null ? 0 : cents(p.dueTotal)), 0) / 100).toFixed(2),
  );

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.people.set([]);
      this.reset();
      if (sheet) void this.reload(sheet);
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected edit(p: Person): void {
    this.editing.set(p.id);
    this.name.set(p.name);
    this.grade.set(p.grade);
    this.error.set('');
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.error.set('');
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.name().trim();
    const grade = this.grade();
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!GRADES.includes(grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    const id = this.editing();
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    const headers = { 'X-User': this.session.user() ?? '' };
    try {
      const saved = await firstValueFrom(
        id === null
          ? this.http.post<Person>(base, { name, grade }, { headers })
          : this.http.put<Person>(`${base}/${id}`, { name, grade }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
      this.reset();
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      if (err instanceof HttpErrorResponse && err.status === 409) {
        this.error.set('That name is already in use on this sheet.');
        return;
      }
      this.error.set(id === null ? 'Could not add the person.' : 'Could not save the person.');
    }
  }

  /** Show a person as saved: in their place, or added at the end when new. */
  protected replace(saved: Person): void {
    this.people.update((ps) =>
      ps.some((p) => p.id === saved.id) ? ps.map((p) => (p.id === saved.id ? saved : p)) : [...ps, saved],
    );
  }

  /** Show the people again as the store holds them, once a rate changed what they are due. */
  protected reloadSheet(): void {
    const sheet = this.session.sheet();
    if (sheet) void this.reload(sheet);
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const people = await firstValueFrom(
      this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`),
    );
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.people.set(people);
  }
}
