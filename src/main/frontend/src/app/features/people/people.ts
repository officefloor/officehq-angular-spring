import { HttpErrorResponse } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { forkJoin, of, switchMap } from 'rxjs';
import { Session } from '../../core/session';
import { Claims } from '../claims/claims';
import {
  Claim,
  ClaimsApi,
  Lateness,
  bound,
  claimDue,
  counts,
  isOpen,
  isOverdue,
  keepsFigure,
  roundsBy,
  sumAmounts,
} from '../claims/claims-api';
import { Periods } from '../periods/periods';
import { Period, PeriodsApi, periodOf } from '../periods/periods-api';
import { Rates } from '../rates/rates';
import { GradeSummary, Summary } from '../summary/summary';
import { SheetFloor } from '../settings/sheet-floor';
import { SheetLatePct } from '../settings/sheet-late-pct';
import { SheetPolicyDate } from '../settings/sheet-policy-date';
import { NO_RATE, Rate, RatesApi, rateOn } from '../rates/rates-api';
import { Grade, PeopleApi, Person, gradeOn, heldOn } from './people-api';

// The people on the active sheet, in the order they were added, and the one form that adds a
// person or, once a person's editor is opened, saves changes to them. Each person's row lists their
// claims, what they have claimed (its OPEN, PENDING and SETTLED claims, though OPEN and PENDING ones dated within one of
// their holds and not bound by a statement count in no total) and what is due on it: the sum of those
// claims' rounded dues; the sheet's
// claimed-total and due-total add everyone's up (a due-total of n/a is skipped). Each person, and the sheet,
// also shows how many of the claims that count are overdue and the sum of their dues (n/a ones skipped).
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, Claims, Periods, Rates, Summary],
  template: `
    <app-rates [grades]="grades" [rates]="rates()" [current]="rateByGrade()" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotals]="periodDueTotals()" (saved)="periodSaved($event)" />
    <app-summary
      [grades]="gradeSummary()"
      [periods]="periods()"
      [periodDueTotals]="periodDueTotals()"
      [statusCounts]="statusCounts()"
    />
    <section aria-labelledby="people-heading" data-testid="people">
      <h2 id="people-heading">People</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="person-form">
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
          <input id="person-form-grade-from" type="date" data-testid="person-form-grade-from" formControlName="gradeFrom" />
        }
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
      </form>
      @if (formError(); as e) {
        <p role="alert" data-testid="person-form-error">{{ e }}</p>
      }
      <form [formGroup]="quickForm" (ngSubmit)="quickAdd()" data-testid="quick-add" aria-label="Quick add a claim">
        <label for="quick-name">Name</label>
        <input id="quick-name" data-testid="quick-name" formControlName="name" />
        <label for="quick-amount">Amount</label>
        <input id="quick-amount" data-testid="quick-amount" formControlName="amount" inputmode="decimal" />
        <button type="submit" data-testid="quick-submit">Quick add</button>
      </form>
      @if (quickError(); as e) {
        <p role="alert" data-testid="quick-error">{{ e }}</p>
      }
      <label>
        <input type="checkbox" data-testid="show-retired" [checked]="showRetired()" (change)="toggleRetired($event)" />
        Show retired
      </label>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <button type="button" data-testid="bulk-open" (click)="openSelected()">Open selected claims</button>
      @if (bulkOpenResult(); as r) {
        <p role="status">
          Opened <strong data-testid="bulk-opened">{{ r.opened }}</strong>,
          refused <strong data-testid="bulk-refused">{{ r.refused }}</strong>
        </p>
      }
      @if (bulkOpenError(); as e) {
        <p role="alert" data-testid="bulk-open-error">{{ e }}</p>
      }
      <p>Claimed total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue: <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong></p>
      <p>Overdue total: <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>
      <table data-testid="people-list">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            <th scope="col">Claimed</th>
            <th scope="col">Due</th>
            <th scope="col">Statements</th>
            <th scope="col">Last stated</th>
            <th scope="col">Stated on</th>
            <th scope="col">Retired</th>
            <th scope="col">Retired from</th>
            <th scope="col">On hold</th>
            <th scope="col">Overdue</th>
            <th scope="col">Overdue total</th>
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
              <td data-testid="person-grade">{{ gradeToday(p) }}</td>
              <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
              <td data-testid="person-due-total">{{ personDueTotal(p) }}</td>
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statedTotal ?? '' }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td data-testid="person-retired">{{ p.retiredFrom ? 'yes' : 'no' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td>
                <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                @if (p.holds.length) {
                  <ul>
                    @for (h of p.holds; track $index) {
                      <li data-testid="person-hold-line">{{ h.from }} to {{ h.to ?? 'open' }}</li>
                    }
                  </ul>
                }
              </td>
              <td data-testid="person-overdue-count">{{ overdueOf(p).length }}</td>
              <td data-testid="person-overdue-total">{{ overdueTotalOf(p) }}</td>
              <td data-testid="person-patrons">{{ patronNames(p) }}</td>
              <td data-testid="person-backing">{{ backing(p) }}</td>
              <td>
                <app-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsOf(p.id)"
                  [grades]="p.grades"
                  [rates]="rates()"
                  [periods]="periods()"
                  [people]="people()"
                  [selected]="selectedClaims()"
                  (saved)="claimSaved($event)"
                  (selectedChange)="claimSelected($event.id, $event.selected)"
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
                  [attr.aria-label]="'Take statement for ' + p.name"
                  (click)="statement(p)"
                >
                  Statement
                </button>
                <label [for]="'person-retire-date-' + p.id">Retire from</label>
                <input
                  type="date"
                  [id]="'person-retire-date-' + p.id"
                  data-testid="person-retire-date"
                  #retireDate
                  (input)="personError.set(null)"
                />
                <button
                  type="button"
                  data-testid="person-retire"
                  [attr.aria-label]="'Retire ' + p.name"
                  (click)="retire(p, retireDate.value)"
                >
                  Retire
                </button>
                <label [for]="'person-hold-from-' + p.id">Hold from</label>
                <input
                  type="date"
                  [id]="'person-hold-from-' + p.id"
                  data-testid="person-hold-from"
                  #holdFrom
                  (input)="personError.set(null)"
                />
                <label [for]="'person-hold-to-' + p.id">Hold to</label>
                <input
                  type="date"
                  [id]="'person-hold-to-' + p.id"
                  data-testid="person-hold-to"
                  #holdTo
                  (input)="personError.set(null)"
                />
                <button
                  type="button"
                  data-testid="person-hold-toggle"
                  [attr.aria-label]="'Hold ' + p.name"
                  (click)="hold(p, holdFrom.value, holdTo.value)"
                >
                  Hold
                </button>
                <label [for]="'person-patron-select-' + p.id">Patron</label>
                <select
                  [id]="'person-patron-select-' + p.id"
                  data-testid="person-patron-select"
                  #patronSelect
                  (change)="patronError.set(null)"
                >
                  @for (o of people(); track o.id) {
                    @if (o.id !== p.id) {
                      <option [value]="o.id">{{ o.name }}</option>
                    }
                  }
                </select>
                <button
                  type="button"
                  data-testid="person-patron-add"
                  [attr.aria-label]="'Add patron for ' + p.name"
                  (click)="addPatron(p, patronSelect.value)"
                >
                  Add patron
                </button>
                @if (patronError()?.id === p.id) {
                  <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                }
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
  private readonly document = inject(DOCUMENT);
  private readonly api = inject(PeopleApi);
  private readonly claimsApi = inject(ClaimsApi);
  private readonly ratesApi = inject(RatesApi);
  private readonly periodsApi = inject(PeriodsApi);
  private readonly floor = inject(SheetFloor);
  private readonly latePct = inject(SheetLatePct);
  private readonly policyDate = inject(SheetPolicyDate);
  /** What a late charge goes by: the sheet's today and late percent; and the policy date rounding goes by. */
  private readonly late = computed<Lateness>(() => ({
    today: this.session.today(),
    latePct: this.latePct.value(),
    policyDate: this.policyDate.value() ?? this.session.today(),
  }));

  protected readonly grades: Grade[] = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  /** Whether retired people are listed; off when the screen opens, and kept as it is by loading a sheet. */
  protected readonly showRetired = signal(false);
  /** The people listed: everyone, or only those not retired unless retired people are shown. Totals count
   * everyone either way. */
  protected readonly shownPeople = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => !p.retiredFrom),
  );
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  /** Each person's holds, by id: their OPEN and PENDING claims dated within one, unless bound, count in no total. */
  private readonly holdsById = computed(() => new Map(this.people().map((p) => [p.id, p.holds])));
  protected readonly claimedTotal = computed(() =>
    sumAmounts(this.claims().filter((c) => this.countsLive(c)).map((c) => c.amount)),
  );
  /** The sheet's rates. */
  protected readonly rates = signal<Rate[]>([]);
  /** The rate each grade with one has in force on the sheet's today. */
  protected readonly rateByGrade = computed(() => {
    const today = this.session.today() ?? '';
    const by = new Map<Grade, string>();
    for (const g of this.grades) {
      const rate = rateOn(this.rates(), g, today);
      if (rate !== undefined) by.set(g, rate);
    }
    return by;
  });

  /** The sheet's periods by from-date. */
  protected readonly periods = signal<Period[]>([]);
  /** Each period's due-total, by id: the sum of the dues of the claims that count dated in it (n/a ones
   * skipped), 0.00 with none; a closed period's is the one fixed when it closed. */
  protected readonly periodDueTotals = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const dues = new Map<number, string[]>();
    for (const c of this.claims().filter((c) => this.countsLive(c))) {
      const period = periodOf(this.periods(), c.date);
      const person = byId.get(c.personId);
      if (!period || !person) continue;
      const due = this.claimDue(c, person);
      if (due !== NO_RATE) dues.set(period.id, [...(dues.get(period.id) ?? []), due]);
    }
    return new Map(this.periods().map((p) => [p.id, p.closedTotal ?? sumAmounts(dues.get(p.id) ?? [])]));
  });

  protected readonly dueTotal = computed(() =>
    sumAmounts(this.people().map((p) => this.personDueTotal(p)).filter((d) => d !== NO_RATE)),
  );
  /** Per grade, the people not retired who have it on the sheet's today and their due-totals added up (n/a
   * ones skipped). */
  protected readonly gradeSummary = computed<GradeSummary[]>(() =>
    this.grades.map((grade) => {
      const on = this.people().filter((p) => !p.retiredFrom && this.gradeToday(p) === grade);
      const dueTotal = sumAmounts(on.map((p) => this.personDueTotal(p)).filter((d) => d !== NO_RATE));
      return { grade, count: on.length, dueTotal };
    }),
  );
  /** How many of the sheet's claims have each status. */
  protected readonly statusCounts = computed(() => {
    const by = new Map<string, number>();
    for (const c of this.claims()) by.set(c.status, (by.get(c.status) ?? 0) + 1);
    return by;
  });
  /** How many of the sheet's claims that count are overdue, and the sum of their dues (n/a ones skipped). */
  protected readonly overdueCount = computed(() =>
    this.people().reduce((n, p) => n + this.overdueOf(p).length, 0),
  );
  protected readonly overdueTotal = computed(() => sumAmounts(this.people().map((p) => this.overdueTotalOf(p))));
  /** The ids of the claims selected to open together. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** How many claims the last open of the selected ones opened and refused. */
  protected readonly bulkOpenResult = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal<string | null>(null);
  /** Whether an open of the selected claims is waiting on the server; another is ignored until it answers. */
  private bulkOpening = false;
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion: number | null = null;
  /** Whether a submit is waiting on the server; another submit is ignored until it answers. */
  private submitting = false;
  protected readonly formError = signal<string | null>(null);
  /** The error of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The error of the last patron added on a person's row, shown on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    /** The date a saved grade is in force from; the sheet's today unless another date is picked. */
    gradeFrom: new FormControl('', { nonNullable: true }),
  });

  /** Whether a quick-add is waiting on the server; another is ignored until it answers. */
  private quickAdding = false;
  protected readonly quickError = signal<string | null>(null);
  protected readonly quickForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.formError.set(null));
    this.quickForm.valueChanges.subscribe(() => this.quickError.set(null));
    effect(() => {
      const today = this.session.today() ?? '';
      untracked(() => {
        if (!this.form.controls.gradeFrom.dirty) this.form.controls.gradeFrom.setValue(today, { emitEvent: false });
      });
    });
    // Re-read the sheet's people and their claims from the store each time a sheet is loaded.
    const loaded = this.session.registerLoader(inject(DestroyRef));
    toObservable(this.session.sheetId)
      .pipe(
        switchMap((id) =>
          id
            ? forkJoin({
                id: of(id),
                people: this.api.list(id),
                claims: this.claimsApi.list(id),
                rates: this.ratesApi.list(id),
                periods: this.periodsApi.list(id),
              })
            : of({ id, people: [] as Person[], claims: [] as Claim[], rates: [] as Rate[], periods: [] as Period[] }),
        ),
      )
      .subscribe(({ id, people, claims, rates, periods }) => {
        this.people.set(people);
        this.periods.set(periods);
        this.claims.set(claims);
        this.rates.set(rates);
        this.selectedClaims.set(new Set());
        this.bulkOpenResult.set(null);
        this.bulkOpenError.set(null);
        this.reset();
        loaded(id);
      });
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected claimed(personId: number): string {
    return sumAmounts(this.claimsOf(personId).filter((c) => this.countsLive(c)).map((c) => c.amount));
  }

  /** The grade the person has in force on the sheet's today. */
  protected gradeToday(person: Person): Grade {
    const today = this.session.today();
    return (today !== null ? gradeOn(person.grades, today) : undefined) ?? person.grade;
  }

  /** What is due on a claim of the person's, at the grade they have on the claim's date. */
  private claimDue(claim: Claim, person: Person): string {
    return claimDue(claim, this.rates(), gradeOn(person.grades, claim.date), this.floor.value(), this.late(),
      roundsBy(claim, this.claimsOf(person.id)));
  }

  /** The sum of the dues of the claims the person has that count (n/a ones skipped), 0.00 with none. A
   * person shows n/a when neither the grade they have on the sheet's today nor one they had on the date of a
   * claim that counts has a rate in force on the sheet's today, unless a settled or stated claim keeps a
   * figure. */
  protected personDueTotal(person: Person): string {
    const counted = this.claimsOf(person.id).filter((c) => this.countsLive(c));
    const frozen = counted.some(keepsFigure);
    const grades = [this.gradeToday(person), ...counted.map((c) => gradeOn(person.grades, c.date))];
    if (!grades.some((g) => g !== undefined && this.rateByGrade().has(g)) && !frozen) return NO_RATE;
    return sumAmounts(counted.map((c) => this.claimDue(c, person)).filter((d) => d !== NO_RATE));
  }

  /** The person's claims that count and are overdue. */
  protected overdueOf(person: Person): Claim[] {
    const today = this.session.today();
    return this.claimsOf(person.id).filter((c) => this.countsLive(c) && isOverdue(c, today));
  }

  /** The sum of the dues of the person's overdue claims that count (n/a ones skipped), 0.00 with none. */
  protected overdueTotalOf(person: Person): string {
    return sumAmounts(
      this.overdueOf(person)
        .map((c) => this.claimDue(c, person))
        .filter((d) => d !== NO_RATE),
    );
  }

  /** The names of the person's patrons, sorted, comma and space separated. */
  protected patronNames(person: Person): string {
    return this.patronsOf(person)
      .map((p) => p.name)
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
      .join(', ');
  }

  /** The person's backing: their patrons' due-totals added up (n/a ones skipped), 0.00 with no patrons. */
  protected backing(person: Person): string {
    return sumAmounts(this.patronsOf(person).map((p) => this.personDueTotal(p)).filter((d) => d !== NO_RATE));
  }

  private patronsOf(person: Person): Person[] {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    return person.patronIds.map((id) => byId.get(id)).filter((p): p is Person => p !== undefined);
  }

  /** Name another person on the sheet as the person's patron. */
  protected addPatron(person: Person, patronId: string): void {
    this.patronError.set(null);
    this.personError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    if (!patronId) {
      this.patronError.set({ id: person.id, message: 'Choose a patron.' });
      return;
    }
    this.api.addPatron(sheetId, person.id, Number(patronId)).subscribe({
      next: (p) => {
        if (this.session.sheetId() === sheetId) this.people.update((ps) => ps.map((x) => (x.id === p.id ? p : x)));
      },
      error: (e: HttpErrorResponse) =>
        this.patronError.set({
          id: person.id,
          message:
            e.status === 409
              ? 'That person is already a patron.'
              : e.status === 422
                ? 'A person cannot be their own patron.'
                : e.status === 404
                  ? 'That person is no longer on this sheet.'
                  : 'Could not add that patron.',
        }),
    });
  }

  /** Whether a claim counts in the live totals: it counts, and is not an OPEN or PENDING claim dated within one of
   * its person's holds and not bound by a statement. */
  private countsLive(claim: Claim): boolean {
    return (
      counts(claim) &&
      !(isOpen(claim) && !bound(claim) && heldOn(this.holdsById().get(claim.personId) ?? [], claim.date))
    );
  }

  protected rateSaved(rate: Rate): void {
    this.rates.update((rs) => [...rs.filter((r) => r.grade !== rate.grade || r.from !== rate.from), rate]);
  }

  protected periodSaved(period: Period): void {
    this.periods.update((ps) =>
      [...ps.filter((p) => p.id !== period.id), period].sort((a, b) => (a.from < b.from ? -1 : 1)),
    );
  }

  protected claimSaved(claim: Claim): void {
    this.claims.update((cs) =>
      cs.some((c) => c.id === claim.id) ? cs.map((c) => (c.id === claim.id ? claim : c)) : [claim, ...cs],
    );
  }

  protected claimSelected(id: number, selected: boolean): void {
    this.bulkOpenError.set(null);
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (selected) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Open the selected claims together; the server opens and numbers them in list order and leaves each one
   * that cannot be opened as it is. */
  protected openSelected(): void {
    if (this.bulkOpening) return;
    this.bulkOpenResult.set(null);
    this.bulkOpenError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.bulkOpenError.set('Load a sheet first.');
      return;
    }
    const ids = [...this.selectedClaims()];
    if (ids.length === 0) {
      this.bulkOpenError.set('Select the claims to open.');
      return;
    }
    this.bulkOpening = true;
    this.claimsApi.openAll(sheetId, ids).subscribe({
      next: ({ opened, refused, claims }) => {
        this.bulkOpening = false;
        if (this.session.sheetId() !== sheetId) return;
        for (const c of claims) this.claimSaved(c);
        this.selectedClaims.set(new Set());
        this.bulkOpenResult.set({ opened, refused });
      },
      error: (e: HttpErrorResponse) => {
        this.bulkOpening = false;
        this.bulkOpenError.set(e.status === 404 ? 'That sheet is no longer in the store.' : 'Could not open those claims.');
      },
    });
  }

  /** Take a statement for the person, then re-read the sheet's claims, which it binds or unbinds. */
  protected statement(person: Person): void {
    this.personError.set(null);
    this.patronError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.api
      .statement(sheetId, person.id)
      .pipe(switchMap((p) => forkJoin({ person: of(p), claims: this.claimsApi.list(sheetId) })))
      .subscribe({
        next: ({ person: p, claims }) => {
          if (this.session.sheetId() !== sheetId) return;
          this.claims.set(claims);
          this.people.update((ps) => ps.map((x) => (x.id === p.id ? p : x)));
        },
        error: (e: HttpErrorResponse) =>
          this.personError.set({
            id: person.id,
            message: e.status === 404 ? 'That person is no longer on this sheet.' : 'Could not take that statement.',
          }),
      });
  }

  /** Download everyone, retired people included, in list order as a CSV file: name, the grade in force on the
   * sheet's today, the retired-from date or nothing, and the due-total as shown. */
  protected exportCsv(): void {
    const lines = [
      ['name', 'grade', 'retired-from', 'due-total'],
      ...this.people().map((p) => [p.name, this.gradeToday(p), p.retiredFrom ?? '', this.personDueTotal(p)]),
    ];
    const csv = lines.map((l) => l.map(csvValue).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const a = this.document.createElement('a');
    a.href = url;
    a.download = 'people.csv';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected toggleRetired(event: Event): void {
    this.showRetired.set((event.target as HTMLInputElement).checked);
  }

  /** Retire the person from the date given, the sheet's today when none is. */
  protected retire(person: Person, from: string): void {
    this.personError.set(null);
    this.patronError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.api.retire(sheetId, person.id, from || null).subscribe({
      next: (p) => {
        if (this.session.sheetId() === sheetId) this.people.update((ps) => ps.map((x) => (x.id === p.id ? p : x)));
      },
      error: (e: HttpErrorResponse) =>
        this.personError.set({
          id: person.id,
          message: e.status === 404 ? 'That person is no longer on this sheet.' : 'Could not retire that person.',
        }),
    });
  }

  /** Put the person on hold from the date given, the sheet's today when none is, to the date given or open-ended. */
  protected hold(person: Person, from: string, to: string): void {
    this.personError.set(null);
    this.patronError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.api.hold(sheetId, person.id, from || null, to || null).subscribe({
      next: (p) => {
        if (this.session.sheetId() === sheetId) this.people.update((ps) => ps.map((x) => (x.id === p.id ? p : x)));
      },
      error: (e: HttpErrorResponse) =>
        this.personError.set({
          id: person.id,
          message: e.status === 404 ? 'That person is no longer on this sheet.' : 'Could not change that hold.',
        }),
    });
  }

  /** Add a draft claim for the named person, who is added first when not on the sheet. */
  protected quickAdd(): void {
    if (this.quickAdding) return;
    this.quickError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.quickError.set('Load a sheet first.');
      return;
    }
    const value = this.quickForm.getRawValue();
    if (!value.name.trim()) {
      this.quickError.set('Type a name for the person.');
      return;
    }
    this.quickAdding = true;
    this.api.quickAdd(sheetId, value).subscribe({
      next: ({ person, personCreated, claim }) => {
        this.quickAdding = false;
        if (this.session.sheetId() !== sheetId) return;
        if (personCreated) this.people.update((ps) => [...ps, person]);
        this.claimSaved(claim);
        this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
      },
      error: (e: HttpErrorResponse) => {
        this.quickAdding = false;
        this.quickError.set(this.quickRefusal(e));
      },
    });
  }

  private quickRefusal(e: HttpErrorResponse): string {
    if (e.status === 400) return 'Type a name and an amount of 0.00 or more, with up to two decimals.';
    if (e.status === 423) return 'The sheet\'s today is in a closed period.';
    if (e.status === 422) return 'That person is retired from before the sheet\'s today.';
    return 'Could not add that claim.';
  }

  protected edit(person: Person): void {
    this.personError.set(null);
    this.patronError.set(null);
    this.editingId.set(person.id);
    this.editingVersion = person.version;
    this.form.setValue(
      { name: person.name, grade: this.gradeToday(person), gradeFrom: this.session.today() ?? '' },
      { emitEvent: false },
    );
    this.formError.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.editingVersion = null;
    this.form.reset({ name: '', grade: 'A', gradeFrom: this.session.today() ?? '' }, { emitEvent: false });
    this.formError.set(null);
  }

  protected submit(): void {
    if (this.submitting) return;
    this.formError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.formError.set('Load a sheet first.');
      return;
    }
    const value = this.form.getRawValue();
    if (!value.name.trim()) {
      this.formError.set('Type a name for the person.');
      return;
    }
    const id = this.editingId();
    this.submitting = true;
    const done = () => (this.submitting = false);
    if (id === null) {
      this.api.add(sheetId, { name: value.name, grade: value.grade }).subscribe({
        next: (p) => {
          done();
          if (this.session.sheetId() === sheetId) this.people.update((ps) => [...ps, p]);
          this.reset();
        },
        error: (e: HttpErrorResponse) => {
          done();
          this.formError.set(this.refusal(e, 'Could not add that person.'));
        },
      });
    } else {
      this.api
        .update(sheetId, id, { ...value, gradeFrom: value.gradeFrom || null, version: this.editingVersion ?? undefined })
        .subscribe({
          next: (p) => {
            done();
            if (this.session.sheetId() === sheetId) this.people.update((ps) => ps.map((x) => (x.id === p.id ? p : x)));
            this.reset();
          },
          error: (e: HttpErrorResponse) => {
            done();
            if (e.status === 412) {
              this.formError.set('Someone else has saved this person since you opened it. Their values are shown.');
              this.showCurrent(sheetId, id);
            } else {
              this.formError.set(this.refusal(e, 'Could not save that person.'));
            }
          },
        });
    }
  }

  /** Re-read the person from the store and show their current values in the editor, keeping its error. */
  private showCurrent(sheetId: string, id: number): void {
    this.api.list(sheetId).subscribe((people) => {
      if (this.session.sheetId() !== sheetId) return;
      this.people.set(people);
      const current = people.find((p) => p.id === id);
      if (current && this.editingId() === id) {
        this.editingVersion = current.version;
        this.form.setValue(
          { name: current.name, grade: this.gradeToday(current), gradeFrom: this.session.today() ?? '' },
          { emitEvent: false },
        );
      }
    });
  }

  private refusal(e: HttpErrorResponse, fallback: string): string {
    return e.status === 409 ? 'That name is already in use on this sheet.' : fallback;
  }
}

/** A CSV value, wrapped in double quotes, with any quote inside doubled, when it holds a comma, a quote or a
 * line break. */
function csvValue(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}
