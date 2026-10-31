import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin, of, switchMap } from 'rxjs';
import { AMOUNT_PATTERN, ClaimView, Claims, cents, countsWithHolds, isOverdue, money, roundingDate } from '../../core/claims';
import { GRADES, Grade, People, PersonView, gradeOn } from '../../core/people';
import { PatronView, Patrons } from '../../core/patrons';
import { Periods, periodOn } from '../../core/periods';
import { DEFAULT_DUE_TERMS, DueTerms, NOT_AVAILABLE, RateView, Rates, claimDueCents } from '../../core/rates';
import { Sheets } from '../../core/sheet';
import { StatementSummary, Statements } from '../../core/statements';
import { PersonClaims } from '../claims/claims';
import { SheetHeader } from '../header/header';
import { PeriodsPanel } from '../periods/periods';
import { GradeSummary, PeriodSummary, StatusSummary, SummaryPanel } from '../summary/summary';

const NO_CLAIMS: ClaimView[] = [];
const NO_RATES: RateView[] = [];

/** The statuses the summary counts claims in, in the order it lists them. */
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

/** A count of overdue claims and their dues in whole cents. */
interface Overdue {
  count: number;
  cents: number;
}
const NO_OVERDUE: Overdue = { count: 0, cents: 0 };

/** A CSV field: wrapped in double quotes, with any quote inside doubled, when it holds a comma, a quote or a line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replaceAll('"', '""') + '"' : value;
}

// The people on the active sheet: one form to add a person or edit an existing one, the list with
// each person's claims and due-total, and the sheet's claimed-total and due-total.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, SheetHeader, PersonClaims, PeriodsPanel, SummaryPanel],
  template: `
    <app-header [peopleCount]="people().length" [statuses]="summaryByStatus()" [dueTotal]="dueTotal()"
                [overdueTotal]="money(overdueTotals().cents)" />
    <app-periods [dueTotals]="dueByPeriod()" />
    <app-summary [grades]="summaryByGrade()" [periods]="summaryByPeriod()" [statuses]="summaryByStatus()" />

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
        @if (editingId() !== null) {
          <label for="person-form-grade-from">Grade from</label>
          <input id="person-form-grade-from" type="date" data-testid="person-form-grade-from" formControlName="gradeFrom" />
        }
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

      <p>
        <button type="button" data-testid="export-people" (click)="exportCsv()">Export people as CSV</button>
      </p>

      <p>
        <button type="button" data-testid="bulk-open" (click)="bulkOpen()" [disabled]="bulkOpening()">Open selected claims</button>
        @if (bulkOpenResult(); as r) {
          Opened <span data-testid="bulk-opened">{{ r.opened }}</span>,
          refused <span data-testid="bulk-refused">{{ r.refused }}</span>
        }
        @if (bulkOpenError(); as err) {
          <span role="alert" data-testid="bulk-open-error">{{ err }}</span>
        }
      </p>

      <ul data-testid="person-list">
        @for (p of listed(); track p.id) {
          <li [attr.data-testid]="'person-row-' + p.id">
            <span data-testid="person-name">{{ p.name }}</span>
            — grade <span data-testid="person-grade">{{ gradeToday(p) }}</span>
            — due <span data-testid="person-due-total">{{ dueOf(p) }}</span>
            excess <span data-testid="person-excess">{{ money(excessOf(p.id)) }}</span>
            — overdue <span data-testid="person-overdue-count">{{ overdueOf(p.id).count }}</span>
            due <span data-testid="person-overdue-total">{{ money(overdueOf(p.id).cents) }}</span>
            — statements <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
            — last stated <span data-testid="person-stated-total">{{ statementOf(p.id)?.dueTotal ?? '' }}</span>
            on <span data-testid="person-statement-date">{{ statementOf(p.id)?.date ?? '' }}</span>
            — retired <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
            from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span>
            — on hold <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
            @if (p.holds.length > 0) {
              <ul [attr.aria-label]="'Holds of ' + p.name">
                @for (h of p.holds; track $index) {
                  <li data-testid="person-hold-line">{{ h.from }} to {{ h.to ?? 'no end' }}</li>
                }
              </ul>
            }
            <label [attr.for]="'person-hold-from-' + p.id">Hold from</label>
            <input [id]="'person-hold-from-' + p.id" type="date" data-testid="person-hold-from" #holdFrom
                   (input)="clearPersonError(p.id)" />
            <label [attr.for]="'person-hold-to-' + p.id">to</label>
            <input [id]="'person-hold-to-' + p.id" type="date" data-testid="person-hold-to" #holdTo
                   (input)="clearPersonError(p.id)" />
            <button type="button" data-testid="person-hold-toggle" (click)="hold(p, holdFrom.value, holdTo.value)"
                    [disabled]="holding() === p.id" [attr.aria-label]="'Hold ' + p.name">Hold</button>
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
            <app-person-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [ratesByGrade]="rates.ratesByGrade()" [grades]="p"
                               [periods]="periods.list()" [holds]="p.holds" [others]="othersOf(p.id)"
                               [selected]="selectedClaims()" (selectToggled)="toggleSelected($event)"
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
  protected readonly people = signal<PersonView[]>([]);
  /** The people listed: retired people only while "show retired" is on. Totals still count everyone. */
  protected readonly listed = computed(() =>
    this.api.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The person being retired, so a second press waits for the first. */
  protected readonly retiring = signal<number | null>(null);
  /** The person a hold is being added to, so a second press waits for the first. */
  protected readonly holding = signal<number | null>(null);
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<ClaimView[]>([]);
  /** The ids of the claims selected for a bulk action. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** What the last bulk open did, until the button is pressed again. */
  protected readonly bulkOpenResult = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal<string | null>(null);
  /** Whether a bulk open is running, so a second press waits for the first. */
  protected readonly bulkOpening = signal(false);
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
  /** For each person, the other people on the sheet their claims can be moved to. */
  private readonly othersByPerson = computed(() => {
    const people = this.people();
    return new Map(people.map((p) => [p.id, people.filter((q) => q.id !== p.id)]));
  });
  /** Whether each claim counts in the live totals: a person's open claims dated within one of their holds,
   *  and not bound by a statement, do not. */
  private readonly countsNow = computed(() => {
    const byPerson = new Map(this.people().map((p) => [p.id, countsWithHolds(p.holds)]));
    return (c: ClaimView) => (byPerson.get(c.personId) ?? countsWithHolds([]))(c);
  });
  protected readonly claimedTotal = computed(() =>
    money(this.claims().filter(this.countsNow()).reduce((sum, c) => sum + cents(c.amount), 0)),
  );
  /** Each person's due-total in whole cents: the sum of the dues of their claims that count (0 with none),
   *  at most the sheet's cap; null when neither their grade in force today nor the grade any of those claims is at has a rate in
   *  force today, and none of those claims has a due. */
  private readonly dueByPerson = computed(() => {
    const cap = this.capCents();
    return new Map([...this.uncappedDueByPerson()].map(([id, due]) =>
      [id, due === null || cap === null ? due : Math.min(due, cap)]));
  });
  /** The sheet's cap in whole cents; null with none. */
  private readonly capCents = computed(() => {
    const cap = this.sheets.current()?.cap;
    return cap === null || cap === undefined ? null : cents(cap);
  });
  /** Each person's due-total before the cap, in whole cents; null as for the due-total. */
  private readonly uncappedDueByPerson = computed(() => {
    const today = this.rates.byGrade();
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, number | null>();
    for (const p of this.people()) {
      const counting = this.claimsOf(p.id).filter(countsWithHolds(p.holds));
      const grades = [this.gradeToday(p), ...counting.map((c) => gradeOn(p, c.date))];
      const dues = counting.map((c) => this.claimDue(p, c, dueTerms)).filter((due) => due !== null);
      if (!grades.some((g) => today.has(g)) && dues.length === 0) {
        byPerson.set(p.id, null);
        continue;
      }
      byPerson.set(p.id, dues.reduce((sum, due) => sum + due, 0));
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
    const dueTerms = sheet ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, Overdue>();
    for (const p of this.people()) {
      const overdue = sheet
        ? this.claimsOf(p.id).filter((c) => countsWithHolds(p.holds)(c) && isOverdue(c, sheet.today))
        : NO_CLAIMS;
      byPerson.set(p.id, {
        count: overdue.length,
        cents: overdue.reduce((sum, c) => sum + (this.claimDue(p, c, dueTerms) ?? 0), 0),
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
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const personOf = new Map(this.people().map((p) => [p.id, p]));
    const totals = new Map<number, number>(periods.map((p) => [p.id, 0]));
    for (const c of this.claims().filter(this.countsNow())) {
      const period = periodOn(periods, c.date);
      const person = personOf.get(c.personId);
      if (!period || !person) continue;
      totals.set(period.id, (totals.get(period.id) ?? 0) + (this.claimDue(person, c, dueTerms) ?? 0));
    }
    const closed = new Map(periods.filter((p) => p.closedTotal !== null).map((p) => [p.id, p.closedTotal as string]));
    return new Map([...totals].map(([id, total]) => [id, closed.get(id) ?? money(total)]));
  });
  /** Per grade: the people not retired on it today and their due-totals added up, skipping those with none. */
  protected readonly summaryByGrade = computed<GradeSummary[]>(() => {
    const due = this.dueByPerson();
    const current = this.people().filter((p) => !p.retired);
    return GRADES.map((grade) => {
      const onGrade = current.filter((p) => this.gradeToday(p) === grade);
      return {
        grade,
        count: onGrade.length,
        dueTotal: money(onGrade.reduce((sum, p) => sum + (due.get(p.id) ?? 0), 0)),
      };
    });
  });
  /** Per period: its due-total, as its row shows it. */
  protected readonly summaryByPeriod = computed<PeriodSummary[]>(() => {
    const due = this.dueByPeriod();
    return this.periods.list().map((p) => ({ name: p.name, dueTotal: due.get(p.id) ?? '0.00' }));
  });
  /** Per status: how many of the sheet's claims are in it. */
  protected readonly summaryByStatus = computed<StatusSummary[]>(() => {
    const claims = this.claims();
    return SUMMARY_STATUSES.map((status) => ({ status, count: claims.filter((c) => c.status === status).length }));
  });
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion = 0;
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    /** While editing: the date the grade is from (yyyy-mm-dd); blank takes the sheet's today. */
    gradeFrom: new FormControl('', { nonNullable: true }),
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
          this.selectedClaims.set(new Set());
          this.bulkOpenResult.set(null);
          this.bulkOpenError.set(null);
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

  /** The person's grade in force on the sheet's today. */
  protected gradeToday(p: PersonView): Grade {
    return gradeOn(p, this.sheets.current()?.today ?? '');
  }

  /** A claim's due in whole cents, at the rates of its person's grade in force on its date; null with none. */
  private claimDue(p: PersonView, c: ClaimView, dueTerms: DueTerms): number | null {
    return claimDueCents(c, this.rates.ratesByGrade().get(gradeOn(p, c.date)) ?? NO_RATES, dueTerms,
      roundingDate(c, this.claimsOf(p.id)));
  }

  protected dueOf(p: PersonView): string {
    const due = this.dueByPerson().get(p.id);
    return due === null || due === undefined ? NOT_AVAILABLE : money(due);
  }

  /** How far the person's figures add up above the cap, in whole cents; 0 when they do not, or have no due-total. */
  protected excessOf(personId: number): number {
    const sum = this.uncappedDueByPerson().get(personId) ?? null;
    const due = this.dueByPerson().get(personId) ?? null;
    return sum === null || due === null ? 0 : sum - due;
  }

  /** Download the people as CSV, in list order, retired people included. */
  protected exportCsv(): void {
    const lines = [
      ['name', 'grade', 'retired-from', 'due-total'],
      ...this.people().map((p) => [p.name, this.gradeToday(p), p.retiredFrom ?? '', this.dueOf(p)]),
    ].map((fields) => fields.map(csvField).join(',') + '\n');
    const url = URL.createObjectURL(new Blob(lines, { type: 'text/csv' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'people-' + (this.sheets.current()?.id ?? 'sheet') + '.csv';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
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

  /** Put the person on hold over the dates given: a blank from-date takes the sheet's today, a blank to-date has no end. */
  protected hold(p: PersonView, from: string, to: string): void {
    if (this.holding() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.holding.set(p.id);
    this.api.hold(sheet.id, p.id, from === '' ? null : from, to === '' ? null : to).subscribe({
      next: (saved) => {
        this.holding.set(null);
        this.people.update((list) => list.map((q) => (q.id === saved.id ? saved : q)));
      },
      error: () => {
        this.holding.set(null);
        this.personError.set({ id: p.id, message: 'Could not hold ' + p.name + ' over those dates.' });
      },
    });
  }

  /** Clear the error shown on a person's row once its form is changed. */
  protected clearPersonError(id: number): void {
    if (this.personError()?.id === id) this.personError.set(null);
  }

  protected toggleSelected({ id, selected }: { id: number; selected: boolean }): void {
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (selected) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Open the selected claims together: each that can be is opened, numbered in list order; the rest are left as they are. */
  protected bulkOpen(): void {
    if (this.bulkOpening()) return;
    this.bulkOpenResult.set(null);
    this.bulkOpenError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.bulkOpening.set(true);
    this.claimsApi.bulkOpen(sheet.id, [...this.selectedClaims()]).subscribe({
      next: ({ opened, refused, claims }) => {
        this.bulkOpening.set(false);
        claims.forEach((c) => this.claimSaved(c));
        this.selectedClaims.set(new Set());
        this.bulkOpenResult.set({ opened, refused });
      },
      error: () => {
        this.bulkOpening.set(false);
        this.bulkOpenError.set('Could not open the selected claims.');
      },
    });
  }

  /** A claim as saved: a new one, or one moved to another person, is listed first (its person's newest). */
  protected claimSaved(saved: ClaimView): void {
    this.claims.update((list) => {
      const was = list.find((c) => c.id === saved.id);
      return was && was.personId === saved.personId
        ? list.map((c) => (c.id === saved.id ? saved : c))
        : [saved, ...list.filter((c) => c.id !== saved.id)];
    });
  }

  /** The other people on the sheet a person's claims can be moved to. */
  protected othersOf(personId: number): PersonView[] {
    return this.othersByPerson().get(personId) ?? [];
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
    this.form.setValue({ name: p.name, grade: this.gradeToday(p), gradeFrom: '' }, { emitEvent: false });
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A', gradeFrom: '' }, { emitEvent: false });
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
    const request = id === null
      ? this.api.add(sheet.id, { name: value.name, grade: value.grade })
      : this.api.update(sheet.id, id, this.editingVersion,
          { name: value.name, grade: value.grade, gradeFrom: value.gradeFrom === '' ? null : value.gradeFrom });
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
          this.form.setValue({ name: current.name, grade: this.gradeToday(current), gradeFrom: '' }, { emitEvent: false });
        }
      },
    });
  }
}
