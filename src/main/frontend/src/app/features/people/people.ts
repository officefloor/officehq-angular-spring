import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Access } from '../access/access';
import { ClaimTemplates } from '../claim-templates/claim-templates';
import { Claims, PersonWithClaims as Person } from '../claims/claims';
import { Audit } from '../audit/audit';
import { Search } from '../search/search';
import { Holidays } from '../holidays/holidays';
import { Period, Periods, periodOf, personPeriodCarried, personPeriodTotals } from '../periods/periods';
import { PersonPlans, PlanList } from '../plans/plans';
import { Crew, Crews, crewDueCents } from '../crews/crews';
import { Pool, Pools } from '../pools/pools';
import { Rates } from '../rates/rates';
import { Summary, statusCounts } from '../summary/summary';
import { Tags } from '../tags/tags';
import { VoidReasons } from '../void-reasons/void-reasons';

const GRADES = ['A', 'B', 'C'];

/** The columns of the people list each user may switch off on their screen. */
const COLUMNS = [
  { key: 'aliases', label: 'Aliases' },
  { key: 'terms', label: 'Terms' },
  { key: 'contact', label: 'Contact' },
  { key: 'claimed', label: 'Claimed' },
  { key: 'due-total', label: 'Due' },
  { key: 'excess', label: 'Excess' },
  { key: 'statement-count', label: 'Statements' },
  { key: 'stated-total', label: 'Last stated total' },
  { key: 'statement-date', label: 'Last statement' },
  { key: 'retired', label: 'Retired' },
  { key: 'hold', label: 'On hold' },
  { key: 'overdue-count', label: 'Overdue' },
  { key: 'overdue-total', label: 'Overdue total' },
  { key: 'patrons', label: 'Patrons' },
  { key: 'backing', label: 'Backing' },
];
const DAYS = /^\d{1,5}$/;
const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** Cents in an amount shown with two decimals ("12.50" is 1250). */
function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** The numerator divided by the (positive) divisor, rounded half to even to a whole number. */
function halfEven(numerator: number, divisor: number): number {
  const q = Math.floor(numerator / divisor);
  const r = numerator - q * divisor;
  return 2 * r > divisor || (2 * r === divisor && q % 2 !== 0) ? q + 1 : q;
}

/** The shares, in hundredths, of that many patrons in the order they were named: 100.00 split equally, each
 *  rounded half to even to two decimals, the remainder going to the first-named. */
function patronShares(count: number): number[] {
  if (count === 0) return [];
  const each = halfEven(10000, count);
  return Array.from({ length: count }, (_, i) => (i === 0 ? 10000 - each * (count - 1) : each));
}

/** A CSV field: wrapped in double quotes, any quote inside doubled, when it holds a comma, a quote or a line break. */
/** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
function trimmedSum(figures: number[]): number {
  const sum = figures.reduce((total, f) => total + f, 0);
  return figures.length < 3 ? sum : sum - Math.max(...figures) - Math.min(...figures);
}

/** A sheet's due-total in cents as the sheet shows it: its people's, trimmed, a person showing n/a skipped. */
function dueTotalOf(people: Person[]): number {
  return trimmedSum(people.flatMap((p) => (p.dueTotal === null ? [] : [cents(p.dueTotal)])));
}

/** A sheet's overdue-total in cents as the sheet shows it: its people's, trimmed. */
function overdueTotalOf(people: Person[]): number {
  return trimmedSum(people.map((p) => cents(p.overdueTotal)));
}

/** A name as names are compared: trimmed, in lower case. */
function nameKey(name: string): string {
  return name.trim().toLowerCase();
}

const SORT_KEYS = ['added', 'name', 'grade', 'due-total'] as const;
type SortKey = (typeof SORT_KEYS)[number];

