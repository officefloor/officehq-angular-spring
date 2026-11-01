import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, Claims, PctOn, RETIRED, cents, claimDueCents, countsFor, isOverdue, money } from '../claims/claims';
import { Period, Periods, periodOn } from '../periods/periods';
import { GRADES, NA, Rate, Rates, pctOn } from '../rates/rates';

const NO_CLAIMS: Claim[] = [];
const NO_RATES: Rate[] = [];
const NO_PCT: PctOn = () => null;

/** A grade a person was given, in force from a date until the next one; from is null from the start. */
interface GradeFrom {
  from: string | null;
  grade: string;
}

interface Person {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the store sent them. */
  grade: string;
  /** Every grade they were given, earliest first: the first from the start, each later from its date. */
  grades: GradeFrom[];
  /** Moved on by each save; an edit is made from the version the form loaded. */
  version: number;
  /** Retired once given a retirement date, whatever the date; retiredFrom is null while not. */
  retired: boolean;
  retiredFrom: string | null;
  /** While held, their OPEN claims count in no live total; their SETTLED claims still do. */
  held: boolean;
  /** The other people on the sheet they have named as patrons, in the order named. */
  patronIds: number[];
}

/** The person's grade in force on the date: the one with the latest from-date on or before it. */
function gradeOn(p: Person, date: string): string {
  let on = p.grade;
  for (const g of p.grades) if (g.from === null || g.from <= date) on = g.grade;
  return on;
}

/** A person's statements: how many, and the last one's due-total (null when n/a) and date. */
interface StatementSummary {
  personId: number;
  count: number;
  lastDueTotal: string | null;
  lastDate: string;
}

/** Overdue claims that count: how many, and their dues in cents. */
interface Overdue {
  count: number;
  cents: number;
}

/** A quick-add done: the person the claim went to, whether they were added for it, and the claim. */
interface QuickAdded {
  person: Person;
  added: boolean;
  claim: Claim;
}

/** A statement just taken: the person's statements and their claims as they now stand. */
interface StatementTaken {
  summary: StatementSummary;
  claims: Claim[];
}

