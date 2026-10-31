import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin, of, switchMap } from 'rxjs';
import { AMOUNT_PATTERN, ClaimView, Claims, cents, countsAtEndWithHolds, countsWithHolds, isOverdue, money, roundingDate } from '../../core/claims';
import { GRADES, Grade, People, PersonView, gradeOn } from '../../core/people';
import { PatronView, Patrons } from '../../core/patrons';
import { Periods, periodOn } from '../../core/periods';
import { DEFAULT_DUE_TERMS, DueTerms, NOT_AVAILABLE, RateView, Rates, claimDueCents } from '../../core/rates';
import { Sheets } from '../../core/sheet';
import { StatementSummary, Statements } from '../../core/statements';
import { Watches } from '../../core/watches';
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

/** A due-total split in whole cents: the dues of the SETTLED claims that count, and of the OPEN and PENDING ones. */
interface Split {
  settled: number;
  open: number;
}
const NO_SPLIT: Split = { settled: 0, open: 0 };

/** The dues in whole cents of a person's OPEN and PENDING claims that count, by the claim's age on the sheet's
 *  today: 0 to 30 days (a claim dated after today included), 31 to 60, and 61 or more. */
interface Aged {
  upTo30: number;
  upTo60: number;
  over60: number;
}
const NO_AGED: Aged = { upTo30: 0, upTo60: 0, over60: 0 };
/** A person's terms the form accepts: a whole number of days, 0 or more. */
const TERMS_PATTERN = /^\d{1,5}$/;

/** A person's own terms as the form shows them: blank for none. */
function termsText(p: PersonView): string {
  return p.terms === null ? '' : String(p.terms);
}

