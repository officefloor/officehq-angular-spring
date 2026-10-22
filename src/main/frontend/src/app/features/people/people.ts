import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { ClaimView, ClaimsApi, cents, counts, money } from '../../core/claims';
import { GRADES, Grade, PeopleApi, PersonView } from '../../core/people';
import { PeriodView, PeriodsApi, periodOn } from '../../core/periods';
import { QuickAddView } from '../../core/quick-add';
import { NO_RATE, RateView, RatesApi, claimDueCents, rateOn, sameSlot } from '../../core/rates';
import { Session } from '../../core/session';
import { Claims } from '../claims/claims';
import { Periods } from '../periods/periods';
import { QuickAdd } from '../quick-add/quick-add';
import { Rates } from '../rates/rates';

/**
 * Whether a person's claim counts in the live totals: OPEN and SETTLED claims do, but not an OPEN one
 * while the person is on hold.
 */
function countsLive(claim: ClaimView, person: PersonView): boolean {
  return counts(claim) && !(person.held && claim.status === 'OPEN');
}

// The active sheet's people: listed in the order they were added, added and edited through one form,
// each with their claims, what they have claimed and what is due (the sum of their claims' due
// figures), and the sheet's claimed-total and due-total. The sheet's rates are set here too.
@Component({
  selector: 'app-people',
  imports: [Claims, Periods, QuickAdd, Rates],
  template: `
    <app-rates [rates]="rates()" [today]="today()" (saved)="rateSaved($event)" />
    <app-periods
      [periods]="periods()"
      [dueTotals]="periodDueTotals()"
      (saved)="periodSaved($event)"
      (closed)="periodClosed($event)"
    />
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
      <app-quick-add (added)="quickAdded($event)" />
      <p>
        <input
          id="show-retired"
          type="checkbox"
          data-testid="show-retired"
          [checked]="showRetired()"
          (change)="showRetired.set(checked($event))"
        />
        <label for="show-retired">Show retired</label>
      </p>
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <table>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            <th scope="col">Claimed</th>
            <th scope="col">Due</th>
            <th scope="col">Statements</th>
            <th scope="col">Retired</th>
            <th scope="col">On hold</th>
            <th scope="col">Claims</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of shownPeople(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotalOf(p) }}</td>
              <td>
                <span data-testid="person-statement-count">{{ p.statementCount }}</span>
                <span>Last <span data-testid="person-stated-total">{{ statedTotalOf(p) }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ p.statementDate ?? '' }}</span></span>
              </td>
              <td>
                <span data-testid="person-retired">{{ p.retiredFrom === null ? 'no' : 'yes' }}</span>
                <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
              </td>
              <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              <td>
                <app-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsOf(p.id)"
                  [grade]="p.grade"
                  [rates]="rates()"
                  [periods]="periods()"
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
                <button
                  type="button"
                  data-testid="person-statement"
                  [attr.aria-label]="'Take a statement for ' + p.name"
                  (click)="statement(p)"
                >
                  Statement
                </button>
                <label [for]="'person-retire-date-' + p.id">Retire from</label>
                <input
                  type="date"
                  [id]="'person-retire-date-' + p.id"
                  data-testid="person-retire-date"
                  (input)="retireDates.set(p.id, value($event)); personError.set(null)"
                />
                <button
                  type="button"
                  data-testid="person-retire"
                  [attr.aria-label]="'Retire ' + p.name"
                  (click)="retire(p)"
                >
                  Retire
                </button>
                <button
                  type="button"
                  data-testid="person-hold-toggle"
                  [attr.aria-label]="(p.held ? 'Release ' : 'Hold ') + p.name"
                  (click)="toggleHold(p)"
                >
                  {{ p.held ? 'Release' : 'Hold' }}
                </button>
                @if (personError()?.id === p.id) {
                  <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
                }
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
  private readonly periodsApi = inject(PeriodsApi);

  protected readonly grades = GRADES;
  protected readonly people = signal<PersonView[]>([]);
  /** Whether retired people are listed; off when the screen opens, and kept across sheet loads. */
  protected readonly showRetired = signal(false);
  /** The people listed: everyone, or only those not retired unless retired people are shown. */
  protected readonly shownPeople = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => p.retiredFrom === null),
  );
  /** The retire-from date typed on each person's row, by person id; none means the sheet's today. */
  protected readonly retireDates = new Map<number, string>();
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<ClaimView[]>([]);
  private readonly byPerson = computed(() => {
    const map = new Map<number, ClaimView[]>();
    for (const c of this.claims()) map.set(c.personId, [...(map.get(c.personId) ?? []), c]);
    return map;
  });
  /** The sheet's rates, each grade's by from-date; a grade with no rate is not listed. */
  protected readonly rates = signal<RateView[]>([]);
  /** The sheet's periods by from-date. */
  protected readonly periods = signal<PeriodView[]>([]);
  /**
   * Each period's due-total by period id: the sum of the due figures of the OPEN and SETTLED claims
   * dated in it, each at its own person's grade (a claim with no figure adds nothing), leaving out the
   * OPEN claims of anyone on hold.
   */
  protected readonly periodDueTotals = computed(() => {
    const totals = new Map<number, number>(this.periods().map((p) => [p.id, 0]));
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const c of this.claims()) {
      const period = periodOn(this.periods(), c.date);
      const person = byId.get(c.personId);
      if (!period || !person || !countsLive(c, person)) continue;
      totals.set(period.id, totals.get(period.id)! + (claimDueCents(c, this.rates(), person.grade) ?? 0));
    }
    return new Map([...totals].map(([id, total]) => [id, money(total)]));
  });
  /** The sheet's today, on which each grade's rate in force is read. */
  protected readonly today = computed(() => this.session.today() ?? '');
  protected readonly claimedTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + this.claimedCents(p.id), 0)),
  );
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
  /** The version of the person open in the form, as it was loaded. */
  private readonly version = signal(0);
  protected readonly error = signal<string | null>(null);
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  constructor() {
    // Every sheet load re-reads the sheet's people from the store.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      untracked(() => {
        this.people.set([]);
        this.claims.set([]);
        this.rates.set([]);
        this.periods.set([]);
        this.reset();
        this.personError.set(null);
        this.retireDates.clear();
        if (sheet) void this.reload(sheet);
      });
    });
  }

  protected claimsOf(personId: number): ClaimView[] {
    return this.byPerson().get(personId) ?? [];
  }

  /** A person's claims that count in the live totals (see countsLive). */
  private countingOf(personId: number): ClaimView[] {
    const person = this.people().find((p) => p.id === personId);
    return person ? this.claimsOf(personId).filter((c) => countsLive(c, person)) : [];
  }

  /** What a person has claimed: the sum of their OPEN and SETTLED claims' amounts (see countsLive). */
  protected claimed(personId: number): string {
    return money(this.claimedCents(personId));
  }

  private claimedCents(personId: number): number {
    return this.countingOf(personId).reduce((sum, c) => sum + cents(c.amount), 0);
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
   * (0 with none; see countsLive). Null (n/a) when their grade has no rate on the sheet's today, unless a settled
   * or stated claim keeps a fixed figure: then it is the sum of the figures there are.
   */
  private dueTotalCents(person: PersonView): number | null {
    const counting = this.countingOf(person.id);
    const fixed = (c: ClaimView) => c.settledDue !== null || (c.stated && c.statedDue !== null);
    if (this.pctOf(person.grade) === null && !counting.some(fixed)) return null;
    return counting.reduce((sum, c) => sum + (claimDueCents(c, this.rates(), person.grade) ?? 0), 0);
  }

  /** The last statement's due-total: n/a when it was, empty when none was taken. */
  protected statedTotalOf(person: PersonView): string {
    if (person.statementCount === 0) return '';
    return person.statementTotal ?? NO_RATE;
  }

  /** Take a statement for a person: the row and their claims show what the store holds after it. */
  protected async statement(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      const stated = await this.api.statement(sheet, person.id);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === stated.person.id ? stated.person : p)));
      const byId = new Map(stated.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
    } catch {
      this.personError.set({ id: person.id, message: 'The statement could not be taken.' });
    }
  }

  /** Retire a person from the date typed on their row, or from the sheet's today with none. */
  protected async retire(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const from = this.retireDates.get(person.id)?.trim() || null;
    try {
      const retired = await this.api.retire(sheet, person.id, from);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === retired.id ? retired : p)));
    } catch {
      this.personError.set({ id: person.id, message: 'The person could not be retired.' });
    }
  }

  /** Put a person on hold, or take them off it. */
  protected async toggleHold(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      const saved = await this.api.hold(sheet, person.id, !person.held);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
    } catch {
      this.personError.set({
        id: person.id,
        message: person.held ? 'The person could not be released.' : 'The person could not be put on hold.',
      });
    }
  }

  /** A rate set for a grade, in place of any it had from the same date. */
  protected rateSaved(rate: RateView): void {
    this.rates.update((list) => [...list.filter((r) => !sameSlot(r, rate)), rate]);
  }

  /** A period added, kept in from-date order. */
  protected periodSaved(period: PeriodView): void {
    this.periods.update((list) => [...list, period].sort((a, b) => a.from.localeCompare(b.from)));
  }

  /** A period closed, in its place. */
  protected periodClosed(period: PeriodView): void {
    this.periods.update((list) => list.map((p) => (p.id === period.id ? period : p)));
  }

  /** A claim added (listed first, as the newest) or saved or moved (in its place). */
  protected claimSaved(claim: ClaimView): void {
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id) ? list.map((c) => (c.id === claim.id ? claim : c)) : [claim, ...list],
    );
  }

  /** A claim quick-added: its person listed when they were added for it, the claim first as the newest. */
  protected quickAdded(added: QuickAddView): void {
    if (added.personAdded) this.people.update((list) => [...list, added.person]);
    this.claimSaved(added.claim);
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected gradeOf(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  /** Open a person's editor: the form takes their values. */
  protected open(person: PersonView): void {
    this.personError.set(null);
    this.editing.set(person.id);
    this.version.set(person.version);
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
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const form = { name: this.name(), grade: this.grade() };
    if (!form.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    const id = this.editing();
    this.busy = true;
    try {
      if (id === null) {
        const added = await this.api.add(sheet, form);
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await this.api.edit(sheet, id, { ...form, version: this.version() });
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(sheet, id);
        this.error.set('Someone else has saved this person since you opened it. The form shows their values now.');
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on this sheet.');
      } else {
        this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    } finally {
      this.busy = false;
    }
  }

  /** Someone else saved the person open in the form: show what the store holds for them now. */
  private async showCurrent(sheet: string, id: number): Promise<void> {
    try {
      const current = (await this.api.list(sheet)).find((p) => p.id === id);
      if (!current || this.session.sheet() !== sheet || this.editing() !== id) return;
      this.people.update((list) => list.map((p) => (p.id === id ? current : p)));
      this.version.set(current.version);
      this.name.set(current.name);
      this.grade.set(current.grade);
    } catch {
      // the form keeps what was typed; the refusal is still shown
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const [people, claims, rates, periods] = await Promise.all([
      this.api.list(sheet),
      this.claimsApi.list(sheet),
      this.ratesApi.list(sheet),
      this.periodsApi.list(sheet),
    ]);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) {
      this.claims.set(claims);
      this.rates.set(rates);
      this.periods.set(periods);
      this.people.set(people);
    }
  }
}
