import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, Claims, PctOn, RETIRED, cents, claimDueCents, countsAt, countsFor, isBound, isOverdue, money, roundsOn } from '../claims/claims';
import { Period, Periods, periodOn } from '../periods/periods';
import { GRADES, NA, Rate, Rates, pctOn, rateOn } from '../rates/rates';

const NO_CLAIMS: Claim[] = [];
const NO_RATES: Rate[] = [];
const NO_PCT: PctOn = () => null;
/** The statuses the summary counts claims by. */
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'];

/** A grade a person was given, in force from a date until the next one; from is null from the start. */
interface GradeFrom {
  from: string | null;
  grade: string;
}

/** A hold from a date (null: from the start) to a date (null: open-ended), both included. */
interface Hold {
  from: string | null;
  to: string | null;
}

/** Whether the date falls within the hold. */
function holdContains(h: Hold, date: string): boolean {
  return (h.from === null || h.from <= date) && (h.to === null || date <= h.to);
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
  /** Whether they have any hold, whatever its dates. */
  held: boolean;
  /** Their holds, earliest first; their open claims dated within one, unless bound by a statement, count in no live total. */
  holds: Hold[];
  /** The other people on the sheet they have named as patrons, in the order named. */
  patronIds: number[];
  /** Whether the signed-in user watches them. */
  watched: boolean;
  /** Their own terms in days, ahead of their grade's and the sheet's; null: none. */
  terms: number | null;
  /** Whether they have an open claim (OPEN or PENDING) that counts, dated within the 30 days up to and including the sheet's today. */
  active: boolean;
}

/** The person's grade in force on the date: the one with the latest from-date on or before it. */
function gradeOn(p: Person, date: string): string {
  let on = p.grade;
  for (const g of p.grades) if (g.from === null || g.from <= date) on = g.grade;
  return on;
}

/** A CSV field: wrapped in double quotes, any inside doubled, when it holds a comma, a quote or a line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
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
// each with their claims, what they have claimed and their due-total (OPEN, PENDING and SETTLED claims only); the sheet's claimed-total and
// due-total under the table. Above it, the sheet's rate for each grade. Each person can be given a
// statement, which binds their counting claims' dues; the row shows how many statements they have
// had and the last one's due-total and date. Each person can be retired from a date (blank: the sheet's
// today); retired people are left out of the list unless show retired is on, but their claims
// keep counting in every total. Each person can be put on hold from a from-date (blank: the sheet's
// today) to an optional to-date, any number of times; their open (OPEN or PENDING) claims dated within
// a hold count in no live total (theirs, the sheet's or a period's), unless bound by a statement.
// Each person, and the sheet, shows how many of the claims that count are overdue and their dues' total.
// A person's grade is dated: the first one given is in force from the start; each save records the
// form's grade from its from-date (blank: the sheet's today), unless it leaves the grade as the row
// showed it with the from-date blank. A claim takes the rate of the grade in force on its date; the
// row shows the grade in force on the sheet's today. A person may have their own terms in days (blank:
// none); a claim's due-by is fixed when it is added from them, else their grade's, else the sheet's.
// Each person can name other people on the sheet as their patrons, one at a time (not themselves, nor
// the same person twice); the row shows their patrons' names, sorted, and their backing: their
// patrons' settled figures added up over their SETTLED claims dated within the 90 days up to and
// including the sheet's today, each at its frozen figure (0.00 with none).
// A person's due-total is at most the sheet's cap (blank: none); the row shows their excess, how far the
// sum of their figures is above it (0.00 when not, or n/a). Every total of due-totals adds the capped ones.
// Under it, a summary: per grade, the people on it today (retired people excluded) and their
// due-totals added up; per period, its due-total; per status, how many claims have it.
// Above everything, a header of counters repeating those figures: the people on the sheet (retired
// people included), the claims per status, and the sheet's due-total and overdue-total.
// The sheet's due-total and overdue-total leave out the single highest and the single lowest person's
// figure when three or more people have one (an n/a due-total is no figure).
/** Figures in cents added up, leaving out the single highest and the single lowest when there are three or more. */
function trimmedSum(figures: number[]): number {
  const sum = figures.reduce((total, c) => total + c, 0);
  return figures.length < 3 ? sum : sum - Math.max(...figures) - Math.min(...figures);
}

