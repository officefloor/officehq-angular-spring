import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Access } from '../access/access';
import { Claims, PersonWithClaims as Person } from '../claims/claims';
import { Period, Periods, personPeriodTotals } from '../periods/periods';
import { Rates } from '../rates/rates';
import { Summary, statusCounts } from '../summary/summary';

const GRADES = ['A', 'B', 'C'];
const DAYS = /^\d{1,5}$/;
const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** Cents in an amount shown with two decimals ("12.50" is 1250). */
function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** A CSV field: wrapped in double quotes, any quote inside doubled, when it holds a comma, a quote or a line break. */
/** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
function trimmedSum(figures: number[]): number {
  const sum = figures.reduce((total, f) => total + f, 0);
  return figures.length < 3 ? sum : sum - Math.max(...figures) - Math.min(...figures);
}

function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

// The people on the active sheet, in the order they were added, each with their claims, and the
// one form that adds a person or, once a person's editor is opened, edits them. Retired people are
// left out of the list unless "show retired" is on; their claims keep counting in the totals. A person
// can be held from a date to an optional date, as many times as needed; their OPEN claims dated within a hold,
// unless a statement binds them, count in no total. A person can name
// others on the sheet as their patrons; their backing is their patrons' recent settled figures added up. Each signed-in
// user can watch people of their own choosing.
@Component({
  selector: 'app-people',
  imports: [Access, Claims, Periods, Rates, Summary],
  template: `
    <header aria-label="Counters">
      <p>People <strong data-testid="header-people-count">{{ people().length }}</strong></p>
      @for (s of statusCounts(); track s.status) {
        <p>{{ s.status }} claims <strong [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</strong></p>
      }
      <p>Due total <strong data-testid="header-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue total <strong data-testid="header-overdue-total">{{ overdueTotal() }}</strong></p>
    </header>
    <app-rates (changed)="reloadSheet()" />
    <app-access />
    <app-periods [people]="people()" [(periods)]="periods" />
    <app-summary [people]="people()" [periods]="periods()" />
    <section aria-label="People">
      <h2>People</h2>
      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue claims <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong></p>
      <p>Overdue total <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>
      <form (submit)="save($event)" [attr.aria-label]="editing() === null ? 'Add person' : 'Edit person'">
        <label for="person-form-name">Name</label>
        <input
          id="person-form-name"
          data-testid="person-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set('')"
        />
        <label for="person-form-grade">Grade</label>
        <select
          id="person-form-grade"
          data-testid="person-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set('')"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="person-form-grade-from">Grade from</label>
        <input
          id="person-form-grade-from"
          data-testid="person-form-grade-from"
          type="date"
          [value]="gradeFrom()"
          (input)="gradeFrom.set(value($event)); error.set('')"
        />
        <label for="person-form-terms">Terms in days (blank: none)</label>
        <input
          id="person-form-terms"
          data-testid="person-form-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="person-form-submit">
          {{ editing() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error()) {
          <p role="alert" data-testid="person-form-error">{{ error() }}</p>
        }
      </form>
      <form (submit)="quickAdd($event)" aria-label="Quick add claim">
        <label for="quick-name">Name</label>
        <input
          id="quick-name"
          data-testid="quick-name"
          [value]="quickName()"
          (input)="quickName.set(value($event)); quickError.set('')"
        />
        <label for="quick-amount">Amount</label>
        <input
          id="quick-amount"
          data-testid="quick-amount"
          inputmode="decimal"
          [value]="quickAmount()"
          (input)="quickAmount.set(value($event)); quickError.set('')"
        />
        <button type="submit" data-testid="quick-submit">Quick add claim</button>
        @if (quickError()) {
          <p role="alert" data-testid="quick-error">{{ quickError() }}</p>
        }
      </form>
      <form (submit)="pasteClaims($event)" aria-label="Paste claims">
        <label for="paste-claims">Paste claims, a line each: name,amount,date,note</label>
        <textarea
          id="paste-claims"
          data-testid="paste-claims"
          rows="4"
          [value]="pasteText()"
          (input)="pasteText.set(value($event)); pasteError.set('')"
        ></textarea>
        <button type="submit" data-testid="paste-submit">Add pasted claims</button>
        @if (pasteAdded() !== null) {
          <span>Added <strong data-testid="paste-added">{{ pasteAdded() }}</strong></span>
        }
        @if (pasteError()) {
          <p role="alert" data-testid="paste-error">{{ pasteError() }}</p>
        }
      </form>
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
      <p>
        <button type="button" data-testid="bulk-open" (click)="openSelected()">Open selected claims</button>
        @if (bulkOpened(); as r) {
          <span>Opened <strong data-testid="bulk-opened">{{ r.opened }}</strong></span>
          <span>Refused <strong data-testid="bulk-refused">{{ r.refused }}</strong></span>
        }
        @if (bulkOpenError()) {
          <span role="alert" data-testid="bulk-open-error">{{ bulkOpenError() }}</span>
        }
      </p>
      <p>
        <button type="button" data-testid="bulk-settle" (click)="settleSelected()">Settle selected claims</button>
        @if (bulkSettled(); as r) {
          <span>Settled <strong data-testid="bulk-settled">{{ r.settled }}</strong></span>
          <span>Refused <strong data-testid="bulk-settle-refused">{{ r.refused }}</strong></span>
        }
        @if (bulkSettleError()) {
          <span role="alert" data-testid="bulk-settle-error">{{ bulkSettleError() }}</span>
        }
      </p>
      <form (submit)="gradeSelected($event)" aria-label="Grade selected people">
        <label for="bulk-grade">Grade for the selected people</label>
        <select
          id="bulk-grade"
          data-testid="bulk-grade"
          [value]="bulkGrade()"
          (change)="bulkGrade.set(value($event)); bulkGradeError.set('')"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === bulkGrade()">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">from (blank: today)</label>
        <input
          id="bulk-grade-from"
          data-testid="bulk-grade-from"
          type="date"
          [value]="bulkGradeFrom()"
          (input)="bulkGradeFrom.set(value($event)); bulkGradeError.set('')"
        />
        <button type="submit" data-testid="bulk-grade-apply">Grade selected people</button>
        @if (bulkGraded() !== null) {
          <span>Changed <strong data-testid="bulk-graded">{{ bulkGraded() }}</strong></span>
        }
        @if (bulkGradeError()) {
          <p role="alert" data-testid="person-error">{{ bulkGradeError() }}</p>
        }
      </form>
      <table>
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Terms</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Excess</th>
            <th scope="col">Statements</th><th scope="col">Last stated total</th><th scope="col">Last statement</th>
            <th scope="col">Retired</th><th scope="col">Retired from</th><th scope="col">On hold</th>
            <th scope="col">Overdue</th><th scope="col">Overdue total</th>
            <th scope="col">Patrons</th><th scope="col">Backing</th><th scope="col">Watched</th><th scope="col">Active</th>
            @for (pd of periods(); track pd.id) {
              <th scope="col">Due in {{ pd.name }}</th>
            }
            <th scope="col"><span class="visually-hidden">Actions</span></th><th scope="col">Claims</th>
          </tr>
        </thead>
        <tbody>
          @for (p of shown(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td>
                <input
                  type="checkbox"
                  data-testid="person-select"
                  [attr.aria-label]="'Select ' + p.name"
                  [checked]="selectedPeople().has(p.id)"
                  (change)="selectPerson(p, checked($event))"
                />
              </td>
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              <td data-testid="person-claimed">{{ p.claimed }}</td>
              <td data-testid="person-due-total">{{ p.dueTotal ?? 'n/a' }}</td>
              <td data-testid="person-excess">{{ p.excess }}</td>
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statementCount === 0 ? '' : (p.statedTotal ?? 'n/a') }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              <td data-testid="person-overdue-count">{{ p.overdueCount }}</td>
              <td data-testid="person-overdue-total">{{ p.overdueTotal }}</td>
              <td data-testid="person-patrons">{{ patronage().get(p.id)?.names }}</td>
              <td data-testid="person-backing">{{ patronage().get(p.id)?.backing }}</td>
              <td data-testid="person-watched">{{ watched().has(p.id) ? 'yes' : 'no' }}</td>
              <td data-testid="person-active">{{ p.active ? 'yes' : 'no' }}</td>
              @for (pd of periods(); track pd.id) {
                <td [attr.data-testid]="'person-period-' + pd.name + '-due-total'">{{ periodTotals().get(p.id)?.[pd.id] }}</td>
              }
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button
                  type="button"
                  data-testid="person-watch"
                  [attr.aria-pressed]="watched().has(p.id)"
                  (click)="watch(p)"
                >
                  {{ watched().has(p.id) ? 'Unwatch' : 'Watch' }}<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <label [for]="'person-statement-asof-' + p.id">Statement for {{ p.name }} as of</label>
                <input
                  #statementAsOf
                  type="date"
                  [id]="'person-statement-asof-' + p.id"
                  data-testid="person-statement-asof"
                  (input)="clearError(p)"
                />
                <button type="button" data-testid="person-statement" (click)="statement(p, statementAsOf)">
                  Statement<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-unstate" (click)="unstate(p)">
                  Cancel last statement<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                <label [for]="'person-retire-date-' + p.id">Retire {{ p.name }} from</label>
                <input
                  #retireDate
                  type="date"
                  [id]="'person-retire-date-' + p.id"
                  data-testid="person-retire-date"
                  (input)="clearError(p)"
                />
                <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)">
                  Retire<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <ul aria-label="Holds">
                  @for (h of p.holds; track $index) {
                    <li data-testid="person-hold-line">{{ h.from }} to {{ h.to ?? 'open' }}</li>
                  }
                </ul>
                <label [for]="'person-hold-from-' + p.id">Hold {{ p.name }} from</label>
                <input
                  #holdFrom
                  type="date"
                  [id]="'person-hold-from-' + p.id"
                  data-testid="person-hold-from"
                  (input)="clearError(p)"
                />
                <label [for]="'person-hold-to-' + p.id">to</label>
                <input
                  #holdTo
                  type="date"
                  [id]="'person-hold-to-' + p.id"
                  data-testid="person-hold-to"
                  (input)="clearError(p)"
                />
                <button type="button" data-testid="person-hold-toggle" (click)="hold(p, holdFrom.value, holdTo.value)">
                  Hold<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                @if (personError()?.id === p.id) {
                  <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
                }
                <label [for]="'person-patron-select-' + p.id">Patron for {{ p.name }}</label>
                <select
                  #patronSelect
                  [id]="'person-patron-select-' + p.id"
                  data-testid="person-patron-select"
                  (change)="clearPatronError(p)"
                >
                  <option value="">Choose a person</option>
                  @for (o of patronChoices(p); track o.id) {
                    <option [value]="o.id">{{ o.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-patron-add" (click)="addPatron(p, patronSelect)">
                  Add patron<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                @if (patronError()?.id === p.id) {
                  <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                }
              </td>
              <td><app-claims
                  [person]="p"
                  [periods]="periods()"
                  [people]="people()"
                  [selected]="selectedClaims()"
                  (selectToggled)="selectClaim($event)"
                  (saved)="replace($event)"
                /></td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class People {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  protected readonly periods = signal<Period[]>([]);
  /** Per person id, their due-total for each period on the sheet, by period id. */
  protected readonly periodTotals = computed(
    () => new Map(this.people().map((p) => [p.id, personPeriodTotals(this.periods(), p)])),
  );
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  /** The date an edit's grade applies from; blank: the sheet's today. */
  protected readonly gradeFrom = signal('');
  /** The person's own terms in days; blank: none. */
  protected readonly terms = signal('');
  protected readonly editing = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private version = 0;
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;
  protected readonly error = signal('');
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal('');
  /** A quick-add still waiting for its answer: another press meanwhile is ignored. */
  private quickBusy = false;
  protected readonly pasteText = signal('');
  /** How many claims the last paste added; null while it runs. */
  protected readonly pasteAdded = signal<number | null>(null);
  protected readonly pasteError = signal('');
  /** A paste still waiting for its answer: another press meanwhile is ignored. */
  private pasting = false;
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The refusal of the last patron named on a person's row, shown on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** A patron still waiting for its answer: another press meanwhile is ignored. */
  private naming = false;
  /** A statement still waiting for its answer: another press meanwhile is ignored. */
  private stating = false;
  /** A retirement still waiting for its answer: another press meanwhile is ignored. */
  private retiring = false;
  /** A hold still waiting for its answer: another press meanwhile is ignored. */
  private holding = false;
  /** The ids of the people the signed-in user watches on the sheet. */
  protected readonly watched = signal<ReadonlySet<number>>(new Set());
  /** A watch still waiting for its answer: another press meanwhile is ignored. */
  private watching = false;
  /** The ids of the claims selected to be opened or settled together. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** How many of the selected claims the last bulk open opened and refused; null while it runs. */
  protected readonly bulkOpened = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal('');
  /** A bulk open still waiting for its answer: another press meanwhile is ignored. */
  private bulkOpening = false;
  /** How many of the selected claims the last bulk settle settled and refused; null while it runs. */
  protected readonly bulkSettled = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal('');
  /** A bulk settle still waiting for its answer: another press meanwhile is ignored. */
  private bulkSettling = false;
  /** The ids of the people selected to be given a grade together. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  protected readonly bulkGrade = signal('A');
  /** The date the selected people's grade applies from; blank: the sheet's today. */
  protected readonly bulkGradeFrom = signal('');
  /** How many people the last bulk grade changed; null while it runs. */
  protected readonly bulkGraded = signal<number | null>(null);
  protected readonly bulkGradeError = signal('');
  /** A bulk grade still waiting for its answer: another press meanwhile is ignored. */
  private bulkGrading = false;
  /** Whether retired people are listed; off when the screen opens, left as it is by loading a sheet. */
  protected readonly showRetired = signal(false);
  /** The people listed: everyone, or only those not retired unless "show retired" is on. */
  protected readonly shown = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  protected readonly statusCounts = computed(() => statusCounts(this.people()));
  protected readonly claimedTotal = computed(() =>
    (this.people().reduce((sum, p) => sum + cents(p.claimed), 0) / 100).toFixed(2),
  );
  /** Everyone's due-total added up, trimmed (see {@link trimmedSum}); a person showing n/a is skipped. */
  protected readonly dueTotal = computed(() =>
    (trimmedSum(this.people().flatMap((p) => (p.dueTotal === null ? [] : [cents(p.dueTotal)]))) / 100).toFixed(2),
  );

  /** Each person's patrons' names, sorted and comma separated, and their backing: the patrons'
   *  recent settled figures (see {@link Person.recentSettled}) added up. */
  protected readonly patronage = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    return new Map(
      this.people().map((p) => {
        const patrons = p.patrons.map((id) => byId.get(id)).filter((q): q is Person => q !== undefined);
        const names = patrons.map((q) => q.name).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).join(', ');
        const backing = patrons.reduce((sum, q) => sum + cents(q.recentSettled), 0);
        return [p.id, { names, backing: (backing / 100).toFixed(2) }];
      }),
    );
  });

  /** Everyone's overdue claims counted up. */
  protected readonly overdueCount = computed(() => this.people().reduce((sum, p) => sum + p.overdueCount, 0));
  /** Everyone's overdue-total added up, trimmed (see {@link trimmedSum}). */
  protected readonly overdueTotal = computed(() =>
    (trimmedSum(this.people().map((p) => cents(p.overdueTotal))) / 100).toFixed(2),
  );

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.people.set([]);
      this.watched.set(new Set());
      this.personError.set(null);
      this.patronError.set(null);
      this.selectedClaims.set(new Set());
      this.bulkOpened.set(null);
      this.bulkOpenError.set('');
      this.bulkSettled.set(null);
      this.bulkSettleError.set('');
      this.selectedPeople.set(new Set());
      this.bulkGrade.set('A');
      this.bulkGradeFrom.set('');
      this.bulkGraded.set(null);
      this.bulkGradeError.set('');
      this.reset();
      this.quickName.set('');
      this.quickAmount.set('');
      this.quickError.set('');
      this.pasteText.set('');
      this.pasteAdded.set(null);
      this.pasteError.set('');
      if (sheet) void this.reload(sheet);
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  protected clearError(p: Person): void {
    if (this.personError()?.id === p.id) this.personError.set(null);
  }

  protected clearPatronError(p: Person): void {
    if (this.patronError()?.id === p.id) this.patronError.set(null);
  }

  protected selectClaim(e: { id: number; checked: boolean }): void {
    const next = new Set(this.selectedClaims());
    if (e.checked) next.add(e.id);
    else next.delete(e.id);
    this.selectedClaims.set(next);
  }

  protected selectPerson(p: Person, on: boolean): void {
    const next = new Set(this.selectedPeople());
    if (on) next.add(p.id);
    else next.delete(p.id);
    this.selectedPeople.set(next);
    this.bulkGradeError.set('');
  }

  /** Give the selected people the grade from the date (blank: the sheet's today), each as the person form would. */
  protected async gradeSelected(e: Event): Promise<void> {
    e.preventDefault();
    if (this.bulkGrading) return;
    this.bulkGraded.set(null);
    this.bulkGradeError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const personIds = [...this.selectedPeople()];
    const grade = this.bulkGrade();
    const from = this.bulkGradeFrom().trim();
    if (personIds.length === 0) {
      this.bulkGradeError.set('Select the people to give the grade.');
      return;
    }
    if (!GRADES.includes(grade)) {
      this.bulkGradeError.set('Choose a grade.');
      return;
    }
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.bulkGradeError.set('Enter a date, or leave it blank for today.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.bulkGrading = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ graded: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/grade`,
          { personIds, grade, from: from || null },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedPeople.set(new Set());
      await this.reload(sheet);
      if (this.session.sheet() !== sheet) return;
      this.bulkGraded.set(result.graded);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkGradeError.set('Could not give the selected people the grade.');
    } finally {
      this.bulkGrading = false;
    }
  }

  /** Open the selected claims together; each that cannot be opened is refused and left as it is. */
  protected async openSelected(): Promise<void> {
    if (this.bulkOpening) return;
    this.bulkOpened.set(null);
    this.bulkOpenError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.bulkOpening = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ opened: number; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-open`,
          { claimIds: [...this.selectedClaims()] },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedClaims.set(new Set());
      await this.reload(sheet);
      if (this.session.sheet() !== sheet) return;
      this.bulkOpened.set(result);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkOpenError.set('Could not open the selected claims.');
    } finally {
      this.bulkOpening = false;
    }
  }

  /** Settle the selected claims together; each that cannot be settled is refused and left as it is. */
  protected async settleSelected(): Promise<void> {
    if (this.bulkSettling) return;
    this.bulkSettled.set(null);
    this.bulkSettleError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.bulkSettling = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ settled: number; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-settle`,
          { claimIds: [...this.selectedClaims()] },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedClaims.set(new Set());
      await this.reload(sheet);
      if (this.session.sheet() !== sheet) return;
      this.bulkSettled.set(result);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkSettleError.set('Could not settle the selected claims.');
    } finally {
      this.bulkSettling = false;
    }
  }

  /** The people the person may still name as a patron: everyone else on the sheet not named yet. */
  protected patronChoices(p: Person): Person[] {
    return this.people().filter((q) => q.id !== p.id && !p.patrons.includes(q.id));
  }

  /** Name the chosen person as the person's patron. */
  protected async addPatron(p: Person, select: HTMLSelectElement): Promise<void> {
    if (this.naming) return;
    this.patronError.set(null);
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const patronId = Number(select.value);
    if (!select.value || !Number.isInteger(patronId)) {
      this.patronError.set({ id: p.id, message: 'Choose a person to name as patron.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.naming = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/patrons`,
          { patronId },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
      select.value = '';
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.patronError.set({ id: p.id, message: 'Could not name that person as patron.' });
    } finally {
      this.naming = false;
    }
  }

  protected edit(p: Person): void {
    this.personError.set(null);
    this.patronError.set(null);
    this.editing.set(p.id);
    this.version = p.version;
    this.name.set(p.name);
    this.grade.set(p.grade);
    this.gradeFrom.set('');
    this.terms.set(p.terms === null ? '' : String(p.terms));
    this.error.set('');
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set('');
    this.terms.set('');
    this.error.set('');
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.name().trim();
    const grade = this.grade();
    const gradeFrom = this.gradeFrom() || null;
    const terms = this.terms().trim();
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!GRADES.includes(grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    if (terms && !DAYS.test(terms)) {
      // the edit is refused and its editor closed, so the form is back to adding a person
      this.reset();
      this.error.set('Enter the terms as a whole number of days, or leave them blank.');
      return;
    }
    const id = this.editing();
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        id === null
          ? this.http.post<Person>(base, { name, grade, terms }, { headers })
          : this.http.put<Person>(`${base}/${id}`, { name, grade, gradeFrom, terms, version: this.version }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
      this.reset();
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      if (id !== null && err instanceof HttpErrorResponse && err.status === 412) {
        await this.showCurrent(sheet, id);
        this.error.set('Someone else saved this person since you opened it; the form now shows their values.');
        return;
      }
      if (err instanceof HttpErrorResponse && err.status === 409) {
        this.error.set('That name is already in use on this sheet.');
        return;
      }
      this.error.set(id === null ? 'Could not add the person.' : 'Could not save the person.');
    } finally {
      this.busy = false;
    }
  }

  /** Quick-add a DRAFT claim for the named person, who is added with grade A when not on the sheet. */
  protected async quickAdd(e: Event): Promise<void> {
    e.preventDefault();
    if (this.quickBusy) return;
    this.quickError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.quickName().trim();
    const amount = this.quickAmount().trim();
    if (!name) {
      this.quickError.set('Enter a name.');
      return;
    }
    if (!AMOUNT.test(amount)) {
      this.quickError.set('Enter an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.quickBusy = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(`/api/sheets/${encodeURIComponent(sheet)}/quick-add`, { name, amount }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
      this.quickName.set('');
      this.quickAmount.set('');
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.quickError.set(refusal ?? 'Could not add the claim.');
    } finally {
      this.quickBusy = false;
    }
  }

  /** Add a DRAFT claim per pasted line, in order; one refused line adds none. */
  protected async pasteClaims(e: Event): Promise<void> {
    e.preventDefault();
    if (this.pasting) return;
    this.pasteAdded.set(null);
    this.pasteError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = this.pasteText();
    if (!text.trim()) {
      this.pasteError.set('Paste at least one line.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.pasting = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ added: number }>(`/api/sheets/${encodeURIComponent(sheet)}/claims/paste`, { text }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      await this.reload(sheet);
      if (this.session.sheet() !== sheet) return;
      this.pasteText.set('');
      this.pasteAdded.set(result.added);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.pasteError.set(refusal ?? 'Could not add the pasted claims.');
    } finally {
      this.pasting = false;
    }
  }

  /**
   * Take a statement for the person as of a date on or before the sheet's today (blank: now): their claims
   * that counted then are bound to the figures they had then. The date entry is cleared once it is taken or
   * refused, so a refused date never carries into the next statement.
   */
  protected async statement(p: Person, asOf: HTMLInputElement): Promise<void> {
    if (this.stating) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const date = asOf.value.trim();
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.personError.set({ id: p.id, message: 'Enter a date, or leave it blank for now.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.stating = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/statement`,
          { asOf: date || null },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      asOf.value = '';
      this.replace(saved);
    } catch {
      if (this.session.sheet() !== sheet) return;
      asOf.value = '';
      this.personError.set({
        id: p.id,
        message: date
          ? 'Could not take the statement as of that date: it must be on or before today, with the person on the sheet by then.'
          : 'Could not take the statement.',
      });
    } finally {
      this.stating = false;
    }
  }

  /** Cancel the person's last statement (a steward only): the claims it bound are live again. */
  protected async unstate(p: Person): Promise<void> {
    if (this.stating) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.stating = true;
    try {
      const saved = await firstValueFrom(
        this.http.delete<Person>(`/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/statement`, {
          headers,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({
        id: p.id,
        message: 'Could not cancel the statement: only a steward can, and only a statement that was taken.',
      });
    } finally {
      this.stating = false;
    }
  }

  /** Retire the person from a date; a date left blank takes the sheet's today. */
  protected async retire(p: Person, from: string): Promise<void> {
    if (this.retiring) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const date = from.trim();
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.personError.set({ id: p.id, message: 'Enter a date, or leave it blank for today.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.retiring = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/retire`,
          { from: date || null },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({ id: p.id, message: 'Could not retire the person.' });
    } finally {
      this.retiring = false;
    }
  }

  /** Start watching the person as the signed-in user, or stop when watching them already. */
  protected async watch(p: Person): Promise<void> {
    if (this.watching) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const loads = this.session.loads();
    const on = !this.watched().has(p.id);
    const headers = { 'X-User': this.session.user() ?? '' };
    this.watching = true;
    try {
      const ids = await firstValueFrom(
        this.http.put<number[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/watch`,
          { on },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet || this.session.loads() !== loads) return;
      this.watched.set(new Set(ids));
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({ id: p.id, message: on ? 'Could not watch the person.' : 'Could not stop watching the person.' });
    } finally {
      this.watching = false;
    }
  }

  /** Hold the person from a date (blank: the sheet's today) to an optional date. */
  protected async hold(p: Person, from: string, to: string): Promise<void> {
    if (this.holding) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const start = from.trim();
    const end = to.trim();
    if ((start && !/^\d{4}-\d{2}-\d{2}$/.test(start)) || (end && !/^\d{4}-\d{2}-\d{2}$/.test(end))) {
      this.personError.set({ id: p.id, message: 'Enter the dates, or leave the from-date blank for today.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.holding = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/hold`,
          { from: start || null, to: end || null },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({ id: p.id, message: 'Could not hold the person: a hold cannot end before it starts.' });
    } finally {
      this.holding = false;
    }
  }

  /** Download everyone on the sheet, retired people included, as the store holds them now, as a CSV file. */
  protected async exportCsv(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const loads = this.session.loads();
    const people = await firstValueFrom(
      this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`),
    );
    if (this.session.sheet() !== sheet || this.session.loads() !== loads) return;
    this.people.set(people);
    const lines = [
      'name,grade,retired-from,due-total',
      ...people.map((p) => [p.name, p.grade, p.retiredFrom ?? '', p.dueTotal ?? 'n/a'].map(csvField).join(',')),
    ];
    const url = URL.createObjectURL(new Blob([lines.map((l) => l + '\n').join('')], { type: 'text/csv' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `people-${sheet}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  /** After a refused stale edit: show the person as the store holds them now, in the row and the form. */
  private async showCurrent(sheet: string, id: number): Promise<void> {
    const loads = this.session.loads();
    try {
      const people = await firstValueFrom(
        this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`),
      );
      if (this.session.sheet() !== sheet || this.session.loads() !== loads) return;
      this.people.set(people);
      const current = people.find((p) => p.id === id);
      if (current && this.editing() === id) {
        this.name.set(current.name);
        this.grade.set(current.grade);
        this.terms.set(current.terms === null ? '' : String(current.terms));
      }
    } catch {
      // the error still shows; re-loading the sheet shows the current values
    }
  }

  /** Show a person as saved: in their place, or added at the end when new. */
  protected replace(saved: Person): void {
    this.people.update((ps) =>
      ps.some((p) => p.id === saved.id) ? ps.map((p) => (p.id === saved.id ? saved : p)) : [...ps, saved],
    );
  }

  /** Show the people again as the store holds them, once a rate changed what they are due. */
  protected reloadSheet(): void {
    const sheet = this.session.sheet();
    if (sheet) void this.reload(sheet);
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const [people, watched] = await Promise.all([
      firstValueFrom(this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`)),
      firstValueFrom(
        this.http.get<number[]>(`/api/sheets/${encodeURIComponent(sheet)}/watches`, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      ),
    ]);
    if (this.session.sheet() === sheet && this.session.loads() === loads) {
      this.people.set(people);
      this.watched.set(new Set(watched));
    }
  }
}
