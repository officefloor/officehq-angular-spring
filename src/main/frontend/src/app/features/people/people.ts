import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DOCUMENT, computed, effect, inject, signal, untracked } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { COLUMNS, UserColumns } from '../../core/columns';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT, Claim, Claims, activeOn, claimedTotal, counts, deferred, dueOf, graceOf, isBound, isOpen, overdue, plusDays, reviewLate } from '../claims/claims';
import { DatedFigure, PeriodStore, Periods, periodOn, personPeriodCarried, personPeriodDueTotals } from '../periods/periods';
import { DatedGrade, GRADES, RateStore, atMost, equalShares, excessOver, gradeOn, sharedSum, sumSkippingNa, trimmedSum } from '../rates/rates';
import { SettingsStore } from '../settings/settings';
import { Header } from '../header/header';
import { Consolidated } from '../consolidated/consolidated';
import { Summary, SummaryPerson } from '../summary/summary';
import { PoolRoster, PoolStore } from '../pools/pools';
import { CrewRoster, crewDueTotal } from '../crews/crews';
import { PersonPlans, PlanStore } from '../plans/plans';

interface Person {
  id: number;
  name: string;
  // The grade in force on the sheet's today when the store answered, and every grade recorded, each from its date.
  grade: string;
  grades: DatedGrade[];
  // The person's own terms in days in force on the sheet's today, ahead of their grade's and the sheet's; null for none.
  terms: number | null;
  // The person's contact, without surrounding spaces and lower-cased; null for none.
  contact: string | null;
  // The person's tags, trimmed, lower-cased, without repeats and sorted; none for a person with none.
  tags: string[];
  // The person's aliases, sorted as names are compared: ignoring case.
  aliases: string[];
  // Whether the person's name or an alias is a name or alias on another sheet of the sheet's tree; every edit of
  // them is refused until it renames them out of the clash.
  clashing: boolean;
  // Raised by the store on each save; an edit made from an older version is refused.
  version: number;
  // The date the person is retired from; null while not retired. A person with one set is retired at once.
  retiredFrom: string | null;
  // The retirements the person was unretired from, earliest first: each from its from-date to the day unretired.
  pastRetirements: PastRetirement[];
  // One line per SNAPSHOT written for the person, oldest first: the day being left and their due-total then.
  snapshots: SnapshotLine[];
  // The sheet the person was transferred from; null when they were not.
  originSheet: string | null;
  // Whether the person has any hold, and every hold put on them, earliest from-date first.
  held: boolean;
  holds: DatedHold[];
}

// A hold on a person from its from-date to its to-date (null: with no end), both included. Their open claims dated
// within it, not bound by a statement, count in no live total.
interface DatedHold {
  from: string;
  to: string | null;
}

// A retirement a person was unretired from: from its from-date to the sheet's today when they were unretired.
interface PastRetirement {
  from: string;
  to: string;
}

// A snapshot line: the day being left when the sheet's today changed, and the person's due-total (null: n/a) then.
interface SnapshotLine {
  day: string;
  dueTotal: string | null;
}

// One person named as another's patron.
interface PatronView {
  id: number;
  personId: number;
  patronId: number;
}

// A person's statements: how many have been taken, and the last one's due-total (null for n/a) and date.
interface StatementView {
  personId: number;
  count: number;
  dueTotal: string | null;
  date: string;
  // Each statement still standing, oldest first.
  lines: { date: string; dueTotal: string | null }[];
}

