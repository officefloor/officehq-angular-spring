import { DOCUMENT, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { FigurePipe } from '../../core/figure.pipe';
import { PEOPLE_COLUMNS, PeopleColumn, Session, otherError } from '../../core/session';
import { AMOUNT_PATTERN, Claim, ClaimsApi, byListed, cents, counts, deferred, dueBy, isBound, isOpen, money, overdue, pasteError, quickAddError, roundsHalfUp, statusOn, tagsOf } from '../claims/claims-api';
import { PersonClaims } from '../claims/person-claims';
import { PersonPlans } from '../plans/person-plans';
import { PlansApi } from '../plans/plans-api';
import { Pools } from '../pools/pools';
import { Crews } from '../crews/crews';
import { Crew, crewIds } from '../crews/crews-api';
import { Periods } from '../periods/periods';
import { Period, PeriodsApi, periodOn, personPeriodCarriedCents, personPeriodDueCents } from '../periods/periods-api';
import { Rates } from '../rates/rates';
import { STATUSES, Summary, SummaryPerson, statusCounts } from '../summary/summary';
import { ClassicSummary } from '../summary/classic-summary';
import { Lateness, Rate, RateLevels, RatePreview, RatesApi, claimDueCents, pctOn, plusDays } from '../rates/rates-api';
import { Dashboard, Header } from '../header/header';
import { DescendantFigures, FigureMember, GRADES, NO_FIGURES, PeopleApi, Person, aliasError, approverError, bulkGradeError, bulkRetireError, csvLine, gradeError, gradeOn, holdError, flagError, importError, isStale, mergeError, moveError, patronError, personError, personTags, cancelStatementError, divideHalfEven, patronShares, retireError, statementError, transferError, transferUndoError, unretireError, watchError } from './people-api';

// The sheet's rates; its periods, each with its due-total; its pools; its crews, each with its lead and due-total; the people on the active sheet, in the order they were added, each with their
// claims, what they have claimed, their due-total (held to the sheet's cap less their cut total), its excess over that and its settled and open parts, their overdue claims, their open claims' dues by age, and their patrons with their shares and backing (their patrons' recent settled figures by share); the sheet's claimed-total,
// due-total and overdue claims; a header of counters repeating them; a summary of them by grade, period and status; and
// the one form that adds a person or, with a
// person's editor open, saves that person.
@Component({
  selector: 'app-people',
  imports: [FigurePipe, ReactiveFormsModule, Header, PersonClaims, PersonPlans, Periods, Pools, Crews, Rates, Summary, ClassicSummary],
  template: `
    <app-header [peopleCount]="people().length" [claims]="claims()" [dueTotal]="dueTotal()" [overdueTotal]="overdueTotal()" [consolidated]="consolidated()"
      [myDueTotal]="myFigures()?.dueTotal ?? null" [myOverdueTotal]="myFigures()?.overdueTotal ?? null"
      [myCrewDueTotal]="myCrewDueTotal()" [watchedDueTotal]="watchedDueTotal()" [dashboard]="dashboard()"
      [user]="session.user()" />
    <app-rates [rates]="rates()" [effective]="effectiveRates()" [preview]="previewRate" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotals]="dueByPeriod()" (saved)="periodSaved($event)" (replaced)="periods.set($event)" />
    <app-summary [people]="summaryPeople()" [claims]="claims()" [periods]="periods()" [dueTotals]="dueByPeriod()" />
    <app-classic-summary [periods]="periods()" [dueTotals]="classicDueByPeriod()" [dueTotal]="classicDueTotal()" />
    <app-pools [people]="people()" [crews]="crews()" (cutTotals)="cutTotals.set($event)" />
    <app-crews [people]="people()" [dueTotals]="dueByPerson()" [(crews)]="crews" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editingId() === null ? 'Add person' : 'Edit person'">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" autocomplete="off" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="person-form-terms">Terms (business days)</label>
        <input id="person-form-terms" data-testid="person-form-terms" formControlName="terms" inputmode="numeric" autocomplete="off" />
        <label for="person-form-contact">Contact</label>
        <input id="person-form-contact" data-testid="person-form-contact" formControlName="contact" autocomplete="off" />
        <label for="person-form-tags">Tags (comma separated)</label>
        <input id="person-form-tags" data-testid="person-form-tags" formControlName="tags" autocomplete="off" />
        @if (editingId() !== null) {
          <label for="person-form-grade-from">Grade from</label>
          <input id="person-form-grade-from" type="date" data-testid="person-form-grade-from" formControlName="gradeFrom" />
          <label for="person-form-terms-from">Terms from</label>
          <input id="person-form-terms-from" type="date" data-testid="person-form-terms-from" formControlName="termsFrom" />
        }
        <button type="submit" data-testid="person-form-submit">{{ editingId() === null ? 'Add' : 'Save' }}</button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>
      <p>
        <button type="button" data-testid="plan-generate" (click)="generatePlans()">Generate plan claims</button>
        @if (planGenerated() !== null) {
          <span role="status">Made <strong data-testid="plan-generated-count">{{ planGenerated() }}</strong></span>
        }
        @if (planGenerateErr(); as err) {
          <span role="alert" data-testid="plan-generate-error">{{ err }}</span>
        }
      </p>
      <form [formGroup]="quickForm" (ngSubmit)="quickAdd()" aria-label="Quick add claim">
        <label for="quick-name">Name</label>
        <input id="quick-name" data-testid="quick-name" formControlName="name" autocomplete="off" />
        <label for="quick-amount">Amount</label>
        <input id="quick-amount" data-testid="quick-amount" formControlName="amount" inputmode="decimal" autocomplete="off" />
        <button type="submit" data-testid="quick-submit">Quick add</button>
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="pasteForm" (ngSubmit)="paste()" aria-label="Paste claims">
        <label for="paste-claims">Paste claims (name,amount,date,note per line)</label>
        <textarea id="paste-claims" data-testid="paste-claims" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="paste-submit">Add and open pasted claims</button>
        @if (pasted() !== null) {
          <p role="status">Lines taken <strong data-testid="paste-added">{{ pasted() }}</strong></p>
        }
        @if (pasteErr(); as err) {
          <p role="alert" data-testid="paste-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="importForm" (ngSubmit)="importPeople()" aria-label="Import people">
        <label for="import-people">Import people (name,grade,terms,contact per line)</label>
        <textarea id="import-people" data-testid="import-people" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="import-submit">Import people</button>
        @if (imported(); as result) {
          <p role="status">Added <strong data-testid="import-added">{{ result.added }}</strong>,
            updated <strong data-testid="import-updated">{{ result.updated }}</strong></p>
        }
        @if (importErr(); as err) {
          <p role="alert" data-testid="import-error">{{ err }}</p>
        }
      </form>
      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() | figure }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotal() | figure }}</strong></p>
      <p>Overdue <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong>
        totalling <strong data-testid="rollup-overdue-total">{{ overdueTotal() | figure }}</strong></p>
      <p>Settled total <strong data-testid="rollup-settled-total">{{ settledTotal() | figure }}</strong>
        open total <strong data-testid="rollup-open-total">{{ openTotal() | figure }}</strong></p>
      <p>Cut total <strong data-testid="rollup-cut-total">{{ money(cutTotal()) | figure }}</strong></p>
      <p>Aged due 0–30 <strong data-testid="rollup-aged-0-30">{{ money(agedTotal().upTo30) | figure }}</strong>
        31–60 <strong data-testid="rollup-aged-31-60">{{ money(agedTotal().upTo60) | figure }}</strong>
        61+ <strong data-testid="rollup-aged-61">{{ money(agedTotal().over60) | figure }}</strong></p>
      @if (tagTotals().length > 0) {
        <table aria-label="Due total by tag">
          <thead><tr><th scope="col">Tag</th><th scope="col">Due total</th></tr></thead>
          <tbody>
            @for (t of tagTotals(); track t.tag) {
              <tr><th scope="row">{{ t.tag }}</th><td [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ money(t.cents) | figure }}</td></tr>
            }
          </tbody>
        </table>
      }
      @if (monthTotals().length > 0) {
        <table aria-label="Due total by month">
          <thead><tr><th scope="col">Month</th><th scope="col">Due total</th></tr></thead>
          <tbody>
            @for (m of monthTotals(); track m.month) {
              <tr><th scope="row">{{ m.month }}</th><td [attr.data-testid]="'month-' + m.month + '-due-total'">{{ money(m.cents) | figure }}</td></tr>
            }
          </tbody>
        </table>
      }
      <label for="snapshot-from">Compare snapshots from</label>
      <input #snapshotFrom id="snapshot-from" type="date" data-testid="snapshot-from" />
      <label for="snapshot-to">to</label>
      <input #snapshotTo id="snapshot-to" type="date" data-testid="snapshot-to" />
      <button type="button" data-testid="snapshot-compare"
        (click)="compareSnapshots(snapshotFrom.value, snapshotTo.value)">Compare snapshots</button>
      @if (snapshotRange()) {
        <ul aria-label="Snapshot comparison">
          @for (l of snapshotLines(); track $index) {
            <li data-testid="snapshot-line">{{ l }}</li>
          }
        </ul>
      }
      <button type="button" data-testid="dups-show" (click)="dupsShown.set(true)">Show duplicate claims</button>
      @if (dupsShown()) {
        <ul aria-label="Duplicate claims">
          @for (d of dups(); track d) {
            <li data-testid="dup-line">{{ d }}</li>
          }
        </ul>
      }
      <label>
        <input type="checkbox" data-testid="show-retired" [checked]="session.showRetired()"
          (change)="session.showRetired.set($any($event.target).checked)" />
        Show retired
      </label>
      <label for="sort-by">Sort by</label>
      <select id="sort-by" data-testid="sort-by" [value]="session.sortBy()"
        (change)="session.sortBy.set($any($event.target).value)">
        <option value="added">Added</option>
        <option value="name">Name</option>
        <option value="grade">Grade</option>
        <option value="due-total">Due total</option>
      </select>
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
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <button type="button" data-testid="export-claims" (click)="exportClaims()">Export claims (CSV)</button>
      <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
      @if (bulkResult(); as r) {
        <p role="status">Opened <strong data-testid="bulk-opened">{{ r.opened }}</strong>,
          refused <strong data-testid="bulk-refused">{{ r.refused }}</strong></p>
      }
      @if (bulkError(); as err) {
        <p role="alert" data-testid="bulk-error">{{ err }}</p>
      }
      <button type="button" data-testid="bulk-settle" (click)="bulkSettle()">Settle selected claims</button>
      <span>Last batch <span data-testid="bulk-settle-batch">{{ lastBatch() ?? '' }}</span></span>
      @if (bulkSettleResult(); as r) {
        <p role="status">Settled <strong data-testid="bulk-settled">{{ r.settled }}</strong>,
          refused <strong data-testid="bulk-settle-refused">{{ r.refused }}</strong></p>
      }
      @if (bulkSettleError(); as err) {
        <p role="alert" data-testid="bulk-settle-error">{{ err }}</p>
      }
      <label for="bulk-reassign-to">Reassign selected claims to</label>
      <select #bulkReassignTo id="bulk-reassign-to" data-testid="bulk-reassign-to" (change)="bulkReassignError.set(null)">
        @for (p of people(); track p.id) {
          <option [value]="p.id">{{ p.name }}</option>
        }
      </select>
      <button type="button" data-testid="bulk-reassign" (click)="bulkReassign(bulkReassignTo.value)">Reassign selected claims</button>
      @if (bulkReassignResult(); as r) {
        <p role="status">Reassigned <strong data-testid="bulk-reassigned">{{ r.reassigned }}</strong>,
          refused <strong data-testid="bulk-reassign-refused">{{ r.refused }}</strong></p>
      }
      @if (bulkReassignError(); as err) {
        <p role="alert" data-testid="bulk-reassign-error">{{ err }}</p>
      }
      <label for="bulk-redate-date">Re-date selected claims to</label>
      <input #bulkRedateDate id="bulk-redate-date" type="date" data-testid="bulk-redate-date" (input)="bulkRedateError.set(null)" />
      <button type="button" data-testid="bulk-redate" (click)="bulkRedate(bulkRedateDate.value)">Re-date selected claims</button>
      @if (bulkRedateResult(); as r) {
        <p role="status">Re-dated <strong data-testid="bulk-redated">{{ r.redated }}</strong>,
          refused <strong data-testid="bulk-redate-refused">{{ r.refused }}</strong></p>
      }
      @if (bulkRedateError(); as err) {
        <p role="alert" data-testid="bulk-redate-error">{{ err }}</p>
      }
      @if (session.voidReasons().length > 0) {
        <label for="bulk-void-reason">Void reason for selected claims</label>
        <select id="bulk-void-reason" data-testid="bulk-void-reason" [value]="bulkVoidReason()"
          (change)="bulkVoidReason.set($any($event.target).value); bulkVoidError.set(null)">
          <option value="">Choose a reason</option>
          @for (r of session.voidReasons(); track r) {
            <option [value]="r">{{ r }}</option>
          }
        </select>
      }
      <button type="button" data-testid="bulk-void" (click)="bulkVoid()">Void selected claims</button>
      @if (bulkVoidResult(); as r) {
        <p role="status">Voided <strong data-testid="bulk-voided">{{ r.voided }}</strong>,
          refused <strong data-testid="bulk-void-refused">{{ r.refused }}</strong></p>
      }
      @if (bulkVoidError(); as err) {
        <p role="alert" data-testid="bulk-void-error">{{ err }}</p>
      }
      <form [formGroup]="bulkGradeForm" (ngSubmit)="bulkGrade()" aria-label="Grade selected people">
        <label for="bulk-grade">Grade selected people</label>
        <select id="bulk-grade" data-testid="bulk-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">From</label>
        <input id="bulk-grade-from" type="date" data-testid="bulk-grade-from" formControlName="from" />
        <button type="submit" data-testid="bulk-grade-apply">Grade selected</button>
        @if (bulkGraded() !== null) {
          <p role="status">Changed <strong data-testid="bulk-graded">{{ bulkGraded() }}</strong></p>
        }
        @if (bulkGradeErr(); as err) {
          <p role="alert" data-testid="person-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="bulkRetireForm" (ngSubmit)="bulkRetire()" aria-label="Retire selected people">
        <label for="bulk-retire-date">Retire selected people from</label>
        <input id="bulk-retire-date" type="date" data-testid="bulk-retire-date" formControlName="from" />
        <button type="submit" data-testid="bulk-retire">Retire selected</button>
        @if (bulkRetired() !== null) {
          <p role="status">Retired <strong data-testid="bulk-retired">{{ bulkRetired() }}</strong></p>
        }
        @if (bulkRetireErr(); as err) {
          <p role="alert" data-testid="person-error">{{ err }}</p>
        }
      </form>
      <table data-testid="people-list">
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">Name</th><th scope="col">From sheet</th>
            @if (shows('aliases')) { <th scope="col">Aliases</th> }
            <th scope="col">Add alias</th><th scope="col">Clashing</th><th scope="col">Grade</th>
            @if (shows('terms')) { <th scope="col">Terms</th> }
            @if (shows('contact')) { <th scope="col">Contact</th> }
            <th scope="col">Tags</th>
            @if (shows('claimed')) { <th scope="col">Claimed</th> }
            @if (shows('due-total')) { <th scope="col">Due</th> }
            <th scope="col">Cut</th><th scope="col">By period</th>
            @if (shows('overdue-count')) { <th scope="col">Overdue</th> }
            @if (shows('overdue-total')) { <th scope="col">Overdue total</th> }
            <th scope="col">Score</th>
            <th scope="col">Band</th>
            <th scope="col">Aged</th>
            @if (shows('statement-count')) { <th scope="col">Statements</th> }
            @if (shows('stated-total')) { <th scope="col">Last stated</th> }
            @if (shows('statement-date')) { <th scope="col">Stated on</th> }
            <th scope="col">Statement lines</th>
            <th scope="col">Take statement</th><th scope="col">Snapshots</th>
            @if (shows('retired')) { <th scope="col">Retired</th> }
            <th scope="col">Retirement</th><th scope="col">Active</th>
            @if (shows('hold')) { <th scope="col">Hold</th> }
            <th scope="col">Hold from</th><th scope="col">Flag</th><th scope="col">Approver</th><th scope="col">Watched</th>
            @if (shows('patrons')) { <th scope="col">Patrons</th> }
            @if (shows('backing')) { <th scope="col">Backing</th> }
            <th scope="col">Add patron</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of shownPeople(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td>
                <input type="checkbox" data-testid="person-select" [checked]="selectedPeople().has(p.id)"
                  (change)="personToggled(p.id, $any($event.target).checked)" [attr.aria-label]="'Select ' + p.name" />
              </td>
              <td data-testid="person-name">{{ p.name }} ({{ gradeOf(p) }})</td>
              <td data-testid="person-origin">{{ p.origin ?? '' }}</td>
              @if (shows('aliases')) {
                <td data-testid="person-aliases">{{ p.aliases.join(', ') }}</td>
              }
              <td>
                <label [for]="'person-alias-input-' + p.id" class="visually-hidden">Alias for {{ p.name }}</label>
                <input [id]="'person-alias-input-' + p.id" data-testid="person-alias-input" #aliasInput autocomplete="off"
                  (input)="clearRowError(p.id)" />
                <button type="button" data-testid="person-alias-add" (click)="addAlias(p, aliasInput)"
                  [attr.aria-label]="'Add alias for ' + p.name">Add alias</button>
              </td>
              <td data-testid="person-clashing">{{ p.clashing ? 'yes' : 'no' }}</td>
              <td>
                <span data-testid="person-grade">{{ gradeOf(p) }}</span>
                <label [for]="'person-inline-grade-' + p.id" class="visually-hidden">Grade for {{ p.name }}</label>
                <select [id]="'person-inline-grade-' + p.id" data-testid="person-inline-grade" #inlineGrade
                  (change)="clearRowError(p.id)">
                  @for (g of grades; track g) {
                    <option [value]="g" [selected]="g === gradeOf(p)">{{ g }}</option>
                  }
                </select>
                <button type="button" data-testid="person-inline-grade-save" (click)="saveGrade(p, inlineGrade.value)"
                  [attr.aria-label]="'Save grade of ' + p.name">Save grade</button>
              </td>
              @if (shows('terms')) {
                <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              }
              @if (shows('contact')) {
                <td data-testid="person-contact">{{ p.contact ?? '' }}</td>
              }
              <td data-testid="person-tags">{{ personTags(p).join(', ') }}</td>
              @if (shows('claimed')) {
                <td data-testid="person-claimed">{{ claimedBy(p.id) | figure }}</td>
              }
              @if (shows('due-total')) {
                <td>
                  <span data-testid="person-due-total">{{ dueOf(p) | figure }}</span>
                  <span>excess <span data-testid="person-excess">{{ money(excessOf(p.id)) | figure }}</span></span>
                  <span>settled <span data-testid="person-settled-total">{{ money(settledOpenOf(p.id).settled) | figure }}</span></span>
                  <span>open <span data-testid="person-open-total">{{ money(settledOpenOf(p.id).open) | figure }}</span></span>
                </td>
              }
              <td data-testid="person-cut-total">{{ (money(cutTotals().get(p.id) ?? 0)) | figure }}</td>
              <td>
                @for (period of periods(); track period.id) {
                  <span>{{ period.name }} <span [attr.data-testid]="'person-period-' + period.name + '-due-total'">{{ money(periodDueOf(p.id, period)) | figure }}</span>
                    carried <span [attr.data-testid]="'person-period-' + period.name + '-carried'">{{ money(carriedOf(p.id, period)) | figure }}</span></span>
                }
              </td>
              @if (shows('overdue-count')) {
                <td data-testid="person-overdue-count">{{ overdueOf(p.id).count }}</td>
              }
              @if (shows('overdue-total')) {
                <td data-testid="person-overdue-total">{{ money(overdueOf(p.id).cents) | figure }}</td>
              }
              <td data-testid="person-score">{{ scoreOf(p.id) }}</td>
              <td data-testid="person-band">{{ bandOf(p.id) }}</td>
              <td>
                <span>0–30 <span data-testid="person-aged-0-30">{{ money(agedOf(p.id).upTo30) | figure }}</span></span>
                <span>31–60 <span data-testid="person-aged-31-60">{{ money(agedOf(p.id).upTo60) | figure }}</span></span>
                <span>61+ <span data-testid="person-aged-61">{{ money(agedOf(p.id).over60) | figure }}</span></span>
              </td>
              @if (shows('statement-count')) {
                <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              }
              @if (shows('stated-total')) {
                <td data-testid="person-stated-total">{{ statedTotalOf(p) | figure }}</td>
              }
              @if (shows('statement-date')) {
                <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              }
              <td>
                <ul aria-label="Statement lines">
                  @for (st of p.statements; track $index) {
                    <li data-testid="person-statement-line-line">{{ st.date }} {{ st.dueTotal ?? 'n/a' }}</li>
                  }
                </ul>
              </td>
              <td>
                <label [for]="'person-statement-asof-' + p.id" class="visually-hidden">Statement of {{ p.name }} as of</label>
                <input [id]="'person-statement-asof-' + p.id" type="date" data-testid="person-statement-asof" #statementAsOf
                  (input)="clearRowError(p.id)" />
                <button type="button" data-testid="person-statement" (click)="statement(p, statementAsOf)"
                  [attr.aria-label]="'Take statement of ' + p.name">Statement</button>
                <button type="button" data-testid="person-unstate" (click)="cancelStatement(p)"
                  [attr.aria-label]="'Cancel last statement of ' + p.name">Cancel statement</button>
                <button type="button" data-testid="person-export-statement" (click)="exportStatement(p)"
                  [attr.aria-label]="'Download statement of ' + p.name + ' (CSV)'">Statement (CSV)</button>
              </td>
              <td>
                <ul aria-label="Snapshots">
                  @for (sn of p.snapshots; track $index) {
                    <li data-testid="person-snapshot-line">{{ sn.day }} {{ sn.dueTotal ?? 'n/a' }}</li>
                  }
                </ul>
              </td>
              @if (shows('retired')) {
                <td>
                  <span data-testid="person-retired">{{ p.retiredFrom === null ? 'no' : 'yes' }}</span>
                  <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
                </td>
              }
              <td>
                <label [for]="'person-retire-date-' + p.id" class="visually-hidden">Retire {{ p.name }} from</label>
                <input [id]="'person-retire-date-' + p.id" type="date" data-testid="person-retire-date" #retireDate
                  (input)="clearRowError(p.id)" />
                <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)"
                  [attr.aria-label]="'Retire ' + p.name">Retire</button>
                <button type="button" data-testid="person-unretire" (click)="unretire(p)"
                  [attr.aria-label]="'Unretire ' + p.name">Unretire</button>
                <ul aria-label="Past retirements">
                  @for (r of p.retirements; track $index) {
                    <li data-testid="person-retirement-line">{{ r.from }} to {{ r.to }}</li>
                  }
                </ul>
              </td>
              <td data-testid="person-active">{{ activeIds().has(p.id) ? 'yes' : 'no' }}</td>
              @if (shows('hold')) {
                <td>
                  <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                  <ul aria-label="Holds">
                    @for (h of p.holds; track $index) {
                      <li data-testid="person-hold-line">{{ h.from }} to {{ h.to ?? 'open' }}</li>
                    }
                  </ul>
                </td>
              }
              <td>
                <label [for]="'person-hold-from-' + p.id" class="visually-hidden">Hold {{ p.name }} from</label>
                <input [id]="'person-hold-from-' + p.id" type="date" data-testid="person-hold-from" #holdFrom
                  (input)="clearRowError(p.id)" />
                <label [for]="'person-hold-to-' + p.id" class="visually-hidden">Hold {{ p.name }} to</label>
                <input [id]="'person-hold-to-' + p.id" type="date" data-testid="person-hold-to" #holdTo
                  (input)="clearRowError(p.id)" />
                <button type="button" data-testid="person-hold-toggle" (click)="hold(p, holdFrom.value, holdTo.value)"
                  [attr.aria-label]="'Hold ' + p.name">Hold</button>
              </td>
              <td>
                <span data-testid="person-flag-shown">{{ p.flag ?? '' }}</span>
                <label [for]="'person-flag-reason-' + p.id" class="visually-hidden">Flag {{ p.name }} for</label>
                <input [id]="'person-flag-reason-' + p.id" type="text" data-testid="person-flag-reason" #flagReason
                  maxlength="200" (input)="clearRowError(p.id)" />
                <label [for]="'person-flag-until-' + p.id" class="visually-hidden">Flag {{ p.name }} until</label>
                <input [id]="'person-flag-until-' + p.id" type="date" data-testid="person-flag-until" #flagUntil
                  (input)="clearRowError(p.id)" />
                <button type="button" data-testid="person-flag" (click)="flag(p, flagReason.value, flagUntil.value)"
                  [attr.aria-label]="'Flag ' + p.name">Flag</button>
              </td>
              <td>
                <span data-testid="person-approver">{{ p.approver ?? '' }}</span>
                <label [for]="'person-approver-input-' + p.id" class="visually-hidden">Approver for {{ p.name }}</label>
                <input [id]="'person-approver-input-' + p.id" data-testid="person-approver-input" #approverInput
                  autocomplete="off" maxlength="100" (input)="clearRowError(p.id)" />
                <button type="button" data-testid="person-approver-save" (click)="setApprover(p, approverInput.value)"
                  [attr.aria-label]="'Set approver for ' + p.name">Set approver</button>
              </td>
              <td>
                <span data-testid="person-watched">{{ p.watched ? 'yes' : 'no' }}</span>
                <button type="button" data-testid="person-watch" (click)="watch(p)" [attr.aria-pressed]="p.watched"
                  [attr.aria-label]="'Watch ' + p.name">{{ p.watched ? 'Unwatch' : 'Watch' }}</button>
              </td>
              @if (shows('patrons')) {
                <td data-testid="person-patrons">{{ patronNamesOf(p) }}</td>
              }
              @if (shows('backing')) {
                <td data-testid="person-backing">{{ backingOf(p) | figure }}</td>
              }
              <td>
                <label [for]="'person-patron-select-' + p.id" class="visually-hidden">Patron for {{ p.name }}</label>
                <select [id]="'person-patron-select-' + p.id" data-testid="person-patron-select" #patronSelect
                  (change)="clearRowError(p.id)">
                  <option value="">Choose a patron</option>
                  @for (o of patronChoicesFor(p); track o.id) {
                    <option [value]="o.id">{{ o.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-patron-add" (click)="addPatron(p, patronSelect.value)"
                  [attr.aria-label]="'Add patron for ' + p.name">Add patron</button>
                @if (patronErrors()[p.id]; as err) {
                  <p role="alert" data-testid="person-patron-error">{{ err }}</p>
                }
              </td>
              <td>
                <app-person-plans [personId]="p.id" [personName]="p.name" (claimSaved)="claimSaved($event)" />
                <app-person-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [rates]="rateLevels()" [grades]="p.grades" [others]="patronChoicesFor(p)" [periods]="periods()"
                  [selected]="selectedClaims()" (toggled)="claimToggled($event)" (saved)="claimSaved($event)" />
              </td>
              <td>
                <button type="button" data-testid="person-move-up" (click)="move(p, 'up')"
                  [attr.aria-label]="'Move ' + p.name + ' up'">Up</button>
                <button type="button" data-testid="person-move-down" (click)="move(p, 'down')"
                  [attr.aria-label]="'Move ' + p.name + ' down'">Down</button>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
                  [attr.aria-label]="'Edit ' + p.name">Edit</button>
                <label [for]="'person-merge-into-' + p.id" class="visually-hidden">Merge {{ p.name }} into</label>
                <select [id]="'person-merge-into-' + p.id" data-testid="person-merge-into" #mergeInto
                  (change)="clearRowError(p.id)">
                  <option value="">Merge into</option>
                  @for (o of patronChoicesFor(p); track o.id) {
                    <option [value]="o.id">{{ o.name }}</option>
                  }
                </select>
                <button type="button" data-testid="person-merge" (click)="merge(p, mergeInto.value)"
                  [attr.aria-label]="'Merge ' + p.name">Merge</button>
                <label [for]="'person-transfer-to-' + p.id" class="visually-hidden">Transfer {{ p.name }} to sheet</label>
                <input [id]="'person-transfer-to-' + p.id" data-testid="person-transfer-to" #transferTo
                  placeholder="Sheet id" (input)="clearRowError(p.id)" />
                <button type="button" data-testid="person-transfer" (click)="transfer(p, transferTo.value)"
                  [attr.aria-label]="'Transfer ' + p.name">Transfer</button>
                <button type="button" data-testid="person-transfer-undo" (click)="undoTransfer(p)"
                  [attr.aria-label]="'Undo the transfer of ' + p.name">Undo transfer</button>
                @if (rowErrors()[p.id]; as err) {
                  <p role="alert" data-testid="person-error">{{ err }}</p>
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
  protected readonly session = inject(Session);
  private readonly api = inject(PeopleApi);
  private readonly claimsApi = inject(ClaimsApi);
  private readonly plansApi = inject(PlansApi);
  private readonly ratesApi = inject(RatesApi);
  private readonly periodsApi = inject(PeriodsApi);
  private readonly document = inject(DOCUMENT);

  protected readonly grades = GRADES;
  protected readonly columns = PEOPLE_COLUMNS;
  protected readonly people = signal<Person[]>([]);
  /** The people the list shows: retired ones (any with a retirement date) only with "show retired" on; for a
   *  restricted user, only the person who goes by their name and the people of the crews that person leads or
   *  belongs to. */
  protected readonly shownPeople = computed(() => {
    const visible = this.visibleIds();
    const listed = visible ? this.people().filter((p) => visible.has(p.id)) : this.people();
    const shown = this.session.showRetired() ? listed : listed.filter((p) => p.retiredFrom === null);
    return this.sorted(shown);
  });
  /** The ids of the people a restricted user may see on the list; null when the user is not restricted. */
  private readonly visibleIds = computed<Set<number> | null>(() => {
    if (this.myRole() !== 'restricted') return null;
    const me = this.me();
    const ids = new Set(this.addedHere());
    if (!me) return ids;
    ids.add(me.id);
    for (const c of this.crews()) {
      const people = crewIds(c);
      if (people.includes(me.id)) people.forEach((id) => ids.add(id));
    }
    return ids;
  });
  /** The people this screen's quick-add bar added since the sheet was loaded: a restricted user sees
   *  them on the list until the sheet is loaded again. */
  private readonly addedHere = signal<ReadonlySet<number>>(new Set());
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  /** The retirement date of each person with one, by person id: from the sheet's today on or after it, their
   *  open (OPEN or PENDING) claims count in no live total unless bound by a statement. */
  private readonly retiredFromById = computed(() =>
    new Map(this.people().filter((p) => p.retiredFrom !== null).map((p) => [p.id, p.retiredFrom as string])));
  /** Whether an unbound open claim is left out of the live totals: its person's retirement date reached on
   *  the sheet's today. A hold leaves nothing out; it only takes the person out of pool cuts. */
  private readonly leftOut = computed(() => {
    const retired = this.retiredFromById();
    const today = this.session.today();
    return (c: Claim) => {
      const from = retired.get(c.personId);
      return (from !== undefined && today !== null && from <= today);
    };
  });
  /** Whether a claim counts toward claimed: as it counts in the live totals. */
  private readonly countsClaimed = computed(() => {
    const retired = this.retiredFromById();
    const today = this.session.today();
    return (c: Claim) => {
      const from = retired.get(c.personId);
      const retiredNow = from !== undefined && today !== null && from <= today;
      return counts(c, today) && !(isOpen(c) && !isBound(c) && retiredNow);
    };
  });
  /** Whether a claim counts in the live totals: open (OPEN or PENDING) and SETTLED ones, but not a deferred one,
   *  nor an open one dated within a hold of its person, or of a person whose retirement date is reached,
   *  unless bound by a statement. */
  private readonly countsLive = computed(() => {
    const leftOut = this.leftOut();
    const today = this.session.today();
    return (c: Claim) => counts(c, today) && !(isOpen(c) && !isBound(c) && leftOut(c));
  });
  /** The ids of the people who are active: with an open (OPEN or PENDING) claim that counts in the
   *  live totals, dated within the 30 days up to and including the sheet's today. */
  protected readonly activeIds = computed(() => {
    const today = this.session.today();
    const out = new Set<number>();
    if (today === null) return out;
    const since = plusDays(today, -29);
    const live = this.countsLive();
    for (const c of this.claims()) {
      if (isOpen(c) && live(c) && since <= c.date && c.date <= today) out.add(c.personId);
    }
    return out;
  });
  /** Each person's claims (newest first) and claimed in cents (counting claims only, corrections left out), by person id. */
  private readonly byPerson = computed(() => {
    const out = new Map<number, { claims: Claim[]; cents: number }>();
    const live = this.countsClaimed();
    for (const c of this.claims()) {
      const entry = out.get(c.personId) ?? { claims: [], cents: 0 };
      entry.claims.push(c);
      if (live(c) && !c.corrects) entry.cents += cents(c.amount);
      out.set(c.personId, entry);
    }
    return out;
  });
  /** The sheet's rates (a percent with two decimals), each for a grade from a from-date. */
  protected readonly rates = signal<Rate[]>([]);
  /** The rates of each of the sheet's ancestors, its parent's first. */
  private readonly inherited = signal<Rate[][]>([]);
  /** The sheet's own rates and those it inherits, as the rates panel and each claim apply them. */
  protected readonly rateLevels = computed(() => new RateLevels(this.rates(), this.inherited()));
  /** The rates in force on the sheet: its own, else (for a grade and date with none) its ancestors'. */
  protected readonly effectiveRates = computed(() => this.rateLevels().inForce);
  /** What every late charge follows: the sheet's today and late percent. */
  private readonly lateness = computed<Lateness>(() => ({
    today: this.session.today(), latePctOn: (d) => this.session.latePctOn(d),
  }));
  /** Each person's due-total in cents, by person id: the sum of the dues their OPEN and SETTLED
   *  claims show (each already rounded; open ones within a hold, unbound, left out). Null (n/a) when their grade in force on the
   *  sheet's today has no rate then, unless a claim shows a due (a settled claim's fixed one, a stated claim's bound one,
   *  or one at the rate of the grade they had on its date): then the sum of the dues there are. */
  private readonly uncappedDueByPerson = computed(() => {
    const out = new Map<number, number | null>();
    const late = this.lateness();
    const shared = this.sharedParts();
    for (const p of this.people()) {
      const dues = this.claimsOf(p.id).filter(this.countsLive())
        .map((c) => this.claimDueCents(p, c, late))
        .filter((d): d is number => d !== null);
      dues.push(...(shared.get(p.id) ?? []).map((part) => part.cents));
      if (pctOn(this.ratesOf(this.gradeOf(p)), this.session.today()) === null && dues.length === 0) {
        out.set(p.id, null);
        continue;
      }
      out.set(p.id, dues.reduce((sum, d) => sum + d, 0));
    }
    return out;
  });
  /** The parts of shared claims' dues in cents moving into (or, negative, out of) each person's
   *  figures, by person id: for each counting claim with shares (its due not n/a), each holder's part
   *  (the due times their share over 100, rounded half to even), and the same parts taken off the
   *  claim's own person; each with whether the claim is SETTLED. */
  private readonly sharedParts = computed(() => {
    const out = new Map<number, { cents: number; settled: boolean }[]>();
    const late = this.lateness();
    const onSheet = new Set(this.people().map((p) => p.id));
    const add = (id: number, part: { cents: number; settled: boolean }) => out.set(id, [...(out.get(id) ?? []), part]);
    for (const p of this.people()) {
      for (const c of this.claimsOf(p.id).filter(this.countsLive())) {
        if (!c.shares?.length) continue;
        const due = this.claimDueCents(p, c, late);
        if (due === null) continue;
        const settled = c.status === 'SETTLED';
        for (const s of c.shares) {
          if (!onSheet.has(s.personId)) continue;
          const part = sharePart(due, cents(s.share));
          add(s.personId, { cents: part, settled });
          add(p.id, { cents: -part, settled });
        }
      }
    }
    return out;
  });
  /** Each person's due-total in cents, by person id, held to the sheet's cap less their cut total
   *  (not below 0.00): that when their uncapped one is above it (n/a is not capped). */
  protected readonly dueByPerson = computed(() => {
    const cap = this.session.cap();
    const capCents = cap === null ? null : cents(cap);
    const cuts = this.cutTotals();
    const out = new Map<number, number | null>();
    for (const [id, due] of this.uncappedDueByPerson()) {
      const held = capCents === null ? null : Math.max(capCents - (cuts.get(id) ?? 0), 0);
      out.set(id, due === null || held === null ? due : Math.min(due, held));
    }
    return out;
  });
  /** Each person's uncapped due-total split in cents, by person id: the dues (n/a ones skipped) their
   *  counting SETTLED claims show (at their fixed figures) and those their counting open (OPEN and
   *  PENDING) claims show; and the settled part left from the score (those that correct nothing). */
  private readonly settledOpenByPerson = computed(() => {
    const out = new Map<number, { settled: number; open: number; scored: number }>();
    const late = this.lateness();
    for (const p of this.people()) {
      const split = { settled: 0, open: 0, scored: 0 };
      for (const c of this.claimsOf(p.id).filter(this.countsLive())) {
        const due = this.claimDueCents(p, c, late) ?? 0;
        if (c.status === 'SETTLED') {
          split.settled += due;
          if (!c.corrects) split.scored += due;
        } else if (isOpen(c)) split.open += due;
      }
      for (const part of this.sharedParts().get(p.id) ?? []) {
        if (part.settled) split.settled += part.cents;
        else split.open += part.cents;
      }
      out.set(p.id, split);
    }
    return out;
  });
  protected settledOpenOf(personId: number): { settled: number; open: number; scored: number } {
    return this.settledOpenByPerson().get(personId) ?? { settled: 0, open: 0, scored: 0 };
  }
  /** Everyone's settled-total added up (not trimmed). */
  protected readonly settledTotal = computed(() =>
    money([...this.settledOpenByPerson().values()].reduce((sum, s) => sum + s.settled, 0)));
  /** Everyone's open-total added up (not trimmed). */
  protected readonly openTotal = computed(() =>
    money([...this.settledOpenByPerson().values()].reduce((sum, s) => sum + s.open, 0)));
  /** Each person's cut total in cents, by person id, as the pools show their cuts. */
  protected readonly cutTotals = signal<Map<number, number>>(new Map());
  /** Everyone's cut total added up. */
  protected readonly cutTotal = computed(() => [...this.cutTotals().values()].reduce((sum, c) => sum + c, 0));
  /** The person's excess in cents: how far their uncapped due-total is above their capped one, the
   *  cap less their cut total (0 otherwise). */
  protected excessOf(personId: number): number {
    return (this.uncappedDueByPerson().get(personId) ?? 0) - (this.dueByPerson().get(personId) ?? 0);
  }
  /** Each person's overdue claims, by person id: how many of their counting claims are overdue
   *  (OPEN with the due-by before the sheet's today), and the dues they show in cents (n/a ones skipped). */
  private readonly overdueByPerson = computed(() => {
    const out = new Map<number, { count: number; cents: number }>();
    const late = this.lateness();
    for (const p of this.people()) {
      const overdueClaims = this.claimsOf(p.id).filter(this.countsLive()).filter((c) => overdue(c, late.today, this.graceOf(p, c)));
      out.set(p.id, {
        count: overdueClaims.length,
        cents: overdueClaims.reduce((sum, c) => sum + (this.claimDueCents(p, c, late) ?? 0), 0),
      });
    }
    return out;
  });
  /** The person's score: their settled figures (the dues of their counting SETTLED claims that
   *  correct nothing) over 100,
   *  less 5 for each of their counting overdue claims, rounded half to even to a whole number. */
  protected scoreOf(personId: number): number {
    const x = this.settledOpenOf(personId).scored - 50000 * this.overdueOf(personId).count;
    const q = Math.floor(x / 10000);
    const r = x - q * 10000;
    return r > 5000 || (r === 5000 && q % 2 !== 0) ? q + 1 : q;
  }
  /** The person's band while both score thresholds are set: HIGH when their score is at or above
   *  the high one, MID when at or above the mid one, else LOW; blank while either is blank. */
  protected bandOf(personId: number): string {
    const mid = this.session.scoreMid();
    const high = this.session.scoreHigh();
    if (mid === null || high === null) return '';
    const score = this.scoreOf(personId);
    return score >= high ? 'HIGH' : score >= mid ? 'MID' : 'LOW';
  }
  /** Each person's open (OPEN and PENDING) counting claims' dues in cents (n/a ones skipped), by person id,
   *  split by the claim's age on the sheet's today (days from its date): 0 to 30, 31 to 60, 61 or more;
   *  one dated after today counts as 0 to 30. */
  private readonly agedByPerson = computed(() => {
    const out = new Map<number, Aged>();
    const late = this.lateness();
    const today = late.today;
    const from31 = today === null ? null : plusDays(today, -31);
    const from61 = today === null ? null : plusDays(today, -61);
    for (const p of this.people()) {
      const aged: Aged = { upTo30: 0, upTo60: 0, over60: 0 };
      for (const c of this.claimsOf(p.id).filter(this.countsLive()).filter(isOpen)) {
        const due = this.claimDueCents(p, c, late) ?? 0;
        if (from61 !== null && c.date <= from61) aged.over60 += due;
        else if (from31 !== null && c.date <= from31) aged.upTo60 += due;
        else aged.upTo30 += due;
      }
      out.set(p.id, aged);
    }
    return out;
  });
  /** Everyone's age buckets added up (not trimmed), in cents. */
  protected readonly agedTotal = computed<Aged>(() => {
    const total: Aged = { upTo30: 0, upTo60: 0, over60: 0 };
    for (const a of this.agedByPerson().values()) {
      total.upTo30 += a.upTo30;
      total.upTo60 += a.upTo60;
      total.over60 += a.over60;
    }
    return total;
  });
  protected agedOf(personId: number): Aged {
    return this.agedByPerson().get(personId) ?? { upTo30: 0, upTo60: 0, over60: 0 };
  }
  /** The role the signed-in user acts in on the sheet (as it stood then, under an as-of date), as last read. */
  private readonly myRole = signal<string | null>(null);
  /** The steward's dashboard: the PENDING and REVIEW claims and the overdue claims that count,
   *  over the sheet and all its descendants; null unless the signed-in user is a steward. */
  protected readonly dashboard = computed<Dashboard | null>(() => {
    if (this.myRole() !== 'steward') return null;
    const claims = this.claims();
    const d = this.descendants();
    return {
      pending: claims.filter((c) => c.status === 'PENDING').length + (d.statusCounts['PENDING'] ?? 0),
      review: claims.filter((c) => c.status === 'REVIEW').length + (d.statusCounts['REVIEW'] ?? 0),
      overdue: this.overdueCount() + d.overdueCount,
    };
  });
  protected readonly overdueCount = computed(() =>
    [...this.overdueByPerson().values()].reduce((sum, o) => sum + o.count, 0));
  protected readonly overdueTotal = computed(() =>
    money(trimmedSum([...this.overdueByPerson().values()].map((o) => o.cents))));
  protected readonly money = money;
  protected readonly personTags = personTags;
  /** Whether the signed-in user's screen shows a people-list column. */
  protected shows(column: PeopleColumn): boolean {
    return !this.session.hiddenColumns().has(column);
  }
  /** The sheet's periods, by from-date. */
  protected readonly periods = signal<Period[]>([]);
  /** Whether a claim counts in a period's live due-total, taken in the status its log gives it at
   *  the end of the period's last day: as the live totals count it in that status (one not yet
   *  added then does not count). */
  private readonly countsAtEnd = computed(() => {
    const leftOut = this.leftOut();
    const today = this.session.today();
    return (c: Claim, end: string) => {
      const status = statusOn(c, end);
      if (status === null) return false;
      if (status === 'SETTLED') return true;
      if ((status !== 'OPEN' && status !== 'PENDING') || deferred(c, today)) return false;
      // a hold or a reached retirement leaves out a claim still open now, as the live due-total does
      return !isOpen(c) || isBound(c) || !leftOut(c);
    };
  });
  /** Each person's live due-total for each period in cents, by person id then period id: the dues
   *  (n/a ones skipped) of their claims whose due-by falls in it, each counted by its status at the
   *  period's end (OPEN and SETTLED ones; ones still open within a hold, unbound, or deferred, left out). */
  private readonly liveDueByPersonPeriod = computed(() => {
    const out = new Map<number, Map<number, number>>();
    const late = this.lateness();
    const countsAtEnd = this.countsAtEnd();
    for (const p of this.people()) {
      const theirs = new Map<number, number>();
      for (const c of this.claimsOf(p.id)) {
        const period = periodOn(this.periods(), c.dueBy);
        if (period === null || !countsAtEnd(c, period.to)) continue;
        theirs.set(period.id, (theirs.get(period.id) ?? 0) + (this.claimDueCents(p, c, late) ?? 0));
      }
      out.set(p.id, theirs);
    }
    return out;
  });
  /** Each person's carried figure for each period in cents, by person id then period id: the dues
   *  (n/a ones skipped) of their claims whose due-by falls in a period ending before this one starts
   *  and that were still OPEN or PENDING at its end by their log, counted as the period figures count,
   *  plus their opening balance for it. */
  private readonly carriedByPersonPeriod = computed(() => {
    const out = new Map<number, Map<number, number>>();
    const late = this.lateness();
    const countsAtEnd = this.countsAtEnd();
    const periods = this.periods();
    for (const p of this.people()) {
      const theirs = new Map<number, number>();
      for (const c of this.claimsOf(p.id)) {
        const own = periodOn(periods, c.dueBy);
        if (own === null) continue;
        for (const period of periods) {
          if (own.to >= period.from) continue;
          const status = statusOn(c, period.to);
          if ((status !== 'OPEN' && status !== 'PENDING') || !countsAtEnd(c, period.to)) continue;
          theirs.set(period.id, (theirs.get(period.id) ?? 0) + (this.claimDueCents(p, c, late) ?? 0));
        }
      }
      for (const period of periods) {
        const opening = period.openings?.[p.id];
        if (opening !== undefined) theirs.set(period.id, (theirs.get(period.id) ?? 0) + cents(opening));
      }
      out.set(p.id, theirs);
    }
    return out;
  });
  /** The person's carried figure for the period in cents: fixed when it was closed, else live
   *  (0 with nothing carried). */
  protected carriedOf(personId: number, period: Period): number {
    return personPeriodCarriedCents(period, personId, this.carriedByPersonPeriod().get(personId) ?? new Map());
  }
  /** The person's due-total for the period in cents: fixed when it was closed, else live. */
  protected periodDueOf(personId: number, period: Period): number {
    return personPeriodDueCents(period, personId, this.liveDueByPersonPeriod().get(personId) ?? new Map());
  }
  /** Each period's due-total in cents, by period id: the people's due-totals for it added up. */
  protected readonly dueByPeriod = computed(() => {
    const out = new Map<number, number>();
    for (const period of this.periods()) {
      out.set(period.id, this.people().reduce((sum, p) => sum + this.periodDueOf(p.id, period), 0));
    }
    return out;
  });
  /** Each period's classic due-total in cents, by period id: a closed or soft-closed one's figures
   *  fixed then (the people's added up), else the dues (n/a ones skipped) of the claims whose due-by
   *  falls in it, each counted by its status now as the live totals count it. */
  protected readonly classicDueByPeriod = computed(() => {
    const out = new Map<number, number>();
    const late = this.lateness();
    const live = this.countsLive();
    for (const period of this.periods()) {
      if (period.closed || period.softClosed) {
        out.set(period.id, this.people().reduce((sum, p) => sum + this.periodDueOf(p.id, period), 0));
        continue;
      }
      let total = 0;
      for (const p of this.people()) {
        for (const c of this.claimsOf(p.id)) {
          if (live(c) && periodOn([period], c.dueBy) !== null) total += this.claimDueCents(p, c, late) ?? 0;
        }
      }
      out.set(period.id, total);
    }
    return out;
  });
  /** Everyone's due-total added up, skipping n/a, not trimmed. */
  protected readonly classicDueTotal = computed(() =>
    [...this.dueByPerson().values()].reduce((sum: number, d) => sum + (d ?? 0), 0));
  /** Each person as the summary counts them: grade today, whether retired (as the list hides them), due-total, whether active. */
  protected readonly summaryPeople = computed<SummaryPerson[]>(() => {
    const dues = this.dueByPerson();
    const active = this.activeIds();
    return this.people().map((p) => ({ grade: this.gradeOf(p), retired: p.retiredFrom !== null, due: dues.get(p.id) ?? null,
      active: active.has(p.id) }));
  });
  /** The names of the people on the sheet, by person id (retired ones too). */
  private readonly peopleById = computed(() => new Map(this.people().map((p) => [p.id, p])));
  /** Everyone's due-total added up, skipping n/a, trimmed. */
  protected readonly dueTotal = computed(() =>
    money(trimmedSum([...this.dueByPerson().values()].filter((d): d is number => d !== null))));
  /** The signed-in user's own figures: the due-total and overdue-total of the person on the sheet
   *  who goes by the user's name (their name or an alias, ignoring case); null when there is none. */
  protected readonly myFigures = computed(() => {
    const me = this.me();
    return me ? { dueTotal: this.dueOf(me), overdueTotal: money(this.overdueOf(me.id).cents) } : null;
  });
  /** The person on the sheet who goes by the signed-in user's name (their name or an alias,
   *  ignoring case); undefined when there is none. */
  private readonly me = computed(() => {
    const user = this.session.user()?.toLowerCase();
    if (!user) return undefined;
    return this.people().find((p) => [p.name, ...p.aliases].some((n) => n.toLowerCase() === user));
  });
  /** The sheet's crews, as the crews section last read or saved them. */
  protected readonly crews = signal<Crew[]>([]);
  /** The signed-in user's crew total: the due-totals of the crews their person leads (each the
   *  lead's and members' due-totals, n/a skipped), added up; null when they lead none. */
  protected readonly myCrewDueTotal = computed(() => {
    const me = this.me();
    const led = me ? this.crews().filter((c) => c.leadId === me.id) : [];
    if (led.length === 0) return null;
    const dues = this.dueByPerson();
    return money(led.reduce((sum, c) =>
      sum + crewIds(c).reduce((s, id) => s + (dues.get(id) ?? 0), 0), 0));
  });
  /** The due-totals of the people the signed-in user watches (n/a skipped), added up; 0.00 when
   *  they watch no one. */
  protected readonly watchedDueTotal = computed(() => {
    const dues = this.dueByPerson();
    return money(this.people().filter((p) => p.watched).reduce((sum, p) => sum + (dues.get(p.id) ?? 0), 0));
  });
  /** The figures of the sheet's descendants added up, as last read from the store. */
  private readonly descendants = signal<DescendantFigures>(NO_FIGURES);
  /** The sheet's own figures as it stood at the end of the as-of date, read from the store while
   *  the screen shows it as of a date; null when it shows the sheet as it stands (the screen's own
   *  figures are then live). */
  private readonly ownLive = signal<DescendantFigures | null>(null);
  /** The sheet's own figures and its descendants' added up, each as the screen shows the sheet:
   *  as it is now, or as it stood at the end of the as-of date. The due-total and overdue-total are
   *  everyone's over the whole tree, trimmed once; people whose names or aliases match count once. */
  protected readonly consolidated = computed<DescendantFigures>(() => {
    const d = this.descendants();
    const own = this.ownLive() ?? this.ownFigures();
    const members = [...own.members, ...d.members];
    return {
      people: distinctPeople(members),
      dueTotal: money(trimmedSum(members.flatMap((m) => (m.dueTotal === null ? [] : [cents(m.dueTotal)])))),
      overdueTotal: money(trimmedSum(members.map((m) => cents(m.overdueTotal)))),
      gradeDueTotals: Object.fromEntries(GRADES.map((g) =>
        [g, money(cents(own.gradeDueTotals[g] ?? '0.00') + cents(d.gradeDueTotals[g] ?? '0.00'))])),
      statusCounts: Object.fromEntries(STATUSES.map((s) =>
        [s, (own.statusCounts[s] ?? 0) + (d.statusCounts[s] ?? 0)])),
      members,
      overdueCount: own.overdueCount + d.overdueCount,
    };
  });
  /** The sheet's own figures, live, as the consolidated figures add them up. */
  private readonly ownFigures = computed<DescendantFigures>(() => {
    const people = this.summaryPeople().filter((p) => !p.retired);
    const dues = this.dueByPerson();
    const overdues = this.overdueByPerson();
    const members = this.people().map((p) => {
      const due = dues.get(p.id) ?? null;
      return { names: [p.name, ...p.aliases], dueTotal: due === null ? null : money(due),
        overdueTotal: money(overdues.get(p.id)?.cents ?? 0) };
    });
    return {
      people: this.people().length, dueTotal: this.dueTotal(), overdueTotal: this.overdueTotal(),
      gradeDueTotals: Object.fromEntries(GRADES.map((g) =>
        [g, money(people.filter((p) => p.grade === g).reduce((sum, p) => sum + (p.due ?? 0), 0))])),
      statusCounts: Object.fromEntries(statusCounts(this.claims()).map((s) => [s.status, s.count])),
      members,
      overdueCount: this.overdueCount(),
    };
  });
  protected readonly claimedTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + (this.byPerson().get(p.id)?.cents ?? 0), 0)));
  /** Every tag ever used on the sheet, as last read from the store. */
  private readonly tagsUsed = signal<string[]>([]);
  /** Each tag ever used on the sheet (those read, and any a claim or person carries now), in order,
   *  with the dues in cents (n/a ones skipped) of the counting claims that carry it, themselves or
   *  through their person, once each; a claim with two tags counts in both. */
  protected readonly tagTotals = computed(() => {
    const out = new Map<string, number>(this.tagsUsed().map((t) => [t, 0]));
    for (const c of this.claims()) for (const t of tagsOf(c)) if (!out.has(t)) out.set(t, 0);
    for (const p of this.people()) for (const t of personTags(p)) if (!out.has(t)) out.set(t, 0);
    const late = this.lateness();
    const live = this.countsLive();
    for (const p of this.people()) {
      const own = personTags(p);
      for (const c of this.claimsOf(p.id).filter((c) => (c.tags !== null || own.length > 0) && live(c))) {
        const due = this.claimDueCents(p, c, late) ?? 0;
        for (const t of new Set([...tagsOf(c), ...own])) out.set(t, (out.get(t) ?? 0) + due);
      }
    }
    return [...out].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([tag, cents]) => ({ tag, cents }));
  });
  /** Each calendar month (yyyy-mm) any claim on the sheet is dated in, in order, with the dues in
   *  cents (n/a ones skipped) of the counting claims dated in it. */
  protected readonly monthTotals = computed(() => {
    const out = new Map<string, number>(this.claims().map((c) => [c.date.slice(0, 7), 0]));
    const late = this.lateness();
    const live = this.countsLive();
    for (const p of this.people()) {
      for (const c of this.claimsOf(p.id).filter(live)) {
        const month = c.date.slice(0, 7);
        out.set(month, (out.get(month) ?? 0) + (this.claimDueCents(p, c, late) ?? 0));
      }
    }
    return [...out].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([month, cents]) => ({ month, cents }));
  });
  /** The from-date and to-date of the snapshot comparison last asked for; null before any. */
  protected readonly snapshotRange = signal<{ from: string; to: string } | null>(null);
  /** The snapshot comparison, one line per person in list order: "<name> <then> <now>", then the
   *  due-total of the snapshot taken leaving the from-date, now that of the to-date (or the live
   *  due-total when the to-date is blank); a missing snapshot is empty, and the line is trimmed. */
  protected readonly snapshotLines = computed(() => {
    const range = this.snapshotRange();
    if (!range) return [];
    const at = (p: Person, day: string): string => {
      const sn = p.snapshots.filter((s) => s.day === day).at(-1);
      return sn ? sn.dueTotal ?? 'n/a' : '';
    };
    return this.shownPeople().map((p) =>
      [p.name, at(p, range.from), range.to ? at(p, range.to) : this.dueOf(p)].join(' ').trim());
  });
  /** Compare the snapshots of the two dates, with the people (and their snapshots) as the
   *  store now holds them. */
  protected async compareSnapshots(from: string, to: string): Promise<void> {
    this.snapshotRange.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const people = await this.api.list(sheetId);
    if (this.session.sheetId() !== sheetId) return;
    this.people.set(people);
    this.snapshotRange.set({ from, to });
  }
  /** Whether the duplicate claims are shown. */
  protected readonly dupsShown = signal(false);
  /** Each person, date and amount with two or more claims (VOID and DELETED ones left out), as
   *  "<name> <date> <amount> x<count>": people in list order, then by date, then by amount. */
  protected readonly dups = computed(() => {
    const out: string[] = [];
    for (const p of this.shownPeople()) {
      const groups = new Map<string, { date: string; amount: string; count: number }>();
      for (const c of this.claimsOf(p.id)) {
        if (c.status === 'VOID' || c.status === 'DELETED') continue;
        const key = c.date + ' ' + c.amount;
        const g = groups.get(key) ?? { date: c.date, amount: c.amount, count: 0 };
        g.count++;
        groups.set(key, g);
      }
      [...groups.values()]
        .filter((g) => g.count >= 2)
        .sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : cents(a.amount) - cents(b.amount)))
        .forEach((g) => out.push(`${p.name} ${g.date} ${g.amount} x${g.count}`));
    }
    return out;
  });
  /** The ids of the claims selected for a bulk action. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** The last bulk open's counts; gone while one runs. */
  protected readonly bulkResult = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkError = signal<string | null>(null);
  private bulkOpening = false;
  /** The last bulk settle's counts; gone while one runs. */
  protected readonly bulkSettleResult = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal<string | null>(null);
  /** The number of the sheet's last bulk settle batch (the highest any claim carries); null with none. */
  protected readonly lastBatch = computed(() =>
    this.claims().reduce<number | null>((last, c) => c.batch !== null && (last === null || c.batch > last) ? c.batch : last, null));
  private bulkSettling = false;
  /** The last bulk reassign's counts; gone while one runs. */
  protected readonly bulkReassignResult = signal<{ reassigned: number; refused: number } | null>(null);
  protected readonly bulkReassignError = signal<string | null>(null);
  private bulkReassigning = false;
  /** The last bulk re-date's counts; gone while one runs. */
  protected readonly bulkRedateResult = signal<{ redated: number; refused: number } | null>(null);
  protected readonly bulkRedateError = signal<string | null>(null);
  private bulkRedating = false;
  /** The last bulk void's counts; gone while one runs. */
  protected readonly bulkVoidResult = signal<{ voided: number; refused: number } | null>(null);
  protected readonly bulkVoidError = signal<string | null>(null);
  private bulkVoiding = false;
  /** The void reason chosen in the bulk void bar; blank for none. */
  protected readonly bulkVoidReason = signal('');
  /** The ids of the people selected for a bulk grade. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  /** The bulk grade bar: a grade and the date it is in force from (blank: the sheet's today). */
  protected readonly bulkGradeForm = new FormGroup({
    grade: new FormControl<string>('A', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
  });
  /** How many people the last bulk grade changed; null while one runs or before the first. */
  protected readonly bulkGraded = signal<number | null>(null);
  protected readonly bulkGradeErr = signal<string | null>(null);
  private bulkGrading = false;
  /** The bulk retire bar: the date the selected people are retired from (blank: the sheet's today). */
  protected readonly bulkRetireForm = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
  });
  /** How many people the last bulk retire retired; null while one runs or before the first. */
  protected readonly bulkRetired = signal<number | null>(null);
  protected readonly bulkRetireErr = signal<string | null>(null);
  private bulkRetiring = false;
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the open editor loaded. */
  private editingVersion = 0;
  /** Whether a submit is waiting for its answer; a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  /** The error of each person's last refused row action, by person id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** The error of each person's last refused patron, by person id. */
  protected readonly patronErrors = signal<Record<number, string>>({});

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
    grade: new FormControl<string>('A', { nonNullable: true, validators: Validators.required }),
    /** On an edit, the date the grade is in force from; blank takes the sheet's today. */
    gradeFrom: new FormControl('', { nonNullable: true }),
    /** The person's own terms in whole days; blank for none. */
    terms: new FormControl('', { nonNullable: true }),
    /** On an edit, the date the terms are in force from; blank records them from the beginning of time. */
    termsFrom: new FormControl('', { nonNullable: true }),
    /** The person's contact; blank for none. */
    contact: new FormControl('', { nonNullable: true }),
    /** The person's tags, comma separated; blank for none. */
    tags: new FormControl('', { nonNullable: true }),
  });

  /** The quick-add bar: a name and an amount make a DRAFT claim for that person. */
  protected readonly quickForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });
  protected readonly quickError = signal<string | null>(null);
  private quickAdding = false;

  /** The paste box: lines of name,amount,date,note, each an OPEN claim (a matching DRAFT is updated and opened). */
  protected readonly pasteForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  /** How many lines the last paste took; null while one runs or before the first. */
  protected readonly pasted = signal<number | null>(null);
  protected readonly pasteErr = signal<string | null>(null);
  private pasting = false;

  /** How many claims the last generate of the plans made; null while one runs or before the first. */
  protected readonly planGenerated = signal<number | null>(null);
  protected readonly planGenerateErr = signal<string | null>(null);
  private generating = false;

  /** The import box: lines of name,grade,terms,contact, each a person as the form adds them. */
  protected readonly importForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  /** How many people the last import added; null while one runs or before the first. */
  protected readonly imported = signal<{ added: number; updated: number } | null>(null);
  protected readonly importErr = signal<string | null>(null);
  private importing = false;

  /** Guards against a slower, older read landing over a newer one. */
  private readSeq = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    this.quickForm.valueChanges.subscribe(() => this.quickError.set(null));
    this.pasteForm.valueChanges.subscribe(() => this.pasteErr.set(null));
    this.importForm.valueChanges.subscribe(() => this.importErr.set(null));
    this.bulkGradeForm.valueChanges.subscribe(() => this.bulkGradeErr.set(null));
    this.bulkRetireForm.valueChanges.subscribe(() => this.bulkRetireErr.set(null));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.reset();
        this.rowErrors.set({});
        this.patronErrors.set({});
        this.quickForm.reset({ name: '', amount: '' });
        this.quickError.set(null);
        this.pasteForm.reset({ text: '' });
        this.pasted.set(null);
        this.pasteErr.set(null);
        this.planGenerated.set(null);
        this.planGenerateErr.set(null);
        this.session.plans.set([]);
        this.importForm.reset({ text: '' });
        this.imported.set(null);
        this.importErr.set(null);
        this.selectedClaims.set(new Set());
        this.bulkResult.set(null);
        this.bulkError.set(null);
        this.bulkSettleResult.set(null);
        this.bulkSettleError.set(null);
        this.bulkReassignResult.set(null);
        this.bulkReassignError.set(null);
        this.bulkRedateResult.set(null);
        this.bulkRedateError.set(null);
        this.bulkVoidResult.set(null);
        this.bulkVoidError.set(null);
        this.bulkVoidReason.set('');
        this.selectedPeople.set(new Set());
        this.bulkGradeForm.reset({ grade: 'A', from: '' }, { emitEvent: false });
        this.bulkGraded.set(null);
        this.bulkGradeErr.set(null);
        this.bulkRetireForm.reset({ from: '' }, { emitEvent: false });
        this.bulkRetired.set(null);
        this.bulkRetireErr.set(null);
        this.people.set([]);
        this.claims.set([]);
        this.tagsUsed.set([]);
        this.rates.set([]);
        this.inherited.set([]);
        this.descendants.set(NO_FIGURES);
        this.ownLive.set(null);
        this.myRole.set(null);
        this.addedHere.set(new Set());
        this.periods.set([]);
        if (id) void this.read(id);
      });
    });
    // a new parent sheet brings in other rates
    effect(() => {
      this.session.parent();
      const id = untracked(() => this.session.sheetId());
      if (id) void untracked(() => this.readInherited(id));
    });
  }

  protected edit(p: Person): void {
    this.error.set(null);
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.form.setValue({ name: p.name, grade: this.gradeOf(p), gradeFrom: '', terms: termsText(p), termsFrom: '', contact: p.contact ?? '',
      tags: personTags(p).join(', ') });
  }

  protected claimsOf(personId: number): Claim[] {
    return this.byPerson().get(personId)?.claims ?? [];
  }

  protected overdueOf(personId: number): { count: number; cents: number } {
    return this.overdueByPerson().get(personId) ?? { count: 0, cents: 0 };
  }

  protected claimedBy(personId: number): string {
    return money(this.byPerson().get(personId)?.cents ?? 0);
  }

  /** The people in the order this user's screen sorts them by: names compared ignoring case, grades
   *  in their order, due-totals smallest first with n/a last; equal keys keep list order. */
  private sorted(people: Person[]): Person[] {
    const by = this.session.sortBy();
    if (by === 'added') return people;
    const dues = this.dueByPerson();
    const key = (p: Person): string | number => {
      switch (by) {
        case 'name': return p.name.toLowerCase();
        case 'grade': return GRADES.indexOf(this.gradeOf(p) as (typeof GRADES)[number]);
        default: return dues.get(p.id) ?? Number.POSITIVE_INFINITY;
      }
    };
    return people.map((p) => ({ p, k: key(p) }))
      .sort((a, b) => (a.k < b.k ? -1 : a.k > b.k ? 1 : 0))
      .map((e) => e.p);
  }

  /** The grade the person shows: the one in force on the sheet's today. */
  protected gradeOf(p: Person): string {
    return gradeOn(p.grades, this.session.today());
  }

  /** How many OPEN and PENDING claims of people on a grade (the one in force on each claim's date)
   *  would show a different due were a percent that grade's rate on every date, and the differences
   *  (new due minus current due) added up in cents; a claim bound by a statement does not change. */
  protected readonly previewRate = (grade: string, pct: string): RatePreview => {
    const late = this.lateness();
    const candidate: Rate[] = [{ grade, from: null, to: null, pct }];
    let count = 0;
    let diff = 0;
    for (const p of this.people()) {
      for (const c of this.claimsOf(p.id)) {
        if ((c.status !== 'OPEN' && c.status !== 'PENDING') || isBound(c) || gradeOn(p.grades, c.date) !== grade) continue;
        const now = this.claimDueCents(p, c, late);
        const next = this.claimDueCents(p, c, late, candidate);
        if (now === next) continue;
        count++;
        diff += (next ?? 0) - (now ?? 0);
      }
    }
    return { count, cents: diff };
  };

  /** A claim's due in cents, at the rate of the person's grade in force on the claim's date as the
   *  claim applies the rates (not a forward-only one saved after it was added), or at the given
   *  rates in their place. */
  private claimDueCents(p: Person, c: Claim, late: Lateness, rates?: Rate[]): number | null {
    const grade = gradeOn(p.grades, c.date);
    return claimDueCents(rates ?? this.rateLevels().forClaim(c.added).filter((r) => r.grade === grade), c, cents(this.session.floorOn(c.date)),
      { ...late, graceOf: (o) => this.graceOf(p, o) },
      roundsHalfUp(c, this.claimsOf(p.id), this.session.policyDate(), late.today, (d) => this.session.roundingOn(d)));
  }

  /** A claim's grace days: those of the person's grade in force on the claim's date. */
  private graceOf(p: Person, c: Claim): number {
    return this.session.graceOn(gradeOn(p.grades, c.date), c.date);
  }

  /** Every rate in force on the sheet for a grade, those it inherits included. */
  protected ratesOf(grade: string): Rate[] {
    return this.effectiveRates().filter((r) => r.grade === grade);
  }

  protected dueOf(p: Person): string {
    const due = this.dueByPerson().get(p.id);
    return due === null || due === undefined ? 'n/a' : money(due);
  }

  /** The person's patrons as <name> (<grade today>) <share>, sorted by name, comma and space separated. */
  protected patronNamesOf(p: Person): string {
    const byId = this.peopleById();
    const shares = patronShares(p.patronIds.length);
    return p.patronIds
      .map((id, i) => ({ patron: byId.get(id), share: shares[i] }))
      .filter((o): o is { patron: Person; share: number } => !!o.patron?.name)
      .map((o) => ({ name: o.patron.name, grade: this.gradeOf(o.patron), share: o.share }))
      .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
      .map((o) => `${o.name} (${o.grade}) ${money(o.share)}`)
      .join(', ');
  }

  /** The sum over the person's patrons of the patron's settled figures over their SETTLED claims dated
   *  within the 90 days up to and including the sheet's today (each at the due fixed when it was settled)
   *  times their share divided by 100, rounded half to even; 0.00 with none. */
  protected backingOf(p: Person): string {
    const today = this.session.today();
    if (today === null) return money(0);
    const since = plusDays(today, -89);
    const figures = new Map<number, number>();
    for (const c of this.claims()) {
      if (c.status === 'SETTLED' && c.settledDue !== null && since <= c.date && c.date <= today) {
        figures.set(c.personId, (figures.get(c.personId) ?? 0) + cents(c.settledDue as string));
      }
    }
    const shares = patronShares(p.patronIds.length);
    // cents times hundredths of a percent: divided by 10000 for cents
    const scaled = p.patronIds.reduce((sum, id, i) => sum + (figures.get(id) ?? 0) * shares[i], 0);
    return money(divideHalfEven(scaled, 10000));
  }

  /** The people the person may name as a patron: everyone else on the sheet. */
  protected patronChoicesFor(p: Person): Person[] {
    return this.people().filter((o) => o.id !== p.id);
  }

  /** Name another person as the person's patron; the row then shows them among its patrons. */
  protected async addPatron(p: Person, patronId: string): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    if (!patronId) {
      this.patronErrors.update((errs) => ({ ...errs, [p.id]: 'Choose a patron.' }));
      return;
    }
    try {
      const saved = await this.api.addPatron(sheetId, p.id, Number(patronId));
      if (this.session.sheetId() === sheetId) this.taken(saved);
    } catch (e) {
      this.patronErrors.update((errs) => ({ ...errs, [p.id]: patronError(e) }));
    }
  }

  /** Merge a person into another; the row then leaves the list, and the sheet's people and claims
   *  are read again to show the other with what they took in. */
  protected async merge(p: Person, intoId: string): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    if (!intoId) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: 'Choose a person to merge into.' }));
      return;
    }
    try {
      await this.api.merge(sheetId, p.id, Number(intoId));
      if (this.session.sheetId() !== sheetId) return;
      this.people.update((ps) => ps.filter((o) => o.id !== p.id));
      await this.read(sheetId);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: mergeError(e) }));
    }
  }

  /** Transfer a person to another sheet; the row then leaves the list, and the sheet's people and
   *  claims are read again to show what they left behind. */
  protected async transfer(p: Person, toSheetId: string): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    if (!toSheetId.trim()) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: 'Enter the id of the sheet to transfer to.' }));
      return;
    }
    try {
      await this.api.transfer(sheetId, p.id, toSheetId.trim());
      if (this.session.sheetId() !== sheetId) return;
      this.people.update((ps) => ps.filter((o) => o.id !== p.id));
      await this.read(sheetId);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: transferError(e) }));
    }
  }

  /** Undo the transfer that brought a person here; the row then leaves the list, and the sheet's
   *  people and claims are read again. */
  protected async undoTransfer(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      await this.api.undoTransfer(sheetId, p.id);
      if (this.session.sheetId() !== sheetId) return;
      this.people.update((ps) => ps.filter((o) => o.id !== p.id));
      await this.read(sheetId);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: transferUndoError(e) }));
    }
  }

  /** The last statement's due-total: blank with none, n/a when it was n/a. */
  protected statedTotalOf(p: Person): string {
    return p.statementCount === 0 ? '' : (p.statedTotal ?? 'n/a');
  }

  /** Take a statement of a person, as of the date entered (blank: now), which is then cleared; the
   *  row then shows it and their claims as bound now. */
  protected async statement(p: Person, asOfInput: HTMLInputElement): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const asOf = asOfInput.value.trim();
    asOfInput.value = '';
    try {
      const taken = await this.api.statement(sheetId, p.id, asOf);
      if (this.session.sheetId() !== sheetId) return;
      this.taken(taken.person);
      this.claims.update((cs) => [...cs.filter((c) => c.personId !== p.id), ...taken.claims].sort(byListed));
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: statementError(e) }));
    }
  }

  /** Cancel a person's last statement; the row then shows the one before it and their claims live again. */
  protected async cancelStatement(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const cancelled = await this.api.cancelStatement(sheetId, p.id);
      if (this.session.sheetId() !== sheetId) return;
      this.taken(cancelled.person);
      this.claims.update((cs) => [...cs.filter((c) => c.personId !== p.id), ...cancelled.claims].sort(byListed));
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: cancelStatementError(e) }));
    }
  }

  /** Retire a person from a date (blank: the sheet's today); the row then shows them retired. */
  protected async retire(p: Person, from: string): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const retired = await this.api.retire(sheetId, p.id, from.trim());
      if (this.session.sheetId() === sheetId) this.taken(retired);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: retireError(e) }));
    }
  }

  /** Give a person the chosen grade from the sheet's today, as the person form would; the row then
   *  shows the grade, and the claims and rollups follow it. */
  protected async saveGrade(p: Person, grade: string): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const graded = await this.api.grade(sheetId, p.id, grade);
      if (this.session.sheetId() === sheetId) this.taken(graded);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: gradeError(e) }));
    }
  }

  /** Unretire a retired person on the sheet's today; the row then shows them not retired, with
   *  the retirement kept as a past one. */
  protected async unretire(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const unretired = await this.api.unretire(sheetId, p.id);
      if (this.session.sheetId() === sheetId) this.taken(unretired);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: unretireError(e) }));
    }
  }

  /** Move a person one place up or down the list, swapping with their neighbour; the list then
   *  shows everyone in their new order. */
  protected async move(p: Person, direction: 'up' | 'down'): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const people = await this.api.move(sheetId, p.id, direction);
      if (this.session.sheetId() === sheetId) this.people.set(people);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: moveError(e) }));
    }
  }

  /** Add a hold to a person from a date (blank: the sheet's today) to an optional one; the row then shows it. */
  protected async hold(p: Person, from: string, to: string): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const saved = await this.api.hold(sheetId, p.id, from.trim(), to.trim());
      if (this.session.sheetId() === sheetId) this.taken(saved);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: holdError(e) }));
    }
  }

  /** Give a person the alias entered, which is then cleared; the row then shows it among their aliases. */
  protected async addAlias(p: Person, aliasInput: HTMLInputElement): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const saved = await this.api.addAlias(sheetId, p.id, aliasInput.value);
      aliasInput.value = '';
      if (this.session.sheetId() === sheetId) this.taken(saved);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: aliasError(e) }));
    }
  }

  /** Flag a person, as a steward, with a reason until a date (blank: the sheet's today); the row
   *  then shows the reason while the flag is in force. */
  protected async flag(p: Person, reason: string, until: string): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const saved = await this.api.flag(sheetId, p.id, reason, until.trim());
      if (this.session.sheetId() === sheetId) this.taken(saved);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: flagError(e) }));
    }
  }

  /** Name the user (blank: none) who may approve or reject the person's PENDING claims; the row then shows them. */
  protected async setApprover(p: Person, user: string): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const saved = await this.api.setApprover(sheetId, p.id, user);
      if (this.session.sheetId() === sheetId) this.taken(saved);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: approverError(e) }));
    }
  }

  /** Start or stop watching a person as the signed-in user; the row then shows whether they are watched. */
  protected async watch(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const saved = await this.api.watch(sheetId, p.id, !p.watched);
      if (this.session.sheetId() === sheetId) this.taken(saved);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [p.id]: watchError(e) }));
    }
  }

  protected clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
    this.patronErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /** Take in a rate just set, in place of the grade's old one for the same from-date. */
  protected rateSaved(rate: Rate): void {
    this.rates.update((rs) => [...rs.filter((r) => r.grade !== rate.grade || r.from !== rate.from), rate]);
  }

  /** Take in a period just added or closed, in its place by from-date. */
  protected periodSaved(period: Period): void {
    this.periods.update((ps) => [...ps.filter((p) => p.id !== period.id), period].sort((a, b) => a.from.localeCompare(b.from)));
  }

  /** Take in a claim just added (listed first, as the newest) or saved (in its place; a
   *  reassigned one listed first under its new person). An action on the person's row, it clears
   *  the row's last error. */
  protected claimSaved(claim: Claim): void {
    this.clearRowError(claim.personId);
    this.claims.update((cs) => [claim, ...cs.filter((c) => c.id !== claim.id)].sort(byListed));
  }

  /** Select a claim for a bulk action, or take it out of the selection. */
  protected claimToggled(t: { id: number; on: boolean }): void {
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (t.on) next.add(t.id);
      else next.delete(t.id);
      return next;
    });
  }

  /** Open every selected claim that can be opened, numbered in list order; then show how many
   *  were opened and refused, and the claims as the store now holds them. */
  protected async bulkOpen(): Promise<void> {
    if (this.bulkOpening) return;
    this.bulkResult.set(null);
    this.bulkError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const ids = [...this.selectedClaims()];
    this.bulkOpening = true;
    try {
      const result = await this.claimsApi.bulkOpen(sheetId, ids);
      const claims = await this.claimsApi.list(sheetId);
      if (this.session.sheetId() !== sheetId) return;
      this.claims.set(claims);
      this.selectedClaims.set(new Set());
      this.bulkResult.set(result);
    } catch {
      this.bulkError.set('Something went wrong opening the claims; try again.');
    } finally {
      this.bulkOpening = false;
    }
  }

  /** Settle every selected claim that can be settled, in list order, by the rules of settling one
   *  (one sent to PENDING counts as settled); then show how many were settled and refused, and the
   *  claims as the store now holds them. */
  protected async bulkSettle(): Promise<void> {
    if (this.bulkSettling) return;
    this.bulkSettleResult.set(null);
    this.bulkSettleError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const ids = [...this.selectedClaims()];
    this.bulkSettling = true;
    try {
      const result = await this.claimsApi.bulkSettle(sheetId, ids);
      const claims = await this.claimsApi.list(sheetId);
      if (this.session.sheetId() !== sheetId) return;
      this.claims.set(claims);
      this.selectedClaims.set(new Set());
      this.bulkSettleResult.set(result);
    } catch {
      this.bulkSettleError.set('Something went wrong settling the claims; try again.');
    } finally {
      this.bulkSettling = false;
    }
  }

  /** Move every selected claim that can be moved to the person, in list order, by the rules of
   *  reassigning one; then show how many were moved and refused, and the claims as the store now
   *  holds them. */
  protected async bulkReassign(personId: string): Promise<void> {
    if (this.bulkReassigning) return;
    this.bulkReassignResult.set(null);
    this.bulkReassignError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    if (!personId) {
      this.bulkReassignError.set('Choose a person to reassign the claims to.');
      return;
    }
    const ids = [...this.selectedClaims()];
    this.bulkReassigning = true;
    try {
      const result = await this.claimsApi.bulkReassign(sheetId, ids, Number(personId));
      const claims = await this.claimsApi.list(sheetId);
      if (this.session.sheetId() !== sheetId) return;
      this.claims.set(claims);
      this.selectedClaims.set(new Set());
      this.bulkReassignResult.set(result);
    } catch {
      this.bulkReassignError.set('Something went wrong reassigning the claims; try again.');
    } finally {
      this.bulkReassigning = false;
    }
  }

  /** Re-date every selected claim that can be re-dated, in list order, by the rules of re-dating
   *  one; then show how many were re-dated and refused, and the claims as the store now holds them. */
  protected async bulkRedate(date: string): Promise<void> {
    if (this.bulkRedating) return;
    this.bulkRedateResult.set(null);
    this.bulkRedateError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    date = date.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.bulkRedateError.set('Enter a date as yyyy-mm-dd to re-date the claims to.');
      return;
    }
    const ids = [...this.selectedClaims()];
    this.bulkRedating = true;
    try {
      const result = await this.claimsApi.bulkRedate(sheetId, ids, date);
      const claims = await this.claimsApi.list(sheetId);
      if (this.session.sheetId() !== sheetId) return;
      this.claims.set(claims);
      this.selectedClaims.set(new Set());
      this.bulkRedateResult.set(result);
    } catch {
      this.bulkRedateError.set('Something went wrong re-dating the claims; try again.');
    } finally {
      this.bulkRedating = false;
    }
  }

  /** Void every selected claim, in list order, exactly as voiding one would (with the reason
   *  chosen; a sheet with void reasons refuses each without one); then show how many were voided
   *  and refused, and the claims as the store now holds them. */
  protected async bulkVoid(): Promise<void> {
    if (this.bulkVoiding) return;
    this.bulkVoidResult.set(null);
    this.bulkVoidError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const chosen = this.bulkVoidReason();
    const reason = this.session.voidReasons().includes(chosen) ? chosen : null;
    const ids = [...this.selectedClaims()];
    this.bulkVoiding = true;
    try {
      const result = await this.claimsApi.bulkVoid(sheetId, ids, reason);
      const claims = await this.claimsApi.list(sheetId);
      if (this.session.sheetId() !== sheetId) return;
      this.claims.set(claims);
      this.selectedClaims.set(new Set());
      this.bulkVoidResult.set(result);
    } catch {
      this.bulkVoidError.set('Something went wrong voiding the claims; try again.');
    } finally {
      this.bulkVoiding = false;
    }
  }

  /** Select a person for a bulk grade or retire, or take them out of the selection. */
  protected personToggled(id: number, on: boolean): void {
    this.bulkGradeErr.set(null);
    this.bulkRetireErr.set(null);
    this.clearRowError(id); // the next action on the row
    this.selectedPeople.update((ids) => {
      const next = new Set(ids);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Give every selected person the grade from the date entered (blank: the sheet's today), in
   *  list order, as the person form would; then show how many were changed and the people as the
   *  store now holds them. */
  protected async bulkGrade(): Promise<void> {
    if (this.bulkGrading) return;
    this.bulkGraded.set(null);
    this.bulkGradeErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const ids = [...this.selectedPeople()];
    if (ids.length === 0) {
      this.bulkGradeErr.set('Select at least one person.');
      return;
    }
    const value = this.bulkGradeForm.getRawValue();
    this.bulkGrading = true;
    try {
      const result = await this.api.bulkGrade(sheetId, ids, value.grade, value.from.trim());
      const people = await this.api.list(sheetId);
      if (this.session.sheetId() !== sheetId) return;
      this.people.set(people);
      this.selectedPeople.set(new Set());
      this.bulkGradeForm.reset({ grade: 'A', from: '' }, { emitEvent: false });
      this.bulkGraded.set(result.changed);
    } catch (e) {
      this.bulkGradeErr.set(bulkGradeError(e));
    } finally {
      this.bulkGrading = false;
    }
  }

  /** Retire every selected person from the date entered (blank: the sheet's today), in list
   *  order, as the row's retire would; then show how many were retired and the people as the
   *  store now holds them. */
  protected async bulkRetire(): Promise<void> {
    if (this.bulkRetiring) return;
    this.bulkRetired.set(null);
    this.bulkRetireErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const ids = [...this.selectedPeople()];
    if (ids.length === 0) {
      this.bulkRetireErr.set('Select at least one person.');
      return;
    }
    const value = this.bulkRetireForm.getRawValue();
    this.bulkRetiring = true;
    try {
      const result = await this.api.bulkRetire(sheetId, ids, value.from.trim());
      const people = await this.api.list(sheetId);
      if (this.session.sheetId() !== sheetId) return;
      this.people.set(people);
      this.selectedPeople.set(new Set());
      this.bulkRetireForm.reset({ from: '' }, { emitEvent: false });
      this.bulkRetired.set(result.retired);
    } catch (e) {
      this.bulkRetireErr.set(bulkRetireError(e));
    } finally {
      this.bulkRetiring = false;
    }
  }

  /** Download the people as CSV, in list order, retired ones too: name, grade today, retired-from, due-total as shown. */
  protected exportCsv(): void {
    const lines = [csvLine(['name', 'grade', 'retired-from', 'due-total']),
      ...this.people().map((p) => csvLine([p.name, this.gradeOf(p), p.retiredFrom ?? '', this.dueOf(p)]))];
    this.download(lines.join(''), `people-${this.session.sheetId()}.csv`);
  }

  /** Download the claims as CSV: people in list order (retired ones too), each one's claims oldest first, with
   *  person, number, date, amount, due, status and period as the claim's row shows them. */
  protected exportClaims(): void {
    const late = this.lateness();
    const periods = this.periods();
    const lines = [csvLine(['person', 'number', 'date', 'amount', 'due', 'status', 'period'])];
    for (const p of this.people()) {
      for (const c of [...this.claimsOf(p.id)].reverse()) {
        const due = this.claimDueCents(p, c, late);
        lines.push(csvLine([p.name, c.number ?? '', c.date, c.amount,
          due === null ? 'n/a' : money(due), c.status, periodOn(periods, dueBy(c))?.name ?? '']));
      }
    }
    this.download(lines.join(''), `claims-${this.session.sheetId()}.csv`);
  }

  /** Download a person's statement as CSV: their claims bound by their last statement, oldest first, with
   *  number, date, amount, the bound figure as the claim shows it, and status. */
  protected exportStatement(p: Person): void {
    this.clearRowError(p.id);
    const late = this.lateness();
    const lines = [csvLine(['number', 'date', 'amount', 'figure', 'status'])];
    for (const c of [...this.claimsOf(p.id)].reverse().filter(isBound)) {
      const figure = this.claimDueCents(p, c, late);
      lines.push(csvLine([c.number ?? '', c.date, c.amount, figure === null ? 'n/a' : money(figure), c.status]));
    }
    this.download(lines.join(''), `statement-${this.session.sheetId()}-${p.id}.csv`);
  }

  /** Hand the browser a CSV file to download. */
  private download(text: string, name: string): void {
    const url = URL.createObjectURL(new Blob([text], { type: 'text/csv' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = name;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected cancel(): void {
    this.reset();
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.form.getRawValue();
    if (!value.name.trim()) {
      this.error.set('Enter a name.');
      return;
    }
    if (!(GRADES as readonly string[]).includes(value.grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    if (!/^\d{0,4}$/.test(value.terms.trim())) {
      // refused terms leave the editor, so the form is ready to add a person next
      this.reset();
      this.error.set('Enter terms as whole days, or leave them blank for none.');
      return;
    }
    const editing = this.editingId();
    this.submitting = true;
    try {
      if (editing === null) {
        const added = await this.api.add(sheetId, { name: value.name, grade: value.grade, terms: value.terms.trim(),
          contact: value.contact.trim(), tags: value.tags.trim() });
        if (this.session.sheetId() === sheetId) this.people.update((ps) => [...ps, added]);
      } else {
        const saved = await this.api.update(sheetId, editing,
          { ...value, gradeFrom: value.gradeFrom.trim() || null, terms: value.terms.trim(),
            termsFrom: value.termsFrom.trim() || null, contact: value.contact.trim(),
            tags: value.tags.trim(), version: this.editingVersion });
        if (this.session.sheetId() === sheetId) this.taken(saved);
      }
      this.reset();
    } catch (e) {
      if (editing !== null && isStale(e)) await this.reloadEditing(sheetId, editing);
      this.error.set(personError(e));
    } finally {
      this.submitting = false;
    }
  }

  /** Quick-add a DRAFT claim by name; the person is added first (grade A) when new. */
  protected async quickAdd(): Promise<void> {
    if (this.quickAdding) return;
    this.quickError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.quickForm.getRawValue();
    const amount = value.amount.trim();
    if (!value.name.trim()) {
      this.quickError.set('Enter a name.');
      return;
    }
    if (!AMOUNT_PATTERN.test(amount)) {
      this.quickError.set('Enter an amount of 0.00 or more with up to two decimals.');
      return;
    }
    this.quickAdding = true;
    try {
      const added = await this.claimsApi.quickAdd(sheetId, { name: value.name, amount });
      if (this.session.sheetId() !== sheetId) return;
      if (added.personAdded) {
        this.people.update((ps) => [...ps.filter((p) => p.id !== added.person.id), added.person]);
        this.addedHere.update((ids) => new Set([...ids, added.person.id]));
      }
      else this.taken(added.person);
      this.claimSaved(added.claim);
      this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
    } catch (e) {
      this.quickError.set(quickAddError(e));
    } finally {
      this.quickAdding = false;
    }
  }

  /** Add or update and open a claim per pasted line, all or none; then show how many lines went through. */
  protected async paste(): Promise<void> {
    if (this.pasting) return;
    this.pasted.set(null);
    this.pasteErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const text = this.pasteForm.getRawValue().text;
    if (!text.trim()) {
      this.pasteErr.set('Paste at least one line of name,amount,date,note.');
      return;
    }
    this.pasting = true;
    try {
      const added = await this.claimsApi.paste(sheetId, text);
      if (this.session.sheetId() !== sheetId) return;
      for (const claim of added) this.claimSaved(claim);
      this.pasteForm.reset({ text: '' }, { emitEvent: false });
      this.pasted.set(added.length);
    } catch (e) {
      this.pasteErr.set(pasteError(e));
    } finally {
      this.pasting = false;
    }
  }

  /** Make the claims every plan on the sheet is due by the sheet's today; then show how many were made. */
  protected async generatePlans(): Promise<void> {
    if (this.generating) return;
    this.planGenerated.set(null);
    this.planGenerateErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.generating = true;
    try {
      const { claims, made, plans } = await this.plansApi.generate(sheetId);
      if (this.session.sheetId() !== sheetId) return;
      for (const claim of claims) this.claimSaved(claim);
      this.session.plans.set(plans);
      this.planGenerated.set(made);
    } catch (e) {
      this.planGenerateErr.set(otherError(e));
    } finally {
      this.generating = false;
    }
  }

  /** Add or update a person per imported line, all or none; then show how many were added and updated. */
  protected async importPeople(): Promise<void> {
    if (this.importing) return;
    this.imported.set(null);
    this.importErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const text = this.importForm.getRawValue().text;
    if (!text.trim()) {
      this.importErr.set('Paste at least one line of name,grade,terms,contact.');
      return;
    }
    this.importing = true;
    try {
      const { added, updated } = await this.api.importPeople(sheetId, text);
      if (this.session.sheetId() !== sheetId) return;
      updated.forEach((p) => this.taken(p));
      this.people.update((ps) => [...ps, ...added]);
      this.importForm.reset({ text: '' }, { emitEvent: false });
      this.imported.set({ added: added.length, updated: updated.length });
    } catch (e) {
      this.importErr.set(importError(e));
    } finally {
      this.importing = false;
    }
  }

  /** Take in a person as the store now holds them, in place of the old row. */
  private taken(person: Person): void {
    this.people.update((ps) => ps.map((p) => (p.id === person.id ? person : p)));
  }

  /** After a save refused as stale, show the person's current values in the row and the open
   *  editor, which then edits that version. */
  private async reloadEditing(sheetId: string, id: number): Promise<void> {
    try {
      const current = await this.api.get(sheetId, id);
      if (this.session.sheetId() !== sheetId || this.editingId() !== id) return;
      this.taken(current);
      this.editingVersion = current.version;
      this.form.setValue({ name: current.name, grade: this.gradeOf(current), gradeFrom: this.form.getRawValue().gradeFrom,
        terms: termsText(current), termsFrom: this.form.getRawValue().termsFrom, contact: current.contact ?? '', tags: personTags(current).join(', ') },
        { emitEvent: false });
    } catch {
      // the error shown already says the save was refused
    }
  }

  private reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A', gradeFrom: '', terms: '', termsFrom: '', contact: '', tags: '' });
    this.error.set(null);
  }

  private async readInherited(sheetId: string): Promise<void> {
    const seq = this.readSeq;
    try {
      const inherited = await this.ratesApi.inherited(sheetId);
      if (seq === this.readSeq && this.session.sheetId() === sheetId) this.inherited.set(inherited);
    } catch {
      // the next load of the sheet reads them again
    }
  }

  private async read(sheetId: string): Promise<void> {
    const seq = ++this.readSeq;
    try {
      const asOf = this.session.asOf() !== null;
      const [people, claims, rates, inherited, periods, tags, descendants, own, plans, role] = await Promise.all([
        this.api.list(sheetId), this.claimsApi.list(sheetId), this.ratesApi.list(sheetId), this.ratesApi.inherited(sheetId),
        this.periodsApi.list(sheetId), this.claimsApi.tags(sheetId), this.api.descendantFigures(sheetId),
        asOf ? this.api.ownFigures(sheetId) : Promise.resolve(null), this.plansApi.list(sheetId),
        this.api.myRole(sheetId)]);
      if (seq === this.readSeq) {
        this.session.plans.set(plans);
        this.myRole.set(role);
        this.descendants.set(descendants);
        this.ownLive.set(own);
        this.tagsUsed.set(tags);
        this.rates.set(rates);
        this.inherited.set(inherited);
        this.periods.set(periods);
        this.claims.set(claims);
        this.people.set(people);
      }
    } catch {
      if (seq === this.readSeq) this.error.set('Something went wrong loading people; try again.');
    }
  }
}

/** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
function trimmedSum(figures: number[]): number {
  const sum = figures.reduce((a, b) => a + b, 0);
  return figures.length < 3 ? sum : sum - Math.max(...figures) - Math.min(...figures);
}

/** How many people these are, counting once those whose names or aliases match (ignoring case,
 *  as names are compared), directly or through others. */
function distinctPeople(members: FigureMember[]): number {
  const parent = members.map((_, i) => i);
  const root = (i: number): number => (parent[i] === i ? i : (parent[i] = root(parent[i])));
  const byName = new Map<string, number>();
  members.forEach((m, i) => {
    for (const n of m.names) {
      const key = n.toLowerCase();
      const other = byName.get(key);
      if (other === undefined) byName.set(key, i);
      else parent[root(i)] = root(other);
    }
  });
  return members.filter((_, i) => root(i) === i).length;
}

/** A person's own terms as the form shows them: the days, blank when none. */
function termsText(p: Person): string {
  return p.terms === null ? '' : String(p.terms);
}

/** A person's open claims' dues in cents by age on the sheet's today: 0 to 30 days, 31 to 60, 61 or more. */
interface Aged {
  upTo30: number;
  upTo60: number;
  over60: number;
}

/** A holder's part of a due in cents: the due times their share (in hundredths of a percent) over
 *  10000, rounded half to even. */
function sharePart(due: number, share: number): number {
  const x = due * share;
  const q = Math.floor(x / 10000);
  const r = x - q * 10000;
  return r > 5000 || (r === 5000 && q % 2 !== 0) ? q + 1 : q;
}
