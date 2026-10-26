import { HttpErrorResponse } from '@angular/common/http';
import { FigurePipe, showFigure } from '../../core/figure.pipe';
import { UserLocale } from '../../core/user-locale';
import { DOCUMENT } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { ClaimView, ClaimsApi, Lateness, SplitView, cents, claimOwedCents, counts, isBound, isCorrection, isDeferred, isOpen, isOverdue, isReviewLate, keepsFigure, money, statusAsOf } from '../claims/claims-api';
import { PersonClaims } from '../claims/person-claims';
import { Periods } from '../periods/periods';
import { PeriodView, PeriodsApi, periodOf } from '../periods/periods-api';
import { PoolsList, personCutTotals, poolsWithCrews } from '../pools/pools';
import { Pools } from '../pools/pools-api';
import { CrewsList } from '../crews/crews';
import { Crews, crewIds } from '../crews/crews-api';
import { Rates } from '../rates/rates';
import { RateView, RatesApi, rateInForce, sameRate } from '../rates/rates-api';
import { SheetCap } from '../settings/sheet-cap';
import { SheetFloor } from '../settings/sheet-floor';
import { SheetGrace } from '../settings/sheet-grace';
import { SheetReviewSla } from '../settings/sheet-review-sla';
import { SheetLatePct } from '../settings/sheet-late-pct';
import { SheetParent } from '../settings/sheet-parent';
import { SheetPot } from '../settings/sheet-pot';
import { SheetPolicyDate } from '../settings/sheet-policy-date';
import { SheetRounding } from '../settings/sheet-rounding';
import { StatementView, StatementsApi } from '../statements/statements-api';
import { SheetHeader } from '../summary/sheet-header';
import { GradeSummary, PeriodSummary, StatusSummary, Summary, VoidReasonSummary } from '../summary/summary';
import { ClassicPeriodSummary, ClassicSummary } from '../summary/classic-summary';
import { VoidReasons } from '../void-reasons/void-reasons-api';
import { GRADES, Grade, PeopleApi, PersonView, QuickAddView, gradeOn, nameWithGrade } from './people-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;
/** The statuses the summary counts claims of; no claim is ever pending, so its count stays 0. */
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'REVIEW'] as const;
import { QuickAdd } from './quick-add';
import { PasteClaims } from '../claims/paste-claims';
import { ImportOpenings } from '../periods/import-openings';
import { Rollover } from '../periods/rollover';
import { ImportPeople } from './import-people';
import { Consolidated } from '../summary/consolidated';
import { TreeSearch } from '../summary/tree-search';
import { Duplicates } from '../duplicates/duplicates';
import { GeneratePlans } from '../plans/generate-plans';
import { PersonPlans } from '../plans/person-plans';
import { PeopleColumns, PeopleColumnsChoice } from '../columns/people-columns';

