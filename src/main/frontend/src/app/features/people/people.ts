import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin, of, switchMap } from 'rxjs';
import { AMOUNT_PATTERN, ClaimView, Claims, cents, countsWhile, isOverdue, money } from '../../core/claims';
import { GRADES, Grade, People, PersonView } from '../../core/people';
import { PatronView, Patrons } from '../../core/patrons';
import { Periods, periodOn } from '../../core/periods';
import { DEFAULT_DUE_TERMS, NOT_AVAILABLE, RateView, Rates, claimDueCents } from '../../core/rates';
import { Sheets } from '../../core/sheet';
import { StatementSummary, Statements } from '../../core/statements';
import { PersonClaims } from '../claims/claims';
import { PeriodsPanel } from '../periods/periods';

const NO_CLAIMS: ClaimView[] = [];
const NO_RATES: RateView[] = [];

/** A count of overdue claims and their dues in whole cents. */
interface Overdue {
  count: number;
  cents: number;
}
const NO_OVERDUE: Overdue = { count: 0, cents: 0 };

// The people on the active sheet: one form to add a person or edit an existing one, the list with
// each person's claims and due-total, and the sheet's claimed-total and due-total.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, PersonClaims, PeriodsPanel],
  template: `
    <app-periods [dueTotals]="dueByPeriod()" />

    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue: <strong data-testid="rollup-overdue-count">{{ overdueTotals().count }}</strong>
        claims, due <strong data-testid="rollup-overdue-total">{{ money(overdueTotals().cents) }}</strong></p>

      <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editingId() === null ? 'Add a person' : 'Edit a person'">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" autocomplete="off" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="person-form-submit" [disabled]="saving()">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>

      <form [formGroup]="quickForm" (ngSubmit)="quickAdd()" aria-label="Quick-add a claim">
        <label for="quick-name">Name</label>
        <input id="quick-name" data-testid="quick-name" formControlName="name" autocomplete="off" />
        <label for="quick-amount">Amount</label>
        <input id="quick-amount" data-testid="quick-amount" formControlName="amount" inputmode="decimal" autocomplete="off" />
        <button type="submit" data-testid="quick-submit" [disabled]="quickSaving()">Quick add</button>
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err }}</p>
        }
      </form>

      <p>
        <input id="show-retired" type="checkbox" data-testid="show-retired"
               [checked]="api.showRetired()" (change)="api.showRetired.set($any($event.target).checked)" />
        <label for="show-retired">Show retired</label>
      </p>

      <ul data-testid="person-list">
        @for (p of listed(); track p.id) {
          <li [attr.data-testid]="'person-row-' + p.id">
            <span data-testid="person-name">{{ p.name }}</span>
            — grade <span data-testid="person-grade">{{ p.grade }}</span>
            — due <span data-testid="person-due-total">{{ dueOf(p) }}</span>
            — overdue <span data-testid="person-overdue-count">{{ overdueOf(p.id).count }}</span>
            due <span data-testid="person-overdue-total">{{ money(overdueOf(p.id).cents) }}</span>
            — statements <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
            — last stated <span data-testid="person-stated-total">{{ statementOf(p.id)?.dueTotal ?? '' }}</span>
            on <span data-testid="person-statement-date">{{ statementOf(p.id)?.date ?? '' }}</span>
            — retired <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
            from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span>
            — on hold <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
            <button type="button" data-testid="person-hold-toggle" (click)="toggleHold(p)" [disabled]="holding() === p.id"
                    [attr.aria-label]="(p.held ? 'Release ' : 'Hold ') + p.name">{{ p.held ? 'Release' : 'Hold' }}</button>
            <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
                    [attr.aria-label]="'Edit ' + p.name">Edit</button>
            <button type="button" data-testid="person-statement" (click)="takeStatement(p)" [disabled]="stating() === p.id"
                    [attr.aria-label]="'Take a statement of ' + p.name">Statement</button>
            <label [attr.for]="'person-retire-date-' + p.id">Retire from</label>
            <input [id]="'person-retire-date-' + p.id" type="date" data-testid="person-retire-date" #retireDate />
            <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)" [disabled]="retiring() === p.id"
                    [attr.aria-label]="'Retire ' + p.name">Retire</button>
            — patrons <span data-testid="person-patrons">{{ patronsOf(p.id) }}</span>
            — backing <span data-testid="person-backing">{{ backingOf(p.id) }}</span>
            <label [attr.for]="'person-patron-select-' + p.id">Patron</label>
            <select [id]="'person-patron-select-' + p.id" data-testid="person-patron-select" #patronSelect
                    (change)="patronError.set(null)">
              @for (q of people(); track q.id) {
                <option [value]="q.id">{{ q.name }}</option>
              }
            </select>
            <button type="button" data-testid="person-patron-add" (click)="addPatron(p, patronSelect.value)"
                    [disabled]="addingPatron() === p.id" [attr.aria-label]="'Add a patron of ' + p.name">Add patron</button>
            @if (patronError()?.id === p.id) {
              <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
            }
            @if (personError()?.id === p.id) {
              <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
            }
            <app-person-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [rates]="rates.ratesByGrade().get(p.grade) ?? noRates"
                               [periods]="periods.list()" [held]="p.held"
                               (saved)="claimSaved($event)" />
          </li>
        }
      </ul>
    </section>
  `,
})
export class PeopleList {
  private readonly sheets = inject(Sheets);
  protected readonly api = inject(People);
  private readonly claimsApi = inject(Claims);
  private readonly statementsApi = inject(Statements);
  private readonly patronsApi = inject(Patrons);
  protected readonly rates = inject(Rates);
  protected readonly periods = inject(Periods);

