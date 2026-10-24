import { HttpErrorResponse } from '@angular/common/http';
import { Component, DOCUMENT, computed, effect, inject, signal, type WritableSignal, untracked } from '@angular/core';
import { Session } from '../../core/session';
import { Claims } from './claims';
import { Delegations } from './delegations';
import { Holidays } from './holidays';
import { Notes } from './notes';
import { Header } from './header';
import { Claim, ClaimStatus, ClaimsService, cents, counts, deferred, isCorrection, isOpen, money, overdue, statusOn } from './claims.service';
import { DescendantFigures, GRADES, Grade, Patron, PeopleService, Person, Snapshot, gradeOn, heldOn } from './people.service';
import { Periods } from './periods';
import { Period, PeriodsService, periodOn } from './periods.service';
import { Rates } from './rates';
import { Graces, Lateness, Rate, RatesService, Rounding, claimShownCents, overdueFrom, rateOn } from './rates.service';
import { Settings } from './settings';
import { Dated, SettingsService, inForce } from './settings.service';
import { Statement, StatementsService } from './statements.service';
import { Summary, SummaryPerson } from './summary';
import { Dashboard, DashboardCounts, NO_DASHBOARD } from './dashboard';
import { Templates } from './templates';
import { TreeSearch } from './tree-search';
import { VoidReasons } from './void-reasons';
import { Pools } from './pools';
import { Crews } from './crews';
import { CrewsService, crewDueCents } from './crews.service';
import { Plans } from './plans';
import { Plan, PlansService } from './plans.service';
import { PoolsService } from './pools.service';
import { Audit } from './audit';
import { FigurePipe } from '../../core/figure.pipe';