// The people on the active sheet: one form adds a person or, with an editor open, saves one.
@Component({
  selector: 'app-people',
  imports: [FigPipe, Claims, Consolidated, Header, Periods, PersonPlans, Summary],
  template: `
    <app-header
      [peopleCount]="people().length"
      [statuses]="claimStatuses()"
      [dueTotal]="dueTotal()"
      [overdueTotal]="overdueTotal()"
      [myDueTotal]="myFigures()?.dueTotal ?? ''"
      [myOverdueTotal]="myFigures()?.overdueTotal ?? ''"
      [myCrewDueTotal]="myCrewDueTotal()"
    />
    <app-consolidated
      [ownPeopleCount]="people().length"
      [ownDueTotal]="dueTotal()"
      [ownOverdueTotal]="overdueTotal()"
    />
    <app-periods [figures]="countedFigures()" />
    <app-summary
      [people]="summaryPeople()"
      [figures]="countedFigures()"
      [statuses]="claimStatuses()"
      [voidedWith]="voidedWith()"
      [reviewLateCount]="reviewLateCount()"
    />

    <section aria-label="People">
      <h2>People</h2>

      <form (submit)="submit($event)" [attr.aria-label]="editingId() === null ? 'Add person' : 'Edit person'">
        <label for="person-name">Name</label>
        <input
          id="person-name"
          data-testid="person-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="person-grade">Grade</label>
        <select
          id="person-grade"
          data-testid="person-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="person-grade-from">Grade from</label>
        <input
          type="date"
          id="person-grade-from"
          data-testid="person-form-grade-from"
          [value]="gradeFrom()"
          (input)="gradeFrom.set(value($event)); error.set(null)"
        />
        <label for="person-terms">Terms (days)</label>
        <input
          id="person-terms"
          inputmode="numeric"
          data-testid="person-form-terms"
          [value]="terms()"
          (input)="terms.set(value($event)); error.set(null)"
        />
        <label for="person-terms-from">Terms from</label>
        <input
          type="date"
          id="person-terms-from"
          data-testid="person-form-terms-from"
          [value]="termsFrom()"
          (input)="termsFrom.set(value($event)); error.set(null)"
        />
        <label for="person-contact">Contact</label>
        <input
          id="person-contact"
          data-testid="person-form-contact"
          [value]="contact()"
          (input)="contact.set(value($event)); error.set(null)"
        />
        <label for="person-tags">Tags (comma separated)</label>
        <input
          id="person-tags"
          data-testid="person-form-tags"
          [value]="tags()"
          (input)="tags.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err | fig }}</p>
        }
      </form>

      <form (submit)="quickAdd($event)" aria-label="Quick add claim">
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
        <button type="submit" data-testid="quick-submit">Quick add</button>
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err | fig }}</p>
        }
      </form>

      <form (submit)="importPeople($event)" aria-label="Import people">
        <label for="import-people">Import people, one per line as name,grade,terms,contact</label>
        <textarea
          id="import-people"
          data-testid="import-people"
          rows="4"
          [value]="importText()"
          (input)="importText.set(value($event)); importError.set(null)"
        ></textarea>
        <button type="submit" data-testid="import-submit">Import people</button>
        @if (importAdded() !== null) {
          <p role="status">
            Added: <strong data-testid="import-added">{{ importAdded() | fig }}</strong>, updated:
            <strong data-testid="import-updated">{{ importUpdated() | fig }}</strong>
          </p>
        }
        @if (importError(); as err) {
          <p role="alert" data-testid="import-error">{{ err | fig }}</p>
        }
      </form>

      <form (submit)="paste($event)" aria-label="Paste claims">
        <label for="paste-claims">Paste claims, one per line as name,amount,date,note</label>
        <textarea
          id="paste-claims"
          data-testid="paste-claims"
          rows="4"
          [value]="pasteText()"
          (input)="pasteText.set(value($event)); pasteError.set(null)"
        ></textarea>
        <button type="submit" data-testid="paste-submit">Open pasted claims</button>
        @if (pasteAdded() !== null) {
          <p role="status">Went through: <strong data-testid="paste-added">{{ pasteAdded() | fig }}</strong></p>
        }
        @if (pasteError(); as err) {
          <p role="alert" data-testid="paste-error">{{ err | fig }}</p>
        }
      </form>

      <div role="group" aria-label="Generate the plans' claims">
        <button type="button" data-testid="plan-generate" (click)="generatePlans()">Generate plan claims</button>
        @if (plansGenerated() !== null) {
          <p role="status">Made: <strong data-testid="plan-generated-count">{{ plansGenerated() | fig }}</strong></p>
        }
        @if (planGenerateError(); as err) {
          <p role="alert" data-testid="plan-generate-error">{{ err | fig }}</p>
        }
      </div>

      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="showRetired()"
          (change)="showRetired.set(checked($event))"
        />
        Show retired
      </label>
      <fieldset>
        <legend>Columns shown</legend>
        @for (c of columnChoices; track c.id) {
          <label>
            <input
              type="checkbox"
              [attr.data-testid]="'column-' + c.id"
              [checked]="columns.shown(c.id)"
              (change)="columns.set(c.id, checked($event))"
            />
            {{ c.label }}
          </label>
        }
      </fieldset>
      <label>
        Sort by
        <select data-testid="sort-by" [value]="sortKey()" (change)="sortKey.set(value($event))">
          <option value="added">Added</option>
          <option value="name">Name</option>
          <option value="grade">Grade</option>
          <option value="due-total">Due-total</option>
        </select>
      </label>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <button type="button" data-testid="export-claims" (click)="exportClaims()">Export claims (CSV)</button>

      <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() | fig }}</strong></p>
      <p>Due in total: <strong data-testid="rollup-due-total">{{ dueTotal() | fig }}</strong></p>
      <p>Overdue claims: <strong data-testid="rollup-overdue-count">{{ overdueClaims().length | fig }}</strong></p>
      <p>Overdue in total: <strong data-testid="rollup-overdue-total">{{ overdueTotal() | fig }}</strong></p>
      <p>Settled in total: <strong data-testid="rollup-settled-total">{{ settledTotal() | fig }}</strong></p>
      <p>Open in total: <strong data-testid="rollup-open-total">{{ openTotal() | fig }}</strong></p>
      <p>Cut in total: <strong data-testid="rollup-cut-total">{{ poolStore.cutTotal() | fig }}</strong></p>
      @if (tagDueTotals().length > 0) {
        <table>
          <caption>Due by tag</caption>
          <thead>
            <tr><th scope="col">Tag</th><th scope="col">Due</th></tr>
          </thead>
          <tbody>
            @for (t of tagDueTotals(); track t.tag) {
              <tr>
                <th scope="row">{{ t.tag }}</th>
                <td [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ t.dueTotal | fig }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
      @if (monthDueTotals().length > 0) {
        <table>
          <caption>Due by month</caption>
          <thead>
            <tr><th scope="col">Month</th><th scope="col">Due</th></tr>
          </thead>
          <tbody>
            @for (m of monthDueTotals(); track m.month) {
              <tr>
                <th scope="row">{{ m.month }}</th>
                <td [attr.data-testid]="'month-' + m.month + '-due-total'">{{ m.dueTotal | fig }}</td>
              </tr>
            }
          </tbody>
        </table>
      }

      <div role="group" aria-label="Selected claims">
        <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
        @if (bulkOpened(); as result) {
          <p role="status">
            Opened: <strong data-testid="bulk-opened">{{ result.opened | fig }}</strong>
            Refused: <strong data-testid="bulk-refused">{{ result.refused | fig }}</strong>
          </p>
        }
        @if (bulkError(); as err) {
          <p role="alert" data-testid="bulk-open-error">{{ err | fig }}</p>
        }
        <button type="button" data-testid="bulk-settle" (click)="bulkSettle()">Settle selected claims</button>
        <span>Last batch: <strong data-testid="bulk-settle-batch">{{ (session.sheetLastBatch() ?? '') | fig }}</strong></span>
        @if (bulkSettled(); as result) {
          <p role="status">
            Settled: <strong data-testid="bulk-settled">{{ result.settled | fig }}</strong>
            Refused: <strong data-testid="bulk-settle-refused">{{ result.refused | fig }}</strong>
          </p>
        }
        @if (bulkSettleError(); as err) {
          <p role="alert" data-testid="bulk-settle-error">{{ err | fig }}</p>
        }
        <label for="bulk-reassign-to">Move selected claims to</label>
        <select
          id="bulk-reassign-to"
          data-testid="bulk-reassign-to"
          (change)="bulkReassignTo.set(value($event)); bulkReassignError.set(null)"
        >
          <option value="" [selected]="!bulkReassignTo()">Choose a person</option>
          @for (q of people(); track q.id) {
            <option [value]="q.id" [selected]="bulkReassignTo() === '' + q.id">{{ q.name }}</option>
          }
        </select>
        <button type="button" data-testid="bulk-reassign" (click)="bulkReassign()">Move selected claims</button>
        @if (bulkReassigned(); as result) {
          <p role="status">
            Moved: <strong data-testid="bulk-reassigned">{{ result.reassigned | fig }}</strong>
            Refused: <strong data-testid="bulk-reassign-refused">{{ result.refused | fig }}</strong>
          </p>
        }
        @if (bulkReassignError(); as err) {
          <p role="alert" data-testid="bulk-reassign-error">{{ err | fig }}</p>
        }
        <label for="bulk-redate-date">Re-date selected claims to</label>
        <input
          type="date"
          id="bulk-redate-date"
          data-testid="bulk-redate-date"
          [value]="bulkRedateDate()"
          (input)="bulkRedateDate.set(value($event)); bulkRedateError.set(null)"
        />
        <button type="button" data-testid="bulk-redate" (click)="bulkRedate()">Re-date selected claims</button>
        @if (bulkRedated(); as result) {
          <p role="status">
            Re-dated: <strong data-testid="bulk-redated">{{ result.redated | fig }}</strong>
            Refused: <strong data-testid="bulk-redate-refused">{{ result.refused | fig }}</strong>
          </p>
        }
        @if (bulkRedateError(); as err) {
          <p role="alert" data-testid="bulk-redate-error">{{ err | fig }}</p>
        }
      </div>

      <div role="group" aria-label="Selected people">
        <label for="bulk-grade">Grade for selected people</label>
        <select
          id="bulk-grade"
          data-testid="bulk-grade"
          [value]="bulkGrade()"
          (change)="bulkGrade.set(value($event)); bulkGradeError.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === bulkGrade()">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">Grade from</label>
        <input
          type="date"
          id="bulk-grade-from"
          data-testid="bulk-grade-from"
          [value]="bulkGradeFrom()"
          (input)="bulkGradeFrom.set(value($event)); bulkGradeError.set(null)"
        />
        <button type="button" data-testid="bulk-grade-apply" (click)="applyBulkGrade()">Grade selected people</button>
        @if (bulkGraded() !== null) {
          <p role="status">Changed: <strong data-testid="bulk-graded">{{ bulkGraded() | fig }}</strong></p>
        }
        @if (bulkGradeError(); as err) {
          <p role="alert" data-testid="person-error">{{ err | fig }}</p>
        }
        <label for="bulk-retire-date">Retire selected people from</label>
        <input
          type="date"
          id="bulk-retire-date"
          data-testid="bulk-retire-date"
          [value]="bulkRetireFrom()"
          (input)="bulkRetireFrom.set(value($event)); bulkRetireError.set(null)"
        />
        <button type="button" data-testid="bulk-retire" (click)="applyBulkRetire()">Retire selected people</button>
        @if (bulkRetired() !== null) {
          <p role="status">Retired: <strong data-testid="bulk-retired">{{ bulkRetired() | fig }}</strong></p>
        }
        @if (bulkRetireError(); as err) {
          <p role="alert" data-testid="person-error">{{ err | fig }}</p>
        }
      </div>

      <table>
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">Name</th><th scope="col">Grade</th>@if (columns.shown('terms')) {<th scope="col">Terms</th>}@if (columns.shown('contact')) {<th scope="col">Contact</th>}<th scope="col">Tags</th>@if (columns.shown('aliases')) {<th scope="col">Aliases</th>}<th scope="col">Clashing</th><th scope="col">From sheet</th><th scope="col">Cut total</th>@if (columns.shown('claimed')) {<th scope="col">Claimed</th>}@if (columns.shown('due-total')) {<th scope="col">Due</th>}
            <th scope="col">Settled total</th><th scope="col">Open total</th>
            @if (columns.shown('overdue-count')) {<th scope="col">Overdue</th>}@if (columns.shown('overdue-total')) {<th scope="col">Overdue total</th>}
            <th scope="col">Due aged 0-30</th><th scope="col">Due aged 31-60</th><th scope="col">Due aged 61+</th>
            @if (columns.shown('statement-count')) {<th scope="col">Statements</th>}@if (columns.shown('stated-total')) {<th scope="col">Last stated total</th>}@if (columns.shown('statement-date')) {<th scope="col">Last statement</th>}
            @if (columns.shown('retired')) {<th scope="col">Retired</th>}<th scope="col">Retired from</th>@if (columns.shown('hold')) {<th scope="col">On hold</th>}<th scope="col">Active</th>
            @if (columns.shown('patrons')) {<th scope="col">Patrons</th>}@if (columns.shown('backing')) {<th scope="col">Backing</th>}@if (columns.shown('excess')) {<th scope="col">Excess</th>}<th scope="col">Watched</th>
            @for (pd of periods.list(); track pd.id) {
              <th scope="col">Due in {{ pd.name }}</th>
              <th scope="col">Carried into {{ pd.name }}</th>
            }
            <th scope="col"><span class="visually-hidden">Actions</span></th><th scope="col">Plans</th><th scope="col">Claims</th>
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
              <td data-testid="person-name">{{ p.name | fig }}</td>
              <td data-testid="person-grade">{{ gradeToday(p) | fig }}</td>
              @if (columns.shown('terms')) {
                <td data-testid="person-terms">{{ (p.terms ?? '') | fig }}</td>
              }
              @if (columns.shown('contact')) {
                <td data-testid="person-contact">{{ (p.contact ?? '') | fig }}</td>
              }
              <td data-testid="person-tags">{{ (p.tags.join(', ')) | fig }}</td>
              @if (columns.shown('aliases')) {
                <td data-testid="person-aliases">{{ (p.aliases.join(', ')) | fig }}</td>
              }
              <td data-testid="person-clashing">{{ (p.clashing ? 'yes' : 'no') | fig }}</td>
              <td data-testid="person-origin">{{ (p.originSheet ?? '') | fig }}</td>
              <td data-testid="person-cut-total">{{ (poolStore.cutTotals().get(p.id) ?? '0.00') | fig }}</td>
              @if (columns.shown('claimed')) {
                <td data-testid="person-claimed">{{ (claimedBy().get(p.id) ?? '0.00') | fig }}</td>
              }
              @if (columns.shown('due-total')) {
                <td data-testid="person-due-total">{{ (dueBy().get(p.id) ?? 'n/a') | fig }}</td>
              }
              <td data-testid="person-settled-total">{{ (splitBy().get(p.id)?.settled ?? '0.00') | fig }}</td>
              <td data-testid="person-open-total">{{ (splitBy().get(p.id)?.open ?? '0.00') | fig }}</td>
              @if (columns.shown('overdue-count')) {
                <td data-testid="person-overdue-count">{{ (overdueBy().get(p.id)?.length ?? 0) | fig }}</td>
              }
              @if (columns.shown('overdue-total')) {
                <td data-testid="person-overdue-total">{{ (overdueTotalBy().get(p.id) ?? '0.00') | fig }}</td>
              }
              <td data-testid="person-aged-0-30">{{ (agedBy().get(p.id)?.[0] ?? '0.00') | fig }}</td>
              <td data-testid="person-aged-31-60">{{ (agedBy().get(p.id)?.[1] ?? '0.00') | fig }}</td>
              <td data-testid="person-aged-61">{{ (agedBy().get(p.id)?.[2] ?? '0.00') | fig }}</td>
              @if (columns.shown('statement-count')) {
                <td data-testid="person-statement-count">{{ (statementsBy().get(p.id)?.count ?? 0) | fig }}</td>
              }
              @if (columns.shown('stated-total')) {
                <td data-testid="person-stated-total">{{ statedTotal(statementsBy().get(p.id)) | fig }}</td>
              }
              @if (columns.shown('statement-date')) {
                <td data-testid="person-statement-date">{{ (statementsBy().get(p.id)?.date ?? '') | fig }}</td>
              }
              @if (columns.shown('retired')) {
                <td data-testid="person-retired">{{ (p.retiredFrom !== null ? 'yes' : 'no') | fig }}</td>
              }
              <td data-testid="person-retired-from">{{ (p.retiredFrom ?? '') | fig }}</td>
              @if (columns.shown('hold')) {
                <td data-testid="person-hold">{{ (p.held ? 'yes' : 'no') | fig }}</td>
              }
              <td data-testid="person-active">{{ (activeIds().has(p.id) ? 'yes' : 'no') | fig }}</td>
              @if (columns.shown('patrons')) {
                <td data-testid="person-patrons">{{ (patronNamesBy().get(p.id) ?? '') | fig }}</td>
              }
              @if (columns.shown('backing')) {
                <td data-testid="person-backing">{{ (backingBy().get(p.id) ?? '0.00') | fig }}</td>
              }
              @if (columns.shown('excess')) {
                <td data-testid="person-excess">{{ (excessBy().get(p.id) ?? '0.00') | fig }}</td>
              }
              <td data-testid="person-watched">{{ (watched().has(p.id) ? 'yes' : 'no') | fig }}</td>
              @for (pd of periods.list(); track pd.id) {
                <td [attr.data-testid]="'person-period-' + pd.name + '-due-total'">
                  {{ (periodDueBy().get(p.id)?.get(pd.id) ?? '0.00') | fig }}
                </td>
                <td [attr.data-testid]="'person-period-' + pd.name + '-carried'">
                  {{ (periodCarriedBy().get(p.id)?.get(pd.id) ?? '0.00') | fig }}
                </td>
              }
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button
                  type="button"
                  data-testid="person-watch"
                  [attr.aria-pressed]="watched().has(p.id)"
                  (click)="toggleWatch(p)"
                >
                  Watch<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <label [for]="'statement-asof-' + p.id">Statement as of<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  type="date"
                  [id]="'statement-asof-' + p.id"
                  data-testid="person-statement-asof"
                  [value]="statementAsOfs()[p.id] ?? ''"
                  (input)="setStatementAsOf(p.id, value($event))"
                />
                <button type="button" data-testid="person-statement" (click)="takeStatement(p)">
                  Statement<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-unstate" (click)="cancelStatement(p)">
                  Cancel statement<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                <label [for]="'retire-date-' + p.id">Retire from<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  type="date"
                  [id]="'retire-date-' + p.id"
                  data-testid="person-retire-date"
                  [value]="retireDates()[p.id] ?? ''"
                  (input)="setRetireDate(p.id, value($event))"
                />
                <label [for]="'inline-grade-' + p.id">Grade<span class="visually-hidden"> for {{ p.name }}</span></label>
                <select
                  [id]="'inline-grade-' + p.id"
                  data-testid="person-inline-grade"
                  (change)="setInlineGrade(p.id, value($event))"
                >
                  @for (g of grades; track g) {
                    <option [value]="g" [selected]="g === (inlineGrades()[p.id] ?? gradeToday(p))">{{ g }}</option>
                  }
                </select>
                <button type="button" data-testid="person-inline-grade-save" (click)="saveInlineGrade(p)">
                  Save grade<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-move-up" (click)="move(p, 'up')">
                  Move up<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-move-down" (click)="move(p, 'down')">
                  Move down<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-retire" (click)="retire(p)">
                  Retire<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button type="button" data-testid="person-unretire" (click)="unretire(p)">
                  Unretire<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                @if (p.pastRetirements.length > 0) {
                  <ul aria-label="Past retirements">
                    @for (r of p.pastRetirements; track $index) {
                      <li data-testid="person-retirement-line">{{ r.from }} to {{ r.to }}</li>
                    }
                  </ul>
                }
                @if (statementsBy().get(p.id)?.lines?.length) {
                  <ul aria-label="Statements">
                    @for (l of statementsBy().get(p.id)!.lines; track $index) {
                      <li data-testid="person-statement-line-line">{{ l.date }} {{ l.dueTotal ?? 'n/a' }}</li>
                    }
                  </ul>
                }
                @if (p.snapshots.length > 0) {
                  <ul aria-label="Snapshots">
                    @for (s of p.snapshots; track $index) {
                      <li data-testid="person-snapshot-line">{{ s.day }} {{ s.dueTotal ?? 'n/a' }}</li>
                    }
                  </ul>
                }
                <label [for]="'hold-from-' + p.id">Hold from<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  type="date"
                  [id]="'hold-from-' + p.id"
                  data-testid="person-hold-from"
                  [value]="holdFroms()[p.id] ?? ''"
                  (input)="setHoldFrom(p.id, value($event))"
                />
                <label [for]="'hold-to-' + p.id">Hold to<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  type="date"
                  [id]="'hold-to-' + p.id"
                  data-testid="person-hold-to"
                  [value]="holdTos()[p.id] ?? ''"
                  (input)="setHoldTo(p.id, value($event))"
                />
                <button type="button" data-testid="person-hold-toggle" (click)="hold(p)">
                  Hold<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                @if (p.holds.length > 0) {
                  <ul aria-label="Holds">
                    @for (h of p.holds; track $index) {
                      <li data-testid="person-hold-line">{{ h.from }} to {{ h.to ?? 'no end' }}</li>
                    }
                  </ul>
                }
                <label [for]="'alias-' + p.id">Alias<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  type="text"
                  [id]="'alias-' + p.id"
                  data-testid="person-alias-input"
                  [value]="aliasInputs()[p.id] ?? ''"
                  (input)="setAliasInput(p.id, value($event))"
                />
                <button type="button" data-testid="person-alias-add" (click)="addAlias(p)">
                  Add alias<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                @if (rowErrors()[p.id]; as err) {
                  <p role="alert" data-testid="person-error">{{ err | fig }}</p>
                }
                <label [for]="'patron-' + p.id">Patron<span class="visually-hidden"> for {{ p.name }}</span></label>
                <select
                  [id]="'patron-' + p.id"
                  data-testid="person-patron-select"
                  (change)="setPatronChoice(p.id, value($event))"
                >
                  <option value="" [selected]="!patronChoices()[p.id]">Choose a person</option>
                  @for (q of people(); track q.id) {
                    @if (q.id !== p.id) {
                      <option [value]="q.id" [selected]="patronChoices()[p.id] === '' + q.id">{{ q.name }}</option>
                    }
                  }
                </select>
                <button type="button" data-testid="person-patron-add" (click)="addPatron(p)">
                  Add patron<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                @if (patronErrors()[p.id]; as err) {
                  <p role="alert" data-testid="person-patron-error">{{ err | fig }}</p>
                }
                <label [for]="'merge-into-' + p.id">Merge into<span class="visually-hidden"> for {{ p.name }}</span></label>
                <select
                  [id]="'merge-into-' + p.id"
                  data-testid="person-merge-into"
                  (change)="setMergeChoice(p.id, value($event))"
                >
                  <option value="" [selected]="!mergeChoices()[p.id]">Choose a person</option>
                  @for (q of people(); track q.id) {
                    <option [value]="q.id" [selected]="mergeChoices()[p.id] === '' + q.id">{{ q.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-merge" (click)="merge(p)">
                  Merge<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <label [for]="'transfer-to-' + p.id">Transfer to sheet<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  type="text"
                  [id]="'transfer-to-' + p.id"
                  data-testid="person-transfer-to"
                  [value]="transferTargets()[p.id] ?? ''"
                  (input)="setTransferTarget(p.id, value($event))"
                />
                <button type="button" data-testid="person-transfer" (click)="transfer(p)">
                  Transfer<span class="visually-hidden"> {{ p.name }}</span>
                </button>
              </td>
              <td>
                <app-person-plans [personId]="p.id" [personName]="p.name" />
              </td>
              <td>
                <app-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsBy().get(p.id) ?? []"
                  [sheetClaims]="claims()"
                  [grades]="p.grades"
                  [retiredFrom]="p.retiredFrom"
                  [pastRetirements]="p.pastRetirements"
                  [people]="people()"
                  [selected]="selectedClaims()"
                  (selectedChange)="selectClaim($event.id, $event.checked)"
                  (saved)="claimSaved($event)"
                />
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class People {
  private readonly http = inject(HttpClient);
  private readonly document = inject(DOCUMENT);
  protected readonly session = inject(Session);
  protected readonly rates = inject(RateStore);
  private readonly settings = inject(SettingsStore);
  protected readonly periods = inject(PeriodStore);
  private readonly poolRoster = inject(PoolRoster);
  private readonly crewRoster = inject(CrewRoster);
  protected readonly poolStore = inject(PoolStore);
  private readonly planStore = inject(PlanStore);

  protected readonly columns = inject(UserColumns);
  protected readonly columnChoices = COLUMNS;

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  // Retired people are left out of the list unless this is on; it belongs to the screen, so loading a sheet keeps it.
  protected readonly showRetired = signal(false);
  // The order the list is shown in; it belongs to the screen (each user's own), so loading a sheet keeps it.
  // Only the screen is sorted: positions, rollups and every action in list order are unaffected.
  protected readonly sortKey = signal('added');
  protected readonly shownPeople = computed(() => {
    const shown = this.showRetired() ? this.people() : this.people().filter((p) => p.retiredFrom === null);
    const key = this.sortKey();
    if (key === 'added') return shown;
    const due = this.dueBy();
    const sortValue = (p: Person): string | number | null => {
      if (key === 'name') return p.name.toLowerCase();
      if (key === 'grade') return this.gradeToday(p);
      const d = due.get(p.id);
      return d == null ? null : Number(d);
    };
    const keyed = shown.map((p) => ({ p, v: sortValue(p) }));
    // Array.prototype.sort is stable, so equal keys keep list order; n/a goes last.
    keyed.sort((a, b) => {
      if (a.v === null || b.v === null) return a.v === null ? (b.v === null ? 0 : 1) : -1;
      return a.v < b.v ? -1 : a.v > b.v ? 1 : 0;
    });
    return keyed.map((k) => k.p);
  });
  // Each row's retire-from date as typed, by person id; blank takes the sheet's today.
  protected readonly retireDates = signal<Record<number, string>>({});
  /** The grade chosen in each row's grade select, until saved. */
  protected readonly inlineGrades = signal<Record<number, string>>({});
  // The date typed beside each row's statement button, by person id: blank takes the statement now.
  protected readonly statementAsOfs = signal<Record<number, string>>({});
  // Each row's hold from- and to-dates as typed, by person id; a blank from-date takes the sheet's today, a blank
  // to-date puts a hold with no end.
  protected readonly holdFroms = signal<Record<number, string>>({});
  protected readonly holdTos = signal<Record<number, string>>({});
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  // The date the form's grade applies from; blank takes the sheet's today.
  protected readonly gradeFrom = signal('');
  // The person's own terms in whole days as typed; blank for none.
  protected readonly terms = signal('');
  // The date the terms apply from; blank for the beginning of time.
  protected readonly termsFrom = signal('');
  // The person's contact as typed; blank for none.
  protected readonly contact = signal('');
  // The person's tags as typed, comma separated; blank for none.
  protected readonly tags = signal('');
  protected readonly editingId = signal<number | null>(null);
  // The version of the person the open editor loaded.
  private readonly editingVersion = signal(0);
  // A submit in flight: a second submit before it answers is ignored, so it stores one record.
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  // The quick-add bar: a name and an amount make a draft claim, adding the person when the name is new.
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal<string | null>(null);
  private quickAdding = false;
  // The paste box: lines of name,amount,date,note open a claim each, all or none; and how many went through.
  protected readonly pasteText = signal('');
  protected readonly pasteAdded = signal<number | null>(null);
  protected readonly pasteError = signal<string | null>(null);
  private pasting = false;
  // How many claims the last generate made, or null while none has answered since it was pressed.
  protected readonly plansGenerated = signal<number | null>(null);
  protected readonly planGenerateError = signal<string | null>(null);
  private generating = false;
  // The import box: lines of name,grade,terms,contact add a person each, all or none; and how many it added.
  protected readonly importText = signal('');
  protected readonly importAdded = signal<number | null>(null);
  protected readonly importUpdated = signal(0);
  protected readonly importError = signal<string | null>(null);
  private importing = false;
  // Each person's refused row action, by person id.
  protected readonly rowErrors = signal<Record<number, string>>({});
  // Every patron named on the sheet, the patron chosen on each row (a person id, by person id) and each row's
  // refused patron.
  protected readonly patrons = signal<PatronView[]>([]);
  // The ids of the people the signed-in user watches on the sheet.
  protected readonly watched = signal<ReadonlySet<number>>(new Set());
  protected readonly patronChoices = signal<Record<number, string>>({});
  // The alias typed on each row, by person id.
  protected readonly aliasInputs = signal<Record<number, string>>({});
  protected readonly patronErrors = signal<Record<number, string>>({});
  // The person chosen on each row to merge that row's person into (a person id, by person id).
  protected readonly mergeChoices = signal<Record<number, string>>({});
  // The sheet id typed on each row to transfer that row's person to, by person id.
  protected readonly transferTargets = signal<Record<number, string>>({});
  // Each person's statements, by person id; a person with none is not in the map.
  protected readonly statements = signal<StatementView[]>([]);
  protected readonly statementsBy = computed(() => new Map(this.statements().map((s) => [s.personId, s])));
  // Every claim on the sheet, newest first.
  protected readonly claims = signal<Claim[]>([]);
  protected readonly claimsBy = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  // Every tag ever used on a claim or person of the sheet, as the store last told it.
  protected readonly usedTags = signal<string[]>([]);
  // For every tag ever used on the sheet (those the store keeps and those on the claims and people now), sorted: the
  // due of the claims counted live that carry it, or whose person carries it, once each, skipping n/a; a claim with
  // two tags counts in both.
  protected readonly tagDueTotals = computed(() => {
    const all = new Set([
      ...this.usedTags(),
      ...this.claims().flatMap((c) => c.tags),
      ...this.people().flatMap((p) => p.tags),
    ]);
    const personTags = new Map(this.people().map((p) => [p.id, p.tags]));
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const rates = this.rates.byClaim();
    const floor = this.settings.floor();
    const late = this.settings.lateness();
    const dues = new Map<string, (string | null)[]>();
    for (const c of this.liveClaims()) {
      const carried = new Set([...c.tags, ...(personTags.get(c.personId) ?? [])]);
      if (carried.size === 0) continue;
      const own = grades.get(c.personId) ?? [];
      const due = dueOf(c, rates(c).get(gradeOn(own, c.date)) ?? [], floor, late, graceOf(c, own, late));
      for (const t of carried) dues.set(t, [...(dues.get(t) ?? []), due]);
    }
    return [...all]
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
      .map((tag) => ({ tag, dueTotal: sumSkippingNa(dues.get(tag) ?? []) }));
  });
  // For every month (yyyy-mm) any claim on the sheet is dated in, sorted: the due of the claims counted live dated
  // in it, skipping n/a.
  protected readonly monthDueTotals = computed(() => {
    const all = new Set(this.claims().map((c) => c.date.slice(0, 7)));
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const rates = this.rates.byClaim();
    const floor = this.settings.floor();
    const late = this.settings.lateness();
    const dues = new Map<string, (string | null)[]>();
    for (const c of this.liveClaims()) {
      const own = grades.get(c.personId) ?? [];
      const month = c.date.slice(0, 7);
      dues.set(month, [...(dues.get(month) ?? []), dueOf(c, rates(c).get(gradeOn(own, c.date)) ?? [], floor, late, graceOf(c, own, late))]);
    }
    return [...all]
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
      .map((month) => ({ month, dueTotal: sumSkippingNa(dues.get(month) ?? []) }));
  });
  // The claims selected for a bulk open, by id, and the last bulk open's counts (none while one runs).
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  protected readonly bulkOpened = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkError = signal<string | null>(null);
  private bulkOpening = false;
  // The last bulk settle's counts (none while one runs); a claim made PENDING counts as settled.
  protected readonly bulkSettled = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal<string | null>(null);
  private bulkSettling = false;
  // The person a bulk move takes the selected claims to, by id ('' for none chosen), and its last counts.
  protected readonly bulkReassignTo = signal('');
  protected readonly bulkReassigned = signal<{ reassigned: number; refused: number } | null>(null);
  protected readonly bulkReassignError = signal<string | null>(null);
  private bulkReassigning = false;
  // The date a bulk re-date gives the selected claims, as typed, and its last counts (none while one runs).
  protected readonly bulkRedateDate = signal('');
  protected readonly bulkRedated = signal<{ redated: number; refused: number } | null>(null);
  protected readonly bulkRedateError = signal<string | null>(null);
  private bulkRedating = false;
  // The people selected for a bulk grade, by id; the grade and from-date (blank: the sheet's today) it gives them,
  // and how many the last one changed (none while one runs).
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  protected readonly bulkGrade = signal('A');
  protected readonly bulkGradeFrom = signal('');
  protected readonly bulkGraded = signal<number | null>(null);
  protected readonly bulkGradeError = signal<string | null>(null);
  // The bulk retire's from-date as typed (blank: the sheet's today) and the last one's count (none while one runs).
  protected readonly bulkRetireFrom = signal('');
  protected readonly bulkRetired = signal<number | null>(null);
  protected readonly bulkRetireError = signal<string | null>(null);
  private bulkRetiring = false;
  private bulkGrading = false;
  // The claims that count in the live totals: open, pending and settled ones, leaving out the open and pending ones
  // dated within one of their person's holds, or whose person's retirement date today has reached, that no statement
  // binds, and the deferred ones.
  protected readonly liveClaims = computed(() => {
    const leftOut = this.leftOut();
    return this.claims().filter((c) => counts(c) && !leftOut(c));
  });
  // Whether the live rules leave the claim out whatever its status: deferred, or open, bound by no statement and
  // dated within one of its person's holds or with its person's retirement date reached today.
  private readonly leftOut = computed(() => {
    const today = this.session.sheetToday();
    const holds = new Map(this.people().map((p) => [p.id, p.holds]));
    const retiredFrom = new Map(this.people().map((p) => [p.id, p.retiredFrom]));
    // a person whose retirement date the sheet's today has reached holds every claim, as a hold would
    const held = (c: Claim) => {
      const from = retiredFrom.get(c.personId) ?? null;
      return (from !== null && today !== null && today >= from)
        || (holds.get(c.personId) ?? []).some((h) => h.from <= c.date && (h.to === null || c.date <= h.to));
    };
    return (c: Claim) => deferred(c, today) || (isOpen(c) && !isBound(c) && held(c));
  });
  protected readonly liveBy = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.liveClaims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  protected readonly claimedBy = computed(() => {
    const by = new Map<number, string>();
    for (const [id, list] of this.liveBy()) by.set(id, claimedTotal(list));
    return by;
  });
  protected readonly claimedTotal = computed(() => claimedTotal(this.liveClaims()));
  // Each person's due-total: the sum of their counted claims' figures, each settled one's frozen figure or
  // the one a statement bound it to, or its live due, skipping n/a. With no rate in force today for their grade
  // today nor for the grade of any counted claim's date it is null (n/a), unless a counted claim has a figure (a
  // settled or stated one, or one dated within a rate that has since ended). This is before the sheet's cap.
  private readonly uncappedDueBy = computed(() => {
    const by = new Map<number, string | null>();
    const current = this.rates.current();
    const rates = this.rates.byClaim();
    const floor = this.settings.floor();
    const late = this.settings.lateness();
    for (const p of this.people()) {
      const counted = this.liveBy().get(p.id) ?? [];
      // the grades whose rate today decides n/a: the one in force today and each counted claim's
      const grades = [this.gradeToday(p), ...counted.map((c) => gradeOn(p.grades, c.date))];
      const dues = counted.map((c) => dueOf(c, rates(c).get(gradeOn(p.grades, c.date)) ?? [], floor, late, graceOf(c, p.grades, late)));
      if (!grades.some((g) => current.has(g)) && dues.every((d) => d === null)) {
        by.set(p.id, null);
        continue;
      }
      by.set(p.id, sumSkippingNa(dues));
    }
    return by;
  });
  // Each person's settled-total and open-total: the figures of their counted settled claims (frozen, corrections
  // included) and of their counted open and pending claims, each skipping n/a. Together they make the due-total
  // before the cap.
  protected readonly splitBy = computed(() => {
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const rates = this.rates.byClaim();
    const floor = this.settings.floor();
    const late = this.settings.lateness();
    const by = new Map<number, { settled: string; open: string }>();
    for (const [id, list] of this.liveBy()) {
      const own = grades.get(id) ?? [];
      const due = (c: Claim) => dueOf(c, rates(c).get(gradeOn(own, c.date)) ?? [], floor, late, graceOf(c, own, late));
      by.set(id, {
        settled: sumSkippingNa(list.filter((c) => c.status === 'SETTLED').map(due)),
        open: sumSkippingNa(list.filter(isOpen).map(due)),
      });
    }
    return by;
  });
  // The sheet's settled-total and open-total: everyone's figure added up, not trimmed.
  protected readonly settledTotal = computed(() => sumSkippingNa([...this.splitBy().values()].map((s) => s.settled)));
  protected readonly openTotal = computed(() => sumSkippingNa([...this.splitBy().values()].map((s) => s.open)));
  // Each person's due-total held to the sheet's cap; everything that adds up due-totals uses it. n/a is not capped.
  protected readonly dueBy = computed(() => {
    const cap = this.settings.cap();
    const by = new Map<number, string | null>();
    for (const [id, due] of this.uncappedDueBy()) by.set(id, atMost(due, cap));
    return by;
  });
  // Each person's excess: what their due-total is above the sheet's cap, else 0.00.
  protected readonly excessBy = computed(() => {
    const cap = this.settings.cap();
    const by = new Map<number, string>();
    for (const [id, due] of this.uncappedDueBy()) by.set(id, excessOver(due, cap));
    return by;
  });
  // Every claim the live rules do not leave out: its person, its due-by, the figure it totals and its log, for the
  // periods' due-totals, which count it by its status at each period's end.
  protected readonly countedFigures = computed<DatedFigure[]>(() => {
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const rates = this.rates.byClaim();
    const floor = this.settings.floor();
    const late = this.settings.lateness();
    const leftOut = this.leftOut();
    return this.claims()
      .filter((c) => !leftOut(c))
      .map((c) => {
        const grade = gradeOn(grades.get(c.personId) ?? [], c.date);
        return {
          personId: c.personId,
          dueBy: c.dueBy,
          figure: dueOf(c, rates(c).get(grade) ?? [], floor, late, late.grace(grade, c.date)),
          status: c.status,
          log: c.log,
        };
      });
  });
  // Each person's due-total for each period, by person id then period id: a closed period's figure fixed for them
  // when it closed, else their counted claims' figures due by a date in it, skipping n/a.
  protected readonly periodDueBy = computed(() => {
    const periods = this.periods.list();
    const figures = this.countedFigures();
    return new Map(this.people().map((p) => [p.id, personPeriodDueTotals(periods, p.id, figures)]));
  });
  // Each person's carried figure for each period, by person id then period id: their counted claims belonging to an
  // earlier period that were still open or pending at its end.
  protected readonly periodCarriedBy = computed(() => {
    const periods = this.periods.list();
    const figures = this.countedFigures();
    return new Map(this.people().map((p) => [p.id, personPeriodCarried(periods, p.id, figures)]));
  });
  // The people who are active: with an open or pending counted claim dated within the 30 days up to today.
  protected readonly activeIds = computed(() => {
    const today = this.session.sheetToday();
    return new Set(this.liveClaims().filter((c) => activeOn(c, today)).map((c) => c.personId));
  });
  // Each person as the summary counts them: grade today, due-total, whether retired and whether active.
  protected readonly summaryPeople = computed<SummaryPerson[]>(() => {
    const due = this.dueBy();
    const active = this.activeIds();
    return this.people().map((p) => ({
      grade: this.gradeToday(p),
      dueTotal: due.get(p.id) ?? null,
      retired: p.retiredFrom !== null,
      active: active.has(p.id),
    }));
  });
  protected readonly claimStatuses = computed(() => this.claims().map((c) => c.status));
  // The REVIEW claims late against the review SLA.
  protected readonly reviewLateCount = computed(() => {
    const today = this.session.sheetToday();
    const sla = this.settings.reviewSla();
    return this.claims().filter((c) => reviewLate(c, today, sla)).length;
  });
  protected readonly voidedWith = computed(() =>
    this.claims()
      .filter((c) => c.status === 'VOID')
      .map((c) => c.voidReason),
  );
  // Each person's patron ids, in the order they were named; a person with none is not in the map.
  private readonly patronIdsBy = computed(() => {
    const lists = new Map<number, number[]>();
    for (const r of this.patrons()) lists.set(r.personId, [...(lists.get(r.personId) ?? []), r.patronId]);
    return lists;
  });
  // Each person's patrons with their shares: 100.00 split equally in the order they were named, the remainder to
  // the first; a person with none is not in the map.
  private readonly patronSharesBy = computed(() => {
    const by = new Map<number, { patronId: number; share: string }[]>();
    for (const [id, list] of this.patronIdsBy()) {
      const shares = equalShares(list.length);
      by.set(id, list.map((patronId, i) => ({ patronId, share: shares[i] })));
    }
    return by;
  });
  // Each person's patrons as "<name> <share>", sorted by name and comma separated; a person with none is not in
  // the map.
  protected readonly patronNamesBy = computed(() => {
    const names = new Map(this.people().map((p) => [p.id, p.name]));
    const by = new Map<number, string>();
    for (const [id, list] of this.patronSharesBy()) {
      const named = list
        .filter((r) => names.has(r.patronId))
        .map((r) => ({ name: names.get(r.patronId)!, share: r.share }))
        .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
      by.set(id, named.map((r) => `${r.name} ${r.share}`).join(', '));
    }
    return by;
  });
  // Each person's backing: over their patrons, the patron's figure (their settled claims dated within the 90 days
  // up to and including the sheet's today, each at its frozen figure, added up skipping n/a) times their share
  // divided by 100, added up and rounded half to even; a person with no patrons is not in the map.
  protected readonly backingBy = computed(() => {
    const today = this.session.sheetToday();
    const settled = new Map<number, (string | null)[]>();
    for (const c of this.claims()) {
      if (c.status !== 'SETTLED' || today === null || c.date < plusDays(today, -89) || c.date > today) continue;
      settled.set(c.personId, [...(settled.get(c.personId) ?? []), c.settledDue]);
    }
    const by = new Map<number, string>();
    for (const [id, list] of this.patronSharesBy()) {
      by.set(id, sharedSum(list.map((r) => ({ figure: sumSkippingNa(settled.get(r.patronId) ?? []), share: r.share }))));
    }
    return by;
  });
  // The sheet's due-total: trimmed of the highest and lowest person's figure when three or more have one.
  protected readonly dueTotal = computed(() => trimmedSum([...this.dueBy().values()]));
  // The claims counted live that are overdue: open and due by a date (plus its grace) before the sheet's today.
  protected readonly overdueClaims = computed(() => {
    const today = this.session.sheetToday();
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const late = this.settings.lateness();
    return this.liveClaims().filter((c) => overdue(c, today, graceOf(c, grades.get(c.personId) ?? [], late)));
  });
  protected readonly overdueBy = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.overdueClaims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  // Each person's overdue-total: the sum of their overdue claims' figures, skipping n/a.
  protected readonly overdueTotalBy = computed(() => {
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const rates = this.rates.byClaim();
    const floor = this.settings.floor();
    const late = this.settings.lateness();
    const by = new Map<number, string>();
    for (const [id, list] of this.overdueBy()) {
      const own = grades.get(id) ?? [];
      by.set(id, sumSkippingNa(list.map((c) => dueOf(c, rates(c).get(gradeOn(own, c.date)) ?? [], floor, late, graceOf(c, own, late)))));
    }
    return by;
  });
  // Each person's due of their counted open and pending claims, split by the claim's age on the sheet's today (the
  // days from its date): 0 to 30, 31 to 60, and 61 or more; one dated after today is 0 to 30. Each skips n/a.
  protected readonly agedBy = computed(() => {
    const today = this.session.sheetToday();
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const rates = this.rates.byClaim();
    const floor = this.settings.floor();
    const late = this.settings.lateness();
    const by = new Map<number, string[]>();
    if (today === null) return by;
    const from30 = plusDays(today, -30);
    const from60 = plusDays(today, -60);
    for (const [id, list] of this.liveBy()) {
      const own = grades.get(id) ?? [];
      const buckets: (string | null)[][] = [[], [], []];
      for (const c of list.filter(isOpen)) {
        const due = dueOf(c, rates(c).get(gradeOn(own, c.date)) ?? [], floor, late, graceOf(c, own, late));
        buckets[c.date >= from30 ? 0 : c.date >= from60 ? 1 : 2].push(due);
      }
      by.set(id, buckets.map(sumSkippingNa));
    }
    return by;
  });
  // The sheet's overdue-total: every person has a figure (0.00 with nothing overdue), trimmed like the due-total.
  protected readonly overdueTotal = computed(() => {
    const by = this.overdueTotalBy();
    return trimmedSum(this.people().map((p) => by.get(p.id) ?? '0.00'));
  });

  // The signed-in user's own figures: those of the person whose name or an alias is, ignoring case, the user's name,
  // shown as on their row; null when there is no such person.
  private readonly me = computed(() => {
    const user = this.session.user()?.toLowerCase();
    if (!user) return undefined;
    return this.people().find(
      (p) => p.name.toLowerCase() === user || p.aliases.some((a) => a.toLowerCase() === user),
    );
  });
  protected readonly myFigures = computed(() => {
    const me = this.me();
    if (me === undefined) return null;
    return {
      dueTotal: this.dueBy().get(me.id) ?? 'n/a',
      overdueTotal: this.overdueTotalBy().get(me.id) ?? '0.00',
    };
  });
  // The signed-in user's crew total: the due-totals of the crews their person leads, added up; blank when they lead
  // none, or there is no such person.
  protected readonly myCrewDueTotal = computed(() => {
    const me = this.me();
    if (me === undefined) return '';
    const led = this.crewRoster.crews().filter((c) => c.leadId === me.id);
    if (led.length === 0) return '';
    const due = this.dueBy();
    return sumSkippingNa(led.map((c) => crewDueTotal(c, due)));
  });

  constructor() {
    // The pools offer these people, and count a member retired, or held, as soon as they are.
    effect(() => {
      const list = this.people().map((p) => ({ id: p.id, name: p.name, retiredFrom: p.retiredFrom, holds: p.holds }));
      untracked(() => this.poolRoster.people.set(list));
    });
    // The crews offer these people, and add up their due-totals as the list shows them.
    effect(() => {
      const list = this.people().map((p) => ({ id: p.id, name: p.name }));
      const due = this.dueBy();
      untracked(() => {
        this.crewRoster.people.set(list);
        this.crewRoster.dueBy.set(due);
      });
    });
    // Re-read the people from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.people.set([]);
        this.claims.set([]);
        this.usedTags.set([]);
        this.statements.set([]);
        this.patrons.set([]);
        this.watched.set(new Set());
        this.patronChoices.set({});
        this.aliasInputs.set({});
        this.patronErrors.set({});
        this.mergeChoices.set({});
        this.transferTargets.set({});
        this.rowErrors.set({});
        this.retireDates.set({});
        this.inlineGrades.set({});
        this.statementAsOfs.set({});
        this.holdFroms.set({});
        this.holdTos.set({});
        this.reset();
        this.quickName.set('');
        this.quickAmount.set('');
        this.quickError.set(null);
        this.pasteText.set('');
        this.pasteAdded.set(null);
        this.pasteError.set(null);
        this.planStore.list.set([]);
        this.plansGenerated.set(null);
        this.planGenerateError.set(null);
        this.importText.set('');
        this.importAdded.set(null);
        this.importError.set(null);
        this.selectedPeople.set(new Set());
        this.bulkGrade.set('A');
        this.bulkGradeFrom.set('');
        this.bulkGraded.set(null);
        this.bulkGradeError.set(null);
        this.bulkRetireFrom.set('');
        this.bulkRetired.set(null);
        this.bulkRetireError.set(null);
        this.bulkReassignTo.set('');
        this.bulkReassigned.set(null);
        this.bulkReassignError.set(null);
        this.bulkRedateDate.set('');
        this.bulkRedated.set(null);
        this.bulkRedateError.set(null);
        if (id) void this.fetch(id);
      });
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  protected setRetireDate(id: number, date: string): void {
    this.retireDates.update((d) => ({ ...d, [id]: date }));
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected setStatementAsOf(id: number, date: string): void {
    this.statementAsOfs.update((d) => ({ ...d, [id]: date }));
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /** Retires the person from the typed date, or the sheet's today when blank. Their claims keep counting. */
  protected async retire(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const from = (this.retireDates()[p.id] ?? '').trim();
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Enter a retirement date as yyyy-mm-dd.' }));
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/retire`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { from: from || null }));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((q) => (q.id === saved.id ? saved : q)));
      this.retireDates.update(({ [p.id]: _, ...rest }) => rest);
      if (this.editingId() === saved.id) this.editingVersion.set(saved.version);
    } catch {
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Could not retire this person.' }));
    }
  }

  protected setInlineGrade(id: number, grade: string): void {
    this.inlineGrades.update((g) => ({ ...g, [id]: grade }));
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /**
   * Records the grade chosen on the row from the sheet's today, exactly as saving the person's edit with that grade
   * would; refused when the person has been saved since the row was read.
   */
  protected async saveInlineGrade(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const grade = this.inlineGrades()[p.id] ?? this.gradeToday(p);
    if (!GRADES.includes(grade)) {
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Choose a grade of A, B or C.' }));
      return;
    }
    const edit = {
      name: p.name,
      grade,
      gradeFrom: null,
      version: p.version,
      terms: p.terms,
      contact: p.contact,
      tags: null,
    };
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}`;
    try {
      const saved = await firstValueFrom(this.http.put<Person>(url, edit));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((q) => (q.id === saved.id ? saved : q)));
      this.inlineGrades.update(({ [p.id]: _, ...rest }) => rest);
      if (this.editingId() === saved.id) this.editingVersion.set(saved.version);
    } catch (err) {
      const stale = err instanceof HttpErrorResponse && err.status === 412;
      this.rowErrors.update((e) => ({
        ...e,
        [p.id]: stale ? 'Someone else has saved this person since; reload the sheet.' : 'Could not save this grade.',
      }));
    }
  }

  /** Unretires a retired person; their retirement is kept on the row, to the sheet's today. */
  protected async unretire(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (p.retiredFrom === null) {
      this.rowErrors.update((e) => ({ ...e, [p.id]: `${p.name} is not retired.` }));
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/unretire`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, {}));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((q) => (q.id === saved.id ? saved : q)));
      if (this.editingId() === saved.id) this.editingVersion.set(saved.version);
    } catch {
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Could not unretire this person.' }));
    }
  }

  /**
   * Swaps the person with the one above or below them in the list; the top person cannot move up, nor the bottom one
   * down. The list comes back in its new order.
   */
  protected async move(p: Person, direction: 'up' | 'down'): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/move`;
    try {
      const list = await firstValueFrom(this.http.post<Person[]>(url, { direction }));
      if (this.session.sheetId() !== sheet) return;
      this.people.set(list);
    } catch (e) {
      const atEnd = e instanceof HttpErrorResponse && e.status === 409;
      const message = !atEnd
        ? 'Could not move this person.'
        : direction === 'up'
          ? 'The top person cannot move up.'
          : 'The bottom person cannot move down.';
      this.rowErrors.update((errors) => ({ ...errors, [p.id]: message }));
    }
  }

  /** Clears the row's refused actions: taking the next action on a row removes the last one's error. */
  private clearRowErrors(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
    this.patronErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected setPatronChoice(id: number, patronId: string): void {
    this.patronChoices.update((c) => ({ ...c, [id]: patronId }));
    this.patronErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /** Names the chosen person as the person's patron: never themselves, nor anyone already named. */
  protected async addPatron(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const patronId = Number(this.patronChoices()[p.id] ?? '');
    const refuse = (message: string) => this.patronErrors.update((e) => ({ ...e, [p.id]: message }));
    if (!patronId) {
      refuse('Choose a person to add as a patron.');
      return;
    }
    if (patronId === p.id) {
      refuse('A person cannot be their own patron.');
      return;
    }
    if (this.patrons().some((r) => r.personId === p.id && r.patronId === patronId)) {
      refuse('That person is already one of their patrons.');
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/patrons`;
    try {
      const added = await firstValueFrom(this.http.post<PatronView>(url, { patronId }));
      if (this.session.sheetId() !== sheet) return;
      this.patrons.update((list) => [...list, added]);
      this.patronChoices.update(({ [p.id]: _, ...rest }) => rest);
    } catch (err) {
      refuse(
        err instanceof HttpErrorResponse && err.status === 409
          ? 'That person is already one of their patrons.'
          : 'Could not add that patron.',
      );
    }
  }

  protected setMergeChoice(id: number, intoId: string): void {
    this.mergeChoices.update((c) => ({ ...c, [id]: intoId }));
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /**
   * A steward merges the person into the chosen one, who takes their claims, names, patrons and pool memberships;
   * the merged person leaves the sheet. Never into themselves, nor when either is retired.
   */
  protected async merge(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const intoId = Number(this.mergeChoices()[p.id] ?? '');
    const refuse = (message: string) => this.rowErrors.update((e) => ({ ...e, [p.id]: message }));
    if (!intoId) {
      refuse('Choose a person to merge into.');
      return;
    }
    if (intoId === p.id) {
      refuse('A person cannot be merged into themselves.');
      return;
    }
    const into = this.people().find((q) => q.id === intoId);
    if (p.retiredFrom !== null || (into !== undefined && into.retiredFrom !== null)) {
      refuse('A retired person cannot be merged.');
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/merge`;
    try {
      await firstValueFrom(this.http.post<Person>(url, { intoId }));
    } catch (err) {
      refuse(
        err instanceof HttpErrorResponse && err.status === 403
          ? 'Only a steward can merge people.'
          : 'Could not merge this person.',
      );
      return;
    }
    // the merge moves claims, patrons and pool memberships: every view re-reads the sheet
    if (this.session.sheetId() === sheet) await this.session.loadSheet(sheet);
  }

  protected setTransferTarget(id: number, sheetId: string): void {
    this.transferTargets.update((t) => ({ ...t, [id]: sheetId }));
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /**
   * A steward transfers the person, with their claims, to the sheet with the typed id; they leave this sheet. Refused
   * for a retired person and for this sheet's own id.
   */
  protected async transfer(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const toSheet = (this.transferTargets()[p.id] ?? '').trim();
    const refuse = (message: string) => this.rowErrors.update((e) => ({ ...e, [p.id]: message }));
    if (!/^[A-Za-z0-9_.-]{1,100}$/.test(toSheet)) {
      refuse('Enter the id of the sheet to transfer to.');
      return;
    }
    if (toSheet === sheet) {
      refuse('A person cannot be transferred to their own sheet.');
      return;
    }
    if (p.retiredFrom !== null) {
      refuse('A retired person cannot be transferred.');
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/transfer`;
    try {
      await firstValueFrom(this.http.post<void>(url, { toSheetId: toSheet }));
    } catch (err) {
      refuse(
        err instanceof HttpErrorResponse && err.status === 403
          ? 'Only a steward can transfer people.'
          : 'Could not transfer this person.',
      );
      return;
    }
    this.transferTargets.update(({ [p.id]: _, ...rest }) => rest);
    // the person leaves with their claims, patron links and pool memberships: every view re-reads the sheet
    if (this.session.sheetId() === sheet) await this.session.loadSheet(sheet);
  }

  protected setAliasInput(id: number, alias: string): void {
    this.aliasInputs.update((a) => ({ ...a, [id]: alias }));
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /** Gives the person the typed alias: never blank, nor any name or alias on the sheet, as names are compared. */
  protected async addAlias(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const alias = (this.aliasInputs()[p.id] ?? '').trim();
    const refuse = (message: string) => this.rowErrors.update((e) => ({ ...e, [p.id]: message }));
    if (!alias) {
      refuse('Enter an alias.');
      return;
    }
    const key = alias.toLowerCase();
    if (this.people().some((q) => q.name.toLowerCase() === key || q.aliases.some((a) => a.toLowerCase() === key))) {
      refuse('That name is already in use on this sheet.');
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/aliases`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { alias }));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((q) => (q.id === saved.id ? saved : q)));
      this.aliasInputs.update(({ [p.id]: _, ...rest }) => rest);
      if (this.editingId() === saved.id) this.editingVersion.set(saved.version);
    } catch (err) {
      refuse(
        err instanceof HttpErrorResponse && err.status === 409
          ? 'That name is already in use on this sheet.'
          : 'Could not add that alias.',
      );
    }
  }

  protected setHoldFrom(id: number, date: string): void {
    this.holdFroms.update((d) => ({ ...d, [id]: date }));
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected setHoldTo(id: number, date: string): void {
    this.holdTos.update((d) => ({ ...d, [id]: date }));
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /** Puts a hold on the person from the typed date (blank: the sheet's today) to the typed to-date (blank: no end). */
  protected async hold(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const from = (this.holdFroms()[p.id] ?? '').trim();
    const to = (this.holdTos()[p.id] ?? '').trim();
    const refuse = (message: string) => this.rowErrors.update((e) => ({ ...e, [p.id]: message }));
    if ((from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) || (to && !/^\d{4}-\d{2}-\d{2}$/.test(to))) {
      refuse('Enter hold dates as yyyy-mm-dd.');
      return;
    }
    if (to && to < (from || this.session.sheetToday() || '')) {
      refuse('A hold cannot end before it starts.');
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/hold`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { from: from || null, to: to || null }));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((q) => (q.id === saved.id ? saved : q)));
      this.holdFroms.update(({ [p.id]: _, ...rest }) => rest);
      this.holdTos.update(({ [p.id]: _, ...rest }) => rest);
      if (this.editingId() === saved.id) this.editingVersion.set(saved.version);
    } catch {
      refuse('Could not put this person on hold.');
    }
  }

  /** Starts the signed-in user watching the person, or stops them when they already watch them. */
  protected async toggleWatch(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const on = !this.watched().has(p.id);
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/watch`;
    try {
      const saved = await firstValueFrom(this.http.post<{ personId: number; watched: boolean }>(url, { on }));
      if (this.session.sheetId() !== sheet) return;
      this.watched.update((ids) => {
        const next = new Set(ids);
        if (saved.watched) next.add(saved.personId);
        else next.delete(saved.personId);
        return next;
      });
    } catch {
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Could not change watching this person.' }));
    }
  }

  protected statedTotal(s: StatementView | undefined): string {
    return s === undefined ? '' : (s.dueTotal ?? 'n/a');
  }

  /**
   * Takes a statement for the person: binds their counting claims to their figures now, or as they stood at the end
   * of the typed date (on or before the sheet's today).
   */
  protected async takeStatement(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const asOf = (this.statementAsOfs()[p.id] ?? '').trim();
    if (asOf && !/^\d{4}-\d{2}-\d{2}$/.test(asOf)) {
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Enter the statement date as yyyy-mm-dd.' }));
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/statement`;
    try {
      const taken = await firstValueFrom(
        this.http.post<{ statement: StatementView; claims: Claim[] }>(url, { asOf: asOf || null }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.statementAsOfs.update(({ [p.id]: _, ...rest }) => rest);
      const byId = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      this.statements.update((list) => [...list.filter((s) => s.personId !== p.id), taken.statement]);
    } catch {
      // a date typed for a refused statement does not carry into the next one
      this.statementAsOfs.update(({ [p.id]: _, ...rest }) => rest);
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Could not take a statement for this person.' }));
    }
  }

  /** Cancels the person's last statement: the claims it bound are live again. */
  protected async cancelStatement(p: Person): Promise<void> {
    this.clearRowErrors(p.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${p.id}/statement`;
    try {
      const left = await firstValueFrom(
        this.http.delete<{ statement: StatementView; claims: Claim[] }>(url),
      );
      if (this.session.sheetId() !== sheet) return;
      const byId = new Map(left.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      this.statements.update((list) => [
        ...list.filter((s) => s.personId !== p.id),
        ...(left.statement.count > 0 ? [left.statement] : []),
      ]);
    } catch {
      this.rowErrors.update((e) => ({ ...e, [p.id]: 'Could not cancel the last statement for this person.' }));
    }
  }

  /** The grade in force for the person on the sheet's today. */
  /** Downloads every person, retired ones included, in list order as a CSV file. */
  protected async exportCsv(): Promise<void> {
    await this.session.settled();
    const due = this.dueBy();
    const lines = [['name', 'grade', 'retired-from', 'due-total']];
    for (const p of this.people()) {
      lines.push([p.name, this.gradeToday(p), p.retiredFrom ?? '', due.get(p.id) ?? 'n/a']);
    }
    this.downloadCsv('people.csv', lines);
  }

  /**
   * Downloads every claim as a CSV file: people in list order, retired ones included, each person's claims
   * oldest first, with the figures as the claims list shows them.
   */
  protected async exportClaims(): Promise<void> {
    await this.session.settled();
    const rates = this.rates.byClaim();
    const floor = this.settings.floor();
    const late = this.settings.lateness();
    const periods = this.periods.list();
    const claimsBy = this.claimsBy();
    const lines = [['person', 'number', 'date', 'amount', 'due', 'status', 'period']];
    for (const p of this.people()) {
      for (const c of [...(claimsBy.get(p.id) ?? [])].reverse()) {
        const due = dueOf(c, rates(c).get(gradeOn(p.grades, c.date)) ?? [], floor, late, graceOf(c, p.grades, late));
        const period = periodOn(periods, c.dueBy)?.name ?? '';
        lines.push([p.name, c.number ?? '', c.date, c.amount, due ?? 'n/a', c.status, period]);
      }
    }
    this.downloadCsv('claims.csv', lines);
  }

  private downloadCsv(fileName: string, lines: string[][]): void {
    const csv = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected gradeToday(p: Person): string {
    return gradeOn(p.grades, this.session.sheetToday());
  }

  protected edit(p: Person): void {
    this.clearRowErrors(p.id);
    this.editingId.set(p.id);
    this.editingVersion.set(p.version);
    this.name.set(p.name);
    this.grade.set(this.gradeToday(p));
    this.gradeFrom.set('');
    this.terms.set(p.terms === null ? '' : String(p.terms));
    this.termsFrom.set('');
    this.contact.set(p.contact ?? '');
    this.tags.set(p.tags.join(', '));
    this.error.set(null);
  }

  protected claimSaved(saved: Claim): void {
    this.claims.update((list) => {
      const was = list.find((c) => c.id === saved.id);
      // a new claim, or one moved to another person, is listed first: its person's newest
      if (!was || was.personId !== saved.personId) return [saved, ...list.filter((c) => c.id !== saved.id)];
      return list.map((c) => (c.id === saved.id ? saved : c));
    });
  }

  protected selectClaim(id: number, checked: boolean): void {
    this.selectedClaims.update((selected) => {
      const next = new Set(selected);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /**
   * Opens the selected claims together: the store opens and numbers each that can be opened in list order and
   * leaves each that cannot as it is; shows how many were opened and how many refused.
   */
  protected async bulkOpen(): Promise<void> {
    if (this.bulkOpening) return;
    this.bulkOpened.set(null);
    this.bulkError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    this.bulkOpening = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ opened: Claim[]; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-open`,
          { ids },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const c of result.opened) this.claimSaved(c);
      this.selectedClaims.set(new Set());
      this.bulkOpened.set({ opened: result.opened.length, refused: result.refused });
    } catch {
      this.bulkError.set('Could not open the selected claims.');
    } finally {
      this.bulkOpening = false;
    }
  }

  /**
   * Settles the selected claims together: the store settles each that can be settled in list order, by the rules
   * of settling one, and leaves each that cannot as it is; shows how many were settled and how many refused. One
   * that settles at least one is the sheet's next batch, shown as its last.
   */
  protected async bulkSettle(): Promise<void> {
    if (this.bulkSettling) return;
    this.bulkSettled.set(null);
    this.bulkSettleError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    this.bulkSettling = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ settled: Claim[]; refused: number; batch: number | null }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-settle`,
          { ids },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const c of result.settled) this.claimSaved(c);
      if (result.batch !== null) this.session.sheetLastBatch.set(result.batch);
      this.selectedClaims.set(new Set());
      this.bulkSettled.set({ settled: result.settled.length, refused: result.refused });
    } catch {
      this.bulkSettleError.set('Could not settle the selected claims.');
    } finally {
      this.bulkSettling = false;
    }
  }

  /**
   * Moves the selected claims to the chosen person together: the store moves each that can be moved in list order,
   * by the rules of moving one, and leaves each that cannot as it is; shows how many were moved and how many refused.
   */
  protected async bulkReassign(): Promise<void> {
    if (this.bulkReassigning) return;
    this.bulkReassigned.set(null);
    this.bulkReassignError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const to = this.bulkReassignTo();
    if (!to) {
      this.bulkReassignError.set('Choose the person to move the selected claims to.');
      return;
    }
    const ids = [...this.selectedClaims()];
    this.bulkReassigning = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ reassigned: Claim[]; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-reassign`,
          { ids, personId: Number(to) },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      // each moved claim is listed as its person's newest, the last one moved first
      for (const moved of result.reassigned) {
        this.claims.update((list) => [moved, ...list.filter((c) => c.id !== moved.id)]);
      }
      this.selectedClaims.set(new Set());
      this.bulkReassigned.set({ reassigned: result.reassigned.length, refused: result.refused });
    } catch {
      this.bulkReassignError.set('Could not move the selected claims.');
    } finally {
      this.bulkReassigning = false;
    }
  }

  /**
   * Re-dates the selected claims to the typed date together: the store re-dates each that can be re-dated in list
   * order, by the rules of re-dating one, and leaves each that cannot as it is; shows how many were re-dated and how
   * many refused.
   */
  protected async bulkRedate(): Promise<void> {
    if (this.bulkRedating) return;
    this.bulkRedated.set(null);
    this.bulkRedateError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const date = this.bulkRedateDate().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.bulkRedateError.set('Enter the new date of the selected claims.');
      return;
    }
    const ids = [...this.selectedClaims()];
    this.bulkRedating = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ redated: Claim[]; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-redate`,
          { ids, date },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const c of result.redated) this.claimSaved(c);
      this.selectedClaims.set(new Set());
      this.bulkRedated.set({ redated: result.redated.length, refused: result.refused });
    } catch {
      this.bulkRedateError.set('Could not re-date the selected claims.');
    } finally {
      this.bulkRedating = false;
    }
  }

  protected selectPerson(id: number, checked: boolean): void {
    this.bulkGradeError.set(null);
    this.bulkRetireError.set(null);
    this.clearRowErrors(id);
    this.selectedPeople.update((selected) => {
      const next = new Set(selected);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /**
   * Gives the selected people the chosen grade from the typed date (blank: the sheet's today) together: the store
   * saves each one's edit in list order, as the person form would, all or none. Shows how many were changed.
   */
  protected async applyBulkGrade(): Promise<void> {
    if (this.bulkGrading) return;
    this.bulkGraded.set(null);
    this.bulkGradeError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ids = [...this.selectedPeople()];
    // grading the selected people is the next action on each of their rows
    ids.forEach((id) => this.clearRowErrors(id));
    const grade = this.bulkGrade();
    const from = this.bulkGradeFrom().trim();
    if (ids.length === 0) {
      this.bulkGradeError.set('Select at least one person to grade.');
      return;
    }
    if (!GRADES.includes(grade)) {
      this.bulkGradeError.set('Choose a grade of A, B or C.');
      return;
    }
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.bulkGradeError.set('Enter the grade from-date as yyyy-mm-dd.');
      return;
    }
    this.bulkGrading = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ graded: Person[] }>(`/api/sheets/${encodeURIComponent(sheet)}/people/bulk-grade`, {
          ids,
          grade,
          from: from || null,
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      const byId = new Map(result.graded.map((p) => [p.id, p]));
      this.people.update((list) => list.map((p) => byId.get(p.id) ?? p));
      const editing = this.editingId();
      if (editing !== null && byId.has(editing)) this.editingVersion.set(byId.get(editing)!.version);
      this.selectedPeople.set(new Set());
      this.bulkGradeFrom.set('');
      this.bulkGraded.set(result.graded.length);
    } catch {
      this.bulkGradeError.set('Could not grade the selected people; none were changed.');
    } finally {
      this.bulkGrading = false;
    }
  }

  /**
   * Retires the selected people from the typed date (blank: the sheet's today) together: the store retires each one
   * in list order, as the row's retire would, all or none. Shows how many were retired.
   */
  protected async applyBulkRetire(): Promise<void> {
    if (this.bulkRetiring) return;
    this.bulkRetired.set(null);
    this.bulkRetireError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ids = [...this.selectedPeople()];
    // retiring the selected people is the next action on each of their rows
    ids.forEach((id) => this.clearRowErrors(id));
    const from = this.bulkRetireFrom().trim();
    if (ids.length === 0) {
      this.bulkRetireError.set('Select at least one person to retire.');
      return;
    }
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.bulkRetireError.set('Enter the retirement date as yyyy-mm-dd.');
      return;
    }
    this.bulkRetiring = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ retired: Person[] }>(`/api/sheets/${encodeURIComponent(sheet)}/people/bulk-retire`, {
          ids,
          from: from || null,
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      const byId = new Map(result.retired.map((p) => [p.id, p]));
      this.people.update((list) => list.map((p) => byId.get(p.id) ?? p));
      const editing = this.editingId();
      if (editing !== null && byId.has(editing)) this.editingVersion.set(byId.get(editing)!.version);
      this.selectedPeople.set(new Set());
      this.bulkRetireFrom.set('');
      this.bulkRetired.set(result.retired.length);
    } catch {
      this.bulkRetireError.set('Could not retire the selected people; none were retired.');
    } finally {
      this.bulkRetiring = false;
    }
  }

  protected reset(): void {
    this.editingId.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set('');
    this.terms.set('');
    this.termsFrom.set('');
    this.contact.set('');
    this.tags.set('');
    this.error.set(null);
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const termsText = this.terms().trim();
    if (termsText !== '' && !/^\d{1,5}$/.test(termsText)) {
      // refused terms change nothing: an open editor is closed, so the form is ready to add the next person
      this.reset();
      this.error.set('Enter the terms as a whole number of days, or leave them blank.');
      return;
    }
    const contactText = this.contact().trim().toLowerCase();
    const form = {
      name: this.name().trim(),
      grade: this.grade(),
      terms: termsText === '' ? null : Number(termsText),
      termsFrom: this.termsFrom() || null,
      contact: contactText === '' ? null : contactText,
      tags: this.tags(),
    };
    if (!form.name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!GRADES.includes(form.grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    const editing = this.editingId();
    this.submitting = true;
    try {
      if (editing === null) {
        const added = await firstValueFrom(this.http.post<Person>(base, form));
        this.people.update((list) => [...list, added]);
      } else {
        const edit = { ...form, gradeFrom: this.gradeFrom() || null, version: this.editingVersion() };
        const saved = await firstValueFrom(this.http.put<Person>(`${base}/${editing}`, edit));
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (err) {
      if (err instanceof HttpErrorResponse && err.status === 412 && editing !== null) {
        await this.reloadEditing(base, editing);
        return;
      }
      if (err instanceof HttpErrorResponse && err.status === 409) {
        this.error.set('That name or contact is already in use on this sheet.');
        return;
      }
      this.error.set(editing === null ? 'Could not add that person.' : 'Could not save that person.');
    } finally {
      this.submitting = false;
    }
  }

  /** Adds a draft claim dated the sheet's today for the named person, adding them with grade A when new. */
  protected async quickAdd(e: Event): Promise<void> {
    e.preventDefault();
    if (this.quickAdding) return;
    this.quickError.set(null);
    const sheet = this.session.sheetId();
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
    this.quickAdding = true;
    try {
      const added = await firstValueFrom(
        this.http.post<{ person: Person; personAdded: boolean; claim: Claim }>(
          `/api/sheets/${encodeURIComponent(sheet)}/quick-add`,
          { name, amount },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      if (!this.people().some((p) => p.id === added.person.id)) {
        this.people.update((list) => [...list, added.person]);
      }
      this.claimSaved(added.claim);
      this.quickName.set('');
      this.quickAmount.set('');
    } catch (err) {
      this.quickError.set(
        err instanceof HttpErrorResponse && err.status === 409
          ? 'That claim is due by a date in a closed period, so it cannot be added.'
          : 'Could not quick add that claim.',
      );
    } finally {
      this.quickAdding = false;
    }
  }

  /**
   * Adds and opens a claim for each pasted line, in order, or updates and opens the DRAFT it matches; the store
   * checks every line first and takes none when any is refused. Shows how many lines went through.
   */
  protected async paste(e: Event): Promise<void> {
    e.preventDefault();
    if (this.pasting) return;
    this.pasteAdded.set(null);
    this.pasteError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = this.pasteText();
    if (!text.trim()) {
      this.pasteError.set('Paste at least one line of name,amount,date,note.');
      return;
    }
    this.pasting = true;
    try {
      const added = await firstValueFrom(
        this.http.post<Claim[]>(`/api/sheets/${encodeURIComponent(sheet)}/claims/paste`, { text }),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const c of added) this.claimSaved(c);
      this.pasteText.set('');
      this.pasteAdded.set(added.length);
    } catch (err) {
      const reason = err instanceof HttpErrorResponse ? (err.error as { message?: string } | null)?.message : null;
      this.pasteError.set(reason ? reason : 'Could not add the pasted claims; none were added.');
    } finally {
      this.pasting = false;
    }
  }

  /**
   * Makes, for every plan in the order they were added, a draft claim on each plan date up to its end and the sheet's
   * today that has none yet; a date the claim form refuses is skipped and tried again next time. Shows how many were
   * made.
   */
  protected async generatePlans(): Promise<void> {
    if (this.generating) return;
    this.plansGenerated.set(null);
    this.planGenerateError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    this.generating = true;
    try {
      const made = await this.planStore.generate(sheet);
      if (this.session.sheetId() !== sheet) return;
      for (const c of made) this.claimSaved(c);
      this.plansGenerated.set(made.length);
    } catch (err) {
      this.planGenerateError.set(
        err instanceof HttpErrorResponse && err.status === 403
          ? 'Your role may not generate the plans\' claims on this sheet.'
          : 'Could not generate the plans\' claims.',
      );
    } finally {
      this.generating = false;
    }
  }

  /**
   * Adds a person for each line of the import box, or updates the person on the sheet it names, in order; the store
   * checks every line by the person form's rules first and changes none when any is refused. Shows how many were
   * added and how many updated.
   */
  protected async importPeople(e: Event): Promise<void> {
    e.preventDefault();
    if (this.importing) return;
    this.importAdded.set(null);
    this.importError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = this.importText();
    if (!text.trim()) {
      this.importError.set('Paste at least one line of name,grade,terms,contact.');
      return;
    }
    this.importing = true;
    try {
      const { added, updated } = await firstValueFrom(
        this.http.post<{ added: Person[]; updated: Person[] }>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/import`,
          { text },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      const byId = new Map(updated.map((p) => [p.id, p]));
      this.people.update((list) => [...list.map((p) => byId.get(p.id) ?? p), ...added]);
      this.importText.set('');
      this.importUpdated.set(updated.length);
      this.importAdded.set(added.length);
    } catch (err) {
      const reason = err instanceof HttpErrorResponse ? (err.error as { message?: string } | null)?.message : null;
      this.importError.set(reason ? reason : 'Could not import those people; none were added.');
    } finally {
      this.importing = false;
    }
  }

  /** After a save refused for a stale version: show the person as stored now, with the editor kept open on it. */
  private async reloadEditing(base: string, id: number): Promise<void> {
    const message = 'Someone else has saved this person since you opened it. The current values are shown.';
    try {
      const current = (await firstValueFrom(this.http.get<Person[]>(base))).find((p) => p.id === id);
      if (current && this.editingId() === id) {
        this.people.update((list) => list.map((p) => (p.id === id ? current : p)));
        this.editingVersion.set(current.version);
        this.name.set(current.name);
        this.grade.set(this.gradeToday(current));
        this.terms.set(current.terms === null ? '' : String(current.terms));
        this.contact.set(current.contact ?? '');
        this.tags.set(current.tags.join(', '));
      }
    } catch {
      // the error below still tells the person the save was refused
    }
    if (this.editingId() === id) this.error.set(message);
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const base = `/api/sheets/${encodeURIComponent(sheet)}`;
      const [list, claims, tags, statements, patrons, watched] = await Promise.all([
        firstValueFrom(this.http.get<Person[]>(`${base}/people`)),
        firstValueFrom(this.http.get<Claim[]>(`${base}/claims`)),
        firstValueFrom(this.http.get<string[]>(`${base}/claim-tags`)),
        firstValueFrom(this.http.get<StatementView[]>(`${base}/statements`)),
        firstValueFrom(this.http.get<PatronView[]>(`${base}/people/patrons`)),
        firstValueFrom(this.http.get<number[]>(`${base}/people/watched`)),
        this.rates.load(sheet),
        this.periods.load(sheet),
        this.planStore.load(sheet),
      ]);
      // a later load may have moved to another sheet while this one was in flight
      if (this.session.sheetId() === sheet) {
        this.claims.set(claims);
        this.usedTags.set(tags);
        // a selected claim no longer on the sheet stays out of the next bulk action
        const ids = new Set(claims.map((c) => c.id));
        this.selectedClaims.update((selected) => new Set([...selected].filter((id) => ids.has(id))));
        this.statements.set(statements);
        this.patrons.set(patrons);
        this.watched.set(new Set(watched));
        this.people.set(list);
        // a selected person no longer on the sheet stays out of the next bulk grade
        const people = new Set(list.map((p) => p.id));
        this.selectedPeople.update((selected) => new Set([...selected].filter((id) => people.has(id))));
      }
    } catch {
      this.error.set('Could not load the people.');
    }
  }
}

// A CSV field: wrapped in double quotes, with quotes doubled, when it holds a comma, a quote or a line break.
function csvField(v: string): string {
  return /[",\r\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
}