@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, Claims, Periods, Rates],
  template: `
    <header aria-label="Sheet counters">
      <p>People: <strong data-testid="header-people-count">{{ people().length }}</strong></p>
      @for (s of statusSummary(); track s.status) {
        <p>{{ s.status }}: <strong [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</strong></p>
      }
      <p>Due total: <strong data-testid="header-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue total: <strong data-testid="header-overdue-total">{{ money(overdueTotal().cents) }}</strong></p>
    </header>
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
        <label for="person-form-terms">Terms (days, blank: none)</label>
        <input id="person-form-terms" data-testid="person-form-terms" formControlName="terms" inputmode="numeric" />
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
      <form [formGroup]="pasteForm" (ngSubmit)="paste()" aria-label="Paste draft claims">
        <label for="paste-claims">Paste claims (name,amount,date,note per line)</label>
        <textarea id="paste-claims" data-testid="paste-claims" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="paste-submit">Paste</button>
        @if (pasteAdded() !== null) {
          <p role="status">Added <span data-testid="paste-added">{{ pasteAdded() }}</span></p>
        }
        @if (pasteError()) {
          <p role="alert" class="error" data-testid="paste-error">{{ pasteError() }}</p>
        }
      </form>
      <label>
        <input type="checkbox" data-testid="show-retired" [checked]="session.showRetired()"
               (change)="session.showRetired.set($any($event.target).checked)" />
        Show retired
      </label>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export CSV</button>
      <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
      @if (bulkOpened(); as result) {
        <p role="status">Opened <span data-testid="bulk-opened">{{ result.opened }}</span>,
          refused <span data-testid="bulk-refused">{{ result.refused }}</span></p>
      }
      @if (bulkError()) {
        <p role="alert" class="error" data-testid="bulk-open-error">{{ bulkError() }}</p>
      }
      <button type="button" data-testid="bulk-settle" (click)="bulkSettle()">Settle selected claims</button>
      @if (bulkSettled(); as result) {
        <p role="status">Settled <span data-testid="bulk-settled">{{ result.settled }}</span>,
          refused <span data-testid="bulk-settle-refused">{{ result.refused }}</span></p>
      }
      @if (bulkSettleError()) {
        <p role="alert" class="error" data-testid="bulk-settle-error">{{ bulkSettleError() }}</p>
      }
      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Terms</th><th scope="col">Claimed</th><th scope="col">Due total</th><th scope="col">Excess</th><th scope="col">Periods</th><th scope="col">Statements</th><th scope="col">Retired</th><th scope="col">Hold</th><th scope="col">Watched</th><th scope="col">Active</th><th scope="col">Overdue</th><th scope="col">Patrons</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of listed(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              <td data-testid="person-claimed">{{ claimedOf(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotalOf(p) }}</td>
              <td data-testid="person-excess">{{ excessOf(p) }}</td>
              <td>
                @for (period of periods(); track period.id) {
                  <span>{{ period.name }} <span [attr.data-testid]="'person-period-' + period.name + '-due-total'">{{ personPeriodDueOf(p, period) }}</span></span>
                }
              </td>
              <td>
                <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
                <span>Last <span data-testid="person-stated-total">{{ statedTotalOf(p.id) }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ statementOf(p.id)?.lastDate ?? '' }}</span></span>
                <label [for]="'person-statement-asof-' + p.id" class="visually-hidden">Statement for {{ p.name }} as of</label>
                <input type="date" [id]="'person-statement-asof-' + p.id" data-testid="person-statement-asof"
                       #statementAsOf (input)="clearPersonError(p.id)" />
                <button type="button" data-testid="person-statement" (click)="takeStatement(p, statementAsOf)"
                        [attr.aria-label]="'Take statement for ' + p.name">Statement</button>
                <button type="button" data-testid="person-unstate" (click)="cancelStatement(p)"
                        [attr.aria-label]="'Cancel last statement for ' + p.name">Cancel statement</button>
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
                <ul>
                  @for (h of p.holds; track $index) {
                    <li data-testid="person-hold-line">{{ h.from ?? 'start' }} to {{ h.to ?? 'open' }}</li>
                  }
                </ul>
                <label [for]="'person-hold-from-' + p.id" class="visually-hidden">Hold {{ p.name }} from</label>
                <input type="date" [id]="'person-hold-from-' + p.id" data-testid="person-hold-from"
                       #holdFrom (input)="clearPersonError(p.id)" />
                <label [for]="'person-hold-to-' + p.id" class="visually-hidden">Hold {{ p.name }} to</label>
                <input type="date" [id]="'person-hold-to-' + p.id" data-testid="person-hold-to"
                       #holdTo (input)="clearPersonError(p.id)" />
                <button type="button" data-testid="person-hold-toggle" (click)="hold(p, holdFrom.value, holdTo.value)"
                        [attr.aria-label]="'Hold ' + p.name">Hold</button>
              </td>
              <td>
                <span data-testid="person-watched">{{ p.watched ? 'yes' : 'no' }}</span>
                <button type="button" data-testid="person-watch" (click)="watch(p)" [attr.aria-pressed]="p.watched"
                        [attr.aria-label]="(p.watched ? 'Stop watching ' : 'Watch ') + p.name">{{ p.watched ? 'Unwatch' : 'Watch' }}</button>
              </td>
              <td data-testid="person-active">{{ p.active ? 'yes' : 'no' }}</td>
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
                            [periods]="periods()" [selected]="selectedIds()" [others]="patronChoicesFor(p)"
                            (saved)="claimSaved($event)" (moved)="claimMoved($event)" (toggled)="claimToggled($event)" />
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
    <section aria-labelledby="summary-heading">
      <h2 id="summary-heading">Summary</h2>
      <table>
        <caption>By grade today, retired people excluded</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">People</th><th scope="col">Due total</th><th scope="col">Active</th></tr>
        </thead>
        <tbody>
          @for (g of gradeSummary(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade }}</th>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-count'">{{ g.count }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-active'">{{ g.active }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due total</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ periodDueTotalOf(p) }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By status</caption>
        <thead>
          <tr><th scope="col">Status</th><th scope="col">Claims</th></tr>
        </thead>
        <tbody>
          @for (s of statusSummary(); track s.status) {
            <tr>
              <th scope="row">{{ s.status }}</th>
              <td [attr.data-testid]="'summary-status-' + s.status + '-count'">{{ s.count }}</td>
            </tr>
          }
        </tbody>
      </table>
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
  /** The ids of the claims selected for a bulk change. */
  protected readonly selectedIds = signal<ReadonlySet<number>>(new Set());
  /** How many the last bulk open opened and refused; cleared while the next one runs. */
  protected readonly bulkOpened = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkError = signal('');
  /** How many the last bulk settle settled (or sent to PENDING) and refused; cleared while the next one runs. */
  protected readonly bulkSettled = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal('');
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  /** Each person's holds, by id, for those with any. */
  private readonly holdsById = computed(() => new Map(this.people().filter((p) => p.held).map((p) => [p.id, p.holds])));
  /** The sheet's today, those whose retirement date it has reached, by id. */
  private readonly retiredTodayIds = computed(() => {
    const today = this.session.today();
    return new Set(this.people().filter((p) => today !== null && p.retiredFrom !== null && p.retiredFrom <= today).map((p) => p.id));
  });
  /** Whether the claim's person keeps it out of the live totals while open: dated within a hold of theirs, or they are retired on the sheet's today. */
  private keptOut(c: Claim): boolean {
    return this.retiredTodayIds().has(c.personId) || (this.holdsById().get(c.personId) ?? []).some((h) => holdContains(h, c.date));
  }
  /** Whether the claim counts in the live totals: OPEN, PENDING or SETTLED, less open ones kept out by a hold or retirement and not bound, and deferred ones. */
  private readonly counts = (c: Claim): boolean => countsFor(c, this.keptOut(c), this.session.today());
  protected readonly claimedTotal = computed(() => money(this.claims().filter(this.counts).reduce((sum, c) => sum + cents(c.amount), 0)));
  /** The sheet's rates, each for a grade from its from-date. */
  protected readonly rates = signal<Rate[]>([]);
  /** The sheet's periods, earliest first. */
  protected readonly periods = signal<Period[]>([]);
  /**
   * Each person's live due-total per period in cents, by person id then period id: the dues as shown now
   * of their claims whose date falls in it that counted by the status they had at the end of its last day.
   */
  private readonly personPeriodLiveCents = computed(() => {
    const by = new Map<number, Map<number, number>>();
    const today = this.session.today();
    for (const c of this.claims()) {
      const period = periodOn(this.periods(), c.date);
      const pctOn = this.pctOnByPerson().get(c.personId);
      if (period === null || pctOn === undefined) continue;
      if (!countsAt(c, period.to, this.keptOut(c), today)) continue;
      const theirs = by.get(c.personId) ?? new Map<number, number>();
      theirs.set(period.id, (theirs.get(period.id) ?? 0) + (claimDueCents(c, pctOn, this.session.floorCentsOn, this.session.late(), roundsOn(c, this.claimsOf(c.personId))) ?? 0));
      by.set(c.personId, theirs);
    }
    return by;
  });
  /** A person's due-total for a period in cents: fixed when it was closed or soft-closed (0 for someone added later), else live. */
  private personPeriodCents(personId: number, period: Period): number {
    if (period.closed || period.softClosed) return cents(period.closedPersonDues?.[personId] ?? '0.00');
    return this.personPeriodLiveCents().get(personId)?.get(period.id) ?? 0;
  }
  protected personPeriodDueOf(p: Person, period: Period): string {
    return money(this.personPeriodCents(p.id, period));
  }
  /** A period's due-total: the people's figures for it added up. */
  protected readonly periodDueTotalOf = (period: Period): string =>
    money(this.people().reduce((sum, p) => sum + this.personPeriodCents(p.id, period), 0));
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
  /** Everyone's due-total added up, skipping those whose due-total is n/a, trimmed. */
  protected readonly dueTotal = computed(() =>
    money(trimmedSum(this.people().map((p) => this.dueTotalCents(p)).filter((c): c is number => c !== null))),
  );
  /** Each person's overdue claims that count, by id: how many and their dues in cents. */
  private readonly overdueByPerson = computed(() => {
    const today = this.session.today();
    const floor = this.session.floorCentsOn;
    const late = this.session.late();
    const by = new Map<number, Overdue>();
    for (const p of this.people()) {
      const pctOn = this.pctOnOf(p.id);
      const theirs = this.claimsOf(p.id);
      const overdue = theirs.filter((c) => this.counts(c) && isOverdue(c, today));
      by.set(p.id, { count: overdue.length, cents: overdue.reduce((sum, c) => sum + (claimDueCents(c, pctOn, floor, late, roundsOn(c, theirs)) ?? 0), 0) });
    }
    return by;
  });
  /** The sheet's overdue claims that count: everyone's added up. */
  protected readonly overdueTotal = computed(() => {
    let count = 0;
    for (const o of this.overdueByPerson().values()) count += o.count;
    return { count, cents: trimmedSum([...this.overdueByPerson().values()].map((o) => o.cents)) };
  });
  /** Per grade: the people not retired on it today, their due-totals added up, skipping n/a, and how many are active. */
  protected readonly gradeSummary = computed(() =>
    GRADES.map((grade) => {
      const on = this.people().filter((p) => !p.retired && this.gradeOf(p) === grade);
      return { grade, count: on.length, dueTotal: money(on.reduce((sum, p) => sum + (this.dueTotalCents(p) ?? 0), 0)),
        active: on.filter((p) => p.active).length };
    }),
  );
  /** Per status: how many of the sheet's claims have it. */
  protected readonly statusSummary = computed(() =>
    SUMMARY_STATUSES.map((status) => ({ status, count: this.claims().filter((c) => c.status === status).length })),
  );
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
    /** Their own terms in days; blank: none. */
    terms: new FormControl('', { nonNullable: true }),
  });

  /** A quick-add is under way; another one waits for its answer rather than storing twice. */
  private quickAdding = false;
  protected readonly quickError = signal('');
  protected readonly quickForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });

  /** A paste is under way; another one waits for its answer rather than storing twice. */
  private pasting = false;
  protected readonly pasteError = signal('');
  /** How many claims the last paste added; null before one, and while one runs. */
  protected readonly pasteAdded = signal<number | null>(null);
  protected readonly pasteForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.pasteForm.valueChanges.subscribe(() => this.pasteError.set(''));
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.quickForm.valueChanges.subscribe(() => this.quickError.set(''));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.selectedIds.set(new Set());
      this.bulkOpened.set(null);
      this.bulkError.set('');
      this.bulkSettled.set(null);
      this.bulkSettleError.set('');
      this.rates.set([]);
      this.periods.set([]);
      this.statements.set([]);
      this.personError.set(null);
      this.patronError.set(null);
      this.reset();
      this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
      this.quickError.set('');
      this.pasteForm.reset({ text: '' }, { emitEvent: false });
      this.pasteError.set('');
      this.pasteAdded.set(null);
      if (id) void this.load(id);
    });
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/people`;
  }

  /** A bulk open or settle is under way; another press waits for its answer. */
  private bulkRunning = false;

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
   * nor a due under another grade they had on its date or under a rate not in force today.
   */
  private uncappedDueCents(p: Person): number | null {
    const theirs = this.claimsOf(p.id);
    const counted = theirs.filter(this.counts);
    const pctOn = this.pctOnOf(p.id);
    const floor = this.session.floorCentsOn;
    const late = this.session.late();
    const gradeToday = this.gradeOf(p);
    const today = this.session.today();
    const otherRate = (c: Claim) => {
      const rates = this.ratesOf(gradeOn(p, c.date));
      return today !== null && rateOn(rates, c.date) !== rateOn(rates, today);
    };
    const fixed = (c: Claim) => c.settledDue !== null || (isBound(c) && c.statedDue !== null)
      || ((gradeOn(p, c.date) !== gradeToday || otherRate(c)) && claimDueCents(c, pctOn, floor, late, roundsOn(c, theirs)) !== null);
    if (this.pctOf(gradeToday) === null && !counted.some(fixed)) return null;
    return counted.reduce((sum, c) => sum + (claimDueCents(c, pctOn, floor, late, roundsOn(c, theirs)) ?? 0), 0);
  }

  /** The sheet's cap in cents, or null when there is none. */
  private readonly capCents = computed(() => {
    const cap = this.session.cap();
    return cap === null ? null : cents(cap);
  });

  /** Their due-total in cents, at most the cap; null (n/a) is not capped. */
  private dueTotalCents(p: Person): number | null {
    const total = this.uncappedDueCents(p);
    const cap = this.capCents();
    return total === null || cap === null ? total : Math.min(total, cap);
  }

  /** How far the sum of their figures is above the cap; 0.00 when it is not, or is n/a. */
  protected excessOf(p: Person): string {
    const total = this.uncappedDueCents(p);
    const cap = this.capCents();
    return money(total === null || cap === null ? 0 : Math.max(0, total - cap));
  }

  protected overdueOf(p: Person): Overdue {
    return this.overdueByPerson().get(p.id) ?? { count: 0, cents: 0 };
  }

  /** Their due-total, or n/a. */
  protected dueTotalOf(p: Person): string {
    const total = this.dueTotalCents(p);
    return total === null ? NA : money(total);
  }

  /**
   * Downloads everyone, retired people included, in list order as CSV: name, grade in force today,
   * retired-from (or nothing) and due-total as shown.
   */
  protected exportCsv(): void {
    const lines = [['name', 'grade', 'retired-from', 'due-total'], ...this.people().map((p) => [p.name, this.gradeOf(p), p.retiredFrom ?? '', this.dueTotalOf(p)])];
    const csv = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'people.csv';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  /** Their patrons' names, comma and space separated, sorted. */
  protected patronsOf(p: Person): string {
    return this.patronsOfPerson(p)
      .map((x) => x.name)
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
      .join(', ');
  }

  /**
   * Their patrons' settled figures added up: each patron's SETTLED claims dated within the 90 days up to
   * and including the sheet's today, at their frozen figures; 0.00 with none.
   */
  protected backingOf(p: Person): string {
    const today = this.session.today();
    if (today === null) return money(0);
    const from = new Date(Date.parse(`${today}T00:00:00Z`) - 89 * 86_400_000).toISOString().slice(0, 10);
    const settled = (c: Claim) => c.status === 'SETTLED' && c.settledDue !== null && c.date >= from && c.date <= today;
    return money(this.patronsOfPerson(p).reduce(
      (sum, x) => sum + this.claimsOf(x.id).filter(settled).reduce((s, c) => s + cents(c.settledDue!), 0), 0));
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

  /**
   * Take a statement for the person, binding their counting claims to their dues now, or as they stood
   * at the end of the date entered (blank: now). The date is cleared once the statement is taken or refused,
   * so a refused date never carries into the next statement.
   */
  protected async takeStatement(p: Person, asOfInput: HTMLInputElement): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const asOf = asOfInput.value.trim();
    if (asOf && !/^\d{4}-\d{2}-\d{2}$/.test(asOf)) {
      this.personError.set({ id: p.id, message: 'Enter a date, or leave it blank for now.' });
      return;
    }
    try {
      const taken = await firstValueFrom(
        this.http.post<StatementTaken>(`${this.url(sheet)}/${p.id}/statement`, { asOf: asOf || null }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      asOfInput.value = '';
      this.statements.update((list) => [...list.filter((s) => s.personId !== p.id), taken.summary]);
      const byId = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
    } catch {
      if (this.session.sheetId() !== sheet) return;
      asOfInput.value = '';
      this.personError.set({ id: p.id, message: 'Could not take a statement for ' + p.name + '.' });
    }
  }

  /** Cancel the person's last statement, making the claims it bound live again. */
  protected async cancelStatement(p: Person): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const cancelled = await firstValueFrom(
        this.http.post<StatementTaken>(`${this.url(sheet)}/${p.id}/statement/cancel`, null, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.statements.update((list) => [
        ...list.filter((s) => s.personId !== p.id),
        ...(cancelled.summary.count > 0 ? [cancelled.summary] : []),
      ]);
      const byId = new Map(cancelled.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
    } catch {
      if (this.session.sheetId() === sheet) this.personError.set({ id: p.id, message: 'Could not cancel the last statement for ' + p.name + '.' });
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

  /** Put the person on hold from the date (blank: the sheet's today) to the optional to-date. */
  protected async hold(p: Person, fromDate: string, toDate: string): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const from = fromDate.trim();
    const to = toDate.trim();
    const date = /^\d{4}-\d{2}-\d{2}$/;
    if ((from && !date.test(from)) || (to && !date.test(to))) {
      this.personError.set({ id: p.id, message: 'Enter dates, or leave the from-date blank for today and the to-date blank for no end.' });
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(`${this.url(sheet)}/${p.id}/hold`, { from: from || null, to: to || null }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
    } catch {
      if (this.session.sheetId() === sheet) this.personError.set({ id: p.id, message: 'Could not hold ' + p.name + '.' });
    }
  }

  /** Start watching the person as the signed-in user, or stop if they already do. */
  protected async watch(p: Person): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const saved = await firstValueFrom(
        this.http.put<Person>(`${this.url(sheet)}/${p.id}/watch`, { watched: !p.watched }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
    } catch {
      if (this.session.sheetId() === sheet) this.personError.set({ id: p.id, message: 'Could not change watching ' + p.name + '.' });
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
  protected claimToggled(e: { id: number; checked: boolean }): void {
    this.selectedIds.update((ids) => {
      const next = new Set(ids);
      if (e.checked) next.add(e.id);
      else next.delete(e.id);
      return next;
    });
  }

  /** Open every selected claim that can be opened, numbered in list order; show how many opened and were refused. */
  protected async bulkOpen(): Promise<void> {
    if (this.bulkRunning) return;
    this.bulkOpened.set(null);
    this.bulkError.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    this.bulkRunning = true;
    try {
      const result = await firstValueFrom(this.http.post<{ opened: number; refused: number }>(
        `${base}/claims/open`, { ids: [...this.selectedIds()] }, { headers: this.headers() }));
      const claims = await firstValueFrom(this.http.get<Claim[]>(`${base}/claims`, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.claims.set(claims);
      this.selectedIds.set(new Set());
      this.bulkOpened.set(result);
    } catch {
      if (this.session.sheetId() === sheet) this.bulkError.set('Could not open the selected claims.');
    } finally {
      this.bulkRunning = false;
    }
  }

  /** Settle every selected claim that can be settled, in list order; show how many settled and were refused. */
  protected async bulkSettle(): Promise<void> {
    if (this.bulkRunning) return;
    this.bulkSettled.set(null);
    this.bulkSettleError.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    this.bulkRunning = true;
    try {
      const result = await firstValueFrom(this.http.post<{ settled: number; refused: number }>(
        `${base}/claims/settle`, { ids: [...this.selectedIds()] }, { headers: this.headers() }));
      const claims = await firstValueFrom(this.http.get<Claim[]>(`${base}/claims`, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.claims.set(claims);
      this.selectedIds.set(new Set());
      this.bulkSettled.set(result);
    } catch {
      if (this.session.sheetId() === sheet) this.bulkSettleError.set('Could not settle the selected claims.');
    } finally {
      this.bulkRunning = false;
    }
  }

  protected claimSaved(claim: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id) ? list.map((c) => (c.id === claim.id ? claim : c)) : [claim, ...list],
    );
  }

  /** Put a claim moved to another person first, as their newest. */
  protected claimMoved(claim: Claim): void {
    this.claims.update((list) => [claim, ...list.filter((c) => c.id !== claim.id)]);
  }

  private reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A', gradeFrom: '', terms: '' }, { emitEvent: false });
    this.error.set('');
  }

  protected edit(p: Person): void {
    this.personError.set(null);
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.form.setValue({ name: p.name, grade: this.gradeOf(p), gradeFrom: '', terms: p.terms?.toString() ?? '' }, { emitEvent: false });
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
    const terms = raw.terms.trim();
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!this.grades.includes(grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    if (!/^\d{0,4}$/.test(terms)) {
      // refused terms close an editor, so the form is back to adding; the error stays until it is changed
      if (this.editingId() !== null) this.reset();
      this.error.set('Enter terms as a whole number of days, 0 or more, or leave them blank.');
      return;
    }
    const id = this.editingId();
    this.saving = true;
    try {
      if (id === null) {
        const added = await firstValueFrom(
          this.http.post<Person>(this.url(sheet), { name, grade, terms }, { headers: this.headers() }),
        );
        if (this.session.sheetId() !== sheet) return;
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await firstValueFrom(
          this.http.put<Person>(`${this.url(sheet)}/${id}`, { name, grade, gradeFrom, terms, version: this.editingVersion }, { headers: this.headers() }),
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

  /**
   * Add a DRAFT claim per pasted line of name,amount,date,note, in order, for the person of the name
   * on the sheet. Every line is checked first; if any is refused, none is added.
   */
  protected async paste(): Promise<void> {
    if (this.pasting) return;
    this.pasteError.set('');
    this.pasteAdded.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = this.pasteForm.getRawValue().text;
    if (!text.trim()) {
      this.pasteError.set('Paste at least one line of name,amount,date,note.');
      return;
    }
    this.pasting = true;
    try {
      const done = await firstValueFrom(
        this.http.post<{ added: number; claims: Claim[] }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/paste`, { text }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const claim of done.claims) this.claimSaved(claim);
      this.pasteAdded.set(done.added);
      this.pasteForm.reset({ text: '' }, { emitEvent: false });
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.pasteError.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? 'No claim dated inside a closed period can be changed.'
          : e instanceof HttpErrorResponse && e.status === 422
          ? RETIRED
          : 'Every line must be name,amount,date,note for a person on the sheet; nothing was added.',
      );
    } finally {
      this.pasting = false;
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
        this.form.setValue({ name: current.name, grade: this.gradeOf(current), gradeFrom: '', terms: current.terms?.toString() ?? '' }, { emitEvent: false });
      }
    } catch {
      // the error already shown stands
    }
  }
}
