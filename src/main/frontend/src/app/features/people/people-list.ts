import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Claim, Claims, QuickAdded, addDays, backingFigure, claimDue, countsAsOf, countsLive, isOpen, keepsActive, overdue, statusOn, sumAmounts, trimmedSum } from '../../core/claims';
import { Patron, Patrons, patronShares } from '../../core/patrons';
import { Pools, formatCents } from '../../core/pools';
import { Grade, Leave, People, Person, Taken, gradeOn } from '../../core/people';
import { Period, Periods, periodOf } from '../../core/periods';
import { Rate, Rates, pctOn, weightedSum } from '../../core/rates';
import { PERSON_COLUMNS, PersonColumn, Session } from '../../core/session';
import { downloadCsv } from '../../core/csv';
import { DatedValue, Graces, RoundingMode, SheetSettings, graceOn, valueOn } from '../../core/settings';
import { Watch, Watches } from '../../core/watches';
import { Plan, Plans } from '../../core/plans';
import { DescendantFigures, Descendants, NO_DESCENDANTS } from '../../core/descendants';
import { VoidReasons } from '../../core/void-reasons';
import { PersonClaims } from '../claims/person-claims';
import { PeriodForm } from '../periods/period-form';
import { RateForm } from '../rates/rate-form';
import { GradeSummary, PeriodSummary, StatusSummary, SummaryPanel, VoidReasonSummary } from '../summary/summary-panel';
import { HeaderCounters } from '../summary/header-counters';
import { ClassicPeriodSummary, ClassicSummaryPanel } from '../summary/classic-summary-panel';
import { QuickAddBar } from './quick-add-bar';
import { PasteClaimsBox } from './paste-claims-box';
import { ImportPeopleBox } from './import-people-box';
import { TreeSearchBox } from './tree-search-box';
import { PoolList } from '../pools/pool-list';
import { CrewList } from '../crews/crew-list';
import { Crews, crewDueTotal } from '../../core/crews';
import { PersonPlans } from '../plans/person-plans';
import { PlanGenerateBox } from '../plans/plan-generate-box';
import { FigurePipe } from '../../core/figure';

/** What the people list can be sorted by on the screen. */
type SortKey = 'added' | 'name' | 'grade' | 'due-total';

/** What leaves no claim out: no holds, not retired. */
const NO_LEAVE: Leave = { holds: [], retiredFrom: null };