// The people on the active sheet, in list order (each has a position; new people go to the end, and
// the up and down buttons on a row swap the person with their neighbour), and the form that adds a person
// or, once a person's editor is opened, saves changes to them. Each person's row lists their claims
// and shows what they have claimed (their OPEN, PENDING and SETTLED claims, corrections left out) and what is due on it: the sum of
// those claims' due figures; the sheet's
// claimed-total and due-total add up everyone's (a due-total that is n/a is skipped). A statement
// taken of a person binds each of their counting claims to the figure it shows then; the row shows
// how many statements they have had and the last one's due-total and date. A person can be retired
// from a date; retired people are left out of the list unless "show retired" is on, but their claims
// keep counting in every total. A retired person can be unretired on the sheet's today: the retirement
// is kept on their row as a past one, and no claim can be dated after its from-date and before its
// to-date. A person can be put on several holds over time, each from a date (the
// sheet's today when left blank) and to an optional date: their OPEN and PENDING claims dated within a
// hold count in no live total (theirs, the sheet's or a period's) unless a statement binds them;
// SETTLED ones and those dated outside every hold still do. A hold ends on its to-date.
// A claim is due by the date fixed when it was added (see the claims); an OPEN or PENDING one due by a date before the sheet's
// today is overdue. Each row, and the sheet, shows how many of the claims that count are overdue and
// what is due on them. An overdue claim's due gains, for every full 30 days it is overdue, the sheet's
// late percent in force on that boundary date of its figure, each rounded like the claim; a statement never binds that charge, settling fixes it in. The floor is
// applied last: the live due is the greater of the floor in force on the claim's date and its figure plus that charge.
// A person can name other people on the sheet as their patrons, one at a time from their row; the
// patrons carry shares: 100.00 split equally in the order they were named, each rounded half to even,
// the remainder to the first named. The row shows each patron as "<name> <share>", sorted by name, and
// their backing: over their patrons, the figures of the patron's SETTLED claims dated within the 90 days
// up to and including the sheet's today, each at its frozen figure, added up, times the patron's share
// divided by 100; the sum rounded half to even (0.00 with none). A person's due-total is at most the sheet's cap: when the
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
  imports: [FigurePipe, Audit, Claims, Crews, Delegations, Header, Holidays, Notes, Periods, Plans, Pools, Rates, Settings, Summary, Dashboard, Templates, TreeSearch, VoidReasons],
  template: `
    <app-header
      [peopleCount]="people().length"
      [claims]="claims()"
      [dueTotal]="dueTotal()"
      [overdueTotal]="money(overdueTotal().cents)"
      [myDueTotal]="myFigures()?.dueTotal ?? ''"
      [myOverdueTotal]="myFigures()?.overdueTotal ?? ''"
      [myCrewDueTotal]="myCrewDueTotal()"
    />
    <section aria-label="Consolidated figures" data-testid="consolidated" class="consolidated">
      <span>Over this sheet and the sheets below it:</span>
      <span>People <strong data-testid="consolidated-people-count">{{ (consolidated().peopleCount) | figure }}</strong></span>
      <span>Due <strong data-testid="consolidated-due-total">{{ (consolidated().dueTotal) | figure }}</strong></span>
      <span>Overdue <strong data-testid="consolidated-overdue-total">{{ (consolidated().overdueTotal) | figure }}</strong></span>
    </section>
    <app-settings
      (floorSaved)="floors.set($event)"
      (capSaved)="caps.set($event)"
      (latePctSaved)="latePcts.set($event)"
      (graceSaved)="graces.set($event)"
      (policyDateSaved)="policyDate.set($event)"
      (roundingSaved)="roundings.set($event)"
      (fiscalStartMonthSaved)="fiscalStartMonth.set($event)"
    />
    <app-delegations />
    <app-holidays (added)="holidayAdded()" />
    <app-notes />
    <app-void-reasons />
    <app-pools [people]="people()" />
    <app-crews [people]="people()" [dueCents]="dueCentsById()" />
    <app-templates />
    <app-tree-search />
    <app-audit />
    <app-rates [rates]="rates()" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotals]="periodDueTotals()" [fiscalStartMonth]="fiscalStartMonth()" (added)="periodAdded($event)" (closed)="periodClosed($event)" (reopened)="periodClosed($event)" (reloaded)="periods.set($event)" />
    <app-dashboard [counts]="dashboard()" />
    <app-summary
      [people]="summaryPeople()"
      [periods]="periods()"
      [periodDueTotals]="periodDueTotals()"
      [classicPeriodDueTotals]="classicPeriodDueTotals()"
      [classicDueTotal]="classicDueTotal()"
      [claims]="claims()"
      [tagDueTotals]="tagDueTotals()"
      [monthDueTotals]="monthDueTotals()"
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
            <option [value]="g" [selected]="g === grade()">{{ (g) | figure }}</option>
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
        <label for="person-form-terms-from">Terms from</label>
        <input
          id="person-form-terms-from"
          type="date"
          data-testid="person-form-terms-from"
          [value]="termsFrom()"
          (input)="termsFrom.set(value($event)); error.set(null)"
        />
        <label for="person-form-contact">Contact</label>
        <input
          id="person-form-contact"
          data-testid="person-form-contact"
          [value]="contact()"
          (input)="contact.set(value($event)); error.set(null)"
        />
        <label for="person-form-tags">Tags (comma separated)</label>
        <input
          id="person-form-tags"
          data-testid="person-form-tags"
          [value]="tags()"
          (input)="tags.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="person-form-submit">
          {{ (editingId() === null ? 'Add person' : 'Save person') | figure }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="person-form-error">{{ (message) | figure }}</p>
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
          <p role="alert" class="error" data-testid="quick-error">{{ (message) | figure }}</p>
        }
      </form>

      <form (submit)="importPeople($event)" aria-label="Import people" data-testid="import-form">
        <label for="import-people">People to import, one per line: name,grade,terms,contact (terms and contact may be left out)</label>
        <textarea
          id="import-people"
          data-testid="import-people"
          rows="4"
          [value]="importText()"
          (input)="importText.set(value($event)); importError.set(null)"
        ></textarea>
        <button type="submit" data-testid="import-submit">Import people</button>
        @if (importAdded() !== null) {
          Added <span data-testid="import-added">{{ (importAdded()) | figure }}</span>
        }
        @if (importUpdated() !== null) {
          Updated <span data-testid="import-updated">{{ (importUpdated()) | figure }}</span>
        }
        @if (importError(); as message) {
          <p role="alert" class="error" data-testid="import-error">{{ (message) | figure }}</p>
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
          Pasted and opened <span data-testid="paste-added">{{ (pasteAdded()) | figure }}</span>
        }
        @if (pasteError(); as message) {
          <p role="alert" class="error" data-testid="paste-error">{{ (message) | figure }}</p>
        }
      </form>

      <div class="generate">
        <button type="button" data-testid="plan-generate" (click)="generatePlans()">Generate plan claims</button>
        @if (planGenerated() !== null) {
          Made <span data-testid="plan-generated-count">{{ (planGenerated()) | figure }}</span>
        }
        @if (planGenerateError(); as message) {
          <p role="alert" class="error" data-testid="plan-generate-error">{{ (message) | figure }}</p>
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
        @for (c of columns; track c.key) {
          <label>
            <input
              type="checkbox"
              [attr.data-testid]="'column-' + c.key"
              [checked]="shows(c.key)"
              (change)="showColumn(c.key, checked($event))"
            />
            {{ (c.label) | figure }}
          </label>
        }
      </fieldset>
      <label for="sort-by">Sort people by</label>
      <select id="sort-by" data-testid="sort-by" (change)="sortKey.set(sortKeyOf($event))">
        @for (k of sortKeys; track k) {
          <option [value]="k" [selected]="sortKey() === k">{{ (k) | figure }}</option>
        }
      </select>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <button type="button" data-testid="export-claims" (click)="exportClaims()">Export claims (CSV)</button>

      <p>
        <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
        @if (bulkOpened(); as result) {
          Opened <span data-testid="bulk-opened">{{ (result.opened) | figure }}</span>,
          refused <span data-testid="bulk-refused">{{ (result.refused) | figure }}</span>
        }
      </p>

      <p>
        <button type="button" data-testid="bulk-settle" (click)="bulkSettle()">Settle selected claims</button>
        Last batch <span data-testid="bulk-settle-batch">{{ (session.lastSettleBatch() ?? '') | figure }}</span>
        @if (bulkSettled(); as result) {
          Settled <span data-testid="bulk-settled">{{ (result.settled) | figure }}</span>,
          refused <span data-testid="bulk-settle-refused">{{ (result.refused) | figure }}</span>
        }
      </p>

      <p>
        <label for="bulk-reassign-to">Move selected claims to</label>
        <select
          id="bulk-reassign-to"
          data-testid="bulk-reassign-to"
          (change)="bulkReassignTo.set(value($event))"
        >
          <option value="" [selected]="!bulkReassignTo()">Choose a person</option>
          @for (p of people(); track p.id) {
            <option [value]="p.id" [selected]="bulkReassignTo() === '' + p.id">{{ (p.name) | figure }}</option>
          }
        </select>
        <button type="button" data-testid="bulk-reassign" (click)="bulkReassign()">Move selected claims</button>
        @if (bulkReassigned(); as result) {
          Moved <span data-testid="bulk-reassigned">{{ (result.reassigned) | figure }}</span>,
          refused <span data-testid="bulk-reassign-refused">{{ (result.refused) | figure }}</span>
        }
      </p>

      <p>
        <label for="bulk-redate-date">Re-date selected claims to</label>
        <input
          id="bulk-redate-date"
          data-testid="bulk-redate-date"
          placeholder="YYYY-MM-DD"
          [value]="bulkRedateDate()"
          (input)="bulkRedateDate.set(value($event))"
        />
        <button type="button" data-testid="bulk-redate" (click)="bulkRedate()">Re-date selected claims</button>
        @if (bulkRedated(); as result) {
          Re-dated <span data-testid="bulk-redated">{{ (result.redated) | figure }}</span>,
          refused <span data-testid="bulk-redate-refused">{{ (result.refused) | figure }}</span>
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
            <option [value]="g" [selected]="bulkGradeChoice() === g">{{ (g) | figure }}</option>
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
          Changed <span data-testid="bulk-graded">{{ (bulkGraded()) | figure }}</span>
        }
        @if (bulkGradeError(); as message) {
          <span role="alert" class="error" data-testid="person-error">{{ (message) | figure }}</span>
        }
      </p>

      <p>
        <label for="bulk-retire-date">Retire selected people from</label>
        <input
          id="bulk-retire-date"
          data-testid="bulk-retire-date"
          placeholder="YYYY-MM-DD"
          [value]="bulkRetireFrom()"
          (input)="bulkRetireFrom.set(value($event)); bulkRetireError.set(null)"
        />
        <button type="button" data-testid="bulk-retire" (click)="bulkRetire()">Retire selected people</button>
        @if (bulkRetired() !== null) {
          Retired <span data-testid="bulk-retired">{{ (bulkRetired()) | figure }}</span>
        }
        @if (bulkRetireError(); as message) {
          <span role="alert" class="error" data-testid="person-error">{{ (message) | figure }}</span>
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
              @if (shows('terms')) {
                <th scope="col">Terms</th>
              }
              @if (shows('contact')) {
                <th scope="col">Contact</th>
              }
              <th scope="col">Tags</th>
              @if (shows('aliases')) {
                <th scope="col">Aliases</th>
              }
              <th scope="col">Clashing</th>
              <th scope="col">Came from</th>
              @if (shows('claimed')) {
                <th scope="col">Claimed</th>
              }
              @if (shows('due-total')) {
                <th scope="col">Due</th>
              }
              <th scope="col">Settled due</th>
              <th scope="col">Open due</th>
              @for (pd of periods(); track pd.id) {
                <th scope="col">Due in {{ (pd.name) | figure }}</th>
                <th scope="col">Carried into {{ (pd.name) | figure }}</th>
              }
              @if (shows('excess')) {
                <th scope="col">Excess</th>
              }
              <th scope="col">Cut total</th>
              @if (shows('overdue-count')) {
                <th scope="col">Overdue</th>
              }
              @if (shows('overdue-total')) {
                <th scope="col">Overdue due</th>
              }
              <th scope="col">Due aged 0–30 days</th>
              <th scope="col">Due aged 31–60 days</th>
              <th scope="col">Due aged 61+ days</th>
              @if (shows('statement-count')) {
                <th scope="col">Statements</th>
              }
              @if (shows('stated-total')) {
                <th scope="col">Last stated</th>
              }
              @if (shows('statement-date')) {
                <th scope="col">Stated on</th>
              }
              @if (shows('retired')) {
                <th scope="col">Retired</th>
              }
              <th scope="col">Retired from</th>
              <th scope="col">Flag</th>
              @if (shows('hold')) {
                <th scope="col">On hold</th>
              }
              <th scope="col">Active</th>
              <th scope="col">Watched</th>
              @if (shows('patrons')) {
                <th scope="col">Patrons</th>
              }
              <th scope="col">Snapshots</th>
              <th scope="col">Statement lines</th>
              @if (shows('backing')) {
                <th scope="col">Backing</th>
              }
              <th scope="col">Claims</th>
              <th scope="col">Plans</th>
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
                <td data-testid="person-name">{{ (p.name) | figure }}</td>
                <td data-testid="person-grade">{{ (gradeNow(p)) | figure }}</td>
                @if (shows('terms')) {
                  <td data-testid="person-terms">{{ (p.terms ?? '') | figure }}</td>
                }
                @if (shows('contact')) {
                  <td data-testid="person-contact">{{ (p.contact ?? '') | figure }}</td>
                }
                <td data-testid="person-tags">{{ (p.tags.join(', ')) | figure }}</td>
                @if (shows('aliases')) {
                  <td data-testid="person-aliases">{{ (p.aliases.join(', ')) | figure }}</td>
                }
                <td data-testid="person-clashing">{{ (p.clashing ? 'yes' : 'no') | figure }}</td>
                <td data-testid="person-origin">{{ (p.origin ?? '') | figure }}</td>
                @if (shows('claimed')) {
                  <td data-testid="person-claimed">{{ (claimedOf(p)) | figure }}</td>
                }
                @if (shows('due-total')) {
                  <td data-testid="person-due-total">{{ (dueTotalOf(p) ?? 'n/a') | figure }}</td>
                }
                @let split = splitOf(p);
                <td data-testid="person-settled-total">{{ (money(split.settled)) | figure }}</td>
                <td data-testid="person-open-total">{{ (money(split.open)) | figure }}</td>
                @for (pd of periods(); track pd.id) {
                  <td [attr.data-testid]="'person-period-' + pd.name + '-due-total'">{{ (money(personPeriodCents().get(p.id)?.[pd.name] ?? 0)) | figure }}</td>
                  <td [attr.data-testid]="'person-period-' + pd.name + '-carried'">{{ (money(personCarriedCents().get(p.id)?.[pd.name] ?? 0)) | figure }}</td>
                }
                @if (shows('excess')) {
                  <td data-testid="person-excess">{{ (excessOf(p)) | figure }}</td>
                }
                <td data-testid="person-cut-total">{{ (money(cutCents().get(p.id) ?? 0)) | figure }}</td>
                @if (shows('overdue-count')) {
                  <td data-testid="person-overdue-count">{{ (overdueOf(p).count) | figure }}</td>
                }
                @if (shows('overdue-total')) {
                  <td data-testid="person-overdue-total">{{ (money(overdueOf(p).cents)) | figure }}</td>
                }
                @let aged = agedOf(p);
                <td data-testid="person-aged-0-30">{{ (money(aged[0])) | figure }}</td>
                <td data-testid="person-aged-31-60">{{ (money(aged[1])) | figure }}</td>
                <td data-testid="person-aged-61">{{ (money(aged[2])) | figure }}</td>
                @if (shows('statement-count')) {
                  <td data-testid="person-statement-count">{{ (statementsOf(p.id).length) | figure }}</td>
                }
                @if (shows('stated-total')) {
                  <td data-testid="person-stated-total">{{ (lastStatementOf(p.id)?.dueTotal ?? '') | figure }}</td>
                }
                @if (shows('statement-date')) {
                  <td data-testid="person-statement-date">{{ (lastStatementOf(p.id)?.date ?? '') | figure }}</td>
                }
                @if (shows('retired')) {
                  <td data-testid="person-retired">{{ (p.retired ? 'yes' : 'no') | figure }}</td>
                }
                <td data-testid="person-retired-from">{{ (p.retiredFrom ?? '') | figure }}</td>
                <td data-testid="person-flag-shown">{{ (p.flag ?? '') | figure }}</td>
                @if (shows('hold')) {
                  <td data-testid="person-hold">{{ (p.held ? 'yes' : 'no') | figure }}</td>
                }
                <td data-testid="person-active">{{ (isActive(p) ? 'yes' : 'no') | figure }}</td>
                <td data-testid="person-watched">{{ (watched().has(p.id) ? 'yes' : 'no') | figure }}</td>
                @if (shows('patrons')) {
                  <td data-testid="person-patrons">{{ (patronNamesOf(p.id)) | figure }}</td>
                }
                <td>
                  @for (line of snapshotLinesOf(p.id); track $index) {
                    <div data-testid="person-snapshot-line">{{ (line) | figure }}</div>
                  }
                </td>
                <td>
                  @for (s of statementLinesOf(p.id); track s.id) {
                    <div data-testid="person-statement-line-line">{{ (s.date + ' ' + s.dueTotal) | figure }}</div>
                  }
                </td>
                @if (shows('backing')) {
                  <td data-testid="person-backing">{{ (backingOf(p.id)) | figure }}</td>
                }
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
                    [roundings]="roundings()"
                    [others]="othersOf(p.id)"
                    [selected]="selectedClaims()"
                    (selectToggled)="selectClaim($event.id, $event.selected)"
                    (saved)="claimSaved($event)"
                  />
                </td>
                <td>
                  <app-plans [personId]="p.id" [personName]="p.name" [plans]="plansOf(p.id)" (added)="planAdded($event)" />
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
                  <button
                    type="button"
                    data-testid="person-move-up"
                    [attr.aria-label]="'Move ' + p.name + ' up'"
                    (click)="move(p, 'up')"
                  >
                    Up
                  </button>
                  <button
                    type="button"
                    data-testid="person-move-down"
                    [attr.aria-label]="'Move ' + p.name + ' down'"
                    (click)="move(p, 'down')"
                  >
                    Down
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
                  <label [for]="'person-inline-grade-' + p.id">Grade from today</label>
                  <select
                    [id]="'person-inline-grade-' + p.id"
                    data-testid="person-inline-grade"
                    [value]="inlineGrades()[p.id] ?? gradeNow(p)"
                    (change)="setInlineGrade(p.id, value($event))"
                  >
                    @for (g of grades; track g) {
                      <option [value]="g" [selected]="g === (inlineGrades()[p.id] ?? gradeNow(p))">{{ (g) | figure }}</option>
                    }
                  </select>
                  <button
                    type="button"
                    data-testid="person-inline-grade-save"
                    [attr.aria-label]="'Save the grade of ' + p.name"
                    (click)="saveInlineGrade(p)"
                  >
                    Save grade
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
                  <button
                    type="button"
                    data-testid="person-unretire"
                    [attr.aria-label]="'Unretire ' + p.name"
                    (click)="unretire(p)"
                  >
                    Unretire
                  </button>
                  <ul class="retirements">
                    @for (r of p.retirements; track $index) {
                      <li data-testid="person-retirement-line">{{ (r.from) | figure }} to {{ (r.to) | figure }}</li>
                    }
                  </ul>
                  <ul class="holds">
                    @for (h of p.holds; track $index) {
                      <li data-testid="person-hold-line">held from {{ (h.from) | figure }}{{ (h.to ? ' to ' + h.to : '') | figure }}</li>
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
                  <label [for]="'person-flag-reason-' + p.id">Flag reason</label>
                  <input
                    [id]="'person-flag-reason-' + p.id"
                    data-testid="person-flag-reason"
                    [value]="flagReasons()[p.id] ?? ''"
                    (input)="setHoldDate(flagReasons, p.id, value($event))"
                  />
                  <label [for]="'person-flag-until-' + p.id">Flag until</label>
                  <input
                    [id]="'person-flag-until-' + p.id"
                    data-testid="person-flag-until"
                    placeholder="YYYY-MM-DD"
                    [value]="flagUntils()[p.id] ?? ''"
                    (input)="setHoldDate(flagUntils, p.id, value($event))"
                  />
                  <button
                    type="button"
                    data-testid="person-flag"
                    [attr.aria-label]="'Flag ' + p.name"
                    (click)="flag(p)"
                  >
                    Flag
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
                  <button
                    type="button"
                    data-testid="person-watch"
                    [attr.aria-label]="(watched().has(p.id) ? 'Stop watching ' : 'Watch ') + p.name"
                    [attr.aria-pressed]="watched().has(p.id)"
                    (click)="toggleWatch(p)"
                  >
                    {{ (watched().has(p.id) ? 'Unwatch' : 'Watch') | figure }}
                  </button>
                  @if (rowErrors()[p.id]; as message) {
                    <p role="alert" class="error" data-testid="person-error">{{ (message) | figure }}</p>
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
                        <option [value]="o.id" [selected]="patronChoices()[p.id] === '' + o.id">{{ (o.name) | figure }}</option>
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
                    <p role="alert" class="error" data-testid="person-patron-error">{{ (message) | figure }}</p>
                  }
                  <label [for]="'person-merge-into-' + p.id">Merge into</label>
                  <select
                    [id]="'person-merge-into-' + p.id"
                    data-testid="person-merge-into"
                    [value]="mergeChoices()[p.id] ?? ''"
                    (change)="setMergeChoice(p.id, value($event))"
                  >
                    <option value="" [selected]="!mergeChoices()[p.id]">Choose a person</option>
                    @for (o of people(); track o.id) {
                      <option [value]="o.id" [selected]="mergeChoices()[p.id] === '' + o.id">{{ (o.name) | figure }}</option>
                    }
                  </select>
                  <button
                    type="button"
                    data-testid="person-merge"
                    [attr.aria-label]="'Merge ' + p.name + ' into the person chosen'"
                    (click)="merge(p)"
                  >
                    Merge
                  </button>
                  <label [for]="'person-transfer-to-' + p.id">Transfer to sheet</label>
                  <input
                    [id]="'person-transfer-to-' + p.id"
                    data-testid="person-transfer-to"
                    [value]="transferInputs()[p.id] ?? ''"
                    (input)="setTransferInput(p.id, value($event))"
                  />
                  <button
                    type="button"
                    data-testid="person-transfer"
                    [attr.aria-label]="'Transfer ' + p.name + ' to the sheet given'"
                    (click)="transfer(p)"
                  >
                    Transfer
                  </button>
                  <button
                    type="button"
                    data-testid="person-transfer-undo"
                    [attr.aria-label]="'Undo the transfer that brought ' + p.name + ' here'"
                    (click)="transferUndo(p)"
                  >
                    Undo transfer
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
      <p>
        Claimed in total: <span data-testid="rollup-claimed-total">{{ (claimedTotal()) | figure }}</span>
      </p>
      <p>
        Due in total: <span data-testid="rollup-due-total">{{ (dueTotal()) | figure }}</span>
      </p>
      <p>
        Settled due in total: <span data-testid="rollup-settled-total">{{ (money(splitTotal().settled)) | figure }}</span>,
        open due in total: <span data-testid="rollup-open-total">{{ (money(splitTotal().open)) | figure }}</span>
      </p>
      <p>
        Overdue: <span data-testid="rollup-overdue-count">{{ (overdueTotal().count) | figure }}</span>,
        due on them <span data-testid="rollup-overdue-total">{{ (money(overdueTotal().cents)) | figure }}</span>
      </p>
      <p>
        Cut in total: <span data-testid="rollup-cut-total">{{ (money(cutTotal())) | figure }}</span>
      </p>
    </section>
  `,
  styles: `
    .consolidated {
      display: flex;
      flex-wrap: wrap;
      gap: 1.5rem;
      margin: 0 0 1rem;
    }
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
  protected readonly session = inject(Session);
  private readonly service = inject(PeopleService);
  private readonly claimsService = inject(ClaimsService);
  private readonly ratesService = inject(RatesService);
  private readonly settingsService = inject(SettingsService);
  private readonly statementsService = inject(StatementsService);
  private readonly periodsService = inject(PeriodsService);
  private readonly poolsService = inject(PoolsService);
  private readonly crewsService = inject(CrewsService);
  private readonly plansService = inject(PlansService);
  private readonly document = inject(DOCUMENT);

  protected readonly grades = GRADES;
  protected readonly money = money;
  protected readonly people = signal<Person[]>([]);
  /** Whether retired people are listed; off when the screen is first opened, kept across sheet loads. */
  protected readonly showRetired = signal(false);
  /** The people-list columns each user may switch off for their own screen. */
  protected readonly columns: readonly { key: string; label: string }[] = [
    { key: 'claimed', label: 'Claimed' },
    { key: 'due-total', label: 'Due' },
    { key: 'statement-count', label: 'Statements' },
    { key: 'stated-total', label: 'Last stated' },
    { key: 'statement-date', label: 'Stated on' },
    { key: 'overdue-count', label: 'Overdue' },
    { key: 'overdue-total', label: 'Overdue due' },
    { key: 'patrons', label: 'Patrons' },
    { key: 'backing', label: 'Backing' },
    { key: 'hold', label: 'On hold' },
    { key: 'retired', label: 'Retired' },
    { key: 'terms', label: 'Terms' },
    { key: 'contact', label: 'Contact' },
    { key: 'aliases', label: 'Aliases' },
    { key: 'excess', label: 'Excess' },
  ];
  private readonly hiddenColumns = computed(() => new Set(this.session.hiddenColumns()));
  protected readonly sortKeys = SORT_KEYS;
  /** How this screen orders the people it lists; added (list order) when the screen is first
   *  opened, kept across sheet loads. Only what the screen shows is reordered. */
  protected readonly sortKey = signal<SortKey>('added');
  /** The people listed: everyone, or only those not retired unless "show retired" is on, in the
   *  chosen order; people with equal keys keep their list order. */
  protected readonly shownPeople = computed(() => {
    const listed = this.showRetired() ? this.people() : this.people().filter((p) => !p.retired);
    const key = this.sortKey();
    if (key === 'added') return listed;
    const keyed = listed.map((p, i) => ({ p, i, k: this.sortValueOf(p, key) }));
    keyed.sort((a, b) => compareKeys(a.k, b.k) || a.i - b.i);
    return keyed.map((x) => x.p);
  });
  /** The retire-from date typed on each person's row, by person id. */
  protected readonly retireDates = signal<Record<number, string>>({});
  /** The grade chosen in each person's row select, by person id; their grade in force until chosen. */
  protected readonly inlineGrades = signal<Record<number, Grade>>({});
  /** The statement as-of date typed on each person's row, by person id. */
  protected readonly statementAsOfs = signal<Record<number, string>>({});
  /** The hold from- and to-dates typed on each person's row, by person id. */
  protected readonly holdFroms = signal<Record<number, string>>({});
  protected readonly holdTos = signal<Record<number, string>>({});
  protected readonly flagReasons = signal<Record<number, string>>({});
  protected readonly flagUntils = signal<Record<number, string>>({});
  /** The alias typed on each person's row, by person id. */
  protected readonly aliasInputs = signal<Record<number, string>>({});
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
  /** The signed-in user's steward dashboard over the sheet. */
  protected readonly dashboard = signal<DashboardCounts>(NO_DASHBOARD);
  /** The sheet's patrons, in the order they were named. */
  protected readonly patrons = signal<Patron[]>([]);
  /** The sheet's snapshots, in the order they were written. */
  protected readonly snapshots = signal<Snapshot[]>([]);
  /** Each person's snapshot lines as "<day> <due-total>", oldest first, by person id. */
  private readonly snapshotLines = computed(() => {
    const by = new Map<number, string[]>();
    for (const s of this.snapshots()) by.set(s.personId, [...(by.get(s.personId) ?? []), `${s.day} ${s.dueTotal}`]);
    return by;
  });
  /** The patron chosen on each person's row (a person id), by person id. */
  protected readonly patronChoices = signal<Record<number, string>>({});
  /** The person chosen on each person's row to merge them into (a person id), by person id. */
  protected readonly mergeChoices = signal<Record<number, string>>({});

  /** The sheet id typed on each person's row to transfer them to, by person id. */
  protected readonly transferInputs = signal<Record<number, string>>({});
  /** The error of the last refused patron added on each person's row, by person id. */
  protected readonly patronErrors = signal<Record<number, string>>({});
  /** Each person's patrons with their shares in hundredths, in the order they were named, by person id:
   *  100.00 split equally, each share rounded half to even, the first-named patron also taking the
   *  remainder. */
  private readonly patronShares = computed(() => {
    const by = new Map<number, number[]>();
    for (const pt of this.patrons()) by.set(pt.personId, [...(by.get(pt.personId) ?? []), pt.patronId]);
    return new Map(
      [...by].map(([id, patronIds]) => {
        const share = halfEven(10000n, BigInt(patronIds.length));
        const shares = patronIds.map((patronId) => ({ patronId, share }));
        shares[0].share += 10000 - share * patronIds.length;
        return [id, shares];
      }),
    );
  });
  /** Each person's patrons as "<name> <share>", sorted by name and comma separated, by person id. */
  private readonly patronNames = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p.name]));
    const by = new Map<number, string>();
    for (const [id, shares] of this.patronShares()) {
      const named = shares.flatMap(({ patronId, share }) => {
        const name = byId.get(patronId);
        return name === undefined ? [] : [{ name, share }];
      });
      named.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
      by.set(id, named.map(({ name, share }) => `${name} ${money(share)}`).join(', '));
    }
    return by;
  });
  /** Each person's backing, by person id: over their patrons, the figures of the patron's SETTLED
   *  claims dated within the 90 days up to and including the sheet's today, each as it shows (its
   *  frozen figure), added up (n/a ones skipped), times the patron's share divided by 100; the sum
   *  rounded half to even; 0 with none. */
  private readonly backings = computed(() => {
    const today = this.session.today();
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const by = new Map<number, number>();
    for (const [id, shares] of this.patronShares()) {
      let sum = 0n;
      for (const { patronId, share } of shares) {
        const patron = byId.get(patronId);
        const settled = patron === undefined || today === null ? 0 : this.recentSettledCentsOf(patron, today);
        sum += BigInt(settled) * BigInt(share);
      }
      by.set(id, halfEven(sum, 10000n));
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
  /** Every rounding mode recorded, each from its date, oldest first; null records none from then. */
  protected readonly roundings = signal<Dated<string | null>[]>([]);
  /** The month (1 to 12) the sheet's fiscal year starts in. */
  protected readonly fiscalStartMonth = signal(1);
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
  /** Each person's carried figure for each period, in cents, by person id and period name: what is due
   *  on their claims that count whose due-by falls in a period ending before this one starts and that
   *  were still OPEN or PENDING at the end of this one's last day, by the status log, as each claim
   *  shows it now, and their opening balance for it; once the period is closed it is the figure fixed
   *  then (0 for a person added later). */
  protected readonly personCarriedCents = computed(() => {
    const by = new Map<number, Record<string, number>>();
    for (const person of this.people()) {
      const sums: Record<string, number> = {};
      for (const p of this.periods()) {
        sums[p.name] = p.closed
          ? cents(p.closedPersonCarried?.[person.id] ?? '0.00')
          : cents(p.personOpenings?.[person.id] ?? '0.00');
      }
      for (const c of this.claimsOf(person.id)) {
        const own = periodOn(this.periods(), c.dueBy);
        if (own === null) continue;
        for (const p of this.periods()) {
          if (p.closed || own.to >= p.from) continue;
          const status = statusOn(c, p.to);
          if ((status !== 'OPEN' && status !== 'PENDING') || !this.countsAtEnd(c, person, p.to)) continue;
          sums[p.name] += claimShownCents(c, this.rates(), person.grades, this.floors(), this.lateness(), this.roundingOf(person.id)) ?? 0;
        }
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
  /** Each period's classic due-total, by name: while it is open, what is due on the claims whose
   *  due-by falls in it that count by their status now (OPEN, SETTLED and PENDING), as each claim
   *  shows it; once it is closed, the figure fixed then. */
  protected readonly classicPeriodDueTotals = computed(() => {
    const sums: Record<string, number> = {};
    for (const p of this.periods()) {
      sums[p.name] = p.closed ? cents(p.closedTotal ?? '0.00') : 0;
    }
    for (const person of this.people()) {
      for (const c of this.claimsOf(person.id)) {
        const period = periodOn(this.periods(), c.dueBy);
        if (period === null || period.closed || !this.countsLive(c, person)) continue;
        sums[period.name] += claimShownCents(c, this.rates(), person.grades, this.floors(), this.lateness(), this.roundingOf(person.id)) ?? 0;
      }
    }
    return Object.fromEntries(Object.entries(sums).map(([name, sum]) => [name, money(sum)]));
  });
  /** The sheet's classic due-total: everyone's due-totals added up (n/a ones skipped), not trimmed. */
  protected readonly classicDueTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + (this.dueCentsOf(p) ?? 0), 0)),
  );
  /** Every tag ever used on the sheet: those the store keeps, and those on its claims now. */
  protected readonly sheetTags = signal<string[]>([]);
  /** Each tag ever used on the sheet, sorted, with its due-total: what is due on the claims that count
   *  which carry it, or whose person carries it, as each claim shows it (a claim with two tags counts
   *  in both, and once for a tag both it and its person carry). */
  protected readonly tagDueTotals = computed(() => {
    const sums = new Map<string, number>();
    for (const tag of this.sheetTags()) sums.set(tag, 0);
    for (const c of this.claims()) for (const tag of c.tags) if (!sums.has(tag)) sums.set(tag, 0);
    for (const p of this.people()) for (const tag of p.tags) if (!sums.has(tag)) sums.set(tag, 0);
    for (const person of this.people()) {
      for (const c of this.claimsOf(person.id)) {
        const tags = new Set([...c.tags, ...person.tags]);
        if (tags.size === 0 || !this.countsLive(c, person)) continue;
        const due = claimShownCents(c, this.rates(), person.grades, this.floors(), this.lateness(), this.roundingOf(person.id)) ?? 0;
        for (const tag of tags) sums.set(tag, (sums.get(tag) ?? 0) + due);
      }
    }
    return [...sums.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([tag, sum]) => ({ tag, dueTotal: money(sum) }));
  });
  /** Each calendar month (yyyy-mm) any of the sheet's claims is dated in, sorted, with its due-total:
   *  what is due on the claims dated in it that count, as each claim shows it. */
  protected readonly monthDueTotals = computed(() => {
    const sums = new Map<string, number>();
    for (const c of this.claims()) sums.set(c.date.slice(0, 7), 0);
    for (const person of this.people()) {
      for (const c of this.claimsOf(person.id)) {
        if (!this.countsLive(c, person)) continue;
        const due = claimShownCents(c, this.rates(), person.grades, this.floors(), this.lateness(), this.roundingOf(person.id)) ?? 0;
        const month = c.date.slice(0, 7);
        sums.set(month, (sums.get(month) ?? 0) + due);
      }
    }
    return [...sums.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([month, sum]) => ({ month, dueTotal: money(sum) }));
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
  /** Each person's due-total, in cents, as their row shows it, by person id; null when it is n/a. */
  protected readonly dueCentsById = computed(() => new Map(this.people().map((p) => [p.id, this.dueCentsOf(p)] as const)));
  /** Each person's cut total, in cents, by person id: their cuts across every pool they are a member of,
   *  added up (a pool that shows no cuts adds nothing). */
  protected readonly cutCents = computed(() => {
    const by = new Map<number, number>();
    for (const pool of this.poolsService.pools()) {
      for (const m of pool.members) {
        if (m.personId !== null && m.cut !== null) by.set(m.personId, (by.get(m.personId) ?? 0) + cents(m.cut));
      }
    }
    return by;
  });
  /** The sheet's cut total, in cents: everyone's cut totals added up. */
  protected readonly cutTotal = computed(() =>
    this.people().reduce((sum, p) => sum + (this.cutCents().get(p.id) ?? 0), 0),
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
  /** The signed-in user's own figures: the due-total and overdue-total of the person on the sheet who
   *  has the user's name, or else has it as an alias (as names are compared); null when there is none. */
  protected readonly myFigures = computed(() => {
    const me = this.me();
    if (!me) return null;
    return { dueTotal: this.dueTotalOf(me) ?? 'n/a', overdueTotal: money(this.overdueOf(me).cents) };
  });
  /** The signed-in user's crew total: the due-totals of the crews led by the person who is the user (as
   *  their own figures find them), added up; blank when there is no such person or they lead no crew. */
  protected readonly myCrewDueTotal = computed(() => {
    const me = this.me();
    if (!me) return '';
    const led = this.crewsService.crews().filter((c) => c.lead?.personId === me.id);
    if (led.length === 0) return '';
    const due = this.dueCentsById();
    return money(led.reduce((sum, c) => sum + crewDueCents(c, due), 0));
  });
  /** The person on the sheet who has the signed-in user's name, or else has it as an alias (as names are
   *  compared); null when there is none. */
  private readonly me = computed(() => {
    const user = this.session.user();
    if (user === null) return null;
    const key = nameKey(user);
    const people = this.people();
    return people.find((p) => nameKey(p.name) === key) ?? people.find((p) => p.aliases.some((a) => nameKey(a) === key)) ?? null;
  });
  /** The figures of the sheet's descendants, added up, as read when the sheet was loaded. */
  protected readonly descendants = signal<DescendantFigures>({ peopleCount: 0, dueTotal: '0.00', overdueTotal: '0.00' });
  /** The consolidated figures: the sheet's own (as its header shows them) and its descendants', added up.
   *  While the sheet is read as of an earlier date they are the tree's as it stood then: the server adds the sheet's own in. */
  protected readonly consolidated = computed(() => {
    const d = this.descendants();
    if (this.session.asOf()) return d;
    return {
      peopleCount: this.people().length + d.peopleCount,
      dueTotal: money(cents(this.dueTotal()) + cents(d.dueTotal)),
      overdueTotal: money(this.overdueTotal().cents + cents(d.overdueTotal)),
    };
  });
  protected readonly name = signal('');
  protected readonly grade = signal<Grade>('A');
  /** The date a save gives the grade from; blank for the sheet's today. */
  protected readonly gradeFrom = signal('');
  /** The person's own terms in days, as typed; blank for none. */
  protected readonly terms = signal('');
  /** The date the terms are given from on a save; blank for the beginning of time. */
  protected readonly termsFrom = signal('');
  /** The person's contact, as typed; blank for none. */
  protected readonly contact = signal('');
  /** The person's tags, as typed; blank for none. */
  protected readonly tags = signal('');
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
  /** The sheet's plans, in the order they were added. */
  protected readonly plans = signal<Plan[]>([]);
  /** How many claims the last generate made; null before one, and while one runs. */
  protected readonly planGenerated = signal<number | null>(null);
  protected readonly planGenerateError = signal<string | null>(null);
  /** A generate is waiting for its answer; another meanwhile is ignored. */
  private generating = false;
  /** A paste is waiting for its answer; another meanwhile is ignored. */
  private pasting = false;
  protected readonly importText = signal('');
  /** How many people the last import added; null before one, and while one runs. */
  protected readonly importAdded = signal<number | null>(null);
  /** How many people the last import updated; null before one, and while one runs. */
  protected readonly importUpdated = signal<number | null>(null);
  protected readonly importError = signal<string | null>(null);
  /** An import is waiting for its answer; another meanwhile is ignored. */
  private importing = false;

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
  /** The person, by id, a bulk move moves the selected claims to; blank before one is chosen. */
  protected readonly bulkReassignTo = signal('');
  /** How the last bulk move went; null before one, and while one runs. */
  protected readonly bulkReassigned = signal<{ reassigned: number; refused: number } | null>(null);
  /** A bulk move is waiting for its answer; another meanwhile is ignored. */
  private bulkReassigning = false;
  /** The date a bulk re-date re-dates the selected claims to. */
  protected readonly bulkRedateDate = signal('');
  /** How the last bulk re-date went; null before one, and while one runs. */
  protected readonly bulkRedated = signal<{ redated: number; refused: number } | null>(null);
  /** A bulk re-date is waiting for its answer; another meanwhile is ignored. */
  private bulkRedating = false;
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
  /** The date a bulk retire retires from; blank for the sheet's today. */
  protected readonly bulkRetireFrom = signal('');
  /** How many people the last bulk retire retired; null before one, and while one runs. */
  protected readonly bulkRetired = signal<number | null>(null);
  protected readonly bulkRetireError = signal<string | null>(null);
  /** A bulk retire is waiting for its answer; another meanwhile is ignored. */
  private bulkRetiring = false;
  /** The read of the sheet last loaded (as of the date set, when one is); done once it is shown. */
  private reading: Promise<void> = Promise.resolve();
  /** The sheet load {@link reading} was started for. */
  private readLoad = -1;

  constructor() {
    // Re-read the sheet's people from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.sheet();
      this.readLoad = this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.sheetTags.set([]);
      this.rates.set([]);
      this.floors.set([]);
      this.caps.set([]);
      this.latePcts.set([]);
      this.graces.set({});
      this.policyDate.set(null);
      this.roundings.set([]);
      this.fiscalStartMonth.set(1);
      this.statements.set([]);
      this.periods.set([]);
      this.descendants.set({ peopleCount: 0, dueTotal: '0.00', overdueTotal: '0.00' });
      this.rowErrors.set({});
      this.selectedClaims.set(new Set());
      this.bulkOpened.set(null);
      this.bulkSettled.set(null);
      this.bulkReassignTo.set('');
      this.bulkReassigned.set(null);
      this.bulkRedateDate.set('');
      this.bulkRedated.set(null);
      this.selectedPeople.set(new Set());
      this.bulkGradeChoice.set('A');
      this.bulkGradeFrom.set('');
      this.bulkGraded.set(null);
      this.bulkGradeError.set(null);
      this.bulkRetireFrom.set('');
      this.bulkRetired.set(null);
      this.bulkRetireError.set(null);
      this.retireDates.set({});
      this.inlineGrades.set({});
      this.statementAsOfs.set({});
      this.holdFroms.set({});
      this.holdTos.set({});
      this.flagReasons.set({});
      this.flagUntils.set({});
      this.aliasInputs.set({});
      this.patrons.set([]);
      this.snapshots.set([]);
      this.watched.set(new Set());
      this.dashboard.set(NO_DASHBOARD);
      this.patronChoices.set({});
      this.mergeChoices.set({});
      this.transferInputs.set({});
      this.patronErrors.set({});
      this.reset();
      this.quickName.set('');
      this.quickAmount.set('');
      this.quickError.set(null);
      this.pasteText.set('');
      this.pasteAdded.set(null);
      this.pasteError.set(null);
      this.plans.set([]);
      this.planGenerated.set(null);
      this.planGenerateError.set(null);
      this.importText.set('');
      this.importAdded.set(null);
      this.importUpdated.set(null);
      this.importError.set(null);
      this.reading = sheet ? this.read(sheet) : Promise.resolve();
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

  /** Whether the signed-in user's screen shows this people-list column. */
  protected shows(column: string): boolean {
    return !this.hiddenColumns().has(column);
  }

  protected showColumn(column: string, on: boolean): void {
    const hidden = this.session.hiddenColumns().filter((c) => c !== column);
    void this.session.setHiddenColumns(on ? hidden : [...hidden, column]);
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

  /** Swap the person with the one above or below them in the list; refused for the top person
   *  moving up and the bottom one moving down. */
  protected async move(person: Person, direction: 'up' | 'down'): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    try {
      await this.service.move(sheet, person.id, direction);
      const list = await this.service.list(sheet);
      if (this.sheet() !== sheet) return;
      this.people.set(list);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [person.id]: direction === 'up' ? 'That person is already at the top.' : 'That person is already at the bottom.',
      }));
    }
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

  protected setInlineGrade(id: number, grade: string): void {
    this.clearRowError(id);
    if ((this.grades as readonly string[]).includes(grade)) {
      this.inlineGrades.update((grades) => ({ ...grades, [id]: grade as Grade }));
    }
  }

  /** Give the person the grade chosen on their row from the sheet's today, as the person form would. */
  protected async saveInlineGrade(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    const grade = this.inlineGrades()[person.id] ?? this.gradeNow(person);
    try {
      const saved = await this.service.grade(sheet, person.id, person.version, grade);
      if (this.sheet() !== sheet) return;
      this.put(saved);
      this.inlineGrades.update(({ [person.id]: _, ...rest }) => rest);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'That grade could not be saved.' }));
    }
  }

  /** Unretire the person on the sheet's today; one not retired is refused with an error. */
  protected async unretire(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    try {
      const unretired = await this.service.unretire(sheet, person.id);
      if (this.sheet() !== sheet) return;
      this.put(unretired);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'Only a retired person can be unretired.' }));
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

  /** The person's statements still standing, by date, oldest first; those of a day keep their order
   *  among the person's statements (those a merge or a transfer brought over before their own). */
  protected statementLinesOf(personId: number): Statement[] {
    return [...this.statementsOf(personId)].sort((a, b) => a.date.localeCompare(b.date));
  }

  protected snapshotLinesOf(personId: number): string[] {
    return this.snapshotLines().get(personId) ?? [];
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

  protected setMergeChoice(id: number, intoId: string): void {
    this.mergeChoices.update((choices) => ({ ...choices, [id]: intoId }));
    this.clearRowError(id);
  }

  /** Merge the person into the one chosen on their row, then re-read the sheet: the merge moved their
   *  claims, aliases, patrons and pool memberships to that person, and they leave the list. */
  protected async merge(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    const choice = this.mergeChoices()[person.id] ?? '';
    if (choice === '') {
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'Choose a person to merge into.' }));
      return;
    }
    try {
      await this.service.merge(sheet, person.id, Number(choice));
      if (this.sheet() !== sheet) return;
      this.mergeChoices.update(({ [person.id]: _, ...rest }) => rest);
      await Promise.all([this.read(sheet), this.poolsService.refresh(sheet), this.crewsService.refresh(sheet)]);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [person.id]: 'That person cannot be merged into the one chosen.',
      }));
    }
  }

  protected setTransferInput(id: number, sheetId: string): void {
    this.transferInputs.update((typed) => ({ ...typed, [id]: sheetId }));
    this.clearRowError(id);
  }

  /** Transfer the person to the sheet typed on their row, then re-read the sheet: they leave it
   *  entirely, with their patron links and pool memberships. */
  protected async transfer(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    const to = (this.transferInputs()[person.id] ?? '').trim();
    if (to === '') {
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'Enter a sheet to transfer to.' }));
      return;
    }
    try {
      await this.service.transfer(sheet, person.id, to);
      if (this.sheet() !== sheet) return;
      this.transferInputs.update(({ [person.id]: _, ...rest }) => rest);
      await Promise.all([this.read(sheet), this.poolsService.refresh(sheet), this.crewsService.refresh(sheet)]);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [person.id]: 'That person cannot be transferred to that sheet.',
      }));
    }
  }

  /** Undo the transfer that brought the person here, then re-read the sheet: they go back to the
   *  sheet they came from. */
  protected async transferUndo(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    try {
      await this.service.transferUndo(sheet, person.id);
      if (this.sheet() !== sheet) return;
      await Promise.all([this.read(sheet), this.poolsService.refresh(sheet), this.crewsService.refresh(sheet)]);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [person.id]: 'That transfer cannot be undone.',
      }));
    }
  }

  protected setHoldDate(dates: WritableSignal<Record<number, string>>, id: number, date: string): void {
    dates.update((typed) => ({ ...typed, [id]: date }));
    this.clearRowError(id);
  }

  protected setAliasInput(id: number, alias: string): void {
    this.aliasInputs.update((typed) => ({ ...typed, [id]: alias }));
    this.clearRowError(id);
  }

  /** Give the person the alias typed on their row; a blank one is refused. */
  protected async addAlias(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    const alias = this.aliasInputs()[person.id] ?? '';
    if (!alias.trim()) {
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'Type an alias for the person.' }));
      return;
    }
    try {
      const saved = await this.service.addAlias(sheet, person.id, alias);
      if (this.sheet() !== sheet) return;
      this.put(saved);
      this.aliasInputs.update(({ [person.id]: _, ...rest }) => rest);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [person.id]: 'That alias is already a name or alias on the sheet.',
      }));
    }
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

  /** Flag the person with the reason typed on their row until the date typed; refused for anyone but
   *  a steward, and when the reason is blank. */
  protected async flag(person: Person): Promise<void> {
    this.clearRowError(person.id);
    const sheet = this.sheet();
    if (!sheet) return;
    const reason = (this.flagReasons()[person.id] ?? '').trim();
    const until = (this.flagUntils()[person.id] ?? '').trim();
    if (reason === '') {
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'Type a reason for the flag.' }));
      return;
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(until)) {
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'Type a date as YYYY-MM-DD.' }));
      return;
    }
    try {
      const saved = await this.service.flag(sheet, person.id, reason, until);
      if (this.sheet() !== sheet) return;
      this.put(saved);
      this.flagReasons.update(({ [person.id]: _, ...rest }) => rest);
      this.flagUntils.update(({ [person.id]: _, ...rest }) => rest);
    } catch {
      if (this.sheet() !== sheet) return;
      this.rowErrors.update((errors) => ({ ...errors, [person.id]: 'Only a steward can flag a person.' }));
    }
  }

  /** Whether the claim counts in the live totals: OPEN, PENDING or SETTLED, but not an OPEN claim
   *  deferred on the sheet's today, nor OPEN or PENDING when it is dated within one of its person's
   *  holds, or held itself, or its person's retirement date is reached on the sheet's today, and no statement binds it. */
  private countsLive(claim: Claim, person: Person): boolean {
    return this.countsAs(claim, claim.status, person);
  }

  /** Whether the claim would count in the live totals were its status `status` (see countsLive). */
  private countsAs(claim: Claim, status: ClaimStatus, person: Person): boolean {
    const as = { ...claim, status };
    if (!counts(as)) return false;
    if (!isOpen(as)) return true;
    if (deferred(claim, this.session.today())) return false;
    const bound = claim.stated;
    const today = this.session.today();
    const retired = person.retiredFrom !== null && today !== null && person.retiredFrom <= today;
    return bound || !(claim.held || heldOn(person.holds, claim.date) || retired);
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
   *  out); drafts, voided claims and corrections do not count. */
  private claimedCentsOf(person: Person): number {
    return this.claimsOf(person.id).reduce(
      (sum, c) => sum + (this.countsLive(c, person) && !isCorrection(c) ? cents(c.amount) : 0),
      0,
    );
  }

  /** The grade's rate in force on the sheet's today. */
  private rateOf(grade: Grade | null): string | null {
    const today = this.session.today();
    return today === null || grade === null ? null : rateOn(this.rates(), grade, today);
  }

  /** What decides how the person's claims round. */
  private roundingOf(personId: number): Rounding {
    return { modes: this.roundings(), policyDate: this.policyDateInForce(), claims: this.claimsOf(personId) };
  }

  /** Download everyone, retired people included, in list order as a CSV file: name, grade in force
   *  today, retired-from date (blank when not retired) and due-total as shown. */
  protected async exportCsv(): Promise<void> {
    if (!(await this.readDone())) return;
    const lines = [['name', 'grade', 'retired-from', 'due-total']];
    for (const p of this.people()) {
      lines.push([p.name, this.gradeNow(p), p.retiredFrom ?? '', this.dueTotalOf(p) ?? 'n/a']);
    }
    this.downloadCsv('people.csv', lines);
  }

  /** Download every claim as a CSV file: people in list order, retired people included, and each
   *  person's claims oldest first, with number, date, amount, due, status and period as each claim
   *  shows them (a blank number or period, and a due of n/a, as they show). */
  protected async exportClaims(): Promise<void> {
    if (!(await this.readDone())) return;
    const lines = [['person', 'number', 'date', 'amount', 'due', 'status', 'period']];
    for (const p of this.people()) {
      for (const c of [...this.claimsOf(p.id)].reverse()) {
        const due = claimShownCents(c, this.rates(), p.grades, this.floors(), this.lateness(), this.roundingOf(p.id));
        const period = periodOn(this.periods(), c.dueBy)?.name ?? '';
        lines.push([p.name, c.number ?? '', c.date, c.amount, due === null ? 'n/a' : money(due), c.status, period]);
      }
    }
    this.downloadCsv('claims.csv', lines);
  }

  /** Wait for the sheet's last read, as of the date set when one is, to be shown, so an export taken
   *  just after a load shows what it read; false when no sheet is loaded. */
  private async readDone(): Promise<boolean> {
    // A load just made starts its read once the view next updates.
    while (this.readLoad !== this.session.loads()) await new Promise((done) => setTimeout(done));
    let reading: Promise<void>;
    do {
      reading = this.reading;
      await reading;
    } while (reading !== this.reading);
    return this.sheet() !== null;
  }

  /** Download the lines as a CSV file of the name, each field quoted when it needs to be. */
  private downloadCsv(name: string, lines: string[][]): void {
    const text = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([text], { type: 'text/csv' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = name;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  /** The value the person is sorted on under `key`; null (n/a) sorts last. */
  private sortValueOf(person: Person, key: Exclude<SortKey, 'added'>): string | number | null {
    switch (key) {
      case 'name':
        return nameKey(person.name);
      case 'grade':
        return this.gradeNow(person);
      case 'due-total':
        return this.dueCentsOf(person);
    }
  }

  protected sortKeyOf(event: Event): SortKey {
    const v = (event.target as HTMLSelectElement).value;
    return (SORT_KEYS as readonly string[]).includes(v) ? (v as SortKey) : 'added';
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

  /** Re-read the sheet's claims: an added holiday re-fixes their due-by on the server. */
  protected async holidayAdded(): Promise<void> {
    const sheet = this.sheet();
    if (!sheet) return;
    try {
      const claims = await this.claimsService.list(sheet);
      if (this.sheet() === sheet) this.claims.set(claims);
    } catch {
      // The claims could not be re-read; they show as they were until the sheet is loaded again.
    }
  }

  /** Put a set rate in place of the grade's old one for the same from-date. */
  protected rateSaved(rate: Rate): void {
    this.rates.update((list) => [
      ...list.filter((r) => (r.level ?? 0) !== 0 || r.grade !== rate.grade || r.from !== rate.from),
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

  /** Settle the selected claims together, in list order, each by the rules of settling one, all or
   *  nothing: if any cannot be settled, none is. Shows how many were settled and refused; the old
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
      if (result.batch !== null) this.session.lastSettleBatch.set(result.batch);
      this.selectedClaims.set(new Set());
      this.bulkSettled.set({ settled: result.settled, refused: result.refused });
    } catch {
      if (this.session.sheetId() === sheet) this.bulkSettled.set({ settled: 0, refused: this.selectedClaims().size });
    } finally {
      this.bulkSettling = false;
    }
  }

  /** Move the selected claims together to the person chosen, in list order, each by the rules of
   *  moving one; those that cannot be moved are left as they are. Shows how many were moved and
   *  refused; the old counts are gone while it runs. */
  protected async bulkReassign(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet || this.bulkReassigning) return;
    this.bulkReassigned.set(null);
    const to = Number(this.bulkReassignTo());
    if (!to) {
      this.bulkReassigned.set({ reassigned: 0, refused: this.selectedClaims().size });
      return;
    }
    this.bulkReassigning = true;
    try {
      const result = await this.claimsService.bulkReassign(sheet, [...this.selectedClaims()], to);
      if (this.session.sheetId() !== sheet) return;
      // Each moved claim is listed as its person's newest, even one that stayed with its person.
      for (const c of result.claims) {
        this.claims.update((list) => list.filter((o) => o.id !== c.id));
        this.claimSaved(c);
      }
      this.selectedClaims.set(new Set());
      this.bulkReassigned.set({ reassigned: result.reassigned, refused: result.refused });
    } catch {
      if (this.session.sheetId() === sheet) {
        this.bulkReassigned.set({ reassigned: 0, refused: this.selectedClaims().size });
      }
    } finally {
      this.bulkReassigning = false;
    }
  }

  /** Re-date the selected claims together to the date typed, in list order, each by the rules of
   *  re-dating one; those that cannot be re-dated are left as they are. Shows how many were re-dated
   *  and refused; the old counts are gone while it runs. */
  protected async bulkRedate(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet || this.bulkRedating) return;
    this.bulkRedated.set(null);
    const date = this.bulkRedateDate().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.bulkRedated.set({ redated: 0, refused: this.selectedClaims().size });
      return;
    }
    this.bulkRedating = true;
    try {
      const result = await this.claimsService.bulkRedate(sheet, [...this.selectedClaims()], date);
      if (this.session.sheetId() !== sheet) return;
      for (const c of result.claims) this.claimSaved(c);
      this.selectedClaims.set(new Set());
      this.bulkRedated.set({ redated: result.redated, refused: result.refused });
    } catch {
      if (this.session.sheetId() === sheet) {
        this.bulkRedated.set({ redated: 0, refused: this.selectedClaims().size });
      }
    } finally {
      this.bulkRedating = false;
    }
  }

  protected selectPerson(id: number, selected: boolean): void {
    this.bulkGradeError.set(null);
    this.bulkRetireError.set(null);
    this.clearRowError(id);
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

  /** Retire the selected people from the chosen date (the sheet's today when blank), in list order,
   *  each as the row's retire would. Shows how many were retired; the old count is gone while it
   *  runs. */
  protected async bulkRetire(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet || this.bulkRetiring) return;
    this.bulkRetired.set(null);
    this.bulkRetireError.set(null);
    const from = this.bulkRetireFrom().trim();
    if (from !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.bulkRetireError.set('Type a date as YYYY-MM-DD.');
      return;
    }
    this.bulkRetiring = true;
    try {
      const result = await this.service.bulkRetire(sheet, [...this.selectedPeople()], from || null);
      if (this.session.sheetId() !== sheet) return;
      for (const p of result.people) this.put(p);
      this.selectedPeople.set(new Set());
      this.bulkRetired.set(result.retired);
    } catch {
      if (this.session.sheetId() === sheet) this.bulkRetireError.set('The selected people could not be retired.');
    } finally {
      this.bulkRetiring = false;
    }
  }

  /** Put an added claim first, or a saved one in its place. */
  /** Show the claim as saved; one new, or moved to another person, is listed first (newest). */
  protected claimSaved(claim: Claim): void {
    // A claim saved on the row is the next action taken on it: its old error goes.
    this.clearRowError(claim.personId);
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id && c.personId === claim.personId)
        ? list.map((c) => (c.id === claim.id ? claim : c))
        : [claim, ...list.filter((c) => c.id !== claim.id)],
    );
    // A tag once used stays among the sheet's tags, even when the claim later drops it.
    const fresh = claim.tags.filter((t) => !this.sheetTags().includes(t));
    if (fresh.length > 0) this.sheetTags.update((tags) => [...tags, ...fresh]);
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
    this.terms.set(person.undatedTerms === null ? '' : String(person.undatedTerms));
    this.termsFrom.set('');
    this.contact.set(person.contact ?? '');
    this.tags.set(person.tags.join(', '));
    this.error.set(null);
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

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.sheet();
    if (!sheet) return;
    const fields = { name: this.name(), grade: this.grade(), terms: this.terms().trim(), contact: this.contact(), tags: this.tags() };
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
    const contact = fields.contact.trim().toLowerCase();
    if (contact && this.people().some((p) => p.id !== id && p.contact === contact)) {
      // Refused at once, as the server would: nothing is saved and the editor stays open. The people
      // are re-read, so a later save starts from them as now stored.
      this.error.set('Another person on the sheet already has that contact.');
      void this.reread(sheet, id, this.editorOpenings);
      return;
    }
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
          termsFrom: this.termsFrom() || null,
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
          this.terms.set(current.undatedTerms === null ? '' : String(current.undatedTerms));
          this.contact.set(current.contact ?? '');
          this.tags.set(current.tags.join(', '));
        }
        this.error.set('Someone else saved this person since you opened it; their changes are shown.');
        return;
      }
      if (e instanceof HttpErrorResponse && e.status === 409) {
        const body = e.error as { error?: unknown } | null;
        this.error.set(body?.error === 'contact-taken'
          ? 'Another person on the sheet already has that contact.'
          : 'Another person on the sheet already has that name.');
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

  /** The person's plans, in the order they were added. */
  protected plansOf(personId: number): Plan[] {
    return this.plans().filter((pl) => pl.personId === personId);
  }

  protected planAdded(plan: Plan): void {
    this.plans.update((list) => [...list.filter((pl) => pl.id !== plan.id), plan]);
  }

  /** Make the DRAFT claims every plan on the sheet is due. Shows how many were made; the old count is
   *  gone while it runs. */
  protected async generatePlans(): Promise<void> {
    if (this.generating) return;
    this.planGenerateError.set(null);
    this.planGenerated.set(null);
    const sheet = this.sheet();
    if (!sheet) return;
    this.generating = true;
    try {
      const generated = await this.plansService.generate(sheet);
      if (this.sheet() !== sheet) return;
      for (const c of generated.claims) this.claimSaved(c);
      this.plans.set(generated.plans);
      this.planGenerated.set(generated.made);
    } catch {
      if (this.sheet() !== sheet) return;
      this.planGenerateError.set('The plans could not be generated.');
    } finally {
      this.generating = false;
    }
  }

  /** Add (or update a matching DRAFT) and open a claim per pasted line, in order; when any line is
   *  refused nothing changes. Shows how many lines went through; the old count is gone while it runs. */
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
            : e instanceof HttpErrorResponse && e.status === 403
              ? 'You may not open claims on this sheet.'
              : e instanceof HttpErrorResponse && e.status === 422
                ? 'A line needs a note, or is dated after the person retired.'
                : e instanceof HttpErrorResponse && e.status === 409
                  ? 'A line matches a claim that is not a draft, is over a period\'s cap, or reuses a reference.'
                  : 'Those claims could not be pasted and opened.',
      );
    } finally {
      this.pasting = false;
    }
  }

  /** Add or update a person per pasted line, in order, as the person form does; a line naming a person
   *  on the sheet updates them. When any line is refused no one is added or updated. Shows how many were
   *  added and updated; the old counts are gone while it runs. */
  protected async importPeople(event: Event): Promise<void> {
    event.preventDefault();
    if (this.importing) return;
    this.importError.set(null);
    this.importAdded.set(null);
    this.importUpdated.set(null);
    const sheet = this.sheet();
    if (!sheet) return;
    if (!this.importText().trim()) {
      this.importError.set('Paste a line of name,grade,terms,contact.');
      return;
    }
    this.importing = true;
    try {
      const imported = await this.service.importPeople(sheet, this.importText());
      if (this.sheet() !== sheet) return;
      this.people.update((list) => {
        const fresh = imported.people.filter((p) => !list.some((q) => q.id === p.id));
        return [...list.map((q) => imported.people.find((p) => p.id === q.id) ?? q), ...fresh];
      });
      this.importText.set('');
      this.importAdded.set(imported.added);
      this.importUpdated.set(imported.updated);
    } catch (e) {
      if (this.sheet() !== sheet) return;
      this.importError.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'You may not add people to this sheet.'
          : 'A line is not a person the form would add, or repeats a name or contact; no one was added or updated.',
      );
    } finally {
      this.importing = false;
    }
  }

  /** Put a saved person in place of their old row. */
  private put(person: Person): void {
    this.people.update((list) => list.map((p) => (p.id === person.id ? person : p)));
  }

  /** Re-read the sheet's people after a refusal decided here; when the editor is still as it was
   *  opened (`opening`) on a person someone else has saved since, show them as now stored. */
  private async reread(sheet: string, id: number | null, opening: number): Promise<void> {
    try {
      const list = await this.service.list(sheet);
      if (this.sheet() !== sheet) return;
      this.people.set(list);
      const current = list.find((p) => p.id === id);
      if (id === null || !current || this.editingId() !== id || this.editorOpenings !== opening) return;
      if (current.version === this.editingVersion) return;
      this.editingVersion = current.version;
      this.name.set(current.name);
      this.grade.set(this.gradeNow(current));
      this.terms.set(current.undatedTerms === null ? '' : String(current.undatedTerms));
      this.contact.set(current.contact ?? '');
      this.tags.set(current.tags.join(', '));
      this.error.set('Someone else saved this person since you opened it; their changes are shown.');
    } catch {
      // The people could not be re-read; the list stays as it was.
    }
  }

  /** The person as now stored, when a save was refused for being made from an older version. */
  private staleOf(e: unknown): Person | null {
    if (!(e instanceof HttpErrorResponse) || e.status !== 409) return null;
    const body = e.error as { error?: unknown; current?: Person } | null;
    return body?.error === 'stale' && body.current ? body.current : null;
  }

  private async read(sheet: string): Promise<void> {
    try {
      const [list, claims, rates, statements, periods, settings, patrons, watched, tags, descendants, plans, snapshots, dashboard] = await Promise.all([
        this.service.list(sheet),
        this.claimsService.list(sheet),
        this.ratesService.list(sheet),
        this.statementsService.list(sheet),
        this.periodsService.list(sheet),
        this.settingsService.get(sheet),
        this.service.patrons(sheet),
        this.service.watched(sheet),
        this.claimsService.tags(sheet),
        // Without its descendants' figures the sheet shows only its own as consolidated.
        this.service.descendants(sheet).catch(() => ({ peopleCount: 0, dueTotal: '0.00', overdueTotal: '0.00' })),
        this.plansService.list(sheet),
        this.service.snapshots(sheet),
        this.service.dashboard(sheet).catch(() => NO_DASHBOARD),
      ]);
      if (this.sheet() === sheet) {
        this.floors.set(settings.floors);
        this.caps.set(settings.caps);
        this.latePcts.set(settings.latePcts);
        this.graces.set(settings.graces ?? {});
        this.policyDate.set(settings.policyDate);
        this.roundings.set(settings.roundings ?? []);
        this.fiscalStartMonth.set(settings.fiscalStartMonth ?? 1);
        this.rates.set(rates);
        this.periods.set(periods);
        this.statements.set(statements);
        this.claims.set(claims);
        this.sheetTags.set(tags);
        this.patrons.set(patrons);
        this.snapshots.set(snapshots);
        this.watched.set(new Set(watched));
        this.dashboard.set(dashboard);
        this.descendants.set(descendants);
        this.plans.set(plans);
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

/** `numerator` divided by the positive `denominator`, rounded half to even to a whole number. */
function halfEven(numerator: bigint, denominator: bigint): number {
  const negative = numerator < 0n;
  const abs = negative ? -numerator : numerator;
  let quotient = abs / denominator;
  const twice = (abs % denominator) * 2n;
  if (twice > denominator || (twice === denominator && quotient % 2n === 1n)) quotient += 1n;
  return Number(negative ? -quotient : quotient);
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

/** A name as names are compared: trimmed, ignoring case. */
const SORT_KEYS = ['added', 'name', 'grade', 'due-total'] as const;
type SortKey = (typeof SORT_KEYS)[number];

/** Ascending, with null (n/a) after every value. */
function compareKeys(a: string | number | null, b: string | number | null): number {
  if (a === null || b === null) return a === b ? 0 : a === null ? 1 : -1;
  return a < b ? -1 : a > b ? 1 : 0;
}

function nameKey(name: string): string {
  return name.trim().toLowerCase();
}
