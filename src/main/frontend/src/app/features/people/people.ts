import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface Person {
  id: number;
  name: string;
  grade: string;
}

const GRADES = ['A', 'B', 'C'];

// The people on the active sheet: one form adds a person or, with an editor open, saves one.
@Component({
  selector: 'app-people',
  template: `
    <section aria-label="People">
      <h2>People</h2>

      <form (submit)="submit($event)" [attr.aria-label]="editingId() === null ? 'Add person' : 'Edit person'">
        <label for="person-name">Name</label>
        <input
          id="person-name"
          data-testid="person-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="person-grade">Grade</label>
        <select
          id="person-grade"
          data-testid="person-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save' }}
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
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // Re-read the people from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.people.set([]);
        this.reset();
        if (id) void this.fetch(id);
      });
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected edit(p: Person): void {
    this.editingId.set(p.id);
    this.name.set(p.name);
    this.grade.set(p.grade);
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.name.set('');
    this.grade.set('A');
    this.error.set(null);
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const form = { name: this.name().trim(), grade: this.grade() };
    if (!form.name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!GRADES.includes(form.grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    const editing = this.editingId();
    try {
      if (editing === null) {
        const added = await firstValueFrom(this.http.post<Person>(base, form));
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await firstValueFrom(this.http.put<Person>(`${base}/${editing}`, form));
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (err) {
      if (err instanceof HttpErrorResponse && err.status === 409) {
        this.error.set('That name is already in use on this sheet.');
        return;
      }
      this.error.set(editing === null ? 'Could not add that person.' : 'Could not save that person.');
    }
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(
        this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`),
      );
      // a later load may have moved to another sheet while this one was in flight
      if (this.session.sheetId() === sheet) this.people.set(list);
    } catch {
      this.error.set('Could not load the people.');
    }
  }
}
