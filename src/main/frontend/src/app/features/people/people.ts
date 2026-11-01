import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface Person {
  id: number;
  name: string;
  grade: string;
}

// The people on the active sheet, in the order they were added, with one form to add or edit.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form [formGroup]="form" (ngSubmit)="save()" [attr.aria-label]="editingId() === null ? 'Add person' : 'Edit person'">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" />
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
        @if (error()) {
          <p role="alert" class="error" data-testid="person-form-error">{{ error() }}</p>
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
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly grades = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl('A', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.people.set([]);
      this.reset();
      if (id) void this.load(id);
    });
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/people`;
  }

  private async load(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.get<Person[]>(this.url(sheet), { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.people.set(list);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the people on this sheet.');
    }
  }

  private reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
    this.error.set('');
  }

  protected edit(p: Person): void {
    this.editingId.set(p.id);
    this.form.setValue({ name: p.name, grade: p.grade }, { emitEvent: false });
    this.error.set('');
  }

  protected cancel(): void {
    this.reset();
  }

  protected async save(): Promise<void> {
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.form.getRawValue();
    const name = raw.name.trim();
    const grade = raw.grade;
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!this.grades.includes(grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    const id = this.editingId();
    try {
      if (id === null) {
        const added = await firstValueFrom(
          this.http.post<Person>(this.url(sheet), { name, grade }, { headers: this.headers() }),
        );
        if (this.session.sheetId() !== sheet) return;
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await firstValueFrom(
          this.http.put<Person>(`${this.url(sheet)}/${id}`, { name, grade }, { headers: this.headers() }),
        );
        if (this.session.sheetId() !== sheet) return;
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on this sheet.');
      } else {
        this.error.set(id === null ? 'Could not add that person.' : 'Could not save that person.');
      }
    }
  }
}