/** A CSV field: wrapped in double quotes, with any quote inside doubled, when it holds a comma, a quote or a line break. */
/** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
function trimmedSum(figures: number[]): number {
  const sum = figures.reduce((total, f) => total + f, 0);
  return figures.length < 3 ? sum : sum - Math.max(...figures) - Math.min(...figures);
}

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
      <p>Settled in total: <strong data-testid="rollup-settled-total">{{ money(splitTotals().settled) }}</strong>,
        open in total: <strong data-testid="rollup-open-total">{{ money(splitTotals().open) }}</strong></p>

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
        <label for="person-form-terms">Terms (days; blank: none of their own)</label>
        <input id="person-form-terms" data-testid="person-form-terms" formControlName="terms" inputmode="numeric" autocomplete="off" />
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

      <form [formGroup]="pasteForm" (ngSubmit)="paste()" aria-label="Paste claims">
        <label for="paste-claims">Paste claims, one per line as name,amount,date,note</label>
        <textarea id="paste-claims" data-testid="paste-claims" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="paste-submit" [disabled]="pasting()">Add pasted claims</button>
        @if (pasteAdded() !== null) {
          Added <span data-testid="paste-added">{{ pasteAdded() }}</span>
        }
        @if (pasteError(); as err) {
          <p role="alert" data-testid="paste-error">{{ err }}</p>
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

      <p>
        <button type="button" data-testid="bulk-settle" (click)="bulkSettle()" [disabled]="bulkSettling()">Settle selected claims</button>
        @if (bulkSettleResult(); as r) {
          Settled <span data-testid="bulk-settled">{{ r.settled }}</span>,
          refused <span data-testid="bulk-settle-refused">{{ r.refused }}</span>
        }
        @if (bulkSettleError(); as err) {
          <span role="alert" data-testid="bulk-settle-error">{{ err }}</span>
        }
      </p>

      <form [formGroup]="bulkGradeForm" (ngSubmit)="bulkGrade()" aria-label="Grade the selected people">
        <label for="bulk-grade">Grade</label>
        <select id="bulk-grade" data-testid="bulk-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">from</label>
        <input id="bulk-grade-from" type="date" data-testid="bulk-grade-from" formControlName="from" />
        <button type="submit" data-testid="bulk-grade-apply" [disabled]="bulkGrading()">Grade selected people</button>
        @if (bulkGraded() !== null) {
          Changed <span data-testid="bulk-graded">{{ bulkGraded() }}</span>
        }
      </form>

      <ul data-testid="person-list">
        @for (p of listed(); track p.id) {
          <li [attr.data-testid]="'person-row-' + p.id">
            <input type="checkbox" data-testid="person-select" [checked]="selectedPeople().has(p.id)"
                   (change)="togglePerson(p.id, $any($event.target).checked)" [attr.aria-label]="'Select ' + p.name" />
            <span data-testid="person-name">{{ p.name }}</span>
            — grade <span data-testid="person-grade">{{ gradeToday(p) }}</span>
            — terms <span data-testid="person-terms">{{ p.terms ?? '' }}</span>
            — due <span data-testid="person-due-total">{{ dueOf(p) }}</span>
            @for (pd of periods.list(); track pd.id) {
              — {{ pd.name }} due <span [attr.data-testid]="'person-period-' + pd.name + '-due-total'">{{ periodDueOf(p.id, pd.id) }}</span>
            }
            settled <span data-testid="person-settled-total">{{ money(splitOf(p.id).settled) }}</span>
            open <span data-testid="person-open-total">{{ money(splitOf(p.id).open) }}</span>
            excess <span data-testid="person-excess">{{ money(excessOf(p.id)) }}</span>
            — overdue <span data-testid="person-overdue-count">{{ overdueOf(p.id).count }}</span>
            due <span data-testid="person-overdue-total">{{ money(overdueOf(p.id).cents) }}</span>
            — aged 0–30 <span data-testid="person-aged-0-30">{{ money(agedOf(p.id).upTo30) }}</span>
            31–60 <span data-testid="person-aged-31-60">{{ money(agedOf(p.id).upTo60) }}</span>
            61+ <span data-testid="person-aged-61">{{ money(agedOf(p.id).over60) }}</span>
            — statements <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
            — last stated <span data-testid="person-stated-total">{{ statementOf(p.id)?.dueTotal ?? '' }}</span>
            on <span data-testid="person-statement-date">{{ statementOf(p.id)?.date ?? '' }}</span>
            — retired <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
            from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span>
            — on hold <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
            — active <span data-testid="person-active">{{ activeByPerson().get(p.id) ? 'yes' : 'no' }}</span>
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
            <label [attr.for]="'person-statement-asof-' + p.id">Statement as of</label>
            <input [id]="'person-statement-asof-' + p.id" type="date" data-testid="person-statement-asof" #statementAsOf
                   (input)="clearPersonError(p.id)" />
            <button type="button" data-testid="person-statement" (click)="takeStatement(p, statementAsOf)" [disabled]="stating() === p.id"
                    [attr.aria-label]="'Take a statement of ' + p.name">Statement</button>
            <button type="button" data-testid="person-unstate" (click)="cancelStatement(p)" [disabled]="stating() === p.id"
                    [attr.aria-label]="'Cancel the last statement of ' + p.name">Cancel statement</button>
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
            — watched <span data-testid="person-watched">{{ watched().has(p.id) ? 'yes' : 'no' }}</span>
            <button type="button" data-testid="person-watch" (click)="toggleWatch(p)" [disabled]="watching() === p.id"
                    [attr.aria-pressed]="watched().has(p.id)" [attr.aria-label]="'Watch ' + p.name">Watch</button>
            @if (patronError()?.id === p.id) {
              <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
            }
            @if (personError()?.id === p.id) {
              <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
            }
            <app-person-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [ratesByGrade]="rates.ratesByGrade()" [grades]="p"
                               [periods]="periods.list()" [holds]="p.holds" [retiredFrom]="p.retiredFrom" [others]="othersOf(p.id)"
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
  private readonly watchesApi = inject(Watches);
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
  /** What the last bulk settle did, until the button is pressed again. */
  protected readonly bulkSettleResult = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal<string | null>(null);
  /** Whether a bulk settle is running, so a second press waits for the first. */
  protected readonly bulkSettling = signal(false);
  /** The ids of the people selected for a bulk action. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  /** How many people the last bulk grade changed; null while one runs or before any. */
  protected readonly bulkGraded = signal<number | null>(null);
  /** Whether a bulk grade is running, so a second press waits for the first. */
  protected readonly bulkGrading = signal(false);
  /** The grade to give the selected people, and the date it is from (blank takes the sheet's today). */
  protected readonly bulkGradeForm = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
  });
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
  /** The ids of the people the signed-in user watches. */
  protected readonly watched = signal<ReadonlySet<number>>(new Set());
  /** The person whose watch is being changed, so a second press waits for the first. */
  protected readonly watching = signal<number | null>(null);
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
  /** The sheet's today ('' before a sheet is loaded). */
  private readonly todayNow = computed(() => this.sheets.current()?.today ?? '');
  /** Whether each claim counts in the live totals: a deferred claim does not, nor do a person's open claims
   *  dated within one of their holds, or all of them once their retirement date is reached, when not bound by a
   *  statement. */
  private readonly countsNow = computed(() => {
    const today = this.todayNow();
    const byPerson = new Map(this.people().map((p) => [p.id, countsWithHolds(p.holds, today, p.retiredFrom)]));
    return (c: ClaimView) => (byPerson.get(c.personId) ?? countsWithHolds([], today))(c);
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
      const counting = this.claimsOf(p.id).filter(countsWithHolds(p.holds, this.todayNow(), p.retiredFrom));
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
  /** Each person's due-total before the cap split by status, in whole cents: the dues of their SETTLED claims that
   *  count (at their frozen figures, corrections included) and of their OPEN and PENDING claims that count. */
  private readonly splitByPerson = computed(() => {
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, Split>();
    for (const p of this.people()) {
      const split = { ...NO_SPLIT };
      for (const c of this.claimsOf(p.id).filter(countsWithHolds(p.holds, this.todayNow(), p.retiredFrom))) {
        const due = this.claimDue(p, c, dueTerms) ?? 0;
        if (c.status === 'SETTLED') split.settled += due;
        else if (c.status === 'OPEN' || c.status === 'PENDING') split.open += due;
      }
      byPerson.set(p.id, split);
    }
    return byPerson;
  });
  /** Everyone's settled-total and open-total, each simply added up (not trimmed). */
  protected readonly splitTotals = computed(() => {
    const total = { ...NO_SPLIT };
    for (const split of this.splitByPerson().values()) {
      total.settled += split.settled;
      total.open += split.open;
    }
    return total;
  });
  /** Everyone's due-total added up, skipping those with none, trimmed of the highest and lowest. */
  protected readonly dueTotal = computed(() =>
    money(trimmedSum([...this.dueByPerson().values()].filter((due) => due !== null))),
  );
  /** Each person's overdue claims that count: how many, and their dues added up in whole cents (none counting as 0). */
  private readonly overdueByPerson = computed(() => {
    const sheet = this.sheets.current();
    const dueTerms = sheet ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, Overdue>();
    for (const p of this.people()) {
      const overdue = sheet
        ? this.claimsOf(p.id).filter((c) => countsWithHolds(p.holds, sheet.today, p.retiredFrom)(c) && isOverdue(c, sheet.today))
        : NO_CLAIMS;
      byPerson.set(p.id, {
        count: overdue.length,
        cents: overdue.reduce((sum, c) => sum + (this.claimDue(p, c, dueTerms) ?? 0), 0),
      });
    }
    return byPerson;
  });
  /** Each person's aged dues: their OPEN and PENDING claims that count, by age in days on the sheet's today. */
  private readonly agedByPerson = computed(() => {
    const today = this.todayNow();
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, Aged>();
    for (const p of this.people()) {
      const aged = { ...NO_AGED };
      if (today) {
        for (const c of this.claimsOf(p.id).filter(countsWithHolds(p.holds, today, p.retiredFrom))) {
          if (c.status !== 'OPEN' && c.status !== 'PENDING') continue;
          const due = this.claimDue(p, c, dueTerms) ?? 0;
          const age = Math.round((Date.parse(today) - Date.parse(c.date)) / 86_400_000);
          if (age <= 30) aged.upTo30 += due;
          else if (age <= 60) aged.upTo60 += due;
          else aged.over60 += due;
        }
      }
      byPerson.set(p.id, aged);
    }
    return byPerson;
  });
  /** Everyone's overdue claims added up: the count of them all, the dues trimmed of the highest and lowest person's. */
  protected readonly overdueTotals = computed(() => {
    const overdue = [...this.overdueByPerson().values()];
    return {
      count: overdue.reduce((sum, o) => sum + o.count, 0),
      cents: trimmedSum(overdue.map((o) => o.cents)),
    };
  });
  /** Each person's due-total for each period, in whole cents, by person id then period id: the dues as shown now
   *  of their claims whose due-by falls in it and that counted by the status they had at the end of its last day
   *  (none counting as 0); a closed or soft-closed period keeps the figures fixed when it was closed, 0 for a person added since. */
  private readonly dueByPersonPeriod = computed(() => {
    const periods = this.periods.list();
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, Map<number, number>>();
    for (const p of this.people()) {
      const live = new Map<number, number>(periods.map((pd) => [pd.id, 0]));
      for (const c of this.claimsOf(p.id)) {
        const period = periodOn(periods, c.dueBy);
        if (period && countsAtEndWithHolds(p.holds, this.todayNow(), period.to, p.retiredFrom)(c)) {
          live.set(period.id, (live.get(period.id) ?? 0) + (this.claimDue(p, c, dueTerms) ?? 0));
        }
      }
      for (const pd of periods) {
        if (pd.closed || pd.softClosed) live.set(pd.id, cents(pd.closedPersonTotals[String(p.id)] ?? '0.00'));
      }
      byPerson.set(p.id, live);
    }
    return byPerson;
  });
  /** Each period's due-total, by period id: the sum of the people's figures for it. */
  protected readonly dueByPeriod = computed(() => {
    const totals = new Map<number, number>(this.periods.list().map((pd) => [pd.id, 0]));
    for (const figures of this.dueByPersonPeriod().values()) {
      for (const [id, due] of figures) totals.set(id, (totals.get(id) ?? 0) + due);
    }
    return new Map([...totals].map(([id, total]) => [id, money(total)]));
  });
  /** Whether each person is active, by person id: they have an OPEN or PENDING claim that counts dated within the
   *  30 days up to and including the sheet's today. */
  protected readonly activeByPerson = computed(() => {
    const today = this.todayNow();
    const from = today ? new Date(Date.parse(today) - 29 * 86_400_000).toISOString().slice(0, 10) : '';
    return new Map(this.people().map((p) => [p.id, today !== '' && this.claimsOf(p.id).some((c) =>
      (c.status === 'OPEN' || c.status === 'PENDING') && countsWithHolds(p.holds, today, p.retiredFrom)(c)
      && from <= c.date && c.date <= today)]));
  });
  /** Per grade: the people not retired on it today, their due-totals added up (skipping those with none), and how
   *  many of them are active. */
  protected readonly summaryByGrade = computed<GradeSummary[]>(() => {
    const due = this.dueByPerson();
    const active = this.activeByPerson();
    const current = this.people().filter((p) => !p.retired);
    return GRADES.map((grade) => {
      const onGrade = current.filter((p) => this.gradeToday(p) === grade);
      return {
        grade,
        count: onGrade.length,
        dueTotal: money(onGrade.reduce((sum, p) => sum + (due.get(p.id) ?? 0), 0)),
        active: onGrade.filter((p) => active.get(p.id)).length,
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
  /** A person's due-total for a period, with two decimals. */
  protected periodDueOf(personId: number, periodId: number): string {
    return money(this.dueByPersonPeriod().get(personId)?.get(periodId) ?? 0);
  }
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
    /** The person's own terms in days; blank for none. */
    terms: new FormControl('', { nonNullable: true }),
  });

  protected readonly quickError = signal<string | null>(null);
  protected readonly quickSaving = signal(false);

  protected readonly quickForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });

  protected readonly pasteError = signal<string | null>(null);
  protected readonly pasting = signal(false);
  /** How many claims the last paste added; null while one runs or before any. */
  protected readonly pasteAdded = signal<number | null>(null);

  protected readonly pasteForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its people from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.people.set([]);
          this.claims.set([]);
          this.selectedClaims.set(new Set());
          this.selectedPeople.set(new Set());
          this.bulkGraded.set(null);
          this.bulkGradeForm.reset({ grade: 'A', from: '' }, { emitEvent: false });
          this.bulkOpenResult.set(null);
          this.bulkOpenError.set(null);
          this.bulkSettleResult.set(null);
          this.bulkSettleError.set(null);
          this.statements.set(new Map());
          this.patrons.set([]);
          this.watched.set(new Set());
          this.personError.set(null);
          this.patronError.set(null);
          this.reset();
          this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
          this.quickError.set(null);
          this.pasteForm.reset({ text: '' }, { emitEvent: false });
          this.pasteError.set(null);
          this.pasteAdded.set(null);
          return sheet
            ? forkJoin({
                people: this.api.list(sheet.id),
                claims: this.claimsApi.list(sheet.id),
                statements: this.statementsApi.list(sheet.id),
                patrons: this.patronsApi.list(sheet.id),
                watched: this.watchesApi.list(sheet.id),
              })
            : of({ people: [], claims: [], statements: [], patrons: [], watched: [] });
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ people, claims, statements, patrons, watched }) => {
          this.patrons.set(patrons);
          this.watched.set(new Set(watched));
          this.people.set(people);
          this.claims.set(claims);
          this.statements.set(new Map(statements.map((s) => [s.personId, s])));
        },
      });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
    this.quickForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.quickError.set(null));
    this.pasteForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.pasteError.set(null));
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

  protected splitOf(personId: number): Split {
    return this.splitByPerson().get(personId) ?? NO_SPLIT;
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

  /** The person's backing: their patrons' settled figures over their SETTLED claims dated within the 90 days up to and
   *  including the sheet's today, added up; 0.00 with none. */
  protected backingOf(personId: number): string {
    const today = this.todayNow();
    if (today === '') return money(0);
    const from = new Date(Date.parse(today) - 89 * 86_400_000).toISOString().slice(0, 10);
    return money((this.patronIdsByPerson().get(personId) ?? []).reduce((sum, id) => sum + this.claimsOf(id)
      .filter((c) => c.status === 'SETTLED' && c.settledDue !== null && from <= c.date && c.date <= today)
      .reduce((s, c) => s + cents(c.settledDue ?? '0'), 0), 0));
  }

  /** Start watching the person, or stop when the signed-in user already watches them. */
  protected toggleWatch(p: PersonView): void {
    if (this.watching() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.watching.set(p.id);
    this.watchesApi.set(sheet.id, p.id, !this.watched().has(p.id)).subscribe({
      next: ({ personId, watched }) => {
        this.watching.set(null);
        this.watched.update((set) => {
          const next = new Set(set);
          if (watched) next.add(personId);
          else next.delete(personId);
          return next;
        });
      },
      error: () => {
        this.watching.set(null);
        this.personError.set({ id: p.id, message: 'Could not change whether you watch ' + p.name + '.' });
      },
    });
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

  protected agedOf(personId: number): Aged {
    return this.agedByPerson().get(personId) ?? NO_AGED;
  }

  protected overdueOf(personId: number): Overdue {
    return this.overdueByPerson().get(personId) ?? NO_OVERDUE;
  }

  protected statementOf(personId: number): StatementSummary | undefined {
    return this.statements().get(personId);
  }

  /** Take a statement of the person, as of the date in its entry (blank: now), then show their summary and their
   *  claims as it left them; the entry is cleared once it is taken or refused. */
  protected takeStatement(p: PersonView, asOf: HTMLInputElement): void {
    if (this.stating() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.stating.set(p.id);
    this.statementsApi.take(sheet.id, p.id, asOf.value || null).subscribe({
      next: ({ summary, claims }) => {
        this.stating.set(null);
        asOf.value = '';
        this.statements.update((map) => new Map(map).set(summary.personId, summary));
        const byId = new Map(claims.map((c) => [c.id, c]));
        this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      },
      error: () => {
        this.stating.set(null);
        asOf.value = '';
        this.personError.set({ id: p.id, message: 'Could not take a statement of ' + p.name + '.' });
      },
    });
  }

  /** Cancel the person's last statement, then show the statement before it and their claims live again. */
  protected cancelStatement(p: PersonView): void {
    if (this.stating() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.stating.set(p.id);
    this.statementsApi.cancel(sheet.id, p.id).subscribe({
      next: ({ summary, claims }) => {
        this.stating.set(null);
        this.statements.update((map) => {
          const next = new Map(map);
          if (summary.count === 0) next.delete(summary.personId);
          else next.set(summary.personId, summary);
          return next;
        });
        const byId = new Map(claims.map((c) => [c.id, c]));
        this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      },
      error: () => {
        this.stating.set(null);
        this.personError.set({ id: p.id, message: 'Could not cancel the last statement of ' + p.name + '.' });
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

  protected togglePerson(id: number, selected: boolean): void {
    this.selectedPeople.update((ids) => {
      const next = new Set(ids);
      if (selected) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Give the selected people the chosen grade together, in list order, each as an edit in the person form would. */
  protected bulkGrade(): void {
    if (this.bulkGrading()) return;
    this.bulkGraded.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const ids = this.people().map((p) => p.id).filter((id) => this.selectedPeople().has(id));
    const first = ids[0];
    if (first !== undefined) this.clearPersonError(first);
    const { grade, from } = this.bulkGradeForm.getRawValue();
    this.bulkGrading.set(true);
    this.api.bulkGrade(sheet.id, ids, grade, from === '' ? null : from).subscribe({
      next: ({ changed, people }) => {
        this.bulkGrading.set(false);
        const byId = new Map(people.map((p) => [p.id, p]));
        this.people.update((list) => list.map((p) => byId.get(p.id) ?? p));
        this.selectedPeople.set(new Set());
        this.bulkGraded.set(changed);
      },
      error: () => {
        this.bulkGrading.set(false);
        // The refusal shows on the first selected person's row.
        if (first !== undefined) this.personError.set({ id: first, message: 'Could not grade the selected people.' });
      },
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

  /** Settle the selected claims together, in list order, each by the rules of settling one; the rest are left as they are. */
  protected bulkSettle(): void {
    if (this.bulkSettling()) return;
    this.bulkSettleResult.set(null);
    this.bulkSettleError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.bulkSettling.set(true);
    this.claimsApi.bulkSettle(sheet.id, [...this.selectedClaims()]).subscribe({
      next: ({ settled, refused, claims }) => {
        this.bulkSettling.set(false);
        claims.forEach((c) => this.claimSaved(c));
        this.selectedClaims.set(new Set());
        this.bulkSettleResult.set({ settled, refused });
      },
      error: () => {
        this.bulkSettling.set(false);
        this.bulkSettleError.set('Could not settle the selected claims.');
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
            ? 'That claim is due by inside a closed period.'
            : err instanceof HttpErrorResponse && err.status === 422
              ? 'That claim is dated after the person retired.'
            : err instanceof HttpErrorResponse && err.status === 409
              ? 'That name is already in use on this sheet.'
              : 'Could not add that claim.',
        );
      },
    });
  }

  /** Add a DRAFT claim per pasted line for people on the sheet; when any line is refused, none is added. */
  protected paste(): void {
    if (this.pasting()) return;
    this.pasteError.set(null);
    this.pasteAdded.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const text = this.pasteForm.getRawValue().text;
    if (text.trim() === '') {
      this.pasteError.set('Paste at least one line of name,amount,date,note.');
      return;
    }
    this.pasting.set(true);
    this.claimsApi.paste(sheet.id, text).subscribe({
      next: ({ added, claims }) => {
        this.pasting.set(false);
        claims.forEach((c) => this.claimSaved(c));
        this.pasteAdded.set(added);
      },
      error: (err: unknown) => {
        this.pasting.set(false);
        this.pasteError.set(
          err instanceof HttpErrorResponse && err.status === 423
            ? 'A pasted claim is due by inside a closed period; nothing was added.'
            : err instanceof HttpErrorResponse && err.status === 422
              ? 'A pasted claim is dated after its person retired; nothing was added.'
              : 'A pasted line is not a valid claim for someone on this sheet; nothing was added.',
        );
      },
    });
  }

  protected edit(p: PersonView): void {
    this.personError.set(null);
    this.patronError.set(null);
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.form.setValue({ name: p.name, grade: this.gradeToday(p), gradeFrom: '', terms: termsText(p) }, { emitEvent: false });
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A', gradeFrom: '', terms: '' }, { emitEvent: false });
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
    const termsValue = value.terms.trim();
    if (termsValue !== '' && !TERMS_PATTERN.test(termsValue)) {
      // Refused terms close the editor, saving nothing, so the form is ready for the next person.
      if (this.editingId() !== null) this.reset();
      this.error.set('Type terms of 0 or more whole days, or leave them blank.');
      return;
    }
    const terms = termsValue === '' ? null : Number(termsValue);
    const id = this.editingId();
    this.saving.set(true);
    const request = id === null
      ? this.api.add(sheet.id, { name: value.name, grade: value.grade, terms })
      : this.api.update(sheet.id, id, this.editingVersion,
          { name: value.name, grade: value.grade, gradeFrom: value.gradeFrom === '' ? null : value.gradeFrom, terms });
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
          this.form.setValue({ name: current.name, grade: this.gradeToday(current), gradeFrom: '', terms: termsText(current) },
            { emitEvent: false });
        }
      },
    });
  }
}
