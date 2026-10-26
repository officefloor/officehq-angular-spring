import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, Claims, claimedTotal } from '../claims/claims';
import { GRADES, RateStore, dueOn, sumSkippingNa } from '../rates/rates';

interface Person {
  id: number;
  name: string;
  grade: string;
}

// The people on the active sheet: one form adds a person or, with an editor open, saves one.
@Component({
  selector: 'app-people',
  imports: [Claims],
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

      <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>

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
              <td data-testid="person-claimed">{{ claimedBy().get(p.id) ?? '0.00' }}</td>
              <td data-testid="person-due-total">{{ dueBy().get(p.id) ?? 'n/a' }}</td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
              </td>
              <td>
                <app-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsBy().get(p.id) ?? []"
                  [rates]="rates.rates().get(p.grade) ?? []"
                  (saved)="claimSaved($event)"
                />
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
  protected readonly rates = inject(RateStore);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  // Every claim on the sheet, newest first.
  protected readonly claims = signal<Claim[]>([]);
  protected readonly claimsBy = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  protected readonly claimedBy = computed(() => {
    const by = new Map<number, string>();
    for (const [id, list] of this.claimsBy()) by.set(id, claimedTotal(list));
    return by;
  });
  protected readonly claimedTotal = computed(() => claimedTotal(this.claims()));
  // Each person's due-total: their claimed times their grade's rate in force today; null (n/a) with none.
  protected readonly dueBy = computed(() => {
    const by = new Map<number, string | null>();
    const rates = this.rates.current();
    for (const p of this.people()) by.set(p.id, dueOn(this.claimedBy().get(p.id) ?? '0.00', rates.get(p.grade)));
    return by;
  });
  protected readonly dueTotal = computed(() => sumSkippingNa([...this.dueBy().values()]));

  constructor() {
    // Re-read the people from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.people.set([]);
        this.claims.set([]);
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

  protected claimSaved(saved: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === saved.id)
        ? list.map((c) => (c.id === saved.id ? saved : c))
        : [saved, ...list],
    );
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
      const base = `/api/sheets/${encodeURIComponent(sheet)}`;
      const [list, claims] = await Promise.all([
        firstValueFrom(this.http.get<Person[]>(`${base}/people`)),
        firstValueFrom(this.http.get<Claim[]>(`${base}/claims`)),
        this.rates.load(sheet),
      ]);
      // a later load may have moved to another sheet while this one was in flight
      if (this.session.sheetId() === sheet) {
        this.claims.set(claims);
        this.people.set(list);
      }
    } catch {
      this.error.set('Could not load the people.');
    }
  }
}
