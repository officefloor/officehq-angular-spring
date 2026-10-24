import { HttpErrorResponse } from '@angular/common/http';
import { Component, DOCUMENT, computed, effect, inject, signal, type WritableSignal, untracked } from '@angular/core';
import { Session } from '../../core/session';
import { Claims } from './claims';
import { Delegations } from './delegations';
import { Holidays } from './holidays';
import { Header } from './header';
import { Claim, ClaimStatus, ClaimsService, cents, counts, deferred, isOpen, money, overdue, statusOn } from './claims.service';
import { GRADES, Grade, Patron, PeopleService, Person, gradeOn, heldOn } from './people.service';
import { Periods } from './periods';
import { Period, PeriodsService, periodOn } from './periods.service';
import { Rates } from './rates';
import { Graces, Lateness, Rate, RatesService, Rounding, claimShownCents, overdueFrom, rateOn } from './rates.service';
import { Settings } from './settings';
import { Dated, SettingsService, inForce } from './settings.service';
import { Statement, StatementsService } from './statements.service';
import { Summary, SummaryPerson } from './summary';

// The people on the active sheet, in the order they were added, and the form that adds a person
// or, once a person's editor is opened, saves changes to them. Each person's row lists their claims
// and shows what they have claimed (their OPEN, PENDING and SETTLED claims) and what is due on it: the sum of
// those claims' due figures; the sheet's
// claimed-total and due-total add up everyone's (a due-total that is n/a is skipped). A statement
// taken of a person binds each of their counting claims to the figure it shows then; the row shows
// how many statements they have had and the last one's due-total and date. A person can be retired
// from a date; retired people are left out of the list unless "show retired" is on, but their claims
// keep counting in every total. A person can be put on several holds over time, each from a date (the
// sheet's today when left blank) and to an optional date: their OPEN and PENDING claims dated within a
// hold count in no live total (theirs, the sheet's or a period's) unless a statement binds them;
// SETTLED ones and those dated outside every hold still do. A hold ends on its to-date.
// A claim is due by the date fixed when it was added (see the claims); an OPEN or PENDING one due by a date before the sheet's
// today is overdue. Each row, and the sheet, shows how many of the claims that count are overdue and
// what is due on them. An overdue claim's due gains, for every full 30 days it is overdue, the sheet's
// late percent in force on that boundary date of its figure, each rounded like the claim; a statement never binds that charge, settling fixes it in. The floor is
// applied last: the live due is the greater of the floor in force on the claim's date and its figure plus that charge.
// A person can name other people on the sheet as their patrons, one at a time from their row; the
// row shows their patrons' names, sorted, and their backing: the figures of their patrons' SETTLED
// claims dated within the 90 days up to and including the sheet's today, each at its frozen figure,
// added up (0.00 with none). A person's due-total is at most the sheet's cap: when the
// sum of their figures is above it, the row shows the cap and the difference as their excess; every
// total of due-totals (the sheet's, a grade's, the export) adds up the capped figures.
// The sheet's due-total and overdue-total leave out the single highest and the single lowest person's
// figure when three or more people have one (a due-total of n/a is no figure).
// Each row splits its figures before the cap into a settled-total (its counting SETTLED claims, at
// their frozen figures) and an open-total (its counting OPEN and PENDING claims); the sheet's
// settled-total and open-total simply add up everyone's.
// Each signed-in user can watch people from their row; the row shows whether that user watches them.
@Component({
  selector: 'app-people',
  imports: [Claims, Delegations, Header, Holidays, Periods, Rates, Settings, Summary],
  template: `
    <app-header
      [peopleCount]="people().length"
      [claims]="claims()"
      [dueTotal]="dueTotal()"
      [overdueTotal]="money(overdueTotal().cents)"
    />
    <app-settings
      (floorSaved)="floors.set($event)"
      (capSaved)="caps.set($event)"
      (latePctSaved)="latePcts.set($event)"
      (graceSaved)="graces.set($event)"
      (policyDateSaved)="policyDate.set($event)"
    />
    <app-delegations />
    <app-holidays />
    <app-rates [rates]="rates()" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotals]="periodDueTotals()" (added)="periodAdded($event)" (closed)="periodClosed($event)" (reopened)="periodClosed($event)" />
    <app-summary
      [people]="summaryPeople()"
      [periods]="periods()"
      [periodDueTotals]="periodDueTotals()"
      [claims]="claims()"
    />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>

      <form (submit)="submit($event)" aria-label="Person" data-testid="person-form">
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
          (change)="grade.set(gradeOf($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="person-form-grade-from">Grade from</label>
        <input
          id="person-form-grade-from"
          type="date"
          data-testid="person-form-grade-from"
          [value]="gradeFrom()"
          (input)="gradeFrom.set(value($event)); error.set(null)"
        />
        <label for="person-form-terms">Terms (business days)</label>
        <input
          id="person-form-terms"
          inputmode="numeric"
          data-testid="person-form-terms"
          [value]="terms()"
          (input)="terms.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="person-form-error">{{ message }}</p>
        }
      </form>

      <form (submit)="quickAdd($event)" aria-label="Quick add a claim" data-testid="quick-form">
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
          <p role="alert" class="error" data-testid="quick-error">{{ message }}</p>
        }
      </form>

      <form (submit)="paste($event)" aria-label="Paste claims" data-testid="paste-form">
        <label for="paste-claims">Claims to paste, one per line: name,amount,date,note (a note may end with " #reference")</label>
        <textarea
          id="paste-claims"
          data-testid="paste-claims"
          rows="4"
          [value]="pasteText()"
          (input)="pasteText.set(value($event)); pasteError.set(null)"
        ></textarea>
        <button type="submit" data-testid="paste-submit">Paste claims</button>
        @if (pasteAdded() !== null) {
          Added <span data-testid="paste-added">{{ pasteAdded() }}</span>
        }
        @if (pasteError(); as message) {
          <p role="alert" class="error" data-testid="paste-error">{{ message }}</p>
        }
      </form>

      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="showRetired()"
          (change)="showRetired.set(checked($event))"
        />
        Show retired
      </label>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>

      <p>
        <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
        @if (bulkOpened(); as result) {
          Opened <span data-testid="bulk-opened">{{ result.opened }}</span>,
          refused <span data-testid="bulk-refused">{{ result.refused }}</span>
        }
      </p>

      <p>
        <button type="button" data-testid="bulk-settle" (click)="bulkSettle()">Settle selected claims</button>
        @if (bulkSettled(); as result) {
          Settled <span data-testid="bulk-settled">{{ result.settled }}</span>,
          refused <span data-testid="bulk-settle-refused">{{ result.refused }}</span>
        }
      </p>

      <p>
        <label for="bulk-grade">Grade for selected people</label>
        <select
          id="bulk-grade"
          data-testid="bulk-grade"
          [value]="bulkGradeChoice()"
          (change)="bulkGradeChoice.set(gradeOf($event)); bulkGradeError.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="bulkGradeChoice() === g">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">From</label>
        <input
          id="bulk-grade-from"
          data-testid="bulk-grade-from"
          placeholder="YYYY-MM-DD"
          [value]="bulkGradeFrom()"
          (input)="bulkGradeFrom.set(value($event)); bulkGradeError.set(null)"
        />
        <button type="button" data-testid="bulk-grade-apply" (click)="bulkGrade()">Grade selected people</button>
        @if (bulkGraded() !== null) {
          Changed <span data-testid="bulk-graded">{{ bulkGraded() }}</span>
        }
        @if (bulkGradeError(); as message) {
          <span role="alert" class="error" data-testid="person-error">{{ message }}</span>
        }
      </p>

      @if (people().length === 0) {
        <p data-testid="home-empty">Nothing here yet.</p>
      } @else {
        <table data-testid="people-list">
          <thead>
            <tr>
              <th scope="col"><span class="visually-hidden">Select</span></th>
              <th scope="col">Name</th>
              <th scope="col">Grade</th>
              <th scope="col">Terms</th>
              <th scope="col">Claimed</th>
              <th scope="col">Due</th>
              <th scope="col">Settled due</th>
              <th scope="col">Open due</th>
              @for (pd of periods(); track pd.id) {
                <th scope="col">Due in {{ pd.name }}</th>
              }
              <th scope="col">Excess</th>
              <th scope="col">Overdue</th>
              <th scope="col">Overdue due</th>
              <th scope="col">Due aged 0–30 days</th>
              <th scope="col">Due aged 31–60 days</th>
              <th scope="col">Due aged 61+ days</th>
              <th scope="col">Statements</th>
              <th scope="col">Last stated</th>
              <th scope="col">Stated on</th>
              <th scope="col">Retired</th>
              <th scope="col">Retired from</th>
              <th scope="col">On hold</th>
              <th scope="col">Active</th>
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
                <td data-testid="person-grade">{{ gradeNow(p) }}</td>
                <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
                <td data-testid="person-claimed">{{ claimedOf(p) }}</td>
                <td data-testid="person-due-total">{{ dueTotalOf(p) ?? 'n/a' }}</td>
                @let split = splitOf(p);
                <td data-testid="person-settled-total">{{ money(split.settled) }}</td>
                <td data-testid="person-open-total">{{ money(split.open) }}</td>
                @for (pd of periods(); track pd.id) {
                  <td [attr.data-testid]="'person-period-' + pd.name + '-due-total'">{{ money(personPeriodCents().get(p.id)?.[pd.name] ?? 0) }}</td>
                }
                <td data-testid="person-excess">{{ excessOf(p) }}</td>
                <td data-testid="person-overdue-count">{{ overdueOf(p).count }}</td>
                <td data-testid="person-overdue-total">{{ money(overdueOf(p).cents) }}</td>
                @let aged = agedOf(p);
                <td data-testid="person-aged-0-30">{{ money(aged[0]) }}</td>
                <td data-testid="person-aged-31-60">{{ money(aged[1]) }}</td>
                <td data-testid="person-aged-61">{{ money(aged[2]) }}</td>
                <td data-testid="person-statement-count">{{ statementsOf(p.id).length }}</td>
                <td data-testid="person-stated-total">{{ lastStatementOf(p.id)?.dueTotal ?? '' }}</td>
                <td data-testid="person-statement-date">{{ lastStatementOf(p.id)?.date ?? '' }}</td>
                <td data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</td>
                <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
                <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
                <td data-testid="person-active">{{ isActive(p) ? 'yes' : 'no' }}</td>
                <td data-testid="person-watched">{{ watched().has(p.id) ? 'yes' : 'no' }}</td>
                <td data-testid="person-patrons">{{ patronNamesOf(p.id) }}</td>
                <td data-testid="person-backing">{{ backingOf(p.id) }}</td>
                <td>
                  <app-claims
                    [personId]="p.id"
                    [personName]="p.name"
                    [claims]="claimsOf(p.id)"
                    [grades]="p.grades"
                    [rates]="rates()"
                    [floors]="floors()"
                    [periods]="periods()"
                    [latePcts]="latePcts()"
                    [graces]="graces()"
                    [policyDate]="policyDateInForce()"
                    [others]="othersOf(p.id)"
                    [selected]="selectedClaims()"
                    (selectToggled)="selectClaim($event.id, $event.selected)"
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
                  <label [for]="'person-statement-asof-' + p.id">Statement as of</label>
                  <input
                    [id]="'person-statement-asof-' + p.id"
                    data-testid="person-statement-asof"
                    placeholder="YYYY-MM-DD"
                    [value]="statementAsOfs()[p.id] ?? ''"
                    (input)="setStatementAsOf(p.id, value($event))"
                  />
                  <button
                    type="button"
                    data-testid="person-statement"
                    [attr.aria-label]="'Take a statement of ' + p.name"
                    (click)="takeStatement(p)"
                  >
                    Statement
                  </button>
                  <button
                    type="button"
                    data-testid="person-unstate"
                    [attr.aria-label]="'Cancel the last statement of ' + p.name"
                    (click)="cancelStatement(p)"
                  >
                    Cancel statement
                  </button>
                  <label [for]="'person-retire-date-' + p.id">Retire from</label>
                  <input
                    [id]="'person-retire-date-' + p.id"
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
                      <li data-testid="person-hold-line">held from {{ h.from }}{{ h.to ? ' to ' + h.to : '' }}</li>
                    }
                  </ul>
                  <label [for]="'person-hold-from-' + p.id">Hold from</label>
                  <input
                    [id]="'person-hold-from-' + p.id"
                    data-testid="person-hold-from"
                    [value]="holdFroms()[p.id] ?? ''"
                    (input)="setHoldDate(holdFroms, p.id, value($event))"
                  />
                  <label [for]="'person-hold-to-' + p.id">Hold to</label>
                  <input
                    [id]="'person-hold-to-' + p.id"
                    data-testid="person-hold-to"
                    [value]="holdTos()[p.id] ?? ''"
                    (input)="setHoldDate(holdTos, p.id, value($event))"
                  />
                  <button
                    type="button"
                    data-testid="person-hold-toggle"
                    [attr.aria-label]="'Hold ' + p.name"
                    (click)="hold(p)"
                  >
                    Hold
                  </button>
                  <button
                    type="button"
                    data-testid="person-watch"
                    [attr.aria-label]="(watched().has(p.id) ? 'Stop watching ' : 'Watch ') + p.name"
                    [attr.aria-pressed]="watched().has(p.id)"
                    (click)="toggleWatch(p)"
                  >
                    {{ watched().has(p.id) ? 'Unwatch' : 'Watch' }}
                  </button>
                  @if (rowErrors()[p.id]; as message) {
                    <p role="alert" class="error" data-testid="person-error">{{ message }}</p>
                  }
                  <label [for]="'person-patron-select-' + p.id">Patron</label>
                  <select
                    [id]="'person-patron-select-' + p.id"
                    data-testid="person-patron-select"
                    [value]="patronChoices()[p.id] ?? ''"
                    (change)="setPatronChoice(p.id, value($event))"
                  >
                    <option value="" [selected]="!patronChoices()[p.id]">Choose a patron</option>
                    @for (o of people(); track o.id) {
                      @if (o.id !== p.id) {
                        <option [value]="o.id" [selected]="patronChoices()[p.id] === '' + o.id">{{ o.name }}</option>
                      }
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
                  @if (patronErrors()[p.id]; as message) {
                    <p role="alert" class="error" data-testid="person-patron-error">{{ message }}</p>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
      <p>
        Claimed in total: <span data-testid="rollup-claimed-total">{{ claimedTotal() }}</span>
      </p>
      <p>
        Due in total: <span data-testid="rollup-due-total">{{ dueTotal() }}</span>
      </p>
      <p>
        Settled due in total: <span data-testid="rollup-settled-total">{{ money(splitTotal().settled) }}</span>,
        open due in total: <span data-testid="rollup-open-total">{{ money(splitTotal().open) }}</span>
      </p>
      <p>
        Overdue: <span data-testid="rollup-overdue-count">{{ overdueTotal().count }}</span>,
        due on them <span data-testid="rollup-overdue-total">{{ money(overdueTotal().cents) }}</span>
      </p>
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 1rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
    table {
      border-collapse: collapse;
    }
    th,
    td {
      text-align: left;
      padding: 0.25rem 0.75rem 0.25rem 0;
      white-space: pre;
      vertical-align: top;
    }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class People {
  private readonly session = inject(Session);
  private readonly service = inject(PeopleService);
  private readonly claimsService = inject(ClaimsService);
  private readonly ratesService = inject(RatesService);
  private readonly settingsService = inject(SettingsService);
  private readonly statementsService = inject(StatementsService);
  private readonly periodsService = inject(PeriodsService);
  private readonly document = inject(DOCUMENT);

  protected readonly grades = GRADES;
  protected readonly money = money;
  protected readonly people = signal<Person[]>([]);
  /** Whether retired people are listed; off when the screen is first opened, kept across sheet loads. */
  protected readonly showRetired = signal(false);
  /** The people listed: everyone, or only those not retired unless "show retired" is on. */
  protected readonly shownPeople = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The retire-from date typed on each person's row, by person id. */
  protected readonly retireDates = signal<Record<number, string>>({});
  /** The statement as-of date typed on each person's row, by person id. */
  protected readonly statementAsOfs = signal<Record<number, string>>({});
  /** The hold from- and to-dates typed on each person's row, by person id. */
  protected readonly holdFroms = signal<Record<number, string>>({});
  protected readonly holdTos = signal<Record<number, string>>({});
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  protected readonly claimedTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + this.claimedCentsOf(p), 0)),
  );
  /** The sheet's statements, in the order they were taken. */
  protected readonly statements = signal<Statement[]>([]);
  private readonly statementsByPerson = computed(() => {
    const by = new Map<number, Statement[]>();
    for (const s of this.statements()) by.set(s.personId, [...(by.get(s.personId) ?? []), s]);
    return by;
  });
  /** The ids of the people the signed-in user watches. */
  protected readonly watched = signal<ReadonlySet<number>>(new Set());
  /** The sheet's patrons, in the order they were named. */
  protected readonly patrons = signal<Patron[]>([]);
  /** The patron chosen on each person's row (a person id), by person id. */
  protected readonly patronChoices = signal<Record<number, string>>({});
  /** The error of the last refused patron added on each person's row, by person id. */
  protected readonly patronErrors = signal<Record<number, string>>({});
  /** Each person's patrons' names, sorted and comma separated, by person id. */
  private readonly patronNames = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p.name]));
    const by = new Map<number, string[]>();
    for (const pt of this.patrons()) {
      const name = byId.get(pt.patronId);
      if (name !== undefined) by.set(pt.personId, [...(by.get(pt.personId) ?? []), name]);
    }
    return new Map([...by].map(([id, names]) => [id, names.sort().join(', ')]));
  });
  /** Each person's backing, by person id: the figures of their patrons' SETTLED claims dated within
   *  the 90 days up to and including the sheet's today, each as it shows (its frozen figure), added
   *  up (n/a ones skipped); 0 with none. */
  private readonly backings = computed(() => {
    const today = this.session.today();
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const by = new Map<number, number>();
    for (const pt of this.patrons()) {
      const patron = byId.get(pt.patronId);
      const settled = patron === undefined || today === null ? 0 : this.recentSettledCentsOf(patron, today);
      by.set(pt.personId, (by.get(pt.personId) ?? 0) + settled);
    }
    return by;
  });
  /** The error of the last refused action on each person's row, by person id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** The sheet's rates, by grade. */
  protected readonly rates = signal<Rate[]>([]);
  /** Every floor recorded on the sheet, each from its date, oldest first: the least a claim's live due
   *  can be is the one in force on its date. */
  protected readonly floors = signal<Dated[]>([]);
  /** Every cap recorded on the sheet, each from its date, oldest first; null records no cap. */
  protected readonly caps = signal<Dated<string | null>[]>([]);
  /** The sheet's cap in force on its today, in cents: the most a person's due-total can be; null when
   *  there is none. */
  protected readonly cap = computed(() => {
    const today = this.session.today();
    const cap = today === null ? null : inForce(this.caps(), today, null);
    return cap === null ? null : cents(cap);
  });
  /** Every late percent recorded on the sheet, each from its date, oldest first, with two decimals. */
  protected readonly latePcts = signal<Dated[]>([]);
  /** Every grace recorded on the sheet for each grade, each from its date, oldest first. */
  protected readonly graces = signal<Graces>({});
  /** The sheet's policy date as set; null when it is the sheet's today. */
  protected readonly policyDate = signal<string | null>(null);
  /** The policy date in force: the one set, or the sheet's today. */
  protected readonly policyDateInForce = computed(() => this.policyDate() ?? this.session.today());
  /** What the claims' late charges are worked from. */
  private readonly lateness = computed<Lateness>(() => ({
    today: this.session.today(),
    latePcts: this.latePcts(),
    graces: this.graces(),
  }));
  /** The sheet's periods. */
  protected readonly periods = signal<Period[]>([]);
  /** Each person's due-total for each period, in cents, by person id and period name. While a period
   *  is open it is what is due on their claims whose due-by falls in it that counted at the end of its last day,
   *  as each claim shows it now (a claim with nothing due is skipped); once it is closed it is the figure fixed then (0 for a person
   *  added later). */
  protected readonly personPeriodCents = computed(() => {
    const by = new Map<number, Record<string, number>>();
    for (const person of this.people()) {
      const sums: Record<string, number> = {};
      for (const p of this.periods()) {
        sums[p.name] = p.closed ? cents(p.closedPersonTotals[person.id] ?? '0.00') : 0;
      }
      for (const c of this.claimsOf(person.id)) {
        const period = periodOn(this.periods(), c.dueBy);
        if (period === null || period.closed || !this.countsAtEnd(c, person, period.to)) continue;
        sums[period.name] += claimShownCents(c, this.rates(), person.grades, this.floors(), this.lateness(), this.roundingOf(person.id)) ?? 0;
      }
      by.set(person.id, sums);
    }
    return by;
  });
  /** Each period's due-total, by name: the sum of the people's figures for it. */
  protected readonly periodDueTotals = computed(() => {
    const sums: Record<string, number> = {};
    for (const p of this.periods()) sums[p.name] = 0;
    for (const figures of this.personPeriodCents().values()) {
      for (const [name, sum] of Object.entries(figures)) sums[name] += sum;
    }
    return Object.fromEntries(Object.entries(sums).map(([name, sum]) => [name, money(sum)]));
  });
  /** Everyone as the summary counts them: their grade today, whether retired, their due-total, and
   *  whether they are active. */
  protected readonly summaryPeople = computed<SummaryPerson[]>(() =>
    this.people().map((p) => ({ grade: this.gradeNow(p), retired: p.retired, dueCents: this.dueCentsOf(p), active: this.isActive(p) })),
  );
  /** The sheet's due-total: everyone's due-totals added up (n/a ones skipped), trimmed. */
  protected readonly dueTotal = computed(() =>
    money(trimmedSum(this.people().map((p) => this.dueCentsOf(p)).filter((c): c is number => c !== null))),
  );
  /** Everyone's settled-totals and open-totals, in cents, each simply added up (not trimmed). */
  protected readonly splitTotal = computed(() => {
    const each = this.people().map((p) => this.splitOf(p));
    return {
      settled: each.reduce((sum, s) => sum + s.settled, 0),
      open: each.reduce((sum, s) => sum + s.open, 0),
    };
  });
  /** How many of everyone's counting claims are overdue, and what is due on them, in cents (trimmed). */
  protected readonly overdueTotal = computed(() => {
    const each = this.people().map((p) => this.overdueOf(p));
    return {
      count: each.reduce((sum, o) => sum + o.count, 0),
      cents: trimmedSum(each.map((o) => o.cents)),
    };
  });
  protected readonly name = signal('');
  protected readonly grade = signal<Grade>('A');
  /** The date a save gives the grade from; blank for the sheet's today. */
  protected readonly gradeFrom = signal('');
  /** The person's own terms in days, as typed; blank for none. */
  protected readonly terms = signal('');
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion = 0;
  /** Counts the times the editor was opened, so a save answered after it was opened again leaves it open. */
  private editorOpenings = 0;
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal<string | null>(null);
  /** A quick-add is waiting for its answer; another meanwhile is ignored. */
  private quickAdding = false;
  private readonly sheet = computed(() => this.session.sheetId());
  protected readonly pasteText = signal('');
  /** How many claims the last paste added; null before one, and while one runs. */
  protected readonly pasteAdded = signal<number | null>(null);
  protected readonly pasteError = signal<string | null>(null);
  /** A paste is waiting for its answer; another meanwhile is ignored. */
  private pasting = false;

  /** The ids of the claims selected for a bulk action. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** How the last bulk open went; null before one, and while one runs. */
  protected readonly bulkOpened = signal<{ opened: number; refused: number } | null>(null);
  /** A bulk open is waiting for its answer; another meanwhile is ignored. */
  private bulkOpening = false;
  /** How the last bulk settle went; null before one, and while one runs. */
  protected readonly bulkSettled = signal<{ settled: number; refused: number } | null>(null);
  /** A bulk settle is waiting for its answer; another meanwhile is ignored. */
  private bulkSettling = false;
  /** The ids of the people selected for a bulk grade. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  /** The grade a bulk grade gives. */
  protected readonly bulkGradeChoice = signal<Grade>('A');
  /** The date a bulk grade gives it from; blank for the sheet's today. */
  protected readonly bulkGradeFrom = signal('');
  /** How many people the last bulk grade changed; null before one, and while one runs. */
  protected readonly bulkGraded = signal<number | null>(null);
  protected readonly bulkGradeError = signal<string | null>(null);
  /** A bulk grade is waiting for its answer; another meanwhile is ignored. */
  private bulkGrading = false;

  constructor() {
    // Re-read the sheet's people from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.sheet();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.rates.set([]);
      this.floors.set([]);
      this.caps.set([]);
      this.latePcts.set([]);
      this.graces.set({});
      this.policyDate.set(null);
      this.statements.set([]);
      this.periods.set([]);
      this.rowErrors.set({});
      this.selectedClaims.set(new Set());
      this.bulkOpened.set(null);
      this.bulkSettled.set(null);
      this.selectedPeople.set(new Set());
      this.bulkGradeChoice.set('A');
      this.bulkGradeFrom.set('');
      this.bulkGraded.set(null);
      this.bulkGradeError.set(null);
      this.retireDates.set({});
      this.statementAsOfs.set({});
      this.holdFroms.set({});
      this.holdTos.set({});
      this.patrons.set([]);
      this.watched.set(new Set());
      this.patronChoices.set({});
      this.patronErrors.set({});
      this.reset();
      this.quickName.set('');
      this.quickAmount.set('');
      this.quickError.set(null);
      this.pasteText.set('');
      this.pasteAdded.set(null);
      this.pasteError.set(null);
      if (sheet) void this.read(sheet);
    });
    // A change of the sheet's today may close periods its grace days have passed: re-read them.
    let shownToday: string | null = null;
    effect(() => {
      const today = this.session.today();
      const sheet = untracked(() => this.sheet());
      if (shownToday !== null && today !== shownToday && sheet) void this.readPeriods(sheet);
      shownToday = today;
    });
  }

  private async readPeriods(sheet: string): Promise<void> {
    try {
      const periods = await this.periodsService.list(sheet);
      if (this.sheet() === sheet) this.periods.set(periods);
    } catch {
      // The periods could not be read; they show as they were.
    }
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected gradeOf(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  /** The figures of the person's SETTLED claims dated within the 90 days up to and including
   *  `today`, each as it shows, added up (n/a ones skipped). */
  private recentSettledCentsOf(person: Person, today: string): number {
    const from = daysBefore(today, 89);
    return this.claimsOf(person.id)
      .filter((c) => c.status === 'SETTLED' && c.date >= from && c.date <= today)
      .reduce(
        (sum, c) =>
          sum + (claimShownCents(c, this.rates(), person.grades, this.floors(), this.lateness(), this.roundingOf(person.id)) ?? 0),
        0,
      );
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected statementsOf(personId: number): Statement[] {
    return this.statementsByPerson().get(personId) ?? [];
  }

  protected lastStatementOf(personId: number): Statement | null {
    return this.statementsOf(personId).at(-1) ?? null;
  }

  protected setStatementAsOf(id: number, date: string): void {
    this.statementAsOfs.update((dates) => ({ ...dates, [id]: date }));
    this.clearRowError(id);
  }

  /** Take a statement of the person, as of the date typed on their row (now when blank), and show their
   *  claims as it bound or unbound them. */
  protected async takeStatement(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    const asOf = (this.statementAsOfs()[person.id] ?? '').trim();
    if (asOf !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(asOf)) {
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'Type a date as YYYY-MM-DD.' }));
      return;
    }
    // The as-of date goes with this statement only, taken or refused: the next is taken now unless typed again.
    this.statementAsOfs.update(({ [person.id]: _, ...rest }) => rest);
    try {
      const taken = await this.statementsService.take(sheet, person.id, asOf || null);
      if (this.sheet() !== sheet) return;
      for (const c of taken.claims) this.claimSaved(c);
      this.statements.update((list) => [...list, taken.statement]);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'A statement could not be taken.' }));
    }
  }

  /** Cancel the person's last statement, and show their claims live again where it bound them. */
  protected async cancelStatement(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    try {
      const cancelled = await this.statementsService.cancelLast(sheet, person.id);
      if (this.sheet() !== sheet) return;
      for (const c of cancelled.claims) this.claimSaved(c);
      this.statements.update((list) => list.filter((s) => s.id !== cancelled.statement.id));
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'That statement could not be cancelled.' }));
    }
  }

  protected setRetireDate(id: number, date: string): void {
    this.retireDates.update((dates) => ({ ...dates, [id]: date }));
    this.clearRowError(id);
  }

  /** Retire the person from the date typed on their row (the sheet's today when blank). */
  protected async retire(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    const from = (this.retireDates()[person.id] ?? '').trim();
    if (from !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'Type a date as YYYY-MM-DD.' }));
      return;
    }
    try {
      const retired = await this.service.retire(sheet, person.id, from || null);
      if (this.sheet() !== sheet) return;
      this.put(retired);
      this.retireDates.update(({ [person.id]: _, ...rest }) => rest);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'That person could not be retired.' }));
    }
  }

  private clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
    this.patronErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /** Start or stop the signed-in user watching the person. */
  protected async toggleWatch(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    const on = !this.watched().has(person.id);
    try {
      await this.service.watch(sheet, person.id, on);
      if (this.sheet() !== sheet) return;
      this.watched.update((ids) => {
        const next = new Set(ids);
        if (on) next.add(person.id);
        else next.delete(person.id);
        return next;
      });
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [person.id]: on ? 'That person could not be watched.' : 'That person could not be unwatched.',
      }));
    }
  }

  protected patronNamesOf(personId: number): string {
    return this.patronNames().get(personId) ?? '';
  }

  protected backingOf(personId: number): string {
    return money(this.backings().get(personId) ?? 0);
  }

  protected setPatronChoice(id: number, patronId: string): void {
    this.patronChoices.update((choices) => ({ ...choices, [id]: patronId }));
    this.clearRowError(id);
  }

  /** Name the person chosen on the row as the person's patron. */
  protected async addPatron(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    const choice = this.patronChoices()[person.id] ?? '';
    if (choice === '') {
      this.patronErrors.update((errors) => ({ ...errors, [person.id]: 'Choose a patron.' }));
      return;
    }
    try {
      const added = await this.service.addPatron(sheet, person.id, Number(choice));
      if (this.sheet() !== sheet) return;
      this.patrons.update((list) => [...list, added]);
      this.patronChoices.update(({ [person.id]: _, ...rest }) => rest);
    } catch {
      if (this.sheet() !== sheet) return;
      this.patronErrors.update((errors) => ({
        ...errors,
        [person.id]: 'That person cannot be added as a patron.',
      }));
    }
  }

  protected setHoldDate(dates: WritableSignal<Record<number, string>>, id: number, date: string): void {
    dates.update((typed) => ({ ...typed, [id]: date }));
    this.clearRowError(id);
  }

  /** Put the person on a hold from the date typed on their row (the sheet's today when blank) to the
   *  to-date typed (no end when blank). */
  protected async hold(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    const from = (this.holdFroms()[person.id] ?? '').trim();
    const to = (this.holdTos()[person.id] ?? '').trim();
    const date = /^\d{4}-\d{2}-\d{2}$/;
    if ((from !== '' && !date.test(from)) || (to !== '' && !date.test(to))) {
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'Type a date as YYYY-MM-DD.' }));
      return;
    }
    try {
      const saved = await this.service.hold(sheet, person.id, from || null, to || null);
      if (this.sheet() !== sheet) return;
      this.put(saved);
      this.holdFroms.update(({ [person.id]: _, ...rest }) => rest);
      this.holdTos.update(({ [person.id]: _, ...rest }) => rest);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'That person could not be held.' }));
    }
  }

  /** Whether the claim counts in the live totals: OPEN, PENDING or SETTLED, but not an OPEN claim
   *  deferred on the sheet's today, nor OPEN or PENDING when it is dated within one of its person's
   *  holds, or its person's retirement date is reached on the sheet's today, and no statement binds it
   *  (a PENDING claim is bound by none while it is). */
  private countsLive(claim: Claim, person: Person): boolean {
    return this.countsAs(claim, claim.status, person);
  }

  /** Whether the claim would count in the live totals were its status `status` (see countsLive). */
  private countsAs(claim: Claim, status: ClaimStatus, person: Person): boolean {
    const as = { ...claim, status };
    if (!counts(as)) return false;
    if (!isOpen(as)) return true;
    if (deferred(claim, this.session.today())) return false;
    const bound = claim.stated && status !== 'PENDING';
    const today = this.session.today();
    const retired = person.retiredFrom !== null && today !== null && person.retiredFrom <= today;
    return bound || !(heldOn(person.holds, claim.date) || retired);
  }

  /** Whether the claim counts in a period ending on `end`: by the status it had at the end of
   *  that day (a claim added after does not count), otherwise as the live rules say while it is still
   *  open now (a claim since settled or voided is left out by no hold or deferral). */
  private countsAtEnd(claim: Claim, person: Person, end: string): boolean {
    const status = statusOn(claim, end);
    if (status === null) return false;
    if (!isOpen(claim)) return counts({ ...claim, status });
    return this.countsAs(claim, status, person);
  }

  /** Whether the person is active: they have an OPEN or PENDING claim that counts, dated within the
   *  30 days up to and including the sheet's today. */
  protected isActive(person: Person): boolean {
    const today = this.session.today();
    if (today === null) return false;
    const from = daysBefore(today, 29);
    return this.claimsOf(person.id).some(
      (c) => isOpen(c) && this.countsLive(c, person) && from <= c.date && c.date <= today,
    );
  }

  protected claimedOf(person: Person): string {
    return money(this.claimedCentsOf(person));
  }

  /** What the person's OPEN and SETTLED claims add up to (unbound OPEN ones dated within a hold left
   *  out); drafts and voided claims do not count. */
  private claimedCentsOf(person: Person): number {
    return this.claimsOf(person.id).reduce((sum, c) => sum + (this.countsLive(c, person) ? cents(c.amount) : 0), 0);
  }

  /** The grade's rate in force on the sheet's today. */
  private rateOf(grade: Grade | null): string | null {
    const today = this.session.today();
    return today === null || grade === null ? null : rateOn(this.rates(), grade, today);
  }

  /** What decides how the person's claims round. */
  private roundingOf(personId: number): Rounding {
    return { policyDate: this.policyDateInForce(), claims: this.claimsOf(personId) };
  }

  /** Download everyone, retired people included, in list order as a CSV file: name, grade in force
   *  today, retired-from date (blank when not retired) and due-total as shown. */
  protected exportCsv(): void {
    const lines = [['name', 'grade', 'retired-from', 'due-total']];
    for (const p of this.people()) {
      lines.push([p.name, this.gradeNow(p), p.retiredFrom ?? '', this.dueTotalOf(p) ?? 'n/a']);
    }
    const text = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([text], { type: 'text/csv' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = 'people.csv';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  /** The person's grade in force on the sheet's today. */
  protected gradeNow(person: Person): Grade {
    return gradeOn(person.grades, this.session.today()) ?? person.grade;
  }

  /** The sum of what is due on each of the person's OPEN and SETTLED claims, as each claim shows
   *  it (a claim with nothing due is skipped), at most the sheet's cap; n/a when no rate of their
   *  grade applies on the sheet's today and none of their claims has a figure. */
  protected dueTotalOf(person: Person): string | null {
    const due = this.dueCentsOf(person);
    return due === null ? null : money(due);
  }

  /** How far the sum of the person's figures is above the sheet's cap; 0.00 when it is not, or when
   *  their due-total is n/a. */
  protected excessOf(person: Person): string {
    const sum = this.sumCentsOf(person);
    const cap = this.cap();
    return money(sum === null || cap === null ? 0 : Math.max(0, sum - cap));
  }

  /** The person's due-total in cents, capped; null when it is n/a. */
  private dueCentsOf(person: Person): number | null {
    const sum = this.sumCentsOf(person);
    const cap = this.cap();
    return sum === null || cap === null ? sum : Math.min(sum, cap);
  }

  /** The sum of the person's figures in cents, before the cap; null when it is n/a. */
  private sumCentsOf(person: Person): number | null {
    const split = this.splitOf(person);
    return split.any || this.rateOf(this.gradeNow(person)) !== null ? split.settled + split.open : null;
  }

  /** The person's figures before the cap, in cents, split in two: what is due on their counting SETTLED
   *  claims (at their frozen figures) and on their counting OPEN and PENDING ones, as each claim shows
   *  it (a claim with nothing due is skipped); with whether any claim had a figure. */
  protected splitOf(person: Person): { settled: number; open: number; any: boolean } {
    let settled = 0;
    let open = 0;
    let any = false;
    for (const c of this.claimsOf(person.id)) {
      if (!this.countsLive(c, person)) continue;
      const due = claimShownCents(c, this.rates(), person.grades, this.floors(), this.lateness(), this.roundingOf(person.id));
      if (due === null) continue;
      if (isOpen(c)) open += due;
      else settled += due;
      any = true;
    }
    return { settled, open, any };
  }

  /** How many of the person's counting claims are overdue, and the sum of what is due on them, in
   *  cents, as each claim shows it (a claim with nothing due adds nothing). */
  protected overdueOf(person: Person): { count: number; cents: number } {
    let count = 0;
    let sum = 0;
    const today = this.session.today();
    for (const c of this.claimsOf(person.id)) {
      if (!this.countsLive(c, person) || !overdue(c, today, overdueFrom(c, person.grades, this.graces()))) continue;
      count++;
      sum += claimShownCents(c, this.rates(), person.grades, this.floors(), this.lateness(), this.roundingOf(person.id)) ?? 0;
    }
    return { count, cents: sum };
  }

  /** What is due on the person's counting OPEN and PENDING claims, in cents, as each claim shows it,
   *  split by the claim's age on the sheet's today (the days from its date): 0 to 30 days (a claim
   *  dated after today included), 31 to 60, and 61 or more. */
  protected agedOf(person: Person): [number, number, number] {
    const aged: [number, number, number] = [0, 0, 0];
    const today = this.session.today();
    if (today === null) return aged;
    for (const c of this.claimsOf(person.id)) {
      if (!isOpen(c) || !this.countsLive(c, person)) continue;
      const due = claimShownCents(c, this.rates(), person.grades, this.floors(), this.lateness(), this.roundingOf(person.id));
      if (due === null) continue;
      const age = daysBetween(c.date, today);
      aged[age <= 30 ? 0 : age <= 60 ? 1 : 2] += due;
    }
    return aged;
  }

  protected periodAdded(period: Period): void {
    this.periods.update((list) => [...list, period]);
  }

  /** Put a closed or reopened period in its place. */
  protected periodClosed(period: Period): void {
    this.periods.update((list) => list.map((p) => (p.id === period.id ? period : p)));
  }

  /** Put a set rate in place of the grade's old one for the same from-date. */
  protected rateSaved(rate: Rate): void {
    this.rates.update((list) => [
      ...list.filter((r) => r.grade !== rate.grade || r.from !== rate.from),
      rate,
    ]);
  }

  protected selectClaim(id: number, selected: boolean): void {
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (selected) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Open the selected claims together, numbered in list order; those that cannot be opened are left
   *  as they are. Shows how many were opened and refused; the old counts are gone while it runs. */
  protected async bulkOpen(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet || this.bulkOpening) return;
    this.bulkOpened.set(null);
    this.bulkOpening = true;
    try {
      const result = await this.claimsService.bulkOpen(sheet, [...this.selectedClaims()]);
      if (this.session.sheetId() !== sheet) return;
      for (const c of result.claims) this.claimSaved(c);
      this.selectedClaims.set(new Set());
      this.bulkOpened.set({ opened: result.opened, refused: result.refused });
    } catch {
      if (this.session.sheetId() === sheet) this.bulkOpened.set({ opened: 0, refused: this.selectedClaims().size });
    } finally {
      this.bulkOpening = false;
    }
  }

  /** Settle the selected claims together, in list order, each by the rules of settling one; those
   *  that cannot be settled are left as they are. Shows how many were settled and refused; the old
   *  counts are gone while it runs. */
  protected async bulkSettle(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet || this.bulkSettling) return;
    this.bulkSettled.set(null);
    this.bulkSettling = true;
    try {
      const result = await this.claimsService.bulkSettle(sheet, [...this.selectedClaims()]);
      if (this.session.sheetId() !== sheet) return;
      for (const c of result.claims) this.claimSaved(c);
      this.selectedClaims.set(new Set());
      this.bulkSettled.set({ settled: result.settled, refused: result.refused });
    } catch {
      if (this.session.sheetId() === sheet) this.bulkSettled.set({ settled: 0, refused: this.selectedClaims().size });
    } finally {
      this.bulkSettling = false;
    }
  }

  protected selectPerson(id: number, selected: boolean): void {
    this.bulkGradeError.set(null);
    this.selectedPeople.update((ids) => {
      const next = new Set(ids);
      if (selected) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Give the selected people the chosen grade from the chosen date (the sheet's today when blank),
   *  in list order, each as the person form would. Shows how many were changed; the old count is
   *  gone while it runs. */
  protected async bulkGrade(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet || this.bulkGrading) return;
    this.bulkGraded.set(null);
    this.bulkGradeError.set(null);
    this.bulkGrading = true;
    try {
      const result = await this.service.bulkGrade(sheet, [...this.selectedPeople()], this.bulkGradeChoice(),
        this.bulkGradeFrom().trim() || null);
      if (this.session.sheetId() !== sheet) return;
      for (const p of result.people) this.put(p);
      this.selectedPeople.set(new Set());
      this.bulkGraded.set(result.graded);
    } catch {
      if (this.session.sheetId() === sheet) this.bulkGradeError.set('The selected people could not be given that grade.');
    } finally {
      this.bulkGrading = false;
    }
  }

  /** Put an added claim first, or a saved one in its place. */
  /** Show the claim as saved; one new, or moved to another person, is listed first (newest). */
  protected claimSaved(claim: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id && c.personId === claim.personId)
        ? list.map((c) => (c.id === claim.id ? claim : c))
        : [claim, ...list.filter((c) => c.id !== claim.id)],
    );
  }

  /** The other people on the sheet, by name, whom the person's claims can be moved to. */
  protected othersOf(personId: number): { id: number; name: string }[] {
    return this.people()
      .filter((p) => p.id !== personId)
      .map((p) => ({ id: p.id, name: p.name }));
  }

  protected edit(person: Person): void {
    this.clearRowError(person.id);
    this.editingId.set(person.id);
    this.editingVersion = person.version;
    this.editorOpenings++;
    this.name.set(person.name);
    this.grade.set(this.gradeNow(person));
    this.gradeFrom.set('');
    this.terms.set(person.terms === null ? '' : String(person.terms));
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
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
    const sheet = this.sheet();
    if (!sheet) return;
    const fields = { name: this.name(), grade: this.grade(), terms: this.terms().trim() };
    if (!fields.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    if (!/^\d{0,4}$/.test(fields.terms)) {
      // Nothing is saved: the editor closes, so the form is ready for the next person.
      this.reset();
      this.error.set('Terms are a whole number of business days, or blank for none.');
      return;
    }
    const id = this.editingId();
    const opening = this.editorOpenings;
    this.submitting = true;
    try {
      if (id === null) {
        const added = await this.service.add(sheet, fields);
        if (this.sheet() === sheet) this.people.update((list) => [...list, added]);
      } else {
        const saved = await this.service.update(sheet, id, this.editingVersion, {
          ...fields,
          gradeFrom: this.gradeFrom() || null,
        });
        if (this.sheet() !== sheet) return;
        this.put(saved);
        if (this.editorOpenings !== opening) {
          // The editor was opened again while the save waited: keep it, from the version just saved.
          if (this.editingId() === saved.id) this.editingVersion = saved.version;
          return;
        }
      }
      if (this.sheet() === sheet) this.reset();
    } catch (e) {
      if (this.sheet() !== sheet) return;
      const current = this.staleOf(e);
      if (current !== null) {
        // Someone else saved the person since the editor loaded them: show them as now stored.
        this.put(current);
        if (this.editingId() === current.id) {
          this.editingVersion = current.version;
          this.name.set(current.name);
          this.grade.set(this.gradeNow(current));
          this.terms.set(current.terms === null ? '' : String(current.terms));
        }
        this.error.set('Someone else saved this person since you opened it; their changes are shown.');
        return;
      }
      if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('Another person on the sheet already has that name.');
        return;
      }
      this.error.set(id === null ? 'That person could not be added.' : 'That person could not be saved.');
    } finally {
      this.submitting = false;
    }
  }

  /** Add a DRAFT claim for the named person, adding them (grade A) first when the name is new. */
  protected async quickAdd(event: Event): Promise<void> {
    event.preventDefault();
    if (this.quickAdding) return;
    this.quickError.set(null);
    const sheet = this.sheet();
    if (!sheet) return;
    const name = this.quickName();
    if (!name.trim()) {
      this.quickError.set('Type a name for the claim.');
      return;
    }
    this.quickAdding = true;
    try {
      const added = await this.service.quickAdd(sheet, name, this.quickAmount());
      if (this.sheet() !== sheet) return;
      this.people.update((list) =>
        list.some((p) => p.id === added.person.id) ? list : [...list, added.person],
      );
      this.claimSaved(added.claim);
      this.quickName.set('');
      this.quickAmount.set('');
    } catch (e) {
      if (this.sheet() !== sheet) return;
      this.quickError.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim falls in a closed period.'
          : e instanceof HttpErrorResponse && e.status === 422
            ? 'That claim is dated after the person retired.'
            : 'That claim could not be added.',
      );
    } finally {
      this.quickAdding = false;
    }
  }

  /** Add a DRAFT claim per pasted line, in order; when any line is refused nothing is added. Shows
   *  how many were added; the old count is gone while it runs. */
  protected async paste(event: Event): Promise<void> {
    event.preventDefault();
    if (this.pasting) return;
    this.pasteError.set(null);
    this.pasteAdded.set(null);
    const sheet = this.sheet();
    if (!sheet) return;
    if (!this.pasteText().trim()) {
      this.pasteError.set('Paste a line of name,amount,date,note.');
      return;
    }
    this.pasting = true;
    try {
      const pasted = await this.claimsService.paste(sheet, this.pasteText());
      if (this.sheet() !== sheet) return;
      for (const c of pasted.claims) this.claimSaved(c);
      this.pasteText.set('');
      this.pasteAdded.set(pasted.added);
    } catch (e) {
      if (this.sheet() !== sheet) return;
      this.pasteError.set(
        e instanceof HttpErrorResponse && e.status === 404
          ? 'A line names someone not on the sheet.'
          : e instanceof HttpErrorResponse && e.status === 423
            ? 'A line falls in a closed period.'
            : e instanceof HttpErrorResponse && e.status === 422
              ? 'A line is dated after the person retired.'
              : e instanceof HttpErrorResponse && e.status === 409
                ? 'A line gives a reference already used on the sheet or in the paste.'
                : 'Those claims could not be added.',
      );
    } finally {
      this.pasting = false;
    }
  }

  /** Put a saved person in place of their old row. */
  private put(person: Person): void {
    this.people.update((list) => list.map((p) => (p.id === person.id ? person : p)));
  }

  /** The person as now stored, when a save was refused for being made from an older version. */
  private staleOf(e: unknown): Person | null {
    if (!(e instanceof HttpErrorResponse) || e.status !== 409) return null;
    const body = e.error as { error?: unknown; current?: Person } | null;
    return body?.error === 'stale' && body.current ? body.current : null;
  }

  private async read(sheet: string): Promise<void> {
    try {
      const [list, claims, rates, statements, periods, settings, patrons, watched] = await Promise.all([
        this.service.list(sheet),
        this.claimsService.list(sheet),
        this.ratesService.list(sheet),
        this.statementsService.list(sheet),
        this.periodsService.list(sheet),
        this.settingsService.get(sheet),
        this.service.patrons(sheet),
        this.service.watched(sheet),
      ]);
      if (this.sheet() === sheet) {
        this.floors.set(settings.floors);
        this.caps.set(settings.caps);
        this.latePcts.set(settings.latePcts);
        this.graces.set(settings.graces ?? {});
        this.policyDate.set(settings.policyDate);
        this.rates.set(rates);
        this.periods.set(periods);
        this.statements.set(statements);
        this.claims.set(claims);
        this.patrons.set(patrons);
        this.watched.set(new Set(watched));
        this.people.set(list);
      }
    } catch {
      // The sheet's people could not be read; the list stays empty.
    }
  }
}

/** A CSV field: wrapped in double quotes, with any quote inside doubled, when it holds a comma, a
 *  quote or a line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
}

/** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
function trimmedSum(figures: number[]): number {
  const sum = figures.reduce((a, b) => a + b, 0);
  return figures.length < 3 ? sum : sum - Math.max(...figures) - Math.min(...figures);
}

/** The date (YYYY-MM-DD) `days` days before `date`. */
function daysBefore(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString().slice(0, 10);
}

/** The whole days from `from` to `to` (both YYYY-MM-DD); negative when `from` is after `to`. */
function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}
