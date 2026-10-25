import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { ClaimView, ClaimsApi, Lateness, cents, claimOwedCents, counts, isOverdue, keepsFigure, money } from '../claims/claims-api';
import { PersonClaims } from '../claims/person-claims';
import { Periods } from '../periods/periods';
import { PeriodView, PeriodsApi, periodOf } from '../periods/periods-api';
import { Rates } from '../rates/rates';
import { RateView, RatesApi, rateInForce, sameRate } from '../rates/rates-api';
import { SheetFloor } from '../settings/sheet-floor';
import { SheetLatePct } from '../settings/sheet-late-pct';
import { SheetPolicyDate } from '../settings/sheet-policy-date';
import { SheetTerms } from '../settings/sheet-terms';
import { StatementView, StatementsApi } from '../statements/statements-api';
import { GradeSummary, PeriodSummary, StatusSummary, Summary } from '../summary/summary';
import { GRADES, Grade, PeopleApi, PersonView, QuickAddView, gradeOn } from './people-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;
/** The statuses the summary counts claims of; no claim is ever pending, so its count stays 0. */
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;
import { QuickAdd } from './quick-add';

// The sheet's people in the order they were added, with one form to add a person or edit one,
// each with their claims and what they have claimed and is due, the sheet's claimed-total and
// due-total, the sheet's rates, and its periods with the due-total of the counting claims dated in each. Each person's statement button records their due-total and binds
// their counting claims to the figures they show then.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, PersonClaims, Rates, Periods, QuickAdd, Summary],
  template: `
    @if (session.sheet()) {
      <app-rates [rates]="rates()" (saved)="rateSaved($event)" />
      <app-periods [periods]="periods()" [dueTotals]="periodDueTotals()" (added)="periodAdded($event)" (changed)="periodChanged($event)" />
      <section aria-labelledby="people-heading">
        <h2 id="people-heading">People</h2>
        <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editingId() === null ? 'Add a person' : 'Edit a person'">
          <label for="person-form-name">Name</label>
          <input id="person-form-name" data-testid="person-form-name" formControlName="name" />
          <label for="person-form-grade">Grade</label>
          <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
            @for (g of grades; track g) {
              <option [value]="g">{{ g }}</option>
            }
          </select>
          @if (editingId() !== null) {
            <label for="person-form-grade-from">Grade from</label>
            <input id="person-form-grade-from" type="date" data-testid="person-form-grade-from" formControlName="gradeFrom"
              aria-describedby="person-form-grade-from-hint" />
            <span id="person-form-grade-from-hint">Blank: the sheet's today</span>
          }
          <button type="submit" data-testid="person-form-submit" [disabled]="saving()">
            {{ editingId() === null ? 'Add person' : 'Save person' }}
          </button>
          @if (editingId() !== null) {
            <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
          }
          @if (error()) {
            <p role="alert" data-testid="person-form-error">{{ error() }}</p>
          }
        </form>
        <app-quick-add (added)="quickAdded($event)" />
        <label>
          <input type="checkbox" data-testid="show-retired" [checked]="showRetired()"
            (change)="showRetired.set($any($event.target).checked)" />
          Show retired
        </label>
        <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
        <p>Due in total: <strong data-testid="rollup-due-total">{{ sheetDueTotal() }}</strong></p>
        <p>Overdue: <strong data-testid="rollup-overdue-count">{{ sheetOverdue().count }}</strong>
          claims, due <strong data-testid="rollup-overdue-total">{{ money(sheetOverdue().cents) }}</strong></p>
        <app-summary [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()" />
        <table data-testid="people-list">
          <thead>
            <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Overdue</th><th scope="col">Retired</th><th scope="col">On hold</th><th scope="col">Statements</th><th scope="col">Patrons</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
          </thead>
          <tbody>
            @for (p of shownPeople(); track p.id) {
              <tr [attr.data-testid]="'person-row-' + p.id">
                <td data-testid="person-name">{{ p.name }}</td>
                <td data-testid="person-grade">{{ gradeToday(p) }}</td>
                <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
                <td data-testid="person-due-total">{{ personDueTotal(p) }}</td>
                <td>
                  <span data-testid="person-overdue-count">{{ overdueOf(p.id).count }}</span>
                  due <span data-testid="person-overdue-total">{{ money(overdueOf(p.id).cents) }}</span>
                </td>
                <td>
                  <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
                  from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span>
                  <label [for]="'person-retire-date-' + p.id" class="visually-hidden">Retire {{ p.name }} from</label>
                  <input type="date" [id]="'person-retire-date-' + p.id" data-testid="person-retire-date"
                    #retireDate (input)="clearPersonError(p.id)" />
                  <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)"
                    [disabled]="retiring() === p.id" [attr.aria-label]="'Retire ' + p.name">Retire</button>
                </td>
                <td>
                  <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                  <button type="button" data-testid="person-hold-toggle" (click)="toggleHold(p)"
                    [disabled]="holding() === p.id" [attr.aria-label]="(p.held ? 'Release ' : 'Hold ') + p.name">
                    {{ p.held ? 'Release' : 'Hold' }}</button>
                </td>
                <td>
                  <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
                  taken; last <span data-testid="person-stated-total">{{ statementOf(p.id)?.lastDueTotal ?? '' }}</span>
                  on <span data-testid="person-statement-date">{{ statementOf(p.id)?.lastDate ?? '' }}</span>
                  <button type="button" data-testid="person-statement" (click)="takeStatement(p)"
                    [disabled]="stating() === p.id" [attr.aria-label]="'Take a statement of ' + p.name">Statement</button>
                  @if (personError()?.id === p.id) {
                    <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
                  }
                </td>
                <td>
                  <span data-testid="person-patrons">{{ patronNames(p) }}</span>
                  backing <span data-testid="person-backing">{{ backing(p) }}</span>
                  <label [for]="'person-patron-select-' + p.id" class="visually-hidden">Patron for {{ p.name }}</label>
                  <select [id]="'person-patron-select-' + p.id" data-testid="person-patron-select" #patronSelect
                    (change)="clearPatronError(p.id)">
                    <option value="">Choose a patron</option>
                    @for (o of people(); track o.id) {
                      @if (o.id !== p.id) {
                        <option [value]="o.id">{{ o.name }}</option>
                      }
                    }
                  </select>
                  <button type="button" data-testid="person-patron-add" (click)="addPatron(p, patronSelect)"
                    [disabled]="addingPatron() === p.id" [attr.aria-label]="'Add a patron for ' + p.name">Add patron</button>
                  @if (patronError()?.id === p.id) {
                    <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                  }
                </td>
                <td>
                  <app-person-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)"
                    [rates]="rates()" [grades]="p.grades" [periods]="periods()"
                    (added)="claimAdded($event)" (saved)="claimSaved($event)" (changed)="claimSaved($event)" />
                </td>
                <td>
                  <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
                    [attr.aria-label]="'Edit ' + p.name">Edit</button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    }
  `,
})
export class People {
  protected readonly session = inject(Session);
  private readonly api = inject(PeopleApi);
  private readonly claimsApi = inject(ClaimsApi);
  private readonly ratesApi = inject(RatesApi);
  private readonly statementsApi = inject(StatementsApi);
  private readonly periodsApi = inject(PeriodsApi);
  private readonly floor = inject(SheetFloor);
  private readonly terms = inject(SheetTerms);
  private readonly latePct = inject(SheetLatePct);
  private readonly policyDate = inject(SheetPolicyDate);
  /** What the late charges depend on now. */
  private readonly lateness = computed<Lateness>(() =>
    ({ terms: this.terms.days(), today: this.session.sheet()?.today, latePct: this.latePct.pct(),
      policyDate: this.policyDate.date() ?? this.session.sheet()?.today }));