function compareKeys(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** The people in the order the key puts them (stable): by name as names are compared, by grade, or by
 *  due-total with n/a last; 'added' keeps the list order. */
function sortPeople(people: Person[], key: SortKey): Person[] {
  switch (key) {
    case 'name':
      return [...people].sort((a, b) => compareKeys(nameKey(a.name), nameKey(b.name)));
    case 'grade':
      return [...people].sort((a, b) => compareKeys(a.grade, b.grade));
    case 'due-total':
      return [...people].sort((a, b) =>
        a.dueTotal === null || b.dueTotal === null
          ? Number(a.dueTotal === null) - Number(b.dueTotal === null)
          : cents(a.dueTotal) - cents(b.dueTotal),
      );
    default:
      return people;
  }
}

/** A sheet's figures that are consolidated over it and its descendants. */
interface SheetFigures {
  people: number;
  /** In cents. */
  due: number;
  /** In cents. */
  overdue: number;
}

function figuresOf(people: Person[]): SheetFigures {
  return { people: people.length, due: dueTotalOf(people), overdue: overdueTotalOf(people) };
}

function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

// The people on the active sheet, in the order they were added, each with their claims, and the
// one form that adds a person or, once a person's editor is opened, edits them. Retired people are
// left out of the list unless "show retired" is on; their claims keep counting in the totals. A person
// can be held from a date to an optional date, as many times as needed; their OPEN claims dated within a hold,
// unless a statement binds them, count in no total. A person can name
// others on the sheet as their patrons; each patron carries an equal share of 100.00 and their backing is
// their patrons' recent settled figures, each weighted by its share. Each signed-in
// user can watch people of their own choosing.
@Component({
  selector: 'app-people',
  imports: [FigurePipe, Access, Audit, ClaimTemplates, Claims, Crews, Holidays, Periods, PersonPlans, Pools, Rates, Search, Summary, Tags, VoidReasons],
  template: `
    <header aria-label="Counters">
      <p>People <strong data-testid="header-people-count">{{ people().length }}</strong></p>
      @for (s of statusCounts(); track s.status) {
        <p>{{ s.status }} claims <strong [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</strong></p>
      }
      <p>Due total <strong data-testid="header-due-total">{{ dueTotal() | figure }}</strong></p>
      <p>Overdue total <strong data-testid="header-overdue-total">{{ overdueTotal() | figure }}</strong></p>
      <p>My due total <strong data-testid="my-due-total">{{ (me() === null ? '' : (me()!.dueTotal ?? 'n/a')) | figure }}</strong></p>
      <p>My overdue total <strong data-testid="my-overdue-total">{{ (me()?.overdueTotal ?? '') | figure }}</strong></p>
      <p>My crew due total <strong data-testid="my-crew-due-total">{{ myCrewDueTotal() | figure }}</strong></p>
    </header>
    <section aria-label="Steward dashboard">
      <h2>Steward dashboard</h2>
      <p>PENDING claims <strong data-testid="dash-pending-count">{{ dashboard()?.pending ?? '' }}</strong></p>
      <p>REVIEW claims <strong data-testid="dash-review-count">{{ dashboard()?.review ?? '' }}</strong></p>
      <p>Overdue claims <strong data-testid="dash-overdue-count">{{ dashboard()?.overdue ?? '' }}</strong></p>
    </section>
    <section aria-label="Consolidated figures">
      <h2>Consolidated over this sheet and its descendants</h2>
      <p>People <strong data-testid="consolidated-people-count">{{ consolidated().people }}</strong></p>
      <p>Due total <strong data-testid="consolidated-due-total">{{ consolidated().due | figure }}</strong></p>
      <p>Overdue total <strong data-testid="consolidated-overdue-total">{{ consolidated().overdue | figure }}</strong></p>
    </section>
    <app-rates (changed)="reloadSheet()" />
    <app-access [settingsSaved]="reloadAfterSettings" />
    <app-periods [people]="people()" [(periods)]="periods" />
    <app-holidays />
    <app-void-reasons />
    <app-claim-templates />
    <app-pools [people]="people()" [crews]="crews()" [(pools)]="pools" />
    <app-crews [people]="people()" [(crews)]="crews" />
    <app-summary [people]="people()" [periods]="periods()" />
    <app-tags [people]="people()" />
    <app-search />
    <app-audit />
    <section aria-label="People">
      <h2>People</h2>
      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() | figure }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotal() | figure }}</strong></p>
      <p>Overdue claims <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong></p>
      <p>Overdue total <strong data-testid="rollup-overdue-total">{{ overdueTotal() | figure }}</strong></p>
      <p>Settled total <strong data-testid="rollup-settled-total">{{ settledTotal() | figure }}</strong></p>
      <p>Open total <strong data-testid="rollup-open-total">{{ openTotal() | figure }}</strong></p>
      <p>Cut total <strong data-testid="rollup-cut-total">{{ cutTotal() | figure }}</strong></p>
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
        <label for="person-form-terms-from">Terms from (blank: from the beginning)</label>
        <input
          id="person-form-terms-from"
          data-testid="person-form-terms-from"
          type="date"
          [value]="termsFrom()"
          (input)="termsFrom.set(value($event)); error.set('')"
        />
        <label for="person-form-contact">Contact (blank: none)</label>
        <input
          id="person-form-contact"
          data-testid="person-form-contact"
          [value]="contact()"
          (input)="contact.set(value($event)); error.set('')"
        />
        <label for="person-form-tags">Tags (comma separated)</label>
        <input
          id="person-form-tags"
          data-testid="person-form-tags"
          [value]="tags()"
          (input)="tags.set(value($event)); error.set('')"
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
        <label for="quick-name">Name or alias</label>
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
        <label for="paste-claims">Paste claims, a line each: name or alias,amount,date,note (a note may end in " #reference")</label>
        <textarea
          id="paste-claims"
          data-testid="paste-claims"
          rows="4"
          [value]="pasteText()"
          (input)="pasteText.set(value($event)); pasteError.set('')"
        ></textarea>
        <button type="submit" data-testid="paste-submit">Add and open pasted claims</button>
        @if (pasteAdded() !== null) {
          <span>Lines through <strong data-testid="paste-added">{{ pasteAdded() }}</strong></span>
        }
        @if (pasteError()) {
          <p role="alert" data-testid="paste-error">{{ pasteError() }}</p>
        }
      </form>
      <form (submit)="importPeople($event)" aria-label="Import people">
        <label for="import-people">Import people, a line each: name,grade,terms,contact (terms and contact may be left out; a name on the sheet updates that person)</label>
        <textarea
          id="import-people"
          data-testid="import-people"
          rows="4"
          [value]="importText()"
          (input)="importText.set(value($event)); importError.set('')"
        ></textarea>
        <button type="submit" data-testid="import-submit">Import people</button>
        @if (importResult(); as imported) {
          <span>Added <strong data-testid="import-added">{{ imported.added }}</strong></span>
          <span>Updated <strong data-testid="import-updated">{{ imported.updated }}</strong></span>
        }
        @if (importError()) {
          <p role="alert" data-testid="import-error">{{ importError() }}</p>
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
        <label for="sort-by">Sort by</label>
        <select id="sort-by" data-testid="sort-by" [value]="sortKey()" (change)="sortKey.set(sortKeyOf(value($event)))">
          @for (k of sortKeys; track k) {
            <option [value]="k">{{ k }}</option>
          }
        </select>
      </p>
      <p>
        <button type="button" data-testid="export-people" (click)="exportCsv()">Export people as CSV</button>
        <button type="button" data-testid="export-claims" (click)="exportClaims()">Export claims as CSV</button>
      </p>
      <p>
        <button type="button" data-testid="plan-generate" (click)="generatePlans()">Generate plan claims</button>
        <span>Made <strong data-testid="plan-generated-count">{{ planGenerated() ?? '' }}</strong></span>
        @if (planGenerateError()) {
          <span role="alert" data-testid="plan-generate-error">{{ planGenerateError() }}</span>
        }
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
        <span>Last batch <strong data-testid="bulk-settle-batch">{{ settleBatch() ?? '' }}</strong></span>
        @if (bulkSettled(); as r) {
          <span>Settled <strong data-testid="bulk-settled">{{ r.settled }}</strong></span>
          <span>Refused <strong data-testid="bulk-settle-refused">{{ r.refused }}</strong></span>
        }
        @if (bulkSettleError()) {
          <span role="alert" data-testid="bulk-settle-error">{{ bulkSettleError() }}</span>
        }
      </p>
      <p>
        <label for="bulk-reassign-to">Move selected claims to</label>
        <select
          id="bulk-reassign-to"
          data-testid="bulk-reassign-to"
          (change)="bulkReassignTo.set(value($event)); bulkReassignError.set('')"
        >
          <option value="" [selected]="!bulkReassignTo()">Choose a person</option>
          @for (p of people(); track p.id) {
            <option [value]="p.id" [selected]="bulkReassignTo() === '' + p.id">{{ p.name }}</option>
          }
        </select>
        <button type="button" data-testid="bulk-reassign" (click)="reassignSelected()">Move selected claims</button>
        @if (bulkReassigned(); as r) {
          <span>Moved <strong data-testid="bulk-reassigned">{{ r.reassigned }}</strong></span>
          <span>Refused <strong data-testid="bulk-reassign-refused">{{ r.refused }}</strong></span>
        }
        @if (bulkReassignError()) {
          <span role="alert" data-testid="bulk-reassign-error">{{ bulkReassignError() }}</span>
        }
      </p>
      <p>
        <label for="bulk-redate-date">Re-date selected claims to</label>
        <input
          id="bulk-redate-date"
          data-testid="bulk-redate-date"
          type="date"
          [value]="bulkRedateDate()"
          (input)="bulkRedateDate.set(value($event)); bulkRedateError.set('')"
        />
        <button type="button" data-testid="bulk-redate" (click)="redateSelected()">Re-date selected claims</button>
        @if (bulkRedated(); as r) {
          <span>Re-dated <strong data-testid="bulk-redated">{{ r.redated }}</strong></span>
          <span>Refused <strong data-testid="bulk-redate-refused">{{ r.refused }}</strong></span>
        }
        @if (bulkRedateError()) {
          <span role="alert" data-testid="bulk-redate-error">{{ bulkRedateError() }}</span>
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
      <form (submit)="retireSelected($event)" aria-label="Retire selected people">
        <label for="bulk-retire-date">Retire the selected people from (blank: today)</label>
        <input
          id="bulk-retire-date"
          data-testid="bulk-retire-date"
          type="date"
          [value]="bulkRetireFrom()"
          (input)="bulkRetireFrom.set(value($event)); bulkRetireError.set('')"
        />
        <button type="submit" data-testid="bulk-retire">Retire selected people</button>
        @if (bulkRetired() !== null) {
          <span>Retired <strong data-testid="bulk-retired">{{ bulkRetired() }}</strong></span>
        }
        @if (bulkRetireError()) {
          <p role="alert" data-testid="person-error">{{ bulkRetireError() }}</p>
        }
      </form>
      <fieldset>
        <legend>Columns shown</legend>
        @for (c of columns; track c.key) {
          <label>
            <input
              type="checkbox"
              [attr.data-testid]="'column-' + c.key"
              [checked]="columnShown(c.key)"
              (change)="setShown(c.key, checked($event))"
            />
            {{ c.label }}
          </label>
        }
      </fieldset>
      <table>
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">Name</th>@if (columnShown('aliases')) {<th scope="col">Aliases</th>}<th scope="col">Flag</th><th scope="col">Clashing</th><th scope="col">Came from</th><th scope="col">Grade</th>@if (columnShown('terms')) {<th scope="col">Terms</th>}@if (columnShown('contact')) {<th scope="col">Contact</th>}<th scope="col">Tags</th>@if (columnShown('claimed')) {<th scope="col">Claimed</th>}@if (columnShown('due-total')) {<th scope="col">Due</th>}<th scope="col">Settled</th><th scope="col">Open</th>@if (columnShown('excess')) {<th scope="col">Excess</th>}
            @if (columnShown('statement-count')) {<th scope="col">Statements</th>}@if (columnShown('stated-total')) {<th scope="col">Last stated total</th>}@if (columnShown('statement-date')) {<th scope="col">Last statement</th>}
            @if (columnShown('retired')) {<th scope="col">Retired</th>}<th scope="col">Retired from</th>@if (columnShown('hold')) {<th scope="col">On hold</th>}
            @if (columnShown('overdue-count')) {<th scope="col">Overdue</th>}@if (columnShown('overdue-total')) {<th scope="col">Overdue total</th>}
            <th scope="col">Due aged 0–30</th><th scope="col">Due aged 31–60</th><th scope="col">Due aged 61+</th>
            @if (columnShown('patrons')) {<th scope="col">Patrons</th>}@if (columnShown('backing')) {<th scope="col">Backing</th>}<th scope="col">Watched</th><th scope="col">Active</th><th scope="col">Cut total</th>
            @for (pd of periods(); track pd.id) {
              <th scope="col">Due in {{ pd.name }}</th><th scope="col">Carried into {{ pd.name }}</th>
            }
            <th scope="col"><span class="visually-hidden">Actions</span></th><th scope="col">Claims</th>
          </tr>
        </thead>
        <tbody>
          @for (p of sorted(); track p.id) {
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
              @if (columnShown('aliases')) {
                <td data-testid="person-aliases">{{ (p.aliases ?? []).join(', ') }}</td>
              }
              <td data-testid="person-flag-shown">{{ p.flag ?? '' }}</td>
              <td data-testid="person-clashing">{{ p.clashing ? 'yes' : 'no' }}</td>
              <td data-testid="person-origin">{{ p.origin ?? '' }}</td>
              <td data-testid="person-grade">{{ p.grade }}</td>
              @if (columnShown('terms')) {
                <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              }
              @if (columnShown('contact')) {
                <td data-testid="person-contact">{{ p.contact ?? '' }}</td>
              }
              <td data-testid="person-tags">{{ (p.tags ?? []).join(', ') }}</td>
              @if (columnShown('claimed')) {
                <td data-testid="person-claimed">{{ p.claimed | figure }}</td>
              }
              @if (columnShown('due-total')) {
                <td data-testid="person-due-total">{{ (p.dueTotal ?? 'n/a') | figure }}</td>
              }
              <td data-testid="person-settled-total">{{ p.settledTotal | figure }}</td>
              <td data-testid="person-open-total">{{ p.openTotal | figure }}</td>
              @if (columnShown('excess')) {
                <td data-testid="person-excess">{{ p.excess | figure }}</td>
              }
              @if (columnShown('statement-count')) {
                <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              }
              @if (columnShown('stated-total')) {
                <td data-testid="person-stated-total">{{ (p.statementCount === 0 ? '' : (p.statedTotal ?? 'n/a')) | figure }}</td>
              }
              @if (columnShown('statement-date')) {
                <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              }
              @if (columnShown('retired')) {
                <td data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</td>
              }
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              @if (columnShown('hold')) {
                <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              }
              @if (columnShown('overdue-count')) {
                <td data-testid="person-overdue-count">{{ p.overdueCount }}</td>
              }
              @if (columnShown('overdue-total')) {
                <td data-testid="person-overdue-total">{{ p.overdueTotal | figure }}</td>
              }
              <td data-testid="person-aged-0-30">{{ p.aged0to30 | figure }}</td>
              <td data-testid="person-aged-31-60">{{ p.aged31to60 | figure }}</td>
              <td data-testid="person-aged-61">{{ p.aged61 | figure }}</td>
              @if (columnShown('patrons')) {
                <td data-testid="person-patrons">{{ patronage().get(p.id)?.names }}</td>
              }
              @if (columnShown('backing')) {
                <td data-testid="person-backing">{{ (patronage().get(p.id)?.backing) | figure }}</td>
              }
              <td data-testid="person-watched">{{ watched().has(p.id) ? 'yes' : 'no' }}</td>
              <td data-testid="person-active">{{ p.active ? 'yes' : 'no' }}</td>
              <td data-testid="person-cut-total">{{ (((personCuts().get(p.id) ?? 0) / 100).toFixed(2)) | figure }}</td>
              @for (pd of periods(); track pd.id) {
                <td [attr.data-testid]="'person-period-' + pd.name + '-due-total'">{{ (periodTotals().get(p.id)?.[pd.id]) | figure }}</td>
                <td [attr.data-testid]="'person-period-' + pd.name + '-carried'">{{ (periodCarried().get(p.id)?.[pd.id]) | figure }}</td>
              }
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-move-up" (click)="move(p, 'up')">
                  Up<span class="visually-hidden">: move {{ p.name }} up the list</span>
                </button>
                <button type="button" data-testid="person-move-down" (click)="move(p, 'down')">
                  Down<span class="visually-hidden">: move {{ p.name }} down the list</span>
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
                <button type="button" data-testid="person-unretire" (click)="unretire(p)">
                  Unretire<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <ul aria-label="Past retirements">
                  @for (r of p.retirements; track $index) {
                    <li data-testid="person-retirement-line">{{ r.from }} to {{ r.to }}</li>
                  }
                </ul>
                <ul aria-label="Snapshots">
                  @for (s of p.snapshots; track $index) {
                    <li data-testid="person-snapshot-line">{{ s.day }} {{ s.dueTotal }}</li>
                  }
                </ul>
                <ul aria-label="Statements">
                  @for (s of p.statementLines; track $index) {
                    <li data-testid="person-statement-line-line">{{ s.date }} {{ s.dueTotal }}</li>
                  }
                </ul>
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
                <label [for]="'person-alias-input-' + p.id">Alias for {{ p.name }}</label>
                <input
                  #aliasInput
                  type="text"
                  [id]="'person-alias-input-' + p.id"
                  data-testid="person-alias-input"
                  (input)="clearError(p)"
                />
                <button type="button" data-testid="person-alias-add" (click)="addAlias(p, aliasInput)">
                  Add alias<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                <label [for]="'person-flag-reason-' + p.id">Flag {{ p.name }} for</label>
                <input
                  #flagReason
                  type="text"
                  [id]="'person-flag-reason-' + p.id"
                  data-testid="person-flag-reason"
                  (input)="clearError(p)"
                />
                <label [for]="'person-flag-until-' + p.id">until</label>
                <input
                  #flagUntil
                  type="date"
                  [id]="'person-flag-until-' + p.id"
                  data-testid="person-flag-until"
                  (input)="clearError(p)"
                />
                <button type="button" data-testid="person-flag" (click)="flag(p, flagReason, flagUntil)">
                  Flag<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <label [for]="'person-inline-grade-' + p.id">Grade for {{ p.name }}</label>
                <select
                  #inlineGrade
                  [id]="'person-inline-grade-' + p.id"
                  data-testid="person-inline-grade"
                  (change)="clearError(p)"
                >
                  @for (g of grades; track g) {
                    <option [value]="g" [selected]="g === p.grade">{{ g }}</option>
                  }
                </select>
                <button type="button" data-testid="person-inline-grade-save" (click)="saveGrade(p, inlineGrade.value)">
                  Save grade<span class="visually-hidden"> for {{ p.name }}</span>
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
                <label [for]="'person-merge-into-' + p.id">Merge {{ p.name }} into</label>
                <select
                  #mergeSelect
                  [id]="'person-merge-into-' + p.id"
                  data-testid="person-merge-into"
                  (change)="clearError(p)"
                >
                  <option value="">Choose a person</option>
                  @for (o of people(); track o.id) {
                    <option [value]="o.id">{{ o.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-merge" (click)="merge(p, mergeSelect)">
                  Merge<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <label [for]="'person-transfer-to-' + p.id">Transfer {{ p.name }} to sheet</label>
                <input
                  #transferTo
                  type="text"
                  [id]="'person-transfer-to-' + p.id"
                  data-testid="person-transfer-to"
                  (input)="clearError(p)"
                />
                <button type="button" data-testid="person-transfer" (click)="transfer(p, transferTo)">
                  Transfer<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-transfer-undo" (click)="undoTransfer(p)">
                  Undo transfer<span class="visually-hidden"> of {{ p.name }}</span>
                </button>
                <app-person-plans [personId]="p.id" [name]="p.name" />
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
  protected readonly pools = signal<Pool[]>([]);
  /** Per person id, in cents, their cuts across every pool they are a member of; a pool showing no cut adds nothing. */
  protected readonly personCuts = computed(() => {
    const cuts = new Map<number, number>();
    for (const pool of this.pools()) {
      for (const m of pool.members) {
        if (m.personId !== null && m.cut !== null) cuts.set(m.personId, (cuts.get(m.personId) ?? 0) + cents(m.cut));
      }
    }
    return cuts;
  });
  /** Per person id, their due-total for each period on the sheet, by period id. */
  protected readonly periodTotals = computed(
    () => new Map(this.people().map((p) => [p.id, personPeriodTotals(this.periods(), p)])),
  );
  /** Per person id, their carried figure for each period on the sheet, by period id. */
  protected readonly periodCarried = computed(
    () => new Map(this.people().map((p) => [p.id, personPeriodCarried(this.periods(), p)])),
  );
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  /** The date an edit's grade applies from; blank: the sheet's today. */
  protected readonly gradeFrom = signal('');
  /** The person's own terms in days; blank: none. */
  protected readonly terms = signal('');
  /** The date the person's own terms apply from; blank: from the beginning of time. */
  protected readonly termsFrom = signal('');
  /** The person's contact; blank: none. */
  protected readonly contact = signal('');
  /** The person's tags, comma separated; blank: none. */
  protected readonly tags = signal('');
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
  /** How many lines the last paste put through; null while it runs. */
  protected readonly pasteAdded = signal<number | null>(null);
  protected readonly pasteError = signal('');
  /** A paste still waiting for its answer: another press meanwhile is ignored. */
  private pasting = false;
  protected readonly importText = signal('');
  /** How many people the last import added and updated; null while it runs. */
  protected readonly importResult = signal<{ added: number; updated: number } | null>(null);
  protected readonly importError = signal('');
  /** An import still waiting for its answer: another press meanwhile is ignored. */
  private importing = false;
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The refusal of the last patron named on a person's row, shown on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** A patron still waiting for its answer: another press meanwhile is ignored. */
  private naming = false;
  /** An alias still waiting for its answer: another press meanwhile is ignored. */
  private aliasing = false;
  /** A flag still waiting for its answer: another press meanwhile is ignored. */
  private flagging = false;
  /** A merge still waiting for its answer: another press meanwhile is ignored. */
  private merging = false;
  /** A move still waiting for its answer: another press meanwhile is ignored. */
  private moving = false;
  /** A transfer still waiting for its answer: another press meanwhile is ignored. */
  private transferring = false;
  /** A statement still waiting for its answer: another press meanwhile is ignored. */
  private stating = false;
  /** A retirement still waiting for its answer: another press meanwhile is ignored. */
  private retiring = false;
  private grading = false;
  /** A hold still waiting for its answer: another press meanwhile is ignored. */
  private holding = false;
  /** The ids of the people the signed-in user watches on the sheet. */
  protected readonly watched = signal<ReadonlySet<number>>(new Set());
  protected readonly columns = COLUMNS;
  /** The columns of the people list the signed-in user has switched off; saved per user. */
  private readonly hidden = signal<ReadonlySet<string>>(new Set());
  /** Column choices still being saved; while any is, a reload keeps the choices on screen. */
  private columnSaves = 0;
  /** A watch still waiting for its answer: another press meanwhile is ignored. */
  private watching = false;
  /** The ids of the claims selected to be opened or settled together. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** How many of the selected claims the last bulk open opened and refused; null while it runs. */
  /** How many claims the last generate of the plans made; null while it runs. */
  protected readonly planGenerated = signal<number | null>(null);
  protected readonly planGenerateError = signal('');
  /** A generate still waiting for its answer: another press meanwhile is ignored. */
  private generating = false;
  private readonly planList = inject(PlanList);
  protected readonly bulkOpened = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal('');
  /** A bulk open still waiting for its answer: another press meanwhile is ignored. */
  private bulkOpening = false;
  /** How many of the selected claims the last bulk settle settled and refused; null while it runs. */
  protected readonly bulkSettled = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal('');
  /** The number of the sheet's last settle batch; null when there has been none. */
  protected readonly settleBatch = signal<number | null>(null);
  /** A bulk settle still waiting for its answer: another press meanwhile is ignored. */
  private bulkSettling = false;
  /** The id of the person the selected claims are to be moved to; blank until one is chosen. */
  protected readonly bulkReassignTo = signal('');
  /** How many of the selected claims the last bulk move moved and refused; null while it runs. */
  protected readonly bulkReassigned = signal<{ reassigned: number; refused: number } | null>(null);
  protected readonly bulkReassignError = signal('');
  /** A bulk move still waiting for its answer: another press meanwhile is ignored. */
  private bulkReassigning = false;
  /** The date the selected claims are to be re-dated to; blank until one is typed. */
  protected readonly bulkRedateDate = signal('');
  /** How many of the selected claims the last bulk re-date re-dated and refused; null while it runs. */
  protected readonly bulkRedated = signal<{ redated: number; refused: number } | null>(null);
  protected readonly bulkRedateError = signal('');
  /** A bulk re-date still waiting for its answer: another press meanwhile is ignored. */
  private bulkRedating = false;
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
  /** The date the selected people are retired from; blank: the sheet's today. */
  protected readonly bulkRetireFrom = signal('');
  /** How many people the last bulk retire retired; null while it runs. */
  protected readonly bulkRetired = signal<number | null>(null);
  protected readonly bulkRetireError = signal('');
  /** A bulk retire still waiting for its answer: another press meanwhile is ignored. */
  private bulkRetiring = false;
  /** Whether retired people are listed; off when the screen opens, left as it is by loading a sheet. */
  protected readonly showRetired = signal(false);
  /** The people listed: everyone, or only those not retired unless "show retired" is on. */
  protected readonly shown = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  protected readonly sortKeys = SORT_KEYS;
  /** How this user's screen orders the listed people; only the screen, never the list order itself. */
  protected readonly sortKey = signal<SortKey>('added');
  /** The listed people as the screen orders them; people with equal keys keep their list order. */
  protected readonly sorted = computed(() => sortPeople(this.shown(), this.sortKey()));
  protected readonly statusCounts = computed(() => statusCounts(this.people()));
  protected readonly claimedTotal = computed(() =>
    (this.people().reduce((sum, p) => sum + cents(p.claimed), 0) / 100).toFixed(2),
  );
  /** Everyone's due-total added up, trimmed (see {@link trimmedSum}); a person showing n/a is skipped. */
  protected readonly dueTotal = computed(() => (dueTotalOf(this.people()) / 100).toFixed(2));
  /** Everyone's settled-total added up, not trimmed. */
  protected readonly settledTotal = computed(() =>
    (this.people().reduce((sum, p) => sum + cents(p.settledTotal), 0) / 100).toFixed(2),
  );
  /** Everyone's open-total added up, not trimmed. */
  protected readonly openTotal = computed(() =>
    (this.people().reduce((sum, p) => sum + cents(p.openTotal), 0) / 100).toFixed(2),
  );

  /** Everyone's cut total added up, not trimmed. */
  protected readonly cutTotal = computed(() =>
    (this.people().reduce((sum, p) => sum + (this.personCuts().get(p.id) ?? 0), 0) / 100).toFixed(2),
  );

  /** Each person's patrons, each as their name and share, sorted by name and comma separated, and their
   *  backing: each patron's recent settled figure (see {@link Person.recentSettled}) times their share
   *  divided by 100, added up and rounded half to even (see {@link patronShares}). */
  protected readonly patronage = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    return new Map(
      this.people().map((p) => {
        const patrons = p.patrons.map((id) => byId.get(id)).filter((q): q is Person => q !== undefined);
        const shares = patronShares(patrons.length);
        const named = patrons.map((q, i) => ({ name: q.name, share: shares[i] }));
        const names = named
          .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
          .map((n) => `${n.name} ${(n.share / 100).toFixed(2)}`)
          .join(', ');
        // cents times hundredths of a percent: ten-thousandths of a cent.
        const backing = patrons.reduce((sum, q, i) => sum + cents(q.recentSettled) * shares[i], 0);
        return [p.id, { names, backing: (halfEven(backing, 10000) / 100).toFixed(2) }];
      }),
    );
  });

  /** Everyone's overdue claims counted up. */
  protected readonly overdueCount = computed(() => this.people().reduce((sum, p) => sum + p.overdueCount, 0));
  /** The role the signed-in user acts in on the sheet (as of the as-of date while one is set); null until read. */
  private readonly role = signal<string | null>(null);
  /**
   * The steward's dashboard: the sheet's PENDING and REVIEW claims and its overdue claims that count, counted;
   * null (the cells empty) when the signed-in user is not a steward.
   */
  protected readonly dashboard = computed(() => {
    if (this.role() !== 'steward') return null;
    const counts = this.statusCounts();
    const count = (status: string) => counts.find((s) => s.status === status)?.count ?? 0;
    return { pending: count('PENDING'), review: count('REVIEW'), overdue: this.overdueCount() };
  });
  /** The person on the sheet with the signed-in user's name or alias, as names are compared; null when none. */
  protected readonly me = computed(() => {
    const user = this.session.user();
    if (user === null) return null;
    const key = nameKey(user);
    return this.people().find((p) => [p.name, ...(p.aliases ?? [])].some((n) => nameKey(n) === key)) ?? null;
  });
  /** The sheet's crews, as the crews panel last read them. */
  protected readonly crews = signal<Crew[]>([]);
  /**
   * The due-totals of the crews led by the person with the signed-in user's name, added up, with two decimals; ''
   * when that person leads no crew, or there is no such person.
   */
  protected readonly myCrewDueTotal = computed(() => {
    const me = this.me();
    if (me === null) return '';
    const led = this.crews().filter((c) => c.lead?.personId === me.id);
    if (led.length === 0) return '';
    const totals = crewDueCents(led, this.people());
    return ([...totals.values()].reduce((sum, t) => sum + t, 0) / 100).toFixed(2);
  });
  /** Everyone's overdue-total added up, trimmed (see {@link trimmedSum}). */
  protected readonly overdueTotal = computed(() => (overdueTotalOf(this.people()) / 100).toFixed(2));
  /**
   * The figures of each of the sheet's descendants (its children, theirs, and so on), as each shows them: as of the
   * date the sheet is shown as of, while one is set.
   */
  private readonly descendants = signal<SheetFigures[]>([]);
  /**
   * The people counted, and the due- and overdue-totals added up, over the sheet and all its descendants, as
   * they stand live; while the sheet is shown as of a date, each as it stood at the end of that day, that date
   * as its today.
   */
  protected readonly consolidated = computed(() => {
    const all = [figuresOf(this.people()), ...this.descendants()];
    return {
      people: all.reduce((sum, f) => sum + f.people, 0),
      due: (all.reduce((sum, f) => sum + f.due, 0) / 100).toFixed(2),
      overdue: (all.reduce((sum, f) => sum + f.overdue, 0) / 100).toFixed(2),
    };
  });

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.people.set([]);
      this.role.set(null);
      this.descendants.set([]);
      this.watched.set(new Set());
      this.personError.set(null);
      this.patronError.set(null);
      this.selectedClaims.set(new Set());
      this.planGenerated.set(null);
      this.planGenerateError.set('');
      this.bulkOpened.set(null);
      this.bulkOpenError.set('');
      this.bulkSettled.set(null);
      this.bulkSettleError.set('');
      this.settleBatch.set(null);
      this.bulkReassignTo.set('');
      this.bulkReassigned.set(null);
      this.bulkReassignError.set('');
      this.bulkRedateDate.set('');
      this.bulkRedated.set(null);
      this.bulkRedateError.set('');
      this.selectedPeople.set(new Set());
      this.bulkGrade.set('A');
      this.bulkGradeFrom.set('');
      this.bulkGraded.set(null);
      this.bulkGradeError.set('');
      this.bulkRetireFrom.set('');
      this.bulkRetired.set(null);
      this.bulkRetireError.set('');
      this.reset();
      this.quickName.set('');
      this.quickAmount.set('');
      this.quickError.set('');
      this.pasteText.set('');
      this.pasteAdded.set(null);
      this.pasteError.set('');
      this.importText.set('');
      this.importResult.set(null);
      this.importError.set('');
      if (sheet) void this.reload(sheet);
    });
  }

  protected sortKeyOf(key: string): SortKey {
    return SORT_KEYS.find((k) => k === key) ?? 'added';
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  /** Whether the signed-in user's screen shows the column. */
  protected columnShown(key: string): boolean {
    return !this.hidden().has(key);
  }

  /** Show or switch off a column on the signed-in user's screen, and save the choice. */
  protected setShown(key: string, shown: boolean): void {
    this.hidden.update((h) => {
      const next = new Set(h);
      if (shown) next.delete(key);
      else next.add(key);
      return next;
    });
    this.columnSaves++;
    void firstValueFrom(
      this.http.put<string[]>(`/api/me/columns/${encodeURIComponent(key)}`, { shown }, {
        headers: { 'X-User': this.session.user() ?? '' },
      }),
    )
      .catch(() => undefined)
      .finally(() => this.columnSaves--);
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
    this.bulkRetireError.set('');
    this.clearError(p);
  }

  /** Give the selected people the grade from the date (blank: the sheet's today), each as the person form would. */
  protected async gradeSelected(e: Event): Promise<void> {
    e.preventDefault();
    if (this.bulkGrading) return;
    this.bulkGraded.set(null);
    this.bulkGradeError.set('');
    this.personError.set(null);
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

  /** Retire the selected people from the date (blank: the sheet's today), each as the row's retire would. */
  protected async retireSelected(e: Event): Promise<void> {
    e.preventDefault();
    if (this.bulkRetiring) return;
    this.bulkRetired.set(null);
    this.bulkRetireError.set('');
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const personIds = [...this.selectedPeople()];
    const from = this.bulkRetireFrom().trim();
    if (personIds.length === 0) {
      this.bulkRetireError.set('Select the people to retire.');
      return;
    }
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.bulkRetireError.set('Enter a date, or leave it blank for today.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.bulkRetiring = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ retired: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/retire`,
          { personIds, from: from || null },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedPeople.set(new Set());
      await this.reload(sheet);
      if (this.session.sheet() !== sheet) return;
      this.bulkRetired.set(result.retired);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkRetireError.set('Could not retire the selected people.');
    } finally {
      this.bulkRetiring = false;
    }
  }

  /** Make the claims every plan on the sheet is due up to its today, then show them and how many were made. */
  protected async generatePlans(): Promise<void> {
    if (this.generating) return;
    this.planGenerated.set(null);
    this.planGenerateError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.generating = true;
    try {
      const made = await this.planList.generate(sheet);
      if (this.session.sheet() !== sheet) return;
      await this.reload(sheet);
      if (this.session.sheet() !== sheet) return;
      this.planGenerated.set(made);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const answer = err instanceof HttpErrorResponse ? err : null;
      this.planGenerateError.set(answer?.error?.message ?? 'Could not generate the plans\' claims.');
    } finally {
      this.generating = false;
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

  /** Move the selected claims to the chosen person together; each that cannot be moved is refused and left as it is. */
  protected async reassignSelected(): Promise<void> {
    if (this.bulkReassigning) return;
    this.bulkReassigned.set(null);
    this.bulkReassignError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const personId = Number(this.bulkReassignTo());
    if (!this.bulkReassignTo() || !Number.isInteger(personId)) {
      this.bulkReassignError.set('Choose the person to move the selected claims to.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.bulkReassigning = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ reassigned: number; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-reassign`,
          { claimIds: [...this.selectedClaims()], personId },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedClaims.set(new Set());
      await this.reload(sheet);
      if (this.session.sheet() !== sheet) return;
      this.bulkReassigned.set(result);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkReassignError.set('Could not move the selected claims.');
    } finally {
      this.bulkReassigning = false;
    }
  }

  /** Re-date the selected claims to the date typed together; each that cannot be re-dated is refused and left as it is. */
  protected async redateSelected(): Promise<void> {
    if (this.bulkRedating) return;
    this.bulkRedated.set(null);
    this.bulkRedateError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const date = this.bulkRedateDate().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.bulkRedateError.set('Enter the date to re-date the selected claims to.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.bulkRedating = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ redated: number; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-redate`,
          { claimIds: [...this.selectedClaims()], date },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedClaims.set(new Set());
      await this.reload(sheet);
      if (this.session.sheet() !== sheet) return;
      this.bulkRedated.set(result);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkRedateError.set('Could not re-date the selected claims.');
    } finally {
      this.bulkRedating = false;
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
    this.termsFrom.set('');
    this.contact.set(p.contact ?? '');
    this.tags.set(p.tags.join(', '));
    this.error.set('');
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set('');
    this.terms.set('');
    this.termsFrom.set('');
    this.contact.set('');
    this.tags.set('');
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
    const termsFrom = this.termsFrom() || null;
    const contact = this.contact().trim().toLowerCase();
    const tags = this.tags();
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
          ? this.http.post<Person>(base, { name, grade, terms, termsFrom, contact, tags }, { headers })
          : this.http.put<Person>(`${base}/${id}`, { name, grade, gradeFrom, terms, termsFrom, contact, tags, version: this.version }, { headers }),
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
        this.error.set('That name (or alias) or contact is already in use on this sheet.');
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

  /** Add (or update a matching DRAFT) and open a claim per pasted line, in order; one refused line changes nothing. */
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
   * Per pasted line, in order, update the person on the sheet it names or add one, each as the person form
   * would; one refused line imports none.
   */
  protected async importPeople(e: Event): Promise<void> {
    e.preventDefault();
    if (this.importing) return;
    this.importResult.set(null);
    this.importError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = this.importText();
    if (!text.trim()) {
      this.importError.set('Paste at least one line.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.importing = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ added: number; updated: number }>(`/api/sheets/${encodeURIComponent(sheet)}/people/import`, { text }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      await this.reload(sheet);
      if (this.session.sheet() !== sheet) return;
      this.importText.set('');
      this.importResult.set(result);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.importError.set(refusal ?? 'Could not import the people.');
    } finally {
      this.importing = false;
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

  /** Record the grade from the sheet's today, the rest of the person as they are, as their editor would. */
  protected async saveGrade(p: Person, grade: string): Promise<void> {
    if (this.grading) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    if (!GRADES.includes(grade)) {
      this.personError.set({ id: p.id, message: 'Choose a grade.' });
      return;
    }
    const body = {
      name: p.name,
      grade,
      gradeFrom: null,
      terms: p.terms === null ? '' : String(p.terms),
      contact: p.contact ?? '',
      tags: p.tags.join(', '),
      version: p.version,
    };
    const headers = { 'X-User': this.session.user() ?? '' };
    this.grading = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<Person>(`/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}`, body, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const message =
        err instanceof HttpErrorResponse && err.status === 412
          ? 'Someone else saved this person since the sheet was loaded; re-load it to see their values.'
          : 'Could not save the grade.';
      this.personError.set({ id: p.id, message });
    } finally {
      this.grading = false;
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

  /** Unretire the retired person on the sheet's today; anyone not retired is refused. */
  protected async unretire(p: Person): Promise<void> {
    if (this.retiring) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.retiring = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(`/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/unretire`, null, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({ id: p.id, message: 'Could not unretire: only a retired person can be.' });
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

  /** Give the person the typed alias; a blank one, or one already a name or alias on the sheet, is refused. */
  protected async addAlias(p: Person, input: HTMLInputElement): Promise<void> {
    if (this.aliasing) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const alias = input.value.trim();
    if (!alias) {
      this.personError.set({ id: p.id, message: 'Enter an alias.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.aliasing = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/aliases`,
          { alias },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
      input.value = '';
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({ id: p.id, message: 'Could not add the alias: it is already a name or alias on this sheet.' });
    } finally {
      this.aliasing = false;
    }
  }

  /** Flag the person with the typed reason until the date (blank: the sheet's today); only a steward may, and a blank reason is refused. */
  protected async flag(p: Person, reasonInput: HTMLInputElement, untilInput: HTMLInputElement): Promise<void> {
    if (this.flagging) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const reason = reasonInput.value.trim();
    if (!reason) {
      this.personError.set({ id: p.id, message: 'Enter a reason for the flag.' });
      return;
    }
    const until = untilInput.value || null;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.flagging = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/flag`,
          { reason, until },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.replace(saved);
      reasonInput.value = '';
      untilInput.value = '';
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({ id: p.id, message: 'Could not flag the person: only a steward can.' });
    } finally {
      this.flagging = false;
    }
  }

  /** Move the person up or down the list, swapping them with their neighbour; once done the list is shown again as the store holds it. */
  protected async move(p: Person, direction: 'up' | 'down'): Promise<void> {
    if (this.moving) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.moving = true;
    try {
      await firstValueFrom(
        this.http.post<Person>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/move`,
          { direction },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      await this.reload(sheet);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({
        id: p.id,
        message: direction === 'up' ? 'Could not move up: the top person cannot move up.' : 'Could not move down: the bottom person cannot move down.',
      });
    } finally {
      this.moving = false;
    }
  }

  /** Merge the person into the chosen one; once done the whole sheet is shown again as the store holds it. */
  protected async merge(p: Person, select: HTMLSelectElement): Promise<void> {
    if (this.merging) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const intoId = Number(select.value);
    if (!select.value || !Number.isInteger(intoId)) {
      this.personError.set({ id: p.id, message: 'Choose a person to merge into.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.merging = true;
    try {
      await firstValueFrom(
        this.http.post<Person>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/merge`,
          { intoId },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      await this.reload(sheet);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({
        id: p.id,
        message: 'Could not merge: only a steward can, into someone else, and neither may be retired.',
      });
    } finally {
      this.merging = false;
    }
  }

  /** Transfer the person to the sheet with the typed id; once done the whole sheet is shown again as the store holds it. */
  protected async transfer(p: Person, input: HTMLInputElement): Promise<void> {
    if (this.transferring) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const toSheet = input.value.trim();
    if (!toSheet) {
      this.personError.set({ id: p.id, message: 'Enter the id of the sheet to transfer to.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.transferring = true;
    try {
      await firstValueFrom(
        this.http.post<void>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/transfer`,
          { toSheet },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      await this.reload(sheet);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({
        id: p.id,
        message:
          'Could not transfer: only a steward can, not a retired person, to another sheet with no one of the same name, alias or contact.',
      });
    } finally {
      this.transferring = false;
    }
  }

  /** Undo the transfer that brought the person here today; once done the whole sheet is shown again as the store holds it. */
  protected async undoTransfer(p: Person): Promise<void> {
    if (this.transferring) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.transferring = true;
    try {
      await firstValueFrom(
        this.http.post<void>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/transfer-undo`,
          {},
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      await this.reload(sheet);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.personError.set({
        id: p.id,
        message:
          'Could not undo the transfer: only a steward can, on the day the person arrived here by transfer.',
      });
    } finally {
      this.transferring = false;
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

  /** Download everyone on the sheet, retired people included, as the store holds them now (as of the as-of date
   *  while one is set: the request names it), as a CSV file. */
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

  /** Download every claim on the sheet, retired people's included, as the store holds them now (as of the as-of
   *  date while one is set), as a CSV file:
   *  people in list order, each person's claims oldest first, the figures as the screen shows them. */
  protected async exportClaims(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const loads = this.session.loads();
    const people = await firstValueFrom(
      this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`),
    );
    if (this.session.sheet() !== sheet || this.session.loads() !== loads) return;
    this.people.set(people);
    const periods = this.periods();
    const lines = [
      'person,number,date,amount,due,status,period',
      ...people.flatMap((p) =>
        [...p.claims]
          .reverse()
          .map((c) =>
            [p.name, c.number ?? '', c.date, c.amount, c.due ?? 'n/a', c.status, periodOf(periods, c.dueBy)?.name ?? '']
              .map(csvField)
              .join(','),
          ),
      ),
    ];
    const url = URL.createObjectURL(new Blob([lines.map((l) => l + '\n').join('')], { type: 'text/csv' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `claims-${sheet}.csv`;
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
        this.contact.set(current.contact ?? '');
        this.tags.set(current.tags.join(', '));
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

  /** Show the people again as the store holds them, once a setting changed what they are due. */
  protected readonly reloadAfterSettings = async (): Promise<void> => {
    const sheet = this.session.sheet();
    if (sheet) await Promise.all([this.reload(sheet), this.reloadPeriods(sheet)]).catch(() => undefined);
  };

  /** Re-read the sheet's periods: a setting such as the fiscal start month changes how they are shown. */
  private async reloadPeriods(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const periods = await firstValueFrom(this.http.get<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.periods.set(periods);
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const [people, role, watched, batch, descendants, hidden] = await Promise.all([
      firstValueFrom(this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`)),
      firstValueFrom(
        this.http.get<{ role: string }>(`/api/sheets/${encodeURIComponent(sheet)}/role`, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      ),
      firstValueFrom(
        this.http.get<number[]>(`/api/sheets/${encodeURIComponent(sheet)}/watches`, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      ),
      firstValueFrom(
        this.http.get<{ last: number | null }>(`/api/sheets/${encodeURIComponent(sheet)}/claims/settle-batch`),
      ),
      this.descendantFigures(sheet),
      firstValueFrom(
        this.http.get<string[]>('/api/me/hidden-columns', { headers: { 'X-User': this.session.user() ?? '' } }),
      ),
    ]);
    if (this.session.sheet() === sheet && this.session.loads() === loads) {
      this.people.set(people);
      this.role.set(role.role);
      this.watched.set(new Set(watched));
      if (this.columnSaves === 0) this.hidden.set(new Set(hidden));
      this.settleBatch.set(batch.last);
      this.descendants.set(descendants);
    }
  }

  /**
   * The figures of each of the sheet's descendants, each read from its own people as they stand live, or while the
   * screen shows the sheet as of a date, as they stood at the end of that day (the request names the date).
   */
  private async descendantFigures(sheet: string): Promise<SheetFigures[]> {
    const ids = await firstValueFrom(this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/descendants`));
    return Promise.all(
      ids.map(async (id) =>
        figuresOf(await firstValueFrom(this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(id)}/people`))),
      ),
    );
  }
}
