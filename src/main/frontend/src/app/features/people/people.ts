import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { forkJoin, of, switchMap } from 'rxjs';
import { Session } from '../../core/session';
import { Claims } from '../claims/claims';
import { Claim, ClaimsApi, Lateness, claimDue, counts, isOverdue, keepsFigure, sumAmounts } from '../claims/claims-api';
import { Periods } from '../periods/periods';
import { Period, PeriodsApi, periodOf } from '../periods/periods-api';
import { Rates } from '../rates/rates';
import { SheetFloor } from '../settings/sheet-floor';
import { SheetLatePct } from '../settings/sheet-late-pct';
import { SheetTerms } from '../settings/sheet-terms';
import { NO_RATE, Rate, RatesApi, rateOn } from '../rates/rates-api';
import { Grade, PeopleApi, Person } from './people-api';

// The people on the active sheet, in the order they were added, and the one form that adds a
// person or, once a person's editor is opened, saves changes to them. Each person's row lists their
// claims, what they have claimed (its OPEN and SETTLED claims, though a held person's OPEN ones count in
// no total) and what is due on it: the sum of those
// claims' rounded dues; the sheet's
// claimed-total and due-total add everyone's up (a due-total of n/a is skipped). Each person, and the sheet,
// also shows how many of the claims that count are overdue and the sum of their dues (n/a ones skipped).
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, Claims, Periods, Rates],
  template: `
    <app-rates [grades]="grades" [rates]="rates()" [current]="rateByGrade()" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotals]="periodDueTotals()" (saved)="periodSaved($event)" />
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
              <td data-testid="person-due-total">{{ personDueTotal(p) }}</td>
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statedTotal ?? '' }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td data-testid="person-retired">{{ p.retiredFrom ? 'yes' : 'no' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              <td data-testid="person-overdue-count">{{ overdueOf(p).length }}</td>
              <td data-testid="person-overdue-total">{{ overdueTotalOf(p) }}</td>
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
                <button
                  type="button"
                  data-testid="person-hold-toggle"
                  [attr.aria-label]="(p.held ? 'Release ' : 'Hold ') + p.name"
                  (click)="hold(p)"
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
  private readonly floor = inject(SheetFloor);
  private readonly terms = inject(SheetTerms);
  private readonly latePct = inject(SheetLatePct);
  /** What a late charge goes by: the sheet's today, terms and late percent. */
  private readonly late = computed<Lateness>(() => ({
    today: this.session.today(),
    terms: this.terms.value(),
    latePct: this.latePct.value(),
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
  /** The ids of the people on hold, whose OPEN claims count in no total. */
  private readonly heldIds = computed(() => new Set(this.people().filter((p) => p.held).map((p) => p.id)));
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
    const grades = new Map(this.people().map((p) => [p.id, p.grade]));
    const dues = new Map<number, string[]>();
    for (const c of this.claims().filter((c) => this.countsLive(c))) {
      const period = periodOf(this.periods(), c.date);
      const grade = grades.get(c.personId);
      if (!period || !grade) continue;
      const due = claimDue(c, this.rates(), grade, this.floor.value(), this.late());
      if (due !== NO_RATE) dues.set(period.id, [...(dues.get(period.id) ?? []), due]);
    }
    return new Map(this.periods().map((p) => [p.id, p.closedTotal ?? sumAmounts(dues.get(p.id) ?? [])]));
  });

  protected readonly dueTotal = computed(() =>
    sumAmounts(this.people().map((p) => this.personDueTotal(p)).filter((d) => d !== NO_RATE)),
  );
  /** How many of the sheet's claims that count are overdue, and the sum of their dues (n/a ones skipped). */
  protected readonly overdueCount = computed(() =>
    this.people().reduce((n, p) => n + this.overdueOf(p).length, 0),
  );
  protected readonly overdueTotal = computed(() => sumAmounts(this.people().map((p) => this.overdueTotalOf(p))));
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion: number | null = null;
  /** Whether a submit is waiting on the server; another submit is ignored until it answers. */
  private submitting = false;
  protected readonly formError = signal<string | null>(null);
  /** The error of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
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
    // Re-read the sheet's people and their claims from the store each time a sheet is loaded.
    toObservable(this.session.sheetId)
      .pipe(
        switchMap((id) =>
          id
            ? forkJoin({
                people: this.api.list(id),
                claims: this.claimsApi.list(id),
                rates: this.ratesApi.list(id),
                periods: this.periodsApi.list(id),
              })
            : of({ people: [] as Person[], claims: [] as Claim[], rates: [] as Rate[], periods: [] as Period[] }),
        ),
      )
      .subscribe(({ people, claims, rates, periods }) => {
        this.people.set(people);
        this.periods.set(periods);
        this.claims.set(claims);
        this.rates.set(rates);
        this.reset();
      });
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected claimed(personId: number): string {
    return sumAmounts(this.claimsOf(personId).filter((c) => this.countsLive(c)).map((c) => c.amount));
  }

  /** The sum of the dues of the claims the person has that count (n/a ones skipped), 0.00 with none. A
   * person whose grade has no rate in force on the sheet's today shows n/a, unless a settled or stated claim
   * keeps a figure. */
  protected personDueTotal(person: Person): string {
    const counted = this.claimsOf(person.id).filter((c) => this.countsLive(c));
    const frozen = counted.some(keepsFigure);
    if (!this.rateByGrade().has(person.grade) && !frozen) return NO_RATE;
    return sumAmounts(
      counted.map((c) => claimDue(c, this.rates(), person.grade, this.floor.value(), this.late())).filter((d) => d !== NO_RATE),
    );
  }

  /** The person's claims that count and are overdue. */
  protected overdueOf(person: Person): Claim[] {
    const today = this.session.today();
    return this.claimsOf(person.id).filter((c) => this.countsLive(c) && isOverdue(c, this.terms.value(), today));
  }

  /** The sum of the dues of the person's overdue claims that count (n/a ones skipped), 0.00 with none. */
  protected overdueTotalOf(person: Person): string {
    return sumAmounts(
      this.overdueOf(person)
        .map((c) => claimDue(c, this.rates(), person.grade, this.floor.value(), this.late()))
        .filter((d) => d !== NO_RATE),
    );
  }

  /** Whether a claim counts in the live totals: it counts, and is not an OPEN claim of a held person. */
  private countsLive(claim: Claim): boolean {
    return counts(claim) && !(claim.status === 'OPEN' && this.heldIds().has(claim.personId));
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

  /** Take a statement for the person, then re-read the sheet's claims, which it binds or unbinds. */
  protected statement(person: Person): void {
    this.personError.set(null);
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

  protected toggleRetired(event: Event): void {
    this.showRetired.set((event.target as HTMLInputElement).checked);
  }

  /** Retire the person from the date given, the sheet's today when none is. */
  protected retire(person: Person, from: string): void {
    this.personError.set(null);
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

  /** Put the person on hold, or take them off it when held. */
  protected hold(person: Person): void {
    this.personError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.api.hold(sheetId, person.id).subscribe({
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
    this.editingId.set(person.id);
    this.editingVersion = person.version;
    this.form.setValue({ name: person.name, grade: person.grade }, { emitEvent: false });
    this.formError.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.editingVersion = null;
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
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
      this.api.add(sheetId, value).subscribe({
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
      this.api.update(sheetId, id, { ...value, version: this.editingVersion ?? undefined }).subscribe({
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
        this.form.setValue({ name: current.name, grade: current.grade }, { emitEvent: false });
      }
    });
  }

  private refusal(e: HttpErrorResponse, fallback: string): string {
    return e.status === 409 ? 'That name is already in use on this sheet.' : fallback;
  }
}
