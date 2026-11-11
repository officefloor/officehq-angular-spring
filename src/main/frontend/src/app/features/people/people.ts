import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, Claims, sumAmounts } from '../claims/claims';
import { Rate, Rates, dueOf, sumDues } from '../rates/rates';

interface Person {
  id: number;
  name: string;
  grade: string;
}

// The people on the active sheet, in the order they were added, each with their claims and what
// they have claimed and their due-total, the sheet's claimed-total and due-total, the grades' rates, and the one form that adds a person or, once a
// person's editor is opened, saves that person.
@Component({
  selector: 'app-people',
  imports: [Claims, Rates],
  template: `
    <app-rates [grades]="grades" [rates]="rates()" (saved)="rateSaved($event)" />
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
          {{ editing() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as message) {
          <p role="alert" data-testid="person-form-error">{{ message }}</p>
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
              <td data-testid="person-claimed">{{ claimedBy(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotalOf(p) }}</td>
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
              <td
                app-claims
                [personId]="p.id"
                [personName]="p.name"
                [claims]="claimsOf(p.id)"
                [pct]="rateOf(p.grade)"
                (saved)="claimSaved($event)"
              ></td>
            </tr>
          }
        </tbody>
      </table>
      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
    </section>
  `,
})
export class People {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly grades = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) {
      const list = by.get(c.personId);
      if (list) list.push(c);
      else by.set(c.personId, [c]);
    }
    return by;
  });
  protected readonly claimedTotal = computed(() => sumAmounts(this.claims().map((c) => c.amount)));
  /** The sheet's rates, one per grade that has one. */
  protected readonly rates = signal<Rate[]>([]);
  private readonly rateByGrade = computed(() => new Map(this.rates().map((r) => [r.grade, r.pct])));
  /** Everyone's due-total added up, skipping the n/a ones. */
  protected readonly dueTotal = computed(() => sumDues(this.people().map((p) => this.dueTotalOf(p))));
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  /** The id of the person whose editor is open, or null when the form adds a person. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  private reads = 0;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.people.set([]);
        this.claims.set([]);
        this.rates.set([]);
        this.reset();
        if (sheet) void this.read(sheet);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  /** The sum of the person's claims' amounts, with two decimals. */
  protected claimedBy(personId: number): string {
    return sumAmounts(this.claimsOf(personId).map((c) => c.amount));
  }

  /** The rate for a grade, or undefined when it has none. */
  protected rateOf(grade: string): string | undefined {
    return this.rateByGrade().get(grade);
  }

  /** The person's claimed times their grade's rate, or n/a when the grade has no rate. */
  protected dueTotalOf(person: Person): string {
    return dueOf(this.claimedBy(person.id), this.rateOf(person.grade));
  }

  /** Show a set rate at once, then re-read so the screen holds what the store does. */
  protected rateSaved(rate: Rate): void {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.rates.update((list) =>
      [...list.filter((r) => r.grade !== rate.grade), rate].sort((a, b) => a.grade.localeCompare(b.grade)),
    );
    void this.read(sheet);
  }

  /** Show an added or saved claim at once, then re-read so the screen holds what the store does. */
  protected claimSaved(claim: Claim): void {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id)
        ? list.map((c) => (c.id === claim.id ? claim : c))
        : [claim, ...list].sort((a, b) => b.id - a.id),
    );
    void this.read(sheet);
  }

  protected edit(person: Person): void {
    this.editing.set(person.id);
    this.name.set(person.name);
    this.grade.set(person.grade);
    this.error.set(null);
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.error.set(null);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const body = { name: this.name(), grade: this.grade() };
    if (!body.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    const editing = this.editing();
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    try {
      if (editing === null) {
        const added = await firstValueFrom(this.http.post<Person>(url, body, this.session.headers()));
        if (this.session.sheet() === sheet) this.people.update((list) => [...list, added]);
      } else {
        const saved = await firstValueFrom(
          this.http.put<Person>(`${url}/${editing}`, body, this.session.headers()),
        );
        if (this.session.sheet() === sheet) {
          this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        }
      }
      if (this.session.sheet() === sheet) {
        this.reset();
        // Re-read the list so a slower read started before this change cannot overwrite it.
        void this.read(sheet);
      }
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('Another person on the sheet already has that name.');
      } else {
        this.error.set(editing === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    }
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const url = `/api/sheets/${encodeURIComponent(sheet)}`;
    const [list, claims, rates] = await Promise.all([
      firstValueFrom(this.http.get<Person[]>(`${url}/people`, this.session.headers())),
      firstValueFrom(this.http.get<Claim[]>(`${url}/claims`, this.session.headers())),
      firstValueFrom(this.http.get<Rate[]>(`${url}/rates`, this.session.headers())),
    ]).catch(() => [null, null, null]);
    // A later read wins over a slower earlier one.
    if (list && claims && rates && read === this.reads && this.session.sheet() === sheet) {
      this.people.set(list);
      this.claims.set(claims);
      this.rates.set(rates);
    }
  }
}