  protected readonly grades = GRADES;
  protected readonly money = money;
  protected readonly noRates = NO_RATES;
  protected readonly people = signal<PersonView[]>([]);
  /** The people listed: retired people only while "show retired" is on. Totals still count everyone. */
  protected readonly listed = computed(() =>
    this.api.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The person being retired, so a second press waits for the first. */
  protected readonly retiring = signal<number | null>(null);
  /** The person being held or released, so a second press waits for the first. */
  protected readonly holding = signal<number | null>(null);
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<ClaimView[]>([]);
  /** Each person's statements, by person; one with none is missing. */
  private readonly statements = signal<Map<number, StatementSummary>>(new Map());
  /** The person whose statement is being taken, so a second press waits for the first. */
  protected readonly stating = signal<number | null>(null);
  /** Every patron named on the sheet. */
  private readonly patrons = signal<PatronView[]>([]);
  /** The person a patron is being added to, so a second press waits for the first. */
  protected readonly addingPatron = signal<number | null>(null);
  /** The error of the last patron added on a person's row, until the next action on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** Each person's patrons' ids, by person. */
  private readonly patronIdsByPerson = computed(() => {
    const byPerson = new Map<number, number[]>();
    for (const pt of this.patrons()) byPerson.set(pt.personId, [...(byPerson.get(pt.personId) ?? []), pt.patronId]);
    return byPerson;
  });
  /** The error of the last action taken on a person's row, until the next action on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  private readonly claimsByPerson = computed(() => {
    const byPerson = new Map<number, ClaimView[]>();
    for (const c of this.claims()) byPerson.set(c.personId, [...(byPerson.get(c.personId) ?? []), c]);
    return byPerson;
  });
  /** Whether each claim counts in the live totals: a held person's OPEN claims do not. */
  private readonly countsNow = computed(() => {
    const held = new Set(this.people().filter((p) => p.held).map((p) => p.id));
    return (c: ClaimView) => countsWhile(held.has(c.personId))(c);
  });
  protected readonly claimedTotal = computed(() =>
    money(this.claims().filter(this.countsNow()).reduce((sum, c) => sum + cents(c.amount), 0)),
  );
  /** Each person's due-total in whole cents: the sum of the dues of their claims that count (0 with none);
   *  null when their grade has no rate in force today and none of those claims keeps a settled figure. */
  private readonly dueByPerson = computed(() => {
    const today = this.rates.byGrade();
    const ratesByGrade = this.rates.ratesByGrade();
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, number | null>();
    for (const p of this.people()) {
      const counting = this.claimsOf(p.id).filter(countsWhile(p.held));
      if (!today.has(p.grade) && !counting.some((c) => c.settledDue !== null || c.statedDue !== null)) {
        byPerson.set(p.id, null);
        continue;
      }
      const gradeRates = ratesByGrade.get(p.grade) ?? NO_RATES;
      byPerson.set(p.id, counting.reduce((sum, c) => sum + (claimDueCents(c, gradeRates, dueTerms) ?? 0), 0));
    }
    return byPerson;
  });
  /** Everyone's due-total added up, skipping those with none. */
  protected readonly dueTotal = computed(() =>
    money([...this.dueByPerson().values()].reduce<number>((sum, due) => sum + (due ?? 0), 0)),
  );
  /** Each person's overdue claims that count: how many, and their dues added up in whole cents (none counting as 0). */
  private readonly overdueByPerson = computed(() => {
    const sheet = this.sheets.current();
    const ratesByGrade = this.rates.ratesByGrade();
    const dueTerms = sheet ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, Overdue>();
    for (const p of this.people()) {
      const overdue = sheet
        ? this.claimsOf(p.id).filter((c) => countsWhile(p.held)(c) && isOverdue(c, sheet.terms, sheet.today))
        : NO_CLAIMS;
      const gradeRates = ratesByGrade.get(p.grade) ?? NO_RATES;
      byPerson.set(p.id, {
        count: overdue.length,
        cents: overdue.reduce((sum, c) => sum + (claimDueCents(c, gradeRates, dueTerms) ?? 0), 0),
      });
    }
    return byPerson;
  });
  /** Everyone's overdue claims added up. */
  protected readonly overdueTotals = computed(() =>
    [...this.overdueByPerson().values()].reduce<Overdue>(
      (sum, o) => ({ count: sum.count + o.count, cents: sum.cents + o.cents }),
      NO_OVERDUE,
    ),
  );
  /** Each period's due-total, by period id: the dues of the claims that count whose date falls in it;
   *  a closed period keeps the total fixed when it was closed. */
  protected readonly dueByPeriod = computed(() => {
    const periods = this.periods.list();
    const ratesByGrade = this.rates.ratesByGrade();
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const gradeOf = new Map(this.people().map((p) => [p.id, p.grade]));
    const totals = new Map<number, number>(periods.map((p) => [p.id, 0]));
    for (const c of this.claims().filter(this.countsNow())) {
      const period = periodOn(periods, c.date);
      const grade = gradeOf.get(c.personId);
      if (!period || !grade) continue;
      totals.set(period.id, (totals.get(period.id) ?? 0) + (claimDueCents(c, ratesByGrade.get(grade) ?? NO_RATES, dueTerms) ?? 0));
    }
    const closed = new Map(periods.filter((p) => p.closedTotal !== null).map((p) => [p.id, p.closedTotal as string]));
    return new Map([...totals].map(([id, total]) => [id, closed.get(id) ?? money(total)]));
  });
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion = 0;
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
  });

  protected readonly quickError = signal<string | null>(null);
  protected readonly quickSaving = signal(false);

  protected readonly quickForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its people from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.people.set([]);
          this.claims.set([]);
          this.statements.set(new Map());
          this.patrons.set([]);
          this.personError.set(null);
          this.patronError.set(null);
          this.reset();
          this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
          this.quickError.set(null);
          return sheet
            ? forkJoin({
                people: this.api.list(sheet.id),
                claims: this.claimsApi.list(sheet.id),
                statements: this.statementsApi.list(sheet.id),
                patrons: this.patronsApi.list(sheet.id),
              })
            : of({ people: [], claims: [], statements: [], patrons: [] });
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ people, claims, statements, patrons }) => {
          this.patrons.set(patrons);
          this.people.set(people);
          this.claims.set(claims);
          this.statements.set(new Map(statements.map((s) => [s.personId, s])));
        },
      });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
    this.quickForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.quickError.set(null));
  }

  protected claimsOf(personId: number): ClaimView[] {
    return this.claimsByPerson().get(personId) ?? NO_CLAIMS;
  }

  protected dueOf(p: PersonView): string {
    const due = this.dueByPerson().get(p.id);
    return due === null || due === undefined ? NOT_AVAILABLE : money(due);
  }

  /** The person's patrons' names, sorted, comma and space separated. */
  protected patronsOf(personId: number): string {
    const nameOf = new Map(this.people().map((q) => [q.id, q.name]));
    return (this.patronIdsByPerson().get(personId) ?? [])
      .map((id) => nameOf.get(id) ?? '')
      .sort((a, b) => a.localeCompare(b))
      .join(', ');
  }

  /** The person's backing: their patrons' due-totals added up, skipping those with none; 0.00 with no patrons. */
  protected backingOf(personId: number): string {
    const due = this.dueByPerson();
    return money((this.patronIdsByPerson().get(personId) ?? []).reduce((sum, id) => sum + (due.get(id) ?? 0), 0));
  }

  /** Name the chosen person as the person's patron; refused when it is themselves or already their patron. */
  protected addPatron(p: PersonView, patronId: string): void {
    if (this.addingPatron() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet || patronId === '') return;
    this.addingPatron.set(p.id);
    this.patronsApi.add(sheet.id, p.id, Number(patronId)).subscribe({
      next: (added) => {
        this.addingPatron.set(null);
        this.patrons.update((list) => [...list, added]);
      },
      error: (err: unknown) => {
        this.addingPatron.set(null);
        this.patronError.set({
          id: p.id,
          message:
            err instanceof HttpErrorResponse && err.status === 422
              ? p.name + ' cannot be their own patron.'
              : err instanceof HttpErrorResponse && err.status === 409
                ? 'That person is already a patron of ' + p.name + '.'
                : 'Could not add that patron.',
        });
      },
    });
  }

  protected overdueOf(personId: number): Overdue {
    return this.overdueByPerson().get(personId) ?? NO_OVERDUE;
  }

  protected statementOf(personId: number): StatementSummary | undefined {
    return this.statements().get(personId);
  }

  /** Take a statement of the person, then show their summary and their claims as it left them. */
  protected takeStatement(p: PersonView): void {
    if (this.stating() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.stating.set(p.id);
    this.statementsApi.take(sheet.id, p.id).subscribe({
      next: ({ summary, claims }) => {
        this.stating.set(null);
        this.statements.update((map) => new Map(map).set(summary.personId, summary));
        const byId = new Map(claims.map((c) => [c.id, c]));
        this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      },
      error: () => {
        this.stating.set(null);
        this.personError.set({ id: p.id, message: 'Could not take a statement of ' + p.name + '.' });
      },
    });
  }

  /** Retire the person from a date; a blank date takes the sheet's today. */
  protected retire(p: PersonView, from: string): void {
    if (this.retiring() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.retiring.set(p.id);
    this.api.retire(sheet.id, p.id, from === '' ? null : from).subscribe({
      next: (retired) => {
        this.retiring.set(null);
        this.people.update((list) => list.map((q) => (q.id === retired.id ? retired : q)));
      },
      error: () => {
        this.retiring.set(null);
        this.personError.set({ id: p.id, message: 'Could not retire ' + p.name + '.' });
      },
    });
  }

  /** Put the person on hold, or take them off hold when held. */
  protected toggleHold(p: PersonView): void {
    if (this.holding() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.holding.set(p.id);
    this.api.toggleHold(sheet.id, p.id).subscribe({
      next: (saved) => {
        this.holding.set(null);
        this.people.update((list) => list.map((q) => (q.id === saved.id ? saved : q)));
      },
      error: () => {
        this.holding.set(null);
        this.personError.set({ id: p.id, message: 'Could not ' + (p.held ? 'release ' : 'hold ') + p.name + '.' });
      },
    });
  }

  protected claimSaved(saved: ClaimView): void {
    this.claims.update((list) =>
      list.some((c) => c.id === saved.id)
        ? list.map((c) => (c.id === saved.id ? saved : c))
        : [saved, ...list].sort((a, b) => b.id - a.id),
    );
  }

  /** Quick-add a DRAFT claim for the named person, adding them first (grade A) when not on the sheet. */
  protected quickAdd(): void {
    // A second submit before the first has answered is ignored, so it stores one record.
    if (this.quickSaving()) return;
    this.quickError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.quickForm.getRawValue();
    if (value.name.trim() === '') {
      this.quickError.set('Type a name for the person.');
      return;
    }
    const amount = value.amount.trim();
    if (!AMOUNT_PATTERN.test(amount)) {
      this.quickError.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    this.quickSaving.set(true);
    this.claimsApi.quickAdd(sheet.id, value.name, amount).subscribe({
      next: ({ person, personAdded, claim }) => {
        this.quickSaving.set(false);
        if (personAdded) this.people.update((list) => [...list, person]);
        this.claimSaved(claim);
        this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
      },
      error: (err: unknown) => {
        this.quickSaving.set(false);
        this.quickError.set(
          err instanceof HttpErrorResponse && err.status === 423
            ? 'That claim is dated inside a closed period.'
            : err instanceof HttpErrorResponse && err.status === 422
              ? 'That claim is dated after the person retired.'
            : err instanceof HttpErrorResponse && err.status === 409
              ? 'That name is already in use on this sheet.'
              : 'Could not add that claim.',
        );
      },
    });
  }

  protected edit(p: PersonView): void {
    this.personError.set(null);
    this.patronError.set(null);
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.form.setValue({ name: p.name, grade: p.grade }, { emitEvent: false });
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A' }, { emitEvent: false });
    this.error.set(null);
  }

  protected submit(): void {
    // A second submit before the first has answered is ignored, so it stores one record.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.form.getRawValue();
    if (value.name.trim() === '') {
      this.error.set('Type a name for the person.');
      return;
    }
    const id = this.editingId();
    this.saving.set(true);
    const request = id === null ? this.api.add(sheet.id, value) : this.api.update(sheet.id, id, this.editingVersion, value);
    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.people.update((list) =>
          id === null ? [...list, saved] : list.map((p) => (p.id === saved.id ? saved : p)),
        );
        this.reset();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (id !== null && err instanceof HttpErrorResponse && err.status === 412) {
          this.error.set('Someone else has saved this person since you opened it; their values are shown.');
          this.showCurrent(sheet.id, id);
        } else if (err instanceof HttpErrorResponse && err.status === 409) {
          this.error.set('That name is already in use on this sheet.');
        } else {
          this.error.set(id === null ? 'Could not add that person.' : 'Could not save that person.');
        }
      },
    });
  }

  /** Re-read the people after a refused edit and show the person's current values in the editor. */
  private showCurrent(sheetId: string, id: number): void {
    this.api.list(sheetId).subscribe({
      next: (people) => {
        this.people.set(people);
        const current = people.find((p) => p.id === id);
        if (current && this.editingId() === id) {
          this.editingVersion = current.version;
          this.form.setValue({ name: current.name, grade: current.grade }, { emitEvent: false });
        }
      },
    });
  }
}
