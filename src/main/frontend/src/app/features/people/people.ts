import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Claims } from './claims';
import { Claim, ClaimsService, cents, counts, money } from './claims.service';
import { GRADES, Grade, PeopleService, Person } from './people.service';
import { Rates } from './rates';
import { Rate, RatesService, claimDueCents, rateOn } from './rates.service';

// The people on the active sheet, in the order they were added, and the form that adds a person
// or, once a person's editor is opened, saves changes to them. Each person's row lists their claims
// and shows what they have claimed (their OPEN and SETTLED claims) and what is due on it: the sum of
// those claims' due figures; the sheet's
// claimed-total and due-total add up everyone's (a due-total that is n/a is skipped).
@Component({
  selector: 'app-people',
  imports: [Claims, Rates],
  template: `
    <app-rates [rates]="rates()" (saved)="rateSaved($event)" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>

      <form (submit)="submit($event)" aria-label="Person" data-testid="person-form">
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
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="person-form-error">{{ message }}</p>
        }
      </form>

      @if (people().length === 0) {
        <p data-testid="home-empty">Nothing here yet.</p>
      } @else {
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
                <td data-testid="person-due-total">{{ dueTotalOf(p) ?? 'n/a' }}</td>
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
                    (click)="edit(p)"
                  >
                    Edit
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
        <p>
          Claimed in total: <span data-testid="rollup-claimed-total">{{ claimedTotal() }}</span>
        </p>
        <p>
          Due in total: <span data-testid="rollup-due-total">{{ dueTotal() }}</span>
        </p>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 1rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
    table {
      border-collapse: collapse;
    }
    th,
    td {
      text-align: left;
      padding: 0.25rem 0.75rem 0.25rem 0;
      white-space: pre;
      vertical-align: top;
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class People {
  private readonly session = inject(Session);
  private readonly service = inject(PeopleService);
  private readonly claimsService = inject(ClaimsService);
  private readonly ratesService = inject(RatesService);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  protected readonly claimedTotal = computed(() =>
    money(this.claims().reduce((sum, c) => sum + (counts(c) ? cents(c.amount) : 0), 0)),
  );
  /** The sheet's rates, by grade. */
  protected readonly rates = signal<Rate[]>([]);
  protected readonly dueTotal = computed(() => {
    let sum = 0;
    for (const p of this.people()) sum += this.dueCentsOf(p) ?? 0;
    return money(sum);
  });
  protected readonly name = signal('');
  protected readonly grade = signal<Grade>('A');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  private readonly sheet = computed(() => this.session.sheetId());

  constructor() {
    // Re-read the sheet's people from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.sheet();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.rates.set([]);
      this.reset();
      if (sheet) void this.read(sheet);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected gradeOf(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected claimedOf(personId: number): string {
    return money(this.claimedCentsOf(personId));
  }

  /** What the person's OPEN and SETTLED claims add up to; drafts and voided claims do not count. */
  private claimedCentsOf(personId: number): number {
    return this.claimsOf(personId).reduce((sum, c) => sum + (counts(c) ? cents(c.amount) : 0), 0);
  }

  /** The grade's rate in force on the sheet's today. */
  private rateOf(grade: Grade): string | null {
    const today = this.session.today();
    return today === null ? null : rateOn(this.rates(), grade, today);
  }

  /** The sum of what is due on each of the person's OPEN and SETTLED claims, as each claim shows
   *  it (a claim with nothing due is skipped); n/a when no rate of their grade applies on the
   *  sheet's today and none of their claims has a figure. */
  protected dueTotalOf(person: Person): string | null {
    const due = this.dueCentsOf(person);
    return due === null ? null : money(due);
  }

  private dueCentsOf(person: Person): number | null {
    let sum = 0;
    let any = false;
    for (const c of this.claimsOf(person.id)) {
      if (!counts(c)) continue;
      const due = claimDueCents(c, this.rates(), person.grade);
      if (due === null) continue;
      sum += due;
      any = true;
    }
    return any || this.rateOf(person.grade) !== null ? sum : null;
  }

  /** Put a set rate in place of the grade's old one for the same from-date. */
  protected rateSaved(rate: Rate): void {
    this.rates.update((list) => [
      ...list.filter((r) => r.grade !== rate.grade || r.from !== rate.from),
      rate,
    ]);
  }

  /** Put an added claim first, or a saved one in its place. */
  protected claimSaved(claim: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id)
        ? list.map((c) => (c.id === claim.id ? claim : c))
        : [claim, ...list],
    );
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
    const sheet = this.sheet();
    if (!sheet) return;
    const fields = { name: this.name(), grade: this.grade() };
    if (!fields.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    const id = this.editingId();
    try {
      if (id === null) {
        const added = await this.service.add(sheet, fields);
        if (this.sheet() === sheet) this.people.update((list) => [...list, added]);
      } else {
        const saved = await this.service.update(sheet, id, fields);
        if (this.sheet() === sheet)
          this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      if (this.sheet() === sheet) this.reset();
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('Another person on the sheet already has that name.');
        return;
      }
      this.error.set(id === null ? 'That person could not be added.' : 'That person could not be saved.');
    }
  }

  private async read(sheet: string): Promise<void> {
    try {
      const [list, claims, rates] = await Promise.all([
        this.service.list(sheet),
        this.claimsService.list(sheet),
        this.ratesService.list(sheet),
      ]);
      if (this.sheet() === sheet) {
        this.rates.set(rates);
        this.claims.set(claims);
        this.people.set(list);
      }
    } catch {
      // The sheet's people could not be read; the list stays empty.
    }
  }
}
