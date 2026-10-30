import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface Person {
  id: number;
  name: string;
  grade: string;
}

const GRADES = ['A', 'B', 'C'];

// The people on the active sheet, in the order they were added, and the one form that adds a
// person or, once a person's editor is opened, edits them.
@Component({
  selector: 'app-people',
  template: `
    <section aria-label="People">
      <h2>People</h2>
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
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
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

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');

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
    const name = this.name();
    const grade = this.grade();
    if (!name.trim()) {
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
      this.people.update((ps) =>
        ps.some((p) => p.id === saved.id) ? ps.map((p) => (p.id === saved.id ? saved : p)) : [...ps, saved],
      );
      this.reset();
    } catch {
      this.error.set(id === null ? 'Could not add the person.' : 'Could not save the person.');
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const people = await firstValueFrom(
      this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`),
    );
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.people.set(people);
  }
}
