import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Claims } from './claims';
import { Claim, ClaimsService, cents, counts, money, overdue } from './claims.service';
import { GRADES, Grade, Patron, PeopleService, Person } from './people.service';
import { Periods } from './periods';
import { Period, PeriodsService, periodOn } from './periods.service';
import { Rates } from './rates';
import { Lateness, Rate, RatesService, claimShownCents, rateOn } from './rates.service';
import { Settings } from './settings';
import { FLOOR_CENTS, LATE_PCT, SettingsService, TERMS_DAYS } from './settings.service';
import { Statement, StatementsService } from './statements.service';

// The people on the active sheet, in the order they were added, and the form that adds a person
// or, once a person's editor is opened, saves changes to them. Each person's row lists their claims
// and shows what they have claimed (their OPEN and SETTLED claims) and what is due on it: the sum of
// those claims' due figures; the sheet's
// claimed-total and due-total add up everyone's (a due-total that is n/a is skipped). A statement
// taken of a person binds each of their counting claims to the figure it shows then; the row shows
// how many statements they have had and the last one's due-total and date. A person can be retired
// from a date; retired people are left out of the list unless "show retired" is on, but their claims
// keep counting in every total. A person can be put on hold and taken off it again: while held,
// their OPEN claims count in no live total (theirs, the sheet's or a period's); SETTLED ones still do.
// A claim is due by its date plus the sheet's terms; an OPEN one due by a date before the sheet's
// today is overdue. Each row, and the sheet, shows how many of the claims that count are overdue and
// what is due on them. An overdue claim's due gains the sheet's late percent of its figure for every
// full 30 days it is overdue; a statement never binds that charge, settling fixes it in.
// A person can name other people on the sheet as their patrons, one at a time from their row; the
// row shows their patrons' names, sorted, and their backing: their patrons' due-totals added up
// (a due-total that is n/a is skipped).
@Component({
  selector: 'app-people',
  imports: [Claims, Periods, Rates, Settings],
  template: `
    <app-settings
      (floorSaved)="floor.set($event)"
      (termsSaved)="terms.set($event)"
      (latePctSaved)="latePct.set($event)"
    />
    <app-rates [rates]="rates()" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotals]="periodDueTotals()" (added)="periodAdded($event)" (closed)="periodClosed($event)" />
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

      <form (submit)="quickAdd($event)" aria-label="Quick add a claim" data-testid="quick-form">
        <label for="quick-name">Name</label>
        <input
          id="quick-name"
          data-testid="quick-name"
          [value]="quickName()"
          (input)="quickName.set(value($event)); quickError.set(null)"
        />
        <label for="quick-amount">Amount</label>
        <input
          id="quick-amount"
          data-testid="quick-amount"
          inputmode="decimal"
          [value]="quickAmount()"
          (input)="quickAmount.set(value($event)); quickError.set(null)"
        />
        <button type="submit" data-testid="quick-submit">Quick add claim</button>
        @if (quickError(); as message) {
          <p role="alert" class="error" data-testid="quick-error">{{ message }}</p>
        }
      </form>

      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="showRetired()"
          (change)="showRetired.set(checked($event))"
        />
        Show retired
      </label>

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
              <th scope="col">Overdue</th>
              <th scope="col">Overdue due</th>
              <th scope="col">Statements</th>
              <th scope="col">Last stated</th>
              <th scope="col">Stated on</th>
              <th scope="col">Retired</th>
              <th scope="col">Retired from</th>
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
                <td data-testid="person-grade">{{ p.grade }}</td>
                <td data-testid="person-claimed">{{ claimedOf(p) }}</td>
                <td data-testid="person-due-total">{{ dueTotalOf(p) ?? 'n/a' }}</td>
                <td data-testid="person-overdue-count">{{ overdueOf(p).count }}</td>
                <td data-testid="person-overdue-total">{{ money(overdueOf(p).cents) }}</td>
                <td data-testid="person-statement-count">{{ statementsOf(p.id).length }}</td>
                <td data-testid="person-stated-total">{{ lastStatementOf(p.id)?.dueTotal ?? '' }}</td>
                <td data-testid="person-statement-date">{{ lastStatementOf(p.id)?.date ?? '' }}</td>
                <td data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</td>
                <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
                <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
                <td data-testid="person-patrons">{{ patronNamesOf(p.id) }}</td>
                <td data-testid="person-backing">{{ backingOf(p.id) }}</td>
                <td>
                  <app-claims
                    [personId]="p.id"
                    [personName]="p.name"
                    [claims]="claimsOf(p.id)"
                    [grade]="p.grade"
                    [rates]="rates()"
                    [floor]="floor()"
                    [periods]="periods()"
                    [terms]="terms()"
                    [latePct]="latePct()"
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
                  <button
                    type="button"
                    data-testid="person-statement"
                    [attr.aria-label]="'Take a statement of ' + p.name"
                    (click)="takeStatement(p)"
                  >
                    Statement
                  </button>
                  <label [for]="'person-retire-date-' + p.id">Retire from</label>
                  <input
                    [id]="'person-retire-date-' + p.id"
                    data-testid="person-retire-date"
                    [value]="retireDates()[p.id] ?? ''"
                    (input)="setRetireDate(p.id, value($event))"
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
                  @if (rowErrors()[p.id]; as message) {
                    <p role="alert" class="error" data-testid="person-error">{{ message }}</p>
                  }
                  <label [for]="'person-patron-select-' + p.id">Patron</label>
                  <select
                    [id]="'person-patron-select-' + p.id"
                    data-testid="person-patron-select"
                    [value]="patronChoices()[p.id] ?? ''"
                    (change)="setPatronChoice(p.id, value($event))"
                  >
                    <option value="" [selected]="!patronChoices()[p.id]">Choose a patron</option>
                    @for (o of people(); track o.id) {
                      @if (o.id !== p.id) {
                        <option [value]="o.id" [selected]="patronChoices()[p.id] === '' + o.id">{{ o.name }}</option>
                      }
                    }
                  </select>
                  <button
                    type="button"
                    data-testid="person-patron-add"
                    [attr.aria-label]="'Add a patron of ' + p.name"
                    (click)="addPatron(p)"
                  >
                    Add patron
                  </button>
                  @if (patronErrors()[p.id]; as message) {
                    <p role="alert" class="error" data-testid="person-patron-error">{{ message }}</p>
                  }
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
        <p>
          Overdue: <span data-testid="rollup-overdue-count">{{ overdueTotal().count }}</span>,
          due on them <span data-testid="rollup-overdue-total">{{ money(overdueTotal().cents) }}</span>
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
  private readonly settingsService = inject(SettingsService);
  private readonly statementsService = inject(StatementsService);
  private readonly periodsService = inject(PeriodsService);

  protected readonly grades = GRADES;
  protected readonly money = money;
  protected readonly people = signal<Person[]>([]);
  /** Whether retired people are listed; off when the screen is first opened, kept across sheet loads. */
  protected readonly showRetired = signal(false);
  /** The people listed: everyone, or only those not retired unless "show retired" is on. */
  protected readonly shownPeople = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The retire-from date typed on each person's row, by person id. */
  protected readonly retireDates = signal<Record<number, string>>({});
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  protected readonly claimedTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + this.claimedCentsOf(p), 0)),
  );
  /** The sheet's statements, in the order they were taken. */
  protected readonly statements = signal<Statement[]>([]);
  private readonly statementsByPerson = computed(() => {
    const by = new Map<number, Statement[]>();
    for (const s of this.statements()) by.set(s.personId, [...(by.get(s.personId) ?? []), s]);
    return by;
  });
  /** The sheet's patrons, in the order they were named. */
  protected readonly patrons = signal<Patron[]>([]);
  /** The patron chosen on each person's row (a person id), by person id. */
  protected readonly patronChoices = signal<Record<number, string>>({});
  /** The error of the last refused patron added on each person's row, by person id. */
  protected readonly patronErrors = signal<Record<number, string>>({});
  /** Each person's patrons' names, sorted and comma separated, by person id. */
  private readonly patronNames = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p.name]));
    const by = new Map<number, string[]>();
    for (const pt of this.patrons()) {
      const name = byId.get(pt.patronId);
      if (name !== undefined) by.set(pt.personId, [...(by.get(pt.personId) ?? []), name]);
    }
    return new Map([...by].map(([id, names]) => [id, names.sort().join(', ')]));
  });
  /** Each person's backing, by person id: their patrons' due-totals added up (n/a ones skipped). */
  private readonly backings = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const by = new Map<number, number>();
    for (const pt of this.patrons()) {
      const patron = byId.get(pt.patronId);
      const due = patron === undefined ? null : this.dueCentsOf(patron);
      by.set(pt.personId, (by.get(pt.personId) ?? 0) + (due ?? 0));
    }
    return by;
  });
  /** The error of the last refused action on each person's row, by person id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** The sheet's rates, by grade. */
  protected readonly rates = signal<Rate[]>([]);
  /** The sheet's floor, in cents: the least a claim's live due can be. */
  protected readonly floor = signal(FLOOR_CENTS);
  /** The sheet's terms, in days: how long after its date a claim is due by. */
  protected readonly terms = signal(TERMS_DAYS);
  /** The sheet's late percent, with two decimals. */
  protected readonly latePct = signal(LATE_PCT);
  /** What the claims' late charges are worked from. */
  private readonly lateness = computed<Lateness>(() => ({
    terms: this.terms(),
    today: this.session.today(),
    latePct: this.latePct(),
  }));
  /** The sheet's periods. */
  protected readonly periods = signal<Period[]>([]);
  /** Each period's due-total, by name: what is due on the OPEN and SETTLED claims dated in it, as
   *  each claim shows it (a claim with nothing due is skipped). */
  protected readonly periodDueTotals = computed(() => {
    const sums: Record<string, number> = {};
    for (const p of this.periods()) sums[p.name] = 0;
    for (const person of this.people()) {
      for (const c of this.claimsOf(person.id)) {
        if (!this.countsLive(c, person)) continue;
        const period = periodOn(this.periods(), c.date);
        if (period === null) continue;
        sums[period.name] += claimShownCents(c, this.rates(), person.grade, this.floor(), this.lateness()) ?? 0;
      }
    }
    return Object.fromEntries(Object.entries(sums).map(([name, sum]) => [name, money(sum)]));
  });
  protected readonly dueTotal = computed(() => {
    let sum = 0;
    for (const p of this.people()) sum += this.dueCentsOf(p) ?? 0;
    return money(sum);
  });
  /** How many of everyone's counting claims are overdue, and what is due on them, in cents. */
  protected readonly overdueTotal = computed(() =>
    this.people().reduce(
      (sum, p) => {
        const o = this.overdueOf(p);
        return { count: sum.count + o.count, cents: sum.cents + o.cents };
      },
      { count: 0, cents: 0 },
    ),
  );
  protected readonly name = signal('');
  protected readonly grade = signal<Grade>('A');
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion = 0;
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal<string | null>(null);
  /** A quick-add is waiting for its answer; another meanwhile is ignored. */
  private quickAdding = false;
  private readonly sheet = computed(() => this.session.sheetId());

  constructor() {
    // Re-read the sheet's people from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.sheet();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.rates.set([]);
      this.floor.set(FLOOR_CENTS);
      this.terms.set(TERMS_DAYS);
      this.latePct.set(LATE_PCT);
      this.statements.set([]);
      this.periods.set([]);
      this.rowErrors.set({});
      this.retireDates.set({});
      this.patrons.set([]);
      this.patronChoices.set({});
      this.patronErrors.set({});
      this.reset();
      this.quickName.set('');
      this.quickAmount.set('');
      this.quickError.set(null);
      if (sheet) void this.read(sheet);
    });
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

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected statementsOf(personId: number): Statement[] {
    return this.statementsByPerson().get(personId) ?? [];
  }

  protected lastStatementOf(personId: number): Statement | null {
    return this.statementsOf(personId).at(-1) ?? null;
  }

  /** Take a statement of the person, and show their claims as it bound or unbound them. */
  protected async takeStatement(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    try {
      const taken = await this.statementsService.take(sheet, person.id);
      if (this.sheet() !== sheet) return;
      for (const c of taken.claims) this.claimSaved(c);
      this.statements.update((list) => [...list, taken.statement]);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'A statement could not be taken.' }));
    }
  }

  protected setRetireDate(id: number, date: string): void {
    this.retireDates.update((dates) => ({ ...dates, [id]: date }));
    this.clearRowError(id);
  }

  /** Retire the person from the date typed on their row (the sheet's today when blank). */
  protected async retire(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    const from = (this.retireDates()[person.id] ?? '').trim();
    if (from !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'Type a date as YYYY-MM-DD.' }));
      return;
    }
    try {
      const retired = await this.service.retire(sheet, person.id, from || null);
      if (this.sheet() !== sheet) return;
      this.put(retired);
      this.retireDates.update(({ [person.id]: _, ...rest }) => rest);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'That person could not be retired.' }));
    }
  }

  private clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
    this.patronErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected patronNamesOf(personId: number): string {
    return this.patronNames().get(personId) ?? '';
  }

  protected backingOf(personId: number): string {
    return money(this.backings().get(personId) ?? 0);
  }

  protected setPatronChoice(id: number, patronId: string): void {
    this.patronChoices.update((choices) => ({ ...choices, [id]: patronId }));
    this.clearRowError(id);
  }

  /** Name the person chosen on the row as the person's patron. */
  protected async addPatron(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    const choice = this.patronChoices()[person.id] ?? '';
    if (choice === '') {
      this.patronErrors.update((errors) => ({ ...errors, [person.id]: 'Choose a patron.' }));
      return;
    }
    try {
      const added = await this.service.addPatron(sheet, person.id, Number(choice));
      if (this.sheet() !== sheet) return;
      this.patrons.update((list) => [...list, added]);
      this.patronChoices.update(({ [person.id]: _, ...rest }) => rest);
    } catch {
      if (this.sheet() !== sheet) return;
      this.patronErrors.update((errors) => ({
        ...errors,
        [person.id]: 'That person cannot be added as a patron.',
      }));
    }
  }

  /** Put the person on hold, or take them off it when held. */
  protected async toggleHold(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    try {
      const saved = await this.service.hold(sheet, person.id, !person.held);
      if (this.sheet() !== sheet) return;
      this.put(saved);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [person.id]: person.held ? 'That person could not be released.' : 'That person could not be held.',
      }));
    }
  }

  /** Whether the claim counts in the live totals: OPEN or SETTLED, but not OPEN while its person is
   *  on hold. */
  private countsLive(claim: Claim, person: Person): boolean {
    return counts(claim) && !(person.held && claim.status === 'OPEN');
  }

  protected claimedOf(person: Person): string {
    return money(this.claimedCentsOf(person));
  }

  /** What the person's OPEN and SETTLED claims add up to (OPEN ones left out while they are on
   *  hold); drafts and voided claims do not count. */
  private claimedCentsOf(person: Person): number {
    return this.claimsOf(person.id).reduce((sum, c) => sum + (this.countsLive(c, person) ? cents(c.amount) : 0), 0);
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
      if (!this.countsLive(c, person)) continue;
      const due = claimShownCents(c, this.rates(), person.grade, this.floor(), this.lateness());
      if (due === null) continue;
      sum += due;
      any = true;
    }
    return any || this.rateOf(person.grade) !== null ? sum : null;
  }

  /** How many of the person's counting claims are overdue, and the sum of what is due on them, in
   *  cents, as each claim shows it (a claim with nothing due adds nothing). */
  protected overdueOf(person: Person): { count: number; cents: number } {
    let count = 0;
    let sum = 0;
    const today = this.session.today();
    for (const c of this.claimsOf(person.id)) {
      if (!this.countsLive(c, person) || !overdue(c, this.terms(), today)) continue;
      count++;
      sum += claimShownCents(c, this.rates(), person.grade, this.floor(), this.lateness()) ?? 0;
    }
    return { count, cents: sum };
  }

  protected periodAdded(period: Period): void {
    this.periods.update((list) => [...list, period]);
  }

  protected periodClosed(period: Period): void {
    this.periods.update((list) => list.map((p) => (p.id === period.id ? period : p)));
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
    this.clearRowError(person.id);
    this.editingId.set(person.id);
    this.editingVersion = person.version;
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
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.sheet();
    if (!sheet) return;
    const fields = { name: this.name(), grade: this.grade() };
    if (!fields.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    const id = this.editingId();
    this.submitting = true;
    try {
      if (id === null) {
        const added = await this.service.add(sheet, fields);
        if (this.sheet() === sheet) this.people.update((list) => [...list, added]);
      } else {
        const saved = await this.service.update(sheet, id, this.editingVersion, fields);
        if (this.sheet() === sheet) this.put(saved);
      }
      if (this.sheet() === sheet) this.reset();
    } catch (e) {
      if (this.sheet() !== sheet) return;
      const current = this.staleOf(e);
      if (current !== null) {
        // Someone else saved the person since the editor loaded them: show them as now stored.
        this.put(current);
        if (this.editingId() === current.id) {
          this.editingVersion = current.version;
          this.name.set(current.name);
          this.grade.set(current.grade);
        }
        this.error.set('Someone else saved this person since you opened it; their changes are shown.');
        return;
      }
      if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('Another person on the sheet already has that name.');
        return;
      }
      this.error.set(id === null ? 'That person could not be added.' : 'That person could not be saved.');
    } finally {
      this.submitting = false;
    }
  }

  /** Add a DRAFT claim for the named person, adding them (grade A) first when the name is new. */
  protected async quickAdd(event: Event): Promise<void> {
    event.preventDefault();
    if (this.quickAdding) return;
    this.quickError.set(null);
    const sheet = this.sheet();
    if (!sheet) return;
    const name = this.quickName();
    if (!name.trim()) {
      this.quickError.set('Type a name for the claim.');
      return;
    }
    this.quickAdding = true;
    try {
      const added = await this.service.quickAdd(sheet, name, this.quickAmount());
      if (this.sheet() !== sheet) return;
      this.people.update((list) =>
        list.some((p) => p.id === added.person.id) ? list : [...list, added.person],
      );
      this.claimSaved(added.claim);
      this.quickName.set('');
      this.quickAmount.set('');
    } catch (e) {
      if (this.sheet() !== sheet) return;
      this.quickError.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim falls in a closed period.'
          : e instanceof HttpErrorResponse && e.status === 422
            ? 'That claim is dated after the person retired.'
            : 'That claim could not be added.',
      );
    } finally {
      this.quickAdding = false;
    }
  }

  /** Put a saved person in place of their old row. */
  private put(person: Person): void {
    this.people.update((list) => list.map((p) => (p.id === person.id ? person : p)));
  }

  /** The person as now stored, when a save was refused for being made from an older version. */
  private staleOf(e: unknown): Person | null {
    if (!(e instanceof HttpErrorResponse) || e.status !== 409) return null;
    const body = e.error as { error?: unknown; current?: Person } | null;
    return body?.error === 'stale' && body.current ? body.current : null;
  }

  private async read(sheet: string): Promise<void> {
    try {
      const [list, claims, rates, statements, periods, settings, patrons] = await Promise.all([
        this.service.list(sheet),
        this.claimsService.list(sheet),
        this.ratesService.list(sheet),
        this.statementsService.list(sheet),
        this.periodsService.list(sheet),
        this.settingsService.get(sheet),
        this.service.patrons(sheet),
      ]);
      if (this.sheet() === sheet) {
        this.floor.set(cents(settings.floor));
        this.terms.set(settings.terms);
        this.latePct.set(settings.latePct);
        this.rates.set(rates);
        this.periods.set(periods);
        this.statements.set(statements);
        this.claims.set(claims);
        this.patrons.set(patrons);
        this.people.set(list);
      }
    } catch {
      // The sheet's people could not be read; the list stays empty.
    }
  }
}
