import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT, Claim, Late, PersonClaims, cents, claimDueCents, counts, deferred, isOpen, money, overdue, plusDays, statusAsOf } from '../claims/claims';
import { Holidays } from '../holidays/holidays';
import { VoidReasonStore, VoidReasons } from '../void-reasons/void-reasons';
import { ClaimTemplates } from '../claim-templates/claim-templates';
import { PeriodStore, Periods, periodOf } from '../periods/periods';
import { NO_RATE, PersonGrade, RateStore, Rates, gradeOn } from '../rates/rates';
import { SettingStore, Settings } from '../settings/settings';
import { Summary } from '../summary/summary';
import { Counters } from '../counters/counters';
import { PoolStore, Pools, figure, personCutTotals } from '../pools/pools';

/** A hold put on a person: from its from-date to its to-date, both included (null: it does not end). */
interface Hold {
  from: string;
  to: string | null;
}

interface Person {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the person was read. */
  grade: string;
  /** Every grade the person was given, from the earliest, each in force from its from-date. */
  grades: PersonGrade[];
  /** Raised on every save; an editor saves only the version it loaded. */
  version: number;
  /** The person's own terms in business days, which fix the due-by of their claims; null for none. */
  terms: number | null;
  /** The person's contact, trimmed and lower-cased; null for none. */
  contact: string | null;
  /** The sheet the person was transferred from; null for one not transferred in. */
  origin: string | null;
  /** How many statements were taken for the person. */
  statementCount: number;
  /** The last statement's due-total (two decimals, or n/a); null when none was taken. */
  statedTotal: string | null;
  /** The sheet's today when the last statement was taken; null when none was. */
  statementDate: string | null;
  /** Whether the person is retired: any retirement date set counts, whatever the date. */
  retired: boolean;
  /** The date the person is retired from; null when they are not retired. */
  retiredFrom: string | null;
  /** The person's past retirements, from the date they were retired from to the day they were
   *  unretired, in the order they ended. */
  retirements: { from: string; to: string }[];
  /** The holds put on the person, in the order they were: their OPEN claims dated within one, unless
   *  bound by a statement, count in no live total. */
  holds: Hold[];
  /** The ids of the person's patrons, in the order they were named. */
  patronIds: number[];
  /** Whether the signed-in user watches the person. */
  watched: boolean;
  /** The person's aliases, sorted as names are compared. */
  aliases: string[];
  /** Whether the person's name or an alias of theirs is a name or alias on another sheet of the tree. */
  clashing: boolean;
}

/** The figures of a sheet's descendants added up, each as that sheet shows it, and the sheet's own
 *  figures as it shows them now (absent until read). */
interface DescendantFigures {
  people: number;
  dueTotal: string;
  overdueTotal: string;
  ownPeople?: number;
  ownDueTotal?: string;
  ownOverdueTotal?: string;
}

const NO_DESCENDANTS: DescendantFigures = { people: 0, dueTotal: '0.00', overdueTotal: '0.00' };

/** A statement taken: the person's statements as their row shows them, and their claims as it
 *  bound them. */
interface StatementTaken {
  statements: { count: number; lastDueTotal: string | null; lastDate: string | null };
  claims: Claim[];
}

