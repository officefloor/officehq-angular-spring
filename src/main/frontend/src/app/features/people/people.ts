import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { PEOPLE_COLUMNS, PeopleColumn, Session } from '../../core/session';
import { downloadCsv } from '../../core/csv';
import { Claim, Claims, DUPLICATE_REF, GraceOn, PctOn, RETIRED, cents, claimDueCents, countsAt, countsFor, isBound, isCorrection, isDuplicateRef, isOpen, isOverdue, money, roundsOn, statusAt } from '../claims/claims';
import { Period, Periods, periodOn } from '../periods/periods';
import { Plan, Plans } from '../plans/plans';
import { GRADES, NA, Rate, Rates, dueCents, rateInLevels } from '../rates/rates';

const NO_CLAIMS: Claim[] = [];
const NO_RATES: Rate[][] = [];
const NO_PCT: PctOn = () => null;
const NO_GRACE: GraceOn = () => 0;
/** The statuses the summary counts claims by. */
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'REVIEW'];

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

/** A past retirement, from its date to the day they were unretired. */
interface Retirement {
  from: string;
  to: string;
}

/** A SNAPSHOT line: the day being left and their due-total on it (null: n/a). */
interface Snapshot {
  day: string;
  dueTotal: string | null;
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
  /** Their past retirements, earliest first; none of their claims may be dated after one's from and before its to. */
  retirements: Retirement[];
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
  /** Their contact, without surrounding spaces and lower-cased; null: none. */
  contact: string | null;
  /** Their aliases, sorted as names are compared. */
  aliases: string[];
  /** Whether they have an open claim (OPEN or PENDING) that counts, dated within the 30 days up to and including the sheet's today. */
  active: boolean;
  /** Whether their name or an alias is, as names are compared, a name or alias on another sheet of the sheet's tree. */
  clashing: boolean;
  /** The sheet they were transferred from; null when never transferred. */
  origin: string | null;
  /** Their tags, trimmed, lower-cased, each once and sorted; empty when they have none. */
  tags: string[];
  /** The SNAPSHOT lines written for them, oldest first. */
  snapshots: Snapshot[];
}

/** The person's grade in force on the date: the one with the latest from-date on or before it. */
function gradeOn(p: Person, date: string): string {
  let on = p.grade;
  for (const g of p.grades) if (g.from === null || g.from <= date) on = g.grade;
  return on;
}

/** A person's statements: how many, and the last one's due-total (null when n/a) and date. */
/** The figures of the sheet's descendants added up, each sheet's due-total and overdue-total as it shows them. */
interface Descendants {
  sheets: number;
  people: number;
  dueTotal: string;
  overdueTotal: string;
  /** The sheet's own figures as the store holds them now. */
  ownPeople: number;
  ownDueTotal: string;
  ownOverdueTotal: string;
}

interface StatementSummary {
  personId: number;
  count: number;
  lastDueTotal: string | null;
  lastDate: string;
  /** Each statement standing, oldest first by date. */
  lines: StatementLine[];
}

/** A statement as listed: its date and due-total (null when n/a). */
interface StatementLine {
  date: string;
  dueTotal: string | null;
}

/** Overdue claims that count: how many, and their dues in cents. */
interface Overdue {
  count: number;
  cents: number;
}

/** The dues in cents of open claims that count, by the claim's age on the sheet's today. */
interface Aged {
  upTo30: number;
  upTo60: number;
  over60: number;
}

/** The dues in cents of claims that count, split into SETTLED ones and open (OPEN or PENDING) ones. */
interface Split {
  settled: number;
  open: number;
}

/** The days from one date to a later one; negative when it is earlier. */
function daysFrom(from: string, to: string): number {
  return Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000);
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

// The people on the active sheet, in their list positions (moved up or down a place at a time), with one form to add or edit,
// each with their claims, what they have claimed (corrections left out) and their due-total (OPEN, PENDING and SETTLED claims only); the sheet's claimed-total and
// due-total under the table. Above it, the sheet's rate for each grade. Each person can be given a
// statement, which binds their counting claims' dues; the row shows how many statements they have
// had and the last one's due-total and date. Each person can be unretired, their retirement kept on the row
// as from to the day unretired. Each person can be retired from a date (blank: the sheet's
// today); retired people are left out of the list unless show retired is on, but their claims
// keep counting in every total. Each person can be put on hold from a from-date (blank: the sheet's
// today) to an optional to-date, any number of times; their open (OPEN or PENDING) claims dated within
// a hold count in no live total (theirs, the sheet's or a period's), unless bound by a statement.
// Each person, and the sheet, shows how many of the claims that count are overdue and their dues' total.
// Each person shows the dues of their open (OPEN or PENDING) claims that count by the claim's age on the
// sheet's today (days from its date): 0 to 30 (a claim dated after today too), 31 to 60, and 61 or more.
// A person's grade is dated: the first one given is in force from the start; each save records the
// form's grade from its from-date (blank: the sheet's today), unless it leaves the grade as the row
// showed it with the from-date blank. A claim takes the rate of the grade in force on its date; the
// row shows the grade in force on the sheet's today. A person may have their own terms in days (blank:
// none), and a contact (blank: none), kept trimmed and lower-cased and refused when another person on
// the sheet has it, and tags (comma separated; trimmed, lower-cased, repeats dropped, shown sorted);
// a claim counts in a tag's total when it or its person carries the tag, once; a claim's due-by is fixed when it is added from them, else their grade's, else the sheet's.
// A person may have aliases, added one at a time and shown sorted as names are compared; an alias may not
// be any name or alias on the sheet, and the quick-add bar and the paste box find a person by one too.
// No name or alias may be used that another sheet of the sheet's tree (the sheets reached through
// parents and children) already uses as a name or alias; a person who already clashes so shows
// clashing yes, and each edit of them is refused until it renames them to a name that does not clash.
// Each person can name other people on the sheet as their patrons, one at a time (not themselves, nor
// the same person twice). The patrons carry shares: 100.00 split equally in the order named, each rounded
// half to even to two decimals, the remainder going to the first named. The row shows each patron as
// <name> <share>, sorted by name, and their backing: over the patrons, the patron's figure (their frozen
// figures of their SETTLED claims dated within the 90 days up to and including the sheet's today) times
// their share divided by 100, rounded half to even, added up (0.00 with none).
// A person's due-total is at most the sheet's cap (blank: none); the row shows their excess, how far the
// sum of their figures is above it (0.00 when not, or n/a). Every total of due-totals adds the capped ones.
// Each row shows the person's cut total: their cuts as the pools show them, across every pool they are a
// member of, added up (a pool that shows no cuts adds nothing); the sheet's cut total adds up everyone's.
// Under it, a summary: per grade, the people on it today (retired people excluded) and their
// due-totals added up; per period, its due-total; per status, how many claims have it.
// Beside it, a classic summary: per period, its due-total counting each claim by its status now (a closed
// period shows the figure fixed when it closed), and the sheet's due-total with no trimming.
// Above everything, a header of counters repeating those figures: the people on the sheet (retired
// people included), the claims per status, and the sheet's due-total and overdue-total.
// The sheet's due-total and overdue-total leave out the single highest and the single lowest person's
// figure when three or more people have one (an n/a due-total is no figure).
// People can be selected and given a grade together from a from-date (blank: the sheet's today), each in
// list order as the person form would; the count changed is shown. Selected people can be retired
// together from a date (blank: the sheet's today), each in list order as the row's retire would; the
// count retired is shown.
/** Figures in cents added up, leaving out the single highest and the single lowest when there are three or more. */
function trimmedSum(figures: number[]): number {
  const sum = figures.reduce((total, c) => total + c, 0);
  return figures.length < 3 ? sum : sum - Math.max(...figures) - Math.min(...figures);
}