// The people on the sheet, in the order they were added, and the form that adds a person or,
// once a person's editor is opened, edits them. Each person's row lists their claims and what
// they have claimed and are due at their grade's rate; the sheet's totals add everyone's up.
// Retired people are left out of the list unless "show retired" is on; their claims still count.
// A person's open claims dated within one of their holds count in no total, unless a statement
// bound them; their settled claims and other open claims still do. From the day the sheet's today
// reaches a person's retirement day, none of their open claims a statement did not bind counts either.
// A person may name other people on the sheet as their patrons; their backing is the settled
// figures of their patrons' settled claims dated within the 90 days up to the sheet's today, each
// patron's weighted by their share (100.00 split equally in the order they were named).
// The signed-in user may watch a person: they are told when a claim of theirs is settled.
@Component({
  selector: 'app-people-list',
  imports: [ReactiveFormsModule, PersonClaims, RateForm, PeriodForm, QuickAddBar, PasteClaimsBox, ImportPeopleBox, SummaryPanel, ClassicSummaryPanel, HeaderCounters, PoolList, CrewList, PersonPlans, PlanGenerateBox, TreeSearchBox, FigurePipe],
  template: `
    <app-header-counters [peopleCount]="people().length" [statuses]="statusSummary()"
      [dueTotal]="dueTotal()" [overdueTotal]="overdueTotal()"
      [myDueTotal]="myDueTotal()" [myOverdueTotal]="myOverdueTotal()" [myCrewDueTotal]="myCrewDueTotal()" />
    <app-rate-form [rates]="rates()" (saved)="rateSaved($event)" (imported)="ratesImported()" />
    <app-period-form [periods]="periods()" [dueTotals]="periodDueTotals()" (saved)="periodSaved($event)" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form data-testid="person-form" [formGroup]="form" (ngSubmit)="submit()">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="person-form-grade-from">Grade from</label>
        <input id="person-form-grade-from" type="date" data-testid="person-form-grade-from" formControlName="gradeFrom" />
        <label for="person-form-terms">Terms (business days)</label>
        <input id="person-form-terms" inputmode="numeric" data-testid="person-form-terms" formControlName="terms" />
        <label for="person-form-terms-from">Terms from</label>
        <input id="person-form-terms-from" type="date" data-testid="person-form-terms-from" formControlName="termsFrom" />
        <label for="person-form-contact">Contact</label>
        <input id="person-form-contact" data-testid="person-form-contact" formControlName="contact" />
        <label for="person-form-tags">Tags (comma separated)</label>
        <input id="person-form-tags" data-testid="person-form-tags" formControlName="tags" />
        <button type="submit" data-testid="person-form-submit">{{ editing() === null ? 'Add person' : 'Save' }}</button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
        }
        @if (error()) {
          <p role="alert" data-testid="person-form-error">{{ error() }}</p>
        }
      </form>
      <app-quick-add-bar (added)="quickAdded($event)" />
      <app-paste-claims-box (pasted)="claimsPasted()" />
      <app-plan-generate-box [refresh]="plansGenerated" />
      <app-import-people-box (imported)="peopleImported($event)" />
      <app-tree-search-box />
      <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() | figure }}</strong></p>
      <p>Due in total: <strong data-testid="rollup-due-total">{{ dueTotal() | figure }}</strong></p>
      <p>Settled due in total: <strong data-testid="rollup-settled-total">{{ settledTotal() | figure }}</strong></p>
      <p>Open due in total: <strong data-testid="rollup-open-total">{{ openTotal() | figure }}</strong></p>
      <p>Cuts in total: <strong data-testid="rollup-cut-total">{{ cutTotal() | figure }}</strong></p>
      <p>Overdue claims: <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong></p>
      <p>Overdue in total: <strong data-testid="rollup-overdue-total">{{ overdueTotal() | figure }}</strong></p>
      <dl aria-label="Consolidated with descendant sheets">
        <dt>People, consolidated</dt>
        <dd data-testid="consolidated-people-count">{{ consolidatedPeopleCount() }}</dd>
        <dt>Due, consolidated</dt>
        <dd data-testid="consolidated-due-total">{{ consolidatedDueTotal() | figure }}</dd>
        <dt>Overdue, consolidated</dt>
        <dd data-testid="consolidated-overdue-total">{{ consolidatedOverdueTotal() | figure }}</dd>
      </dl>
      @if (tagDueTotals().length > 0) {
        <ul aria-label="Due per tag">
          @for (t of tagDueTotals(); track t.tag) {
            <li>Due tagged {{ t.tag }}: <strong [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ t.due | figure }}</strong></li>
          }
        </ul>
      }
      @if (monthDueTotals().length > 0) {
        <ul aria-label="Due per month">
          @for (m of monthDueTotals(); track m.month) {
            <li>Due dated in {{ m.month }}: <strong [attr.data-testid]="'month-' + m.month + '-due-total'">{{ m.due | figure }}</strong></li>
          }
        </ul>
      }
      <app-summary-panel [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()"
        [voidReasons]="voidReasonSummary()" />
      <app-classic-summary-panel [periods]="classicPeriodSummary()" [dueTotal]="classicDueTotal()" />
      <label>
        <input type="checkbox" data-testid="show-retired" [checked]="showRetired()"
          (change)="showRetired.set($any($event.target).checked)" />
        Show retired
      </label>
      <fieldset>
        <legend>Columns shown on your screen</legend>
        @for (c of columns; track c) {
          <label>
            <input type="checkbox" [attr.data-testid]="'column-' + c" [checked]="shows(c)"
              (change)="setColumnShown(c, $any($event.target).checked)" />
            {{ c }}
          </label>
        }
        @if (columnsError()) {
          <p role="alert" data-testid="columns-error">{{ columnsError() }}</p>
        }
      </fieldset>
      <label for="sort-by">Sort by</label>
      <select id="sort-by" data-testid="sort-by" [value]="sortBy()"
        (change)="sortBy.set($any($event.target).value)">
        <option value="added">Added</option>
        <option value="name">Name</option>
        <option value="grade">Grade</option>
        <option value="due-total">Due total</option>
      </select>
      <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
      @if (bulkOpened(); as b) {
        <p role="status">Opened <strong data-testid="bulk-opened">{{ b.opened }}</strong>,
          refused <strong data-testid="bulk-refused">{{ b.refused }}</strong></p>
      }
      <button type="button" data-testid="bulk-settle" (click)="bulkSettle()">Settle selected claims</button>
      <span>Last batch <strong data-testid="bulk-settle-batch">{{ lastSettleBatch() ?? '' }}</strong></span>
      @if (bulkSettled(); as b) {
        <p role="status">Settled <strong data-testid="bulk-settled">{{ b.settled }}</strong>,
          refused <strong data-testid="bulk-settle-refused">{{ b.refused }}</strong></p>
      }
      <label for="bulk-reassign-to">Reassign selected claims to</label>
      <select #bulkReassignTo id="bulk-reassign-to" data-testid="bulk-reassign-to">
        @for (p of people(); track p.id) {
          <option [value]="p.id">{{ p.name }}</option>
        }
      </select>
      <button type="button" data-testid="bulk-reassign" (click)="bulkReassign(bulkReassignTo.value)">Reassign
        selected claims</button>
      @if (bulkReassigned(); as b) {
        <p role="status">Reassigned <strong data-testid="bulk-reassigned">{{ b.reassigned }}</strong>,
          refused <strong data-testid="bulk-reassign-refused">{{ b.refused }}</strong></p>
      }
      <label for="bulk-redate-date">Re-date selected claims to</label>
      <input #bulkRedateDate id="bulk-redate-date" type="date" data-testid="bulk-redate-date" />
      <button type="button" data-testid="bulk-redate" (click)="bulkRedate(bulkRedateDate.value)">Re-date
        selected claims</button>
      @if (bulkRedated(); as b) {
        <p role="status">Re-dated <strong data-testid="bulk-redated">{{ b.redated }}</strong>,
          refused <strong data-testid="bulk-redate-refused">{{ b.refused }}</strong></p>
      }
      <div role="group" aria-labelledby="bulk-grade-heading">
        <span id="bulk-grade-heading">Grade selected people</span>
        <label for="bulk-grade">Grade</label>
        <select #bulkGrade id="bulk-grade" data-testid="bulk-grade" (change)="bulkGradeError.set('')">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">From</label>
        <input #bulkGradeFrom id="bulk-grade-from" type="date" data-testid="bulk-grade-from"
          (input)="bulkGradeError.set('')" />
        <button type="button" data-testid="bulk-grade-apply"
          (click)="gradeSelected(bulkGrade.value, bulkGradeFrom.value)">Grade selected people</button>
        @if (bulkGraded() !== null) {
          <p role="status">Changed <strong data-testid="bulk-graded">{{ bulkGraded() }}</strong></p>
        }
        @if (bulkGradeError()) {
          <p role="alert" data-testid="person-error">{{ bulkGradeError() }}</p>
        }
      </div>
      <div role="group" aria-labelledby="bulk-retire-heading">
        <span id="bulk-retire-heading">Retire selected people</span>
        <label for="bulk-retire-date">From</label>
        <input #bulkRetireDate id="bulk-retire-date" type="date" data-testid="bulk-retire-date"
          (input)="bulkRetireError.set('')" />
        <button type="button" data-testid="bulk-retire"
          (click)="retireSelected(bulkRetireDate.value)">Retire selected people</button>
        @if (bulkRetired() !== null) {
          <p role="status">Retired <strong data-testid="bulk-retired">{{ bulkRetired() }}</strong></p>
        }
        @if (bulkRetireError()) {
          <p role="alert" data-testid="person-error">{{ bulkRetireError() }}</p>
        }
      </div>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <button type="button" data-testid="export-claims" (click)="exportClaimsCsv()">Export claims (CSV)</button>
      <table data-testid="people">
        <thead>
          <tr><th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Active</th>@if (shows('terms')) {<th scope="col">Terms</th>}@if (shows('contact')) {<th scope="col">Contact</th>}<th scope="col">Tags</th>@if (shows('aliases')) {<th scope="col">Aliases</th>}<th scope="col">Clashing</th><th scope="col">Came from</th>@if (shows('claimed')) {<th scope="col">Claimed</th>}@if (shows('due-total')) {<th scope="col">Due</th>}<th scope="col">Settled due</th><th scope="col">Open due</th>@for (pd of periods(); track pd.id) {<th scope="col">Due in {{ pd.name }}</th><th scope="col">Carried into {{ pd.name }}</th>}@if (shows('excess')) {<th scope="col">Excess</th>}<th scope="col">Cut total</th>@if (shows('statement-count')) {<th scope="col">Statements</th>}@if (shows('stated-total')) {<th scope="col">Last stated</th>}@if (shows('statement-date')) {<th scope="col">Stated on</th>}@if (shows('retired')) {<th scope="col">Retired</th>}<th scope="col">Retired from</th><th scope="col">Past retirements</th><th scope="col">Snapshots</th><th scope="col">Statements taken</th>@if (shows('hold')) {<th scope="col">On hold</th>}@if (shows('overdue-count')) {<th scope="col">Overdue</th>}@if (shows('overdue-total')) {<th scope="col">Overdue due</th>}<th scope="col">Due aged 0–30 days</th><th scope="col">Due aged 31–60 days</th><th scope="col">Due aged 61+ days</th>@if (shows('patrons')) {<th scope="col">Patrons</th>}@if (shows('backing')) {<th scope="col">Backing</th>}<th scope="col">Watched</th><th scope="col">Plans</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of sorted(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td>
                <input type="checkbox" data-testid="person-select" [checked]="selectedPeople().has(p.id)"
                  (change)="selectPerson(p.id, $any($event.target).checked)"
                  [attr.aria-label]="'Select ' + p.name" />
              </td>
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              <td data-testid="person-active">{{ activeOf(p) ? 'yes' : 'no' }}</td>
              @if (shows('terms')) {
                <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              }
              @if (shows('contact')) {
                <td data-testid="person-contact">{{ p.contact ?? '' }}</td>
              }
              <td data-testid="person-tags">{{ p.tags }}</td>
              @if (shows('aliases')) {
                <td data-testid="person-aliases">{{ p.aliases.join(', ') }}</td>
              }
              <td data-testid="person-clashing">{{ p.clashing ? 'yes' : 'no' }}</td>
              <td data-testid="person-origin">{{ p.origin ?? '' }}</td>
              @if (shows('claimed')) {
                <td data-testid="person-claimed">{{ claimed().get(p.id) ?? '0.00' | figure }}</td>
              }
              @if (shows('due-total')) {
                <td data-testid="person-due-total">{{ dueOf(p) ?? 'n/a' | figure }}</td>
              }
              <td data-testid="person-settled-total">{{ settledTotalOf(p) | figure }}</td>
              <td data-testid="person-open-total">{{ openTotalOf(p) | figure }}</td>
              @for (pd of periods(); track pd.id) {
                <td [attr.data-testid]="'person-period-' + pd.name + '-due-total'">{{ periodDueOf(p.id, pd) | figure }}</td>
                <td [attr.data-testid]="'person-period-' + pd.name + '-carried'">{{ carriedOf(p.id, pd) | figure }}</td>
              }
              @if (shows('excess')) {
                <td data-testid="person-excess">{{ excessOf(p) | figure }}</td>
              }
              <td data-testid="person-cut-total">{{ cutTotals().get(p.id) ?? '0.00' | figure }}</td>
              @if (shows('statement-count')) {
                <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              }
              @if (shows('stated-total')) {
                <td data-testid="person-stated-total">{{ (p.statementCount > 0 ? (p.statedTotal ?? 'n/a') : '') | figure }}</td>
              }
              @if (shows('statement-date')) {
                <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              }
              @if (shows('retired')) {
                <td data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</td>
              }
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td>
                @if (p.retirements.length > 0) {
                  <ul>
                    @for (r of p.retirements; track $index) {
                      <li data-testid="person-retirement-line">{{ r.from }} to {{ r.to }}</li>
                    }
                  </ul>
                }
              </td>
              <td>
                @if (p.snapshots.length > 0) {
                  <ul>
                    @for (s of p.snapshots; track $index) {
                      <li data-testid="person-snapshot-line">{{ s.day }} {{ s.dueTotal }}</li>
                    }
                  </ul>
                }
              </td>
              <td>
                @if (p.statements.length > 0) {
                  <ul>
                    @for (st of p.statements; track $index) {
                      <li data-testid="person-statement-line-line">{{ st.date }} {{ st.dueTotal ?? 'n/a' | figure }}</li>
                    }
                  </ul>
                }
              </td>
              @if (shows('hold')) {
                <td>
                  <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                  @if (p.holds.length > 0) {
                    <ul>
                      @for (h of p.holds; track $index) {
                        <li data-testid="person-hold-line">{{ h.from ?? '…' }} – {{ h.to ?? '…' }}</li>
                      }
                    </ul>
                  }
                </td>
              }
              @if (shows('overdue-count')) {
                <td data-testid="person-overdue-count">{{ overdueOf(p).length }}</td>
              }
              @if (shows('overdue-total')) {
                <td data-testid="person-overdue-total">{{ overdueTotalOf(p) | figure }}</td>
              }
              @let aged = agedOf(p);
              <td data-testid="person-aged-0-30">{{ aged[0] | figure }}</td>
              <td data-testid="person-aged-31-60">{{ aged[1] | figure }}</td>
              <td data-testid="person-aged-61">{{ aged[2] | figure }}</td>
              @if (shows('patrons')) {
                <td data-testid="person-patrons">{{ patronNames().get(p.id) ?? '' }}</td>
              }
              @if (shows('backing')) {
                <td data-testid="person-backing">{{ backingOf(p) | figure }}</td>
              }
              <td data-testid="person-watched">{{ watched().has(p.id) ? 'yes' : 'no' }}</td>
              <td>
                <app-person-plans [personId]="p.id" [name]="p.name" [plans]="plansByPerson().get(p.id) ?? []"
                  (added)="planAdded($event)" />
              </td>
              <td>
                <app-person-claims [personId]="p.id" [claims]="claimsByPerson().get(p.id) ?? []"
                  [rates]="rates()" [floor]="floor()" [grades]="p.grades" [periods]="periods()"
                  [today]="session.today()" [latePct]="latePct()" [graces]="graces()"
                  [policyDate]="policyDate()" [roundings]="roundings()" [selected]="selected()"
                  (selectedChange)="select($event.id, $event.selected)"
                  [others]="patronChoices(p)" (saved)="claimSaved($event)" />
              </td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="open(p)"
                  [attr.aria-label]="'Edit ' + p.name">Edit</button>
                <label [for]="'person-inline-grade-' + p.id" class="visually-hidden">Grade of {{ p.name }}</label>
                <select #inlineGrade [id]="'person-inline-grade-' + p.id" data-testid="person-inline-grade"
                  [value]="gradeOf(p)" (change)="clearPersonError(p.id)">
                  @for (g of grades; track g) {
                    <option [value]="g" [selected]="g === gradeOf(p)">{{ g }}</option>
                  }
                </select>
                <button type="button" data-testid="person-inline-grade-save" (click)="saveGrade(p, inlineGrade.value)"
                  [attr.aria-label]="'Save the grade of ' + p.name">Save grade</button>
                <button type="button" data-testid="person-move-up" (click)="move(p, 'up')"
                  [attr.aria-label]="'Move ' + p.name + ' up'">Up</button>
                <button type="button" data-testid="person-move-down" (click)="move(p, 'down')"
                  [attr.aria-label]="'Move ' + p.name + ' down'">Down</button>
                <label [for]="'person-statement-asof-' + p.id" class="visually-hidden">Take the statement of {{ p.name }} as of</label>
                <input #statementAsOf type="date" [id]="'person-statement-asof-' + p.id" data-testid="person-statement-asof" />
                <button type="button" data-testid="person-statement" (click)="statement(p, statementAsOf)"
                  [attr.aria-label]="'Take a statement of ' + p.name">Statement</button>
                <button type="button" data-testid="person-unstate" (click)="unstate(p)"
                  [attr.aria-label]="'Cancel the last statement of ' + p.name">Cancel statement</button>
                <label [for]="'person-retire-date-' + p.id" class="visually-hidden">Retire {{ p.name }} from</label>
                <input #retireDate type="date" [id]="'person-retire-date-' + p.id" data-testid="person-retire-date" />
                <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)"
                  [attr.aria-label]="'Retire ' + p.name">Retire</button>
                <button type="button" data-testid="person-unretire" (click)="unretire(p)"
                  [attr.aria-label]="'Unretire ' + p.name">Unretire</button>
                <label [for]="'person-hold-from-' + p.id" class="visually-hidden">Hold {{ p.name }} from</label>
                <input #holdFrom type="date" [id]="'person-hold-from-' + p.id" data-testid="person-hold-from" />
                <label [for]="'person-hold-to-' + p.id" class="visually-hidden">Hold {{ p.name }} to</label>
                <input #holdTo type="date" [id]="'person-hold-to-' + p.id" data-testid="person-hold-to" />
                <button type="button" data-testid="person-hold-toggle" (click)="hold(p, holdFrom.value, holdTo.value)"
                  [attr.aria-label]="'Hold ' + p.name">Hold</button>
                <label [for]="'person-alias-input-' + p.id" class="visually-hidden">Alias of {{ p.name }}</label>
                <input #aliasInput [id]="'person-alias-input-' + p.id" data-testid="person-alias-input"
                  (input)="clearPersonError(p.id)" />
                <button type="button" data-testid="person-alias-add" (click)="addAlias(p, aliasInput)"
                  [attr.aria-label]="'Add an alias of ' + p.name">Add alias</button>
                <label [for]="'person-patron-select-' + p.id" class="visually-hidden">Patron of {{ p.name }}</label>
                <select #patronSelect [id]="'person-patron-select-' + p.id" data-testid="person-patron-select"
                  (change)="patronError.set(null)">
                  <option value="">Choose a patron</option>
                  @for (o of patronChoices(p); track o.id) {
                    <option [value]="o.id">{{ o.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-patron-add" (click)="addPatron(p, patronSelect.value)"
                  [attr.aria-label]="'Add a patron of ' + p.name">Add patron</button>
                <label [for]="'person-merge-into-' + p.id" class="visually-hidden">Merge {{ p.name }} into</label>
                <select #mergeInto [id]="'person-merge-into-' + p.id" data-testid="person-merge-into"
                  (change)="clearPersonError(p.id)">
                  <option value="">Choose who to merge into</option>
                  @for (o of people(); track o.id) {
                    <option [value]="o.id">{{ o.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-merge" (click)="merge(p, mergeInto.value)"
                  [attr.aria-label]="'Merge ' + p.name + ' into the person chosen'">Merge</button>
                <label [for]="'person-transfer-to-' + p.id" class="visually-hidden">Transfer {{ p.name }} to sheet</label>
                <input #transferTo [id]="'person-transfer-to-' + p.id" data-testid="person-transfer-to"
                  (input)="clearPersonError(p.id)" />
                <button type="button" data-testid="person-transfer" (click)="transfer(p, transferTo.value)"
                  [attr.aria-label]="'Transfer ' + p.name + ' to the sheet given'">Transfer</button>
                <button type="button" data-testid="person-watch" (click)="toggleWatch(p)"
                  [attr.aria-pressed]="watched().has(p.id)"
                  [attr.aria-label]="'Watch ' + p.name">{{ watched().has(p.id) ? 'Unwatch' : 'Watch' }}</button>
                @if (patronError()?.id === p.id) {
                  <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                }
                @if (personError()?.id === p.id) {
                  <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
    <app-pool-list [people]="people()" />
    <app-crew-list [people]="people()" [dues]="dueByPerson()" />
  `,
})
export class PeopleList {
  protected readonly session = inject(Session);
  private readonly voidReasons = inject(VoidReasons);
  private readonly api = inject(People);
  private readonly claimsApi = inject(Claims);
  private readonly ratesApi = inject(Rates);
  private readonly periodsApi = inject(Periods);
  private readonly settingsApi = inject(SheetSettings);
  private readonly patronsApi = inject(Patrons);
  private readonly watchesApi = inject(Watches);
  private readonly plansApi = inject(Plans);
  private readonly poolsApi = inject(Pools);
  private readonly descendantsApi = inject(Descendants);
  private readonly crewsApi = inject(Crews);

