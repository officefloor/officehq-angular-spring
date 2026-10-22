import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, linkedSignal, signal, untracked } from '@angular/core';
import { ClaimView, ClaimsApi, cents, counts, deferred, isOpen, money, overdue, roundsHalfUp, statusAt } from '../../core/claims';
import { GRADES, Grade, PeopleApi, PersonView, gradeOn, leavesLive } from '../../core/people';
import { PeriodView, PeriodsApi, fixed, periodOn } from '../../core/periods';
import { QuickAddView } from '../../core/quick-add';
import { NO_RATE, RateView, RatesApi, claimDueCents, rateOn, sameSlot } from '../../core/rates';
import { Session, valueOn } from '../../core/session';
import { Claims } from '../claims/claims';
import { Header } from '../header/header';
import { Periods } from '../periods/periods';
import { PasteClaims } from '../paste-claims/paste-claims';
import { QuickAdd } from '../quick-add/quick-add';
import { Rates } from '../rates/rates';
import { GradeSummary, PeriodSummary, StatusSummary, Summary } from '../summary/summary';

/** The statuses the summary counts claims by, in the order it lists them. */
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

/**
 * Whether a person's claim counts in the live totals: OPEN, PENDING and SETTLED claims do, but not an
 * OPEN or PENDING one dated within one of the person's holds or once their retirement date is reached,
 * unless a statement has bound it, nor
 * one deferred on the sheet's today.
 */
function countsLive(claim: ClaimView, person: PersonView, today: string): boolean {
  return counts(claim) && !deferred(claim, today) && !(isOpen(claim) && !claim.stated && leavesLive(person, claim.date, today));
}

/**
 * Whether a person's claim counts in a period's live figures: it counts by the status it had at the
 * end of the period's last day (see statusAt), one added after then not counting, and is left out,
 * as in the live totals, when deferred on today or when it is still OPEN or PENDING, unbound by a
 * statement and dated within one of the person's holds or of a person whose retirement date is reached.
 */
function countsInPeriod(claim: ClaimView, person: PersonView, today: string, period: PeriodView): boolean {
  const then = statusAt(claim, period.to);
  if (then === null) return false;
  const countedThen = then === 'OPEN' || then === 'PENDING' || then === 'SETTLED';
  return countedThen && !deferred(claim, today) && !(isOpen(claim) && !claim.stated && leavesLive(person, claim.date, today));
}

/** A CSV field: wrapped in double quotes, any inside doubled, when it holds a comma, a quote or a line break. */
/**
 * The sum of the people's figures in whole cents, leaving out the single highest and the single
 * lowest when there are three or more.
 */
function trimmedCents(figures: number[]): number {
  const total = figures.reduce((sum, f) => sum + f, 0);
  return figures.length >= 3 ? total - Math.max(...figures) - Math.min(...figures) : total;
}

/**
 * Whether a person is active on the sheet's today: they have an OPEN or PENDING claim that counts in
 * the live totals (see countsLive), dated within the 30 days up to and including today.
 */
function activeOn(claims: ClaimView[], person: PersonView, today: string): boolean {
  if (today === '') return false;
  const since = new Date(Date.parse(today) - 29 * 86_400_000).toISOString().slice(0, 10);
  return claims.some((c) => isOpen(c) && countsLive(c, person, today) && since <= c.date && c.date <= today);
}

function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

