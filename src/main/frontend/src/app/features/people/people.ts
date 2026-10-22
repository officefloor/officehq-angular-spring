import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { ClaimView, ClaimsApi, cents, counts, money, overdue, roundsHalfUp } from '../../core/claims';
import { GRADES, Grade, PeopleApi, PersonView, gradeOn } from '../../core/people';
import { PeriodView, PeriodsApi, periodOn } from '../../core/periods';
import { QuickAddView } from '../../core/quick-add';
import { NO_RATE, RateView, RatesApi, claimDueCents, rateOn, sameSlot } from '../../core/rates';
import { Session } from '../../core/session';
import { Claims } from '../claims/claims';
import { Periods } from '../periods/periods';
import { QuickAdd } from '../quick-add/quick-add';
import { Rates } from '../rates/rates';
import { GradeSummary, PeriodSummary, StatusSummary, Summary } from '../summary/summary';

/** The statuses the summary counts claims by, in the order it lists them. */
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

/**
 * Whether a person's claim counts in the live totals: OPEN and SETTLED claims do, but not an OPEN one
 * while the person is on hold.
 */
function countsLive(claim: ClaimView, person: PersonView): boolean {
  return counts(claim) && !(person.held && claim.status === 'OPEN');
}

/** A CSV field: wrapped in double quotes, any inside doubled, when it holds a comma, a quote or a line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

// The active sheet's people: listed in the order they were added, added and edited through one form,
// each with their claims, what they have claimed and what is due (the sum of their claims' due
// figures), and the sheet's claimed-total and due-total. The sheet's rates are set here too.
@Component({
  selector: 'app-people',
  imports: [Claims, Periods, QuickAdd, Rates, Summary],
  template: `
    <app-rates [rates]="rates()" [today]="today()" (saved)="rateSaved($event)" />
    <app-periods
      [periods]="periods()"
      [dueTotals]="periodDueTotals()"
      (saved)="periodSaved($event)"
      (closed)="periodChanged($event)"
      (reopened)="periodChanged($event)"
    />
    <app-summary [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()" />
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
          (change)="grade.set(gradeChosen($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="person-form-grade-from">Grade from</label>
        <input
          type="date"
          id="person-form-grade-from"
          data-testid="person-form-grade-from"
          [value]="gradeFrom()"
          (input)="gradeFrom.set(value($event)); error.set(null)"
        />
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
      <p>
        <button type="button" data-testid="export-people" (click)="exportCsv()">Export people as CSV</button>
      </p>
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong> claims,
        due <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>
      <table>
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            <th scope="col">Claimed</th>
            <th scope="col">Due</th>
            <th scope="col">Overdue</th>
            <th scope="col">Statements</th>
            <th scope="col">Retired</th>
            <th scope="col">On hold</th>
            <th scope="col">Patrons</th>
            <th scope="col">Backing</th>
            <th scope="col">Claims</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of shownPeople(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotalOf(p) }}</td>
              <td>
                <span data-testid="person-overdue-count">{{ overdueOf(p.id).length }}</span>
                <span>due <span data-testid="person-overdue-total">{{ overdueTotalOf(p) }}</span></span>
              </td>
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
                <span data-testid="person-patrons">{{ patronsOf(p) }}</span>
                <label [for]="'person-patron-select-' + p.id">Add patron</label>
                <select
                  [id]="'person-patron-select-' + p.id"
                  data-testid="person-patron-select"
                  (change)="patronChoices.set(p.id, value($event)); patronError.set(null)"
                >
                  <option value="">Choose a person</option>
                  @for (other of people(); track other.id) {
                    <option [value]="other.id">{{ other.name }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="person-patron-add"
                  [attr.aria-label]="'Add a patron for ' + p.name"
                  (click)="addPatron(p)"
                >
                  Add
                </button>
                @if (patronError()?.id === p.id) {
                  <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                }
              </td>
              <td data-testid="person-backing">{{ backingOf(p) }}</td>
              <td>
                <app-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsOf(p.id)"
                  [grades]="p.grades"
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
      totals.set(period.id, totals.get(period.id)! + (this.dueCents(c, person) ?? 0));
    }
    return new Map([...totals].map(([id, total]) => [id, money(total)]));
  });
  /**
   * Per grade: the people not retired who have it in force on the sheet's today, and their due-totals
   * added up, skipping those shown as n/a.
   */
  protected readonly gradeSummary = computed<GradeSummary[]>(() => {
    const current = this.people().filter((p) => p.retiredFrom === null);
    return GRADES.map((grade) => {
      const on = current.filter((p) => this.gradeOf(p) === grade);
      return {
        grade,
        count: on.length,
        dueTotal: money(on.reduce((sum, p) => sum + (this.dueTotalCents(p) ?? 0), 0)),
      };
    });
  });
  /** Per period: its due-total as its row shows it (the one fixed at closing, once closed). */
  protected readonly periodSummary = computed<PeriodSummary[]>(() => {
    const totals = this.periodDueTotals();
    return this.periods().map((p) => ({ name: p.name, dueTotal: p.closedTotal ?? totals.get(p.id) ?? '0.00' }));
  });
  /** Per status: how many of the sheet's claims have it. */
  protected readonly statusSummary = computed<StatusSummary[]>(() =>
    SUMMARY_STATUSES.map((status) => ({
      status,
      count: this.claims().filter((c) => c.status === status).length,
    })),
  );
  /** The sheet's floor in whole cents: a claim's live due is at least it. */
  private readonly floorCents = computed(() => cents(this.session.floor()));
  /** The sheet's today, on which each grade's rate in force is read. */
  protected readonly today = computed(() => this.session.today() ?? '');
  /** What late charges are worked from: the sheet's late percent, terms and today. */
  private readonly late = computed(() => ({ pct: this.session.latePct(), terms: this.session.terms(), today: this.today() }));
  protected readonly claimedTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + this.claimedCents(p.id), 0)),
  );
  /** Everyone's due-total added up, skipping those shown as n/a. */
  protected readonly dueTotal = computed(() =>
    money(
      this.people().reduce((sum, p) => sum + (this.dueTotalCents(p) ?? 0), 0),
    ),
  );
  /** How many overdue claims count in the live totals, across everyone. */
  protected readonly overdueCount = computed(() =>
    this.people().reduce((sum, p) => sum + this.overdueOf(p.id).length, 0),
  );
  /** The due of everyone's counting overdue claims added up. */
  protected readonly overdueTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + this.overdueCents(p), 0)),
  );
  protected readonly name = signal('');
  protected readonly grade = signal<Grade>('A');
  /** The date the grade entered is in force from; the sheet's today unless another is typed. */
  protected readonly gradeFrom = signal('');
  /** The id of the person open in the form, or null when the form adds a new person. */
  protected readonly editing = signal<number | null>(null);
  /** The version of the person open in the form, as it was loaded. */
  private readonly version = signal(0);
  protected readonly error = signal<string | null>(null);
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The person chosen in each row's patron list, by person id. */
  protected readonly patronChoices = new Map<number, string>();
  /** The refusal of the last patron added on a person's row, shown on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
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
        this.patronError.set(null);
        this.patronChoices.clear();
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

  /** A person's overdue claims that count in the live totals (see countsLive). */
  protected overdueOf(personId: number): ClaimView[] {
    return this.countingOf(personId).filter((c) => overdue(c, this.session.terms(), this.today()));
  }

  /** The due of a person's counting overdue claims added up (a claim with no figure adds nothing). */
  protected overdueTotalOf(person: PersonView): string {
    return money(this.overdueCents(person));
  }

  private overdueCents(person: PersonView): number {
    return this.overdueOf(person.id).reduce(
      (sum, c) => sum + (this.dueCents(c, person) ?? 0),
      0,
    );
  }

  /** The grade a person has in force on the sheet's today. */
  protected gradeOf(person: PersonView): Grade {
    return gradeOn(person.grades, this.today());
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
   * (0 with none; see countsLive). Null (n/a) when the grade they have in force on the sheet's today has no
   * rate, unless a claim has a figure (a fixed one, or one at the grade in force on its own date): then it
   * is the sum of the figures there are.
   */
  private dueTotalCents(person: PersonView): number | null {
    const dues = this.countingOf(person.id).map((c) => this.dueCents(c, person));
    if (this.pctOf(this.gradeOf(person)) === null && dues.every((d) => d === null)) return null;
    return dues.reduce<number>((sum, d) => sum + (d ?? 0), 0);
  }

  /**
   * A claim's due in whole cents at the grade its person has in force on its date, with its late charge
   * (see claimDueCents).
   */
  private dueCents(claim: ClaimView, person: PersonView): number | null {
    const halfUp = roundsHalfUp(claim, this.claimsOf(person.id), this.session.policyDate(), this.today());
    return claimDueCents(claim, this.rates(), gradeOn(person.grades, claim.date), this.floorCents(), this.late(), halfUp);
  }

  /** A person's patrons' names, sorted, comma and space separated. */
  protected patronsOf(person: PersonView): string {
    return this.patrons(person)
      .map((p) => p.name)
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
      .join(', ');
  }

  /** A person's backing: their patrons' due-totals added up, skipping those shown as n/a. */
  protected backingOf(person: PersonView): string {
    return money(this.patrons(person).reduce((sum, p) => sum + (this.dueTotalCents(p) ?? 0), 0));
  }

  private patrons(person: PersonView): PersonView[] {
    return this.people().filter((p) => person.patronIds.includes(p.id));
  }

  /** Name the person chosen on a person's row as their patron. */
  protected async addPatron(person: PersonView): Promise<void> {
    this.patronError.set(null);
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const patronId = Number(this.patronChoices.get(person.id) ?? '');
    const refuse = (message: string) => this.patronError.set({ id: person.id, message });
    if (!patronId) {
      refuse('Choose a person to add as a patron.');
      return;
    }
    if (patronId === person.id) {
      refuse('A person cannot be their own patron.');
      return;
    }
    if (person.patronIds.includes(patronId)) {
      refuse('That person is already a patron.');
      return;
    }
    try {
      const saved = await this.api.addPatron(sheet, person.id, patronId);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
    } catch {
      refuse('The patron could not be added.');
    }
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

  /**
   * Download the people as a CSV file: a header line, then one line per person in list order, retired
   * people included, with the grade in force on the sheet's today, the retired-from date or nothing, and
   * the due-total as shown. Each line ends with a line feed.
   */
  protected exportCsv(): void {
    const lines = [
      ['name', 'grade', 'retired-from', 'due-total'],
      ...this.people().map((p) => [p.name, this.gradeOf(p), p.retiredFrom ?? '', this.dueTotalOf(p)]),
    ];
    const text = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `people-${this.session.sheet() ?? 'sheet'}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  /** A rate set for a grade, in place of any it had from the same date. */
  protected rateSaved(rate: RateView): void {
    this.rates.update((list) => [...list.filter((r) => !sameSlot(r, rate)), rate]);
  }

  /** A period added, kept in from-date order. */
  protected periodSaved(period: PeriodView): void {
    this.periods.update((list) => [...list, period].sort((a, b) => a.from.localeCompare(b.from)));
  }

  /** A period closed or reopened, in its place. */
  protected periodChanged(period: PeriodView): void {
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

  protected gradeChosen(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  /** Open a person's editor: the form takes their values. */
  protected open(person: PersonView): void {
    this.personError.set(null);
    this.editing.set(person.id);
    this.version.set(person.version);
    this.name.set(person.name);
    this.grade.set(this.gradeOf(person));
    this.gradeFrom.set(this.today());
    this.error.set(null);
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set(this.today());
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
        const gradeFrom = this.gradeFrom().trim() || null;
        const saved = await this.api.edit(sheet, id, { ...form, gradeFrom, version: this.version() });
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
      this.grade.set(this.gradeOf(current));
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