  protected readonly grades: Grade[] = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  /** Whether retired people are listed; off when the screen opens, kept as it is across loads. */
  protected readonly showRetired = signal(false);
  /** The people-list columns the signed-in user can switch off on their screen. */
  protected readonly columns = PERSON_COLUMNS;
  protected readonly columnsError = signal('');
  /** The people listed: everyone, or only those not retired unless "show retired" is on. */
  protected readonly shown = computed(() =>
    this.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The key this screen sorts the listed people by; added (list order) when the screen opens,
   *  kept as it is across loads. Only what is shown is sorted: actions keep the list order. */
  protected readonly sortBy = signal<SortKey>('added');
  /** The people listed, in the order the screen shows them: a stable sort, so equal keys keep
   *  list order. */
  protected readonly sorted = computed(() => {
    const key = this.sortBy();
    const people = this.shown();
    if (key === 'added') return people;
    const compare = (a: Person, b: Person): number => {
      switch (key) {
        case 'name': {
          const x = a.name.trim().toLowerCase();
          const y = b.name.trim().toLowerCase();
          return x < y ? -1 : x > y ? 1 : 0;
        }
        case 'grade':
          return this.gradeOf(a).localeCompare(this.gradeOf(b));
        case 'due-total': {
          const x = this.dueOf(a);
          const y = this.dueOf(b);
          if (x === null || y === null) return (x === null ? 1 : 0) - (y === null ? 1 : 0);
          return Number(x) - Number(y);
        }
      }
    };
    return [...people].sort(compare);
  });
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  /** Every tag the store keeps as ever used on the sheet. */
  private readonly storedTags = signal<string[]>([]);
  protected readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  /** The patrons named on the sheet, in the order they were named. */
  protected readonly patrons = signal<Patron[]>([]);
  /** Each person's patrons' ids, by the person's id. */
  private readonly patronIds = computed(() => {
    const by = new Map<number, number[]>();
    for (const x of this.patrons()) by.set(x.personId, [...(by.get(x.personId) ?? []), x.patronId]);
    return by;
  });
  /** Each person's patrons with their shares (in hundredths), in the order they were named, by the
   *  person's id. */
  private readonly patronShares = computed(() => {
    const by = new Map<number, { id: number; share: number }[]>();
    for (const [id, ps] of this.patronIds()) {
      const shares = patronShares(ps.length);
      by.set(id, ps.map((x, i) => ({ id: x, share: shares[i] })));
    }
    return by;
  });
  /** Each person's patrons as name and share, sorted by name and joined by a comma and a space, by
   *  the person's id. */
  protected readonly patronNames = computed(() => {
    const names = new Map(this.people().map((p) => [p.id, p.name]));
    const by = new Map<number, string>();
    for (const [id, ps] of this.patronShares()) {
      const shown = ps.flatMap((x) => {
        const name = names.get(x.id);
        return name === undefined ? [] : [{ name, share: x.share }];
      });
      shown.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
      by.set(id, shown.map((x) => `${x.name} ${formatCents(x.share)}`).join(', '));
    }
    return by;
  });
  /** The plans on the sheet, in the order they were added. */
  protected readonly plans = signal<Plan[]>([]);
  /** Each person's plans, in the order they were added, by the person's id. */
  protected readonly plansByPerson = computed(() => {
    const by = new Map<number, Plan[]>();
    for (const x of this.plans()) by.set(x.personId, [...(by.get(x.personId) ?? []), x]);
    return by;
  });
  /** The people the signed-in user watches, in the order they started watching. */
  protected readonly watches = signal<Watch[]>([]);
  /** The ids of the people the signed-in user watches. */
  protected readonly watched = computed(() => new Set(this.watches().map((w) => w.personId)));
  /** Each person, by id: their holds and retirement leave claims out of the live totals. */
  private readonly byId = computed(() => new Map(this.people().map((p) => [p.id, p])));
  /** The claims that count in the live totals, newest first. */
  private readonly live = computed(() => this.claims().filter((c) => countsLive(c, this.byId().get(c.personId) ?? NO_LEAVE, this.session.today())));
  /** What each person has claimed: the sum of their open and settled claims' amounts, leaving out
   *  the open ones dated within one of their holds and unbound by a statement, and the corrections. */
  protected readonly claimed = computed(() => {
    const by = new Map<number, string>();
    for (const [id, cs] of this.claimsByPerson()) {
      by.set(id, sumAmounts(cs.filter((c) => !c.corrects && countsLive(c, this.byId().get(id) ?? NO_LEAVE, this.session.today())).map((c) => c.amount)));
    }
    return by;
  });
  protected readonly claimedTotal = computed(() => sumAmounts(this.live().filter((c) => !c.corrects).map((c) => c.amount)));
  /** The rates set on the sheet. */
  protected readonly rates = signal<Rate[]>([]);
  /** The sheet's floors recorded: the least live due a claim has is the one in force on its date. */
  protected readonly floor = signal<DatedValue[]>([]);
  /** The sheet's caps recorded (null: no cap), each from its from-date. */
  private readonly caps = signal<DatedValue<string | null>[]>([]);
  /** The sheet's cap in force on its today: the most a person's due-total comes to; null when there
   *  is no cap. */
  protected readonly cap = computed(() => {
    const today = this.session.today();
    return today === null ? null : valueOn(this.caps(), today, null);
  });
  /** The sheet's late percents recorded: what an overdue claim gains for every full 30 days it is
   *  overdue is the one in force on its date. */
  protected readonly latePct = signal<DatedValue[]>([]);
  /** The grace days recorded for each grade: a claim is overdue only once its due-by plus those in
   *  force on its date for its person's grade then is before the sheet's today. */
  protected readonly graces = signal<Graces>({});
  /** The sheet's policy date (null when blank): it decides how a claim's live figures round. */
  protected readonly policyDate = signal<string | null>(null);
  /** The sheet's rounding modes recorded: the one in force on a claim's date decides how its live
   *  figures round, before the policy date. */
  protected readonly roundings = signal<DatedValue<RoundingMode | null>[]>([]);
  /** What the late charges of overdue claims, and the rounding of live figures, follow. */
  private readonly lateness = computed(() => ({
    today: this.session.today(), pcts: this.latePct(), graces: this.graces(), policyDate: this.policyDate(),
    roundings: this.roundings(),
  }));
  /** The periods on the sheet, earliest first. */
  protected readonly periods = signal<Period[]>([]);
  /** Each person's live due-total for each period, by the person's id then the period's id: the due
   *  figures of their claims whose due-by falls in it and that count by their status at its end,
   *  skipping those with no due. */
  private readonly livePeriodDues = computed(() => {
    const people = new Map(this.people().map((p) => [p.id, p]));
    const figures = new Map<number, Map<number, string[]>>();
    for (const c of this.claims()) {
      const period = periodOf(this.periods(), c.dueBy);
      const person = people.get(c.personId);
      if (period === undefined || person === undefined) continue;
      if (!countsAsOf(c, person, period.to, this.session.today())) continue;
      const d = claimDue(c, this.rates(), gradeOn(person.grades, c.date), this.floor(), this.lateness());
      if (d === null) continue;
      const theirs = figures.get(person.id) ?? new Map<number, string[]>();
      theirs.set(period.id, [...(theirs.get(period.id) ?? []), d]);
      figures.set(person.id, theirs);
    }
    const by = new Map<number, Map<number, string>>();
    for (const [id, theirs] of figures) by.set(id, new Map([...theirs].map(([pid, ds]) => [pid, sumAmounts(ds)])));
    return by;
  });
  /** Each person's carried figure for each period, by the person's id then the period's id: the due
   *  figures of their claims that count whose due-by falls in a period ending before it starts and
   *  that were still open (or pending) by their status at its end, skipping those with no due. */
  private readonly carried = computed(() => {
    const people = new Map(this.people().map((p) => [p.id, p]));
    const today = this.session.today();
    const figures = new Map<number, Map<number, string[]>>();
    for (const c of this.claims()) {
      const belongs = periodOf(this.periods(), c.dueBy);
      const person = people.get(c.personId);
      if (belongs === undefined || person === undefined) continue;
      let d: string | null | undefined;
      for (const pd of this.periods()) {
        if (belongs.to >= pd.from || !countsAsOf(c, person, pd.to, today)) continue;
        const then = statusOn(c, pd.to);
        if (then !== 'OPEN' && then !== 'PENDING') continue;
        if (d === undefined) d = claimDue(c, this.rates(), gradeOn(person.grades, c.date), this.floor(), this.lateness());
        if (d === null) break;
        const theirs = figures.get(person.id) ?? new Map<number, string[]>();
        theirs.set(pd.id, [...(theirs.get(pd.id) ?? []), d]);
        figures.set(person.id, theirs);
      }
    }
    const by = new Map<number, Map<number, string>>();
    for (const [id, theirs] of figures) by.set(id, new Map([...theirs].map(([pid, ds]) => [pid, sumAmounts(ds)])));
    return by;
  });
  /** Each period's due-total by its id: the sum of the people's due-totals for it. */
  protected readonly periodDueTotals = computed(() =>
    new Map(this.periods().map((pd) => [pd.id, sumAmounts(this.people().map((p) => this.periodDueOf(p.id, pd)))])),
  );
  /** Each grade's rate in force on the sheet's today; a grade with none is missing. */
  protected readonly pctOf = computed(() => {
    const today = this.session.today();
    const by = new Map<Grade, string>();
    if (today === null) return by;
    for (const g of this.grades) {
      const pct = pctOn(this.rates(), g, today);
      if (pct !== undefined) by.set(g, pct);
    }
    return by;
  });
  /** The sheet's due-total: everyone's due-total added up, skipping those that are n/a, and leaving
   *  out the single highest and lowest when three or more have one. */
  protected readonly dueTotal = computed(() =>
    trimmedSum(this.people().map((p) => this.dueOf(p)).filter((d): d is string => d !== null)),
  );
  /** The sheet's settled-total: everyone's settled-total added up (not trimmed). */
  protected readonly settledTotal = computed(() =>
    sumAmounts(this.people().map((p) => this.settledTotalOf(p))),
  );
  /** The sheet's open-total: everyone's open-total added up (not trimmed). */
  protected readonly openTotal = computed(() =>
    sumAmounts(this.people().map((p) => this.openTotalOf(p))),
  );
  /** Per person, their cuts across every pool they are a member of added up (a pool that shows no
   *  cuts adds nothing). */
  protected readonly cutTotals = computed(() => {
    const cuts = new Map<number, string[]>();
    for (const pool of this.poolsApi.list()) {
      for (const m of pool.members) {
        if (m.personId === null || m.cut === null) continue;
        cuts.set(m.personId, [...(cuts.get(m.personId) ?? []), m.cut]);
      }
    }
    return new Map([...cuts].map(([id, cs]) => [id, sumAmounts(cs)]));
  });
  /** The sheet's cut total: everyone's cut total added up (not trimmed). */
  protected readonly cutTotal = computed(() =>
    sumAmounts(this.people().map((p) => this.cutTotals().get(p.id) ?? '0.00')),
  );
  /** Per tag ever used on the sheet (those the store keeps, and those of the claims and people
   *  shown), in order: the due figures of the claims that count which carry it, or whose person
   *  carries it (counted once), skipping those with no due (not trimmed, not capped). */
  protected readonly tagDueTotals = computed(() => {
    const figures = new Map<string, string[]>(this.storedTags().map((t) => [t, []]));
    for (const p of this.people()) for (const t of splitTags(p.tags)) if (!figures.has(t)) figures.set(t, []);
    for (const c of this.claims()) {
      const p = this.byId().get(c.personId);
      const tags = [...new Set([...splitTags(c.tags), ...splitTags(p?.tags ?? '')])];
      for (const t of tags) if (!figures.has(t)) figures.set(t, []);
      if (tags.length === 0 || !p || !countsLive(c, p, this.session.today())) continue;
      const d = claimDue(c, this.rates(), gradeOn(p.grades, c.date), this.floor(), this.lateness());
      if (d !== null) for (const t of tags) figures.get(t)!.push(d);
    }
    return [...figures.keys()].sort().map((tag) => ({ tag, due: sumAmounts(figures.get(tag)!) }));
  });
  /** Per calendar month (yyyy-mm) any claim on the sheet is dated in, earliest first: the due
   *  figures of the claims dated in it that count, skipping those with no due (not trimmed, not capped). */
  protected readonly monthDueTotals = computed(() => {
    const figures = new Map<string, string[]>();
    for (const c of this.claims()) {
      const month = c.date.slice(0, 7);
      const theirs = figures.get(month) ?? [];
      figures.set(month, theirs);
      const p = this.byId().get(c.personId);
      if (!p || !countsLive(c, p, this.session.today())) continue;
      const d = claimDue(c, this.rates(), gradeOn(p.grades, c.date), this.floor(), this.lateness());
      if (d !== null) theirs.push(d);
    }
    return [...figures.keys()].sort().map((month) => ({ month, due: sumAmounts(figures.get(month)!) }));
  });
  /** How many claims on the sheet are overdue among those that count in the live totals. */
  protected readonly overdueCount = computed(() =>
    this.people().reduce((n, p) => n + this.overdueOf(p).length, 0),
  );
  /** The sheet's overdue-total: the due figures of the overdue claims that count, skipping those
   *  with no due, and leaving out the single highest and lowest person's when there are three or more. */
  protected readonly overdueTotal = computed(() =>
    trimmedSum(this.people().map((p) => this.overdueTotalOf(p))),
  );
  /** The person on the sheet who has the signed-in user's name, by name or alias, as names are
   *  compared (case and surrounding spaces ignored); null when there is none. */
  private readonly me = computed(() => {
    const user = this.session.user()?.trim().toLowerCase();
    if (!user) return null;
    return this.people().find((p) => [p.name, ...p.aliases].some((n) => n.trim().toLowerCase() === user)) ?? null;
  });
  /** The signed-in user's own due-total (n/a when theirs is); blank when no person is theirs. */
  protected readonly myDueTotal = computed(() => {
    const me = this.me();
    return me ? (this.dueOf(me) ?? 'n/a') : '';
  });
  /** The signed-in user's own overdue-total; blank when no person is theirs. */
  protected readonly myOverdueTotal = computed(() => {
    const me = this.me();
    return me ? this.overdueTotalOf(me) : '';
  });
  /** The due-totals of the crews the signed-in user's person leads, added up; blank when they lead
   *  none. */
  protected readonly myCrewDueTotal = computed(() => {
    const me = this.me();
    const led = me ? this.crewsApi.list().filter((c) => c.lead?.personId === me.id) : [];
    return led.length ? sumAmounts(led.map((c) => crewDueTotal(c, this.dueByPerson()))) : '';
  });
  /** The figures of the sheet's descendants added up, as the store held them when it was loaded. */
  private readonly descendants = signal<DescendantFigures>(NO_DESCENDANTS);
  /** The people of the sheet and all its descendants: while an as-of date is set, each sheet as it
   *  stood at the end of that day. */
  protected readonly consolidatedPeopleCount = computed(() => this.people().length + this.descendants().peopleCount);
  /** The sheet's due-total and each descendant's, as each shows it, added up. */
  protected readonly consolidatedDueTotal = computed(() => sumAmounts([this.dueTotal(), this.descendants().dueTotal]));
  /** The sheet's overdue-total and each descendant's, as each shows it, added up. */
  protected readonly consolidatedOverdueTotal = computed(() =>
    sumAmounts([this.overdueTotal(), this.descendants().overdueTotal]),
  );
  /** Per grade: the people not retired on it on the sheet's today, their due-totals added up,
   *  skipping those that are n/a, and how many of them are active. */
  protected readonly gradeSummary = computed((): GradeSummary[] =>
    this.grades.map((grade) => {
      const on = this.people().filter((p) => !p.retired && this.gradeOf(p) === grade);
      return {
        grade,
        count: on.length,
        dueTotal: sumAmounts(on.map((p) => this.dueOf(p)).filter((d): d is string => d !== null)),
        active: on.filter((p) => this.activeOf(p)).length,
      };
    }),
  );
  /** Per period, earliest first: the due-total its row shows, and its change from the period
   *  before it by start date (none for the earliest). */
  protected readonly periodSummary = computed((): PeriodSummary[] => {
    const byStart = [...this.periods()].sort((a, b) => a.from.localeCompare(b.from));
    const changes = new Map<number, string>();
    byStart.forEach((p, i) => {
      if (i > 0) {
        const cents = (q: Period) => Math.round(Number(this.periodDueTotals().get(q.id) ?? '0') * 100);
        changes.set(p.id, ((cents(p) - cents(byStart[i - 1])) / 100).toFixed(2));
      }
    });
    return this.periods().map((p) => ({
      name: p.name,
      dueTotal: this.periodDueTotals().get(p.id) ?? '0.00',
      change: changes.get(p.id) ?? '',
    }));
  });
  /** Per period, as listed: the due figures of the claims whose due-by falls in it that count by
   *  their status now, skipping those with no due (a closed period shows the figure fixed when it
   *  closed). */
  protected readonly classicPeriodSummary = computed((): ClassicPeriodSummary[] => {
    const figures = new Map<number, string[]>();
    for (const c of this.claims()) {
      const period = periodOf(this.periods(), c.dueBy);
      const person = this.byId().get(c.personId);
      if (period === undefined || person === undefined) continue;
      if (!countsLive(c, person, this.session.today())) continue;
      const d = claimDue(c, this.rates(), gradeOn(person.grades, c.date), this.floor(), this.lateness());
      if (d !== null) figures.set(period.id, [...(figures.get(period.id) ?? []), d]);
    }
    return this.periods().map((p) => ({
      name: p.name,
      dueTotal: p.closed || p.softClosed
        ? (this.periodDueTotals().get(p.id) ?? '0.00')
        : sumAmounts(figures.get(p.id) ?? []),
    }));
  });
  /** The sheet's due-total with no trimming: everyone's due-total added up, skipping those that are n/a. */
  protected readonly classicDueTotal = computed(() =>
    sumAmounts(this.people().map((p) => this.dueOf(p)).filter((d): d is string => d !== null)),
  );
  /** Per status: how many claims on the sheet have it. */
  protected readonly statusSummary = computed((): StatusSummary[] =>
    PeopleList.statuses.map((status) => ({ status, count: this.claims().filter((c) => c.status === status).length })),
  );
  /** Per void reason on the sheet's list: how many void claims were voided with it. */
  protected readonly voidReasonSummary = computed((): VoidReasonSummary[] =>
    this.voidReasons.list().map(({ reason }) => ({
      reason,
      count: this.claims().filter((c) => c.status === 'VOID' && c.voidReason === reason).length,
    })),
  );
  /** The statuses the summary counts claims of. */
  private static readonly statuses = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'REVIEW'];
  /** The ids of the claims selected for a bulk action. */
  protected readonly selected = signal<ReadonlySet<number>>(new Set());
  /** What the last bulk open did; null while one runs and before the first. */
  protected readonly bulkOpened = signal<{ opened: number; refused: number } | null>(null);
  /** Whether a bulk open is waiting for its answer: a second press meanwhile is ignored. */
  private opening = false;
  /** What the last bulk settle did; null while one runs and before the first. */
  protected readonly bulkSettled = signal<{ settled: number; refused: number } | null>(null);
  /** The number of the sheet's last settle batch; null when there has been none. */
  protected readonly lastSettleBatch = signal<number | null>(null);
  /** Whether a bulk settle is waiting for its answer: a second press meanwhile is ignored. */
  private settling = false;
  /** What the last bulk reassign did; null while one runs and before the first. */
  protected readonly bulkReassigned = signal<{ reassigned: number; refused: number } | null>(null);
  /** Whether a bulk reassign is waiting for its answer: a second press meanwhile is ignored. */
  private reassigning = false;
  /** What the last bulk re-date did; null while one runs and before the first. */
  protected readonly bulkRedated = signal<{ redated: number; refused: number } | null>(null);
  /** Whether a bulk re-date is waiting for its answer: a second press meanwhile is ignored. */
  private redating = false;
  /** The ids of the people selected for a bulk grade. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  /** How many people the last bulk grade changed; null while one runs and before the first. */
  protected readonly bulkGraded = signal<number | null>(null);
  /** The refusal of the last bulk grade. */
  protected readonly bulkGradeError = signal('');
  /** Whether a bulk grade is waiting for its answer: a second press meanwhile is ignored. */
  private grading = false;
  /** How many people the last bulk retire retired; null while one runs and before the first. */
  protected readonly bulkRetired = signal<number | null>(null);
  /** The refusal of the last bulk retire. */
  protected readonly bulkRetireError = signal('');
  /** Whether a bulk retire is waiting for its answer: a second press meanwhile is ignored. */
  private retiringAll = false;
  /** The id of the person whose editor is open, or null when the form adds a person. */
  protected readonly editing = signal<number | null>(null);
  /** The version of the person the open editor loaded. */
  private version = 0;
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal('');
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The refusal of the last patron added on a person's row, shown on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** The people whose patron is waiting for its answer: a second press meanwhile is ignored. */
  private readonly naming = new Set<number>();
  /** The people whose statement is waiting for its answer: a second press meanwhile is ignored. */
  private readonly stating = new Set<number>();
  /** The people whose hold is waiting for its answer: a second press meanwhile is ignored. */
  private readonly holding = new Set<number>();
  /** The people whose watch is waiting for its answer: a second press meanwhile is ignored. */
  private readonly watching = new Set<number>();
  /** The people whose retirement is waiting for its answer: a second press meanwhile is ignored. */
  private readonly retiring = new Set<number>();
  /** The people whose row grade is waiting for its answer: a second press meanwhile is ignored. */
  private readonly regrading = new Set<number>();
  private readonly moving = new Set<number>();
  /** The people whose merge is waiting for its answer: a second press meanwhile is ignored. */
  private readonly merging = new Set<number>();
  /** The people whose transfer is waiting for its answer: a second press meanwhile is ignored. */
  private readonly transferring = new Set<number>();
  /** The people whose alias is being added: a second press meanwhile is ignored. */
  private readonly aliasing = new Set<number>();
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    /** The day the grade applies from; blank takes the sheet's today. */
    gradeFrom: new FormControl('', { nonNullable: true }),
    /** The person's own terms in days; blank for none. */
    terms: new FormControl('', { nonNullable: true }),
    /** The day an edit's terms apply from; blank records them from the beginning of time. */
    termsFrom: new FormControl('', { nonNullable: true }),
    /** The person's contact; blank for none. */
    contact: new FormControl('', { nonNullable: true }),
    /** The person's tags, comma separated; blank for none. */
    tags: new FormControl('', { nonNullable: true }),
  });
  /** Only the newest read of the sheet may show: a load overtaken by another is dropped. */
  private reads = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      const sheetId = this.session.sheetId();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.storedTags.set([]);
      this.rates.set([]);
      this.periods.set([]);
      this.patrons.set([]);
      this.watches.set([]);
      this.plans.set([]);
      this.selected.set(new Set());
      this.bulkOpened.set(null);
      this.bulkSettled.set(null);
      this.lastSettleBatch.set(null);
      this.bulkReassigned.set(null);
      this.bulkRedated.set(null);
      this.selectedPeople.set(new Set());
      this.bulkGraded.set(null);
      this.bulkGradeError.set('');
      this.bulkRetired.set(null);
      this.bulkRetireError.set('');
      this.personError.set(null);
      this.patronError.set(null);
      this.reset();
      if (sheetId) void this.reload(sheetId);
    });
    effect(() => {
      const saved = this.settingsApi.saved();
      if (saved === null || saved.sheetId !== untracked(this.session.sheetId)) return;
      this.floor.set(saved.settings.floors);
      this.caps.set(saved.settings.caps);
      this.latePct.set(saved.settings.latePcts);
      this.graces.set(saved.settings.graces);
      this.policyDate.set(saved.settings.policyDate);
      this.roundings.set(saved.settings.roundings);
    });
  }

  /** Whether the signed-in user's screen shows a people-list column. */
  protected shows(column: PersonColumn): boolean {
    return !this.session.hiddenColumns().has(column);
  }

  protected async setColumnShown(column: PersonColumn, shown: boolean): Promise<void> {
    this.columnsError.set('');
    try {
      await this.session.setColumnShown(column, shown);
    } catch {
      this.columnsError.set('Your column choice could not be saved.');
    }
  }

  protected open(p: Person): void {
    this.personError.set(null);
    this.patronError.set(null);
    this.editing.set(p.id);
    this.version = p.version;
    this.form.setValue({ name: p.name, grade: this.gradeOf(p), gradeFrom: '',
      terms: p.undatedTerms?.toString() ?? '', termsFrom: '', contact: p.contact ?? '', tags: p.tags });
    this.error.set('');
  }

  /** Take a statement of the person as of the day entered (blank: now), then show them and their
   *  claims as it left them; the day is cleared whether or not it was taken, so it never carries
   *  into the next statement. */
  protected async statement(p: Person, asOf: HTMLInputElement): Promise<void> {
    if (this.stating.has(p.id)) return;
    this.stating.add(p.id);
    this.personError.set(null);
    this.patronError.set(null);
    const day = asOf.value || null;
    asOf.value = '';
    try {
      this.showTaken(await this.api.statement(p.id, day));
    } catch {
      this.personError.set({ id: p.id, message: 'The statement could not be taken.' });
    } finally {
      this.stating.delete(p.id);
    }
  }

  /** Cancel the person's last statement, then show them and their claims as it left them. */
  protected async unstate(p: Person): Promise<void> {
    if (this.stating.has(p.id)) return;
    this.stating.add(p.id);
    this.personError.set(null);
    this.patronError.set(null);
    try {
      this.showTaken(await this.api.cancelStatement(p.id));
    } catch {
      this.personError.set({ id: p.id, message: 'The statement could not be cancelled.' });
    } finally {
      this.stating.delete(p.id);
    }
  }

  private showTaken(taken: Taken): void {
    this.people.update((ps) => ps.map((x) => (x.id === taken.person.id ? taken.person : x)));
    const byId = new Map(taken.claims.map((c) => [c.id, c]));
    this.claims.update((cs) => [...cs.filter((c) => !byId.has(c.id)), ...taken.claims].sort((a, b) => b.listed - a.listed));
  }

  /** Name the person chosen as the person's patron. */
  protected async addPatron(p: Person, patronId: string): Promise<void> {
    if (this.naming.has(p.id)) return;
    this.personError.set(null);
    this.patronError.set(null);
    const id = Number(patronId);
    if (!patronId || id === p.id) {
      this.patronError.set({ id: p.id, message: 'Choose another person as patron.' });
      return;
    }
    if ((this.patronIds().get(p.id) ?? []).includes(id)) {
      this.patronError.set({ id: p.id, message: 'That person is already a patron.' });
      return;
    }
    this.naming.add(p.id);
    try {
      const added = await this.patronsApi.add(p.id, id);
      this.patrons.update((xs) => [...xs.filter((x) => x.id !== added.id), added]);
    } catch (e) {
      this.patronError.set({
        id: p.id,
        message: e instanceof HttpErrorResponse && e.status === 409 ? 'That person is already a patron.' : 'The patron could not be added.',
      });
    } finally {
      this.naming.delete(p.id);
    }
  }

  /** Start watching the person as the signed-in user, or stop when they already do. */
  protected async toggleWatch(p: Person): Promise<void> {
    if (this.watching.has(p.id)) return;
    this.watching.add(p.id);
    this.personError.set(null);
    this.patronError.set(null);
    const on = !this.watched().has(p.id);
    try {
      if (on) {
        const added = await this.watchesApi.watch(p.id);
        this.watches.update((ws) => [...ws.filter((w) => w.personId !== p.id), added]);
      } else {
        await this.watchesApi.unwatch(p.id);
        this.watches.update((ws) => ws.filter((w) => w.personId !== p.id));
      }
    } catch {
      this.personError.set({ id: p.id, message: on ? 'The person could not be watched.' : 'The person could not be unwatched.' });
    } finally {
      this.watching.delete(p.id);
    }
  }

  /** The people a person may name as patron: everyone else on the sheet. */
  protected patronChoices(p: Person): Person[] {
    return this.people().filter((x) => x.id !== p.id);
  }

  /** A person's backing: over their patrons, the frozen figures of the patron's settled claims
   *  dated within the 90 days up to and including the sheet's today, added up, times the patron's
   *  share divided by 100; added up and rounded half to even, 0.00 with none. */
  protected backingOf(p: Person): string {
    const today = this.session.today();
    return weightedSum((this.patronShares().get(p.id) ?? []).map((x) => ({
      amount: sumAmounts((this.claimsByPerson().get(x.id) ?? [])
        .map((c) => backingFigure(c, today))
        .filter((d): d is string => d !== null)),
      pct: formatCents(x.share),
    })));
  }

  /** Swap the person with the one above or below them; the list then shows the new order. */
  protected async move(p: Person, direction: 'up' | 'down'): Promise<void> {
    if (this.moving.has(p.id)) return;
    this.moving.add(p.id);
    this.personError.set(null);
    this.patronError.set(null);
    try {
      this.people.set(await this.api.move(p.id, direction));
    } catch {
      this.personError.set({ id: p.id, message: `The person could not be moved ${direction}.` });
    } finally {
      this.moving.delete(p.id);
    }
  }

  /** Give the person the grade chosen on their row from the sheet's today, saved as the person form
   *  would save it over the version the row shows. */
  protected async saveGrade(p: Person, grade: string): Promise<void> {
    if (this.regrading.has(p.id)) return;
    this.personError.set(null);
    this.patronError.set(null);
    if (!this.grades.includes(grade as Grade)) {
      this.personError.set({ id: p.id, message: 'Choose a grade of A, B or C.' });
      return;
    }
    this.regrading.add(p.id);
    try {
      const saved = await this.api.edit(p.id, p.version, { name: p.name, grade: grade as Grade, gradeFrom: null,
        terms: p.undatedTerms?.toString() ?? '', contact: p.contact ?? '', tags: p.tags });
      this.people.update((ps) => ps.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editing() === saved.id) this.version = saved.version;
    } catch (e) {
      this.personError.set({
        id: p.id,
        message: e instanceof HttpErrorResponse && e.status === 412
          ? 'Someone else saved this person in the meantime. Load the sheet again to see their current values.'
          : 'The grade could not be saved.',
      });
    } finally {
      this.regrading.delete(p.id);
    }
  }

  /** Retire the person from the day given, or the sheet's today when it is left blank. */
  protected async retire(p: Person, from: string): Promise<void> {
    if (this.retiring.has(p.id)) return;
    this.retiring.add(p.id);
    this.personError.set(null);
    this.patronError.set(null);
    try {
      const saved = await this.api.retire(p.id, from.trim() || null);
      // a retired member gets no cut: read the pools again
      await this.poolsApi.refresh();
      this.people.update((ps) => ps.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editing() === saved.id) this.version = saved.version;
    } catch {
      this.personError.set({ id: p.id, message: 'The person could not be retired.' });
    } finally {
      this.retiring.delete(p.id);
    }
  }

  /** Unretire the person on the sheet's today; refused when they are not retired. */
  protected async unretire(p: Person): Promise<void> {
    if (this.retiring.has(p.id)) return;
    this.retiring.add(p.id);
    this.personError.set(null);
    this.patronError.set(null);
    try {
      const saved = await this.api.unretire(p.id);
      // a retired member gets no cut: read the pools again
      await this.poolsApi.refresh();
      this.people.update((ps) => ps.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editing() === saved.id) this.version = saved.version;
    } catch {
      this.personError.set({ id: p.id, message: 'The person could not be unretired.' });
    } finally {
      this.retiring.delete(p.id);
    }
  }

  /** Merge the person into the one chosen, then read the sheet again: the merged person is gone
   *  and their claims, patrons and pool memberships are the other's. */
  protected async merge(p: Person, intoId: string): Promise<void> {
    if (this.merging.has(p.id)) return;
    this.personError.set(null);
    this.patronError.set(null);
    const id = Number(intoId);
    if (!intoId || id === p.id) {
      this.personError.set({ id: p.id, message: 'Choose another person to merge into.' });
      return;
    }
    this.merging.add(p.id);
    try {
      await this.api.merge(p.id, id);
      const sheetId = this.session.sheetId();
      if (this.editing() === p.id) this.reset();
      void this.poolsApi.refresh();
      if (sheetId) await this.reload(sheetId);
    } catch {
      this.personError.set({ id: p.id, message: 'The person could not be merged.' });
    } finally {
      this.merging.delete(p.id);
    }
  }

  /** Transfer the person to the sheet of the id typed, then read the sheet again: the person is
   *  gone from it. */
  protected async transfer(p: Person, toSheet: string): Promise<void> {
    if (this.transferring.has(p.id)) return;
    this.personError.set(null);
    this.patronError.set(null);
    const to = toSheet.trim();
    if (!to) {
      this.personError.set({ id: p.id, message: 'Give the id of the sheet to transfer to.' });
      return;
    }
    this.transferring.add(p.id);
    try {
      await this.api.transfer(p.id, to);
      const sheetId = this.session.sheetId();
      if (this.editing() === p.id) this.reset();
      void this.poolsApi.refresh();
      if (sheetId) await this.reload(sheetId);
    } catch {
      this.personError.set({ id: p.id, message: 'The person could not be transferred.' });
    } finally {
      this.transferring.delete(p.id);
    }
  }

  /** Give the person the alias typed; a blank one is refused on the row. */
  protected async addAlias(p: Person, input: HTMLInputElement): Promise<void> {
    if (this.aliasing.has(p.id)) return;
    this.personError.set(null);
    this.patronError.set(null);
    const alias = input.value.trim();
    if (!alias) {
      this.personError.set({ id: p.id, message: 'Enter an alias.' });
      return;
    }
    this.aliasing.add(p.id);
    try {
      const saved = await this.api.addAlias(p.id, alias);
      this.people.update((ps) => ps.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editing() === saved.id) this.version = saved.version;
      input.value = '';
    } catch {
      this.personError.set({ id: p.id, message: 'That alias is already a name or alias on this sheet.' });
    } finally {
      this.aliasing.delete(p.id);
    }
  }

  /** Clear the row's error once its alias entry is changed. */
  protected clearPersonError(id: number): void {
    if (this.personError()?.id === id) this.personError.set(null);
  }

  /** Put the person on hold from the day given (blank: the sheet's today) to the day given (blank:
   *  open-ended). */
  protected async hold(p: Person, from: string, to: string): Promise<void> {
    if (this.holding.has(p.id)) return;
    this.holding.add(p.id);
    this.personError.set(null);
    this.patronError.set(null);
    try {
      const saved = await this.api.hold(p.id, from.trim() || null, to.trim() || null);
      this.people.update((ps) => ps.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editing() === saved.id) this.version = saved.version;
    } catch {
      this.personError.set({ id: p.id, message: 'The person could not be put on hold.' });
    } finally {
      this.holding.delete(p.id);
    }
  }

  protected cancel(): void {
    this.reset();
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { name, grade, gradeFrom, termsFrom } = this.form.getRawValue();
    const terms = this.form.controls.terms.value.trim();
    const contact = this.form.controls.contact.value.trim().toLowerCase();
    const tags = this.form.controls.tags.value;
    if (!name.trim()) {
      this.refuse('Enter a name.');
      return;
    }
    if (!this.grades.includes(grade)) {
      this.refuse('Choose a grade of A, B or C.');
      return;
    }
    if (!/^\d{0,4}$/.test(terms)) {
      this.refuse('Enter terms as a whole number of days, or leave them blank.');
      return;
    }
    const id = this.editing();
    this.submitting = true;
    try {
      if (id === null) {
        const added = await this.api.add({ name, grade, terms, contact, tags });
        this.people.update((ps) => [...ps, added]);
      } else {
        const saved = await this.api.edit(id, this.version, { name, grade, gradeFrom: gradeFrom.trim() || null, terms,
          termsFrom: termsFrom.trim() || null, contact, tags });
        this.people.update((ps) => ps.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(id);
        this.error.set('Someone else saved this person in the meantime. The form now shows their current values.');
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.refuse(contact && this.people().some((p) => p.id !== id && p.contact === contact)
          ? 'That contact is already held by someone on this sheet.'
          : 'That name or contact is already in use on this sheet.');
      } else {
        this.refuse(id === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    } finally {
      this.submitting = false;
    }
  }

  /** Show why the form was refused; a refused edit also closes the editor, so the form next adds a
   *  person rather than saving over this one with what is typed into it. */
  private refuse(message: string): void {
    if (this.editing() !== null) this.reset();
    this.error.set(message);
  }

  /** Re-read the person whose save was overtaken and show them as they are now, in their row and in
   *  the still-open editor, which then saves over that version. */
  private async showCurrent(id: number): Promise<void> {
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const current = (await this.api.list(sheetId)).find((p) => p.id === id);
      if (!current || this.editing() !== id) return;
      this.people.update((ps) => ps.map((p) => (p.id === id ? current : p)));
      this.version = current.version;
      this.form.setValue({ name: current.name, grade: this.gradeOf(current), gradeFrom: this.form.getRawValue().gradeFrom,
        terms: current.undatedTerms?.toString() ?? '', termsFrom: this.form.getRawValue().termsFrom,
        contact: current.contact ?? '',
        tags: current.tags },
        { emitEvent: false });
    } catch {
      // the editor keeps what was typed; reopening it reads the person again
    }
  }

  protected select(id: number, on: boolean): void {
    this.selected.update((s) => {
      const next = new Set(s);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  protected selectPerson(id: number, on: boolean): void {
    this.bulkGradeError.set('');
    this.bulkRetireError.set('');
    this.selectedPeople.update((s) => {
      const next = new Set(s);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Give the selected people listed the grade from the day given (blank: the sheet's today), in
   *  list order, then show them as they now are and how many were changed. */
  protected async gradeSelected(grade: string, from: string): Promise<void> {
    if (this.grading) return;
    this.bulkGraded.set(null);
    this.bulkGradeError.set('');
    const ids = this.shown().filter((p) => this.selectedPeople().has(p.id)).map((p) => p.id);
    // Grading is the next action on each selected row: an error left on one of them goes.
    ids.forEach((id) => this.clearPersonError(id));
    if (ids.length === 0) {
      this.bulkGradeError.set('Select the people to grade.');
      return;
    }
    if (!this.grades.includes(grade as Grade)) {
      this.bulkGradeError.set('Choose a grade of A, B or C.');
      return;
    }
    this.grading = true;
    try {
      const result = await this.api.grade(ids, grade as Grade, from.trim() || null);
      const byId = new Map(result.people.map((p) => [p.id, p]));
      this.people.update((ps) => ps.map((p) => byId.get(p.id) ?? p));
      const editing = this.editing();
      const edited = editing === null ? undefined : byId.get(editing);
      if (edited) this.version = edited.version;
      this.selectedPeople.set(new Set());
      this.bulkGraded.set(result.graded);
    } catch {
      this.bulkGradeError.set('The selected people could not be graded.');
    } finally {
      this.grading = false;
    }
  }

  /** Retire the selected people listed from the day given (blank: the sheet's today), in list
   *  order, then show them as they now are and how many were retired. */
  protected async retireSelected(from: string): Promise<void> {
    if (this.retiringAll) return;
    this.bulkRetired.set(null);
    this.bulkRetireError.set('');
    const ids = this.shown().filter((p) => this.selectedPeople().has(p.id)).map((p) => p.id);
    // Retiring is the next action on each selected row: an error left on one of them goes.
    ids.forEach((id) => this.clearPersonError(id));
    if (ids.length === 0) {
      this.bulkRetireError.set('Select the people to retire.');
      return;
    }
    this.retiringAll = true;
    try {
      const result = await this.api.retireAll(ids, from.trim() || null);
      // a retired member gets no cut: read the pools again
      await this.poolsApi.refresh();
      const byId = new Map(result.people.map((p) => [p.id, p]));
      this.people.update((ps) => ps.map((p) => byId.get(p.id) ?? p));
      const editing = this.editing();
      const edited = editing === null ? undefined : byId.get(editing);
      if (edited) this.version = edited.version;
      this.selectedPeople.set(new Set());
      this.bulkRetired.set(result.retired);
    } catch {
      this.bulkRetireError.set('The selected people could not be retired.');
    } finally {
      this.retiringAll = false;
    }
  }

  /** Open the selected claims that can be opened, then show the claims as they are now and how many
   *  were opened and refused. */
  protected async bulkOpen(): Promise<void> {
    const sheetId = this.session.sheetId();
    if (this.opening || !sheetId) return;
    this.opening = true;
    this.bulkOpened.set(null);
    try {
      const result = await this.claimsApi.openAll([...this.selected()]);
      this.selected.set(new Set());
      this.claims.set(await this.claimsApi.list(sheetId));
      this.bulkOpened.set(result);
    } catch {
      const ids = this.selected();
      this.bulkOpened.set({ opened: 0, refused: ids.size });
    } finally {
      this.opening = false;
    }
  }

  /** Settle the selected claims that can be settled, then show the claims as they are now and how
   *  many were settled and refused. */
  protected async bulkSettle(): Promise<void> {
    const sheetId = this.session.sheetId();
    if (this.settling || !sheetId) return;
    this.settling = true;
    this.bulkSettled.set(null);
    try {
      const result = await this.claimsApi.settleAll([...this.selected()]);
      this.selected.set(new Set());
      const [claims, lastBatch] = await Promise.all([
        this.claimsApi.list(sheetId),
        this.claimsApi.lastSettleBatch(sheetId),
        this.descendantsApi.figures(sheetId),
      ]);
      this.claims.set(claims);
      this.lastSettleBatch.set(lastBatch);
      this.bulkSettled.set(result);
    } catch {
      this.bulkSettled.set({ settled: 0, refused: this.selected().size });
    } finally {
      this.settling = false;
    }
  }

  /** Move the selected claims that can be moved to the person, then show the claims as they are
   *  now and how many were moved and refused. */
  protected async bulkReassign(to: string): Promise<void> {
    const sheetId = this.session.sheetId();
    if (this.reassigning || !sheetId) return;
    this.reassigning = true;
    this.bulkReassigned.set(null);
    try {
      if (!to) throw new Error('No person chosen');
      const result = await this.claimsApi.reassignAll([...this.selected()], Number(to));
      this.selected.set(new Set());
      this.claims.set(await this.claimsApi.list(sheetId));
      this.bulkReassigned.set(result);
    } catch {
      this.bulkReassigned.set({ reassigned: 0, refused: this.selected().size });
    } finally {
      this.reassigning = false;
    }
  }

  /** Re-date the selected claims that can be re-dated, then show the claims as they are now and
   *  how many were re-dated and refused. */
  protected async bulkRedate(date: string): Promise<void> {
    const sheetId = this.session.sheetId();
    if (this.redating || !sheetId) return;
    this.redating = true;
    this.bulkRedated.set(null);
    try {
      if (!date) throw new Error('No date chosen');
      const result = await this.claimsApi.redateAll([...this.selected()], date);
      this.selected.set(new Set());
      this.claims.set(await this.claimsApi.list(sheetId));
      this.bulkRedated.set(result);
    } catch {
      this.bulkRedated.set({ redated: 0, refused: this.selected().size });
    } finally {
      this.redating = false;
    }
  }

  protected claimSaved(claim: Claim): void {
    this.clearPersonError(claim.personId);
    this.claims.update((cs) =>
      (cs.some((c) => c.id === claim.id)
        ? cs.map((c) => (c.id === claim.id ? claim : c))
        : [claim, ...cs]).sort((a, b) => b.listed - a.listed),
    );
  }

  protected quickAdded({ person, claim }: QuickAdded): void {
    this.people.update((ps) =>
      ps.some((p) => p.id === person.id) ? ps.map((p) => (p.id === person.id ? person : p)) : [...ps, person],
    );
    this.claimSaved(claim);
  }

  /** Show the people an import added or updated: updated ones in place, added ones at the end. */
  protected peopleImported(imported: Person[]): void {
    this.people.update((ps) => [
      ...ps.map((p) => imported.find((i) => i.id === p.id) ?? p),
      ...imported.filter((i) => !ps.some((p) => p.id === i.id)),
    ]);
  }

  /** Show a plan just added at the end of its person's plans. */
  protected planAdded(plan: Plan): void {
    this.plans.update((xs) => [...xs.filter((x) => x.id !== plan.id), plan]);
  }

  /** Read the sheet's claims and plans again once a generate has made claims. */
  protected readonly plansGenerated = async (): Promise<void> => {
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const [claims, plans] = await Promise.all([this.claimsApi.list(sheetId), this.plansApi.list(sheetId)]);
      if (this.session.sheetId() === sheetId) {
        this.claims.set(claims);
        this.plans.set(plans);
      }
    } catch {
      // the claims show again on the next load
    }
  };

  /** Read the sheet's claims again once a paste has added or opened some. */
  protected async claimsPasted(): Promise<void> {
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const claims = await this.claimsApi.list(sheetId);
    if (this.session.sheetId() === sheetId) this.claims.set(claims);
  }

  /** Read the sheet's rates again once an import has set some. */
  protected async ratesImported(): Promise<void> {
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const rates = await this.ratesApi.list(sheetId);
    if (this.session.sheetId() === sheetId) this.rates.set(rates);
  }

  protected rateSaved(rate: Rate): void {
    const key = (r: Rate) => r.grade + (r.from ?? '');
    this.rates.update((rs) =>
      [...rs.filter((r) => r.level !== rate.level || r.grade !== rate.grade || r.from !== rate.from), rate].sort((a, b) =>
        a.level - b.level || (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0),
      ),
    );
  }

  protected periodSaved(period: Period): void {
    this.periods.update((ps) => [...ps.filter((p) => p.id !== period.id), period].sort((a, b) => (a.from < b.from ? -1 : a.from > b.from ? 1 : 0)));
  }

  /** A person's due-total: the sum of their figures ({@link figuresOf}), at most the sheet's cap.
   *  Null (n/a) when the sum is; n/a is not capped. */
  /** A person's due-total for a period: the one fixed when it was closed or soft-closed (0.00 for a
   *  person added since), or else the live one. */
  protected periodDueOf(personId: number, period: Period): string {
    if (period.closed || period.softClosed) return period.closedPersonTotals[personId] ?? '0.00';
    return this.livePeriodDues().get(personId)?.get(period.id) ?? '0.00';
  }

  /** A person's carried figure for a period: the one fixed when it was closed or soft-closed (0.00
   *  for a person added since), or else the live one ({@link carried}); 0.00 when nothing is carried. */
  protected carriedOf(personId: number, period: Period): string {
    if (period.closed || period.softClosed) return period.closedPersonCarried[personId] ?? '0.00';
    return this.carried().get(personId)?.get(period.id) ?? '0.00';
  }

  /** Each person's due-total by id, as their row shows it; null when n/a. */
  protected readonly dueByPerson = computed(() => new Map(this.people().map((p) => [p.id, this.dueOf(p)])));

  protected dueOf(p: Person): string | null {
    const sum = this.figuresOf(p);
    const cap = this.cap();
    if (sum === null || cap === null) return sum;
    return Number(sum) > Number(cap) ? cap : sum;
  }

  /** A person's excess: how far the sum of their figures is above the sheet's cap; 0.00 when it is
   *  not, or when it is n/a. */
  protected excessOf(p: Person): string {
    const sum = this.figuresOf(p);
    const due = this.dueOf(p);
    return sum === null || due === null ? '0.00' : sumAmounts([sum, '-' + due]);
  }

  /** The sum of the due figures of a person's open and settled claims, leaving out
   *  the open ones dated within one of their holds and unbound by a statement. Null (n/a)
   *  when their grade on the sheet's today has no rate on it and no claim that counts has a figure
   *  (one kept by settling or a statement, or a live due at the grade in force on its date). */
  private figuresOf(p: Person): string | null {
    const counted = (this.claimsByPerson().get(p.id) ?? []).filter((c) => countsLive(c, p, this.session.today()));
    const figures = counted.map((c) => claimDue(c, this.rates(), gradeOn(p.grades, c.date), this.floor(), this.lateness())).filter((d): d is string => d !== null);
    if (!this.pctOf().has(this.gradeOf(p)) && figures.length === 0) {
      return null;
    }
    return sumAmounts(figures);
  }

  /** A person's settled-total: the due figures of their settled claims that count (their frozen
   *  figures, corrections included), skipping those with no due. */
  protected settledTotalOf(p: Person): string {
    return this.statusTotalOf(p, (c) => c.status === 'SETTLED');
  }

  /** A person's open-total: the due figures of their open and pending claims that count (held,
   *  deferred and retired-and-excluded ones are left out), skipping those with no due. With the
   *  settled-total it adds up to their due-total before the cap. */
  protected openTotalOf(p: Person): string {
    return this.statusTotalOf(p, isOpen);
  }

  private statusTotalOf(p: Person, keep: (c: Claim) => boolean): string {
    const today = this.session.today();
    return sumAmounts((this.claimsByPerson().get(p.id) ?? [])
      .filter((c) => keep(c) && countsLive(c, p, today))
      .map((c) => claimDue(c, this.rates(), gradeOn(p.grades, c.date), this.floor(), this.lateness()))
      .filter((d): d is string => d !== null));
  }

  /** The grade a person shows: the one in force on the sheet's today. */
  /** Downloads everyone, retired included, in list order as CSV: name, grade in force today,
   *  retired-from and due-total as shown. */
  protected exportCsv(): void {
    const lines = [['name', 'grade', 'retired-from', 'due-total']].concat(
      this.people().map((p) => [p.name, this.gradeOf(p), p.retiredFrom ?? '', this.dueOf(p) ?? 'n/a']),
    );
    downloadCsv(lines, 'people.csv');
  }

  /** Downloads every claim as CSV, people (retired included) in list order and each person's
   *  claims oldest first: person, number, date, amount, due, status and period as the claim's row
   *  shows them. */
  protected exportClaimsCsv(): void {
    const lines = [['person', 'number', 'date', 'amount', 'due', 'status', 'period']].concat(
      this.people().flatMap((p) => [...(this.claimsByPerson().get(p.id) ?? [])].reverse().map((c) => [
        p.name,
        c.number ?? '',
        c.date,
        c.amount,
        claimDue(c, this.rates(), gradeOn(p.grades, c.date), this.floor(), this.lateness()) ?? 'n/a',
        c.status,
        periodOf(this.periods(), c.dueBy)?.name ?? '',
      ])),
    );
    downloadCsv(lines, 'claims.csv');
  }

  protected gradeOf(p: Person): Grade {
    return gradeOn(p.grades, this.session.today()) ?? p.grade;
  }

  /** Whether a person is active: they have an open (or pending) claim that counts, dated within
   *  the 30 days up to and including the sheet's today. */
  protected activeOf(p: Person): boolean {
    return (this.claimsByPerson().get(p.id) ?? []).some((c) => keepsActive(c, p, this.session.today()));
  }

  /** A person's overdue claims among those that count in the live totals. */
  protected overdueOf(p: Person): Claim[] {
    return (this.claimsByPerson().get(p.id) ?? [])
      .filter((c) => countsLive(c, p, this.session.today())
        && overdue(c, this.session.today(), graceOn(this.graces(), gradeOn(p.grades, c.date), c.date)));
  }

  /** A person's overdue-total: the due figures of their overdue claims that count, skipping those
   *  with no due. */
  protected overdueTotalOf(p: Person): string {
    return sumAmounts(this.overdueOf(p).map((c) => claimDue(c, this.rates(), gradeOn(p.grades, c.date), this.floor(), this.lateness()))
      .filter((d): d is string => d !== null));
  }

  /** A person's due by the age of their open (or pending) claims that count, on the sheet's today
   *  (the days from the claim's date): aged 0 to 30 days, 31 to 60, and 61 or more; a claim dated
   *  after today is aged 0 to 30. Claims with no due are skipped. */
  protected agedOf(p: Person): [string, string, string] {
    const today = this.session.today();
    const buckets: [string[], string[], string[]] = [[], [], []];
    if (today !== null) {
      for (const c of this.claimsByPerson().get(p.id) ?? []) {
        if (!isOpen(c) || !countsLive(c, p, today)) continue;
        const d = claimDue(c, this.rates(), gradeOn(p.grades, c.date), this.floor(), this.lateness());
        if (d === null) continue;
        const bucket = c.date >= addDays(today, -30) ? 0 : c.date >= addDays(today, -60) ? 1 : 2;
        buckets[bucket].push(d);
      }
    }
    return [sumAmounts(buckets[0]), sumAmounts(buckets[1]), sumAmounts(buckets[2])];
  }

  private reset(): void {
    this.editing.set(null);
    this.form.reset({ name: '', grade: 'A', gradeFrom: '', terms: '', termsFrom: '', contact: '', tags: '' },
      { emitEvent: false });
    this.error.set('');
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const [people, claims, tags, rates, periods, settings, patrons, watches, lastBatch, descendants, plans] = await Promise.all([
        this.api.list(sheetId),
        this.claimsApi.list(sheetId),
        this.claimsApi.tags(sheetId),
        this.ratesApi.list(sheetId),
        this.periodsApi.list(sheetId),
        this.settingsApi.get(sheetId),
        this.patronsApi.list(sheetId),
        this.watchesApi.list(sheetId),
        this.claimsApi.lastSettleBatch(sheetId),
        this.descendantsApi.figures(sheetId),
        this.plansApi.list(sheetId),
      ]);
      if (read === this.reads) {
        this.rates.set(rates);
        this.floor.set(settings.floors);
        this.caps.set(settings.caps);
        this.latePct.set(settings.latePcts);
        this.graces.set(settings.graces);
        this.policyDate.set(settings.policyDate);
        this.roundings.set(settings.roundings);
        this.periods.set(periods);
        this.claims.set(claims);
        this.storedTags.set(tags);
        this.patrons.set(patrons);
        this.watches.set(watches);
        this.plans.set(plans);
        this.lastSettleBatch.set(lastBatch);
        this.descendants.set(descendants);
        this.people.set(people);
      }
    } catch {
      // the list stays empty; the next load reads it again
    }
    if (read === this.reads) this.session.markShown(sheetId);
  }
}

/** Tags as shown (comma and space separated), one by one. */
function splitTags(tags: string): string[] {
  return tags === '' ? [] : tags.split(', ');
}
