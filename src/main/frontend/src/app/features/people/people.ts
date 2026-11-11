import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, Claims, PersonGrade, bound, byListed, claimDue, counts, deferred, dueFixed, gradeOn, isOpen, overdue, plusDays, statusOn, sumAmounts } from '../claims/claims';
import { Period, Periods, periodOn } from '../periods/periods';
import { Rate, Rates, capped, excessOver, rateOn, sumDues, trimmedSumDues } from '../rates/rates';
import { GradeSummary, PeriodSummary, StatusSummary, Summary } from '../summary/summary';
import { Header } from '../header/header';

interface Person {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the person was read. */
  grade: string;
  /** Every grade of the person, each from its from-date; the first one given from the beginning of time. */
  grades: PersonGrade[];
  /** The person's own terms in whole days; null when they have none. */
  terms: number | null;
  /** Raised on each save; the editor sends back the one it loaded. */
  version: number;
  /** How many statements the person has had. */
  statementCount: number;
  /** The due-total recorded by the last statement; null with none. */
  statedTotal: string | null;
  /** The date the last statement was taken as of (yyyy-mm-dd): the sheet's today then, or an earlier date given; null with none. */
  statementDate: string | null;
  /** Whether the person has a retirement date: they count as retired at once, whatever the date. */
  retired: boolean;
  /** The date the person is retired from (yyyy-mm-dd); null while not retired. */
  retiredFrom: string | null;
  /** Whether the person has any hold. */
  held: boolean;
  /** The person's holds by from-date: their OPEN and PENDING claims dated within one, unless a statement binds them,
   *  count in no live total; their SETTLED ones still do. */
  holds: PersonHold[];
  /** The ids of the other people on the sheet the person has named as their patrons. */
  patronIds: number[];
  /** Whether the signed-in user watches the person. */
  watched: boolean;
}

/** A hold of a person from its from-date to its to-date (yyyy-mm-dd), both inclusive; to null: it does not end. */
interface PersonHold {
  from: string;
  to: string | null;
}

/** Whether the date is within any of the holds. */
function heldOn(holds: PersonHold[], date: string): boolean {
  return holds.some((h) => h.from <= date && (h.to === null || date <= h.to));
}

/** What decides which of a person's open claims leave the live totals: their holds and their retirement date. */
type LeftOutBy = Pick<Person, 'holds' | 'retiredFrom'>;

/**
 * Whether an open claim of the person, not bound by a statement, is left out of the live totals: one dated within a
 * hold of theirs, or any once their retirement date is reached (the sheet's today on or after it).
 */
function leftOut(claim: Claim, person: LeftOutBy, today: string | null): boolean {
  const retired = person.retiredFrom !== null && today !== null && person.retiredFrom <= today;
  return !bound(claim) && (heldOn(person.holds, claim.date) || retired);
}

/**
 * Whether the claim counts in the live totals: an OPEN, PENDING or SETTLED one, but an OPEN or PENDING one dated within
 * a hold of its person, or any once their retirement date is reached, not unless a statement binds it, and one deferred
 * on the sheet's today not at all.
 */
function live(claim: Claim, person: LeftOutBy, today: string | null): boolean {
  return counts(claim, today) && !(isOpen(claim) && leftOut(claim, person, today));
}

/**
 * Whether the claim counts in a period's due-total: by the status it had at the end of the period's last day, an OPEN,
 * PENDING or SETTLED one, but one open then and still open now and dated within a hold of its person, or any once
 * their retirement date is reached, not unless a statement binds it, and one deferred on the sheet's today not at all.
 */
function liveAtEnd(claim: Claim, person: LeftOutBy, today: string | null, end: string): boolean {
  const status = statusOn(claim, end);
  if (status === null || deferred(claim, today)) return false;
  const open = status === 'OPEN' || status === 'PENDING';
  return (open || status === 'SETTLED') && !(open && isOpen(claim) && leftOut(claim, person, today));
}