@Component({
  selector: 'app-people',
  imports: [FigurePipe, ReactiveFormsModule, Claims, Periods, Plans, Rates],
  template: `
    <header aria-label="Sheet counters">
      <p>People: <strong data-testid="header-people-count">{{ people().length }}</strong></p>
      @for (s of statusSummary(); track s.status) {
        <p>{{ s.status }}: <strong [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</strong></p>
      }
      <p>Due total: <strong data-testid="header-due-total">{{ dueTotal() | figure }}</strong></p>
      <p>Overdue total: <strong data-testid="header-overdue-total">{{ money(overdueTotal().cents) | figure }}</strong></p>
      <p>My due total: <strong data-testid="my-due-total">{{ (me() ? dueTotalOf(me()!) : '') | figure }}</strong></p>
      <p>My overdue total: <strong data-testid="my-overdue-total">{{ (me() ? money(overdueOf(me()!).cents) : '') | figure }}</strong></p>
      <p>My crew total: <strong data-testid="my-crew-due-total">{{ myCrewDueTotal() | figure }}</strong></p>
    </header>
    <section aria-labelledby="consolidated-heading">
      <h2 id="consolidated-heading">Consolidated (this sheet and its descendants)</h2>
      <p>People: <strong data-testid="consolidated-people-count">{{ consolidated().people }}</strong></p>
      <p>Due total: <strong data-testid="consolidated-due-total">{{ money(consolidated().dueCents) | figure }}</strong></p>
      <p>Overdue total: <strong data-testid="consolidated-overdue-total">{{ money(consolidated().overdueCents) | figure }}</strong></p>
    </section>
    <app-rates [rates]="rates()" [inherited]="inheritedRates()" [today]="session.today()" (saved)="rateSaved($event)" (imported)="ratesImported($event)" />
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
        <label for="person-form-contact">Contact (blank: none)</label>
        <input id="person-form-contact" data-testid="person-form-contact" formControlName="contact" />
        <label for="person-form-tags">Tags (comma separated)</label>
        <input id="person-form-tags" data-testid="person-form-tags" formControlName="tags" />
        <button type="submit" data-testid="person-form-submit">{{ editingId() === null ? 'Add' : 'Save' }}</button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
        }
        @if (error()) {
          <p role="alert" class="error" data-testid="person-form-error">{{ error() }}</p>
        }
      </form>
      <form [formGroup]="importForm" (ngSubmit)="importPeople()" aria-label="Import people">
        <label for="import-people">Import people (name,grade,terms,contact per line; terms and contact may be left out)</label>
        <textarea id="import-people" data-testid="import-people" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="import-submit">Import</button>
        @if (importAdded() !== null) {
          <p role="status">
            Added <span data-testid="import-added">{{ importAdded() }}</span>,
            updated <span data-testid="import-updated">{{ importUpdated() }}</span>
          </p>
        }
        @if (importError()) {
          <p role="alert" class="error" data-testid="import-error">{{ importError() }}</p>
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
      <form [formGroup]="pasteForm" (ngSubmit)="paste()" aria-label="Paste claims">
        <label for="paste-claims">Paste claims (name,amount,date,note per line; end the note with " #reference" to give one)</label>
        <textarea id="paste-claims" data-testid="paste-claims" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="paste-submit">Paste</button>
        @if (pasteAdded() !== null) {
          <p role="status">Lines through <span data-testid="paste-added">{{ pasteAdded() }}</span></p>
        }
        @if (pasteError()) {
          <p role="alert" class="error" data-testid="paste-error">{{ pasteError() }}</p>
        }
      </form>
      <button type="button" data-testid="plan-generate" (click)="generatePlans()">Generate planned claims</button>
      @if (plansGenerated() !== null) {
        <p role="status">Made <span data-testid="plan-generated-count">{{ plansGenerated() }}</span></p>
      }
      @if (generateError()) {
        <p role="alert" class="error" data-testid="plan-generate-error">{{ generateError() }}</p>
      }
      <label>
        <input type="checkbox" data-testid="show-retired" [checked]="session.showRetired()"
               (change)="session.showRetired.set($any($event.target).checked)" />
        Show retired
      </label>
      <fieldset>
        <legend>Columns shown</legend>
        @for (c of columns; track c) {
          <label>
            <input type="checkbox" [attr.data-testid]="'column-' + c" [checked]="shows(c)"
                   (change)="session.showColumn(c, $any($event.target).checked)" />
            {{ c }}
          </label>
        }
      </fieldset>
      <label for="sort-by">Sort by</label>
      <select id="sort-by" data-testid="sort-by" [value]="session.sortBy()"
              (change)="session.sortBy.set($any($event.target).value)">
        <option value="added">Added</option>
        <option value="name">Name</option>
        <option value="grade">Grade</option>
        <option value="due-total">Due total</option>
      </select>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export CSV</button>
      <button type="button" data-testid="export-claims" (click)="exportClaims()">Export claims CSV</button>
      <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
      @if (bulkOpened(); as result) {
        <p role="status">Opened <span data-testid="bulk-opened">{{ result.opened }}</span>,
          refused <span data-testid="bulk-refused">{{ result.refused }}</span></p>
      }
      @if (bulkError()) {
        <p role="alert" class="error" data-testid="bulk-open-error">{{ bulkError() }}</p>
      }
      <button type="button" data-testid="bulk-settle" (click)="bulkSettle()">Settle selected claims</button>
      <span>Last batch <span data-testid="bulk-settle-batch">{{ session.lastBatch() ?? '' }}</span></span>
      @if (bulkSettled(); as result) {
        <p role="status">Settled <span data-testid="bulk-settled">{{ result.settled }}</span>,
          refused <span data-testid="bulk-settle-refused">{{ result.refused }}</span></p>
      }
      @if (bulkSettleError()) {
        <p role="alert" class="error" data-testid="bulk-settle-error">{{ bulkSettleError() }}</p>
      }
      <label for="bulk-reassign-to">Move selected claims to</label>
      <select id="bulk-reassign-to" data-testid="bulk-reassign-to" #bulkReassignTo
              (change)="bulkReassignError.set('')">
        @for (p of people(); track p.id) {
          <option [value]="p.id">{{ p.name }}</option>
        }
      </select>
      <button type="button" data-testid="bulk-reassign" (click)="bulkReassign(bulkReassignTo.value)">Move selected claims</button>
      @if (bulkReassigned(); as result) {
        <p role="status">Moved <span data-testid="bulk-reassigned">{{ result.reassigned }}</span>,
          refused <span data-testid="bulk-reassign-refused">{{ result.refused }}</span></p>
      }
      @if (bulkReassignError()) {
        <p role="alert" class="error" data-testid="bulk-reassign-error">{{ bulkReassignError() }}</p>
      }
      <label for="bulk-redate-date">Re-date selected claims to</label>
      <input type="date" id="bulk-redate-date" data-testid="bulk-redate-date" #bulkRedateDate
             (input)="bulkRedateError.set('')" />
      <button type="button" data-testid="bulk-redate" (click)="bulkRedate(bulkRedateDate.value)">Re-date selected claims</button>
      @if (bulkRedated(); as result) {
        <p role="status">Re-dated <span data-testid="bulk-redated">{{ result.redated }}</span>,
          refused <span data-testid="bulk-redate-refused">{{ result.refused }}</span></p>
      }
      @if (bulkRedateError()) {
        <p role="alert" class="error" data-testid="bulk-redate-error">{{ bulkRedateError() }}</p>
      }
      <form [formGroup]="bulkGradeForm" (ngSubmit)="bulkGrade()" aria-label="Grade selected people">
        <label for="bulk-grade">Grade selected people</label>
        <select id="bulk-grade" data-testid="bulk-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">From (blank: today)</label>
        <input type="date" id="bulk-grade-from" data-testid="bulk-grade-from" formControlName="from" />
        <button type="submit" data-testid="bulk-grade-apply">Apply grade</button>
        @if (bulkGraded() !== null) {
          <p role="status">Changed <span data-testid="bulk-graded">{{ bulkGraded() }}</span></p>
        }
      </form>
      <label for="bulk-retire-date">Retire selected people from (blank: today)</label>
      <input type="date" id="bulk-retire-date" data-testid="bulk-retire-date" #bulkRetireDate
             (input)="personError.set(null)" />
      <button type="button" data-testid="bulk-retire" (click)="bulkRetire(bulkRetireDate.value)">Retire selected people</button>
      @if (bulkRetired() !== null) {
        <p role="status">Retired <span data-testid="bulk-retired">{{ bulkRetired() }}</span></p>
      }
      <table>
        <thead>
          <tr><th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">Name</th><th scope="col">Grade</th>@if (shows('terms')) {<th scope="col">Terms</th>}@if (shows('contact')) {<th scope="col">Contact</th>}<th scope="col">Tags</th><th scope="col">Aliases</th><th scope="col">Came from</th>@if (shows('claimed')) {<th scope="col">Claimed</th>}@if (shows('due-total')) {<th scope="col">Due total</th>}<th scope="col">Snapshots</th>@if (shows('excess')) {<th scope="col">Excess</th>}<th scope="col">Settled total</th><th scope="col">Open total</th><th scope="col">Cut total</th><th scope="col">Periods</th><th scope="col">Statements</th><th scope="col">Retired</th><th scope="col">Hold</th><th scope="col">Watched</th><th scope="col">Active</th><th scope="col">Clashing</th><th scope="col">Overdue</th><th scope="col">Aged</th><th scope="col">Patrons</th><th scope="col">Plans</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of sorted(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td>
                <input type="checkbox" data-testid="person-select" [checked]="selectedPeople().has(p.id)"
                       (change)="personToggled(p.id, $any($event.target).checked)"
                       [attr.aria-label]="'Select ' + p.name" />
              </td>
              <td>
                <span data-testid="person-name">{{ p.name }}</span>
                <button type="button" data-testid="person-move-up" (click)="move(p, 'up')"
                        [attr.aria-label]="'Move ' + p.name + ' up'">Up</button>
                <button type="button" data-testid="person-move-down" (click)="move(p, 'down')"
                        [attr.aria-label]="'Move ' + p.name + ' down'">Down</button>
              </td>
              <td>
                <span data-testid="person-grade">{{ gradeOf(p) }}</span>
                <label [for]="'person-inline-grade-' + p.id" class="visually-hidden">Grade for {{ p.name }}</label>
                <select [id]="'person-inline-grade-' + p.id" data-testid="person-inline-grade" #inlineGrade
                        (change)="clearPersonError(p.id)">
                  @for (g of grades; track g) {
                    <option [value]="g" [selected]="g === gradeOf(p)">{{ g }}</option>
                  }
                </select>
                <button type="button" data-testid="person-inline-grade-save" (click)="saveGrade(p, inlineGrade.value)"
                        [attr.aria-label]="'Save grade for ' + p.name">Save grade</button>
              </td>
              @if (shows('terms')) {
                <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              }
              @if (shows('contact')) {
                <td data-testid="person-contact">{{ p.contact ?? '' }}</td>
              }
              <td data-testid="person-tags">{{ p.tags.join(', ') }}</td>
              <td>
                @if (shows('aliases')) {<span data-testid="person-aliases">{{ p.aliases.join(', ') }}</span>}
                <label [for]="'person-alias-input-' + p.id" class="visually-hidden">Alias for {{ p.name }}</label>
                <input [id]="'person-alias-input-' + p.id" data-testid="person-alias-input"
                       #aliasInput (input)="clearPersonError(p.id)" />
                <button type="button" data-testid="person-alias-add" (click)="addAlias(p, aliasInput)"
                        [attr.aria-label]="'Add alias for ' + p.name">Add alias</button>
              </td>
              <td data-testid="person-origin">{{ p.origin ?? '' }}</td>
              @if (shows('claimed')) {
                <td data-testid="person-claimed">{{ claimedOf(p.id) | figure }}</td>
              }
              @if (shows('due-total')) {
                <td data-testid="person-due-total">{{ dueTotalOf(p) | figure }}</td>
              }
              <td>
                <ul>
                  @for (s of p.snapshots; track $index) {
                    <li data-testid="person-snapshot-line">{{ s.day }} {{ s.dueTotal ?? 'n/a' }}</li>
                  }
                </ul>
              </td>
              @if (shows('excess')) {
                <td data-testid="person-excess">{{ excessOf(p) | figure }}</td>
              }
              <td data-testid="person-settled-total">{{ money(splitOf(p).settled) | figure }}</td>
              <td data-testid="person-open-total">{{ money(splitOf(p).open) | figure }}</td>
              <td data-testid="person-cut-total">{{ money(cutTotalOf(p.id)) | figure }}</td>
              <td>
                @for (period of periods(); track period.id) {
                  <span>{{ period.name }} <span [attr.data-testid]="'person-period-' + period.name + '-due-total'">{{ personPeriodDueOf(p, period) | figure }}</span>
                    carried <span [attr.data-testid]="'person-period-' + period.name + '-carried'">{{ personPeriodCarriedOf(p, period) | figure }}</span></span>
                }
              </td>
              <td>
                @if (shows('statement-count')) {<span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>}
                @if (shows('stated-total')) {<span>Last <span data-testid="person-stated-total">{{ statedTotalOf(p.id) | figure }}</span></span>}
                @if (shows('statement-date')) {<span>on <span data-testid="person-statement-date">{{ statementOf(p.id)?.lastDate ?? '' }}</span></span>}
                <ul>
                  @for (l of statementOf(p.id)?.lines ?? []; track $index) {
                    <li data-testid="person-statement-line-line">{{ l.date }} {{ l.dueTotal ?? 'n/a' }}</li>
                  }
                </ul>
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
                @if (shows('retired')) {<span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>}
                <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
                <label [for]="'person-retire-date-' + p.id" class="visually-hidden">Retire {{ p.name }} from</label>
                <input type="date" [id]="'person-retire-date-' + p.id" data-testid="person-retire-date"
                       #retireDate (input)="clearPersonError(p.id)" />
                <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)"
                        [attr.aria-label]="'Retire ' + p.name">Retire</button>
                <button type="button" data-testid="person-unretire" (click)="unretire(p)"
                        [attr.aria-label]="'Unretire ' + p.name">Unretire</button>
                <ul>
                  @for (r of p.retirements; track $index) {
                    <li data-testid="person-retirement-line">{{ r.from }} to {{ r.to }}</li>
                  }
                </ul>
              </td>
              <td>
                @if (shows('hold')) {<span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>}
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
              <td data-testid="person-clashing">{{ p.clashing ? 'yes' : 'no' }}</td>
              <td>
                @if (shows('overdue-count')) {<span data-testid="person-overdue-count">{{ overdueOf(p).count }}</span>}
                @if (shows('overdue-total')) {<span>Total <span data-testid="person-overdue-total">{{ money(overdueOf(p).cents) | figure }}</span></span>}
              </td>
              <td>
                <span>0-30 <span data-testid="person-aged-0-30">{{ money(agedOf(p).upTo30) | figure }}</span></span>
                <span>31-60 <span data-testid="person-aged-31-60">{{ money(agedOf(p).upTo60) | figure }}</span></span>
                <span>61+ <span data-testid="person-aged-61">{{ money(agedOf(p).over60) | figure }}</span></span>
              </td>
              <td>
                @if (shows('patrons')) {<span data-testid="person-patrons">{{ patronsOf(p) }}</span>}
                @if (shows('backing')) {<span>Backing <span data-testid="person-backing">{{ backingOf(p) | figure }}</span></span>}
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
                <app-plans [personId]="p.id" [personName]="p.name" [plans]="plansOf(p.id)" (added)="planAdded($event)" />
              </td>
              <td>
                <app-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [pctOn]="pctOnOf(p.id)" [graceOn]="graceOnOf(p.id)"
                            [periods]="periods()" [selected]="selectedIds()" [others]="patronChoicesFor(p)"
                            (saved)="claimSaved($event)" (moved)="claimMoved($event)" (toggled)="claimToggled($event)" />
              </td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
                        [attr.aria-label]="'Edit ' + p.name">Edit</button>
                <label [for]="'person-merge-into-' + p.id" class="visually-hidden">Merge {{ p.name }} into</label>
                <select [id]="'person-merge-into-' + p.id" data-testid="person-merge-into" #mergeInto
                        (change)="clearPersonError(p.id)">
                  <option value="">Merge into</option>
                  @for (o of people(); track o.id) {
                    <option [value]="o.id">{{ o.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-merge" (click)="merge(p, mergeInto)"
                        [attr.aria-label]="'Merge ' + p.name">Merge</button>
                <label [for]="'person-transfer-to-' + p.id" class="visually-hidden">Transfer {{ p.name }} to sheet</label>
                <input [id]="'person-transfer-to-' + p.id" data-testid="person-transfer-to" #transferTo
                       placeholder="Sheet id" (input)="clearPersonError(p.id)" />
                <button type="button" data-testid="person-transfer" (click)="transfer(p, transferTo)"
                        [attr.aria-label]="'Transfer ' + p.name">Transfer</button>
              </td>
            </tr>
          }
        </tbody>
      </table>
      <p>Claimed total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() | figure }}</strong></p>
      <p>Due total: <strong data-testid="rollup-due-total">{{ dueTotal() | figure }}</strong></p>
      <p>Overdue: <strong data-testid="rollup-overdue-count">{{ overdueTotal().count }}</strong>
        totalling <strong data-testid="rollup-overdue-total">{{ money(overdueTotal().cents) | figure }}</strong></p>
      <p>Settled total: <strong data-testid="rollup-settled-total">{{ money(splitTotal().settled) | figure }}</strong></p>
      <p>Open total: <strong data-testid="rollup-open-total">{{ money(splitTotal().open) | figure }}</strong></p>
      <p>Cut total: <strong data-testid="rollup-cut-total">{{ money(cutTotal()) | figure }}</strong></p>
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
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal | figure }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-active'">{{ g.active }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due total</th><th scope="col">Change</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ periodDueTotalOf(p) | figure }}</td>
              <td [attr.data-testid]="'summary-period-' + p.name + '-change'">{{ periodChangeOf(p) | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By tag, every tag used on the sheet</caption>
        <thead>
          <tr><th scope="col">Tag</th><th scope="col">Due total</th></tr>
        </thead>
        <tbody>
          @for (t of tagSummary(); track t.tag) {
            <tr>
              <th scope="row">{{ t.tag }}</th>
              <td [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ t.dueTotal | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By month, every month a claim on the sheet is dated in</caption>
        <thead>
          <tr><th scope="col">Month</th><th scope="col">Due total</th></tr>
        </thead>
        <tbody>
          @for (m of monthSummary(); track m.month) {
            <tr>
              <th scope="row">{{ m.month }}</th>
              <td [attr.data-testid]="'month-' + m.month + '-due-total'">{{ m.dueTotal | figure }}</td>
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
      <table>
        <caption>By void reason, every reason on the sheet's list</caption>
        <thead>
          <tr><th scope="col">Void reason</th><th scope="col">VOID claims</th></tr>
        </thead>
        <tbody>
          @for (v of voidReasonSummary(); track v.reason) {
            <tr>
              <th scope="row">{{ v.reason }}</th>
              <td [attr.data-testid]="'summary-void-' + v.reason + '-count'">{{ v.count }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
    <section aria-labelledby="classic-heading">
      <h2 id="classic-heading">Classic summary</h2>
      <table>
        <caption>By period, each claim counted by its status now</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due total</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'classic-period-' + p.name + '-due-total'">{{ classicPeriodDueTotalOf(p) | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      <p>Due total (not trimmed): <strong data-testid="classic-due-total">{{ classicDueTotal() | figure }}</strong></p>
    </section>
  `,
})
export class People {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);
  /** The columns of the people list this screen can switch off. */
  protected readonly columns = PEOPLE_COLUMNS;

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** The sheet's descendants' figures, as the store held them when the sheet was loaded (as of the user's date, while one is set). */
  private readonly descendants = signal<Descendants | null>(null);
  /**
   * The sheet's own figures, as shown, and its descendants' added up. Read as of a date, every sheet of the
   * tree is as it stood at the end of that date.
   */
  protected readonly consolidated = computed(() => {
    const d = this.descendants();
    return {
      people: this.people().length + (d?.people ?? 0),
      dueCents: this.dueTotalAllCents() + (d ? cents(d.dueTotal) : 0),
      overdueCents: this.overdueTotal().cents + (d ? cents(d.overdueTotal) : 0),
    };
  });
  /** The people on screen: everyone, less the retired unless show retired is on. */
  protected readonly listed = computed(() =>
    this.session.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /**
   * The people on screen as this screen sorts them: by name as names are compared, by the grade in
   * force today, or by due-total with n/a last; equal keys keep list order. Only the screen is sorted.
   */
  protected readonly sorted = computed(() => {
    const listed = this.listed();
    const by = this.session.sortBy();
    if (by === 'added') return listed;
    const key = (p: Person): string | number | null =>
      by === 'name' ? p.name.trim().toLowerCase() : by === 'grade' ? this.gradeOf(p) : this.dueTotalCents(p);
    const keyed = listed.map((p) => ({ p, k: key(p) }));
    return keyed
      .sort((a, b) => (a.k === b.k ? 0 : a.k === null ? 1 : b.k === null ? -1 : a.k < b.k ? -1 : 1))
      .map((e) => e.p);
  });
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  /** Every plan on the sheet, in the order they were added. */
  protected readonly plans = signal<Plan[]>([]);
  private readonly plansByPerson = computed(() => {
    const by = new Map<number, Plan[]>();
    for (const pl of this.plans()) by.set(pl.personId, [...(by.get(pl.personId) ?? []), pl]);
    return by;
  });
  /** How many claims the last generate made; null before one, and while one runs. */
  protected readonly plansGenerated = signal<number | null>(null);
  protected readonly generateError = signal('');
  /** A generate is under way; another press waits for its answer. */
  private generating = false;
  /** The ids of the claims selected for a bulk change. */
  protected readonly selectedIds = signal<ReadonlySet<number>>(new Set());
  /** How many the last bulk open opened and refused; cleared while the next one runs. */
  protected readonly bulkOpened = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkError = signal('');
  /** How many the last bulk settle settled (or sent to PENDING) and refused; cleared while the next one runs. */
  protected readonly bulkSettled = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal('');
  /** How many the last bulk move moved and refused; cleared while the next one runs. */
  protected readonly bulkReassigned = signal<{ reassigned: number; refused: number } | null>(null);
  protected readonly bulkReassignError = signal('');
  /** How many the last bulk re-date re-dated and refused; cleared while the next one runs. */
  protected readonly bulkRedated = signal<{ redated: number; refused: number } | null>(null);
  protected readonly bulkRedateError = signal('');
  /** The ids of the people selected for a bulk grade. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  /** How many people the last bulk grade changed; null before one, and while one runs. */
  protected readonly bulkGraded = signal<number | null>(null);
  /** How many people the last bulk retire retired; null before one, and while one runs. */
  protected readonly bulkRetired = signal<number | null>(null);
  protected readonly bulkGradeForm = new FormGroup({
    grade: new FormControl('A', { nonNullable: true }),
    /** The date the grade is in force from; blank: the sheet's today. */
    from: new FormControl('', { nonNullable: true }),
  });
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
  /** Whether the claim counts toward what is claimed: one that counts and does not correct another. */
  private readonly countsClaimed = (c: Claim): boolean => this.counts(c) && !isCorrection(c);
  protected readonly claimedTotal = computed(() => money(this.claims().filter(this.countsClaimed).reduce((sum, c) => sum + cents(c.amount), 0)));
  /** The sheet's rates, each for a grade from its from-date. */
  protected readonly rates = signal<Rate[]>([]);
  /** The rates of the sheet's ancestors, nearest first: a grade with none of its own in force on a date takes theirs. */
  protected readonly inheritedRates = signal<Rate[][]>([]);
  /** The sheet's periods, earliest first. */
  protected readonly periods = signal<Period[]>([]);
  /**
   * Each person's live due-total per period in cents, by person id then period id: the dues as shown now
   * of their claims whose due-by falls in it that counted by the status they had at the end of its last day.
   */
  private readonly personPeriodLiveCents = computed(() => {
    const by = new Map<number, Map<number, number>>();
    const today = this.session.today();
    for (const c of this.claims()) {
      const period = periodOn(this.periods(), c.dueBy);
      const pctOn = this.pctOnByPerson().get(c.personId);
      if (period === null || pctOn === undefined) continue;
      if (!countsAt(c, period.to, this.keptOut(c), today)) continue;
      const theirs = by.get(c.personId) ?? new Map<number, number>();
      theirs.set(period.id, (theirs.get(period.id) ?? 0) + (claimDueCents(c, pctOn, this.session.floorCentsOn, this.session.late(), roundsOn(c, this.claimsOf(c.personId)), this.graceOnOf(c.personId)) ?? 0));
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
  /**
   * Each person's carried figure per period in cents, by person id then period id: the dues as shown now of
   * their claims that count whose due-by falls in a period ending before it starts and that were still open
   * (OPEN or PENDING) at the end of its last day, by the status log.
   */
  private readonly personPeriodCarriedCents = computed(() => {
    const by = new Map<number, Map<number, number>>();
    const today = this.session.today();
    const periods = this.periods();
    for (const c of this.claims()) {
      const own = periodOn(periods, c.dueBy);
      const pctOn = this.pctOnByPerson().get(c.personId);
      if (own === null || pctOn === undefined) continue;
      let due: number | null = null;
      for (const period of periods) {
        if (own.to >= period.from) continue;
        const status = statusAt(c, period.to);
        if ((status !== 'OPEN' && status !== 'PENDING') || !countsAt(c, period.to, this.keptOut(c), today)) continue;
        due ??= claimDueCents(c, pctOn, this.session.floorCentsOn, this.session.late(), roundsOn(c, this.claimsOf(c.personId)), this.graceOnOf(c.personId)) ?? 0;
        const theirs = by.get(c.personId) ?? new Map<number, number>();
        theirs.set(period.id, (theirs.get(period.id) ?? 0) + due);
        by.set(c.personId, theirs);
      }
    }
    return by;
  });
  /** A person's carried figure for a period: fixed when it was closed or soft-closed (0 for someone added later), else live. */
  protected personPeriodCarriedOf(p: Person, period: Period): string {
    if (period.closed || period.softClosed) return money(cents(period.closedPersonCarried?.[p.id] ?? '0.00'));
    return money(this.personPeriodCarriedCents().get(p.id)?.get(period.id) ?? 0);
  }
  /** A period's due-total: the people's figures for it added up. */
  protected readonly periodDueTotalOf = (period: Period): string =>
    money(this.periodDueCents(period));
  private periodDueCents(period: Period): number {
    return this.people().reduce((sum, p) => sum + this.personPeriodCents(p.id, period), 0);
  }
  /**
   * Each period's classic live due-total in cents, by period id: the dues as shown now of the claims whose
   * due-by falls in it that count by their status now.
   */
  private readonly classicPeriodLiveCents = computed(() => {
    const by = new Map<number, number>();
    for (const c of this.claims()) {
      const period = periodOn(this.periods(), c.dueBy);
      const pctOn = this.pctOnByPerson().get(c.personId);
      if (period === null || pctOn === undefined || !this.counts(c)) continue;
      by.set(period.id, (by.get(period.id) ?? 0) + (claimDueCents(c, pctOn, this.session.floorCentsOn, this.session.late(), roundsOn(c, this.claimsOf(c.personId)), this.graceOnOf(c.personId)) ?? 0));
    }
    return by;
  });
  /** A period's classic due-total: the figure fixed when it was closed or soft-closed, else each claim counted by its status now. */
  protected classicPeriodDueTotalOf(period: Period): string {
    if (period.closed || period.softClosed) return money(this.periodDueCents(period));
    return money(this.classicPeriodLiveCents().get(period.id) ?? 0);
  }
  /** The due-totals of the crews the signed-in user's person leads, added up; blank when they lead none. */
  protected readonly myCrewDueTotal = computed(() => {
    const me = this.me();
    const led = me ? this.session.crewTotals().filter((c) => c.leadId === me.id) : [];
    return led.length ? money(led.reduce((sum, c) => sum + c.cents, 0)) : '';
  });
  /** Everyone's due-total added up, skipping those whose due-total is n/a, not trimmed. */
  protected readonly classicDueTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + (this.dueTotalCents(p) ?? 0), 0)),
  );
  /** A period's due-total less that of the period before it by from-date; nothing for the earliest. */
  protected periodChangeOf(period: Period): string {
    const list = [...this.periods()].sort((a, b) => (a.from < b.from ? -1 : 1));
    const at = list.findIndex((p) => p.id === period.id);
    if (at <= 0) return '';
    return money(this.periodDueCents(period) - this.periodDueCents(list[at - 1]));
  }
  /** Each person's statements, for those who have had any. */
  protected readonly statements = signal<StatementSummary[]>([]);
  private readonly statementByPerson = computed(() => new Map(this.statements().map((s) => [s.personId, s])));
  /** The error from the last action on a person's row, if it was refused. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The error from the last patron added on a person's row, if it was refused. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  private readonly peopleById = computed(() => new Map(this.people().map((p) => [p.id, p])));
  /** Each grade's rates in levels: the sheet's own, then each ancestor's, nearest first. */
  private readonly ratesByGrade = computed(() => {
    const by = new Map<string, Rate[][]>();
    const levels = [this.rates(), ...this.inheritedRates()];
    for (const g of GRADES) by.set(g, levels.map((rates) => rates.filter((r) => r.grade === g)));
    return by;
  });
  /** Each person's rate on a date, by id: the rate then of the grade in force for them then. */
  private readonly pctOnByPerson = computed(() => {
    const by = new Map<number, PctOn>();
    for (const p of this.people()) by.set(p.id, (date, added) => rateInLevels(this.ratesOf(gradeOn(p, date)), date, added)?.pct ?? null);
    return by;
  });
  /** Each person's grace days for a claim dated on a date, by id: those of the grade in force for them then, in force then. */
  private readonly graceOnByPerson = computed(() => {
    const by = new Map<number, GraceOn>();
    for (const p of this.people()) by.set(p.id, (date) => this.session.graceOn(gradeOn(p, date), date));
    return by;
  });
  /** Each grade's rate in force on the sheet's today, for the grades that have one. */
  private readonly pctByGrade = computed(() => {
    const today = this.session.today();
    const by = new Map<string, string>();
    if (today === null) return by;
    for (const [grade, levels] of this.ratesByGrade()) {
      const pct = rateInLevels(levels, today)?.pct ?? null;
      if (pct !== null) by.set(grade, pct);
    }
    return by;
  });
  /** The person with the signed-in user's name, by name or alias as names are compared; null when none. */
  protected readonly me = computed(() => {
    const user = this.session.user();
    if (user === null) return null;
    const key = user.trim().toLowerCase();
    return this.people().find((p) => p.name.trim().toLowerCase() === key
      || p.aliases.some((a) => a.trim().toLowerCase() === key)) ?? null;
  });
  /** Everyone's due-total added up, skipping those whose due-total is n/a, trimmed. */
  private readonly dueTotalAllCents = computed(() =>
    trimmedSum(this.people().map((p) => this.dueTotalCents(p)).filter((c): c is number => c !== null)),
  );
  protected readonly dueTotal = computed(() =>
    money(this.dueTotalAllCents()),
  );
  /** Each person's overdue claims that count, by id: how many and their dues in cents. */
  private readonly overdueByPerson = computed(() => {
    const today = this.session.today();
    const floor = this.session.floorCentsOn;
    const late = this.session.late();
    const by = new Map<number, Overdue>();
    for (const p of this.people()) {
      const pctOn = this.pctOnOf(p.id);
      const graceOn = this.graceOnOf(p.id);
      const theirs = this.claimsOf(p.id);
      const overdue = theirs.filter((c) => this.counts(c) && isOverdue(c, today, graceOn));
      by.set(p.id, { count: overdue.length, cents: overdue.reduce((sum, c) => sum + (claimDueCents(c, pctOn, floor, late, roundsOn(c, theirs), graceOn) ?? 0), 0) });
    }
    return by;
  });
  /** Each person's open claims that count, by id: their dues in cents by the claim's age on the sheet's today. */
  private readonly agedByPerson = computed(() => {
    const today = this.session.today();
    const floor = this.session.floorCentsOn;
    const late = this.session.late();
    const by = new Map<number, Aged>();
    for (const p of this.people()) {
      const pctOn = this.pctOnOf(p.id);
      const graceOn = this.graceOnOf(p.id);
      const theirs = this.claimsOf(p.id);
      const aged: Aged = { upTo30: 0, upTo60: 0, over60: 0 };
      for (const c of theirs) {
        if (!isOpen(c) || !this.counts(c)) continue;
        const due = claimDueCents(c, pctOn, floor, late, roundsOn(c, theirs), graceOn) ?? 0;
        const age = today === null ? 0 : daysFrom(c.date, today);
        if (age <= 30) aged.upTo30 += due;
        else if (age <= 60) aged.upTo60 += due;
        else aged.over60 += due;
      }
      by.set(p.id, aged);
    }
    return by;
  });
  /**
   * Each person's dues of the claims that count, by id, split in cents: SETTLED ones (at their frozen
   * figures, corrections included) and open ones (OPEN or PENDING). The two add up to the uncapped due-total.
   */
  private readonly splitByPerson = computed(() => {
    const floor = this.session.floorCentsOn;
    const late = this.session.late();
    const by = new Map<number, Split>();
    for (const p of this.people()) {
      const pctOn = this.pctOnOf(p.id);
      const graceOn = this.graceOnOf(p.id);
      const theirs = this.claimsOf(p.id);
      const split: Split = { settled: 0, open: 0 };
      for (const c of theirs) {
        if (!this.counts(c)) continue;
        const due = claimDueCents(c, pctOn, floor, late, roundsOn(c, theirs), graceOn) ?? 0;
        if (c.status === 'SETTLED') split.settled += due;
        else split.open += due;
      }
      by.set(p.id, split);
    }
    return by;
  });
  /** The sheet's settled-total and open-total: everyone's added up, not trimmed. */
  protected readonly splitTotal = computed(() => {
    const total: Split = { settled: 0, open: 0 };
    for (const s of this.splitByPerson().values()) {
      total.settled += s.settled;
      total.open += s.open;
    }
    return total;
  });
  /** The sheet's cut total in cents: everyone's cut totals added up. */
  protected readonly cutTotal = computed(() =>
    this.people().reduce((sum, p) => sum + this.cutTotalOf(p.id), 0));
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
  /** Every tag ever used on a claim or person of the sheet, as the store last told, sorted. */
  protected readonly usedTags = signal<string[]>([]);
  /**
   * Per tag ever used on the sheet (those of the claims and people on screen too), sorted: the dues of the
   * claims that count which carry it or whose person does, added up, each claim once per tag; a claim with
   * two tags counts in both.
   */
  protected readonly tagSummary = computed(() => {
    const floor = this.session.floorCentsOn;
    const late = this.session.late();
    const totals = new Map<string, number>(this.usedTags().map((t) => [t, 0]));
    for (const c of this.claims()) for (const t of c.tags) if (!totals.has(t)) totals.set(t, 0);
    for (const p of this.people()) for (const t of p.tags) if (!totals.has(t)) totals.set(t, 0);
    for (const p of this.people()) {
      const pctOn = this.pctOnOf(p.id);
      const graceOn = this.graceOnOf(p.id);
      const theirs = this.claimsOf(p.id);
      for (const c of theirs) {
        const tags = new Set([...c.tags, ...p.tags]);
        if (tags.size === 0 || !this.counts(c)) continue;
        const due = claimDueCents(c, pctOn, floor, late, roundsOn(c, theirs), graceOn) ?? 0;
        for (const t of tags) totals.set(t, (totals.get(t) ?? 0) + due);
      }
    }
    return [...totals.keys()].sort().map((tag) => ({ tag, dueTotal: money(totals.get(tag) ?? 0) }));
  });
  /**
   * Per calendar month (yyyy-mm) any claim of the sheet is dated in, sorted: the dues of the claims that
   * count dated in it, added up.
   */
  protected readonly monthSummary = computed(() => {
    const floor = this.session.floorCentsOn;
    const late = this.session.late();
    const totals = new Map<string, number>();
    for (const c of this.claims()) totals.set(c.date.slice(0, 7), 0);
    for (const p of this.people()) {
      const pctOn = this.pctOnOf(p.id);
      const graceOn = this.graceOnOf(p.id);
      const theirs = this.claimsOf(p.id);
      for (const c of theirs) {
        if (!this.counts(c)) continue;
        const month = c.date.slice(0, 7);
        const due = claimDueCents(c, pctOn, floor, late, roundsOn(c, theirs), graceOn) ?? 0;
        totals.set(month, (totals.get(month) ?? 0) + due);
      }
    }
    return [...totals.keys()].sort().map((month) => ({ month, dueTotal: money(totals.get(month) ?? 0) }));
  });
  /** Per status: how many of the sheet's claims have it. */
  protected readonly statusSummary = computed(() =>
    SUMMARY_STATUSES.map((status) => ({ status, count: this.claims().filter((c) => c.status === status).length })),
  );
  /** Per reason on the sheet's list: how many VOID claims were voided with it. */
  protected readonly voidReasonSummary = computed(() =>
    this.session.voidReasons().map((reason) => ({
      reason,
      count: this.claims().filter((c) => c.status === 'VOID' && c.voidReason === reason).length,
    })),
  );
  protected readonly money = money;

  /** Whether this screen shows the given people-list column. */
  protected shows(column: PeopleColumn): boolean {
    return !this.session.hiddenColumns().has(column);
  }
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
    /** Their contact; blank: none. */
    contact: new FormControl('', { nonNullable: true }),
    /** Their tags, comma separated; blank: none. */
    tags: new FormControl('', { nonNullable: true }),
  });

  /** A quick-add is under way; another one waits for its answer rather than storing twice. */
  private quickAdding = false;
  protected readonly quickError = signal('');
  protected readonly quickForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });

  /** An import is under way; another one waits for its answer rather than storing twice. */
  private importing = false;
  protected readonly importError = signal('');
  /** How many people the last import added; null before one, and while one runs. */
  protected readonly importAdded = signal<number | null>(null);
  protected readonly importUpdated = signal<number | null>(null);
  protected readonly importForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });

  /** A paste is under way; another one waits for its answer rather than storing twice. */
  private pasting = false;
  protected readonly pasteError = signal('');
  /** How many lines the last paste put through; null before one, and while one runs. */
  protected readonly pasteAdded = signal<number | null>(null);
  protected readonly pasteForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });

  /** The person's cut total in cents: their cuts across every pool they are a member of, added up. */
  protected cutTotalOf(personId: number): number {
    return this.session.cutTotals().get(personId) ?? 0;
  }

  constructor() {
    this.pasteForm.valueChanges.subscribe(() => this.pasteError.set(''));
    this.importForm.valueChanges.subscribe(() => this.importError.set(''));
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.quickForm.valueChanges.subscribe(() => this.quickError.set(''));
    this.bulkGradeForm.valueChanges.subscribe(() => this.personError.set(null));
    effect(() => this.session.people.set(this.people().map((p) => ({ id: p.id, name: p.name, retired: p.retired }))));
    effect(() => this.session.dueTotals.set(new Map(this.people().map((p) => [p.id, this.dueTotalCents(p)]))));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.people.set([]);
      this.descendants.set(null);
      this.claims.set([]);
      this.plans.set([]);
      this.plansGenerated.set(null);
      this.generateError.set('');
      this.selectedIds.set(new Set());
      this.selectedPeople.set(new Set());
      this.bulkGraded.set(null);
      this.bulkRetired.set(null);
      this.bulkGradeForm.reset({ grade: 'A', from: '' }, { emitEvent: false });
      this.bulkOpened.set(null);
      this.bulkError.set('');
      this.bulkSettled.set(null);
      this.bulkSettleError.set('');
      this.bulkReassigned.set(null);
      this.bulkReassignError.set('');
      this.bulkRedated.set(null);
      this.bulkRedateError.set('');
      this.rates.set([]);
      this.periods.set([]);
      this.usedTags.set([]);
      this.statements.set([]);
      this.personError.set(null);
      this.patronError.set(null);
      this.reset();
      this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
      this.quickError.set('');
      this.pasteForm.reset({ text: '' }, { emitEvent: false });
      this.pasteError.set('');
      this.pasteAdded.set(null);
      this.importForm.reset({ text: '' }, { emitEvent: false });
      this.importError.set('');
      this.importAdded.set(null);
      this.importUpdated.set(null);
      if (id) void this.load(id);
    });
    // the ancestors' rates, read again whenever the sheet is loaded or its parent changes
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.session.parent();
      this.inheritedRates.set([]);
      if (id) void this.loadInherited(id);
    });
  }

  private async loadInherited(sheet: string): Promise<void> {
    try {
      const levels = await firstValueFrom(
        this.http.get<Rate[][]>(`/api/sheets/${encodeURIComponent(sheet)}/rates/inherited`, { headers: this.headers() }),
      );
      if (this.session.sheetId() === sheet) this.inheritedRates.set(levels);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the parent sheet\'s rates.');
    }
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
      const [list, claims, rates, statements, periods, tags, descendants, plans] = await Promise.all([
        firstValueFrom(this.http.get<Person[]>(this.url(sheet), { headers: this.headers() })),
        firstValueFrom(this.http.get<Claim[]>(`/api/sheets/${encodeURIComponent(sheet)}/claims`, { headers: this.headers() })),
        firstValueFrom(this.http.get<Rate[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates`, { headers: this.headers() })),
        firstValueFrom(this.http.get<StatementSummary[]>(`/api/sheets/${encodeURIComponent(sheet)}/statements`, { headers: this.headers() })),
        firstValueFrom(this.http.get<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods`, { headers: this.headers() })),
        firstValueFrom(this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/claims/tags`, { headers: this.headers() })),
        firstValueFrom(this.http.get<Descendants>(`/api/sheets/${encodeURIComponent(sheet)}/descendants/figures`, { headers: this.headers() })),
        firstValueFrom(this.http.get<Plan[]>(`/api/sheets/${encodeURIComponent(sheet)}/plans`, { headers: this.headers() })),
      ]);
      if (this.session.sheetId() !== sheet) return;
      this.people.set(list);
      this.claims.set(claims);
      this.rates.set(rates);
      this.statements.set(statements);
      this.periods.set(periods);
      this.usedTags.set(tags);
      this.descendants.set(descendants);
      this.plans.set(plans);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the people on this sheet.');
    }
  }

  /** Re-read the sheet before an export; false when another sheet was loaded meanwhile. */
  private async reloadForExport(): Promise<boolean> {
    const sheet = this.session.sheetId();
    if (!sheet) return false;
    await this.load(sheet);
    return this.session.sheetId() === sheet;
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? NO_CLAIMS;
  }

  private claimedCents(personId: number): number {
    return this.claimsOf(personId).filter(this.countsClaimed).reduce((sum, c) => sum + cents(c.amount), 0);
  }

  protected claimedOf(personId: number): string {
    return money(this.claimedCents(personId));
  }

  /** The grade's rates in levels: the sheet's own, then each ancestor's, nearest first. */
  protected ratesOf(grade: string): Rate[][] {
    return this.ratesByGrade().get(grade) ?? NO_RATES;
  }

  /** The person's rate on a date, of the grade in force for them then. */
  protected pctOnOf(personId: number): PctOn {
    return this.pctOnByPerson().get(personId) ?? NO_PCT;
  }

  /** The person's grace days for a claim dated on a date, of the grade in force for them then. */
  protected graceOnOf(personId: number): GraceOn {
    return this.graceOnByPerson().get(personId) ?? NO_GRACE;
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
    const graceOn = this.graceOnOf(p.id);
    const floor = this.session.floorCentsOn;
    const late = this.session.late();
    const gradeToday = this.gradeOf(p);
    const today = this.session.today();
    const otherRate = (c: Claim) => {
      const rates = this.ratesOf(gradeOn(p, c.date));
      return today !== null && rateInLevels(rates, c.date, c.added) !== rateInLevels(rates, today);
    };
    const fixed = (c: Claim) => c.settledDue !== null || (isBound(c) && c.statedDue !== null)
      || ((gradeOn(p, c.date) !== gradeToday || otherRate(c)) && claimDueCents(c, pctOn, floor, late, roundsOn(c, theirs), graceOn) !== null);
    if (this.pctOf(gradeToday) === null && !counted.some(fixed)) return null;
    return counted.reduce((sum, c) => sum + (claimDueCents(c, pctOn, floor, late, roundsOn(c, theirs), graceOn) ?? 0), 0);
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

  protected splitOf(p: Person): Split {
    return this.splitByPerson().get(p.id) ?? { settled: 0, open: 0 };
  }

  protected agedOf(p: Person): Aged {
    return this.agedByPerson().get(p.id) ?? { upTo30: 0, upTo60: 0, over60: 0 };
  }

  /** Their due-total, or n/a. */
  protected dueTotalOf(p: Person): string {
    const total = this.dueTotalCents(p);
    return total === null ? NA : money(total);
  }

  /**
   * Downloads everyone, retired people included, in list order as CSV: name, grade in force today,
   * retired-from (or nothing) and due-total as shown. Re-read from the store first, so while an as-of
   * date is set it holds the as-of figures.
   */
  protected async exportCsv(): Promise<void> {
    if (!(await this.reloadForExport())) return;
    const lines = [['name', 'grade', 'retired-from', 'due-total'], ...this.people().map((p) => [p.name, this.gradeOf(p), p.retiredFrom ?? '', this.dueTotalOf(p)])];
    downloadCsv(lines, 'people.csv');
  }

  /**
   * Downloads every claim as CSV, people in list order (retired people included) and each person's
   * claims oldest first: person, number, date, amount, due, status and period, as the screen shows them.
   * Re-read from the store first, so while an as-of date is set it holds the as-of figures.
   */
  protected async exportClaims(): Promise<void> {
    if (!(await this.reloadForExport())) return;
    const lines = [['person', 'number', 'date', 'amount', 'due', 'status', 'period']];
    for (const p of this.people()) {
      const theirs = this.claimsOf(p.id);
      const pctOn = this.pctOnOf(p.id);
      const graceOn = this.graceOnOf(p.id);
      for (const c of [...theirs].reverse()) {
        const due = claimDueCents(c, pctOn, this.session.floorCentsOn, this.session.late(), roundsOn(c, theirs), graceOn);
        lines.push([p.name, c.number ?? '', c.date, c.amount, due === null ? NA : money(due), c.status,
          periodOn(this.periods(), c.dueBy)?.name ?? '']);
      }
    }
    downloadCsv(lines, 'claims.csv');
  }

  /** Their patrons as <name> <share>, sorted by name, comma and space separated. */
  protected patronsOf(p: Person): string {
    return this.patronShares(p)
      .sort((a, b) => (a.patron.name < b.patron.name ? -1 : a.patron.name > b.patron.name ? 1 : 0))
      .map((x) => `${x.patron.name} ${money(x.share)}`)
      .join(', ');
  }

  /**
   * Their backing: over their patrons, the patron's settled figures (SETTLED claims dated within the 90
   * days up to and including the sheet's today, at their frozen figures) times the patron's share divided
   * by 100, rounded half to even, added up; 0.00 with none.
   */
  protected backingOf(p: Person): string {
    const today = this.session.today();
    if (today === null) return money(0);
    const from = new Date(Date.parse(`${today}T00:00:00Z`) - 89 * 86_400_000).toISOString().slice(0, 10);
    const settled = (c: Claim) => c.status === 'SETTLED' && c.settledDue !== null && c.date >= from && c.date <= today;
    return money(this.patronShares(p).reduce((sum, x) => {
      const figure = this.claimsOf(x.patron.id).filter(settled).reduce((s, c) => s + cents(c.settledDue!), 0);
      return sum + dueCents(figure, money(x.share));
    }, 0));
  }

  /**
   * Their patrons in the order named, each with their share in hundredths: 100.00 split equally, each
   * rounded half to even, the remainder to the first named.
   */
  private patronShares(p: Person): { patron: Person; share: number }[] {
    const patrons = this.patronsOfPerson(p);
    const n = patrons.length;
    if (n === 0) return [];
    const quotient = Math.floor(10000 / n);
    const twice = (10000 % n) * 2;
    const each = twice > n || (twice === n && quotient % 2 === 1) ? quotient + 1 : quotient;
    return patrons.map((patron, i) => ({ patron, share: i === 0 ? 10000 - each * (n - 1) : each }));
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

  /**
   * Give the person an alias; one blank, or already a name or an alias on the sheet as names are
   * compared, is refused. The entry is cleared once the alias is added.
   */
  protected async addAlias(p: Person, input: HTMLInputElement): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const alias = input.value.trim();
    if (!alias) {
      this.personError.set({ id: p.id, message: 'Enter an alias.' });
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(`${this.url(sheet)}/${p.id}/aliases`, { alias }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      input.value = '';
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.personError.set({
        id: p.id,
        message: e instanceof HttpErrorResponse && e.status === 409
          ? 'That alias is already a name or an alias on this sheet.'
          : 'Could not add that alias for ' + p.name + '.',
      });
    }
  }

  /**
   * Merge the person into the one chosen in the select (a steward only; not into themselves, neither of
   * them retired). Once merged the people and their claims are read again, as their claims and patrons moved.
   */
  protected async merge(p: Person, select: HTMLSelectElement): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const intoId = Number(select.value);
    if (!select.value || !Number.isInteger(intoId)) {
      this.personError.set({ id: p.id, message: 'Choose a person to merge into.' });
      return;
    }
    try {
      await firstValueFrom(
        this.http.post<Person>(`${this.url(sheet)}/${p.id}/merge`, { intoId }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      await this.load(sheet);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.personError.set({
        id: p.id,
        message: e instanceof HttpErrorResponse && e.status === 409
          ? 'Only a steward can merge, into another person, neither of them retired.'
          : 'Could not merge ' + p.name + '.',
      });
    }
  }

  /**
   * Transfer the person to another sheet by its id (a steward only; not a retired person, nor to this
   * sheet). Once transferred the people and their claims are read again, as the person left the sheet.
   */
  protected async transfer(p: Person, input: HTMLInputElement): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const to = input.value.trim();
    if (!/^[A-Za-z0-9._-]{1,100}$/.test(to)) {
      this.personError.set({ id: p.id, message: 'Enter the id of the sheet to transfer to.' });
      return;
    }
    try {
      await firstValueFrom(
        this.http.post<void>(`${this.url(sheet)}/${p.id}/transfer`, { toSheetId: to }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      await this.load(sheet);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.personError.set({
        id: p.id,
        message: e instanceof HttpErrorResponse && e.status === 409
          ? 'Only a steward can transfer, not a retired person, to another sheet without their name, aliases or contact.'
          : 'Could not transfer ' + p.name + '.',
      });
    }
  }

  /** Swap the person with the one above or below them in the list; the top cannot move up nor the bottom down. */
  protected async move(p: Person, direction: 'up' | 'down'): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      await firstValueFrom(
        this.http.post<Person>(`${this.url(sheet)}/${p.id}/move`, { direction }, { headers: this.headers() }),
      );
      const list = await firstValueFrom(this.http.get<Person[]>(this.url(sheet), { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.people.set(list);
    } catch {
      if (this.session.sheetId() === sheet) {
        this.personError.set({ id: p.id, message: 'Could not move ' + p.name + ' ' + direction + '.' });
      }
    }
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

  /** Unretire the retired person on the sheet's today; anyone not retired is refused. */
  protected async unretire(p: Person): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(`${this.url(sheet)}/${p.id}/unretire`, {}, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 409
        ? p.name + ' is not retired.'
        : 'Could not unretire ' + p.name + '.';
      this.personError.set({ id: p.id, message });
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
      this.session.holdChanges.update((n) => n + 1);
    } catch {
      if (this.session.sheetId() === sheet) this.personError.set({ id: p.id, message: 'Could not hold ' + p.name + '.' });
    }
  }

  /**
   * Record the grade for the person from the sheet's today, as the person form would: their other
   * values kept, made from the version shown.
   */
  protected async saveGrade(p: Person, grade: string): Promise<void> {
    this.personError.set(null);
    this.clearPatronError(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (!this.grades.includes(grade)) {
      this.personError.set({ id: p.id, message: 'Choose a grade.' });
      return;
    }
    const body = {
      name: p.name, grade, gradeFrom: null, terms: p.terms?.toString() ?? '', contact: p.contact ?? '',
      tags: p.tags.join(', '), version: p.version,
    };
    try {
      const saved = await firstValueFrom(
        this.http.put<Person>(`${this.url(sheet)}/${p.id}`, body, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 412
        ? 'Someone else saved ' + p.name + ' meanwhile; load the sheet again to see their values.'
        : 'Could not save the grade for ' + p.name + '.';
      this.personError.set({ id: p.id, message });
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

  /** Put each imported rate in place, in the order they were set. */
  protected ratesImported(rates: Rate[]): void {
    for (const rate of rates) this.rateSaved(rate);
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

  protected personToggled(id: number, checked: boolean): void {
    this.clearPersonError(id);
    this.selectedPeople.update((ids) => {
      const next = new Set(ids);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /**
   * Give each selected person the grade from the date (blank: the sheet's today), in list order, as the
   * person form would; show how many were changed. A refusal changes none and shows on the first selected row.
   */
  protected async bulkGrade(): Promise<void> {
    if (this.bulkRunning) return;
    this.bulkGraded.set(null);
    this.personError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ids = this.listed().filter((p) => this.selectedPeople().has(p.id)).map((p) => p.id);
    const { grade, from: rawFrom } = this.bulkGradeForm.getRawValue();
    const from = rawFrom.trim();
    const refuse = (message: string) => {
      const first = ids[0] ?? this.listed()[0]?.id;
      if (first !== undefined) this.personError.set({ id: first, message });
    };
    if (ids.length === 0) {
      refuse('Select at least one person to grade.');
      return;
    }
    if (!this.grades.includes(grade) || (from && !/^\d{4}-\d{2}-\d{2}$/.test(from))) {
      refuse('Choose a grade, and enter a date or leave it blank for today.');
      return;
    }
    this.bulkRunning = true;
    try {
      const result = await firstValueFrom(this.http.post<{ graded: number }>(
        `${this.url(sheet)}/grade`, { ids, grade, from: from || null }, { headers: this.headers() }));
      const list = await firstValueFrom(this.http.get<Person[]>(this.url(sheet), { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.people.set(list);
      this.selectedPeople.set(new Set());
      this.bulkGraded.set(result.graded);
    } catch {
      if (this.session.sheetId() === sheet) refuse('Could not grade the selected people.');
    } finally {
      this.bulkRunning = false;
    }
  }

  /** Retire the selected people from the date (blank: the sheet's today), in list order; show how many were retired. */
  protected async bulkRetire(date: string): Promise<void> {
    if (this.bulkRunning) return;
    this.bulkRetired.set(null);
    this.personError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ids = this.listed().filter((p) => this.selectedPeople().has(p.id)).map((p) => p.id);
    const from = date.trim();
    const refuse = (message: string) => {
      const first = ids[0] ?? this.listed()[0]?.id;
      if (first !== undefined) this.personError.set({ id: first, message });
    };
    if (ids.length === 0) {
      refuse('Select at least one person to retire.');
      return;
    }
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      refuse('Enter a date, or leave it blank for today.');
      return;
    }
    this.bulkRunning = true;
    try {
      const result = await firstValueFrom(this.http.post<{ retired: number }>(
        `${this.url(sheet)}/retire`, { ids, from: from || null }, { headers: this.headers() }));
      const list = await firstValueFrom(this.http.get<Person[]>(this.url(sheet), { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.people.set(list);
      this.selectedPeople.set(new Set());
      this.bulkRetired.set(result.retired);
    } catch {
      if (this.session.sheetId() === sheet) refuse('Could not retire the selected people.');
    } finally {
      this.bulkRunning = false;
    }
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
      const { lastBatch, ...result } = await firstValueFrom(this.http.post<{ settled: number; refused: number; lastBatch: number | null }>(
        `${base}/claims/settle`, { ids: [...this.selectedIds()] }, { headers: this.headers() }));
      const claims = await firstValueFrom(this.http.get<Claim[]>(`${base}/claims`, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.claims.set(claims);
      this.selectedIds.set(new Set());
      this.session.lastBatch.set(lastBatch);
      this.bulkSettled.set(result);
    } catch {
      if (this.session.sheetId() === sheet) this.bulkSettleError.set('Could not settle the selected claims.');
    } finally {
      this.bulkRunning = false;
    }
  }

  /** Move every selected claim that can be moved to the person, in list order; show how many moved and were refused. */
  protected async bulkReassign(to: string): Promise<void> {
    if (this.bulkRunning) return;
    this.bulkReassigned.set(null);
    this.bulkReassignError.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (!to) {
      this.bulkReassignError.set('Choose the person to move the selected claims to.');
      return;
    }
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    this.bulkRunning = true;
    try {
      const result = await firstValueFrom(this.http.post<{ reassigned: number; refused: number }>(
        `${base}/claims/reassign`, { ids: [...this.selectedIds()], personId: Number(to) }, { headers: this.headers() }));
      const claims = await firstValueFrom(this.http.get<Claim[]>(`${base}/claims`, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.claims.set(claims);
      this.selectedIds.set(new Set());
      this.bulkReassigned.set(result);
    } catch {
      if (this.session.sheetId() === sheet) this.bulkReassignError.set('Could not move the selected claims.');
    } finally {
      this.bulkRunning = false;
    }
  }

  /** Re-date every selected claim that can be re-dated, in list order; show how many were re-dated and refused. */
  protected async bulkRedate(value: string): Promise<void> {
    if (this.bulkRunning) return;
    this.bulkRedated.set(null);
    this.bulkRedateError.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const date = value.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.bulkRedateError.set('Enter the new date of the selected claims.');
      return;
    }
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    this.bulkRunning = true;
    try {
      const result = await firstValueFrom(this.http.post<{ redated: number; refused: number }>(
        `${base}/claims/redate`, { ids: [...this.selectedIds()], date }, { headers: this.headers() }));
      const claims = await firstValueFrom(this.http.get<Claim[]>(`${base}/claims`, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.claims.set(claims);
      this.selectedIds.set(new Set());
      this.bulkRedated.set(result);
    } catch {
      if (this.session.sheetId() === sheet) this.bulkRedateError.set('Could not re-date the selected claims.');
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
    this.form.reset({ name: '', grade: 'A', gradeFrom: '', terms: '', contact: '', tags: '' }, { emitEvent: false });
    this.error.set('');
  }

  protected edit(p: Person): void {
    this.personError.set(null);
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.form.setValue({ name: p.name, grade: this.gradeOf(p), gradeFrom: '', terms: p.terms?.toString() ?? '', contact: p.contact ?? '', tags: p.tags.join(', ') }, { emitEvent: false });
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
    const contact = raw.contact.trim().toLowerCase();
    const tags = raw.tags;
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
          this.http.post<Person>(this.url(sheet), { name, grade, terms, contact, tags }, { headers: this.headers() }),
        );
        if (this.session.sheetId() !== sheet) return;
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await firstValueFrom(
          this.http.put<Person>(`${this.url(sheet)}/${id}`, { name, grade, gradeFrom, terms, contact, tags, version: this.editingVersion }, { headers: this.headers() }),
        );
        if (this.session.sheetId() !== sheet) return;
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 412 && id !== null) {
        await this.showCurrent(sheet, id);
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name or contact is already in use on this sheet.');
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
          ? 'No claim due inside a closed period can be changed.'
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
  /** The person's plans, in the order they were added. */
  protected plansOf(personId: number): Plan[] {
    return this.plansByPerson().get(personId) ?? [];
  }

  protected planAdded(plan: Plan): void {
    this.plans.update((list) => [...list, plan]);
  }

  /**
   * Make every plan's claims due by the sheet's today that it has not made yet, then read the claims and
   * plans again: the count shown is how many were made.
   */
  protected async generatePlans(): Promise<void> {
    if (this.generating) return;
    this.generateError.set('');
    this.plansGenerated.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    this.generating = true;
    try {
      const done = await firstValueFrom(
        this.http.post<{ generated: number }>(`/api/sheets/${encodeURIComponent(sheet)}/plans/generate`, null,
          { headers: this.headers() }),
      );
      const [claims, plans] = await Promise.all([
        firstValueFrom(this.http.get<Claim[]>(`/api/sheets/${encodeURIComponent(sheet)}/claims`, { headers: this.headers() })),
        firstValueFrom(this.http.get<Plan[]>(`/api/sheets/${encodeURIComponent(sheet)}/plans`, { headers: this.headers() })),
      ]);
      if (this.session.sheetId() !== sheet) return;
      this.claims.set(claims);
      this.plans.set(plans);
      this.plansGenerated.set(done.generated);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.generateError.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'You may not change this sheet.'
          : 'Could not generate the planned claims.',
      );
    } finally {
      this.generating = false;
    }
  }

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
          ? 'No claim due inside a closed period can be changed.'
          : isDuplicateRef(e)
          ? `${DUPLICATE_REF} Each line's reference must be new; nothing was added.`
          : e instanceof HttpErrorResponse && e.status === 400
          ? 'Every line must be name,amount,date,note for a person on the sheet, and a line matching an existing claim must match a DRAFT; nothing was added.'
          : 'A line could not be added and opened (a person retired, a note required, a period\'s cap, a permission or a lock); nothing was added.',
      );
    } finally {
      this.pasting = false;
    }
  }

  /**
   * Add a person per pasted line of name,grade,terms,contact, in order, each as the person form adds
   * one; a line naming a person on the sheet updates them as their edit form does instead. Every line
   * is checked first; if any is refused, nothing is imported.
   */
  protected async importPeople(): Promise<void> {
    if (this.importing) return;
    this.importError.set('');
    this.importAdded.set(null);
    this.importUpdated.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = this.importForm.getRawValue().text;
    if (!text.trim()) {
      this.importError.set('Paste at least one line of name,grade,terms,contact.');
      return;
    }
    this.importing = true;
    try {
      const done = await firstValueFrom(
        this.http.post<{ added: number; updated: number; people: Person[]; updatedPeople: Person[] }>(
          `${this.url(sheet)}/import`, { text }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      const updated = new Map(done.updatedPeople.map((p) => [p.id, p]));
      this.people.update((list) => [...list.map((p) => updated.get(p.id) ?? p), ...done.people]);
      this.importUpdated.set(done.updated);
      this.importAdded.set(done.added);
      this.importForm.reset({ text: '' }, { emitEvent: false });
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.importError.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'A name or contact is already in use on this sheet, or given twice; nothing was imported.'
          : 'Every line must be name,grade,terms,contact as the person form takes them; nothing was imported.',
      );
    } finally {
      this.importing = false;
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
        this.form.setValue({ name: current.name, grade: this.gradeOf(current), gradeFrom: '', terms: current.terms?.toString() ?? '', contact: current.contact ?? '', tags: current.tags.join(', ') }, { emitEvent: false });
      }
    } catch {
      // the error already shown stands
    }
  }
}