// The people on the active sheet, in the order they were added, with one form to add or edit,
// each with their claims, what they have claimed and their due-total (OPEN and SETTLED claims only); the sheet's claimed-total and
// due-total under the table. Above it, the sheet's rate for each grade. Each person can be given a
// statement, which binds their counting claims' dues; the row shows how many statements they have
// had and the last one's due-total and date. Each person can be retired from a date (blank: the sheet's
// today); retired people are left out of the list unless show retired is on, but their claims
// keep counting in every total. Each person can be put on hold and taken off it again; while held,
// their OPEN claims count in no live total (theirs, the sheet's or a period's), their SETTLED ones still do.
// Each person, and the sheet, shows how many of the claims that count are overdue and their dues' total.
// A person's grade is dated: the first one given is in force from the start; each save records the
// form's grade from its from-date (blank: the sheet's today), unless it leaves the grade as the row
// showed it with the from-date blank. A claim takes the rate of the grade in force on its date; the
// row shows the grade in force on the sheet's today.
// Each person can name other people on the sheet as their patrons, one at a time (not themselves, nor
// the same person twice); the row shows their patrons' names, sorted, and their backing: their
// patrons' due-totals added up, skipping n/a.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, Claims, Periods, Rates],
  template: `
    <app-rates [rates]="rates()" [today]="session.today()" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotalOf]="periodDueTotalOf" (saved)="periodSaved($event)" />
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
        <label for="person-form-grade-from">Grade from</label>
        <input type="date" id="person-form-grade-from" data-testid="person-form-grade-from" formControlName="gradeFrom" />
        <button type="submit" data-testid="person-form-submit">{{ editingId() === null ? 'Add' : 'Save' }}</button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
        }
        @if (error()) {
          <p role="alert" class="error" data-testid="person-form-error">{{ error() }}</p>
        }
      </form>
      <form [formGroup]="quickForm" (ngSubmit)="quickAdd()" aria-label="Quick-add a draft claim">
        <label for="quick-name">Name</label>
        <input id="quick-name" data-testid="quick-name" formControlName="name" />
        <label for="quick-amount">Amount</label>
        <input id="quick-amount" data-testid="quick-amount" formControlName="amount" inputmode="decimal" />
        <button type="submit" data-testid="quick-submit">Quick add</button>
        @if (quickError()) {
          <p role="alert" class="error" data-testid="quick-error">{{ quickError() }}</p>
        }
      </form>
      <label>
        <input type="checkbox" data-testid="show-retired" [checked]="session.showRetired()"
               (change)="session.showRetired.set($any($event.target).checked)" />
        Show retired
      </label>
      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Claimed</th><th scope="col">Due total</th><th scope="col">Statements</th><th scope="col">Retired</th><th scope="col">Hold</th><th scope="col">Overdue</th><th scope="col">Patrons</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of listed(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              <td data-testid="person-claimed">{{ claimedOf(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotalOf(p) }}</td>
              <td>
                <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
                <span>Last <span data-testid="person-stated-total">{{ statedTotalOf(p.id) }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ statementOf(p.id)?.lastDate ?? '' }}</span></span>
                <button type="button" data-testid="person-statement" (click)="takeStatement(p)"
                        [attr.aria-label]="'Take statement for ' + p.name">Statement</button>
                @if (personError()?.id === p.id) {
                  <p role="alert" class="error" data-testid="person-error">{{ personError()?.message }}</p>
                }
              </td>
              <td>
                <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
                <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
                <label [for]="'person-retire-date-' + p.id" class="visually-hidden">Retire {{ p.name }} from</label>
                <input type="date" [id]="'person-retire-date-' + p.id" data-testid="person-retire-date"
                       #retireDate (input)="clearPersonError(p.id)" />
                <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)"
                        [attr.aria-label]="'Retire ' + p.name">Retire</button>
              </td>
              <td>
                <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                <button type="button" data-testid="person-hold-toggle" (click)="toggleHold(p)"
                        [attr.aria-label]="(p.held ? 'Release ' : 'Hold ') + p.name">{{ p.held ? 'Release' : 'Hold' }}</button>
              </td>
              <td>
                <span data-testid="person-overdue-count">{{ overdueOf(p).count }}</span>
                <span>Total <span data-testid="person-overdue-total">{{ money(overdueOf(p).cents) }}</span></span>
              </td>
              <td>
                <span data-testid="person-patrons">{{ patronsOf(p) }}</span>
                <span>Backing <span data-testid="person-backing">{{ backingOf(p) }}</span></span>
                <label [for]="'person-patron-select-' + p.id" class="visually-hidden">Patron for {{ p.name }}</label>
                <select [id]="'person-patron-select-' + p.id" data-testid="person-patron-select" #patronSelect
                        (change)="clearPatronError(p.id)">
                  <option value="">Choose a patron</option>
                  @for (o of patronChoicesFor(p); track o.id) {
                    <option [value]="o.id">{{ o.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-patron-add" (click)="addPatron(p, patronSelect)"
                        [attr.aria-label]="'Add patron for ' + p.name">Add patron</button>
                @if (patronError()?.id === p.id) {
                  <p role="alert" class="error" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                }
              </td>
              <td>
                <app-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [pctOn]="pctOnOf(p.id)"
                            [periods]="periods()"
                            (saved)="claimSaved($event)" />
              </td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
                        [attr.aria-label]="'Edit ' + p.name">Edit</button>
              </td>
            </tr>
          }
        </tbody>
      </table>
      <p>Claimed total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue: <strong data-testid="rollup-overdue-count">{{ overdueTotal().count }}</strong>
        totalling <strong data-testid="rollup-overdue-total">{{ money(overdueTotal().cents) }}</strong></p>
    </section>
  `,
})
export class People {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** The people on screen: everyone, less the retired unless show retired is on. */
  protected readonly listed = computed(() =>
    this.session.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  /** The ids of the people on hold. */
  private readonly heldIds = computed(() => new Set(this.people().filter((p) => p.held).map((p) => p.id)));
  /** Whether the claim counts in the live totals: OPEN or SETTLED, and not OPEN for a held person. */
  private readonly counts = (c: Claim): boolean => countsFor(c, this.heldIds().has(c.personId));
  protected readonly claimedTotal = computed(() => money(this.claims().filter(this.counts).reduce((sum, c) => sum + cents(c.amount), 0)));
  /** The sheet's rates, each for a grade from its from-date. */
  protected readonly rates = signal<Rate[]>([]);
  /** The sheet's periods, earliest first. */
  protected readonly periods = signal<Period[]>([]);
  /** Each period's due-total in cents, by id: the dues of the claims that count whose date falls in it. */
  private readonly periodDueCents = computed(() => {
    const by = new Map<number, number>();
    for (const c of this.claims().filter(this.counts)) {
      const period = periodOn(this.periods(), c.date);
      const pctOn = this.pctOnByPerson().get(c.personId);
      if (period === null || pctOn === undefined) continue;
      by.set(period.id, (by.get(period.id) ?? 0) + (claimDueCents(c, pctOn, cents(this.session.floor()), this.session.late()) ?? 0));
    }
    return by;
  });
  protected readonly periodDueTotalOf = (p: Period): string => money(this.periodDueCents().get(p.id) ?? 0);
  /** Each person's statements, for those who have had any. */
  protected readonly statements = signal<StatementSummary[]>([]);
  private readonly statementByPerson = computed(() => new Map(this.statements().map((s) => [s.personId, s])));
  /** The error from the last action on a person's row, if it was refused. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The error from the last patron added on a person's row, if it was refused. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  private readonly peopleById = computed(() => new Map(this.people().map((p) => [p.id, p])));
  private readonly ratesByGrade = computed(() => {
    const by = new Map<string, Rate[]>();
    for (const r of this.rates()) by.set(r.grade, [...(by.get(r.grade) ?? []), r]);
    return by;
  });
  /** Each person's rate on a date, by id: the rate then of the grade in force for them then. */
  private readonly pctOnByPerson = computed(() => {
    const by = new Map<number, PctOn>();
    for (const p of this.people()) by.set(p.id, (date) => pctOn(this.ratesOf(gradeOn(p, date)), date));
    return by;
  });
  /** Each grade's rate in force on the sheet's today, for the grades that have one. */
  private readonly pctByGrade = computed(() => {
    const today = this.session.today();
    const by = new Map<string, string>();
    if (today === null) return by;
    for (const [grade, rates] of this.ratesByGrade()) {
      const pct = pctOn(rates, today);
      if (pct !== null) by.set(grade, pct);
    }
    return by;
  });
  /** Everyone's due-total added up, skipping those whose due-total is n/a. */
  protected readonly dueTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + (this.dueTotalCents(p) ?? 0), 0)),
  );
  /** Each person's overdue claims that count, by id: how many and their dues in cents. */
  private readonly overdueByPerson = computed(() => {
    const terms = this.session.terms();
    const today = this.session.today();
    const floor = cents(this.session.floor());
    const late = this.session.late();
    const by = new Map<number, Overdue>();
    for (const p of this.people()) {
      const pctOn = this.pctOnOf(p.id);
      const overdue = this.claimsOf(p.id).filter((c) => this.counts(c) && isOverdue(c, terms, today));
      by.set(p.id, { count: overdue.length, cents: overdue.reduce((sum, c) => sum + (claimDueCents(c, pctOn, floor, late) ?? 0), 0) });
    }
    return by;
  });
  /** The sheet's overdue claims that count: everyone's added up. */
  protected readonly overdueTotal = computed(() => {
    let total: Overdue = { count: 0, cents: 0 };
    for (const o of this.overdueByPerson().values()) total = { count: total.count + o.count, cents: total.cents + o.cents };
    return total;
  });
  protected readonly money = money;
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person being edited, as the form loaded it. */
  private editingVersion = 0;
  /** A submit is under way; another one waits for its answer rather than storing twice. */
  private saving = false;
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl('A', { nonNullable: true }),
    /** The date the grade is in force from; blank: the sheet's today. */
    gradeFrom: new FormControl('', { nonNullable: true }),
  });

  /** A quick-add is under way; another one waits for its answer rather than storing twice. */
  private quickAdding = false;
  protected readonly quickError = signal('');
  protected readonly quickForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.quickForm.valueChanges.subscribe(() => this.quickError.set(''));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.rates.set([]);
      this.periods.set([]);
      this.statements.set([]);
      this.personError.set(null);
      this.patronError.set(null);
      this.reset();
      this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
      this.quickError.set('');
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
      const [list, claims, rates, statements, periods] = await Promise.all([
        firstValueFrom(this.http.get<Person[]>(this.url(sheet), { headers: this.headers() })),
        firstValueFrom(this.http.get<Claim[]>(`/api/sheets/${encodeURIComponent(sheet)}/claims`, { headers: this.headers() })),
        firstValueFrom(this.http.get<Rate[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates`, { headers: this.headers() })),
        firstValueFrom(this.http.get<StatementSummary[]>(`/api/sheets/${encodeURIComponent(sheet)}/statements`, { headers: this.headers() })),
        firstValueFrom(this.http.get<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods`, { headers: this.headers() })),
      ]);
      if (this.session.sheetId() !== sheet) return;
      this.people.set(list);
      this.claims.set(claims);
      this.rates.set(rates);
      this.statements.set(statements);
      this.periods.set(periods);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the people on this sheet.');
    }
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? NO_CLAIMS;
  }

  private claimedCents(personId: number): number {
    return this.claimsOf(personId).filter(this.counts).reduce((sum, c) => sum + cents(c.amount), 0);
  }

  protected claimedOf(personId: number): string {
    return money(this.claimedCents(personId));
  }

  protected ratesOf(grade: string): Rate[] {
    return this.ratesByGrade().get(grade) ?? NO_RATES;
  }

  /** The person's rate on a date, of the grade in force for them then. */
  protected pctOnOf(personId: number): PctOn {
    return this.pctOnByPerson().get(personId) ?? NO_PCT;
  }

  /** The person's grade in force on the sheet's today. */
  protected gradeOf(p: Person): string {
    const today = this.session.today();
    return today === null ? p.grade : gradeOn(p, today);
  }

  /** The grade's rate in force on the sheet's today, or null when none is. */
  protected pctOf(grade: string): string | null {
    return this.pctByGrade().get(grade) ?? null;
  }

  /**
   * The sum of the dues of their claims that count, each already rounded to cents; null (n/a) when
   * their grade has no rate in force today and none of those claims has a due fixed when settled or bound by a statement,
   * nor a due under another grade they had on its date.
   */
  private dueTotalCents(p: Person): number | null {
    const counted = this.claimsOf(p.id).filter(this.counts);
    const pctOn = this.pctOnOf(p.id);
    const floor = cents(this.session.floor());
    const late = this.session.late();
    const gradeToday = this.gradeOf(p);
    const fixed = (c: Claim) => c.settledDue !== null || (c.stated && c.statedDue !== null)
      || (gradeOn(p, c.date) !== gradeToday && claimDueCents(c, pctOn, floor, late) !== null);
    if (this.pctOf(gradeToday) === null && !counted.some(fixed)) return null;
    return counted.reduce((sum, c) => sum + (claimDueCents(c, pctOn, floor, late) ?? 0), 0);
  }

  protected overdueOf(p: Person): Overdue {
    return this.overdueByPerson().get(p.id) ?? { count: 0, cents: 0 };
  }

  /** Their due-total, or n/a. */
  protected dueTotalOf(p: Person): string {
    const total = this.dueTotalCents(p);
    return total === null ? NA : money(total);
  }

  /** Their patrons' names, comma and space separated, sorted. */
  protected patronsOf(p: Person): string {
    return this.patronsOfPerson(p)
      .map((x) => x.name)
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
      .join(', ');
  }

  /** Their patrons' due-totals added up, skipping n/a; 0.00 with no patrons. */
  protected backingOf(p: Person): string {
    return money(this.patronsOfPerson(p).reduce((sum, x) => sum + (this.dueTotalCents(x) ?? 0), 0));
  }

  private patronsOfPerson(p: Person): Person[] {
    const byId = this.peopleById();
    return p.patronIds.map((id) => byId.get(id)).filter((x): x is Person => x !== undefined);
  }

  /** The people who may be offered as the person's patron: everyone else on the sheet. */
  protected patronChoicesFor(p: Person): Person[] {
    return this.people().filter((x) => x.id !== p.id);
  }

  protected clearPatronError(id: number): void {
    if (this.patronError()?.id === id) this.patronError.set(null);
  }

  /** Name the person chosen in the select as the person's patron. */
  protected async addPatron(p: Person, select: HTMLSelectElement): Promise<void> {
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const patronId = Number(select.value);
    if (!select.value || !Number.isInteger(patronId)) {
      this.patronError.set({ id: p.id, message: 'Choose a patron.' });
      return;
    }
    if (patronId === p.id || p.patronIds.includes(patronId)) {
      this.patronError.set({ id: p.id, message: patronId === p.id ? 'A person cannot be their own patron.' : 'That patron is already named.' });
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(`${this.url(sheet)}/${p.id}/patrons`, { patronId }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      select.value = '';
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.patronError.set({
        id: p.id,
        message: e instanceof HttpErrorResponse && e.status === 409
          ? 'That patron is already named, or is the person themselves.'
          : 'Could not add that patron for ' + p.name + '.',
      });
    }
  }

  protected statementOf(personId: number): StatementSummary | undefined {
    return this.statementByPerson().get(personId);
  }

  /** The last statement's due-total: blank before any statement, n/a when it had none. */
  protected statedTotalOf(personId: number): string {
    const s = this.statementOf(personId);
    return s === undefined ? '' : (s.lastDueTotal ?? NA);
  }

  /** Take a statement for the person, binding their counting claims to their dues now. */
  protected async takeStatement(p: Person): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const taken = await firstValueFrom(
        this.http.post<StatementTaken>(`${this.url(sheet)}/${p.id}/statement`, null, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.statements.update((list) => [...list.filter((s) => s.personId !== p.id), taken.summary]);
      const byId = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
    } catch {
      if (this.session.sheetId() === sheet) this.personError.set({ id: p.id, message: 'Could not take a statement for ' + p.name + '.' });
    }
  }

  protected clearPersonError(id: number): void {
    if (this.personError()?.id === id) this.personError.set(null);
  }

  /** Retire the person from the date (blank: the sheet's today). */
  protected async retire(p: Person, date: string): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const from = date.trim();
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.personError.set({ id: p.id, message: 'Enter a date, or leave it blank for today.' });
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(`${this.url(sheet)}/${p.id}/retire`, { from: from || null }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
    } catch {
      if (this.session.sheetId() === sheet) this.personError.set({ id: p.id, message: 'Could not retire ' + p.name + '.' });
    }
  }

  /** Put the person on hold, or take them off it when held. */
  protected async toggleHold(p: Person): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(`${this.url(sheet)}/${p.id}/hold`, null, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
    } catch {
      if (this.session.sheetId() === sheet) {
        this.personError.set({ id: p.id, message: 'Could not ' + (p.held ? 'release ' : 'hold ') + p.name + '.' });
      }
    }
  }

  /** Put a set rate in place of the grade's old one from the same date. */
  protected rateSaved(rate: Rate): void {
    this.rates.update((list) => [...list.filter((r) => r.grade !== rate.grade || r.from !== rate.from), rate]);
  }

  /** Put an added period in its place by from-date, or a closed or reopened one in its place. */
  protected periodSaved(period: Period): void {
    this.periods.update((list) =>
      [...list.filter((p) => p.id !== period.id), period].sort((a, b) => (a.from < b.from ? -1 : 1)),
    );
  }

  /** Put an added claim first, or an edited one in its place. */
  protected claimSaved(claim: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id) ? list.map((c) => (c.id === claim.id ? claim : c)) : [claim, ...list],
    );
  }

  private reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A', gradeFrom: '' }, { emitEvent: false });
    this.error.set('');
  }

  protected edit(p: Person): void {
    this.personError.set(null);
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.form.setValue({ name: p.name, grade: this.gradeOf(p), gradeFrom: '' }, { emitEvent: false });
    this.error.set('');
  }

  protected cancel(): void {
    this.reset();
  }

  protected async save(): Promise<void> {
    if (this.saving) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.form.getRawValue();
    const name = raw.name.trim();
    const grade = raw.grade;
    const gradeFrom = raw.gradeFrom.trim() || null;
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!this.grades.includes(grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    const id = this.editingId();
    this.saving = true;
    try {
      if (id === null) {
        const added = await firstValueFrom(
          this.http.post<Person>(this.url(sheet), { name, grade }, { headers: this.headers() }),
        );
        if (this.session.sheetId() !== sheet) return;
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await firstValueFrom(
          this.http.put<Person>(`${this.url(sheet)}/${id}`, { name, grade, gradeFrom, version: this.editingVersion }, { headers: this.headers() }),
        );
        if (this.session.sheetId() !== sheet) return;
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 412 && id !== null) {
        await this.showCurrent(sheet, id);
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name is already in use on this sheet.');
      } else {
        this.error.set(id === null ? 'Could not add that person.' : 'Could not save that person.');
      }
    } finally {
      this.saving = false;
    }
  }

  /**
   * Add a DRAFT claim of the amount, dated the sheet's today with an empty note, for the person of
   * the name; one not on the sheet is added first with grade A. A refusal adds neither.
   */
  protected async quickAdd(): Promise<void> {
    if (this.quickAdding) return;
    this.quickError.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.quickForm.getRawValue();
    const name = raw.name.trim();
    const amount = raw.amount.trim();
    if (!name) {
      this.quickError.set('Enter a name.');
      return;
    }
    if (!/^\d{1,12}(\.\d{1,2})?$/.test(amount)) {
      this.quickError.set('Enter an amount of 0.00 or more, with at most two decimals.');
      return;
    }
    this.quickAdding = true;
    try {
      const done = await firstValueFrom(
        this.http.post<QuickAdded>(`/api/sheets/${encodeURIComponent(sheet)}/quick-add`, { name, amount }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      if (done.added) this.people.update((list) => [...list.filter((p) => p.id !== done.person.id), done.person]);
      this.claimSaved(done.claim);
      this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.quickError.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? 'No claim dated inside a closed period can be changed.'
          : e instanceof HttpErrorResponse && e.status === 422
          ? RETIRED
          : e instanceof HttpErrorResponse && e.status === 409
          ? 'That name is already in use on this sheet.'
          : 'Could not quick-add that claim.',
      );
    } finally {
      this.quickAdding = false;
    }
  }

  /** After a save refused as made from an old version: refuse it, and show the person as now stored. */
  private async showCurrent(sheet: string, id: number): Promise<void> {
    this.error.set('Someone else saved this person meanwhile; it now shows their values.');
    try {
      const list = await firstValueFrom(this.http.get<Person[]>(this.url(sheet), { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.people.set(list);
      const current = list.find((p) => p.id === id);
      if (current && this.editingId() === id) {
        this.editingVersion = current.version;
        this.form.setValue({ name: current.name, grade: this.gradeOf(current), gradeFrom: '' }, { emitEvent: false });
      }
    } catch {
      // the error already shown stands
    }
  }
}