// The sheet's people in the order they were added, with one form to add a person or edit one,
// each with their claims and what they have claimed and is due, the sheet's claimed-total and
// due-total, the sheet's rates, and its periods with the due-total of the counting claims dated in each. Each person's statement button records their due-total and binds
// their counting claims to the figures they show then.
@Component({
  selector: 'app-people',
  imports: [FigurePipe, ReactiveFormsModule, PersonClaims, Rates, Periods, QuickAdd, PasteClaims, ImportOpenings, Rollover, ImportPeople, Summary, ClassicSummary, SheetHeader, Consolidated, TreeSearch, Duplicates, PoolsList, CrewsList, PersonPlans, GeneratePlans, PeopleColumnsChoice],
  template: `
    <app-sheet-header [peopleCount]="people().length" [statuses]="statusSummary()"
      [dueTotal]="sheetDueTotal()" [overdueTotal]="money(sheetOverdue().cents)"
      [myDueTotal]="me() === null ? '' : personDueTotal(me()!)" [myOverdueTotal]="me() === null ? '' : money(overdueOf(me()!.id).cents)" [myCrewDueTotal]="myCrewDueTotal()" />
    <app-consolidated [ownPeople]="treeFigures()" [ownGrades]="gradeSummary()" [ownStatuses]="statusSummary()" />
    <app-tree-search />
    <app-duplicates />
    @if (session.sheet()) {
      <app-rates [rates]="rates()" (saved)="rateSaved($event)" />
      <app-periods [periods]="periods()" [dueTotals]="periodDueTotals()" (added)="periodAdded($event)" (changed)="periodChanged($event)" />
      <app-pools [people]="people()" [periods]="periods()" />
      <app-crews [people]="people()" [dues]="dueCents()" />
      <section aria-labelledby="people-heading">
        <h2 id="people-heading">People</h2>
        <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editingId() === null ? 'Add a person' : 'Edit a person'">
          <label for="person-form-name">Name</label>
          <input id="person-form-name" data-testid="person-form-name" formControlName="name" />
          <label for="person-form-grade">Grade</label>
          <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
            @for (g of grades; track g) {
              <option [value]="g">{{ g }}</option>
            }
          </select>
          @if (editingId() !== null) {
            <label for="person-form-grade-from">Grade from</label>
            <input id="person-form-grade-from" type="date" data-testid="person-form-grade-from" formControlName="gradeFrom"
              aria-describedby="person-form-grade-from-hint" />
            <span id="person-form-grade-from-hint">Blank: the sheet's today</span>
          }
          <label for="person-form-terms">Terms (business days)</label>
          <input id="person-form-terms" inputmode="numeric" data-testid="person-form-terms" formControlName="terms"
            aria-describedby="person-form-terms-hint" />
          <span id="person-form-terms-hint">Blank: none</span>
          <label for="person-form-terms-from">Terms from</label>
          <input id="person-form-terms-from" type="date" data-testid="person-form-terms-from" formControlName="termsFrom"
            aria-describedby="person-form-terms-from-hint" />
          <span id="person-form-terms-from-hint">Blank: from the beginning of time</span>
          <label for="person-form-contact">Contact</label>
          <input id="person-form-contact" data-testid="person-form-contact" formControlName="contact"
            aria-describedby="person-form-contact-hint" />
          <span id="person-form-contact-hint">Blank: none</span>
          <label for="person-form-tags">Tags</label>
          <input id="person-form-tags" data-testid="person-form-tags" formControlName="tags"
            aria-describedby="person-form-tags-hint" />
          <span id="person-form-tags-hint">Comma separated; blank: none</span>
          <button type="submit" data-testid="person-form-submit" [disabled]="saving()">
            {{ editingId() === null ? 'Add person' : 'Save person' }}
          </button>
          @if (editingId() !== null) {
            <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
          }
          @if (error()) {
            <p role="alert" data-testid="person-form-error">{{ error() }}</p>
          }
        </form>
        <app-quick-add (added)="quickAdded($event)" />
        <app-paste-claims (added)="pasted($event)" />
        <app-import-openings (pasted)="periods.set($event)" />
        <app-rollover (rolled)="periods.set($event)" />
        <app-generate-plans (generated)="pasted($event)" />
        <app-import-people (added)="imported($event)" />
        <label>
          <input type="checkbox" data-testid="show-retired" [checked]="showRetired()"
            (change)="showRetired.set($any($event.target).checked)" />
          Show retired
        </label>
        <label>
          Sort by
          <select data-testid="sort-by" [value]="sortKey()" (change)="sortKey.set($any($event.target).value)">
            <option value="added">added</option>
            <option value="name">name</option>
            <option value="grade">grade</option>
            <option value="due-total">due-total</option>
          </select>
        </label>
        <button type="button" data-testid="export-people" (click)="exportCsv()">Export people as CSV</button>
        <button type="button" data-testid="export-claims" (click)="exportClaims()">Export claims as CSV</button>
        <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() | figure }}</strong></p>
        <p>Due in total: <strong data-testid="rollup-due-total">{{ sheetDueTotal() | figure }}</strong></p>
        <p>Settled in total: <strong data-testid="rollup-settled-total">{{ money(sheetSettledOpen().settled) | figure }}</strong>,
          open in total: <strong data-testid="rollup-open-total">{{ money(sheetSettledOpen().open) | figure }}</strong></p>
        <p>Overdue: <strong data-testid="rollup-overdue-count">{{ sheetOverdue().count }}</strong>
          claims, due <strong data-testid="rollup-overdue-total">{{ money(sheetOverdue().cents) | figure }}</strong></p>
        <p>Due aged 0–30 days: <strong data-testid="rollup-aged-0-30">{{ money(sheetAged().upTo30) | figure }}</strong>,
          31–60 days: <strong data-testid="rollup-aged-31-60">{{ money(sheetAged().upTo60) | figure }}</strong>,
          61+ days: <strong data-testid="rollup-aged-61">{{ money(sheetAged().over60) | figure }}</strong></p>
        <p>Cut in total: <strong data-testid="rollup-cut-total">{{ sheetCutTotal() | figure }}</strong></p>
        <div role="group" aria-label="Compare snapshots">
          <label>From <input data-testid="snapshot-from" type="date" [formControl]="snapshotFrom" /></label>
          <label>To <input data-testid="snapshot-to" type="date" [formControl]="snapshotTo" aria-describedby="snapshot-to-hint" /></label>
          <span id="snapshot-to-hint">Blank: due now</span>
          <button type="button" data-testid="snapshot-compare" (click)="compareSnapshots()">Compare snapshots</button>
        </div>
        <ul aria-label="Snapshot comparison">
          @for (line of snapshotLines(); track $index) {
            <li data-testid="snapshot-line">{{ line }}</li>
          }
        </ul>
        @if (tagTotals().length > 0) {
          <table aria-label="Due by tag">
            <thead>
              <tr><th scope="col">Tag</th><th scope="col">Due</th></tr>
            </thead>
            <tbody>
              @for (t of tagTotals(); track t.tag) {
                <tr>
                  <th scope="row">{{ t.tag }}</th>
                  <td [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ t.dueTotal | figure }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
        @if (monthTotals().length > 0) {
          <table aria-label="Due by month">
            <thead>
              <tr><th scope="col">Month</th><th scope="col">Due</th></tr>
            </thead>
            <tbody>
              @for (m of monthTotals(); track m.month) {
                <tr>
                  <th scope="row">{{ m.month }}</th>
                  <td [attr.data-testid]="'month-' + m.month + '-due-total'">{{ m.dueTotal | figure }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
        <p>
          <button type="button" data-testid="bulk-open" (click)="openSelected()" [disabled]="bulkOpening()">
            Open selected claims</button>
          @if (bulkResult(); as r) {
            Opened <strong data-testid="bulk-opened">{{ r.opened }}</strong>,
            refused <strong data-testid="bulk-refused">{{ r.refused }}</strong>
          }
        </p>
        @if (bulkError()) {
          <p role="alert" data-testid="bulk-open-error">{{ bulkError() }}</p>
        }
        <p>
          <button type="button" data-testid="bulk-settle" (click)="settleSelected()" [disabled]="bulkSettling()">
            Settle selected claims</button>
          Last batch <strong data-testid="bulk-settle-batch">{{ session.sheet()?.lastSettleBatch ?? '' }}</strong>
          @if (bulkSettleResult(); as r) {
            Settled <strong data-testid="bulk-settled">{{ r.settled }}</strong>,
            refused <strong data-testid="bulk-settle-refused">{{ r.refused }}</strong>
          }
        </p>
        @if (bulkSettleError()) {
          <p role="alert" data-testid="bulk-settle-error">{{ bulkSettleError() }}</p>
        }
        <p>
          <label for="bulk-reassign-to">Move the selected claims to</label>
          <select id="bulk-reassign-to" data-testid="bulk-reassign-to" #bulkReassignTo
            (change)="bulkReassignError.set('')">
            <option value="">Choose a person</option>
            @for (o of people(); track o.id) {
              <option [value]="o.id">{{ o.name }}</option>
            }
          </select>
          <button type="button" data-testid="bulk-reassign" (click)="reassignSelected(bulkReassignTo.value)"
            [disabled]="bulkReassigning()">Move selected claims</button>
          @if (bulkReassignResult(); as r) {
            Moved <strong data-testid="bulk-reassigned">{{ r.reassigned }}</strong>,
            refused <strong data-testid="bulk-reassign-refused">{{ r.refused }}</strong>
          }
        </p>
        @if (bulkReassignError()) {
          <p role="alert" data-testid="bulk-reassign-error">{{ bulkReassignError() }}</p>
        }
        <p>
          <label for="bulk-redate-date">Re-date the selected claims to</label>
          <input id="bulk-redate-date" type="date" data-testid="bulk-redate-date" #bulkRedateDate
            (input)="bulkRedateError.set('')" />
          <button type="button" data-testid="bulk-redate" (click)="redateSelected(bulkRedateDate.value)"
            [disabled]="bulkRedating()">Re-date selected claims</button>
          @if (bulkRedateResult(); as r) {
            Re-dated <strong data-testid="bulk-redated">{{ r.redated }}</strong>,
            refused <strong data-testid="bulk-redate-refused">{{ r.refused }}</strong>
          }
        </p>
        @if (bulkRedateError()) {
          <p role="alert" data-testid="bulk-redate-error">{{ bulkRedateError() }}</p>
        }
        <p>
          @if (voidReasons.reasons().length > 0) {
            <label for="bulk-void-reason">Void reason for the selected claims</label>
            <select id="bulk-void-reason" data-testid="bulk-void-reason" #bulkVoidReasonSelect
              (change)="bulkVoidReason.set(bulkVoidReasonSelect.value); bulkVoidError.set('')">
              <option value="">Choose a reason</option>
              @for (r of voidReasons.reasons(); track r.id) {
                <option [value]="r.reason">{{ r.reason }}</option>
              }
            </select>
          }
          <button type="button" data-testid="bulk-void" (click)="voidSelected()"
            [disabled]="bulkVoiding()">Void selected claims</button>
          @if (bulkVoidResult(); as r) {
            Voided <strong data-testid="bulk-voided">{{ r.voided }}</strong>,
            refused <strong data-testid="bulk-void-refused">{{ r.refused }}</strong>
          }
        </p>
        @if (bulkVoidError()) {
          <p role="alert" data-testid="bulk-void-error">{{ bulkVoidError() }}</p>
        }
        <p>
          <label for="bulk-grade">Grade for the selected people</label>
          <select id="bulk-grade" data-testid="bulk-grade" #bulkGrade (change)="bulkGradeError.set('')">
            @for (g of grades; track g) {
              <option [value]="g">{{ g }}</option>
            }
          </select>
          <label for="bulk-grade-from">from</label>
          <input id="bulk-grade-from" type="date" data-testid="bulk-grade-from" #bulkGradeFrom
            (input)="bulkGradeError.set('')" aria-describedby="bulk-grade-from-hint" />
          <span id="bulk-grade-from-hint">Blank: the sheet's today</span>
          <button type="button" data-testid="bulk-grade-apply" (click)="gradeSelected(bulkGrade, bulkGradeFrom)"
            [disabled]="bulkGrading()">Give the selected people the grade</button>
          @if (bulkGraded() !== null) {
            Changed <strong data-testid="bulk-graded">{{ bulkGraded() }}</strong>
          }
        </p>
        @if (bulkGradeError()) {
          <p role="alert" data-testid="person-error">{{ bulkGradeError() }}</p>
        }
        <p>
          <label for="bulk-retire-date">Retire the selected people from</label>
          <input id="bulk-retire-date" type="date" data-testid="bulk-retire-date" #bulkRetireDate
            (input)="bulkRetireError.set('')" aria-describedby="bulk-retire-date-hint" />
          <span id="bulk-retire-date-hint">Blank: the sheet's today</span>
          <button type="button" data-testid="bulk-retire" (click)="retireSelected(bulkRetireDate)"
            [disabled]="bulkRetiring()">Retire the selected people</button>
          @if (bulkRetired() !== null) {
            Retired <strong data-testid="bulk-retired">{{ bulkRetired() }}</strong>
          }
        </p>
        @if (bulkRetireError()) {
          <p role="alert" data-testid="person-error">{{ bulkRetireError() }}</p>
        }
        <app-summary [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()" [voidReasons]="voidReasonSummary()" [reviewLate]="reviewLateCount()" />
        <app-classic-summary [periods]="classicPeriodSummary()" [dueTotal]="classicDueTotal()" />
        <app-people-columns />
        <table data-testid="people-list">
          <thead>
            <tr><th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">Name</th>@if (cols.shown('aliases')) {<th scope="col">Aliases</th>}<th scope="col">Grade</th><th scope="col">Change grade</th>@if (cols.shown('terms')) {<th scope="col">Terms</th>}@if (cols.shown('contact')) {<th scope="col">Contact</th>}<th scope="col">Tags</th><th scope="col">Clashing</th><th scope="col">Came from</th>@if (cols.shown('claimed')) {<th scope="col">Claimed</th>}@if (cols.shown('due-total')) {<th scope="col">Due</th>}<th scope="col">Snapshots</th><th scope="col">Settled</th><th scope="col">Open</th>@for (q of periods(); track q.id) {<th scope="col">Due in {{ q.name }}</th>}@if (cols.shown('excess')) {<th scope="col">Excess</th>}<th scope="col">Cut</th><th scope="col">Overdue</th><th scope="col">Score</th><th scope="col">Due aged 0–30 days</th><th scope="col">Due aged 31–60 days</th><th scope="col">Due aged 61+ days</th><th scope="col">Active</th><th scope="col">Retired</th><th scope="col">On hold</th><th scope="col">Flag</th><th scope="col">Approver</th><th scope="col">Statements</th><th scope="col">Patrons</th><th scope="col">Watched</th><th scope="col">Plans</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
          </thead>
          <tbody>
            @for (p of shownPeople(); track p.id) {
              <tr [attr.data-testid]="'person-row-' + p.id">
                <td>
                  <input type="checkbox" data-testid="person-select" [checked]="selectedPeople().has(p.id)"
                    (change)="selectPerson(p.id, $any($event.target).checked)" [attr.aria-label]="'Select ' + p.name" />
                </td>
                <td>
                  <span data-testid="person-name">{{ shownName(p) }}</span>
                  <button type="button" data-testid="person-move-up" (click)="move(p, 'up')"
                    [disabled]="moving() === p.id" [attr.aria-label]="'Move ' + p.name + ' up'">Up</button>
                  <button type="button" data-testid="person-move-down" (click)="move(p, 'down')"
                    [disabled]="moving() === p.id" [attr.aria-label]="'Move ' + p.name + ' down'">Down</button>
                </td>
                @if (cols.shown('aliases')) {
                <td>
                  <span data-testid="person-aliases">{{ p.aliases.join(', ') }}</span>
                  <label [for]="'person-alias-input-' + p.id" class="visually-hidden">Alias for {{ p.name }}</label>
                  <input [id]="'person-alias-input-' + p.id" data-testid="person-alias-input" #aliasInput
                    (input)="clearPersonError(p.id)" />
                  <button type="button" data-testid="person-alias-add" (click)="addAlias(p, aliasInput)"
                    [disabled]="addingAlias() === p.id" [attr.aria-label]="'Add an alias for ' + p.name">Add alias</button>
                </td>
                }
                <td data-testid="person-grade">{{ gradeToday(p) }}</td>
                <td>
                  <label [for]="'person-inline-grade-' + p.id" class="visually-hidden">Grade for {{ p.name }}</label>
                  <select [id]="'person-inline-grade-' + p.id" data-testid="person-inline-grade" #inlineGrade
                    (change)="clearPersonError(p.id)">
                    @for (g of grades; track g) {
                      <option [value]="g" [selected]="g === gradeToday(p)">{{ g }}</option>
                    }
                  </select>
                  <button type="button" data-testid="person-inline-grade-save" (click)="saveGrade(p, inlineGrade)"
                    [disabled]="inlineGrading() === p.id" [attr.aria-label]="'Save the grade of ' + p.name">Save grade</button>
                </td>
                @if (cols.shown('terms')) {
                  <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
                }
                @if (cols.shown('contact')) {
                  <td data-testid="person-contact">{{ p.contact ?? '' }}</td>
                }
                <td data-testid="person-tags">{{ p.tags.join(', ') }}</td>
                <td data-testid="person-clashing">{{ p.clashing ? 'yes' : 'no' }}</td>
                <td data-testid="person-origin">{{ p.origin ?? '' }}</td>
                @if (cols.shown('claimed')) {
                  <td data-testid="person-claimed">{{ claimed(p.id) | figure }}</td>
                }
                @if (cols.shown('due-total')) {
                  <td data-testid="person-due-total">{{ personDueTotal(p) | figure }}</td>
                }
                <td>
                  <ul [attr.aria-label]="'Snapshots of ' + p.name">
                    @for (line of p.snapshots; track $index) {
                      <li data-testid="person-snapshot-line">{{ line }}</li>
                    }
                  </ul>
                </td>
                <td data-testid="person-settled-total">{{ money(settledOpenOf(p.id).settled) | figure }}</td>
                <td data-testid="person-open-total">{{ money(settledOpenOf(p.id).open) | figure }}</td>
                @for (q of periods(); track q.id) {
                  <td [attr.data-testid]="'person-period-' + q.name + '-due-total'">{{ (personPeriodDue(p.id, q.id)) | figure }}</td>
                  <td [attr.data-testid]="'person-period-' + q.name + '-carried'">{{ personPeriodCarried(p.id, q.id) | figure }}</td>
                }
                @if (cols.shown('excess')) {
                  <td data-testid="person-excess">{{ excess(p) | figure }}</td>
                }
                <td data-testid="person-cut-total">{{ personCutTotal(p) | figure }}</td>
                <td>
                  @if (cols.shown('overdue-count')) { <span data-testid="person-overdue-count">{{ overdueOf(p.id).count }}</span> }
                  @if (cols.shown('overdue-total')) { due <span data-testid="person-overdue-total">{{ money(overdueOf(p.id).cents) | figure }}</span> }
                </td>
                <td data-testid="person-score">{{ scoreOf(p.id) }}</td>
                <td data-testid="person-aged-0-30">{{ money(agedOf(p.id).upTo30) }}</td>
                <td data-testid="person-aged-31-60">{{ money(agedOf(p.id).upTo60) }}</td>
                <td data-testid="person-aged-61">{{ money(agedOf(p.id).over60) }}</td>
                <td data-testid="person-active">{{ activeIds().has(p.id) ? 'yes' : 'no' }}</td>
                <td>
                  @if (cols.shown('retired')) { <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span> }
                  from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span>
                  <label [for]="'person-retire-date-' + p.id" class="visually-hidden">Retire {{ p.name }} from</label>
                  <input type="date" [id]="'person-retire-date-' + p.id" data-testid="person-retire-date"
                    #retireDate (input)="clearPersonError(p.id)" />
                  <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)"
                    [disabled]="retiring() === p.id" [attr.aria-label]="'Retire ' + p.name">Retire</button>
                  <button type="button" data-testid="person-unretire" (click)="unretire(p)"
                    [disabled]="retiring() === p.id" [attr.aria-label]="'Unretire ' + p.name">Unretire</button>
                  @for (r of p.retirements; track $index) {
                    <div data-testid="person-retirement-line">{{ r.from }} to {{ r.to }}</div>
                  }
                </td>
                <td>
                  @if (cols.shown('hold')) { <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span> }
                  <ul [attr.aria-label]="'Holds on ' + p.name">
                    @for (h of p.holds; track $index) {
                      <li data-testid="person-hold-line">{{ h.from ?? 'the start' }} to {{ h.to ?? 'no end' }}</li>
                    }
                  </ul>
                  <label [for]="'person-hold-from-' + p.id" class="visually-hidden">Hold {{ p.name }} from</label>
                  <input type="date" [id]="'person-hold-from-' + p.id" data-testid="person-hold-from"
                    #holdFrom (input)="clearPersonError(p.id)" />
                  <label [for]="'person-hold-to-' + p.id" class="visually-hidden">Hold {{ p.name }} to</label>
                  <input type="date" [id]="'person-hold-to-' + p.id" data-testid="person-hold-to"
                    #holdTo (input)="clearPersonError(p.id)" />
                  <button type="button" data-testid="person-hold-toggle" (click)="hold(p, holdFrom, holdTo)"
                    [disabled]="holding() === p.id" [attr.aria-label]="'Hold ' + p.name">Hold</button>
                </td>
                <td>
                  <span data-testid="person-flag-shown">{{ p.flag ?? '' }}</span>
                  <label [for]="'person-flag-reason-' + p.id" class="visually-hidden">Flag {{ p.name }} because</label>
                  <input type="text" [id]="'person-flag-reason-' + p.id" data-testid="person-flag-reason"
                    #flagReason (input)="clearPersonError(p.id)" placeholder="Reason" />
                  <label [for]="'person-flag-until-' + p.id" class="visually-hidden">Flag {{ p.name }} until</label>
                  <input type="date" [id]="'person-flag-until-' + p.id" data-testid="person-flag-until"
                    #flagUntil (input)="clearPersonError(p.id)" />
                  <button type="button" data-testid="person-flag" (click)="flag(p, flagReason, flagUntil)"
                    [disabled]="flagging() === p.id" [attr.aria-label]="'Flag ' + p.name">Flag</button>
                </td>
                <td>
                  <span data-testid="person-approver">{{ p.approver ?? '' }}</span>
                  <label [for]="'person-approver-input-' + p.id" class="visually-hidden">Approver of {{ p.name }}</label>
                  <input type="text" [id]="'person-approver-input-' + p.id" data-testid="person-approver-input"
                    #approverInput (input)="clearPersonError(p.id)" placeholder="User" />
                  <button type="button" data-testid="person-approver-save" (click)="setApprover(p, approverInput)"
                    [disabled]="settingApprover() === p.id" [attr.aria-label]="'Set the approver of ' + p.name">Set approver</button>
                </td>
                <td>
                  @if (cols.shown('statement-count')) { <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
                  taken; } @if (cols.shown('stated-total')) { last <span data-testid="person-stated-total">{{ (statementOf(p.id)?.lastDueTotal ?? '') | figure }}</span> }
                  @if (cols.shown('statement-date')) { on <span data-testid="person-statement-date">{{ statementOf(p.id)?.lastDate ?? '' }}</span> }
                  <ul [attr.aria-label]="'Statements of ' + p.name">
                    @for (line of statementOf(p.id)?.lines ?? []; track $index) {
                      <li data-testid="person-statement-line-line">{{ line }}</li>
                    }
                  </ul>
                  <label [for]="'person-statement-asof-' + p.id" class="visually-hidden">Take {{ p.name }}'s statement as of</label>
                  <input type="date" [id]="'person-statement-asof-' + p.id" data-testid="person-statement-asof"
                    #statementAsOf (input)="clearPersonError(p.id)" />
                  <button type="button" data-testid="person-statement" (click)="takeStatement(p, statementAsOf)"
                    [disabled]="stating() === p.id" [attr.aria-label]="'Take a statement of ' + p.name">Statement</button>
                  <button type="button" data-testid="person-unstate" (click)="cancelStatement(p)"
                    [disabled]="stating() === p.id" [attr.aria-label]="'Cancel the last statement of ' + p.name">Cancel statement</button>
                  <button type="button" data-testid="person-export-statement" (click)="exportStatement(p)"
                    [attr.aria-label]="'Download the statement of ' + p.name + ' as CSV'">Export statement</button>
                  @if (personError()?.id === p.id) {
                    <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
                  }
                </td>
                <td>
                  <span data-testid="person-watched">{{ p.watched ? 'yes' : 'no' }}</span>
                  <button type="button" data-testid="person-watch" (click)="watch(p)" [disabled]="watching() === p.id"
                    [attr.aria-pressed]="p.watched" [attr.aria-label]="'Watch ' + p.name">{{ p.watched ? 'Unwatch' : 'Watch' }}</button>
                </td>
                <td>
                  @if (cols.shown('patrons')) { <span data-testid="person-patrons">{{ patronNames(p) }}</span> }
                  @if (cols.shown('backing')) { backing <span data-testid="person-backing">{{ backing(p) | figure }}</span> }
                  <label [for]="'person-patron-select-' + p.id" class="visually-hidden">Patron for {{ p.name }}</label>
                  <select [id]="'person-patron-select-' + p.id" data-testid="person-patron-select" #patronSelect
                    (change)="clearPatronError(p.id)">
                    <option value="">Choose a patron</option>
                    @for (o of people(); track o.id) {
                      @if (o.id !== p.id) {
                        <option [value]="o.id">{{ o.name }}</option>
                      }
                    }
                  </select>
                  <button type="button" data-testid="person-patron-add" (click)="addPatron(p, patronSelect)"
                    [disabled]="addingPatron() === p.id" [attr.aria-label]="'Add a patron for ' + p.name">Add patron</button>
                  @if (patronError()?.id === p.id) {
                    <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                  }
                </td>
                <td>
                  <app-person-plans [personId]="p.id" [personName]="p.name" />
                </td>
                <td>
                  <app-person-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)"
                    [people]="people()" [rates]="rates()" [grades]="p.grades" [periods]="periods()"
                    [selected]="selectedClaims()" (selectedChange)="selectClaim($event.id, $event.on)"
                    (added)="claimAdded($event)" (saved)="claimSaved($event)" (changed)="claimSaved($event)"
                    (reassigned)="claimReassigned($event)" (split)="claimSplit($event)" />
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
                    [disabled]="merging() === p.id" [attr.aria-label]="'Merge ' + p.name">Merge</button>
                  <label [for]="'person-transfer-to-' + p.id" class="visually-hidden">Transfer {{ p.name }} to sheet</label>
                  <input [id]="'person-transfer-to-' + p.id" data-testid="person-transfer-to" #transferTo
                    placeholder="Sheet id" (input)="clearPersonError(p.id)" />
                  <button type="button" data-testid="person-transfer" (click)="transfer(p, transferTo)"
                    [disabled]="transferring() === p.id" [attr.aria-label]="'Transfer ' + p.name">Transfer</button>
                  <button type="button" data-testid="person-transfer-undo" (click)="undoTransfer(p)"
                    [disabled]="transferring() === p.id" [attr.aria-label]="'Undo the transfer of ' + p.name">Undo transfer</button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    }
  `,
})
export class People {
  protected readonly session = inject(Session);
  protected readonly cols = inject(PeopleColumns);
  protected readonly voidReasons = inject(VoidReasons);
  private readonly api = inject(PeopleApi);
  private readonly userLocale = inject(UserLocale);
  private readonly claimsApi = inject(ClaimsApi);
  private readonly ratesApi = inject(RatesApi);
  private readonly statementsApi = inject(StatementsApi);
  private readonly pools = inject(Pools);
  private readonly crews = inject(Crews);
  private readonly sheetPot = inject(SheetPot);
  private readonly periodsApi = inject(PeriodsApi);
  private readonly floor = inject(SheetFloor);
  private readonly cap = inject(SheetCap);
  private readonly latePct = inject(SheetLatePct);
  private readonly sheetGrace = inject(SheetGrace);
  private readonly reviewSla = inject(SheetReviewSla);
  private readonly sheetParent = inject(SheetParent);
  private readonly policyDate = inject(SheetPolicyDate);
  private readonly rounding = inject(SheetRounding);
  private readonly document = inject(DOCUMENT);
  /** What the late charges depend on now. */
  private readonly lateness = computed<Lateness>(() =>
    ({ today: this.session.sheet()?.today, latePct: (date: string) => this.latePct.pctOn(date),
      grace: (claim: ClaimView) => this.graceOf(claim),
      policyDate: this.policyDate.date() ?? this.session.sheet()?.today,
      rounding: (date: string) => this.rounding.modeOn(date) }));