// The active sheet's people: listed in the order they were added, added and edited through one form,
// each with their claims, what they have claimed and what is due (the sum of their claims' due
// figures), and the sheet's claimed-total and due-total. The sheet's rates are set here too.
@Component({
  selector: 'app-people',
  imports: [Claims, Header, PasteClaims, Periods, QuickAdd, Rates, Summary],
  template: `
    <app-header
      [peopleCount]="people().length"
      [statuses]="statusSummary()"
      [dueTotal]="dueTotal()"
      [overdueTotal]="overdueTotal()"
    />
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
        <label for="person-form-terms">Terms (days)</label>
        <input
          id="person-form-terms"
          data-testid="person-form-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); error.set(null)"
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
      <app-paste-claims (added)="pasted($event)" />
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
      <p>
        <button type="button" data-testid="bulk-open" (click)="openSelected()">Open selected claims</button>
        @if (bulkOpened(); as result) {
          <span role="status">
            Opened <strong data-testid="bulk-opened">{{ result.opened }}</strong>,
            refused <strong data-testid="bulk-refused">{{ result.refused }}</strong>
          </span>
        }
        @if (bulkOpenError(); as message) {
          <span role="alert" data-testid="bulk-open-error">{{ message }}</span>
        }
      </p>
      <p>
        <button type="button" data-testid="bulk-settle" (click)="settleSelected()">Settle selected claims</button>
        @if (bulkSettled(); as result) {
          <span role="status">
            Settled <strong data-testid="bulk-settled">{{ result.settled }}</strong>,
            refused <strong data-testid="bulk-settle-refused">{{ result.refused }}</strong>
          </span>
        }
        @if (bulkSettleError(); as message) {
          <span role="alert" data-testid="bulk-settle-error">{{ message }}</span>
        }
      </p>
      <form (submit)="gradeSelected($event)" aria-label="Grade the selected people">
        <label for="bulk-grade">Grade the selected people</label>
        <select
          id="bulk-grade"
          data-testid="bulk-grade"
          [value]="bulkGrade()"
          (change)="bulkGrade.set(gradeChosen($event)); bulkGradeError.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">from</label>
        <input
          type="date"
          id="bulk-grade-from"
          data-testid="bulk-grade-from"
          [value]="bulkGradeFrom()"
          (input)="bulkGradeFrom.set(value($event)); bulkGradeError.set(null)"
        />
        <button type="submit" data-testid="bulk-grade-apply">Grade selected people</button>
        @if (bulkGraded() !== null) {
          <span role="status">Changed <strong data-testid="bulk-graded">{{ bulkGraded() }}</strong></span>
        }
        @if (bulkGradeError(); as message) {
          <span role="alert" data-testid="person-error">{{ message }}</span>
        }
      </form>
      <table>
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            <th scope="col">Terms</th>
            <th scope="col">Claimed</th>
            <th scope="col">Due</th>
            <th scope="col">Due by period</th>
            <th scope="col">Excess</th>
            <th scope="col">Overdue</th>
            <th scope="col">Statements</th>
            <th scope="col">Retired</th>
            <th scope="col">Active</th>
            <th scope="col">On hold</th>
            <th scope="col">Watched</th>
            <th scope="col">Patrons</th>
            <th scope="col">Backing</th>
            <th scope="col">Claims</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of shownPeople(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td>
                <input
                  type="checkbox"
                  data-testid="person-select"
                  [attr.aria-label]="'Select ' + p.name"
                  [checked]="selectedPeople().has(p.id)"
                  (change)="selectPerson(p.id, checked($event))"
                />
              </td>
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotalOf(p) }}</td>
              <td>
                <ul>
                  @for (period of periods(); track period.id) {
                    <li>
                      {{ period.name }}
                      <span [attr.data-testid]="'person-period-' + period.name + '-due-total'">{{ periodTotalOf(period, p) }}</span>
                    </li>
                  }
                </ul>
              </td>
              <td data-testid="person-excess">{{ excessOf(p) }}</td>
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
              <td data-testid="person-active">{{ activeOf(p) ? 'yes' : 'no' }}</td>
              <td>
                <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                <ul>
                  @for (h of p.holds; track $index) {
                    <li data-testid="person-hold-line">from {{ h.from }}{{ h.to ? ' to ' + h.to : '' }}</li>
                  }
                </ul>
              </td>
              <td>
                <span data-testid="person-watched">{{ watched().has(p.id) ? 'yes' : 'no' }}</span>
                <button
                  type="button"
                  data-testid="person-watch"
                  [attr.aria-pressed]="watched().has(p.id)"
                  [attr.aria-label]="(watched().has(p.id) ? 'Stop watching ' : 'Watch ') + p.name"
                  (click)="toggleWatch(p)"
                >
                  {{ watched().has(p.id) ? 'Unwatch' : 'Watch' }}
                </button>
              </td>
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
                  [people]="people()"
                  [selected]="selectedClaims()"
                  (selectedChange)="selectClaim($event.id, $event.on)"
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
                <label [for]="'person-statement-asof-' + p.id">Statement as of</label>
                <input
                  #statementAsOf
                  type="date"
                  [id]="'person-statement-asof-' + p.id"
                  data-testid="person-statement-asof"
                  (input)="personError.set(null)"
                />
                <button
                  type="button"
                  data-testid="person-statement"
                  [attr.aria-label]="'Take a statement for ' + p.name"
                  (click)="statement(p, statementAsOf)"
                >
                  Statement
                </button>
                <button
                  type="button"
                  data-testid="person-unstate"
                  [attr.aria-label]="'Cancel the last statement for ' + p.name"
                  (click)="unstate(p)"
                >
                  Cancel statement
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
                <label [for]="'person-hold-from-' + p.id">Hold from</label>
                <input
                  type="date"
                  [id]="'person-hold-from-' + p.id"
                  data-testid="person-hold-from"
                  [value]="holdDraft(p.id).from"
                  (input)="setHoldDraft(p.id, { from: value($event) })"
                />
                <label [for]="'person-hold-to-' + p.id">Hold to</label>
                <input
                  type="date"
                  [id]="'person-hold-to-' + p.id"
                  data-testid="person-hold-to"
                  [value]="holdDraft(p.id).to"
                  (input)="setHoldDraft(p.id, { to: value($event) })"
                />
                <button
                  type="button"
                  data-testid="person-hold-toggle"
                  [attr.aria-label]="'Hold ' + p.name"
                  (click)="hold(p)"
                >
                  Hold
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
  /** The hold dates typed on each person's row, by person id; an empty from means the sheet's today. */
  protected readonly holdDrafts = signal(new Map<number, { from: string; to: string }>());
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
   * Each person's due-total for each period, in whole cents, by period id then person id. While the
   * period is open it is live: the sum of the due figures, as shown now, of the person's claims dated in
   * it that were OPEN, PENDING or SETTLED at the end of its last day, each at the grade in force on its
   * date (a claim with no figure adds nothing), leaving out those held or deferred (see countsInPeriod). Once closed or soft-closed it is the figure fixed then,
   * 0 for a person added since.
   */
  private readonly personPeriodCents = computed(() => {
    const figures = new Map<number, Map<number, number>>(
      this.periods().map((period) => [
        period.id,
        new Map(
          this.people().map((p) => [p.id, fixed(period) ? cents(period.personTotals[p.id] ?? '0.00') : 0]),
        ),
      ]),
    );
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const c of this.claims()) {
      const period = periodOn(this.periods(), c.date);
      const person = byId.get(c.personId);
      if (!period || fixed(period) || !person || !countsInPeriod(c, person, this.today(), period)) continue;
      const row = figures.get(period.id)!;
      row.set(person.id, row.get(person.id)! + (this.dueCents(c, person) ?? 0));
    }
    return figures;
  });
  /** Each period's due-total by period id: the sum of the people's figures for it. */
  protected readonly periodDueTotals = computed(
    () =>
      new Map(
        [...this.personPeriodCents()].map(([id, row]) => [id, money([...row.values()].reduce((a, b) => a + b, 0))]),
      ),
  );
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
        active: on.filter((p) => this.activeOf(p)).length,
        dueTotal: money(on.reduce((sum, p) => sum + (this.dueTotalCents(p) ?? 0), 0)),
      };
    });
  });
  /** Per period: its due-total as its row shows it. */
  protected readonly periodSummary = computed<PeriodSummary[]>(() => {
    const totals = this.periodDueTotals();
    return this.periods().map((p) => ({ name: p.name, dueTotal: totals.get(p.id) ?? '0.00' }));
  });
  /** Per status: how many of the sheet's claims have it. */
  protected readonly statusSummary = computed<StatusSummary[]>(() =>
    SUMMARY_STATUSES.map((status) => ({
      status,
      count: this.claims().filter((c) => c.status === status).length,
    })),
  );
  /** The sheet's cap in whole cents: a person's due-total is at most it; null when there is none. */
  private readonly capCents = computed(() => (this.session.cap() === '' ? null : cents(this.session.cap())));
  /** The sheet's today, on which each grade's rate in force is read. */
  protected readonly today = computed(() => this.session.today() ?? '');
  protected readonly claimedTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + this.claimedCents(p.id), 0)),
  );
  /**
   * Everyone's due-total added up, skipping those shown as n/a, and leaving out the highest and
   * lowest when three or more have one.
   */
  protected readonly dueTotal = computed(() =>
    money(
      trimmedCents(
        this.people()
          .map((p) => this.dueTotalCents(p))
          .filter((c): c is number => c !== null),
      ),
    ),
  );
  /** How many overdue claims count in the live totals, across everyone. */
  protected readonly overdueCount = computed(() =>
    this.people().reduce((sum, p) => sum + this.overdueOf(p.id).length, 0),
  );
  /**
   * Everyone's overdue-total added up, leaving out the highest and lowest when there are three or
   * more people.
   */
  protected readonly overdueTotal = computed(() =>
    money(trimmedCents(this.people().map((p) => this.overdueCents(p)))),
  );
  protected readonly name = signal('');
  protected readonly grade = signal<Grade>('A');
  /** The date the grade entered is in force from; the sheet's today unless another is typed. */
  protected readonly gradeFrom = signal('');
  /** The person's own terms as typed: a whole number of days, or blank for none. */
  protected readonly terms = signal('');
  /** The id of the person open in the form, or null when the form adds a new person. */
  protected readonly editing = signal<number | null>(null);
  /** The version of the person open in the form, as it was loaded. */
  private readonly version = signal(0);
  protected readonly error = signal<string | null>(null);
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The person chosen in each row's patron list, by person id. */
  protected readonly patronChoices = new Map<number, string>();
  /** The ids of the people the signed-in user watches. */
  protected readonly watched = signal<ReadonlySet<number>>(new Set());
  /** The refusal of the last patron added on a person's row, shown on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;
  /** The ids of the claims ticked for a bulk action. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** How many claims the last bulk open opened and refused; null until one is done. */
  protected readonly bulkOpened = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal<string | null>(null);
  /** How many claims the last bulk settle settled (or sent PENDING) and refused; null until one is done. */
  protected readonly bulkSettled = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal<string | null>(null);
  /** The ids of the people ticked to be given a grade together. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  /** The grade to give the selected people. */
  protected readonly bulkGrade = signal<Grade>('A');
  /** The date the selected people's grade is in force from; the sheet's today unless another is typed. */
  protected readonly bulkGradeFrom = linkedSignal(() => this.today());
  /** How many people the last bulk grade changed; null until one is done. */
  protected readonly bulkGraded = signal<number | null>(null);
  protected readonly bulkGradeError = signal<string | null>(null);

  constructor() {
    // Every sheet load re-reads the sheet's people from the store.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      untracked(() => {
        this.people.set([]);
        this.watched.set(new Set());
        this.claims.set([]);
        this.rates.set([]);
        this.periods.set([]);
        this.reset();
        this.personError.set(null);
        this.retireDates.clear();
        this.holdDrafts.set(new Map());
        this.patronError.set(null);
        this.patronChoices.clear();
        this.selectedClaims.set(new Set());
        this.bulkOpened.set(null);
        this.bulkOpenError.set(null);
        this.bulkSettled.set(null);
        this.bulkSettleError.set(null);
        this.selectedPeople.set(new Set());
        this.bulkGraded.set(null);
        this.bulkGradeError.set(null);
        this.bulkGradeFrom.set(this.today());
        if (sheet) void this.reload(sheet);
      });
    });
    // A change to the sheet's today may close periods past their grace days: re-read them.
    let shownToday: string | null = null;
    effect(() => {
      const today = this.session.today();
      untracked(() => {
        const sheet = this.session.sheet();
        const changed = shownToday !== null && today !== shownToday;
        shownToday = today;
        if (sheet && changed) void this.todayChanged(sheet);
      });
    });
  }

  /** The sheet's today changed: re-read its periods, some of which may have closed. */
  private async todayChanged(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const periods = await this.periodsApi.list(sheet);
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.periods.set(periods);
  }

  protected claimsOf(personId: number): ClaimView[] {
    return this.byPerson().get(personId) ?? [];
  }

  /** A person's claims that count in the live totals (see countsLive). */
  private countingOf(personId: number): ClaimView[] {
    const person = this.people().find((p) => p.id === personId);
    return person ? this.claimsOf(personId).filter((c) => countsLive(c, person, this.today())) : [];
  }

  /** What a person has claimed: the sum of their OPEN and SETTLED claims' amounts (see countsLive). */
  protected claimed(personId: number): string {
    return money(this.claimedCents(personId));
  }

  private claimedCents(personId: number): number {
    return this.countingOf(personId).reduce((sum, c) => sum + cents(c.amount), 0);
  }

  /** Whether a person is active on the sheet's today (see activeOn). */
  protected activeOf(person: PersonView): boolean {
    return activeOn(this.claimsOf(person.id), person, this.today());
  }

  /** A person's overdue claims that count in the live totals (see countsLive). */
  protected overdueOf(personId: number): ClaimView[] {
    return this.countingOf(personId).filter((c) => overdue(c, this.today()));
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
   * A person's due-total in whole cents, at most the sheet's cap: the sum of the due figures of their OPEN and SETTLED claims
   * (0 with none; see countsLive). Null (n/a) when the grade they have in force on the sheet's today has no
   * rate, unless a claim has a figure (a fixed one, or one at the grade in force on its own date): then it
   * is the sum of the figures there are.
   */
  private dueTotalCents(person: PersonView): number | null {
    const sum = this.dueSumCents(person);
    const cap = this.capCents();
    return sum !== null && cap !== null && sum > cap ? cap : sum;
  }

  /** A person's due-total for a period (see personPeriodCents). */
  protected periodTotalOf(period: PeriodView, person: PersonView): string {
    return money(this.personPeriodCents().get(period.id)?.get(person.id) ?? 0);
  }

  /** A person's excess: by how much the sum of their figures is above the cap, else 0.00 (n/a is not capped). */
  protected excessOf(person: PersonView): string {
    const sum = this.dueSumCents(person);
    const cap = this.capCents();
    return money(sum !== null && cap !== null && sum > cap ? sum - cap : 0);
  }

  /** The sum of a person's due figures in whole cents before the cap, or null (n/a; see dueTotalCents). */
  private dueSumCents(person: PersonView): number | null {
    const dues = this.countingOf(person.id).map((c) => this.dueCents(c, person));
    if (this.pctOf(this.gradeOf(person)) === null && dues.every((d) => d === null)) return null;
    return dues.reduce<number>((sum, d) => sum + (d ?? 0), 0);
  }

  /**
   * A claim's due in whole cents at the grade its person has in force on its date, with the floor
   * in force on its date and the late percent on each 30-day boundary (see claimDueCents).
   */
  private dueCents(claim: ClaimView, person: PersonView): number | null {
    const halfUp = roundsHalfUp(claim, this.claimsOf(person.id), this.session.policyDate(), this.today());
    const floor = cents(valueOn(this.session.floorHistory(), claim.date, '1.00'));
    const late = { history: this.session.latePctHistory(), today: this.today() };
    return claimDueCents(claim, this.rates(), gradeOn(person.grades, claim.date), floor, late, halfUp);
  }

  /** A person's patrons' names, sorted, comma and space separated. */
  protected patronsOf(person: PersonView): string {
    return this.patrons(person)
      .map((p) => p.name)
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
      .join(', ');
  }

  /**
   * A person's backing: the settled figures of their patrons' SETTLED claims dated within the 90 days
   * up to and including the sheet's today, each at its frozen figure (one with none adds nothing).
   */
  protected backingOf(person: PersonView): string {
    return money(this.patrons(person).reduce((sum, p) => sum + this.recentSettledCents(p.id), 0));
  }

  private recentSettledCents(personId: number): number {
    const today = this.today();
    if (today === '') return 0;
    const since = new Date(Date.parse(today) - 89 * 86_400_000).toISOString().slice(0, 10);
    return this.claimsOf(personId)
      .filter((c) => c.status === 'SETTLED' && since <= c.date && c.date <= today)
      .reduce((sum, c) => sum + (c.settledDue === null ? 0 : cents(c.settledDue)), 0);
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

  /**
   * Take a statement for a person, as of the date typed beside the button (blank: now): the row and
   * their claims show what the store holds after it, and the date is cleared for the next one.
   */
  protected async statement(person: PersonView, asOfInput: HTMLInputElement): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const asOf = asOfInput.value.trim() || null;
    try {
      const stated = await this.api.statement(sheet, person.id, asOf);
      if (this.session.sheet() !== sheet) return;
      asOfInput.value = '';
      this.people.update((list) => list.map((p) => (p.id === stated.person.id ? stated.person : p)));
      const byId = new Map(stated.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
    } catch {
      asOfInput.value = '';
      this.personError.set({ id: person.id, message: 'The statement could not be taken.' });
    }
  }

  /** Cancel a person's last statement: the row and their claims show what the store holds after it. */
  protected async unstate(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      const cancelled = await this.api.unstate(sheet, person.id);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === cancelled.person.id ? cancelled.person : p)));
      const byId = new Map(cancelled.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
    } catch {
      this.personError.set({ id: person.id, message: 'The statement could not be cancelled.' });
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

  /** The hold dates typed on a person's row. */
  protected holdDraft(personId: number): { from: string; to: string } {
    return this.holdDrafts().get(personId) ?? { from: '', to: '' };
  }

  /** Change the hold dates typed on a person's row. */
  protected setHoldDraft(personId: number, change: { from?: string; to?: string }): void {
    this.holdDrafts.update((map) => new Map(map).set(personId, { ...this.holdDraft(personId), ...change }));
    this.personError.set(null);
  }

  /** Put a person on hold from the date typed (none: the sheet's today) to the date typed (none: no end). */
  protected async hold(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const draft = this.holdDraft(person.id);
    const from = draft.from.trim() || null;
    const to = draft.to.trim() || null;
    if (from !== null && to !== null && to < from) {
      this.personError.set({ id: person.id, message: 'A hold cannot end before it starts.' });
      return;
    }
    try {
      const saved = await this.api.hold(sheet, person.id, from, to);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.holdDrafts.update((map) => {
        const next = new Map(map);
        next.delete(person.id);
        return next;
      });
    } catch {
      this.personError.set({ id: person.id, message: 'The person could not be put on hold.' });
    }
  }

  /** Start or stop watching a person as the signed-in user. */
  protected async toggleWatch(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const on = !this.watched().has(person.id);
    try {
      await this.api.watch(sheet, person.id, on);
      if (this.session.sheet() !== sheet) return;
      this.watched.update((set) => {
        const next = new Set(set);
        if (on) next.add(person.id);
        else next.delete(person.id);
        return next;
      });
    } catch {
      this.personError.set({
        id: person.id,
        message: on ? 'The person could not be watched.' : 'The person could not be unwatched.',
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

  /** Tick (on) or clear a person's checkbox. */
  protected selectPerson(id: number, on: boolean): void {
    this.selectedPeople.update((set) => {
      const next = new Set(set);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
    this.bulkGraded.set(null);
    this.bulkGradeError.set(null);
  }

  /**
   * Give the selected people the grade from the date together: each is edited as the person form
   * would, in list order; how many were changed is shown, and the selection cleared.
   */
  protected async gradeSelected(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.bulkGraded.set(null);
    this.bulkGradeError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const ids = [...this.selectedPeople()];
    if (ids.length === 0) {
      this.bulkGradeError.set('Select the people to grade.');
      return;
    }
    this.busy = true;
    try {
      const changed = await this.api.gradeAll(sheet, ids, this.bulkGrade(), this.bulkGradeFrom() || null);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => changed.find((c) => c.id === p.id) ?? p));
      this.selectedPeople.set(new Set());
      this.bulkGradeFrom.set(this.today());
      this.bulkGraded.set(changed.length);
    } catch {
      this.bulkGradeError.set('The people could not be graded.');
    } finally {
      this.busy = false;
    }
  }

  /** Tick (on) or clear a claim's checkbox. */
  protected selectClaim(id: number, on: boolean): void {
    this.selectedClaims.update((set) => {
      const next = new Set(set);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
    this.bulkOpenError.set(null);
    this.bulkSettleError.set(null);
  }

  /**
   * Open the selected claims together: each that can be opened is, numbered in list order, and each
   * that cannot is left as it is; how many were opened and refused is shown, and the selection cleared.
   */
  protected async openSelected(): Promise<void> {
    if (this.busy) return;
    this.bulkOpened.set(null);
    this.bulkOpenError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    if (ids.length === 0) {
      this.bulkOpenError.set('Select the claims to open.');
      return;
    }
    this.busy = true;
    try {
      const result = await this.claimsApi.openAll(sheet, ids);
      if (this.session.sheet() !== sheet) return;
      for (const claim of result.opened) this.claimSaved(claim);
      this.selectedClaims.set(new Set());
      this.bulkOpened.set({ opened: result.opened.length, refused: result.refused });
    } catch {
      this.bulkOpenError.set('The claims could not be opened.');
    } finally {
      this.busy = false;
    }
  }

  /**
   * Settle the selected claims together: each is settled (or sent PENDING) as its Settle button would,
   * in list order, and each that cannot be is left as it is; how many were settled and refused is
   * shown, and the selection cleared.
   */
  protected async settleSelected(): Promise<void> {
    if (this.busy) return;
    this.bulkSettled.set(null);
    this.bulkSettleError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    if (ids.length === 0) {
      this.bulkSettleError.set('Select the claims to settle.');
      return;
    }
    this.busy = true;
    try {
      const result = await this.claimsApi.settleAll(sheet, ids);
      if (this.session.sheet() !== sheet) return;
      for (const claim of result.settled) this.claimSaved(claim);
      this.selectedClaims.set(new Set());
      this.bulkSettled.set({ settled: result.settled.length, refused: result.refused });
    } catch {
      this.bulkSettleError.set('The claims could not be settled.');
    } finally {
      this.busy = false;
    }
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

  /** Claims pasted, in the order added: each listed first in turn, so the last is the newest. */
  protected pasted(claims: ClaimView[]): void {
    for (const claim of claims) this.claimSaved(claim);
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
    this.terms.set(person.terms === null ? '' : String(person.terms));
    this.error.set(null);
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set(this.today());
    this.terms.set('');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const typedTerms = this.terms().trim();
    if (typedTerms !== '' && !/^\d{1,5}$/.test(typedTerms)) {
      // Nothing is saved: an edit is closed, leaving the person as they were, and the form adds again.
      if (this.editing() !== null) this.reset();
      this.error.set('Terms are a whole number of days, or blank for none.');
      return;
    }
    const form = { name: this.name(), grade: this.grade(), terms: typedTerms === '' ? null : Number(typedTerms) };
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
      this.terms.set(current.terms === null ? '' : String(current.terms));
    } catch {
      // the form keeps what was typed; the refusal is still shown
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const [people, watched, claims, rates, periods] = await Promise.all([
      this.api.list(sheet),
      this.api.watched(sheet),
      this.claimsApi.list(sheet),
      this.ratesApi.list(sheet),
      this.periodsApi.list(sheet),
    ]);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) {
      this.claims.set(claims);
      this.rates.set(rates);
      this.periods.set(periods);
      this.watched.set(new Set(watched));
      this.people.set(people);
    }
  }
}