// The people on the active sheet, in the order they were added, each with their claims and what
// they have claimed, and the one form that adds a person or, once a person's editor is opened,
// saves their changes.
@Component({
  selector: 'app-people',
  imports: [ClaimTemplates, Counters, Holidays, PersonClaims, Periods, Pools, Rates, Settings, Summary, VoidReasons],
  template: `
    <app-counters
      [peopleCount]="people().length"
      [statuses]="statusSummary()"
      [dueTotal]="dueTotal()"
      [overdueTotal]="overdueTotal()"
      [myDueTotal]="mine()?.dueTotal ?? ''"
      [myOverdueTotal]="mine()?.overdueTotal ?? ''"
      [consolidatedPeopleCount]="consolidatedPeople()"
      [consolidatedDueTotal]="consolidatedDue()"
      [consolidatedOverdueTotal]="consolidatedOverdue()"
    />
    <app-settings />
    <app-rates />
    <app-periods [dueTotals]="periodDueTotals()" />
    <app-holidays />
    <app-void-reasons />
    <app-claim-templates />
    <app-pools [people]="people()" />
    <app-summary
      [grades]="gradeSummary()"
      [periods]="periodSummary()"
      [statuses]="statusSummary()"
      [voids]="voidReasonSummary()"
    />
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
        <label for="person-form-grade-from">Grade from</label>
        <input
          type="date"
          id="person-form-grade-from"
          data-testid="person-form-grade-from"
          [value]="gradeFrom()"
          (input)="gradeFrom.set(value($event)); error.set(null)"
        />
        <label for="person-form-terms">Terms (business days)</label>
        <input
          id="person-form-terms"
          data-testid="person-form-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); error.set(null)"
        />
        <label for="person-form-contact">Contact</label>
        <input
          id="person-form-contact"
          data-testid="person-form-contact"
          [value]="contact()"
          (input)="contact.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
      </form>
      @if (error(); as message) {
        <p role="alert" data-testid="person-form-error">{{ message }}</p>
      }
      <form (submit)="importPeople($event)" aria-label="Import people">
        <label for="import-people">Import people, one per line as name,grade,terms,contact (terms and contact may be left out)</label>
        <textarea
          id="import-people"
          data-testid="import-people"
          rows="4"
          [value]="importText()"
          (input)="importText.set(value($event)); importError.set(null)"
        ></textarea>
        <button type="submit" data-testid="import-submit">Import people</button>
        @if (importAdded() !== null) {
          Added <strong data-testid="import-added">{{ importAdded() }}</strong>
        }
        @if (importUpdated() !== null) {
          Updated <strong data-testid="import-updated">{{ importUpdated() }}</strong>
        }
      </form>
      @if (importError(); as message) {
        <p role="alert" data-testid="import-error">{{ message }}</p>
      }
      <form (submit)="quickAdd($event)" aria-label="Quick add a claim">
        <label for="quick-name">Quick add for</label>
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
      </form>
      @if (quickError(); as message) {
        <p role="alert" data-testid="quick-error">{{ message }}</p>
      }
      <form (submit)="pasteClaims($event)" aria-label="Paste claims">
        <label for="paste-claims">Paste claims, one per line as name,amount,date,note (end the note with " #&lt;reference&gt;" to give a reference)</label>
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
      </form>
      @if (pasteError(); as message) {
        <p role="alert" data-testid="paste-error">{{ message }}</p>
      }
      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="showRetired()"
          (change)="showRetired.set(checked($event))"
        />
        Show retired
      </label>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people as CSV</button>
      <button type="button" data-testid="export-claims" (click)="exportClaims()">Export claims as CSV</button>
      <p>
        <button type="button" data-testid="bulk-open" (click)="openSelected()">Open selected claims</button>
        @if (bulkOpenResult(); as r) {
          Opened <strong data-testid="bulk-opened">{{ r.opened }}</strong>, refused
          <strong data-testid="bulk-refused">{{ r.refused }}</strong>
        }
      </p>
      @if (bulkOpenError(); as message) {
        <p role="alert" data-testid="bulk-open-error">{{ message }}</p>
      }
      <p>
        <button type="button" data-testid="bulk-settle" (click)="settleSelected()">Settle selected claims</button>
        Last batch <strong data-testid="bulk-settle-batch">{{ lastBatch() ?? '' }}</strong>
        @if (bulkSettleResult(); as r) {
          Settled <strong data-testid="bulk-settled">{{ r.settled }}</strong>, refused
          <strong data-testid="bulk-settle-refused">{{ r.refused }}</strong>
        }
      </p>
      @if (bulkSettleError(); as message) {
        <p role="alert" data-testid="bulk-settle-error">{{ message }}</p>
      }
      <p>
        <label for="bulk-reassign-to">Move the selected claims to</label>
        <select
          id="bulk-reassign-to"
          data-testid="bulk-reassign-to"
          (change)="bulkReassignTo.set(value($event)); bulkReassignError.set(null)"
        >
          <option value="" [selected]="!bulkReassignTo()">Choose a person</option>
          @for (o of people(); track o.id) {
            <option [value]="'' + o.id" [selected]="bulkReassignTo() === '' + o.id">{{ o.name }}</option>
          }
        </select>
        <button type="button" data-testid="bulk-reassign" (click)="reassignSelected()">Move selected claims</button>
        @if (bulkReassignResult(); as r) {
          Moved <strong data-testid="bulk-reassigned">{{ r.reassigned }}</strong>, refused
          <strong data-testid="bulk-reassign-refused">{{ r.refused }}</strong>
        }
      </p>
      @if (bulkReassignError(); as message) {
        <p role="alert" data-testid="bulk-reassign-error">{{ message }}</p>
      }
      <form (submit)="gradeSelected($event)" aria-label="Grade the selected people">
        <label for="bulk-grade">Grade the selected people</label>
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
        <label for="bulk-grade-from">From</label>
        <input
          type="date"
          id="bulk-grade-from"
          data-testid="bulk-grade-from"
          [value]="bulkGradeFrom()"
          (input)="bulkGradeFrom.set(value($event)); bulkGradeError.set(null)"
        />
        <button type="submit" data-testid="bulk-grade-apply">Grade selected people</button>
        @if (bulkGraded() !== null) {
          Changed <strong data-testid="bulk-graded">{{ bulkGraded() }}</strong>
        }
      </form>
      @if (bulkGradeError(); as message) {
        <p role="alert" data-testid="person-error">{{ message }}</p>
      }
      <form (submit)="retireSelected($event)" aria-label="Retire the selected people">
        <label for="bulk-retire-date">Retire the selected people from</label>
        <input
          type="date"
          id="bulk-retire-date"
          data-testid="bulk-retire-date"
          [value]="bulkRetireFrom()"
          (input)="bulkRetireFrom.set(value($event)); bulkRetireError.set(null)"
        />
        <button type="submit" data-testid="bulk-retire">Retire selected people</button>
        @if (bulkRetired() !== null) {
          Retired <strong data-testid="bulk-retired">{{ bulkRetired() }}</strong>
        }
      </form>
      @if (bulkRetireError(); as message) {
        <p role="alert" data-testid="person-error">{{ message }}</p>
      }
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Settled in total <strong data-testid="rollup-settled-total">{{ settledTotal() }}</strong></p>
      <p>Open in total <strong data-testid="rollup-open-total">{{ openTotal() }}</strong></p>
      <p>Overdue claims <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong></p>
      <p>Overdue in total <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>
      <p>Cuts in total <strong data-testid="rollup-cut-total">{{ cutTotal() }}</strong></p>
      @if (tagTotals().length > 0) {
        <ul aria-label="Due in total by tag">
          @for (t of tagTotals(); track t.tag) {
            <li>{{ t.tag }} <strong [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ t.dueTotal }}</strong></li>
          }
        </ul>
      }
      @if (monthTotals().length > 0) {
        <ul aria-label="Due in total by month">
          @for (m of monthTotals(); track m.month) {
            <li>{{ m.month }} <strong [attr.data-testid]="'month-' + m.month + '-due-total'">{{ m.dueTotal }}</strong></li>
          }
        </ul>
      }

      <table>
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th>
            <th scope="col">Name</th>
            <th scope="col">Aliases</th>
            <th scope="col">Clashing</th>
            <th scope="col">Grade</th>
            <th scope="col">Terms</th>
            <th scope="col">Contact</th>
            <th scope="col">Came from</th>
            <th scope="col">Claimed</th>
            <th scope="col">Due</th>
            <th scope="col">Settled due</th>
            <th scope="col">Open due</th>
            <th scope="col">Overdue</th>
            <th scope="col">Overdue due</th>
            <th scope="col">Due aged 0–30</th>
            <th scope="col">Due aged 31–60</th>
            <th scope="col">Due aged 61+</th>
            <th scope="col">Statements</th>
            <th scope="col">Last stated</th>
            <th scope="col">Stated on</th>
            <th scope="col">Retired</th>
            <th scope="col">Retired from</th>
            <th scope="col">Past retirements</th>
            <th scope="col">Active</th>
            <th scope="col">On hold</th>
            <th scope="col">Holds</th>
            <th scope="col">Watched</th>
            <th scope="col">Patrons</th>
            <th scope="col">Backing</th>
            <th scope="col">Excess</th>
            <th scope="col">Cut total</th>
            @for (pd of periods.periods(); track pd.id) {
              <th scope="col">Due in {{ pd.name }}</th>
            }
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
              <td data-testid="person-aliases">{{ aliasesOf(p) }}</td>
              <td data-testid="person-clashing">{{ p.clashing ? 'yes' : 'no' }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              <td data-testid="person-contact">{{ p.contact ?? '' }}</td>
              <td data-testid="person-origin">{{ p.origin ?? '' }}</td>
              <td data-testid="person-claimed">{{ claimedBy(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueBy(p) }}</td>
              <td data-testid="person-settled-total">{{ money(settledCentsOf(p)) }}</td>
              <td data-testid="person-open-total">{{ money(openCentsOf(p)) }}</td>
              <td data-testid="person-overdue-count">{{ overdueOf(p.id).length }}</td>
              <td data-testid="person-overdue-total">{{ overdueTotalOf(p) }}</td>
              @let aged = agedOf(p);
              <td data-testid="person-aged-0-30">{{ aged[0] }}</td>
              <td data-testid="person-aged-31-60">{{ aged[1] }}</td>
              <td data-testid="person-aged-61">{{ aged[2] }}</td>
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statedTotal ?? '' }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td>
                <ul [attr.aria-label]="'Past retirements of ' + p.name">
                  @for (r of p.retirements; track $index) {
                    <li data-testid="person-retirement-line">{{ r.from }} to {{ r.to }}</li>
                  }
                </ul>
              </td>
              <td data-testid="person-active">{{ activeIds().has(p.id) ? 'yes' : 'no' }}</td>
              <td data-testid="person-hold">{{ p.holds.length > 0 ? 'yes' : 'no' }}</td>
              <td>
                <ul [attr.aria-label]="'Holds of ' + p.name">
                  @for (h of p.holds; track $index) {
                    <li data-testid="person-hold-line">from {{ h.from }}{{ h.to ? ' to ' + h.to : '' }}</li>
                  }
                </ul>
              </td>
              <td data-testid="person-watched">{{ p.watched ? 'yes' : 'no' }}</td>
              <td data-testid="person-patrons">{{ patronsOf(p) }}</td>
              <td data-testid="person-backing">{{ backingOf(p) }}</td>
              <td data-testid="person-excess">{{ excessOf(p) }}</td>
              <td data-testid="person-cut-total">{{ cutTotalOf(p) }}</td>
              @for (pd of periods.periods(); track pd.id) {
                <td [attr.data-testid]="'person-period-' + pd.name + '-due-total'">
                  {{ money(personPeriodCents().get(pd.id)?.get(p.id) ?? 0) }}
                </td>
              }
              <td>
                <app-person-claims
                  [personId]="p.id"
                  [claims]="claimsOf(p.id)"
                  [grades]="p.grades"
                  [floor]="floor()"
                  [latePct]="settings.latePctOn()"
                  [policyDate]="settings.policyDate()"
                  [graceOn]="settings.graceOn()"
                  [selectedIds]="selectedClaims()"
                  [people]="people()"
                  [viewer]="viewer()"
                  (saved)="claimSaved($event)"
                  (moved)="claimMoved($event)"
                  (selectedChange)="selectClaim($event.id, $event.selected)"
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
                  type="date"
                  [id]="'person-statement-asof-' + p.id"
                  data-testid="person-statement-asof"
                  [value]="statementDates()[p.id] ?? ''"
                  (input)="setStatementDate(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-statement"
                  [attr.aria-label]="'Take a statement for ' + p.name"
                  (click)="takeStatement(p)"
                >
                  Statement
                </button>
                <button
                  type="button"
                  data-testid="person-unstate"
                  [attr.aria-label]="'Cancel the last statement for ' + p.name"
                  (click)="cancelStatement(p)"
                >
                  Cancel statement
                </button>
                <button
                  type="button"
                  data-testid="person-move-up"
                  [attr.aria-label]="'Move ' + p.name + ' up the list'"
                  (click)="move(p, 'up')"
                >
                  Up
                </button>
                <button
                  type="button"
                  data-testid="person-move-down"
                  [attr.aria-label]="'Move ' + p.name + ' down the list'"
                  (click)="move(p, 'down')"
                >
                  Down
                </button>
                <button
                  type="button"
                  data-testid="person-watch"
                  [attr.aria-label]="(p.watched ? 'Stop watching ' : 'Watch ') + p.name"
                  [attr.aria-pressed]="p.watched"
                  (click)="toggleWatch(p)"
                >
                  {{ p.watched ? 'Unwatch' : 'Watch' }}
                </button>
                <label [for]="'person-retire-date-' + p.id">Retire from</label>
                <input
                  type="date"
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
                <button
                  type="button"
                  data-testid="person-unretire"
                  [attr.aria-label]="'Unretire ' + p.name"
                  (click)="unretire(p)"
                >
                  Unretire
                </button>
                <label [for]="'person-hold-from-' + p.id">Hold from</label>
                <input
                  type="date"
                  [id]="'person-hold-from-' + p.id"
                  data-testid="person-hold-from"
                  [value]="holdDates()[p.id]?.from ?? ''"
                  (input)="setHoldDate(p.id, 'from', value($event))"
                />
                <label [for]="'person-hold-to-' + p.id">Hold to</label>
                <input
                  type="date"
                  [id]="'person-hold-to-' + p.id"
                  data-testid="person-hold-to"
                  [value]="holdDates()[p.id]?.to ?? ''"
                  (input)="setHoldDate(p.id, 'to', value($event))"
                />
                <button
                  type="button"
                  data-testid="person-hold-toggle"
                  [attr.aria-label]="'Hold ' + p.name"
                  (click)="hold(p)"
                >
                  Hold
                </button>
                <label [for]="'person-patron-select-' + p.id">Patron</label>
                <select
                  [id]="'person-patron-select-' + p.id"
                  data-testid="person-patron-select"
                  [value]="patronChoices()[p.id] ?? ''"
                  (change)="setPatronChoice(p.id, value($event))"
                >
                  <option value="" [selected]="!patronChoices()[p.id]">Choose a patron</option>
                  @for (o of people(); track o.id) {
                    <option [value]="'' + o.id" [selected]="patronChoices()[p.id] === '' + o.id">{{ o.name }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="person-patron-add"
                  [attr.aria-label]="'Add a patron for ' + p.name"
                  (click)="addPatron(p)"
                >
                  Add patron
                </button>
                <label [for]="'person-alias-input-' + p.id">Alias</label>
                <input
                  [id]="'person-alias-input-' + p.id"
                  data-testid="person-alias-input"
                  [value]="aliasInputs()[p.id] ?? ''"
                  (input)="setAliasInput(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-alias-add"
                  [attr.aria-label]="'Add an alias for ' + p.name"
                  (click)="addAlias(p)"
                >
                  Add alias
                </button>
                <label [for]="'person-merge-into-' + p.id">Merge into</label>
                <select
                  [id]="'person-merge-into-' + p.id"
                  data-testid="person-merge-into"
                  [value]="mergeChoices()[p.id] ?? ''"
                  (change)="setMergeChoice(p.id, value($event))"
                >
                  <option value="" [selected]="!mergeChoices()[p.id]">Choose a person</option>
                  @for (o of people(); track o.id) {
                    <option [value]="'' + o.id" [selected]="mergeChoices()[p.id] === '' + o.id">{{ o.name }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="person-merge"
                  [attr.aria-label]="'Merge ' + p.name + ' into the chosen person'"
                  (click)="merge(p)"
                >
                  Merge
                </button>
                <label [for]="'person-transfer-to-' + p.id">Transfer to sheet</label>
                <input
                  [id]="'person-transfer-to-' + p.id"
                  data-testid="person-transfer-to"
                  [value]="transferTargets()[p.id] ?? ''"
                  (input)="setTransferTarget(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-transfer"
                  [attr.aria-label]="'Transfer ' + p.name + ' to the sheet typed'"
                  (click)="transfer(p)"
                >
                  Transfer
                </button>
                @if (rowError(); as e) {
                  @if (e.id === p.id) {
                    <p role="alert" [attr.data-testid]="e.testId ?? 'person-error'">{{ e.message }}</p>
                  }
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
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  protected readonly rates = inject(RateStore);
  protected readonly periods = inject(PeriodStore);
  private readonly voidReasons = inject(VoidReasonStore);
  protected readonly money = money;
  protected readonly cents = cents;
  protected readonly settings = inject(SettingStore);
  private readonly pools = inject(PoolStore);
  /** Each person's cut total in hundredths, by person id: their cuts across every pool they are a member of. */
  protected readonly cutTotals = computed(() =>
    personCutTotals(this.pools.pools(), this.people(), this.settings.pot(), this.session.today()),
  );
  /** A person's cut total (two decimals). */
  protected cutTotalOf(person: Person): string {
    return figure(this.cutTotals().get(person.id) ?? 0n);
  }
  /** Everyone's cut total added up. */
  protected readonly cutTotal = computed(() => figure([...this.cutTotals().values()].reduce((sum, c) => sum + c, 0n)));
  /** Whether the signed-in user is a viewer on the sheet. */
  protected readonly viewer = computed(() => {
    const user = this.session.user();
    const today = this.session.today();
    // A delegation of the steward role in force on the sheet's today makes the user a steward.
    const delegated = today !== null && this.settings.delegations().some((d) => d.user === user && d.from <= today && today <= d.to);
    return !delegated && this.settings.roles().some((r) => r.user === user && r.role === 'viewer');
  });
  /** The sheet's floor in cents in force on a date. */
  protected readonly floor = computed(() => {
    const floorOn = this.settings.floorOn();
    return (date: string) => cents(floorOn(date));
  });
  /** The sheet's cap in cents in force on its today; null when there is none. */
  private readonly cap = computed(() => {
    const cap = this.settings.cap();
    return cap === null ? null : cents(cap);
  });
  /** What late charges work from: the sheet's late percent in force on a date and today; and the policy date
   *  figures round by. */
  private readonly late = computed<Late>(() => ({
    pct: this.settings.latePctOn(),
    today: this.session.today(),
    policyDate: this.settings.policyDate(),
    grace: this.graceOf(),
  }));
  /** A claim's grace days: those in force on its date for its person's grade in force then. */
  private readonly graceOf = computed(() => {
    const graceOn = this.settings.graceOn();
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    return (c: Claim) => graceOn(gradeOn(grades.get(c.personId) ?? [], c.date), c.date);
  });

  protected readonly grades = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  /** Whether retired people are listed; off when the screen opens and kept across sheet loads. */
  protected readonly showRetired = signal(false);
  /** The people listed: everyone, or only those not retired unless retired people are shown. */
  protected readonly shownPeople = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The retirement date typed on each person's row, by person id. */
  protected readonly retireDates = signal<Record<number, string>>({});
  /** The hold from- and to-dates typed on each person's row, by person id. */
  protected readonly holdDates = signal<Record<number, { from?: string; to?: string }>>({});
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  /** The sheet's claims that count in live totals: OPEN, PENDING and SETTLED ones, except one deferred
   *  on the sheet's today, and, unless bound by a statement, an OPEN one dated within one of its person's
   *  holds or an OPEN or PENDING one whose person is retired by the sheet's today. */
  private readonly live = computed(() => this.claims().filter((c) => counts(c) && this.liveRulesKeep()(c)));
  /** Whether the live rules leave a claim in: it is not deferred on the sheet's today, and, unless bound
   *  by a statement, not an OPEN one dated within one of its person's holds, nor an OPEN or PENDING one
   *  whose person's retirement date is on or before the sheet's today. */
  private readonly liveRulesKeep = computed(() => {
    const holds = new Map(this.people().map((p) => [p.id, p.holds]));
    const retiredFrom = new Map(this.people().map((p) => [p.id, p.retiredFrom]));
    const today = this.session.today();
    const held = (c: Claim) =>
      c.status === 'OPEN' &&
      (holds.get(c.personId) ?? []).some((h) => h.from <= c.date && (h.to === null || c.date <= h.to));
    const retired = (c: Claim) => {
      const from = retiredFrom.get(c.personId) ?? null;
      return isOpen(c) && from !== null && today !== null && from <= today;
    };
    return (c: Claim) => !deferred(c, today) && (c.stated || !(held(c) || retired(c)));
  });
  /** Each person's claims that count toward what is claimed and due, by person id. */
  private readonly countedByPerson = computed(() => {
    const map = new Map<number, Claim[]>();
    for (const c of this.live()) map.set(c.personId, [...(map.get(c.personId) ?? []), c]);
    return map;
  });
  /** The ids of the people who are active: they have an OPEN or PENDING claim that counts dated
   *  within the 30 days up to and including the sheet's today. */
  protected readonly activeIds = computed(() => {
    const today = this.session.today();
    if (today === null) return new Set<number>();
    const since = plusDays(today, -29);
    return new Set(
      this.live()
        .filter((c) => isOpen(c) && since <= c.date && c.date <= today)
        .map((c) => c.personId),
    );
  });
  /** Each person's claims, newest first, by person id. */
  private readonly claimsByPerson = computed(() => {
    const map = new Map<number, Claim[]>();
    for (const c of this.claims()) map.set(c.personId, [...(map.get(c.personId) ?? []), c]);
    return map;
  });
  protected readonly claimedTotal = computed(() =>
    money(this.live().reduce((sum, c) => sum + cents(c.amount), 0)),
  );
  /** Everyone's due-total added up, skipping those whose grade has no rate, trimmed. */
  protected readonly dueTotal = computed(() =>
    money(
      trimmedSum(
        this.people()
          .map((p) => this.dueCentsBy(p))
          .filter((due) => due !== null),
      ),
    ),
  );
  /** The signed-in user's own figures: those of the person whose name or an alias is the user's
   *  name, as names are compared; null when there is no such person. */
  protected readonly mine = computed(() => {
    const user = this.session.user()?.toLowerCase();
    const me = this.people().find(
      (p) => p.name.toLowerCase() === user || p.aliases.some((a) => a.toLowerCase() === user),
    );
    return me ? { dueTotal: this.dueBy(me), overdueTotal: this.overdueTotalOf(me) } : null;
  });
  /** The sheet's descendants' figures added up, as the store last told them. */
  protected readonly descendantFigures = signal<DescendantFigures>(NO_DESCENDANTS);
  /** The consolidated figures: the sheet's own as it shows them now (read from the server while the
   *  screen shows a past day) plus its descendants'. */
  protected readonly consolidatedPeople = computed(() => {
    const d = this.descendantFigures();
    return (this.session.asOf() && d.ownPeople !== undefined ? d.ownPeople : this.people().length) + d.people;
  });
  protected readonly consolidatedDue = computed(() => {
    const d = this.descendantFigures();
    const own = this.session.asOf() && d.ownDueTotal !== undefined ? d.ownDueTotal : this.dueTotal();
    return money(cents(own) + cents(d.dueTotal));
  });
  protected readonly consolidatedOverdue = computed(() => {
    const d = this.descendantFigures();
    const own = this.session.asOf() && d.ownOverdueTotal !== undefined ? d.ownOverdueTotal : this.overdueTotal();
    return money(cents(own) + cents(d.overdueTotal));
  });
  /** Every tag ever used on the sheet, as the store last told it. */
  private readonly usedTags = signal<string[]>([]);
  /** Per tag ever used on the sheet, sorted: the dues shown on the claims that count which carry it
   *  added up (a claim with no figure adds nothing; one with two tags counts in both). */
  protected readonly tagTotals = computed(() => {
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const floor = this.floor();
    const late = this.late();
    const totals = new Map<string, number>();
    for (const tag of this.usedTags()) totals.set(tag, 0);
    for (const c of this.claims()) for (const tag of c.tags) if (!totals.has(tag)) totals.set(tag, 0);
    for (const c of this.live()) {
      const theirs = grades.get(c.personId);
      if (theirs === undefined || c.tags.length === 0) continue;
      const due = claimDueCents(c, this.rates.ofGradeOn(theirs, c.date), floor, late) ?? 0;
      for (const tag of c.tags) totals.set(tag, (totals.get(tag) ?? 0) + due);
    }
    return [...totals.keys()].sort().map((tag) => ({ tag, dueTotal: money(totals.get(tag)!) }));
  });
  /** Per calendar month (yyyy-mm) any claim on the sheet is dated in, sorted: the dues shown on the
   *  claims that count dated in it added up (a claim with no figure adds nothing). */
  protected readonly monthTotals = computed(() => {
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const floor = this.floor();
    const late = this.late();
    const totals = new Map<string, number>();
    for (const c of this.claims()) totals.set(c.date.slice(0, 7), 0);
    for (const c of this.live()) {
      const theirs = grades.get(c.personId);
      if (theirs === undefined) continue;
      const month = c.date.slice(0, 7);
      const due = claimDueCents(c, this.rates.ofGradeOn(theirs, c.date), floor, late) ?? 0;
      totals.set(month, (totals.get(month) ?? 0) + due);
    }
    return [...totals.keys()].sort().map((month) => ({ month, dueTotal: money(totals.get(month)!) }));
  });
  /** Everyone's settled-total added up (not trimmed). */
  protected readonly settledTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + this.settledCentsOf(p), 0)),
  );
  /** Everyone's open-total added up (not trimmed). */
  protected readonly openTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + this.openCentsOf(p), 0)),
  );
  /** Each person's claims that count and are overdue, by person id. */
  private readonly overdueByPerson = computed(() => {
    const today = this.session.today();
    const grace = this.graceOf();
    const map = new Map<number, Claim[]>();
    for (const c of this.live()) {
      if (overdue(c, today, grace(c))) map.set(c.personId, [...(map.get(c.personId) ?? []), c]);
    }
    return map;
  });
  /** How many claims that count are overdue on the sheet. */
  protected readonly overdueCount = computed(() =>
    [...this.overdueByPerson().values()].reduce((n, list) => n + list.length, 0),
  );
  /** Everyone's overdue-total added up, trimmed. */
  protected readonly overdueTotal = computed(() =>
    money(trimmedSum(this.people().map((p) => this.overdueCentsOf(p)))),
  );
  /** Each person's due-total for each period, in cents, by period id then person id: while the
   *  period is open, the dues shown now on their claims whose due-by falls in it and that counted by the
   *  status they had at the end of its last day, as their status logs record it, and the live rules
   *  leave in (a claim with no figure adds nothing); once it is closed or soft-closed, the figures fixed then (a
   *  person added later has none and counts 0.00). */
  protected readonly personPeriodCents = computed(() => {
    const periods = this.periods.periods();
    const grades = new Map(this.people().map((p) => [p.id, p.grades]));
    const totals = new Map<number, Map<number, number>>();
    for (const period of periods) {
      const fixed = Object.entries(period.closed || period.softClosed ? period.closedPersonTotals : {});
      totals.set(period.id, new Map(fixed.map(([id, total]) => [Number(id), cents(total)])));
    }
    const keep = this.liveRulesKeep();
    for (const c of this.claims()) {
      const period = periodOf(periods, c.dueBy);
      const theirs = grades.get(c.personId);
      if (period === null || period.closed || period.softClosed || theirs === undefined) continue;
      const then = statusAsOf(c, period.to);
      if (then === null || !['OPEN', 'PENDING', 'SETTLED'].includes(then) || !keep(c)) continue;
      const rates = this.rates.ofGradeOn(theirs, c.date);
      const byPerson = totals.get(period.id)!;
      const due = claimDueCents(c, rates, this.floor(), this.late()) ?? 0;
      byPerson.set(c.personId, (byPerson.get(c.personId) ?? 0) + due);
    }
    return totals;
  });
  /** Each period's due-total, by period id: the sum of the people's figures for it. */
  protected readonly periodDueTotals = computed(() => {
    const people = new Set(this.people().map((p) => p.id));
    return new Map(
      [...this.personPeriodCents()].map(([id, byPerson]) => [
        id,
        money([...byPerson].reduce((sum, [person, due]) => (people.has(person) ? sum + due : sum), 0)),
      ]),
    );
  });
  /** Per grade: the people not retired on it on the sheet's today, their due-totals added up
   *  (skipping those with no figure), as their rows show them, and how many of them are active. */
  protected readonly gradeSummary = computed(() => {
    const current = this.people().filter((p) => !p.retired);
    return this.grades.map((grade) => {
      const on = current.filter((p) => this.gradeOf(p) === grade);
      const due = on.reduce((sum, p) => sum + (this.dueCentsBy(p) ?? 0), 0);
      const active = on.filter((p) => this.activeIds().has(p.id)).length;
      return { grade, count: on.length, dueTotal: money(due), active };
    });
  });
  /** Per period: the due-total its row shows, the sum of the people's figures for it. */
  protected readonly periodSummary = computed(() => {
    const live = this.periodDueTotals();
    return this.periods.periods().map((p) => ({
      name: p.name,
      dueTotal: live.get(p.id) ?? '0.00',
    }));
  });
  /** Per status: how many of the sheet's claims have it. */
  protected readonly statusSummary = computed(() => {
    const claims = this.claims();
    return ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'].map((status) => ({
      status,
      count: claims.filter((c) => c.status === status).length,
    }));
  });
  /** Per reason on the sheet's list: how many of its VOID claims were voided with it. */
  protected readonly voidReasonSummary = computed(() => {
    const voided = this.claims().filter((c) => c.status === 'VOID');
    return this.voidReasons.reasons().map((r) => ({
      reason: r.reason,
      count: voided.filter((c) => c.voidReason === r.reason).length,
    }));
  });
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  /** The date the grade saved from the editor applies from; blank takes the sheet's today. */
  protected readonly gradeFrom = signal('');
  /** The person's own terms in whole days; blank for none. */
  protected readonly terms = signal('');
  /** The person's contact; blank for none. */
  protected readonly contact = signal('');
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private readonly editingVersion = signal(0);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  /** Whether a quick-add is waiting for its answer; a second one meanwhile is dropped. */
  private readonly quickBusy = signal(false);
  protected readonly quickError = signal<string | null>(null);
  protected readonly importText = signal('');
  /** How many people the last import added; null while it runs or before one. */
  protected readonly importAdded = signal<number | null>(null);
  /** How many people the last import updated; null while it runs or before one. */
  protected readonly importUpdated = signal<number | null>(null);
  /** Whether an import is waiting for its answer; a second one meanwhile is dropped. */
  private readonly importBusy = signal(false);
  protected readonly importError = signal<string | null>(null);
  protected readonly pasteText = signal('');
  /** How many claims the last paste added; null while it runs or before one. */
  protected readonly pasteAdded = signal<number | null>(null);
  /** Whether a paste is waiting for its answer; a second one meanwhile is dropped. */
  private readonly pasteBusy = signal(false);
  protected readonly pasteError = signal<string | null>(null);
  /** The error from the last action on a person's row refused, and the person it belongs to. */
  protected readonly rowError = signal<{ id: number; message: string; testId?: string } | null>(null);
  /** The ids of the claims selected to open or settle together. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** How many claims the last bulk open opened and refused; null while it runs or before one. */
  protected readonly bulkOpenResult = signal<{ opened: number; refused: number } | null>(null);
  /** Why the last bulk open could not be done at all; null when it was. */
  protected readonly bulkOpenError = signal<string | null>(null);
  /** Whether a bulk open is waiting for its answer; a second press meanwhile is dropped. */
  private readonly bulkOpenBusy = signal(false);
  /** How many claims the last bulk settle settled and refused; null while it runs or before one. */
  protected readonly bulkSettleResult = signal<{ settled: number; refused: number } | null>(null);
  /** Why the last bulk settle could not be done at all; null when it was. */
  protected readonly bulkSettleError = signal<string | null>(null);
  /** The number of the sheet's last settle batch; null when there has been none. Shown as of a past
   *  day, the highest batch among the claims as they stood then. */
  protected readonly lastBatch = computed(() => {
    if (this.session.asOf() === null) return this.session.lastBatch();
    const batches = this.claims().flatMap((c) => (c.batch === null ? [] : [c.batch]));
    return batches.length ? Math.max(...batches) : null;
  });
  /** Whether a bulk settle is waiting for its answer; a second press meanwhile is dropped. */
  private readonly bulkSettleBusy = signal(false);
  /** The person (an id, or blank) the selected claims are to move to. */
  protected readonly bulkReassignTo = signal('');
  /** How many claims the last bulk move moved and refused; null while it runs or before one. */
  protected readonly bulkReassignResult = signal<{ reassigned: number; refused: number } | null>(null);
  /** Why the last bulk move could not be done at all; null when it was. */
  protected readonly bulkReassignError = signal<string | null>(null);
  /** Whether a bulk move is waiting for its answer; a second press meanwhile is dropped. */
  private readonly bulkReassignBusy = signal(false);
  /** The ids of the people selected to be given a grade together. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  /** The grade to give the selected people. */
  protected readonly bulkGrade = signal('A');
  /** The date the grade given to the selected people applies from; blank takes the sheet's today. */
  protected readonly bulkGradeFrom = signal('');
  /** How many people the last bulk grade changed; null while it runs or before one. */
  protected readonly bulkGraded = signal<number | null>(null);
  /** Why the last bulk grade could not be done; null when it was. */
  protected readonly bulkGradeError = signal<string | null>(null);
  /** Whether a bulk grade is waiting for its answer; a second press meanwhile is dropped. */
  private readonly bulkGradeBusy = signal(false);
  /** The date the selected people are retired from; blank takes the sheet's today. */
  protected readonly bulkRetireFrom = signal('');
  /** How many people the last bulk retire retired; null while it runs or before one. */
  protected readonly bulkRetired = signal<number | null>(null);
  /** Why the last bulk retire could not be done; null when it was. */
  protected readonly bulkRetireError = signal<string | null>(null);
  /** Whether a bulk retire is waiting for its answer; a second press meanwhile is dropped. */
  private readonly bulkRetireBusy = signal(false);
  /** The date typed to take each person's statement as of (blank: now), by person id. */
  protected readonly statementDates = signal<Record<number, string>>({});
  /** The patron chosen on each person's row (a person id, or blank), by person id. */
  protected readonly patronChoices = signal<Record<number, string>>({});
  /** The alias typed on each person's row, by person id. */
  protected readonly aliasInputs = signal<Record<number, string>>({});
  /** The person chosen on each person's row to merge them into (a person id, or blank), by person id. */
  protected readonly mergeChoices = signal<Record<number, string>>({});
  /** The sheet id typed on each person's row to transfer them to, by person id. */
  protected readonly transferTargets = signal<Record<number, string>>({});

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.usedTags.set([]);
      this.descendantFigures.set(NO_DESCENDANTS);
      this.rowError.set(null);
      this.retireDates.set({});
      this.holdDates.set({});
      this.patronChoices.set({});
      this.aliasInputs.set({});
      this.mergeChoices.set({});
      this.transferTargets.set({});
      this.selectedClaims.set(new Set());
      this.bulkOpenResult.set(null);
      this.bulkOpenError.set(null);
      this.bulkSettleResult.set(null);
      this.bulkSettleError.set(null);
      this.bulkReassignTo.set('');
      this.bulkReassignResult.set(null);
      this.bulkReassignError.set(null);
      this.selectedPeople.set(new Set());
      this.bulkGrade.set('A');
      this.bulkGradeFrom.set('');
      this.bulkGraded.set(null);
      this.bulkGradeError.set(null);
      this.bulkRetireFrom.set('');
      this.bulkRetired.set(null);
      this.bulkRetireError.set(null);
      this.reset();
      this.quickName.set('');
      this.quickAmount.set('');
      this.quickError.set(null);
      this.pasteText.set('');
      this.pasteAdded.set(null);
      this.pasteError.set(null);
      this.importText.set('');
      this.importAdded.set(null);
      this.importUpdated.set(null);
      this.importError.set(null);
      if (sheet) void this.read(sheet, load);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected setRetireDate(personId: number, date: string): void {
    this.retireDates.update((dates) => ({ ...dates, [personId]: date }));
    if (this.rowError()?.id === personId) this.rowError.set(null);
  }

  /** Retire a person from the typed date (blank takes the sheet's today); their row then shows it,
   *  or leaves the list while retired people are not shown. */
  protected async retire(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const from = (this.retireDates()[person.id] ?? '').trim() || null;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/retire`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { from }));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.retireDates.update((dates) => ({ ...dates, [person.id]: '' }));
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be retired.` });
    }
  }

  /** Unretire a retired person on the sheet's today; their row then shows the retirement as a past one.
   *  Anyone not retired is refused on the row. */
  protected async unretire(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/unretire`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, {}));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be unretired: only a retired person can be.` });
    }
  }

  protected setPatronChoice(personId: number, patronId: string): void {
    this.patronChoices.update((choices) => ({ ...choices, [personId]: patronId }));
    if (this.rowError()?.id === personId) this.rowError.set(null);
  }

  /** Name the chosen person as a person's patron; their row then shows them and the backing. */
  protected async addPatron(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const refused = (message: string) =>
      this.rowError.set({ id: person.id, message, testId: 'person-patron-error' });
    const chosen = this.patronChoices()[person.id] ?? '';
    if (!chosen) {
      refused('Choose a patron to add.');
      return;
    }
    const patronId = Number(chosen);
    const patron = this.people().find((p) => p.id === patronId);
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/patrons`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { patronId }));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.patronChoices.update((choices) => ({ ...choices, [person.id]: '' }));
    } catch {
      refused(
        patronId === person.id
          ? `${person.name} cannot be their own patron.`
          : `${patron?.name ?? 'That person'} could not be added as a patron of ${person.name}.`,
      );
    }
  }

  protected setAliasInput(personId: number, alias: string): void {
    this.aliasInputs.update((inputs) => ({ ...inputs, [personId]: alias }));
    if (this.rowError()?.id === personId) this.rowError.set(null);
  }

  /** Give a person the alias typed on their row; refused when blank or already a name or alias on
   *  the sheet. */
  protected async addAlias(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const alias = (this.aliasInputs()[person.id] ?? '').trim();
    if (!alias) {
      this.rowError.set({ id: person.id, message: 'Type an alias to add.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/aliases`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { alias }));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.aliasInputs.update((inputs) => ({ ...inputs, [person.id]: '' }));
    } catch {
      this.rowError.set({
        id: person.id,
        message: `${alias} could not be added as an alias of ${person.name}: it is already a name or alias on the sheet or its tree.`,
      });
    }
  }

  protected setMergeChoice(personId: number, intoId: string): void {
    this.mergeChoices.update((choices) => ({ ...choices, [personId]: intoId }));
    if (this.rowError()?.id === personId) this.rowError.set(null);
  }

  /** Merge a person into the chosen person: their row leaves the list, and the people and claims are
   *  read again, as the merge moves claims, names and patrons to the other. */
  protected async merge(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const chosen = this.mergeChoices()[person.id] ?? '';
    if (!chosen) {
      this.rowError.set({ id: person.id, message: 'Choose a person to merge into.' });
      return;
    }
    const intoId = Number(chosen);
    const into = this.people().find((p) => p.id === intoId);
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/merge`;
    try {
      await firstValueFrom(this.http.post<Person>(url, { intoId }));
    } catch {
      this.rowError.set({
        id: person.id,
        message: `${person.name} could not be merged into ${into?.name ?? 'that person'}.`,
      });
      return;
    }
    if (this.session.sheetId() !== sheet) return;
    this.mergeChoices.update((choices) => ({ ...choices, [person.id]: '' }));
    await this.read(sheet, this.session.loads());
  }

  protected setTransferTarget(personId: number, sheetId: string): void {
    this.transferTargets.update((targets) => ({ ...targets, [personId]: sheetId }));
    if (this.rowError()?.id === personId) this.rowError.set(null);
  }

  /** Transfer a person to the sheet typed: they leave this sheet, so the people and claims are read
   *  again. */
  protected async transfer(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const to = (this.transferTargets()[person.id] ?? '').trim();
    if (!to) {
      this.rowError.set({ id: person.id, message: 'Type a sheet id to transfer to.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/transfer`;
    try {
      await firstValueFrom(this.http.post<void>(url, { toSheetId: to }));
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be transferred to ${to}.` });
      return;
    }
    if (this.session.sheetId() !== sheet) return;
    this.transferTargets.update((targets) => ({ ...targets, [person.id]: '' }));
    await this.read(sheet, this.session.loads());
  }

  /** A person's aliases, as the server sorts them (as names are compared), comma and space separated. */
  protected aliasesOf(person: Person): string {
    return person.aliases.join(', ');
  }

  /** A person's patrons, each as their name and share, sorted by name, comma and space separated. */
  protected patronsOf(person: Person): string {
    const names = new Map(this.people().map((p) => [p.id, p.name]));
    const shares = patronShares(person.patronIds.length);
    return person.patronIds
      .map((id, i) => ({ name: names.get(id), share: shares[i] }))
      .filter((patron): patron is { name: string; share: number } => patron.name !== undefined)
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((patron) => `${patron.name} ${money(patron.share)}`)
      .join(', ');
  }

  /** A person's backing: over their patrons, each patron's settled figure (the due frozen on each of
   *  their SETTLED claims dated within the 90 days up to and including the sheet's today) times their
   *  share divided by 100, added up and rounded half to even; 0.00 with none. */
  protected backingOf(person: Person): string {
    const today = this.session.today();
    if (today === null) return money(0);
    const since = plusDays(today, -89);
    const byPerson = this.claimsByPerson();
    const shares = patronShares(person.patronIds.length);
    // In ten-thousandths of a cent: cents times a share in hundredths of a percent.
    const exact = person.patronIds.reduce((sum, id, i) => {
      const figure = (byPerson.get(id) ?? [])
        .filter((c) => c.status === 'SETTLED' && c.settledDue !== null && since <= c.date && c.date <= today)
        .reduce((total, c) => total + cents(c.settledDue ?? '0'), 0);
      return sum + figure * shares[i];
    }, 0);
    return money(halfEven(exact, 10_000));
  }

  protected setHoldDate(personId: number, field: 'from' | 'to', date: string): void {
    this.holdDates.update((dates) => ({ ...dates, [personId]: { ...dates[personId], [field]: date } }));
    if (this.rowError()?.id === personId) this.rowError.set(null);
  }

  /** Put a hold on a person from the typed from-date (blank takes the sheet's today) to the typed
   *  to-date (blank: it does not end); their row and the totals then show it. */
  protected async hold(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const typed = this.holdDates()[person.id] ?? {};
    const from = (typed.from ?? '').trim() || null;
    const to = (typed.to ?? '').trim() || null;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/hold`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { from, to }));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.holdDates.update((dates) => ({ ...dates, [person.id]: {} }));
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be put on hold.` });
    }
  }

  /** Move a person up or down the list, swapping them with the person above or below; the people
   *  are then read again in their new order. The top person cannot move up nor the bottom one down. */
  protected async move(person: Person, direction: 'up' | 'down'): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/move`;
    try {
      await firstValueFrom(this.http.post<Person>(url, { direction, includeRetired: this.showRetired() }));
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be moved ${direction}.` });
      return;
    }
    if (this.session.sheetId() !== sheet) return;
    await this.read(sheet, this.session.loads());
  }

  /** Start or stop the signed-in user watching a person; their row then shows it. */
  protected async toggleWatch(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/watch`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { watched: !person.watched }));
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
    } catch {
      this.rowError.set({
        id: person.id,
        message: `${person.name} could not be ${person.watched ? 'unwatched' : 'watched'}.`,
      });
    }
  }

  /** Download everyone, retired people included, in list order as CSV: name, the grade in force
   *  on the sheet's today, the retired-from date (or nothing) and the due-total as shown. */
  protected exportCsv(): void {
    this.downloadCsv('people', [
      ['name', 'grade', 'retired-from', 'due-total'],
      ...this.people().map((p) => [p.name, this.gradeOf(p), p.retiredFrom ?? '', this.dueBy(p)]),
    ]);
  }

  /** Download every claim as CSV: people in list order, retired people included, each person's
   *  claims oldest first, with the number, due, status and period as their rows show them. */
  protected exportClaims(): void {
    const periods = this.periods.periods();
    const floor = this.floor();
    const late = this.late();
    this.downloadCsv('claims', [
      ['person', 'number', 'date', 'amount', 'due', 'status', 'period'],
      ...this.people().flatMap((p) =>
        [...this.claimsOf(p.id)].reverse().map((c) => {
          const due = claimDueCents(c, this.rates.ofGradeOn(p.grades, c.date), floor, late);
          return [
            p.name,
            c.number ?? '',
            c.date,
            c.amount,
            due === null ? NO_RATE : money(due),
            c.status,
            periodOf(periods, c.dueBy)?.name ?? '',
          ];
        }),
      ),
    ]);
  }

  /** Download rows as a CSV file named for the sheet: a field holding a quote, comma or line break
   *  is quoted (quotes doubled), and every line ends with a line feed. */
  private downloadCsv(name: string, rows: string[][]): void {
    const field = (v: string) => (/[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
    const text = rows.map((r) => r.map(field).join(',') + '\n').join('');
    const blob = new Blob([text], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${name}-${this.session.sheetId() ?? 'sheet'}.csv`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  /** A person's OPEN, PENDING and SETTLED claims; drafts and voided claims do not count. */
  private countedOf(personId: number): Claim[] {
    return this.countedByPerson().get(personId) ?? [];
  }

  protected claimedBy(personId: number): string {
    return money(this.countedOf(personId).reduce((sum, c) => sum + cents(c.amount), 0));
  }

  /** The grade in force on the sheet's today among a person's grades. */
  protected gradeOf(person: Person): string {
    const today = this.session.today();
    return (today === null ? null : gradeOn(person.grades, today)) ?? person.grade;
  }

  /** The sum of the dues shown on a person's claims that count held to the sheet's cap, or n/a when
   *  the grade in force on the sheet's today has no rate in force then and no claim of theirs shows a
   *  figure. */
  protected dueBy(person: Person): string {
    const due = this.dueCentsBy(person);
    return due === null ? NO_RATE : money(due);
  }

  /** How far the sum of the dues on a person's claims that count is above the sheet's cap; 0.00
   *  when it is not, there is no cap or the due-total is n/a. */
  protected excessOf(person: Person): string {
    const sum = this.uncappedCentsBy(person);
    const cap = this.cap();
    return money(sum === null || cap === null ? 0 : Math.max(sum - cap, 0));
  }

  private dueCentsBy(person: Person): number | null {
    const sum = this.uncappedCentsBy(person);
    const cap = this.cap();
    return sum === null || cap === null ? sum : Math.min(sum, cap);
  }

  private uncappedCentsBy(person: Person): number | null {
    const floor = this.floor();
    const late = this.late();
    const dues = this.countedOf(person.id).map((c) =>
      claimDueCents(c, this.rates.ofGradeOn(person.grades, c.date), floor, late),
    );
    if (!this.rates.current().has(this.gradeOf(person)) && dues.every((due) => due === null)) {
      return null;
    }
    return dues.reduce<number>((sum, due) => sum + (due ?? 0), 0);
  }

  /** The dues of a person's SETTLED claims that count added up, at their frozen figures, corrections
   *  included (a claim with no figure adds nothing). */
  protected settledCentsOf(person: Person): number {
    return this.dueCentsWhere(person, (c) => c.status === 'SETTLED');
  }

  /** The dues of a person's OPEN and PENDING claims that count added up (a claim with no figure adds
   *  nothing). With the settled-total it makes the due-total before the cap. */
  protected openCentsOf(person: Person): number {
    return this.dueCentsWhere(person, isOpen);
  }

  private dueCentsWhere(person: Person, keep: (c: Claim) => boolean): number {
    const floor = this.floor();
    const late = this.late();
    return this.countedOf(person.id)
      .filter(keep)
      .reduce(
        (sum, c) => sum + (claimDueCents(c, this.rates.ofGradeOn(person.grades, c.date), floor, late) ?? 0),
        0,
      );
  }

  protected overdueOf(personId: number): Claim[] {
    return this.overdueByPerson().get(personId) ?? [];
  }

  /** The dues of a person's overdue claims that count added up (a claim with no figure adds nothing). */
  protected overdueTotalOf(person: Person): string {
    return money(this.overdueCentsOf(person));
  }

  /** The dues of a person's OPEN and PENDING claims that count added up by the claim's age on the
   *  sheet's today, the days from its date: 0 to 30 (a claim dated after today too), 31 to 60, and 61
   *  or more (a claim with no figure adds nothing). */
  protected agedOf(person: Person): [string, string, string] {
    const today = this.session.today();
    const floor = this.floor();
    const late = this.late();
    const sums = [0, 0, 0];
    for (const c of this.countedOf(person.id)) {
      if (!isOpen(c)) continue;
      const age = today === null ? 0 : Math.round((Date.parse(today) - Date.parse(c.date)) / 86_400_000);
      const bucket = age <= 30 ? 0 : age <= 60 ? 1 : 2;
      sums[bucket] += claimDueCents(c, this.rates.ofGradeOn(person.grades, c.date), floor, late) ?? 0;
    }
    return [money(sums[0]), money(sums[1]), money(sums[2])];
  }

  private overdueCentsOf(person: Person): number {
    const floor = this.floor();
    const late = this.late();
    return this.overdueOf(person.id).reduce(
      (sum, c) => sum + (claimDueCents(c, this.rates.ofGradeOn(person.grades, c.date), floor, late) ?? 0),
      0,
    );
  }

  /** A claim added (listed first) or saved in place. */
  protected claimSaved(saved: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === saved.id)
        ? list.map((c) => (c.id === saved.id ? saved : c))
        : [saved, ...list],
    );
  }

  /** A claim moved to another person: listed first, as their newest. */
  protected claimMoved(moved: Claim): void {
    this.claims.update((list) => [moved, ...list.filter((c) => c.id !== moved.id)]);
  }

  protected selectClaim(id: number, selected: boolean): void {
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (selected) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Open the selected claims together: each that can be opened is, numbered in list order, and
   *  each that cannot is left as it is; the screen shows how many were opened and refused. */
  protected async openSelected(): Promise<void> {
    if (this.bulkOpenBusy()) return;
    this.bulkOpenResult.set(null);
    this.bulkOpenError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    this.bulkOpenBusy.set(true);
    try {
      const result = await firstValueFrom(
        this.http.post<{ opened: Claim[]; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/open`,
          { claimIds: ids },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const c of result.opened) this.claimSaved(c);
      this.selectedClaims.set(new Set());
      this.bulkOpenResult.set({ opened: result.opened.length, refused: result.refused });
    } catch {
      this.bulkOpenError.set('The selected claims could not be opened.');
    } finally {
      this.bulkOpenBusy.set(false);
    }
  }

  /** Settle the selected claims together, in list order, each by the rules of settling one: a claim
   *  that goes PENDING counts as settled, and each that cannot be settled is left as it is; the
   *  screen shows how many were settled and refused. */
  protected async settleSelected(): Promise<void> {
    if (this.bulkSettleBusy()) return;
    this.bulkSettleResult.set(null);
    this.bulkSettleError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    this.bulkSettleBusy.set(true);
    try {
      const result = await firstValueFrom(
        this.http.post<{ settled: Claim[]; refused: number; batch: number | null }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/settle`,
          { claimIds: ids },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const c of result.settled) this.claimSaved(c);
      this.selectedClaims.set(new Set());
      if (result.batch !== null) this.session.lastBatch.set(result.batch);
      this.bulkSettleResult.set({ settled: result.settled.length, refused: result.refused });
    } catch {
      this.bulkSettleError.set('The selected claims could not be settled.');
    } finally {
      this.bulkSettleBusy.set(false);
    }
  }

  /** Move the selected claims to the chosen person together, in list order, each by the rules of
   *  moving one, and each that cannot be moved left as it is; the screen shows how many were moved
   *  and refused. */
  protected async reassignSelected(): Promise<void> {
    if (this.bulkReassignBusy()) return;
    this.bulkReassignResult.set(null);
    this.bulkReassignError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const to = this.bulkReassignTo();
    if (!to) {
      this.bulkReassignError.set('Choose the person to move the selected claims to.');
      return;
    }
    const ids = [...this.selectedClaims()];
    this.bulkReassignBusy.set(true);
    try {
      const result = await firstValueFrom(
        this.http.post<{ reassigned: Claim[]; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/reassign`,
          { claimIds: ids, toPersonId: Number(to) },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const c of result.reassigned) this.claimMoved(c);
      this.selectedClaims.set(new Set());
      this.bulkReassignResult.set({ reassigned: result.reassigned.length, refused: result.refused });
    } catch {
      this.bulkReassignError.set('The selected claims could not be moved.');
    } finally {
      this.bulkReassignBusy.set(false);
    }
  }

  protected selectPerson(id: number, selected: boolean): void {
    this.selectedPeople.update((ids) => {
      const next = new Set(ids);
      if (selected) next.add(id);
      else next.delete(id);
      return next;
    });
    this.bulkGradeError.set(null);
    this.bulkRetireError.set(null);
  }

  /** Retire the selected people from the typed date (blank takes the sheet's today), in list order,
   *  each as its row's retire would; the screen shows how many were retired. */
  protected async retireSelected(event: Event): Promise<void> {
    event.preventDefault();
    if (this.bulkRetireBusy()) return;
    this.bulkRetired.set(null);
    this.bulkRetireError.set(null);
    this.bulkGradeError.set(null);
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const personIds = [...this.selectedPeople()];
    if (personIds.length === 0) {
      this.bulkRetireError.set('Select the people to retire.');
      return;
    }
    this.bulkRetireBusy.set(true);
    try {
      const retired = await firstValueFrom(
        this.http.post<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people/retire`, {
          personIds,
          from: this.bulkRetireFrom().trim() || null,
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      const saved = new Map(retired.map((p) => [p.id, p]));
      this.people.update((list) => list.map((p) => saved.get(p.id) ?? p));
      this.selectedPeople.set(new Set());
      this.bulkRetireFrom.set('');
      this.bulkRetired.set(retired.length);
    } catch {
      this.bulkRetireError.set('The selected people could not be retired.');
    } finally {
      this.bulkRetireBusy.set(false);
    }
  }

  /** Give the selected people the chosen grade from the typed date (blank takes the sheet's today),
   *  in list order, each as saving them from the editor would; the screen shows how many were changed. */
  protected async gradeSelected(event: Event): Promise<void> {
    event.preventDefault();
    if (this.bulkGradeBusy()) return;
    this.bulkGraded.set(null);
    this.bulkGradeError.set(null);
    this.bulkRetireError.set(null);
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const personIds = [...this.selectedPeople()];
    if (personIds.length === 0) {
      this.bulkGradeError.set('Select the people to grade.');
      return;
    }
    this.bulkGradeBusy.set(true);
    try {
      const graded = await firstValueFrom(
        this.http.post<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people/grade`, {
          personIds,
          grade: this.bulkGrade(),
          from: this.bulkGradeFrom().trim() || null,
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      const saved = new Map(graded.map((p) => [p.id, p]));
      this.people.update((list) => list.map((p) => saved.get(p.id) ?? p));
      this.selectedPeople.set(new Set());
      this.bulkGradeFrom.set('');
      this.bulkGraded.set(graded.length);
    } catch {
      this.bulkGradeError.set('The selected people could not be given the grade.');
    } finally {
      this.bulkGradeBusy.set(false);
    }
  }

  protected setStatementDate(personId: number, date: string): void {
    this.statementDates.update((dates) => ({ ...dates, [personId]: date }));
    if (this.rowError()?.id === personId) this.rowError.set(null);
  }

  /** Take a statement for a person, as of the typed date (blank: now): their row and claims then
   *  show what it recorded and bound. The typed date is cleared whether or not it is taken. */
  protected async takeStatement(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const asOf = (this.statementDates()[person.id] ?? '').trim() || null;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/statement`;
    try {
      const taken = await firstValueFrom(this.http.post<StatementTaken>(url, { asOf }));
      if (this.session.sheetId() !== sheet) return;
      this.statementDates.update((dates) => ({ ...dates, [person.id]: '' }));
      const bound = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => [
        ...taken.claims.filter((c) => !list.some((had) => had.id === c.id)),
        ...list.map((c) => bound.get(c.id) ?? c),
      ].sort((a, b) => b.id - a.id));
      const { count, lastDueTotal, lastDate } = taken.statements;
      this.people.update((list) =>
        list.map((p) =>
          p.id === person.id
            ? { ...p, statementCount: count, statedTotal: lastDueTotal, statementDate: lastDate }
            : p,
        ),
      );
    } catch {
      // The typed date was for this statement only: the next one is taken now unless a date is typed again.
      this.statementDates.update((dates) => ({ ...dates, [person.id]: '' }));
      this.rowError.set({ id: person.id, message: `A statement could not be taken for ${person.name}.` });
    }
  }

  /** Cancel a person's last statement: their row shows the one before it, and their claims are live again. */
  protected async cancelStatement(person: Person): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/statement`;
    try {
      const cancelled = await firstValueFrom(this.http.delete<StatementTaken>(url));
      if (this.session.sheetId() !== sheet) return;
      const live = new Map(cancelled.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => live.get(c.id) ?? c));
      const { count, lastDueTotal, lastDate } = cancelled.statements;
      this.people.update((list) =>
        list.map((p) =>
          p.id === person.id
            ? { ...p, statementCount: count, statedTotal: lastDueTotal, statementDate: lastDate }
            : p,
        ),
      );
    } catch {
      this.rowError.set({ id: person.id, message: `The last statement for ${person.name} could not be cancelled.` });
    }
  }

  protected edit(person: Person): void {
    this.rowError.set(null);
    this.editingId.set(person.id);
    this.editingVersion.set(person.version);
    this.name.set(person.name);
    this.grade.set(this.gradeOf(person));
    this.gradeFrom.set('');
    this.terms.set(person.terms === null ? '' : String(person.terms));
    this.contact.set(person.contact ?? '');
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set('');
    this.terms.set('');
    this.contact.set('');
    this.error.set(null);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const terms = this.terms().trim();
    const contact = this.contact().trim().toLowerCase();
    const body = {
      name: this.name().trim(),
      grade: this.grade(),
      terms: terms === '' ? null : Number(terms),
      contact: contact === '' ? null : contact,
    };
    if (!body.name) {
      this.error.set('Type a name for the person.');
      return;
    }
    if (terms !== '' && !/^\d{1,5}$/.test(terms)) {
      // Nothing is saved: the editor closes, leaving the form ready for the next person.
      this.reset();
      this.error.set('Terms are a whole number of days, or blank for none; nothing was saved.');
      return;
    }
    const id = this.editingId();
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    this.busy.set(true);
    try {
      const saved = await firstValueFrom(
        id === null
          ? this.http.post<Person>(base, body)
          : this.http.put<Person>(`${base}/${id}`, {
              ...body,
              gradeFrom: this.gradeFrom().trim() || null,
              version: this.editingVersion(),
            }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) =>
        id === null ? [...list, saved] : list.map((p) => (p.id === saved.id ? saved : p)),
      );
      this.reset();
    } catch (e) {
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(sheet, id, e.error as Person | null);
        this.error.set('Someone else saved this person since you opened the editor; their values are shown.');
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set(
          `${body.name} is already a name or alias on the sheet or its tree (or one of this person's aliases is), or another person has that contact.`,
        );
      } else {
        this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    } finally {
      this.busy.set(false);
    }
  }

  /** Add a DRAFT claim for the named person, adding them (grade A) when the name is not on the
   *  sheet; their row and the claim then show. */
  protected async quickAdd(event: Event): Promise<void> {
    event.preventDefault();
    if (this.quickBusy()) return;
    this.quickError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.quickName().trim();
    const amount = this.quickAmount().trim();
    if (!name) {
      this.quickError.set('Type a name for the claim.');
      return;
    }
    if (!AMOUNT.test(amount)) {
      this.quickError.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    this.quickBusy.set(true);
    try {
      const added = await firstValueFrom(
        this.http.post<{ person: Person; claim: Claim }>(
          `/api/sheets/${encodeURIComponent(sheet)}/quick-add`,
          { name, amount },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      this.people.update((list) =>
        list.some((p) => p.id === added.person.id)
          ? list.map((p) => (p.id === added.person.id ? added.person : p))
          : [...list, added.person],
      );
      this.claimSaved(added.claim);
      this.quickName.set('');
      this.quickAmount.set('');
    } catch (e) {
      this.quickError.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? `A claim for ${name} could not be added: its date falls in a closed period or after they retired.`
          : `A claim for ${name} could not be added.`,
      );
    } finally {
      this.quickBusy.set(false);
    }
  }

  /** Add a person per pasted line of name,grade,terms,contact, each as the person form would, or update
   *  the person on the sheet the line's name matches; when any line is refused nothing is imported. The
   *  added and updated people then show, and how many of each there were. */
  protected async importPeople(event: Event): Promise<void> {
    event.preventDefault();
    if (this.importBusy()) return;
    this.importError.set(null);
    this.importAdded.set(null);
    this.importUpdated.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = this.importText();
    if (!text.trim()) {
      this.importError.set('Paste at least one line of name,grade,terms,contact.');
      return;
    }
    this.importBusy.set(true);
    try {
      const result = await firstValueFrom(
        this.http.post<{ added: Person[]; updated: Person[] }>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/import`,
          { text },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      const updated = new Map(result.updated.map((p) => [p.id, p]));
      this.people.update((list) => [...list.map((p) => updated.get(p.id) ?? p), ...result.added]);
      this.importAdded.set(result.added.length);
      this.importUpdated.set(result.updated.length);
      this.importText.set('');
    } catch {
      this.importError.set(
        'The people could not be imported: every line needs a name that is a person on the sheet (to update ' +
          'them) or not already a name or alias on the sheet or its tree, a grade of A, B or C, terms of a whole number of days (or blank) and a contact no one ' +
          'else has (or blank); no name or contact may be repeated in the paste.',
      );
    } finally {
      this.importBusy.set(false);
    }
  }

  /** Add a DRAFT claim per pasted line of name,amount,date,note, for people on the sheet; when any
   *  line is refused nothing is added. The added claims then show, and how many were. */
  protected async pasteClaims(event: Event): Promise<void> {
    event.preventDefault();
    if (this.pasteBusy()) return;
    this.pasteError.set(null);
    this.pasteAdded.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = this.pasteText();
    if (!text.trim()) {
      this.pasteError.set('Paste at least one line of name,amount,date,note.');
      return;
    }
    this.pasteBusy.set(true);
    try {
      const result = await firstValueFrom(
        this.http.post<{ added: Claim[] }>(`/api/sheets/${encodeURIComponent(sheet)}/claims/paste`, { text }),
      );
      if (this.session.sheetId() !== sheet) return;
      for (const c of result.added) this.claimSaved(c);
      this.pasteAdded.set(result.added.length);
      this.pasteText.set('');
    } catch {
      this.pasteError.set(
        'The claims could not be added: every line needs a name on the sheet, an amount of 0.00 or more ' +
          'with up to two decimals, and a date (or blank) outside a closed period and before the person retired; ' +
          'a reference given as " #<reference>" at the end of a note must not already be used on the sheet.',
      );
    } finally {
      this.pasteBusy.set(false);
    }
  }

  /** After a refused stale save, show the person as stored now, in their row and in the editor,
   *  which then holds the version it shows. */
  private async showCurrent(sheet: string, id: number, current: Person | null): Promise<void> {
    if (!current) {
      const list = await firstValueFrom(
        this.http.get<Person[]>(`/api/sheets/${encodeURIComponent(sheet)}/people`),
      ).catch(() => []);
      current = list.find((p) => p.id === id) ?? null;
    }
    if (!current || this.session.sheetId() !== sheet) return;
    const now = current;
    this.people.update((list) => list.map((p) => (p.id === now.id ? now : p)));
    if (this.editingId() === now.id) {
      this.editingVersion.set(now.version);
      this.name.set(now.name);
      this.grade.set(this.gradeOf(now));
    }
  }

  /** Read the sheet's people and claims; a response for an earlier load is dropped. */
  private async read(sheet: string, load: number): Promise<void> {
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    const [list, claims, tags, descendants] = await Promise.all([
      firstValueFrom(this.http.get<Person[]>(`${base}/people`)),
      firstValueFrom(this.http.get<Claim[]>(`${base}/claims`)),
      firstValueFrom(this.http.get<string[]>(`${base}/claim-tags`)),
      firstValueFrom(this.http.get<DescendantFigures>(`${base}/descendant-figures`)),
    ]);
    if (this.session.loads() !== load) return;
    this.people.set(list);
    this.claims.set(claims);
    this.usedTags.set(tags);
    this.descendantFigures.set(descendants);
  }
}

/** The figures added up, leaving out the single highest and the single lowest when there are three
 *  or more. */
/** The patrons' shares in hundredths, in the order they were named: 100.00 split equally, each rounded
 *  half to even, the remainder (100.00 minus the shares added up) to the first-named patron. */
function patronShares(count: number): number[] {
  if (count === 0) return [];
  const shares = Array.from({ length: count }, () => halfEven(10_000, count));
  shares[0] += 10_000 - shares.reduce((sum, share) => sum + share, 0);
  return shares;
}

/** A whole number divided by a positive whole number, rounded half to even. */
function halfEven(numerator: number, divisor: number): number {
  const whole = Math.floor(numerator / divisor);
  const rest = numerator - whole * divisor;
  return 2 * rest > divisor || (2 * rest === divisor && whole % 2 !== 0) ? whole + 1 : whole;
}

function trimmedSum(figures: number[]): number {
  const sum = figures.reduce((total, figure) => total + figure, 0);
  return figures.length < 3 ? sum : sum - Math.max(...figures) - Math.min(...figures);
}
