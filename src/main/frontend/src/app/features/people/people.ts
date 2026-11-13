import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface Person {
  id: number;
  name: string;
  grade: string;
}

// The people on the active sheet, in the order they were added, and the one form that adds a
// person or, once a person's editor is opened, saves their changes.
@Component({
  selector: 'app-people',
  template: `
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form (submit)="submit($event)">
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
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
      </form>
      @if (error(); as message) {
        <p role="alert" data-testid="person-form-error">{{ message }}</p>
      }

      <table>
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
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly grades = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.people.set([]);
      this.reset();
      if (sheet) void this.read(sheet, load);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
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

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const body = { name: this.name(), grade: this.grade() };
    if (!body.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    const id = this.editingId();
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    try {
      const saved = await firstValueFrom(
        id === null
          ? this.http.post<Person>(base, body)
          : this.http.put<Person>(`${base}/${id}`, body),
      );
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) =>
        id === null ? [...list, saved] : list.map((p) => (p.id === saved.id ? saved : p)),
      );
      this.reset();
    } catch {
      this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
    }
  }

  /** Read the sheet's people; a response for an earlier load is dropped. */
  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(
      this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`),
    );
    if (this.session.loads() === load) this.people.set(list);
  }
}