/** A CSV field: wrapped in double quotes, any quote inside doubled, when it holds a comma, a quote or a line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

// The people on the active sheet, in the order they were added, each with their claims and what
// they have claimed and their due-total, the sheet's claimed-total and due-total, the grades' rates, and the one form that adds a person or, once a
// person's editor is opened, saves that person.
@Component({
  selector: 'app-people',
  imports: [Claims, Header, Periods, Rates, Summary],
  template: `
    <app-header
      [peopleCount]="people().length"
      [statuses]="statusSummary()"
      [dueTotal]="dueTotal()"
      [overdueTotal]="overdueTotal()"
    />
    <app-rates [grades]="grades" [rates]="rates()" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotals]="periodDueTotals()" (saved)="periodSaved($event)" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form (submit)="submit($event)">
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
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="person-form-grade-from">Grade from (blank: today)</label>
        <input
          id="person-form-grade-from"
          data-testid="person-form-grade-from"
          type="date"
          [value]="gradeFrom()"
          (input)="gradeFrom.set(value($event)); error.set(null)"
        />
        <label for="person-form-terms">Terms in days (blank: none)</label>
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
      <form aria-label="Quick add a claim" (submit)="quickAdd($event)">
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
          <p role="alert" data-testid="quick-error">{{ message }}</p>
        }
      </form>
      <form aria-label="Paste claims" (submit)="pasteClaims($event)">
        <label for="paste-claims">Paste claims, one per line: name,amount,date,note</label>
        <textarea
          id="paste-claims"
          data-testid="paste-claims"
          rows="4"
          [value]="pasteText()"
          (input)="pasteText.set(value($event)); pasteError.set(null)"
        ></textarea>
        <button type="submit" data-testid="paste-submit">Add pasted claims</button>
        @if (pasteAdded() !== null) {
          Added <strong data-testid="paste-added">{{ pasteAdded() }}</strong>
        }
        @if (pasteError(); as message) {
          <p role="alert" data-testid="paste-error">{{ message }}</p>
        }
      </form>
      <p>
        <input
          id="show-retired"
          type="checkbox"
          data-testid="show-retired"
          [checked]="session.showRetired()"
          (change)="session.showRetired.set(checked($event))"
        />
        <label for="show-retired">Show retired</label>
      </p>
      <table>
        <thead>
          <tr>
            <th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Terms</th><th scope="col">Claimed</th><th scope="col">Due</th>
            @for (per of periods(); track per.id) {
              <th scope="col">Due in {{ per.name }}</th>
            }
            <th scope="col">Statements</th><th scope="col">Last stated</th><th scope="col">Stated on</th>
            <th scope="col">Retired</th><th scope="col">Retired from</th><th scope="col">On hold</th><th scope="col">Active</th>
            <th scope="col">Overdue</th><th scope="col">Overdue total</th>
            <th scope="col">Patrons</th><th scope="col">Backing</th><th scope="col">Excess</th><th scope="col">Watched</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th><th scope="col">Claims</th>
          </tr>
        </thead>
        <tbody>
          @for (p of listed(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              <td data-testid="person-claimed">{{ claimedBy(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotalOf(p) }}</td>
              @for (per of periods(); track per.id) {
                <td [attr.data-testid]="'person-period-' + per.name + '-due-total'">{{ periodDueTotalOf(p, per) }}</td>
              }
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statedTotal ?? '' }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              <td data-testid="person-active">{{ isActive(p) ? 'yes' : 'no' }}</td>
              <td data-testid="person-overdue-count">{{ overdueOf(p).length }}</td>
              <td data-testid="person-overdue-total">{{ overdueTotalOf(p) }}</td>
              <td data-testid="person-patrons">{{ patronsOf(p) }}</td>
              <td data-testid="person-backing">{{ backingOf(p) }}</td>
              <td data-testid="person-excess">{{ excessOf(p) }}</td>
              <td data-testid="person-watched">{{ p.watched ? 'yes' : 'no' }}</td>
              <td>
                <button
                  type="button"
                  data-testid="person-watch"
                  [attr.aria-pressed]="p.watched"
                  [attr.aria-label]="(p.watched ? 'Stop watching ' : 'Watch ') + p.name"
                  (click)="watch(p)"
                >
                  {{ p.watched ? 'Unwatch' : 'Watch' }}
                </button>
                <button
                  type="button"
                  [attr.data-testid]="'person-edit-' + p.id"
                  [attr.aria-label]="'Edit ' + p.name"
                  (click)="edit(p)"
                >
                  Edit
                </button>
                <label [for]="'person-statement-asof-' + p.id" class="visually-hidden">Take a statement of {{ p.name }} as of</label>
                <input
                  [id]="'person-statement-asof-' + p.id"
                  type="date"
                  data-testid="person-statement-asof"
                  [value]="statementAsOfs()[p.id] ?? ''"
                  (input)="setStatementAsOf(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-statement"
                  [attr.aria-label]="'Take a statement of ' + p.name"
                  (click)="statement(p)"
                >
                  Statement
                </button>
                <button
                  type="button"
                  data-testid="person-unstate"
                  [attr.aria-label]="'Cancel the last statement of ' + p.name"
                  (click)="unstate(p)"
                >
                  Cancel statement
                </button>
                <label [for]="'person-retire-date-' + p.id" class="visually-hidden">Retire {{ p.name }} from</label>
                <input
                  [id]="'person-retire-date-' + p.id"
                  type="date"
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
                <ul class="holds">
                  @for (h of p.holds; track $index) {
                    <li data-testid="person-hold-line">Held {{ h.from }} to {{ h.to ?? 'open' }}</li>
                  }
                </ul>
                <label [for]="'person-hold-from-' + p.id" class="visually-hidden">Hold {{ p.name }} from</label>
                <input
                  [id]="'person-hold-from-' + p.id"
                  type="date"
                  data-testid="person-hold-from"
                  [value]="holdFroms()[p.id] ?? ''"
                  (input)="setHoldFrom(p.id, value($event))"
                />
                <label [for]="'person-hold-to-' + p.id" class="visually-hidden">Hold {{ p.name }} to</label>
                <input
                  [id]="'person-hold-to-' + p.id"
                  type="date"
                  data-testid="person-hold-to"
                  [value]="holdTos()[p.id] ?? ''"
                  (input)="setHoldTo(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-hold-toggle"
                  [attr.aria-label]="'Hold ' + p.name"
                  (click)="hold(p)"
                >
                  Hold
                </button>
                @if (rowError(); as e) {
                  @if (e.id === p.id) {
                    <p role="alert" data-testid="person-error">{{ e.message }}</p>
                  }
                }
                <label [for]="'person-patron-select-' + p.id" class="visually-hidden">Patron of {{ p.name }}</label>
                <select
                  [id]="'person-patron-select-' + p.id"
                  data-testid="person-patron-select"
                  [value]="patronChoices()[p.id] ?? ''"
                  (change)="setPatronChoice(p.id, value($event))"
                >
                  <option value="" [selected]="!patronChoices()[p.id]">Choose a patron</option>
                  @for (o of patronOptions(p); track o.id) {
                    <option [value]="'' + o.id" [selected]="patronChoices()[p.id] === '' + o.id">{{ o.name }}</option>
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
                @if (patronError(); as e) {
                  @if (e.id === p.id) {
                    <p role="alert" data-testid="person-patron-error">{{ e.message }}</p>
                  }
                }
              </td>
              <td
                app-claims
                [personId]="p.id"
                [personName]="p.name"
                [claims]="claimsOf(p.id)"
                [grades]="p.grades"
                [rates]="rates()"
                [periods]="periods()"
                [people]="people()"
                [retiredFrom]="p.retiredFrom"
                [selected]="selectedClaims()"
                (selectToggled)="selectClaim($event.id, $event.checked)"
                (saved)="claimSaved($event)"
              ></td>
            </tr>
          }
        </tbody>
      </table>
      <p>
        <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
        @if (bulkOpenResult(); as r) {
          Opened <strong data-testid="bulk-opened">{{ r.opened }}</strong>,
          refused <strong data-testid="bulk-refused">{{ r.refused }}</strong>
        }
      </p>
      @if (bulkOpenError(); as message) {
        <p role="alert" data-testid="bulk-open-error">{{ message }}</p>
      }
      <p>
        <button type="button" data-testid="bulk-settle" (click)="bulkSettle()">Settle selected claims</button>
        @if (bulkSettleResult(); as r) {
          Settled <strong data-testid="bulk-settled">{{ r.settled }}</strong>,
          refused <strong data-testid="bulk-settle-refused">{{ r.refused }}</strong>
        }
      </p>
      @if (bulkSettleError(); as message) {
        <p role="alert" data-testid="bulk-settle-error">{{ message }}</p>
      }
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong></p>
      <p>Overdue total <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>
    </section>
    <app-summary [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()" />
  `,
})
export class People {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly grades = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  /** The people listed: the retired ones only while "show retired" is on. Totals still count everyone. */
  protected readonly listed = computed(() =>
    this.session.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The date typed to retire each person from, by id; blank retires from the sheet's today. */
  protected readonly retireDates = signal<Record<number, string>>({});
  /** The date typed beside each person's statement button, by person id: blank takes the statement now. */
  protected readonly statementAsOfs = signal<Record<number, string>>({});
  /** The from-date typed for each person's next hold, by person id. */
  protected readonly holdFroms = signal<Record<number, string>>({});
  /** The to-date typed for each person's next hold, by person id. */
  protected readonly holdTos = signal<Record<number, string>>({});
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) {
      const list = by.get(c.personId);
      if (list) list.push(c);
      else by.set(c.personId, [c]);
    }
    return by;
  });
  protected readonly claimedTotal = computed(() => sumAmounts(this.people().map((p) => this.claimedBy(p.id))));
  /** The sheet's rates, by grade and then from-date. */
  protected readonly rates = signal<Rate[]>([]);
  /** The sheet's periods in date order. */
  protected readonly periods = signal<Period[]>([]);
  /**
   * Each person's live due-total for each period, by person id and then period name: the dues as shown now of their
   * claims dated in it that were OPEN, PENDING or SETTLED at the end of its last day, skipping the n/a ones; a period they have none in is missing.
   */
  private readonly livePersonPeriodDues = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const dues = new Map<number, Map<string, string[]>>();
    for (const c of this.claims()) {
      const period = periodOn(this.periods(), c.date);
      const person = byId.get(c.personId);
      if (!period || person === undefined || !liveAtEnd(c, person, this.session.today(), period.to)) continue;
      const theirs = dues.get(person.id) ?? new Map<string, string[]>();
      const list = theirs.get(period.name) ?? [];
      list.push(claimDue(c, this.rates(), person.grades, this.session));
      theirs.set(period.name, list);
      dues.set(person.id, theirs);
    }
    return new Map(
      [...dues].map(([id, theirs]) => [id, new Map([...theirs].map(([name, list]) => [name, sumDues(list)]))]),
    );
  });
  /** Each period's due-total by name: the sum of everyone's due-total for it. */
  protected readonly periodDueTotals = computed(
    () =>
      new Map(
        this.periods().map((per) => [per.name, sumDues(this.people().map((p) => this.periodDueTotalOf(p, per)))]),
      ),
  );
  /** Everyone's due-total added up, skipping the n/a ones and, with three or more, the highest and lowest. */
  protected readonly dueTotal = computed(() => trimmedSumDues(this.people().map((p) => this.dueTotalOf(p))));
  /** How many of everyone's counted claims are overdue. */
  protected readonly overdueCount = computed(() =>
    this.people().reduce((total, p) => total + this.overdueOf(p).length, 0),
  );
  /** Everyone's overdue-total added up, with three or more leaving out the highest and lowest. */
  protected readonly overdueTotal = computed(() => trimmedSumDues(this.people().map((p) => this.overdueTotalOf(p))));
  /** Per grade: the people not retired on it today, as the list shows their grade, and their due-totals added up. */
  protected readonly gradeSummary = computed<GradeSummary[]>(() =>
    this.grades.map((grade) => {
      const on = this.people().filter((p) => !p.retired && this.gradeOf(p) === grade);
      return {
        grade,
        count: on.length,
        dueTotal: sumDues(on.map((p) => this.dueTotalOf(p))),
        active: on.filter((p) => this.isActive(p)).length,
      };
    }),
  );
  /** Per period: its due-total as the period list shows it. */
  protected readonly periodSummary = computed<PeriodSummary[]>(() =>
    this.periods().map((p) => ({
      name: p.name,
      dueTotal: p.closedTotal ?? this.periodDueTotals().get(p.name) ?? '0.00',
    })),
  );
  /** Per status: how many of the sheet's claims have it. */
  protected readonly statusSummary = computed<StatusSummary[]>(() =>
    ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'].map((status) => ({
      status,
      count: this.claims().filter((c) => c.status === status).length,
    })),
  );
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  /** The date the form's grade applies from; blank for the sheet's today. */
  protected readonly gradeFrom = signal('');
  /** The person's own terms in whole days; blank for none. */
  protected readonly terms = signal('');
  /** The id of the person whose editor is open, or null when the form adds a person. */
  protected readonly editing = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion = 0;
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  /** The quick-add bar: a name and an amount, and the error of its last refused submit. */
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal<string | null>(null);
  private quickAdding = false;
  /** The paste box: its lines, how many its last paste added (null while it runs and before the first), its error. */
  protected readonly pasteText = signal('');
  protected readonly pasteAdded = signal<number | null>(null);
  protected readonly pasteError = signal<string | null>(null);
  private pasting = false;
  /** The error of the last action on a person's row that was refused, and the person it belongs to. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);
  /** The patron chosen on each person's row, by person id, as the chosen person's id; blank with none chosen. */
  protected readonly patronChoices = signal<Record<number, string>>({});
  /** The error of the last refused add of a patron, and the person it belongs to. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** The ids of the claims ticked to act on together. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** How many the last bulk open opened and refused; null while it runs and before the first. */
  protected readonly bulkOpenResult = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal<string | null>(null);
  /** How many the last bulk settle settled and refused; null while it runs and before the first. */
  protected readonly bulkSettleResult = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal<string | null>(null);
  private readonly byId = computed(() => new Map(this.people().map((p) => [p.id, p])));
  private reads = 0;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.people.set([]);
        this.claims.set([]);
        this.rates.set([]);
        this.periods.set([]);
        this.retireDates.set({});
        this.statementAsOfs.set({});
        this.holdFroms.set({});
        this.holdTos.set({});
        this.patronChoices.set({});
        this.patronError.set(null);
        this.selectedClaims.set(new Set());
        this.bulkOpenResult.set(null);
        this.bulkOpenError.set(null);
        this.bulkSettleResult.set(null);
        this.bulkSettleError.set(null);
        this.reset();
        this.quickName.set('');
        this.quickAmount.set('');
        this.quickError.set(null);
        this.pasteText.set('');
        this.pasteAdded.set(null);
        this.pasteError.set(null);
        if (sheet) void this.read(sheet);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected setHoldFrom(id: number, date: string): void {
    this.holdFroms.update((dates) => ({ ...dates, [id]: date }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  protected setHoldTo(id: number, date: string): void {
    this.holdTos.update((dates) => ({ ...dates, [id]: date }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  protected setStatementAsOf(id: number, date: string): void {
    this.statementAsOfs.update((dates) => ({ ...dates, [id]: date }));
  }

  protected setRetireDate(id: number, date: string): void {
    this.retireDates.update((dates) => ({ ...dates, [id]: date }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  /** The sum of the person's OPEN, PENDING and SETTLED claims' amounts, with two decimals; open ones dated within a hold, or any once their retirement date is reached, not unless a statement binds them. */
  protected claimedBy(personId: number): string {
    const person = this.people().find((p) => p.id === personId) ?? { holds: [], retiredFrom: null };
    return sumAmounts(this.claimsOf(personId).filter((c) => live(c, person, this.session.today())).map((c) => c.amount));
  }

  /** The person's grade in force on the sheet's today. */
  protected gradeOf(person: Person): string {
    const today = this.session.today();
    return (today === null ? undefined : gradeOn(person.grades, today)) ?? person.grade;
  }

  /** The grade's rate in force on the sheet's today, or undefined when none is. */
  protected rateOf(grade: string): string | undefined {
    const today = this.session.today();
    return today === null ? undefined : rateOn(this.rates(), grade, today);
  }

  /**
   * The sum of the person's OPEN, PENDING and SETTLED claims' dues, each already rounded, skipping the n/a ones; n/a when
   * their grade in force today has no rate in force today, unless a claim keeps a due fixed by settling or a statement
   * or has a due and is dated on or before today. Held down to the sheet's cap.
   */
  protected dueTotalOf(person: Person): string {
    return capped(this.uncappedDueTotalOf(person), this.session.cap());
  }

  /**
   * The person's due-total for the period: once it is closed or soft-closed, the figure fixed then (0.00 for a person added since);
   * while it is open, the dues of their counting claims dated in it.
   */
  protected periodDueTotalOf(person: Person, period: Period): string {
    if (period.closed || period.softClosed) return period.personTotals?.[person.id] ?? '0.00';
    return this.livePersonPeriodDues().get(person.id)?.get(period.name) ?? '0.00';
  }

  /** How far the sum of the person's dues is above the sheet's cap; 0.00 when it is not. */
  protected excessOf(person: Person): string {
    return excessOver(this.uncappedDueTotalOf(person), this.session.cap());
  }

  /** The sum of the person's dues as {@link dueTotalOf} has it, before the cap. */
  private uncappedDueTotalOf(person: Person): string {
    const counted = this.claimsOf(person.id).filter((c) => live(c, person, this.session.today()));
    const dues = counted.map((c) => claimDue(c, this.rates(), person.grades, this.session));
    const today = this.session.today();
    // A due under a grade the person had on an earlier date counts too.
    const anyDated = today !== null && counted.some((c, i) => dues[i] !== 'n/a' && c.date <= today);
    if (this.rateOf(this.gradeOf(person)) === undefined && !counted.some(dueFixed) && !anyDated) return 'n/a';
    return sumDues(dues);
  }

  /**
   * Whether the person is active: they have an OPEN or PENDING claim that counts in the live totals dated within the
   * 30 days up to and including the sheet's today.
   */
  protected isActive(person: Person): boolean {
    const today = this.session.today();
    if (today === null) return false;
    const since = plusDays(today, -29);
    return this.claimsOf(person.id).some(
      (c) => isOpen(c) && live(c, person, today) && since <= c.date && c.date <= today,
    );
  }

  /** The person's claims that count in the live totals and are overdue. */
  protected overdueOf(person: Person): Claim[] {
    return this.claimsOf(person.id).filter(
      (c) => live(c, person, this.session.today()) && overdue(c, this.session.today()),
    );
  }

  /** The sum of the dues of the person's overdue claims, skipping the n/a ones. */
  protected overdueTotalOf(person: Person): string {
    return sumDues(this.overdueOf(person).map((c) => claimDue(c, this.rates(), person.grades, this.session)));
  }

  /** The names of the person's patrons, sorted, comma and space separated. */
  protected patronsOf(person: Person): string {
    return this.patronsListOf(person)
      .map((p) => p.name)
      .sort((a, b) => a.localeCompare(b))
      .join(', ');
  }

  /**
   * The person's patrons' settled figures added up: each patron's SETTLED claims dated within the 90 days up to and
   * including the sheet's today, each at its frozen figure; 0.00 with none.
   */
  protected backingOf(person: Person): string {
    const today = this.session.today();
    if (today === null) return '0.00';
    const since = plusDays(today, -89);
    return sumDues(
      this.patronsListOf(person).flatMap((p) =>
        this.claimsOf(p.id)
          .filter((c) => c.status === 'SETTLED' && since <= c.date && c.date <= today)
          .map((c) => claimDue(c, this.rates(), p.grades, this.session)),
      ),
    );
  }

  /** The people the person could still name as a patron: everyone else on the sheet not already named. */
  protected patronOptions(person: Person): Person[] {
    return this.people().filter((p) => p.id !== person.id && !person.patronIds.includes(p.id));
  }

  protected setPatronChoice(id: number, choice: string): void {
    this.patronChoices.update((choices) => ({ ...choices, [id]: choice }));
    if (this.patronError()?.id === id) this.patronError.set(null);
  }

  /** Name the chosen person as the person's patron, then re-read so the backing shows what the store holds. */
  protected async addPatron(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const patronId = Number(this.patronChoices()[person.id] ?? '');
    if (!patronId) {
      this.patronError.set({ id: person.id, message: 'Choose a person to name as a patron.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/patrons`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { patronId }, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        this.patronChoices.update((choices) => ({ ...choices, [person.id]: '' }));
        void this.read(sheet);
      }
    } catch {
      const patron = this.byId().get(patronId)?.name ?? 'That person';
      this.patronError.set({ id: person.id, message: `${patron} could not be named a patron of ${person.name}.` });
    }
  }

  private patronsListOf(person: Person): Person[] {
    return person.patronIds.map((id) => this.byId().get(id)).filter((p): p is Person => p !== undefined);
  }

  /** Show a set rate at once, then re-read so the screen holds what the store does. */
  protected rateSaved(rate: Rate): void {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.rates.update((list) =>
      [...list.filter((r) => r.grade !== rate.grade || r.from !== rate.from), rate].sort(
        (a, b) => a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? ''),
      ),
    );
    void this.read(sheet);
  }

  /** Show an added period at once, then re-read so the screen holds what the store does. */
  protected periodSaved(period: Period): void {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.periods.update((list) =>
      [...list.filter((p) => p.id !== period.id), period].sort((a, b) => a.from.localeCompare(b.from)),
    );
    void this.read(sheet);
  }

  /** Show an added or saved claim at once, then re-read so the screen holds what the store does. */
  protected claimSaved(claim: Claim): void {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id)
        ? list.map((c) => (c.id === claim.id ? claim : c)).sort(byListed)
        : [claim, ...list].sort(byListed),
    );
    void this.read(sheet);
  }

  protected selectClaim(id: number, checked: boolean): void {
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
    this.bulkOpenError.set(null);
    this.bulkSettleError.set(null);
  }

  /** Open the ticked claims together, in list order; each that cannot be opened is left as it is. */
  protected async bulkOpen(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.bulkOpenResult.set(null);
    this.bulkOpenError.set(null);
    const ids = [...this.selectedClaims()];
    try {
      const result = await firstValueFrom(
        this.http.post<{ opened: number; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-open`,
          { ids },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedClaims.set(new Set());
      this.bulkOpenResult.set(result);
      await this.read(sheet);
    } catch {
      if (this.session.sheet() === sheet) this.bulkOpenError.set('The selected claims could not be opened.');
    }
  }

  /** Settle the ticked claims together, in list order; each that cannot be settled is left as it is. */
  protected async bulkSettle(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.bulkSettleResult.set(null);
    this.bulkSettleError.set(null);
    const ids = [...this.selectedClaims()];
    try {
      const result = await firstValueFrom(
        this.http.post<{ settled: number; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-settle`,
          { ids },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedClaims.set(new Set());
      this.bulkSettleResult.set(result);
      await this.read(sheet);
    } catch {
      if (this.session.sheet() === sheet) this.bulkSettleError.set('The selected claims could not be settled.');
    }
  }

  /** Start or stop watching the person as the signed-in user. */
  protected async watch(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/watch`;
    try {
      const saved = await firstValueFrom(
        this.http.put<Person>(url, { on: !person.watched }, this.session.headers()),
      );
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be watched or unwatched.` });
    }
  }

  /**
   * Take a statement of the person as of the typed date (blank: now), then re-read so their claims show the dues it
   * bound them to.
   */
  protected async statement(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const asOf = (this.statementAsOfs()[person.id] ?? '').trim();
    if (asOf && !/^\d{4}-\d{2}-\d{2}$/.test(asOf)) {
      this.rowError.set({ id: person.id, message: 'Pick a date to take the statement as of, or leave it blank for now.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/statement`;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(url, asOf ? { asOf } : null, this.session.headers()),
      );
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        this.statementAsOfs.update((dates) => ({ ...dates, [person.id]: '' }));
        void this.read(sheet);
      }
    } catch {
      this.rowError.set({ id: person.id, message: `A statement of ${person.name} could not be taken.` });
    }
  }

  /** Cancel the person's last statement, then re-read so their claims show their live dues again. */
  protected async unstate(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/statement`;
    try {
      const saved = await firstValueFrom(this.http.delete<Person>(url, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        void this.read(sheet);
      }
    } catch {
      this.rowError.set({ id: person.id, message: `The last statement of ${person.name} could not be cancelled.` });
    }
  }

  /** Retire the person from the typed date, or from the sheet's today when none is typed. */
  protected async retire(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const from = (this.retireDates()[person.id] ?? '').trim();
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.rowError.set({ id: person.id, message: 'Pick a date to retire from, or leave it blank for today.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/retire`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { from: from || null }, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        this.retireDates.update((dates) => ({ ...dates, [person.id]: '' }));
        void this.read(sheet);
      }
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be retired.` });
    }
  }

  /**
   * Hold the person from the typed from-date (blank: the sheet's today) to the typed to-date (blank: no end), then
   * re-read so the totals show what the store holds.
   */
  protected async hold(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const from = (this.holdFroms()[person.id] ?? '').trim();
    const to = (this.holdTos()[person.id] ?? '').trim();
    const date = /^\d{4}-\d{2}-\d{2}$/;
    if ((from && !date.test(from)) || (to && !date.test(to))) {
      this.rowError.set({ id: person.id, message: 'Pick the dates of the hold, or leave them blank.' });
      return;
    }
    if (from && to && to < from) {
      this.rowError.set({ id: person.id, message: 'A hold cannot end before it starts.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/hold`;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(url, { from: from || null, to: to || null }, this.session.headers()),
      );
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        this.holdFroms.update((dates) => ({ ...dates, [person.id]: '' }));
        this.holdTos.update((dates) => ({ ...dates, [person.id]: '' }));
        void this.read(sheet);
      }
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be held.` });
    }
  }

  protected edit(person: Person): void {
    this.rowError.set(null);
    this.patronError.set(null);
    this.editing.set(person.id);
    this.editingVersion = person.version;
    this.name.set(person.name);
    this.grade.set(this.gradeOf(person));
    this.gradeFrom.set('');
    this.terms.set(person.terms === null ? '' : String(person.terms));
    this.error.set(null);
  }

  /** Show why the form was refused; a refused save closes the editor, so the form adds a person again. */
  private refuse(message: string): void {
    if (this.editing() !== null) this.reset();
    this.error.set(message);
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set('');
    this.terms.set('');
    this.error.set(null);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const editing = this.editing();
    const body = {
      name: this.name(),
      grade: this.grade(),
      gradeFrom: this.gradeFrom() || null,
      terms: this.terms().trim() || null,
      version: editing === null ? null : this.editingVersion,
    };
    if (!body.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    if (body.terms !== null && !/^\d{1,5}$/.test(body.terms)) {
      this.refuse('Terms are a whole number of days, or blank for none.');
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    this.submitting = true;
    try {
      if (editing === null) {
        const added = await firstValueFrom(this.http.post<Person>(url, body, this.session.headers()));
        if (this.session.sheet() === sheet) this.people.update((list) => [...list, added]);
      } else {
        const saved = await firstValueFrom(
          this.http.put<Person>(`${url}/${editing}`, body, this.session.headers()),
        );
        if (this.session.sheet() === sheet) {
          this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        }
      }
      if (this.session.sheet() === sheet) {
        this.reset();
        // Re-read the list so a slower read started before this change cannot overwrite it.
        void this.read(sheet);
      }
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 412 && editing !== null) {
        this.error.set('Someone else saved this person in the meantime. The form now shows their values.');
        await this.reloadEditor(sheet, editing);
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.refuse('Another person on the sheet already has that name.');
      } else {
        this.refuse(editing === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    } finally {
      this.submitting = false;
    }
  }

  /**
   * Add a DRAFT claim of the amount, dated today with an empty note, for the person of the typed name; a name not on
   * the sheet adds the person first, with grade A. Refused as a whole when either would be.
   */
  protected async quickAdd(event: Event): Promise<void> {
    event.preventDefault();
    if (this.quickAdding) return;
    this.quickError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.quickName();
    if (!name.trim()) {
      this.quickError.set('Type the name of the person the claim is for.');
      return;
    }
    const amount = this.quickAmount().trim();
    if (!/^\d+(\.\d{1,2})?$/.test(amount)) {
      this.quickError.set('Type an amount of 0.00 or more, with at most two decimals.');
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/quick-add`;
    this.quickAdding = true;
    try {
      const added = await firstValueFrom(this.http.post<Claim>(url, { name, amount }, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.quickName.set('');
        this.quickAmount.set('');
        // Re-reads the sheet, which also brings in a person the quick-add created.
        this.claimSaved(added);
      }
    } catch {
      const closed = periodOn(this.periods(), this.session.today() ?? '');
      const why = closed?.closed ? ` Today is in the closed period ${closed.name}.` : '';
      this.quickError.set('The claim could not be added.' + why);
    } finally {
      this.quickAdding = false;
    }
  }

  /**
   * Add a DRAFT claim per pasted line of name,amount,date,note, in order, for the person of that name on the sheet.
   * Refused as a whole when any line would be.
   */
  protected async pasteClaims(event: Event): Promise<void> {
    event.preventDefault();
    if (this.pasting) return;
    this.pasteError.set(null);
    this.pasteAdded.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = this.pasteText();
    if (!text.trim()) {
      this.pasteError.set('Paste at least one line of name,amount,date,note.');
      return;
    }
    this.pasting = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ added: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/paste`,
          { text },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.pasteText.set('');
      await this.read(sheet);
      this.pasteAdded.set(result.added);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const detail = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' ? ` ${e.error.message}` : '';
      this.pasteError.set('The pasted claims could not be added; none were.' + detail);
    } finally {
      this.pasting = false;
    }
  }

  /**
   * Download everyone on the sheet, retired people included, in list order as a CSV file: name, the grade in force
   * today, the retired-from date or nothing, and the due-total as the list shows it.
   */
  protected exportCsv(): void {
    const lines = [['name', 'grade', 'retired-from', 'due-total']].concat(
      this.people().map((p) => [p.name, this.gradeOf(p), p.retiredFrom ?? '', this.dueTotalOf(p)]),
    );
    const text = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `people-${this.session.sheet() ?? 'sheet'}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  /** Re-read the sheet and show the stored values of the person in the open editor. */
  private async reloadEditor(sheet: string, id: number): Promise<void> {
    await this.read(sheet);
    const person = this.people().find((p) => p.id === id);
    if (person && this.editing() === id && this.session.sheet() === sheet) {
      this.editingVersion = person.version;
      this.name.set(person.name);
      this.grade.set(this.gradeOf(person));
      this.terms.set(person.terms === null ? '' : String(person.terms));
    }
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const url = `/api/sheets/${encodeURIComponent(sheet)}`;
    const [list, claims, rates, periods] = await Promise.all([
      firstValueFrom(this.http.get<Person[]>(`${url}/people`, this.session.headers())),
      firstValueFrom(this.http.get<Claim[]>(`${url}/claims`, this.session.headers())),
      firstValueFrom(this.http.get<Rate[]>(`${url}/rates`, this.session.headers())),
      firstValueFrom(this.http.get<Period[]>(`${url}/periods`, this.session.headers())),
    ]).catch(() => [null, null, null, null]);
    // A later read wins over a slower earlier one.
    if (list && claims && rates && periods && read === this.reads && this.session.sheet() === sheet) {
      this.people.set(list);
      this.claims.set(claims);
      this.rates.set(rates);
      this.periods.set(periods);
    }
  }
}
