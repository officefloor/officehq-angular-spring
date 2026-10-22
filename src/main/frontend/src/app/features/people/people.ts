import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { ClaimView, ClaimsApi, cents, counts, money } from '../../core/claims';
import { GRADES, Grade, PeopleApi, PersonView } from '../../core/people';
import { NO_RATE, RateView, RatesApi, claimDueCents, rateOn, sameSlot } from '../../core/rates';
import { Session } from '../../core/session';
import { Claims } from '../claims/claims';
import { Rates } from '../rates/rates';

// The active sheet's people: listed in the order they were added, added and edited through one form,
// each with their claims, what they have claimed and what is due (the sum of their claims' due
// figures), and the sheet's claimed-total and due-total. The sheet's rates are set here too.
@Component({
  selector: 'app-people',
  imports: [Claims, Rates],
  template: `
    <app-rates [rates]="rates()" [today]="today()" (saved)="rateSaved($event)" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form (submit)="save($event)" [attr.aria-label]="editing() === null ? 'Add a person' : 'Edit a person'">
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
          (change)="grade.set(gradeOf($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
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
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <table>
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
              <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotalOf(p) }}</td>
              <td>
                <app-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsOf(p.id)"
                  [grade]="p.grade"
                  [rates]="rates()"
                  (saved)="claimSaved($event)"
                />
              </td>
              <td>
                <button
                  type="button"
                  [attr.data-testid]="'person-edit-' + p.id"
                  [attr.aria-label]="'Edit ' + p.name"
                  (click)="open(p)"
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
  private readonly session = inject(Session);
  private readonly api = inject(PeopleApi);
  private readonly claimsApi = inject(ClaimsApi);
  private readonly ratesApi = inject(RatesApi);

  protected readonly grades = GRADES;
  protected readonly people = signal<PersonView[]>([]);
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<ClaimView[]>([]);
  private readonly byPerson = computed(() => {
    const map = new Map<number, ClaimView[]>();
    for (const c of this.claims()) map.set(c.personId, [...(map.get(c.personId) ?? []), c]);
    return map;
  });
  /** The sheet's rates, each grade's by from-date; a grade with no rate is not listed. */
  protected readonly rates = signal<RateView[]>([]);
  /** The sheet's today, on which each grade's rate in force is read. */
  protected readonly today = computed(() => this.session.today() ?? '');
  protected readonly claimedTotal = computed(() => money(this.claims().filter(counts).reduce((sum, c) => sum + cents(c.amount), 0)));
  /** Everyone's due-total added up, skipping those shown as n/a. */
  protected readonly dueTotal = computed(() =>
    money(
      this.people().reduce((sum, p) => sum + (this.dueTotalCents(p) ?? 0), 0),
    ),
  );
  protected readonly name = signal('');
  protected readonly grade = signal<Grade>('A');
  /** The id of the person open in the form, or null when the form adds a new person. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // Every sheet load re-reads the sheet's people from the store.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      untracked(() => {
        this.people.set([]);
        this.claims.set([]);
        this.rates.set([]);
        this.reset();
        if (sheet) void this.reload(sheet);
      });
    });
  }

  protected claimsOf(personId: number): ClaimView[] {
    return this.byPerson().get(personId) ?? [];
  }

  /** What a person has claimed: the sum of their OPEN and SETTLED claims' amounts. */
  protected claimed(personId: number): string {
    return money(this.claimedCents(personId));
  }

  private claimedCents(personId: number): number {
    return this.claimsOf(personId).filter(counts).reduce((sum, c) => sum + cents(c.amount), 0);
  }

  /** The rate a grade has in force on the sheet's today (a two-decimal percent), or null when it has none. */
  protected pctOf(grade: Grade): string | null {
    return rateOn(this.rates(), grade, this.today());
  }

  /** A person's due-total: the sum of their counting claims' due figures, or n/a (see dueTotalCents). */
  protected dueTotalOf(person: PersonView): string {
    const total = this.dueTotalCents(person);
    return total === null ? NO_RATE : money(total);
  }

  /**
   * A person's due-total in whole cents: the sum of the due figures of their OPEN and SETTLED claims
   * (0 with none). Null (n/a) when their grade has no rate on the sheet's today, unless a settled
   * claim keeps a fixed figure: then it is the sum of the figures there are.
   */
  private dueTotalCents(person: PersonView): number | null {
    const counting = this.claimsOf(person.id).filter(counts);
    if (this.pctOf(person.grade) === null && !counting.some((c) => c.settledDue !== null)) return null;
    return counting.reduce((sum, c) => sum + (claimDueCents(c, this.rates(), person.grade) ?? 0), 0);
  }

  /** A rate set for a grade, in place of any it had from the same date. */
  protected rateSaved(rate: RateView): void {
    this.rates.update((list) => [...list.filter((r) => !sameSlot(r, rate)), rate]);
  }

  /** A claim added (listed first, as the newest) or saved or moved (in its place). */
  protected claimSaved(claim: ClaimView): void {
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id) ? list.map((c) => (c.id === claim.id ? claim : c)) : [claim, ...list],
    );
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected gradeOf(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  /** Open a person's editor: the form takes their values. */
  protected open(person: PersonView): void {
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

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const form = { name: this.name(), grade: this.grade() };
    if (!form.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    const id = this.editing();
    try {
      if (id === null) {
        const added = await this.api.add(sheet, form);
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await this.api.edit(sheet, id, form);
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on this sheet.');
      } else {
        this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const [people, claims, rates] = await Promise.all([
      this.api.list(sheet),
      this.claimsApi.list(sheet),
      this.ratesApi.list(sheet),
    ]);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) {
      this.claims.set(claims);
      this.rates.set(rates);
      this.people.set(people);
    }
  }
}
