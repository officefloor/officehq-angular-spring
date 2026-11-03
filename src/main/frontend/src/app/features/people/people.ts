import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, PersonClaims, cents, money } from '../claims/claims';
import { Rate, Rates, dueCents, dueText } from '../rates/rates';

// The people on the active sheet, in the order they were added, and the one form that both adds
// a person and edits an existing one (opening a person's editor fills the form with their values).
// Each row shows what the person has claimed and lists their claims; the sheet's claimed-total
// adds everyone's up. Each claim and each person also shows what is due at the rate for the
// person's grade, and the sheet's due-total adds up every person's due-total that has a rate.
interface Person {
  id: number;
  name: string;
  grade: string;
}

const GRADES = ['A', 'B', 'C'] as const;

@Component({
  selector: 'app-people',
  imports: [PersonClaims, Rates],
  template: `
    <app-rates [(rates)]="rates" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form aria-label="Person" (submit)="save($event)">
        <label for="person-form-name">Name</label>
        <input
          id="person-form-name"
          data-testid="person-form-name"
          [value]="nameInput()"
          (input)="nameInput.set($any($event.target).value); error.set(null)"
        />
        <label for="person-form-grade">Grade</label>
        <select
          id="person-form-grade"
          data-testid="person-form-grade"
          [value]="gradeInput()"
          (change)="gradeInput.set($any($event.target).value); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === gradeInput()">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <table data-testid="people-list">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            <th scope="col">Claimed</th>
            <th scope="col">Due</th>
            <th scope="col">Claims</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of people(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claimedOf(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueText(dueOf(p)) }}</td>
              <td>
                <app-person-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsOf(p.id)"
                  [pct]="pctOf().get(p.grade)"
                  (saved)="claimSaved($event)"
                />
              </td>
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

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  protected readonly claimedTotal = computed(() => money(this.claims().reduce((sum, c) => sum + cents(c.amount), 0)));
  protected readonly rates = signal<Rate[]>([]);
  protected readonly pctOf = computed(() => new Map(this.rates().map((r) => [r.grade, r.pct])));
  protected readonly dueTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + (this.dueOf(p) ?? 0), 0)),
  );
  protected readonly dueText = dueText;
  protected readonly nameInput = signal('');
  protected readonly gradeInput = signal<string>('A');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // Re-read the sheet's people from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.reset();
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const [list, claims] = await Promise.all([
        firstValueFrom(this.http.post<Person[]>('/api/people/list', { sheet })),
        firstValueFrom(this.http.post<Claim[]>('/api/claims/list', { sheet })),
      ]);
      if (this.session.sheet() === sheet) {
        this.claims.set(claims);
        this.people.set(list);
      }
    } catch {
      this.error.set('Could not read the people on this sheet.');
    }
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected claimedOf(personId: number): string {
    return money(this.claimsOf(personId).reduce((sum, c) => sum + cents(c.amount), 0));
  }

  /** What the person owes on all they claimed, in whole cents, or null when their grade has no rate. */
  protected dueOf(p: Person): number | null {
    return dueCents(this.claimsOf(p.id).reduce((sum, c) => sum + cents(c.amount), 0), this.pctOf().get(p.grade));
  }

  /** A claim added (listed first, newest first) or edited (replaced where it is). */
  protected claimSaved(claim: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id) ? list.map((c) => (c.id === claim.id ? claim : c)) : [claim, ...list],
    );
  }

  protected edit(p: Person): void {
    this.editingId.set(p.id);
    this.nameInput.set(p.name);
    this.gradeInput.set(p.grade);
    this.error.set(null);
  }

  protected cancel(): void {
    this.reset();
  }

  private reset(): void {
    this.editingId.set(null);
    this.nameInput.set('');
    this.gradeInput.set('A');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheet();
    const name = this.nameInput().trim();
    const grade = this.gradeInput();
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!GRADES.includes(grade as (typeof GRADES)[number])) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    const id = this.editingId();
    const body = { sheet, user: this.session.user(), name, grade };
    try {
      if (id === null) {
        const added = await firstValueFrom(this.http.post<Person>('/api/people/add', body));
        if (this.session.sheet() !== sheet) return;
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await firstValueFrom(this.http.post<Person>('/api/people/update', { ...body, id }));
        if (this.session.sheet() !== sheet) return;
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on this sheet.');
      } else {
        this.error.set(id === null ? 'Could not add this person.' : 'Could not save this person.');
      }
    }
  }
}