  protected readonly grades = GRADES;
  protected readonly name = new FormControl('', { nonNullable: true });
  protected readonly grade = new FormControl<Grade>('A', { nonNullable: true });
  /** The date an edit gives the grade from; blank means the sheet's today. */
  protected readonly gradeFrom = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ name: this.name, grade: this.grade, gradeFrom: this.gradeFrom });

  /** Everyone on the sheet, retired or not: their claims count in every total. */
  protected readonly people = signal<PersonView[]>([]);
  /** Whether retired people are listed; off when the screen opens, and kept as it is by loading a sheet. */
  protected readonly showRetired = signal(false);
  /** The people listed: the retired ones only while show retired is on. */
  protected readonly shownPeople = computed(() => this.people().filter((p) => this.showRetired() || !p.retired));
  /** The person being retired. */
  protected readonly retiring = signal<number | null>(null);
  /** The person a patron is being added to. */
  protected readonly addingPatron = signal<number | null>(null);
  /** The refusal of the last patron added on a person's row, shown on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** The person being put on hold or taken off it. */
  protected readonly holding = signal<number | null>(null);
  /** The ids of the people on hold. */
  private readonly heldIds = computed(() => new Set(this.people().filter((p) => p.held).map((p) => p.id)));
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<ClaimView[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, ClaimView[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  private readonly centsByPerson = computed(() => {
    const by = new Map<number, number>();
    for (const c of this.claims()) if (this.countsLive(c)) by.set(c.personId, (by.get(c.personId) ?? 0) + cents(c.amount));
    return by;
  });
  protected readonly claimedTotal = computed(() => money(this.claims().reduce((t, c) => (this.countsLive(c) ? t + cents(c.amount) : t), 0)));
  /** The sheet's rates; a grade with no rate is left out. */
  protected readonly rates = signal<RateView[]>([]);
  private readonly ratesByGrade = computed(() => {
    const by = new Map<Grade, RateView[]>();
    for (const r of this.rates()) by.set(r.grade, [...(by.get(r.grade) ?? []), r]);
    return by;
  });
  /** Everyone's due-total added up, skipping the people whose due-total is n/a. */
  protected readonly sheetDueTotal = computed(() => {
    let total = 0;
    for (const p of this.people()) total += this.dueCentsOf(p) ?? 0;
    return money(total);
  });
  /** Each person's overdue claims that count, by person: how many, and the sum of their figures in
   *  cents, skipping those that are n/a. */
  private readonly overdueByPerson = computed(() => {
    const by = new Map<number, { count: number; cents: number }>();
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const today = this.session.sheet()?.today;
    for (const c of this.claims()) {
      const person = byId.get(c.personId);
      if (person === undefined || !this.countsLive(c) || !isOverdue(c, this.terms.days(), today)) continue;
      const due = claimOwedCents(c, this.ratesOn(person, c.date), this.floor.cents(), this.lateness(), this.claims());
      const was = by.get(c.personId) ?? { count: 0, cents: 0 };
      by.set(c.personId, { count: was.count + 1, cents: was.cents + (due ?? 0) });
    }
    return by;
  });
  /** The sheet's overdue claims that count: how many, and the sum of their figures in cents. */
  protected readonly sheetOverdue = computed(() => {
    let count = 0;
    let total = 0;
    for (const o of this.overdueByPerson().values()) {
      count += o.count;
      total += o.cents;
    }
    return { count, cents: total };
  });
  protected readonly money = money;
  /** The sheet's periods in date order. */
  protected readonly periods = signal<PeriodView[]>([]);
  /** Each period's due-total, by period id: the figures of the counting claims dated in it, skipping
   *  those that are n/a; a closed period's is the total fixed when it was closed. */
  protected readonly periodDueTotals = computed(() => {
    const cents = new Map<number, number>(this.periods().map((p) => [p.id, 0]));
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const c of this.claims()) {
      if (!this.countsLive(c)) continue;
      const period = periodOf(this.periods(), c.date);
      const person = byId.get(c.personId);
      if (period === null || person === undefined) continue;
      const due = claimOwedCents(c, this.ratesOn(person, c.date), this.floor.cents(), this.lateness(), this.claims());
      if (due !== null) cents.set(period.id, (cents.get(period.id) ?? 0) + due);
    }
    const fixed = new Map(this.periods().filter((p) => p.closed).map((p) => [p.id, p.closedTotal ?? '0.00']));
    return new Map([...cents].map(([id, total]) => [id, fixed.get(id) ?? money(total)]));
  });
  /** Per grade, the people on it today who are not retired, and their due-totals added up, skipping
   *  those that are n/a. */
  protected readonly gradeSummary = computed<GradeSummary[]>(() => GRADES.map((grade) => {
    const on = this.people().filter((p) => !p.retired && this.gradeToday(p) === grade);
    return { grade, count: on.length, dueTotal: money(on.reduce((t, p) => t + (this.dueCentsOf(p) ?? 0), 0)) };
  }));
  /** Each period's due-total, as its row shows it. */
  protected readonly periodSummary = computed<PeriodSummary[]>(() =>
    this.periods().map((p) => ({ name: p.name, dueTotal: this.periodDueTotals().get(p.id) ?? '0.00' })));
  /** How many of the sheet's claims have each status. */
  protected readonly statusSummary = computed<StatusSummary[]>(() =>
    SUMMARY_STATUSES.map((status) => ({ status, count: this.claims().filter((c) => c.status === status).length })));
  /** The statements of each person who has had one taken, by person. */
  protected readonly statements = signal<ReadonlyMap<number, StatementView>>(new Map());
  /** The person whose statement is being taken. */
  protected readonly stating = signal<number | null>(null);
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private readonly editingVersion = signal(0);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    // Each load of a sheet re-reads its people from the store and starts the form afresh.
    effect(() => {
      const sheet = this.session.loaded();
      this.reset();
      this.people.set([]);
      this.claims.set([]);
      this.rates.set([]);
      this.periods.set([]);
      this.statements.set(new Map());
      this.personError.set(null);
      this.patronError.set(null);
      if (sheet) void this.reload(sheet.id);
    });
  }

  private async reload(sheetId: string): Promise<void> {
    try {
      const [people, claims, rates, statements, periods] = await Promise.all([
        this.api.list(sheetId), this.claimsApi.list(sheetId), this.ratesApi.list(sheetId),
        this.statementsApi.list(sheetId), this.periodsApi.list(sheetId)]);
      if (this.session.sheet()?.id === sheetId) {
        this.statements.set(new Map(statements.map((s) => [s.personId, s])));
        this.rates.set(rates);
        this.periods.set(periods);
        this.claims.set(claims);
        this.people.set(people);
      }
    } catch {
      this.error.set('The people could not be loaded.');
    }
  }

  protected claimsOf(personId: number): ClaimView[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected statementOf(personId: number): StatementView | undefined {
    return this.statements().get(personId);
  }

  /** Take the person's statement, then show it and their claims as it bound them. */
  protected async takeStatement(person: PersonView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.stating() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    this.stating.set(person.id);
    try {
      const taken = await this.statementsApi.take(sheet.id, person.id);
      if (this.session.sheet()?.id !== sheet.id) return;
      const bound = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => bound.get(c.id) ?? c));
      this.statements.update((by) => new Map(by).set(person.id, taken.statement));
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: 'The statement could not be taken.' });
      }
    } finally {
      this.stating.set(null);
    }
  }

  protected clearPersonError(personId: number): void {
    if (this.personError()?.id === personId) this.personError.set(null);
  }

  /** Retire the person from the date entered, or the sheet's today when it is left blank. */
  protected async retire(person: PersonView, from: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.retiring() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    this.retiring.set(person.id);
    try {
      const retired = await this.api.retire(sheet.id, person.id, from.trim() === '' ? null : from.trim());
      if (this.session.sheet()?.id !== sheet.id) return;
      this.people.update((list) => list.map((p) => (p.id === retired.id ? retired : p)));
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: 'The person could not be retired.' });
      }
    } finally {
      this.retiring.set(null);
    }
  }

  /** Whether the claim counts in the live totals: open and settled claims do, except the open claims
   *  of a person on hold. */
  private countsLive(claim: ClaimView): boolean {
    return counts(claim) && !(claim.status === 'OPEN' && this.heldIds().has(claim.personId));
  }

  /** The names of the person's patrons, sorted, comma and space separated. */
  protected patronNames(person: PersonView): string {
    const nameOf = new Map(this.people().map((p) => [p.id, p.name]));
    return person.patronIds.map((id) => nameOf.get(id) ?? '').filter((n) => n !== '')
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).join(', ');
  }

  /** The person's backing: their patrons' due-totals added up, skipping those that are n/a. */
  protected backing(person: PersonView): string {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    let total = 0;
    for (const id of person.patronIds) {
      const patron = byId.get(id);
      if (patron) total += this.dueCentsOf(patron) ?? 0;
    }
    return money(total);
  }

  protected clearPatronError(personId: number): void {
    if (this.patronError()?.id === personId) this.patronError.set(null);
  }

  /** Name the person chosen in the row's select as the person's patron. */
  protected async addPatron(person: PersonView, select: HTMLSelectElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.addingPatron() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    const patronId = Number(select.value);
    if (select.value === '' || !Number.isInteger(patronId)) {
      this.patronError.set({ id: person.id, message: 'Choose a patron.' });
      return;
    }
    if (patronId === person.id) {
      this.patronError.set({ id: person.id, message: 'A person cannot be their own patron.' });
      return;
    }
    if (person.patronIds.includes(patronId)) {
      this.patronError.set({ id: person.id, message: 'That person is already a patron.' });
      return;
    }
    this.addingPatron.set(person.id);
    try {
      const changed = await this.api.addPatron(sheet.id, person.id, patronId);
      if (this.session.sheet()?.id !== sheet.id) return;
      this.people.update((list) => list.map((p) => (p.id === changed.id ? changed : p)));
      select.value = '';
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        this.patronError.set({ id: person.id, message: e instanceof HttpErrorResponse && e.status === 409
          ? 'That person cannot be added as a patron again.' : 'The patron could not be added.' });
      }
    } finally {
      this.addingPatron.set(null);
    }
  }

  /** Put the person on hold, or take them off it when they are held. */
  protected async toggleHold(person: PersonView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.holding() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    this.holding.set(person.id);
    try {
      const changed = await this.api.hold(sheet.id, person.id, !person.held);
      if (this.session.sheet()?.id !== sheet.id) return;
      this.people.update((list) => list.map((p) => (p.id === changed.id ? changed : p)));
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: person.held ? 'The person could not be released.' : 'The person could not be put on hold.' });
      }
    } finally {
      this.holding.set(null);
    }
  }

  /** The person's overdue claims that count: how many, and the sum of their figures in cents. */
  protected overdueOf(personId: number): { count: number; cents: number } {
    return this.overdueByPerson().get(personId) ?? { count: 0, cents: 0 };
  }

  /** The sum of the person's open and settled claims, with two decimals. */
  protected claimed(personId: number): string {
    return money(this.centsByPerson().get(personId) ?? 0);
  }

  /** The grade's rates, whatever their from-dates. */
  private ratesOf(grade: Grade | null): RateView[] {
    return grade === null ? [] : (this.ratesByGrade().get(grade) ?? []);
  }

  /** The rates of the person's grade in force on a date, whatever their from-dates. */
  private ratesOn(person: PersonView, date: string): RateView[] {
    return this.ratesOf(gradeOn(person.grades, date));
  }

  /** The person's grade in force on the sheet's today. */
  protected gradeToday(person: PersonView): Grade {
    const today = this.session.sheet()?.today;
    return (today === undefined ? null : gradeOn(person.grades, today)) ?? person.grade;
  }

  /** The grade's rate in force on the sheet's today, or null when none is. */
  private rateOf(grade: Grade): string | null {
    const today = this.session.sheet()?.today;
    return today === undefined ? null : (rateInForce(this.ratesOf(grade), today)?.pct ?? null);
  }

  /** The sum of the figures due on the person's open and settled claims, each at the grade in force on
   *  its date, in cents; null (n/a) when no rate of their grade in force today is in force today and
   *  none of their claims keeps a figure. */
  private dueCentsOf(person: PersonView): number | null {
    const gradeToday = this.gradeToday(person);
    let total = 0;
    let keptFigure = false;
    for (const c of this.claimsOf(person.id)) {
      if (!this.countsLive(c)) continue;
      const grade = gradeOn(person.grades, c.date);
      const due = claimOwedCents(c, this.ratesOf(grade), this.floor.cents(), this.lateness(), this.claims());
      if (due !== null) total += due;
      if (keepsFigure(c)) keptFigure = true;
      // A live figure at a grade other than today's is not undone by today's grade having no rate.
      if (due !== null && grade !== gradeToday) keptFigure = true;
    }
    return this.rateOf(gradeToday) === null && !keptFigure ? null : total;
  }

  /** The person's due-total with two decimals, or n/a when no rate of their grade in force today is in
   *  force today and no claim keeps a figure (settled, bound by a statement, or at another grade). */
  protected personDueTotal(person: PersonView): string {
    const due = this.dueCentsOf(person);
    return due === null ? 'n/a' : money(due);
  }

  protected rateSaved(rate: RateView): void {
    // Kept as the store lists them: in grade order, then the rate from the start, then by from-date.
    this.rates.update((list) => [...list.filter((r) => !sameRate(r, rate)), rate]
      .sort((a, b) => a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? '')));
  }

  protected periodAdded(period: PeriodView): void {
    // Kept as the store lists them: in date order.
    this.periods.update((list) => [...list, period].sort((a, b) => a.from.localeCompare(b.from)));
  }

  protected periodChanged(period: PeriodView): void {
    this.periods.update((list) => list.map((p) => (p.id === period.id ? period : p)));
  }

  protected claimAdded(claim: ClaimView): void {
    this.claims.update((list) => [claim, ...list]);
  }

  /** A quick-add's claim, and its person when the quick-add added them. */
  protected quickAdded(added: QuickAddView): void {
    if (!this.people().some((p) => p.id === added.person.id)) this.people.update((list) => [...list, added.person]);
    this.claimAdded(added.claim);
  }

  protected claimSaved(claim: ClaimView): void {
    this.claims.update((list) => list.map((c) => (c.id === claim.id ? claim : c)));
  }

  private reset(): void {
    this.editingId.set(null);
    this.error.set('');
    this.form.reset({ name: '', grade: 'A', gradeFrom: '' }, { emitEvent: false });
  }

  protected edit(person: PersonView): void {
    this.personError.set(null);
    this.patronError.set(null);
    this.editingId.set(person.id);
    this.editingVersion.set(person.version);
    this.error.set('');
    this.form.setValue({ name: person.name, grade: this.gradeToday(person), gradeFrom: '' }, { emitEvent: false });
  }

  protected cancel(): void {
    this.reset();
  }

  /** After an edit was refused because someone else saved the person meanwhile: re-read the people
   *  and show the person's current values in the editor, keeping the refusal showing. */
  private async showCurrent(sheetId: string, id: number): Promise<void> {
    try {
      const people = await this.api.list(sheetId);
      if (this.session.sheet()?.id !== sheetId) return;
      this.people.set(people);
      const current = people.find((p) => p.id === id);
      if (current && this.editingId() === id) {
        this.editingVersion.set(current.version);
        this.form.setValue({ name: current.name, grade: this.gradeToday(current), gradeFrom: '' }, { emitEvent: false });
      }
    } finally {
      this.error.set('Someone else has saved this person since you opened it; the form now shows their changes.');
    }
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const value = this.form.getRawValue();
    if (!value.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    if (!GRADES.includes(value.grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    const id = this.editingId();
    const gradeFrom = value.gradeFrom.trim();
    if (id !== null && gradeFrom !== '' && !DATE.test(gradeFrom)) {
      this.error.set('Type the grade\'s from-date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    this.saving.set(true);
    try {
      if (id === null) {
        const added = await this.api.add(sheet.id, { name: value.name.trim(), grade: value.grade });
        if (this.session.sheet()?.id === sheet.id) this.people.update((list) => [...list, added]);
      } else {
        const saved = await this.api.update(sheet.id, id,
          { name: value.name.trim(), grade: value.grade, gradeFrom: gradeFrom === '' ? null : gradeFrom,
            version: this.editingVersion() });
        if (this.session.sheet()?.id === sheet.id) {
          this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        }
      }
      if (this.session.sheet()?.id === sheet.id) this.reset();
    } catch (e) {
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(sheet.id, id);
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on the sheet.');
      } else {
        this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    } finally {
      this.saving.set(false);
    }
  }
}