  protected readonly grades = GRADES;
  protected readonly name = new FormControl('', { nonNullable: true });
  protected readonly grade = new FormControl<Grade>('A', { nonNullable: true });
  /** The date an edit gives the grade from; blank means the sheet's today. */
  protected readonly gradeFrom = new FormControl('', { nonNullable: true });
  /** The person's own terms, in whole days; blank means none. */
  protected readonly terms = new FormControl('', { nonNullable: true });
  /** The date the terms are recorded from; blank means from the beginning of time. */
  protected readonly termsFrom = new FormControl('', { nonNullable: true });
  /** The person's contact; blank means none. */
  protected readonly contact = new FormControl('', { nonNullable: true });
  /** The person's tags, comma separated; blank means none. */
  protected readonly tags = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ name: this.name, grade: this.grade, gradeFrom: this.gradeFrom,
    terms: this.terms, termsFrom: this.termsFrom, contact: this.contact, tags: this.tags });

  /** Everyone on the sheet, retired or not: their claims count in every total. */
  protected readonly people = signal<PersonView[]>([]);
  /** The from-date and to-date of a snapshot comparison, and its lines. */
  protected readonly snapshotFrom = new FormControl('', { nonNullable: true });
  protected readonly snapshotTo = new FormControl('', { nonNullable: true });
  /** The dates last compared, or null before the first comparison on this sheet. */
  private readonly snapshotsCompared = signal<{ from: string; to: string } | null>(null);
  /** Each person in list order with their due-total in the snapshot written when the sheet's today
   *  left the from-date, and in the one for the to-date (their due-total now when it is blank); a figure
   *  with no snapshot is empty, and an empty figure leaves no trailing space. */
  protected readonly snapshotLines = computed(() => {
    const compared = this.snapshotsCompared();
    if (compared === null) return [];
    // The last snapshot written for the day.
    const on = (person: PersonView, day: string) => {
      const line = person.snapshots.filter((l) => l.startsWith(day + ' ')).at(-1);
      return line === undefined ? '' : line.slice(day.length + 1);
    };
    const shown = (figure: string) => showFigure(figure, this.userLocale.locale());
    return this.people().map((p) => {
      const then = compared.from === '' ? '' : on(p, compared.from);
      const now = compared.to === '' ? this.personDueTotal(p) : on(p, compared.to);
      return `${p.name} ${shown(then)} ${shown(now)}`.trim();
    });
  });
  /** Whether retired people are listed; off when the screen opens, and kept as it is by loading a sheet. */
  protected readonly showRetired = signal(false);
  /** The people this screen added since the sheet was loaded; a restricted user sees them listed
   *  until the sheet is loaded again. */
  private readonly addedHere = signal<ReadonlySet<number>>(new Set());
  /** The people a restricted user may see listed: the people of the crews their person leads or
   *  belongs to, that person, and those this screen added since the sheet was loaded; null
   *  (everyone) for any other role. */
  private readonly visibleIds = computed<Set<number> | null>(() => {
    if (this.session.sheet()?.role !== 'restricted') return null;
    const me = this.me();
    const ids = new Set(this.addedHere());
    if (me === null) return ids;
    ids.add(me.id);
    for (const c of this.crews.crews()) {
      const crew = crewIds(c);
      if (crew.includes(me.id)) for (const id of crew) ids.add(id);
    }
    return ids;
  });
  /** The people listed: the retired ones only while show retired is on, and for a restricted user only
   *  those they may see. Every total is still over everyone. */
  protected readonly listedPeople = computed(() => {
    const visible = this.visibleIds();
    return this.people().filter((p) => (this.showRetired() || !p.retired) && (visible === null || visible.has(p.id)));
  });
  /** What this screen sorts the listed people by; added (list order) when the screen opens, and kept as
   *  it is by loading a sheet. Sorting changes only what is shown, never the list order. */
  protected readonly sortKey = signal<SortKey>('added');
  /** The people listed, sorted by the sort key; equal keys keep list order. */
  protected readonly shownPeople = computed(() => {
    const listed = this.listedPeople();
    const by = this.sortKey();
    if (by === 'added') return listed;
    const keyed = listed.map((p, i) => ({ p, i, k: this.sortValue(p, by) }));
    keyed.sort((a, b) => compareSortValues(a.k, b.k) || a.i - b.i);
    return keyed.map((e) => e.p);
  });
  /** The person being moved up or down the list. */
  protected readonly moving = signal<number | null>(null);
  /** The person being retired. */
  protected readonly retiring = signal<number | null>(null);
  protected readonly inlineGrading = signal<number | null>(null);
  /** The person an alias is being added to. */
  protected readonly addingAlias = signal<number | null>(null);
  /** The person a patron is being added to. */
  protected readonly addingPatron = signal<number | null>(null);
  /** The refusal of the last patron added on a person's row, shown on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** The person a hold is being put on. */
  protected readonly holding = signal<number | null>(null);
  /** The person whose approver is being set. */
  protected readonly settingApprover = signal<number | null>(null);
  /** The person a flag is being put on. */
  protected readonly flagging = signal<number | null>(null);
  /** The retirement date of each retired person, by id. */
  private readonly retiredFromById = computed(() => new Map(this.people().map((p) => [p.id, p.retiredFrom])));
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<ClaimView[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, ClaimView[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  private readonly centsByPerson = computed(() => {
    const by = new Map<number, number>();
    for (const c of this.claims()) if (this.countsClaimed(c)) by.set(c.personId, (by.get(c.personId) ?? 0) + cents(c.amount));
    return by;
  });
  protected readonly claimedTotal = computed(() => money(this.claims().reduce((t, c) => (this.countsClaimed(c) ? t + cents(c.amount) : t), 0)));
  /** The sheet's rates, then its parents' (by level); a grade with no rate is left out. */
  protected readonly rates = signal<RateView[]>([]);
  private readonly ratesByGrade = computed(() => {
    const by = new Map<Grade, RateView[]>();
    for (const r of this.rates()) by.set(r.grade, [...(by.get(r.grade) ?? []), r]);
    return by;
  });
  /** The person on the sheet with the signed-in user's name, by name or alias as names are compared
   *  (without surrounding spaces or regard to case); null when there is none. */
  protected readonly me = computed(() => {
    const user = this.session.user();
    if (user === null) return null;
    const key = (name: string) => name.trim().toLowerCase();
    const mine = key(user);
    return this.people().find((p) => key(p.name) === mine || p.aliases.some((a) => key(a) === mine)) ?? null;
  });
  /** The due-totals of the crews the signed-in user's person leads, added up; blank when they lead none. */
  protected readonly myCrewDueTotal = computed(() => {
    const me = this.me();
    if (me === null) return '';
    const led = this.crews.crews().filter((c) => c.leadId === me.id);
    if (led.length === 0) return '';
    const dues = this.dueCents();
    return money(led.reduce((t, c) => t + crewIds(c).reduce((s, id) => s + (dues.get(id) ?? 0), 0), 0));
  });
  /** Everyone's due-total added up, skipping the people whose due-total is n/a, trimmed. */
  protected readonly sheetDueTotal = computed(() => {
    const figures: number[] = [];
    for (const p of this.people()) {
      const due = this.dueCentsOf(p);
      if (due !== null) figures.push(due);
    }
    return money(trimmedSum(figures));
  });
  /** Each person's overdue claims that count, by person: how many, and the sum of their figures in
   *  cents, skipping those that are n/a. */
  private readonly overdueByPerson = computed(() => {
    const by = new Map<number, { count: number; cents: number }>();
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const today = this.session.sheet()?.today;
    for (const c of this.claims()) {
      const person = byId.get(c.personId);
      if (person === undefined || !this.countsLive(c) || !isOverdue(c, today, this.graceOf(c))) continue;
      const due = claimOwedCents(c, this.ratesOn(person, c.date), this.floor.centsOn(c.date), this.lateness(), this.claims());
      const was = by.get(c.personId) ?? { count: 0, cents: 0 };
      by.set(c.personId, { count: was.count + 1, cents: was.cents + (due ?? 0) });
    }
    return by;
  });
  /** Each person's grades, by id. */
  private readonly gradesById = computed(() => new Map(this.people().map((p) => [p.id, p.grades])));
  /** Each person's open (PENDING included) claims that count, by person: the sum of their figures in
   *  cents, skipping those that are n/a, split by the claim's age in days on the sheet's today — 0 to 30
   *  (a claim dated after today included), 31 to 60, and 61 or more. */
  private readonly agedByPerson = computed(() => {
    const by = new Map<number, Aged>();
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const today = this.session.sheet()?.today;
    if (today === undefined) return by;
    const from30 = daysBefore(today, 30);
    const from60 = daysBefore(today, 60);
    for (const c of this.claims()) {
      const person = byId.get(c.personId);
      if (person === undefined || !isOpen(c) || !this.countsLive(c)) continue;
      const due = claimOwedCents(c, this.ratesOn(person, c.date), this.floor.centsOn(c.date), this.lateness(), this.claims()) ?? 0;
      const was = by.get(c.personId) ?? { upTo30: 0, upTo60: 0, over60: 0 };
      if (c.date >= from30) by.set(c.personId, { ...was, upTo30: was.upTo30 + due });
      else if (c.date >= from60) by.set(c.personId, { ...was, upTo60: was.upTo60 + due });
      else by.set(c.personId, { ...was, over60: was.over60 + due });
    }
    return by;
  });
  /** Each person's claims that count, by person: the sum of the figures of their SETTLED claims (at
   *  their frozen figures) and of their open (PENDING included) ones, in cents, skipping those that are
   *  n/a; together their due-total before the cap. */
  private readonly settledOpenByPerson = computed(() => {
    const by = new Map<number, SettledOpen>();
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const c of this.claims()) {
      const person = byId.get(c.personId);
      if (person === undefined || !this.countsLive(c)) continue;
      const due = claimOwedCents(c, this.ratesOn(person, c.date), this.floor.centsOn(c.date), this.lateness(), this.claims()) ?? 0;
      const was = by.get(c.personId) ?? { settled: 0, open: 0 };
      if (c.status === 'SETTLED') by.set(c.personId, { ...was, settled: was.settled + due });
      else if (isOpen(c)) by.set(c.personId, { ...was, open: was.open + due });
    }
    return by;
  });
  /** Every tag ever used on the sheet, as the store last told it. */
  private readonly sheetTags = signal<string[]>([]);
  /** For every tag ever used on the sheet (those the store keeps and those claims and people carry now),
   *  sorted: the figures of the claims that count which carry it, or whose person carries it, added up
   *  once each, skipping those that are n/a; a claim with several tags counts in each. */
  protected readonly tagTotals = computed(() => {
    const totals = new Map<string, number>(this.sheetTags().map((t) => [t, 0]));
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const p of this.people()) for (const t of p.tags) if (!totals.has(t)) totals.set(t, 0);
    for (const c of this.claims()) {
      for (const t of c.tags) if (!totals.has(t)) totals.set(t, 0);
      const person = byId.get(c.personId);
      if (person === undefined) continue;
      const tags = new Set([...c.tags, ...person.tags]);
      if (tags.size === 0 || !this.countsLive(c)) continue;
      const due = claimOwedCents(c, this.ratesOn(person, c.date), this.floor.centsOn(c.date), this.lateness(), this.claims()) ?? 0;
      for (const t of tags) totals.set(t, totals.get(t)! + due);
    }
    return [...totals].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
      .map(([tag, total]) => ({ tag, dueTotal: money(total) }));
  });
  /** For every calendar month (yyyy-mm) any claim on the sheet is dated in, sorted: the figures of the
   *  claims dated in it that count added up, skipping those that are n/a. */
  protected readonly monthTotals = computed(() => {
    const totals = new Map<string, number>();
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const c of this.claims()) {
      const month = c.date.slice(0, 7);
      if (!totals.has(month)) totals.set(month, 0);
      const person = byId.get(c.personId);
      if (person === undefined || !this.countsLive(c)) continue;
      const due = claimOwedCents(c, this.ratesOn(person, c.date), this.floor.centsOn(c.date), this.lateness(), this.claims()) ?? 0;
      totals.set(month, totals.get(month)! + due);
    }
    return [...totals].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
      .map(([month, total]) => ({ month, dueTotal: money(total) }));
  });
  /** Everyone's settled-total and open-total added up, not trimmed. */
  protected readonly sheetSettledOpen = computed(() => {
    let settled = 0;
    let open = 0;
    for (const p of this.people()) {
      const o = this.settledOpenByPerson().get(p.id);
      settled += o?.settled ?? 0;
      open += o?.open ?? 0;
    }
    return { settled, open };
  });
  /** The sheet's open (PENDING included) claims that count, by age: everyone's age buckets added up,
   *  not trimmed. */
  protected readonly sheetAged = computed(() => {
    const sum: Aged = { upTo30: 0, upTo60: 0, over60: 0 };
    for (const p of this.people()) {
      const a = this.agedByPerson().get(p.id);
      sum.upTo30 += a?.upTo30 ?? 0;
      sum.upTo60 += a?.upTo60 ?? 0;
      sum.over60 += a?.over60 ?? 0;
    }
    return sum;
  });
  /** The sheet's overdue claims that count: how many, and everyone's overdue-total added up, trimmed. */
  protected readonly sheetOverdue = computed(() => {
    let count = 0;
    for (const o of this.overdueByPerson().values()) count += o.count;
    const figures = this.people().map((p) => this.overdueByPerson().get(p.id)?.cents ?? 0);
    return { count, cents: trimmedSum(figures) };
  });
  /** Everyone's name keys (name and aliases, as names are compared) and figures, for the consolidated figures. */
  protected readonly treeFigures = computed(() => this.people().map((p) => {
    const due = this.dueCentsOf(p);
    return {
      keys: [p.name, ...p.aliases].map((n) => n.trim().toLowerCase()),
      dueTotal: due === null ? null : money(due),
      overdueTotal: money(this.overdueByPerson().get(p.id)?.cents ?? 0),
    };
  }));
  /** Each person's due-total in cents, by person id; null when it is n/a. */
  protected readonly dueCents = computed(() => new Map(this.people().map((p) => [p.id, this.dueCentsOf(p)])));
  /** Each person's cut total in cents, by person id: their cuts across every pool they are a member of. */
  private readonly cutTotals = computed(() => personCutTotals(poolsWithCrews(this.pools.pools(), this.crews.crews()), this.people(), this.sheetPot.pot(),
    this.session.sheet()?.today));
  /** The person's cut total, with two decimals. */
  protected personCutTotal(p: PersonView): string {
    return money(this.cutTotals().get(p.id) ?? 0);
  }
  /** The sheet's cut total: everyone's added up, with two decimals. */
  protected readonly sheetCutTotal = computed(() => money(this.people().reduce((t, p) => t + (this.cutTotals().get(p.id) ?? 0), 0)));
  protected readonly money = money;
  /** The sheet's periods in date order. */
  protected readonly periods = signal<PeriodView[]>([]);
  /** Each person's due-total for each period, in cents, by person id then period id: the figures as
   *  shown now of their claims due by a date in it that counted at the end of its last day (countsAt), skipping those that are n/a; for a closed or soft-closed period, the figure
   *  fixed when it was closed (0 for a person added since). */
  private readonly personPeriodCents = computed(() => {
    const by = new Map<number, Map<number, number>>(
      this.people().map((p) => [p.id, new Map(this.periods().map((q) => [q.id, 0]))]));
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const c of this.claims()) {
      const period = periodOf(this.periods(), c.dueBy);
      const person = byId.get(c.personId);
      if (period === null || period.closed || period.softClosed || person === undefined || !this.countsAt(c, period.to)) continue;
      const due = claimOwedCents(c, this.ratesOn(person, c.date), this.floor.centsOn(c.date), this.lateness(), this.claims());
      const of = by.get(person.id)!;
      if (due !== null) of.set(period.id, (of.get(period.id) ?? 0) + due);
    }
    for (const period of this.periods().filter((q) => q.closed || q.softClosed)) {
      for (const [id, of] of by) of.set(period.id, cents(period.closedPersonTotals[id] ?? '0.00'));
    }
    return by;
  });
  /** Per person, per period id, the carried figure: the dues of their claims that count, belonging to a
   *  period ending before this one starts, that were still open (PENDING included) at its end by their
   *  status log, with their opening balance for it; for a closed or soft-closed period, the figure fixed when it was
   *  closed (0 for a person added since). */
  private readonly personCarriedCents = computed(() => {
    const by = new Map<number, Map<number, number>>(
      this.people().map((p) => [p.id, new Map(this.periods().map((q) => [q.id, 0]))]));
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const c of this.claims()) {
      const own = periodOf(this.periods(), c.dueBy);
      const person = byId.get(c.personId);
      if (own === null || person === undefined) continue;
      const later = this.periods().filter((q) => own.to < q.from);
      if (later.length === 0) continue;
      const due = claimOwedCents(c, this.ratesOn(person, c.date), this.floor.centsOn(c.date), this.lateness(), this.claims());
      if (due === null) continue;
      const of = by.get(person.id)!;
      for (const q of later) {
        const status = statusAsOf(c, q.to);
        if ((status === 'OPEN' || status === 'PENDING') && this.countsAt(c, q.to)) of.set(q.id, (of.get(q.id) ?? 0) + due);
      }
    }
    for (const period of this.periods()) {
      for (const [id, of] of by) of.set(period.id, (of.get(period.id) ?? 0) + cents(period.personOpenings[id] ?? '0.00'));
    }
    for (const period of this.periods().filter((q) => q.closed || q.softClosed)) {
      for (const [id, of] of by) of.set(period.id, cents(period.closedPersonCarried[id] ?? '0.00'));
    }
    return by;
  });
  /** Each period's due-total, by period id: the sum of the people's figures for it. */
  protected readonly periodDueTotals = computed(() => {
    const totals = new Map<number, number>(this.periods().map((p) => [p.id, 0]));
    for (const of of this.personPeriodCents().values()) {
      for (const [id, due] of of) totals.set(id, (totals.get(id) ?? 0) + due);
    }
    return new Map([...totals].map(([id, total]) => [id, money(total)]));
  });
  /** The ids of the people who are active: those with an open (PENDING included) claim that counts,
   *  dated within the 30 days up to and including the sheet's today. */
  protected readonly activeIds = computed(() => {
    const today = this.session.sheet()?.today;
    const ids = new Set<number>();
    if (today === undefined) return ids;
    const from = daysBefore(today, 29);
    for (const c of this.claims()) {
      if (isOpen(c) && this.countsLive(c) && c.date >= from && c.date <= today) ids.add(c.personId);
    }
    return ids;
  });
  /** Per grade, the people on it today who are not retired, their due-totals added up, skipping
   *  those that are n/a, and how many of them are active. */
  protected readonly gradeSummary = computed<GradeSummary[]>(() => GRADES.map((grade) => {
    const on = this.people().filter((p) => !p.retired && this.gradeToday(p) === grade);
    return { grade, count: on.length, dueTotal: money(on.reduce((t, p) => t + (this.dueCentsOf(p) ?? 0), 0)),
      active: on.filter((p) => this.activeIds().has(p.id)).length };
  }));
  /** Each period's due-total, as its row shows it, and its change from the period before it by start
   *  date (empty for the earliest). */
  protected readonly periodSummary = computed<PeriodSummary[]>(() => {
    const dueOf = (id: number) => cents(this.periodDueTotals().get(id) ?? '0.00');
    const byStart = [...this.periods()].sort((a, b) => a.from.localeCompare(b.from));
    const change = new Map(byStart.map((p, i) => [p.id, i === 0 ? '' : money(dueOf(p.id) - dueOf(byStart[i - 1].id))]));
    return this.periods().map((p) => ({ name: p.name, dueTotal: money(dueOf(p.id)), change: change.get(p.id) ?? '' }));
  });
  /** Each period's classic due-total: the figures as shown now of the claims due by a date in it that
   *  count by their status now (countsLive), skipping those that are n/a; for a closed or soft-closed
   *  period, the figure fixed when it was closed. */
  protected readonly classicPeriodSummary = computed<ClassicPeriodSummary[]>(() => {
    const totals = new Map<number, number>(this.periods().map((q) => [q.id, 0]));
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const c of this.claims()) {
      const period = periodOf(this.periods(), c.dueBy);
      const person = byId.get(c.personId);
      if (period === null || period.closed || period.softClosed || person === undefined || !this.countsLive(c)) continue;
      const due = claimOwedCents(c, this.ratesOn(person, c.date), this.floor.centsOn(c.date), this.lateness(), this.claims());
      if (due !== null) totals.set(period.id, totals.get(period.id)! + due);
    }
    for (const period of this.periods().filter((q) => q.closed || q.softClosed)) {
      totals.set(period.id, this.people().reduce((t, p) => t + cents(period.closedPersonTotals[p.id] ?? '0.00'), 0));
    }
    return this.periods().map((q) => ({ name: q.name, dueTotal: money(totals.get(q.id) ?? 0) }));
  });
  /** Everyone's due-total added up, skipping the people whose due-total is n/a, not trimmed. */
  protected readonly classicDueTotal = computed(() =>
    money(this.people().reduce((t, p) => t + (this.dueCentsOf(p) ?? 0), 0)));
  /** How many of the sheet's claims have each status. */
  protected readonly statusSummary = computed<StatusSummary[]>(() =>
    SUMMARY_STATUSES.map((status) => ({ status, count: this.claims().filter((c) => c.status === status).length })));
  /** How many of the sheet's claims are review-late. */
  protected readonly reviewLateCount = computed(() =>
    this.claims().filter((c) => isReviewLate(c, this.session.sheet()?.today, this.reviewSla.days())).length);
  protected readonly voidReasonSummary = computed<VoidReasonSummary[]>(() =>
    this.voidReasons.reasons().map(({ reason }) => ({
      reason, count: this.claims().filter((c) => c.status === 'VOID' && c.voidReason === reason).length })));
  /** The statements of each person who has had one taken, by person. */
  protected readonly statements = signal<ReadonlyMap<number, StatementView>>(new Map());
  /** The person whose statement is being taken. */
  protected readonly stating = signal<number | null>(null);
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The ids of the claims selected to open or settle together. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** Whether the selected claims are being opened. */
  protected readonly bulkOpening = signal(false);
  /** How many the last bulk open opened and refused; cleared while the next one runs. */
  protected readonly bulkResult = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkError = signal('');
  /** Whether the selected claims are being settled. */
  protected readonly bulkSettling = signal(false);
  /** How many the last bulk settle settled and refused; cleared while the next one runs. */
  protected readonly bulkSettleResult = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal('');
  /** Whether the selected claims are being moved to another person. */
  protected readonly bulkReassigning = signal(false);
  /** How many the last bulk move moved and refused; cleared while the next one runs. */
  protected readonly bulkReassignResult = signal<{ reassigned: number; refused: number } | null>(null);
  protected readonly bulkReassignError = signal('');
  /** Whether the selected claims are being re-dated. */
  protected readonly bulkRedating = signal(false);
  /** How many the last bulk re-date re-dated and refused; cleared while the next one runs. */
  protected readonly bulkRedateResult = signal<{ redated: number; refused: number } | null>(null);
  protected readonly bulkRedateError = signal('');
  /** Whether the selected claims are being voided. */
  protected readonly bulkVoiding = signal(false);
  /** The void reason chosen for the selected claims, or blank. */
  protected readonly bulkVoidReason = signal('');
  /** How many the last bulk void voided and refused; cleared while the next one runs. */
  protected readonly bulkVoidResult = signal<{ voided: number; refused: number } | null>(null);
  protected readonly bulkVoidError = signal('');
  /** The ids of the people selected to be given a grade together. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  /** Whether the selected people are being given a grade. */
  protected readonly bulkGrading = signal(false);
  /** How many the last bulk grade changed; cleared while the next one runs. */
  protected readonly bulkGraded = signal<number | null>(null);
  protected readonly bulkGradeError = signal('');
  /** Whether the selected people are being retired. */
  protected readonly bulkRetiring = signal(false);
  /** How many the last bulk retire retired; cleared while the next one runs. */
  protected readonly bulkRetired = signal<number | null>(null);
  protected readonly bulkRetireError = signal('');
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private readonly editingVersion = signal(0);
  /** Counts the editors opened, so a save answered after another editor was opened leaves that one be. */
  private editorsOpened = 0;
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    // Each load of a sheet re-reads its people from the store and starts the form afresh.
    effect(() => {
      const sheet = this.session.loaded();
      this.reset();
      this.people.set([]);
      this.addedHere.set(new Set());
      this.claims.set([]);
      this.sheetTags.set([]);
      this.rates.set([]);
      this.periods.set([]);
      this.statements.set(new Map());
      this.personError.set(null);
      this.patronError.set(null);
      this.selectedClaims.set(new Set());
      this.bulkResult.set(null);
      this.bulkError.set('');
      this.bulkSettleResult.set(null);
      this.bulkSettleError.set('');
      this.bulkReassignResult.set(null);
      this.bulkReassignError.set('');
      this.bulkRedateResult.set(null);
      this.bulkRedateError.set('');
      this.bulkVoidReason.set('');
      this.bulkVoidResult.set(null);
      this.bulkVoidError.set('');
      this.selectedPeople.set(new Set());
      this.bulkGraded.set(null);
      this.bulkGradeError.set('');
      this.bulkRetired.set(null);
      this.bulkRetireError.set('');
      this.snapshotsCompared.set(null);
      if (sheet) void this.reload(sheet.id);
    });
    // A change of the sheet's today writes a snapshot of everyone, so their snapshots are re-read.
    let seen: { id: string; today: string } | null = null;
    effect(() => {
      const sheet = this.session.sheet();
      const was = seen;
      seen = sheet ? { id: sheet.id, today: sheet.today } : null;
      if (sheet && was !== null && was.id === sheet.id && was.today !== sheet.today) {
        void untracked(() => this.reloadSnapshots(sheet.id));
      }
    });
    // A change of the sheet's parent changes the rates its dues fall back on, so they are re-read.
    effect(() => {
      if (this.sheetParent.changes() === 0) return;
      const sheet = untracked(() => this.session.sheet());
      if (sheet) void this.reloadRates(sheet.id);
    });
  }

  private async reloadRates(sheetId: string): Promise<void> {
    try {
      const rates = await this.ratesApi.list(sheetId);
      if (this.session.sheet()?.id === sheetId) this.rates.set(rates);
    } catch {
      this.error.set('The rates could not be loaded.');
    }
  }

  private async reload(sheetId: string): Promise<void> {
    try {
      const [people, claims, rates, statements, periods, tags] = await this.session.reading(Promise.all([
        this.api.list(sheetId), this.claimsApi.list(sheetId), this.ratesApi.list(sheetId),
        this.statementsApi.list(sheetId), this.periodsApi.list(sheetId), this.claimsApi.tags(sheetId)]));
      if (this.session.sheet()?.id === sheetId) {
        this.statements.set(new Map(statements.map((s) => [s.personId, s])));
        this.rates.set(rates);
        this.periods.set(periods);
        this.claims.set(claims);
        this.sheetTags.set(tags);
        this.people.set(people);
      }
    } catch {
      this.error.set('The people could not be loaded.');
    }
  }

  protected claimsOf(personId: number): ClaimView[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected statementOf(personId: number): StatementView | undefined {
    return this.statements().get(personId);
  }

  /** Take the person's statement, now or as of the date entered (blank: now), then show it and their
   *  claims as it bound them; the date entered is cleared once it is taken. */
  protected async takeStatement(person: PersonView, asOfInput: HTMLInputElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.stating() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    const asOf = asOfInput.value.trim();
    if (asOf !== '' && !DATE.test(asOf)) {
      this.personError.set({ id: person.id, message: 'Enter the statement date as yyyy-mm-dd.' });
      return;
    }
    this.stating.set(person.id);
    try {
      const taken = await this.statementsApi.take(sheet.id, person.id, asOf || null);
      if (this.session.sheet()?.id !== sheet.id) return;
      const bound = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => bound.get(c.id) ?? c));
      this.statements.update((by) => new Map(by).set(person.id, taken.statement));
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: 'The statement could not be taken.' });
      }
    } finally {
      // The date typed for this statement, taken or refused, never carries into the next one.
      asOfInput.value = '';
      this.stating.set(null);
    }
  }

  /** Cancel the person's last statement, then show the one before it and their claims live again. */
  protected async cancelStatement(person: PersonView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.stating() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    this.stating.set(person.id);
    try {
      const cancelled = await this.statementsApi.cancel(sheet.id, person.id);
      if (this.session.sheet()?.id !== sheet.id) return;
      const live = new Map(cancelled.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => live.get(c.id) ?? c));
      this.statements.update((by) => {
        const next = new Map(by);
        if (cancelled.statement) next.set(person.id, cancelled.statement);
        else next.delete(person.id);
        return next;
      });
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: 'The statement could not be cancelled.' });
      }
    } finally {
      this.stating.set(null);
    }
  }

  /** The person being merged into another. */
  protected readonly merging = signal<number | null>(null);

  /** Merge the person into the one chosen in the row's select, then re-read the sheet's people, claims,
   *  statements, pools and crews, which the merge changes. */
  protected async merge(person: PersonView, select: HTMLSelectElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.merging() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    const intoId = Number(select.value);
    if (select.value === '' || !Number.isInteger(intoId)) {
      this.personError.set({ id: person.id, message: 'Choose the person to merge into.' });
      return;
    }
    if (intoId === person.id) {
      this.personError.set({ id: person.id, message: 'A person cannot be merged into themselves.' });
      return;
    }
    this.merging.set(person.id);
    try {
      await this.api.merge(sheet.id, person.id, intoId);
      if (this.session.sheet()?.id !== sheet.id) return;
      await Promise.all([this.reload(sheet.id), this.pools.refresh(sheet.id), this.crews.refresh(sheet.id)]);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can merge people.' : 'The person could not be merged.' });
      }
    } finally {
      this.merging.set(null);
    }
  }

  /** The person being transferred to another sheet. */
  protected readonly transferring = signal<number | null>(null);

  /** Transfer the person to the sheet whose id is entered in the row, then re-read the sheet's people,
   *  claims, statements, pools and crews, which the transfer changes. */
  protected async transfer(person: PersonView, input: HTMLInputElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.transferring() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    const to = input.value.trim();
    if (to === '') {
      this.personError.set({ id: person.id, message: 'Enter the sheet to transfer to.' });
      return;
    }
    if (to === sheet.id) {
      this.personError.set({ id: person.id, message: 'A person cannot be transferred to their own sheet.' });
      return;
    }
    this.transferring.set(person.id);
    try {
      await this.api.transfer(sheet.id, person.id, to);
      if (this.session.sheet()?.id !== sheet.id) return;
      await Promise.all([this.reload(sheet.id), this.pools.refresh(sheet.id), this.crews.refresh(sheet.id)]);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can transfer people.' : 'The person could not be transferred.' });
      }
    } finally {
      this.transferring.set(null);
    }
  }

  /** Undo the transfer that brought the person to this sheet, then re-read the sheet as a transfer does. */
  protected async undoTransfer(person: PersonView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.transferring() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    this.transferring.set(person.id);
    try {
      await this.api.undoTransfer(sheet.id, person.id);
      if (this.session.sheet()?.id !== sheet.id) return;
      await Promise.all([this.reload(sheet.id), this.pools.refresh(sheet.id), this.crews.refresh(sheet.id)]);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can undo a transfer.' : 'The transfer could not be undone.' });
      }
    } finally {
      this.transferring.set(null);
    }
  }

  /** The person being watched or unwatched. */
  protected readonly watching = signal<number | null>(null);

  /** Start watching the person as the signed-in user, or stop when already watching. */
  protected async watch(person: PersonView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.watching() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    this.watching.set(person.id);
    try {
      const changed = await this.api.watch(sheet.id, person.id, !person.watched);
      if (this.session.sheet()?.id !== sheet.id) return;
      this.people.update((list) => list.map((p) => (p.id === changed.id ? changed : p)));
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: person.watched ? 'The person could not be unwatched.'
          : 'The person could not be watched.' });
      }
    } finally {
      this.watching.set(null);
    }
  }

  protected clearPersonError(personId: number): void {
    if (this.personError()?.id === personId) this.personError.set(null);
  }

  /** Swap the person with the one above or below them in the list, then show the list as it now stands. */
  protected async move(person: PersonView, direction: 'up' | 'down'): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.moving() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    this.moving.set(person.id);
    try {
      await this.api.move(sheet.id, person.id, direction);
      const people = await this.api.list(sheet.id);
      if (this.session.sheet()?.id === sheet.id) this.people.set(people);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: e instanceof HttpErrorResponse && e.status === 409
          ? (direction === 'up' ? 'The top person cannot move up.' : 'The bottom person cannot move down.')
          : 'The person could not be moved.' });
      }
    } finally {
      this.moving.set(null);
    }
  }

  /** Give the person the grade chosen on their row from the sheet's today, as the person form would. */
  protected async saveGrade(person: PersonView, gradeSelect: HTMLSelectElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.inlineGrading() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    const grade = gradeSelect.value as Grade;
    if (!GRADES.includes(grade)) {
      this.personError.set({ id: person.id, message: 'Choose a grade of A, B or C.' });
      return;
    }
    this.inlineGrading.set(person.id);
    try {
      const saved = await this.api.gradeOne(sheet.id, person.id, person.version, grade);
      if (this.session.sheet()?.id !== sheet.id) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: e instanceof HttpErrorResponse && e.status === 412
          ? 'Someone has saved the person since; re-load the sheet to see them.'
          : 'The person could not be given the grade.' });
      }
    } finally {
      this.inlineGrading.set(null);
    }
  }

  /** Retire the person from the date entered, or the sheet's today when it is left blank. */
  protected async retire(person: PersonView, from: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.retiring() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    this.retiring.set(person.id);
    try {
      const retired = await this.api.retire(sheet.id, person.id, from.trim() === '' ? null : from.trim());
      if (this.session.sheet()?.id !== sheet.id) return;
      this.people.update((list) => list.map((p) => (p.id === retired.id ? retired : p)));
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: 'The person could not be retired.' });
      }
    } finally {
      this.retiring.set(null);
    }
  }

  /** Unretire the person on the sheet's today; the screen refuses no one, the store refuses anyone not retired. */
  protected async unretire(person: PersonView): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.retiring() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    this.retiring.set(person.id);
    try {
      const unretired = await this.api.unretire(sheet.id, person.id);
      if (this.session.sheet()?.id !== sheet.id) return;
      this.people.update((list) => list.map((p) => (p.id === unretired.id ? unretired : p)));
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: 'Only a retired person can be unretired.' });
      }
    } finally {
      this.retiring.set(null);
    }
  }

  /** The person's carried figure for the period, with two decimals. */
  protected personPeriodCarried(personId: number, periodId: number): string {
    return money(this.personCarriedCents().get(personId)?.get(periodId) ?? 0);
  }

  /** The person's due-total for the period, with two decimals. */
  protected personPeriodDue(personId: number, periodId: number): string {
    return money(this.personPeriodCents().get(personId)?.get(periodId) ?? 0);
  }

  /** Whether the claim counts in the live totals: open (PENDING included) and settled claims do,
   *  except open claims of a person whose retirement date the sheet's today has reached, that no
   *  statement binds, and claims deferred on the sheet's today. A hold, the person's or the claim's,
   *  leaves a claim in every total. */
  private countsLive(claim: ClaimView): boolean {
    return counts(claim) && !isDeferred(claim, this.session.sheet()?.today) && !(isOpen(claim) && !isBound(claim)
      && this.retired(claim));
  }

  /** Whether the claim's person's retirement date is reached by the sheet's today. */
  private retired(claim: ClaimView): boolean {
    const retiredFrom = this.retiredFromById().get(claim.personId) ?? null;
    const today = this.session.sheet()?.today;
    return retiredFrom !== null && today !== undefined && retiredFrom <= today;
  }

  /** Whether the claim counts in a period ending on `end`: as countsLive decides, but by the status
   *  its log records at the end of that day (none when added after it); deferral is as today, and a
   *  reached retirement only leaves out a claim that is still open now, as it touches only live claims. */
  private countsAt(claim: ClaimView, end: string): boolean {
    const status = statusAsOf(claim, end);
    if (status === null) return false;
    const then: ClaimView = { ...claim, status };
    return counts(then) && !isDeferred(claim, this.session.sheet()?.today) && !(isOpen(then) && isOpen(claim) && !isBound(then)
      && this.retired(claim));
  }

  /** The person's patrons, each as <name> <share>, sorted by name, comma and space separated. */
  protected patronNames(person: PersonView): string {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    return sharesOf(person.patronIds).map(([id, share]) => [byId.get(id), share] as const)
      .filter((e): e is readonly [PersonView, bigint] => e[0] !== undefined)
      .sort(([a], [b]) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
      .map(([p, share]) => `${this.shownName(p)} ${money(Number(share))}`).join(', ');
  }

  /** The person's name as the screen shows it, with their grade in force on the sheet's today. */
  protected shownName(person: PersonView): string {
    return nameWithGrade(person, this.session.sheet()?.today);
  }

  /** The person's backing: over their patrons, the patron's settled figure (their SETTLED claims dated
   *  within the 90 days up to and including the sheet's today, each at its frozen figure) times their
   *  share over 100, added up and rounded half to even; 0.00 with none. */
  protected backing(person: PersonView): string {
    const today = this.session.sheet()?.today;
    if (today === undefined) return money(0);
    const from = daysBefore(today, 89);
    const shares = new Map(sharesOf(person.patronIds));
    const figures = new Map<number, number>();
    for (const c of this.claims()) {
      if (shares.has(c.personId) && c.status === 'SETTLED' && c.settledDue !== null
        && c.date >= from && c.date <= today) figures.set(c.personId, (figures.get(c.personId) ?? 0) + cents(c.settledDue));
    }
    let total = 0n;
    for (const [id, figure] of figures) total += BigInt(figure) * (shares.get(id) ?? 0n);
    return money(Number(divideHalfEven(total, 10000n)));
  }

  protected clearPatronError(personId: number): void {
    if (this.patronError()?.id === personId) this.patronError.set(null);
  }

  /** Give the person the alias typed in the row's entry; a blank one is refused. */
  protected async addAlias(person: PersonView, input: HTMLInputElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.addingAlias() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    const alias = input.value.trim();
    if (alias === '') {
      this.personError.set({ id: person.id, message: 'Type an alias.' });
      return;
    }
    this.addingAlias.set(person.id);
    try {
      const changed = await this.api.addAlias(sheet.id, person.id, alias);
      if (this.session.sheet()?.id !== sheet.id) return;
      this.people.update((list) => list.map((p) => (p.id === changed.id ? changed : p)));
      input.value = '';
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: e instanceof HttpErrorResponse && e.status === 409
          ? 'That alias is already a name or alias on the sheet.' : 'The alias could not be added.' });
      }
    } finally {
      this.addingAlias.set(null);
    }
  }

  /** Name the person chosen in the row's select as the person's patron. */
  protected async addPatron(person: PersonView, select: HTMLSelectElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.addingPatron() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    const patronId = Number(select.value);
    if (select.value === '' || !Number.isInteger(patronId)) {
      this.patronError.set({ id: person.id, message: 'Choose a patron.' });
      return;
    }
    if (patronId === person.id) {
      this.patronError.set({ id: person.id, message: 'A person cannot be their own patron.' });
      return;
    }
    if (person.patronIds.includes(patronId)) {
      this.patronError.set({ id: person.id, message: 'That person is already a patron.' });
      return;
    }
    this.addingPatron.set(person.id);
    try {
      const changed = await this.api.addPatron(sheet.id, person.id, patronId);
      if (this.session.sheet()?.id !== sheet.id) return;
      this.people.update((list) => list.map((p) => (p.id === changed.id ? changed : p)));
      select.value = '';
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        this.patronError.set({ id: person.id, message: e instanceof HttpErrorResponse && e.status === 409
          ? 'That person cannot be added as a patron again.' : 'The patron could not be added.' });
      }
    } finally {
      this.addingPatron.set(null);
    }
  }

  /** Name the row's user as the person's approver (blank: none). */
  protected async setApprover(person: PersonView, input: HTMLInputElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.settingApprover() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    this.settingApprover.set(person.id);
    try {
      const changed = await this.api.setApprover(sheet.id, person.id, input.value.trim());
      if (this.session.sheet()?.id !== sheet.id) return;
      this.people.update((list) => list.map((p) => (p.id === changed.id ? changed : p)));
      input.value = '';
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: 'The approver could not be set.' });
      }
    } finally {
      this.settingApprover.set(null);
    }
  }

  /** Flag the person with the row's reason until its until-date (blank: the sheet's today). */
  protected async flag(person: PersonView, reasonInput: HTMLInputElement, untilInput: HTMLInputElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.flagging() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    const reason = reasonInput.value.trim();
    const until = untilInput.value.trim();
    if (reason === '') {
      this.personError.set({ id: person.id, message: 'Give the flag a reason.' });
      return;
    }
    if (until !== '' && !DATE.test(until)) {
      this.personError.set({ id: person.id, message: 'Enter the until date as yyyy-mm-dd.' });
      return;
    }
    this.flagging.set(person.id);
    try {
      const changed = await this.api.flag(sheet.id, person.id, reason, until === '' ? null : until);
      if (this.session.sheet()?.id !== sheet.id) return;
      this.people.update((list) => list.map((p) => (p.id === changed.id ? changed : p)));
      reasonInput.value = '';
      untilInput.value = '';
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: 'The person could not be flagged.' });
      }
    } finally {
      this.flagging.set(null);
    }
  }

  /** Put a hold on the person from the row's from-date (blank: the sheet's today) to its to-date
   *  (blank: no end). */
  protected async hold(person: PersonView, fromInput: HTMLInputElement, toInput: HTMLInputElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.holding() !== null) return;
    this.personError.set(null);
    this.patronError.set(null);
    const from = fromInput.value.trim();
    const to = toInput.value.trim();
    if ((from !== '' && !DATE.test(from)) || (to !== '' && !DATE.test(to))) {
      this.personError.set({ id: person.id, message: 'Enter the hold dates as yyyy-mm-dd.' });
      return;
    }
    if (to !== '' && to < (from !== '' ? from : sheet.today)) {
      this.personError.set({ id: person.id, message: 'A hold cannot end before it starts.' });
      return;
    }
    this.holding.set(person.id);
    try {
      const changed = await this.api.hold(sheet.id, person.id, from === '' ? null : from, to === '' ? null : to);
      if (this.session.sheet()?.id !== sheet.id) return;
      this.people.update((list) => list.map((p) => (p.id === changed.id ? changed : p)));
      fromInput.value = '';
      toInput.value = '';
    } catch {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: 'The person could not be put on hold.' });
      }
    } finally {
      this.holding.set(null);
    }
  }

  /** The grace days of the claim: those of its person's grade in force on its date, in force on that date. */
  private graceOf(claim: ClaimView): number {
    const grades = this.gradesById().get(claim.personId);
    return grades === undefined ? 0 : this.sheetGrace.daysOn(gradeOn(grades, claim.date), claim.date);
  }

  /** The person's overdue claims that count: how many, and the sum of their figures in cents. */
  protected overdueOf(personId: number): { count: number; cents: number } {
    return this.overdueByPerson().get(personId) ?? { count: 0, cents: 0 };
  }

  /** The person's score: their settled figures divided by 100, less 5 for each overdue claim that
   *  counts, rounded half to even to a whole number. */
  protected scoreOf(personId: number): string {
    // In cents, the score times 10000: the settled cents less 50000 per overdue claim.
    const scaled = this.settledOpenOf(personId).settled - 50000 * this.overdueOf(personId).count;
    const floor = Math.floor(scaled / 10000);
    const rest = scaled - floor * 10000;
    const whole = rest > 5000 || (rest === 5000 && floor % 2 !== 0) ? floor + 1 : floor;
    return String(whole === 0 ? 0 : whole);
  }

  /** The due of the person's settled and of their open claims that count, in cents. */
  protected settledOpenOf(personId: number): SettledOpen {
    return this.settledOpenByPerson().get(personId) ?? { settled: 0, open: 0 };
  }

  /** The due of the person's open claims that count, in cents, by age. */
  protected agedOf(personId: number): Aged {
    return this.agedByPerson().get(personId) ?? { upTo30: 0, upTo60: 0, over60: 0 };
  }

  /** Whether the claim counts toward what is claimed: it counts live and is not a correction. */
  private countsClaimed(claim: ClaimView): boolean {
    return this.countsLive(claim) && !isCorrection(claim);
  }

  /** The sum of the person's open and settled claims, leaving out corrections, with two decimals. */
  protected claimed(personId: number): string {
    return money(this.centsByPerson().get(personId) ?? 0);
  }

  /** The grade's rates, whatever their from-dates. */
  private ratesOf(grade: Grade | null): RateView[] {
    return grade === null ? [] : (this.ratesByGrade().get(grade) ?? []);
  }

  /** The rates of the person's grade in force on a date, whatever their from-dates. */
  private ratesOn(person: PersonView, date: string): RateView[] {
    return this.ratesOf(gradeOn(person.grades, date));
  }

  /** The person's key to sort by: their name as names are compared (without surrounding spaces or
   *  regard to case), the place of their grade today, or their due-total in cents (null, n/a, last). */
  private sortValue(person: PersonView, by: Exclude<SortKey, 'added'>): string | number | null {
    switch (by) {
      case 'name': return person.name.trim().toLowerCase();
      case 'grade': return GRADES.indexOf(this.gradeToday(person));
      case 'due-total': return this.dueCentsOf(person);
    }
  }

  /** The person's grade in force on the sheet's today. */
  protected gradeToday(person: PersonView): Grade {
    const today = this.session.sheet()?.today;
    return (today === undefined ? null : gradeOn(person.grades, today)) ?? person.grade;
  }

  /** The grade's rate in force on the sheet's today, or null when none is. */
  private rateOf(grade: Grade): string | null {
    const today = this.session.sheet()?.today;
    return today === undefined ? null : (rateInForce(this.ratesOf(grade), today)?.pct ?? null);
  }

  /** The person's due-total in cents: the sum of their figures held to the sheet's cap; null (n/a), which
   *  is not capped, when that sum is. */
  private dueCentsOf(person: PersonView): number | null {
    const sum = this.figureCentsOf(person);
    const cap = this.cap.cents();
    return sum === null || cap === null ? sum : Math.min(sum, cap);
  }

  /** The person's excess: how far the sum of their figures is above the cap, with two decimals; 0.00
   *  when it is not above it, there is no cap, or it is n/a. */
  protected excess(person: PersonView): string {
    const sum = this.figureCentsOf(person);
    const due = this.dueCentsOf(person);
    return money(sum === null || due === null ? 0 : sum - due);
  }

  /** The sum of the figures due on the person's open and settled claims, each at the grade in force on
   *  its date, in cents; null (n/a) when no rate of their grade in force today is in force today and
   *  none of their claims keeps a figure. */
  private figureCentsOf(person: PersonView): number | null {
    const today = this.session.sheet()?.today;
    const gradeToday = this.gradeToday(person);
    let total = 0;
    let keptFigure = false;
    for (const c of this.claimsOf(person.id)) {
      if (!this.countsLive(c)) continue;
      const grade = gradeOn(person.grades, c.date);
      const due = claimOwedCents(c, this.ratesOf(grade), this.floor.centsOn(c.date), this.lateness(), this.claims());
      if (due !== null) total += due;
      if (keepsFigure(c)) keptFigure = true;
      // A live figure at a grade other than today's is not undone by today's grade having no rate.
      if (due !== null && grade !== gradeToday) keptFigure = true;
      // Nor is a live figure on a claim dated by today undone by its rate having ended since.
      if (due !== null && today !== undefined && c.date <= today) keptFigure = true;
    }
    return this.rateOf(gradeToday) === null && !keptFigure ? null : total;
  }

  /** The person's due-total with two decimals, or n/a when no rate of their grade in force today is in
   *  force today and no claim keeps a figure (settled, bound by a statement, at another grade, or dated by
   *  today). */
  protected personDueTotal(person: PersonView): string {
    const due = this.dueCentsOf(person);
    return due === null ? 'n/a' : money(due);
  }

  /** Compare the snapshots of the dates entered. */
  protected compareSnapshots(): void {
    this.snapshotsCompared.set({ from: this.snapshotFrom.value.trim(), to: this.snapshotTo.value.trim() });
  }

  /** Re-read each person's snapshots from the store, keeping the rest of what the screen holds. */
  private async reloadSnapshots(sheetId: string): Promise<void> {
    try {
      const fresh = new Map((await this.api.list(sheetId)).map((p) => [p.id, p.snapshots]));
      if (this.session.sheet()?.id !== sheetId) return;
      this.people.update((list) => list.map((p) => {
        const snapshots = fresh.get(p.id);
        return snapshots === undefined ? p : { ...p, snapshots };
      }));
    } catch {
      this.error.set('The snapshots could not be loaded.');
    }
  }

  /** Downloads everyone on the sheet, retired or not, in list order: their name, grade in force today,
   *  retired-from date and due-total as shown, one line each after the header, each ending in a line feed. */
  protected async exportCsv(): Promise<void> {
    await this.session.settled();
    this.download('people.csv', [['name', 'grade', 'retired-from', 'due-total'],
      ...this.people().map((p) => [p.name, this.gradeToday(p), p.retiredFrom ?? '', this.personDueTotal(p)])]);
  }

  /** Downloads every claim on the sheet, retired people's included: people in list order, each person's
   *  claims oldest first, with their number, date, amount, due, status and period as their rows show them. */
  protected async exportClaims(): Promise<void> {
    await this.session.settled();
    const lines = [['person', 'number', 'date', 'amount', 'due', 'status', 'period'],
      ...this.people().flatMap((p) => [...this.claimsOf(p.id)].reverse().map((c) => {
        const due = claimOwedCents(c, this.ratesOn(p, c.date), this.floor.centsOn(c.date), this.lateness(), this.claims());
        return [p.name, c.number ?? '', c.date, c.amount, due === null ? 'n/a' : money(due), c.status,
          periodOf(this.periods(), c.dueBy)?.name ?? ''];
      }))];
    this.download('claims.csv', lines);
  }

  /** Downloads the person's statement: each claim of theirs their last statement bound, oldest first, with
   *  its number, date, amount, the figure it was bound to (n/a when none) and status. */
  protected async exportStatement(person: PersonView): Promise<void> {
    await this.session.settled();
    this.download('statement.csv', [['number', 'date', 'amount', 'figure', 'status'],
      ...[...this.claimsOf(person.id)].reverse().filter(isBound)
        .map((c) => [c.number ?? '', c.date, c.amount, c.statedDue ?? 'n/a', c.status])]);
  }

  /** Offers the lines as a CSV file: a field holding a quote, comma or line end is quoted, and each line
   *  ends in a line feed. */
  private download(fileName: string, lines: readonly (readonly string[])[]): void {
    const field = (v: string) => /[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
    const csv = lines.map((l) => l.map(field).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.click();
    // Released once the browser has taken the file.
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected rateSaved(rate: RateView): void {
    // Kept as the store lists them: by level, in grade order, then the rate from the start, then by from-date.
    this.rates.update((list) => [...list.filter((r) => !sameRate(r, rate)), rate]
      .sort((a, b) => a.level - b.level || a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? '')));
  }

  protected periodAdded(period: PeriodView): void {
    // Kept as the store lists them: in date order.
    this.periods.update((list) => [...list, period].sort((a, b) => a.from.localeCompare(b.from)));
  }

  protected periodChanged(period: PeriodView): void {
    this.periods.update((list) => list.map((p) => (p.id === period.id ? period : p)));
  }

  protected claimAdded(claim: ClaimView): void {
    this.claims.update((list) => [claim, ...list]);
  }

  /** A claim moved to another person: listed first, as their newest claim, whatever its date. */
  protected claimReassigned(claim: ClaimView): void {
    this.claims.update((list) => [claim, ...list.filter((c) => c.id !== claim.id)]);
  }

  /** A split claim, with the rest of its amount, and the new claim split off it, listed first. */
  protected claimSplit(split: SplitView): void {
    this.claims.update((list) => [split.split, ...list.map((c) => (c.id === split.original.id ? split.original : c))]);
  }

  /** A quick-add's claim, and its person when the quick-add added them. */
  protected quickAdded(added: QuickAddView): void {
    if (!this.people().some((p) => p.id === added.person.id)) {
      this.people.update((list) => [...list, added.person]);
      this.addedHere.update((ids) => new Set(ids).add(added.person.id));
    }
    this.claimAdded(added.claim);
  }

  /** An import's people, in the order of their lines: those it updated replace their rows, the rest are added. */
  protected imported(people: PersonView[]): void {
    const changed = new Map(people.map((p) => [p.id, p]));
    this.people.update((list) => {
      const kept = list.map((p) => changed.get(p.id) ?? p);
      const known = new Set(list.map((p) => p.id));
      return [...kept, ...people.filter((p) => !known.has(p.id))];
    });
  }

  /** A paste's claims, in the order of their lines: those it updated replace their rows, the rest are
   *  added, the last line's claim the newest. */
  protected pasted(claims: ClaimView[]): void {
    const changed = new Map(claims.map((c) => [c.id, c]));
    this.claims.update((list) => {
      const known = new Set(list.map((c) => c.id));
      const added = claims.filter((c) => !known.has(c.id)).reverse();
      return [...added, ...list.map((c) => changed.get(c.id) ?? c)];
    });
  }

  protected selectClaim(id: number, on: boolean): void {
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Open the selected claims together, in list order; each that cannot be opened is left as it is. */
  protected async openSelected(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.bulkOpening()) return;
    this.bulkResult.set(null);
    this.bulkError.set('');
    this.bulkOpening.set(true);
    try {
      const result = await this.claimsApi.openSelected(sheet.id, [...this.selectedClaims()]);
      if (this.session.sheet()?.id !== sheet.id) return;
      for (const claim of result.claims) this.claimSaved(claim);
      this.selectedClaims.set(new Set());
      this.bulkResult.set({ opened: result.opened, refused: result.refused });
    } catch {
      if (this.session.sheet()?.id === sheet.id) this.bulkError.set('The selected claims could not be opened.');
    } finally {
      this.bulkOpening.set(false);
    }
  }

  /** Settle the selected claims together, in list order, all or nothing; one left pending counts as
   *  settled, and if any cannot be settled none is and every one counts as refused. */
  protected async settleSelected(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.bulkSettling()) return;
    this.bulkSettleResult.set(null);
    this.bulkSettleError.set('');
    this.bulkSettling.set(true);
    try {
      const result = await this.claimsApi.settleSelected(sheet.id, [...this.selectedClaims()]);
      if (this.session.sheet()?.id !== sheet.id) return;
      for (const claim of result.claims) this.claimSaved(claim);
      if (result.batch !== null) this.session.settleBatchMade(sheet.id, result.batch);
      this.selectedClaims.set(new Set());
      this.bulkSettleResult.set({ settled: result.settled, refused: result.refused });
    } catch {
      if (this.session.sheet()?.id === sheet.id) this.bulkSettleError.set('The selected claims could not be settled.');
    } finally {
      this.bulkSettling.set(false);
    }
  }

  /** Move the selected claims to the chosen person together, in list order, each by the rules of
   *  moving one; each that cannot be moved is left as it is. */
  protected async reassignSelected(to: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.bulkReassigning()) return;
    this.bulkReassignResult.set(null);
    this.bulkReassignError.set('');
    if (to === '') {
      this.bulkReassignError.set('Choose the person to move the selected claims to.');
      return;
    }
    this.bulkReassigning.set(true);
    try {
      const result = await this.claimsApi.reassignSelected(sheet.id, [...this.selectedClaims()], Number(to));
      if (this.session.sheet()?.id !== sheet.id) return;
      for (const claim of result.claims) this.claimReassigned(claim);
      this.selectedClaims.set(new Set());
      this.bulkReassignResult.set({ reassigned: result.reassigned, refused: result.refused });
    } catch {
      if (this.session.sheet()?.id === sheet.id) this.bulkReassignError.set('The selected claims could not be moved.');
    } finally {
      this.bulkReassigning.set(false);
    }
  }

  /** Re-date the selected claims to the date together, in list order, each by the rules of re-dating
   *  one; each that cannot be re-dated is left as it is. */
  protected async redateSelected(date: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.bulkRedating()) return;
    this.bulkRedateResult.set(null);
    this.bulkRedateError.set('');
    const dated = date.trim();
    if (!DATE.test(dated)) {
      this.bulkRedateError.set('Type the new date of the selected claims as yyyy-mm-dd.');
      return;
    }
    this.bulkRedating.set(true);
    try {
      const result = await this.claimsApi.redateSelected(sheet.id, [...this.selectedClaims()], dated);
      if (this.session.sheet()?.id !== sheet.id) return;
      for (const claim of result.claims) this.claimSaved(claim);
      this.selectedClaims.set(new Set());
      this.bulkRedateResult.set({ redated: result.redated, refused: result.refused });
    } catch {
      if (this.session.sheet()?.id === sheet.id) this.bulkRedateError.set('The selected claims could not be re-dated.');
    } finally {
      this.bulkRedating.set(false);
    }
  }

  /** Void the selected claims together, in list order, each as its own void would (with the chosen
   *  reason, while the sheet has void reasons); each that cannot be voided is left as it is. */
  protected async voidSelected(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.bulkVoiding()) return;
    this.bulkVoidResult.set(null);
    this.bulkVoidError.set('');
    const chosen = this.bulkVoidReason();
    const reason = this.voidReasons.reasons().some((r) => r.reason === chosen) ? chosen : '';
    this.bulkVoiding.set(true);
    try {
      const result = await this.claimsApi.voidSelected(sheet.id, [...this.selectedClaims()], reason);
      if (this.session.sheet()?.id !== sheet.id) return;
      for (const claim of result.claims) this.claimSaved(claim);
      this.selectedClaims.set(new Set());
      this.bulkVoidResult.set({ voided: result.voided, refused: result.refused });
    } catch {
      if (this.session.sheet()?.id === sheet.id) this.bulkVoidError.set('The selected claims could not be voided.');
    } finally {
      this.bulkVoiding.set(false);
    }
  }

  protected selectPerson(id: number, on: boolean): void {
    this.bulkGradeError.set('');
    this.bulkRetireError.set('');
    this.selectedPeople.update((ids) => {
      const next = new Set(ids);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Give the selected people the grade from the from-date (blank: the sheet's today), in list order,
   *  each as the person form would. */
  protected async gradeSelected(gradeSelect: HTMLSelectElement, fromInput: HTMLInputElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.bulkGrading()) return;
    this.bulkGraded.set(null);
    this.bulkGradeError.set('');
    this.bulkRetireError.set('');
    this.personError.set(null);
    const grade = gradeSelect.value as Grade;
    const from = fromInput.value.trim();
    if (!GRADES.includes(grade)) {
      this.bulkGradeError.set('Choose a grade of A, B or C.');
      return;
    }
    if (from !== '' && !DATE.test(from)) {
      this.bulkGradeError.set('Enter the from-date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    this.bulkGrading.set(true);
    try {
      const result = await this.api.grade(sheet.id, [...this.selectedPeople()], grade, from === '' ? null : from);
      if (this.session.sheet()?.id !== sheet.id) return;
      const changed = new Map(result.people.map((p) => [p.id, p]));
      this.people.update((list) => list.map((p) => changed.get(p.id) ?? p));
      this.selectedPeople.set(new Set());
      this.bulkGraded.set(result.changed);
    } catch {
      if (this.session.sheet()?.id === sheet.id) this.bulkGradeError.set('The selected people could not be given the grade.');
    } finally {
      this.bulkGrading.set(false);
    }
  }

  /** Retire the selected people from the date (blank: the sheet's today), in list order, each as the
   *  row's retire would. */
  protected async retireSelected(fromInput: HTMLInputElement): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet || this.bulkRetiring()) return;
    this.bulkRetired.set(null);
    this.bulkRetireError.set('');
    this.bulkGradeError.set('');
    this.personError.set(null);
    const from = fromInput.value.trim();
    if (from !== '' && !DATE.test(from)) {
      this.bulkRetireError.set('Enter the date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    this.bulkRetiring.set(true);
    try {
      const result = await this.api.retireSelected(sheet.id, [...this.selectedPeople()], from === '' ? null : from);
      if (this.session.sheet()?.id !== sheet.id) return;
      const retired = new Map(result.people.map((p) => [p.id, p]));
      this.people.update((list) => list.map((p) => retired.get(p.id) ?? p));
      this.selectedPeople.set(new Set());
      this.bulkRetired.set(result.retired);
    } catch {
      if (this.session.sheet()?.id === sheet.id) this.bulkRetireError.set('The selected people could not be retired.');
    } finally {
      this.bulkRetiring.set(false);
    }
  }

  protected claimSaved(claim: ClaimView): void {
    this.claims.update((list) => list.map((c) => (c.id === claim.id ? claim : c)));
  }

  private reset(): void {
    this.editingId.set(null);
    this.error.set('');
    this.form.reset({ name: '', grade: 'A', gradeFrom: '', terms: '', termsFrom: '', contact: '', tags: '' }, { emitEvent: false });
  }

  protected edit(person: PersonView): void {
    this.personError.set(null);
    this.patronError.set(null);
    this.editorsOpened++;
    this.editingId.set(person.id);
    this.editingVersion.set(person.version);
    this.error.set('');
    this.form.setValue({ name: person.name, grade: this.gradeToday(person), gradeFrom: '',
      terms: termsText(person), termsFrom: '', contact: person.contact ?? '', tags: person.tags.join(', ') }, { emitEvent: false });
  }

  protected cancel(): void {
    this.reset();
  }

  /** After an edit was refused because someone else saved the person meanwhile: re-read the people
   *  and show the person's current values in the editor, keeping the refusal showing. */
  private async showCurrent(sheetId: string, id: number): Promise<void> {
    try {
      const people = await this.api.list(sheetId);
      if (this.session.sheet()?.id !== sheetId) return;
      this.people.set(people);
      const current = people.find((p) => p.id === id);
      if (current && this.editingId() === id) {
        this.editingVersion.set(current.version);
        this.form.setValue({ name: current.name, grade: this.gradeToday(current), gradeFrom: '',
          terms: termsText(current), termsFrom: '', contact: current.contact ?? '', tags: current.tags.join(', ') }, { emitEvent: false });
      }
    } finally {
      this.error.set('Someone else has saved this person since you opened it; the form now shows their changes.');
    }
  }

  /** Re-read the people, so the screen shows what others have saved meanwhile; the form is left as it is. */
  private async reread(sheetId: string): Promise<void> {
    try {
      const people = await this.api.list(sheetId);
      if (this.session.sheet()?.id === sheetId) this.people.set(people);
    } catch {
      // The list stays as it was; the next load of the sheet re-reads it.
    }
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const value = this.form.getRawValue();
    if (!value.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    if (!GRADES.includes(value.grade)) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    const id = this.editingId();
    const gradeFrom = value.gradeFrom.trim();
    if (id !== null && gradeFrom !== '' && !DATE.test(gradeFrom)) {
      this.error.set('Type the grade\'s from-date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    const terms = value.terms.trim();
    if (!/^\d{0,5}$/.test(terms)) {
      // A refused edit closes the editor, saving nothing, so the form is ready to add a person again.
      if (id !== null) this.reset();
      this.error.set('Type the terms as a whole number of days, or leave them blank for none.');
      return;
    }
    const termsFrom = value.termsFrom.trim();
    if (termsFrom !== '' && !DATE.test(termsFrom)) {
      this.error.set('Type the terms\' from-date as yyyy-mm-dd, or leave it blank for the beginning of time.');
      return;
    }
    const contact = value.contact.trim().toLowerCase();
    if (contact !== '' && this.people().some((p) => p.id !== id && p.contact === contact)) {
      this.error.set('That contact is already in use on the sheet.');
      void this.reread(sheet.id);
      return;
    }
    this.saving.set(true);
    const editor = this.editorsOpened;
    try {
      if (id === null) {
        const added = await this.api.add(sheet.id,
          { name: value.name.trim(), grade: value.grade, terms,
            termsFrom: termsFrom === '' ? null : termsFrom, contact: value.contact,
            tags: value.tags });
        if (this.session.sheet()?.id === sheet.id) {
          this.people.update((list) => [...list, added]);
          this.addedHere.update((ids) => new Set(ids).add(added.id));
        }
      } else {
        const saved = await this.api.update(sheet.id, id,
          { name: value.name.trim(), grade: value.grade, gradeFrom: gradeFrom === '' ? null : gradeFrom, terms,
            termsFrom: termsFrom === '' ? null : termsFrom,
            contact: value.contact, tags: value.tags, version: this.editingVersion() });
        if (this.session.sheet()?.id === sheet.id) {
          this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
          // An editor opened on the person while this save ran edits the version it stored.
          if (editor !== this.editorsOpened && this.editingId() === saved.id) this.editingVersion.set(saved.version);
        }
      }
      if (this.session.sheet()?.id === sheet.id && editor === this.editorsOpened) this.reset();
    } catch (e) {
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(sheet.id, id);
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        const message: unknown = e.error?.message;
        this.error.set(typeof message === 'string' && message ? message : 'That name is already in use on the sheet.');
      } else {
        this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    } finally {
      this.saving.set(false);
    }
  }
}

/** A person's due in cents by the age of the claims: 0 to 30 days, 31 to 60, and 61 or more. */
interface Aged { upTo30: number; upTo60: number; over60: number }

/** The due of a person's settled claims and of their open ones, in cents. */
interface SettledOpen { settled: number; open: number }

/** The person's own terms from the beginning of time as the form shows them: blank when they have none. */
function termsText(person: PersonView): string {
  return person.ownTerms === null ? '' : String(person.ownTerms);
}

/** The sum of the figures, leaving out the single highest and the single lowest when there are three or more. */
function trimmedSum(figures: number[]): number {
  const total = figures.reduce((t, f) => t + f, 0);
  return figures.length < 3 ? total : total - Math.max(...figures) - Math.min(...figures);
}

/** The date `days` days before `date` (both yyyy-mm-dd). */
function daysBefore(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d - days)).toISOString().slice(0, 10);
}

/** A whole number divided by another, rounded half to even. */
function divideHalfEven(value: bigint, by: bigint): bigint {
  const quotient = value / by;
  const twice = 2n * (value % by);
  return twice > by || (twice === by && quotient % 2n !== 0n) ? quotient + 1n : quotient;
}

/** The shares (in hundredths) of the patrons, in the order they were named: 100.00 split equally, each
 *  rounded half to even, the remainder to the first named. */
function sharesOf(patronIds: readonly number[]): [number, bigint][] {
  const count = BigInt(patronIds.length);
  if (count === 0n) return [];
  const each = divideHalfEven(10000n, count);
  return patronIds.map((id, i) => [id, i === 0 ? 10000n - each * (count - 1n) : each]);
}

/** What the people list can be sorted by on the screen. */
type SortKey = 'added' | 'name' | 'grade' | 'due-total';

/** Orders two sort keys ascending, a null (n/a) one last. */
function compareSortValues(a: string | number | null, b: string | number | null): number {
  if (a === null || b === null) return a === b ? 0 : a === null ? 1 : -1;
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  return a < b ? -1 : a > b ? 1 : 0;
}
