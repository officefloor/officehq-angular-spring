import { HttpErrorResponse } from '@angular/common/http';
import { FigurePipe } from '../../core/locale';
import { Component, computed, effect, inject, linkedSignal, signal, untracked, viewChild } from '@angular/core';
import { Columns, PEOPLE_COLUMNS, PeopleColumn } from '../../core/columns';
import { downloadCsv } from '../../core/csv';
import { ClaimView, ClaimsApi, cents, counts, deferred, isOpen, money, overdue, reviewLate, roundsHalfUp, statusAt } from '../../core/claims';
import { GRADES, Grade, ImportView, PeopleApi, PersonView, flagOn, gradeOn, leavesLive, termsOn } from '../../core/people';
import { PeriodView, PeriodsApi, fixed, periodOn } from '../../core/periods';
import { QuickAddView } from '../../core/quick-add';
import { NO_RATE, RateView, RatesApi, claimDueCents, rateOn, sameSlot } from '../../core/rates';
import { Session, graceOn, valueOn } from '../../core/session';
import { RoleName, SettingsApi } from '../../core/settings';
import { Claims } from '../claims/claims';
import { Header } from '../header/header';
import { Consolidated } from '../consolidated/consolidated';
import { Dashboard } from '../dashboard/dashboard';
import { Periods } from '../periods/periods';
import { PersonPlans, PlanGenerate } from '../plans/plans';
import { Pools } from '../pools/pools';
import { Crews } from '../crews/crews';
import { CrewView, crewDueCents } from '../../core/crews';
import { ImportPeople } from '../import-people/import-people';
import { PasteClaims } from '../paste-claims/paste-claims';
import { ImportOpenings } from '../import-openings/import-openings';
import { QuickAdd } from '../quick-add/quick-add';
import { Rates } from '../rates/rates';
import { GradeSummary, PeriodSummary, StatusSummary, Summary, VoidReasonSummary } from '../summary/summary';
import { VoidReasons } from '../../core/void-reasons';
import { ClassicPeriodSummary, ClassicSummary } from '../summary/classic-summary';

/** The statuses the summary counts claims by, in the order it lists them. */
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'REVIEW'] as const;
/** What the people list can be ordered by on the screen; added is the list order itself. */
const SORT_KEYS = ['added', 'name', 'grade', 'due-total'] as const;
type SortKey = (typeof SORT_KEYS)[number];

/**
 * Whether a person's claim counts in the live totals, what they have claimed included: OPEN, PENDING and
 * SETTLED claims do, held or not, but not an OPEN or PENDING one once their retirement date is reached,
 * unless a statement has bound it, nor one deferred on the sheet's today.
 */
function countsLive(claim: ClaimView, person: PersonView, today: string): boolean {
  return counts(claim) && !deferred(claim, today) && !(isOpen(claim) && !claim.stated && leavesLive(person, today));
}

/**
 * Whether a person's claim counts in a period's live figures: it counts by the status it had at the
 * end of the period's last day (see statusAt), one added after then not counting, and is left out,
 * as in the live totals, when deferred on today or when it is still OPEN or PENDING, unbound by a
 * statement and of a person whose retirement date is reached.
 */
function countsInPeriod(claim: ClaimView, person: PersonView, today: string, period: PeriodView): boolean {
  const then = statusAt(claim, period.to);
  if (then === null) return false;
  const countedThen = then === 'OPEN' || then === 'PENDING' || then === 'SETTLED';
  return countedThen && !deferred(claim, today) && !(isOpen(claim) && !claim.stated && leavesLive(person, today));
}

/**
 * The sum of the people's figures in whole cents, leaving out the single highest and the single
 * lowest when there are three or more.
 */
function trimmedCents(figures: number[]): number {
  const total = figures.reduce((sum, f) => sum + f, 0);
  return figures.length >= 3 ? total - Math.max(...figures) - Math.min(...figures) : total;
}

/** A whole number over a positive whole number, rounded half to even. */
function halfEven(numerator: number, denominator: number): number {
  const whole = Math.floor(numerator / denominator);
  const twice = (numerator - whole * denominator) * 2;
  return twice > denominator || (twice === denominator && whole % 2 !== 0) ? whole + 1 : whole;
}

/** Cents times a share in hundredths of a percent, over 100: whole cents rounded half to even. */
function shareOf(cents: number, share: number): number {
  return halfEven(cents * share, 10000);
}

/**
 * Whether a person is active on the sheet's today: they have an OPEN or PENDING claim that counts in
 * the live totals (see countsLive), dated within the 30 days up to and including today.
 */
function activeOn(claims: ClaimView[], person: PersonView, today: string): boolean {
  if (today === '') return false;
  const since = new Date(Date.parse(today) - 29 * 86_400_000).toISOString().slice(0, 10);
  return claims.some((c) => isOpen(c) && countsLive(c, person, today) && since <= c.date && c.date <= today);
}

// The active sheet's people: listed by their position in the list, added and edited through one form,
// each with their claims, what they have claimed and what is due (the sum of their claims' due
// figures), and the sheet's claimed-total and due-total. The sheet's rates are set here too.
@Component({
  selector: 'app-people',
  imports: [FigurePipe, Claims, Consolidated, Dashboard, Header, ImportPeople, ImportOpenings, PasteClaims, Periods, PersonPlans, PlanGenerate, Pools, Crews, QuickAdd, Rates, Summary, ClassicSummary],
  template: `
    <app-header
      [peopleCount]="people().length"
      [statuses]="statusSummary()"
      [dueTotal]="dueTotal()"
      [overdueTotal]="overdueTotal()"
      [myDueTotal]="me() ? dueTotalOf(me()!) : ''"
      [myOverdueTotal]="me() ? overdueTotalOf(me()!) : ''"
      [myCrewDueTotal]="myCrewDueTotal()"
    />
    <app-consolidated [peopleCount]="people().length" [dueTotal]="dueTotal()" [overdueTotal]="overdueTotal()" />
    <app-dashboard [statuses]="statusSummary()" [overdueTotal]="overdueTotal()" />
    <app-rates [rates]="rates()" [inherited]="inherited()" [today]="today()" (saved)="rateSaved($event)" (imported)="rates.set($event)" />
    <app-periods
      [periods]="periods()"
      [dueTotals]="periodDueTotals()"
      (saved)="periodSaved($event)"
      (closed)="periodChanged($event)"
      (reopened)="periodChanged($event)"
      (locked)="periodChanged($event)"
      (rolledOver)="openingsImported()"
    />
    <app-summary [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()" [voidReasons]="voidReasonSummary()" [reviewLateCount]="reviewLateCount()" />
    <app-classic-summary [periods]="classicPeriodSummary()" [dueTotal]="classicDueTotal()" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form (submit)="save($event)" [attr.aria-label]="editing() === null ? 'Add a person' : 'Edit a person'">
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
          (change)="grade.set(gradeChosen($event)); gradeGiven.set(true); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
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
          (focus)="termsGiven.set(true)"
          (input)="terms.set(value($event)); termsGiven.set(true); error.set(null)"
        />
        <label for="person-form-terms-from">Terms from</label>
        <input
          type="date"
          id="person-form-terms-from"
          data-testid="person-form-terms-from"
          [value]="termsFrom()"
          (input)="termsFrom.set(value($event)); error.set(null)"
        />
        <label for="person-form-contact">Contact</label>
        <input
          id="person-form-contact"
          data-testid="person-form-contact"
          [value]="contact()"
          (focus)="contactGiven.set(true)"
          (input)="contact.set(value($event)); contactGiven.set(true); error.set(null)"
        />
        <label for="person-form-tags">Tags (comma separated)</label>
        <input
          id="person-form-tags"
          data-testid="person-form-tags"
          [value]="tags()"
          (input)="tags.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="person-form-submit">
          {{ editing() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as message) {
          <p role="alert" data-testid="person-form-error">{{ message }}</p>
        }
      </form>
      <app-quick-add (added)="quickAdded($event)" />
      <app-import-people (added)="imported($event)" />
      <app-paste-claims (added)="pasted($event)" />
      <app-import-openings (imported)="openingsImported()" />
      <app-plan-generate (generated)="pasted($event)" />
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
        <label for="sort-by">Sort people by</label>
        <select id="sort-by" data-testid="sort-by" [value]="sortKey()" (change)="sortKey.set(sortKeyOf($event))">
          @for (k of sortKeys; track k) {
            <option [value]="k">{{ k }}</option>
          }
        </select>
      </p>
      <p>
        <button type="button" data-testid="export-people" (click)="exportCsv()">Export people as CSV</button>
        <button type="button" data-testid="export-claims" (click)="exportClaims()">Export claims as CSV</button>
      </p>
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() | figure }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() | figure }}</strong></p>
      <p>Overdue <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong> claims,
        due <strong data-testid="rollup-overdue-total">{{ overdueTotal() | figure }}</strong></p>
      <p>Settled in total <strong data-testid="rollup-settled-total">{{ settledTotal() | figure }}</strong>,
        open in total <strong data-testid="rollup-open-total">{{ openTotal() | figure }}</strong></p>
      <p>Cut in total <strong data-testid="rollup-cut-total">{{ cutTotal() | figure }}</strong></p>
      <p>Due by age
        0&ndash;30 <strong data-testid="rollup-aged-0-30">{{ agedTotal()[0] | figure }}</strong>,
        31&ndash;60 <strong data-testid="rollup-aged-31-60">{{ agedTotal()[1] | figure }}</strong>,
        61+ <strong data-testid="rollup-aged-61">{{ agedTotal()[2] | figure }}</strong></p>
      @if (tagTotals().length > 0) {
        <ul aria-label="Due by tag">
          @for (t of tagTotals(); track t.tag) {
            <li>{{ t.tag }} <strong [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ t.dueTotal | figure }}</strong></li>
          }
        </ul>
      }
      <form (submit)="compareSnapshots($event)" aria-label="Compare snapshots">
        <label for="snapshot-from">Compare the snapshot of</label>
        <input type="date" id="snapshot-from" data-testid="snapshot-from" [value]="snapshotFrom()"
          (input)="snapshotFrom.set(value($event))" />
        <label for="snapshot-to">with the snapshot of (blank: now)</label>
        <input type="date" id="snapshot-to" data-testid="snapshot-to" [value]="snapshotTo()"
          (input)="snapshotTo.set(value($event))" />
        <button type="submit" data-testid="snapshot-compare">Compare snapshots</button>
      </form>
      @if (snapshotLines().length > 0) {
        <ul aria-label="Snapshot comparison">
          @for (line of snapshotLines(); track $index) {
            <li data-testid="snapshot-line">{{ line }}</li>
          }
        </ul>
      }
      @if (monthTotals().length > 0) {
        <ul aria-label="Due by month">
          @for (m of monthTotals(); track m.month) {
            <li>{{ m.month }} <strong [attr.data-testid]="'month-' + m.month + '-due-total'">{{ m.dueTotal | figure }}</strong></li>
          }
        </ul>
      }
      <p>
        <button type="button" data-testid="bulk-open" (click)="openSelected()">Open selected claims</button>
        @if (bulkOpened(); as result) {
          <span role="status">
            Opened <strong data-testid="bulk-opened">{{ result.opened }}</strong>,
            refused <strong data-testid="bulk-refused">{{ result.refused }}</strong>
          </span>
        }
        @if (bulkOpenError(); as message) {
          <span role="alert" data-testid="bulk-open-error">{{ message }}</span>
        }
      </p>
      <p>
        <button type="button" data-testid="bulk-settle" (click)="settleSelected()">Settle selected claims</button>
        <span>Last batch <span data-testid="bulk-settle-batch">{{ lastSettleBatch() ?? '' }}</span></span>
        @if (bulkSettled(); as result) {
          <span role="status">
            Settled <strong data-testid="bulk-settled">{{ result.settled }}</strong>,
            refused <strong data-testid="bulk-settle-refused">{{ result.refused }}</strong>
          </span>
        }
        @if (bulkSettleError(); as message) {
          <span role="alert" data-testid="bulk-settle-error">{{ message }}</span>
        }
      </p>
      <p>
        <label for="bulk-reassign-to">Reassign selected claims to</label>
        <select
          id="bulk-reassign-to"
          data-testid="bulk-reassign-to"
          (change)="bulkReassignTo.set(value($event)); bulkReassigned.set(null); bulkReassignError.set(null)"
        >
          <option value="" [selected]="bulkReassignTo() === ''">Choose a person</option>
          @for (p of people(); track p.id) {
            <option [value]="p.id" [selected]="bulkReassignTo() === '' + p.id">{{ p.name }}</option>
          }
        </select>
        <button type="button" data-testid="bulk-reassign" (click)="reassignSelected()">Reassign selected claims</button>
        @if (bulkReassigned(); as result) {
          <span role="status">
            Reassigned <strong data-testid="bulk-reassigned">{{ result.reassigned }}</strong>,
            refused <strong data-testid="bulk-reassign-refused">{{ result.refused }}</strong>
          </span>
        }
        @if (bulkReassignError(); as message) {
          <span role="alert" data-testid="bulk-reassign-error">{{ message }}</span>
        }
      </p>
      <form (submit)="redateSelected($event)" aria-label="Re-date the selected claims">
        <label for="bulk-redate-date">Re-date the selected claims to</label>
        <input
          type="date"
          id="bulk-redate-date"
          data-testid="bulk-redate-date"
          [value]="bulkRedateDate()"
          (input)="bulkRedateDate.set(value($event)); bulkRedated.set(null); bulkRedateError.set(null)"
        />
        <button type="submit" data-testid="bulk-redate">Re-date selected claims</button>
        @if (bulkRedated(); as result) {
          <span role="status">
            Re-dated <strong data-testid="bulk-redated">{{ result.redated }}</strong>,
            refused <strong data-testid="bulk-redate-refused">{{ result.refused }}</strong>
          </span>
        }
        @if (bulkRedateError(); as message) {
          <span role="alert" data-testid="bulk-redate-error">{{ message }}</span>
        }
      </form>
      <p>
        @if (voidReasons.list().length > 0) {
          <label for="bulk-void-reason">Void reason for the selected claims</label>
          <select
            id="bulk-void-reason"
            data-testid="bulk-void-reason"
            (change)="bulkVoidReason.set(value($event)); bulkVoided.set(null); bulkVoidError.set(null)"
          >
            <option value="" [selected]="bulkVoidReason() === ''">No reason</option>
            @for (r of voidReasons.list(); track r.id) {
              <option [value]="r.reason" [selected]="bulkVoidReason() === r.reason">{{ r.reason }}</option>
            }
          </select>
        }
        <button type="button" data-testid="bulk-void" (click)="voidSelected()">Void selected claims</button>
        @if (bulkVoided(); as result) {
          <span role="status">
            Voided <strong data-testid="bulk-voided">{{ result.voided }}</strong>,
            refused <strong data-testid="bulk-void-refused">{{ result.refused }}</strong>
          </span>
        }
        @if (bulkVoidError(); as message) {
          <span role="alert" data-testid="bulk-void-error">{{ message }}</span>
        }
      </p>
      <form (submit)="gradeSelected($event)" aria-label="Grade the selected people">
        <label for="bulk-grade">Grade the selected people</label>
        <select
          id="bulk-grade"
          data-testid="bulk-grade"
          [value]="bulkGrade()"
          (change)="bulkGrade.set(gradeChosen($event)); bulkGradeError.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">from</label>
        <input
          type="date"
          id="bulk-grade-from"
          data-testid="bulk-grade-from"
          [value]="bulkGradeFrom()"
          (input)="bulkGradeFrom.set(value($event)); bulkGradeError.set(null)"
        />
        <button type="submit" data-testid="bulk-grade-apply">Grade selected people</button>
        @if (bulkGraded() !== null) {
          <span role="status">Changed <strong data-testid="bulk-graded">{{ bulkGraded() }}</strong></span>
        }
        @if (bulkGradeError(); as message) {
          <span role="alert" data-testid="person-error">{{ message }}</span>
        }
      </form>
      <form (submit)="retireSelected($event)" aria-label="Retire the selected people">
        <label for="bulk-retire-date">Retire the selected people from</label>
        <input
          type="date"
          id="bulk-retire-date"
          data-testid="bulk-retire-date"
          [value]="bulkRetireDate()"
          (input)="bulkRetireDate.set(value($event)); bulkRetireError.set(null)"
        />
        <button type="submit" data-testid="bulk-retire">Retire selected people</button>
        @if (bulkRetired() !== null) {
          <span role="status">Retired <strong data-testid="bulk-retired">{{ bulkRetired() }}</strong></span>
        }
        @if (bulkRetireError(); as message) {
          <span role="alert" data-testid="person-error">{{ message }}</span>
        }
      </form>
      <fieldset>
        <legend>Columns shown</legend>
        @for (c of peopleColumns; track c) {
          <label>
            <input
              type="checkbox"
              [attr.data-testid]="'column-' + c"
              [checked]="columns.shown(c)"
              (change)="showColumn(c, checked($event))"
            />
            {{ c }}
          </label>
        }
        @if (columns.error(); as message) {
          <span role="alert" data-testid="columns-error">{{ message }}</span>
        }
      </fieldset>
      <table>
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th>
            <th scope="col">Name</th>
            <th scope="col">Came from</th>
            @if (columns.shown('aliases')) {
              <th scope="col">Aliases</th>
            }
            <th scope="col">Clashing</th>
            <th scope="col">Grade</th>
            @if (columns.shown('terms')) {
              <th scope="col">Terms</th>
            }
            @if (columns.shown('contact')) {
              <th scope="col">Contact</th>
            }
            <th scope="col">Tags</th>
            @if (columns.shown('claimed')) {
              <th scope="col">Claimed</th>
            }
            @if (columns.shown('due-total')) {
              <th scope="col">Due</th>
            }
            <th scope="col">Due by period</th>
            @if (columns.shown('excess')) {
              <th scope="col">Excess</th>
            }
            @if (columns.shown('overdue-count') || columns.shown('overdue-total')) {
              <th scope="col">Overdue</th>
            }
            <th scope="col">Due by age</th>
            @if (columns.shown('statement-count') || columns.shown('stated-total') || columns.shown('statement-date')) {
              <th scope="col">Statements</th>
            }
            @if (columns.shown('retired')) {
              <th scope="col">Retired</th>
            }
            <th scope="col">Statement lines</th>
            <th scope="col">Snapshots</th>
            <th scope="col">Flag</th>
            <th scope="col">Approver</th>
            <th scope="col">Active</th>
            @if (columns.shown('hold')) {
              <th scope="col">On hold</th>
            }
            <th scope="col">Watched</th>
            @if (columns.shown('patrons')) {
              <th scope="col">Patrons</th>
            }
            @if (columns.shown('backing')) {
              <th scope="col">Backing</th>
            }
            <th scope="col">Cut</th>
            <th scope="col">Plans</th>
            <th scope="col">Claims</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of sortedPeople(); track p.id) {
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
              <td data-testid="person-name">{{ shownName(p) }}</td>
              <td data-testid="person-origin">{{ p.originSheetId ?? '' }}</td>
              @if (columns.shown('aliases')) {
                <td>
                  <span data-testid="person-aliases">{{ aliasesOf(p) }}</span>
                  <label [for]="'person-alias-input-' + p.id">Add alias</label>
                  <input
                    type="text"
                    [id]="'person-alias-input-' + p.id"
                    data-testid="person-alias-input"
                    [value]="aliasDrafts.get(p.id) ?? ''"
                    (input)="aliasDrafts.set(p.id, value($event)); personError.set(null)"
                  />
                  <button
                    type="button"
                    data-testid="person-alias-add"
                    [attr.aria-label]="'Add an alias for ' + p.name"
                    (click)="addAlias(p)"
                  >
                    Add
                  </button>
                </td>
              }
              <td data-testid="person-clashing">{{ p.clashing ? 'yes' : 'no' }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              @if (columns.shown('terms')) {
                <td data-testid="person-terms">{{ termsShown(p) ?? '' }}</td>
              }
              @if (columns.shown('contact')) {
                <td data-testid="person-contact">{{ p.contact ?? '' }}</td>
              }
              <td data-testid="person-tags">{{ (p.tags ?? []).join(', ') }}</td>
              @if (columns.shown('claimed')) {
                <td data-testid="person-claimed">{{ claimed(p.id) | figure }}</td>
              }
              @if (columns.shown('due-total')) {
                <td data-testid="person-due-total">{{ dueTotalOf(p) | figure }}</td>
              }
              <td>
                <span>settled <span data-testid="person-settled-total">{{ settledTotalOf(p) | figure }}</span></span>
                <span>open <span data-testid="person-open-total">{{ openTotalOf(p) | figure }}</span></span>
                <span>score <span data-testid="person-score">{{ scoreOf(p) }}</span></span>
                <span>band <span data-testid="person-band">{{ bandOf(p) }}</span></span>
              </td>
              <td>
                <ul>
                  @for (period of periods(); track period.id) {
                    <li>
                      {{ period.name }}
                      <span [attr.data-testid]="'person-period-' + period.name + '-due-total'">{{ periodTotalOf(period, p) | figure }}</span>
                      <span>carried <span [attr.data-testid]="'person-period-' + period.name + '-carried'">{{ carriedOf(period, p) | figure }}</span></span>
                    </li>
                  }
                </ul>
              </td>
              @if (columns.shown('excess')) {
                <td data-testid="person-excess">{{ excessOf(p) | figure }}</td>
              }
              @if (columns.shown('overdue-count') || columns.shown('overdue-total')) {
                <td>
                  @if (columns.shown('overdue-count')) {
                    <span data-testid="person-overdue-count">{{ overdueOf(p.id).length }}</span>
                  }
                  @if (columns.shown('overdue-total')) {
                    <span>due <span data-testid="person-overdue-total">{{ overdueTotalOf(p) | figure }}</span></span>
                  }
                </td>
              }
              <td>
                @let aged = agedOf(p);
                <span>0&ndash;30 <span data-testid="person-aged-0-30">{{ aged[0] | figure }}</span></span>
                <span>31&ndash;60 <span data-testid="person-aged-31-60">{{ aged[1] | figure }}</span></span>
                <span>61+ <span data-testid="person-aged-61">{{ aged[2] | figure }}</span></span>
              </td>
              @if (columns.shown('statement-count') || columns.shown('stated-total') || columns.shown('statement-date')) {
                <td>
                  @if (columns.shown('statement-count')) {
                    <span data-testid="person-statement-count">{{ p.statementCount }}</span>
                  }
                  @if (columns.shown('stated-total')) {
                    <span>Last <span data-testid="person-stated-total">{{ statedTotalOf(p) | figure }}</span></span>
                  }
                  @if (columns.shown('statement-date')) {
                    <span>on <span data-testid="person-statement-date">{{ p.statementDate ?? '' }}</span></span>
                  }
                </td>
              }
              @if (columns.shown('retired')) {
                <td>
                  <span data-testid="person-retired">{{ p.retiredFrom === null ? 'no' : 'yes' }}</span>
                  <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
                  <ul>
                    @for (r of p.retirements ?? []; track $index) {
                      <li data-testid="person-retirement-line">{{ r.from }} to {{ r.to }}</li>
                    }
                  </ul>
                </td>
              }
              <td>
                <ul aria-label="Statement lines">
                  @for (s of p.statementLines ?? []; track $index) {
                    <li data-testid="person-statement-line-line">{{ s.date }} {{ s.dueTotal }}</li>
                  }
                </ul>
              </td>
              <td>
                <ul aria-label="Snapshots">
                  @for (s of p.snapshots ?? []; track $index) {
                    <li data-testid="person-snapshot-line">{{ s.asOf }} {{ s.dueTotal }}</li>
                  }
                </ul>
              </td>
              <td data-testid="person-flag-shown">{{ flagShown(p) }}</td>
              <td data-testid="person-approver">{{ p.approver ?? '' }}</td>
              <td data-testid="person-active">{{ activeOf(p) ? 'yes' : 'no' }}</td>
              @if (columns.shown('hold')) {
                <td>
                  <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                  <ul>
                    @for (h of p.holds; track $index) {
                      <li data-testid="person-hold-line">from {{ h.from }}{{ h.to ? ' to ' + h.to : '' }}</li>
                    }
                  </ul>
                </td>
              }
              <td>
                <span data-testid="person-watched">{{ watched().has(p.id) ? 'yes' : 'no' }}</span>
                <button
                  type="button"
                  data-testid="person-watch"
                  [attr.aria-pressed]="watched().has(p.id)"
                  [attr.aria-label]="(watched().has(p.id) ? 'Stop watching ' : 'Watch ') + p.name"
                  (click)="toggleWatch(p)"
                >
                  {{ watched().has(p.id) ? 'Unwatch' : 'Watch' }}
                </button>
              </td>
              @if (columns.shown('patrons')) {
                <td>
                  <span data-testid="person-patrons">{{ patronsOf(p) }}</span>
                  <label [for]="'person-patron-select-' + p.id">Add patron</label>
                  <select
                    [id]="'person-patron-select-' + p.id"
                    data-testid="person-patron-select"
                    (change)="patronChoices.set(p.id, value($event)); patronError.set(null)"
                  >
                    <option value="">Choose a person</option>
                    @for (other of people(); track other.id) {
                      <option [value]="other.id">{{ other.name }}</option>
                    }
                  </select>
                  <button
                    type="button"
                    data-testid="person-patron-add"
                    [attr.aria-label]="'Add a patron for ' + p.name"
                    (click)="addPatron(p)"
                  >
                    Add
                  </button>
                  @if (patronError()?.id === p.id) {
                    <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                  }
                </td>
              }
              @if (columns.shown('backing')) {
                <td data-testid="person-backing">{{ backingOf(p) | figure }}</td>
              }
              <td data-testid="person-cut-total">{{ cutTotalOf(p) | figure }}</td>
              <td>
                <app-person-plans [personId]="p.id" [personName]="p.name" />
              </td>
              <td>
                <app-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [retiredFrom]="p.retiredFrom"
                  [claims]="claimsOf(p.id)"
                  [grades]="p.grades"
                  [rates]="rates()"
                  [inherited]="inherited()"
                  [periods]="periods()"
                  [people]="people()"
                  [selected]="selectedClaims()"
                  (selectedChange)="selectClaim($event.id, $event.on)"
                  (saved)="claimSaved($event)"
                />
              </td>
              <td>
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
                  [attr.data-testid]="'person-edit-' + p.id"
                  [attr.aria-label]="'Edit ' + p.name"
                  (click)="open(p)"
                >
                  Edit
                </button>
                <label [for]="'person-statement-asof-' + p.id">Statement as of</label>
                <input
                  #statementAsOf
                  type="date"
                  [id]="'person-statement-asof-' + p.id"
                  data-testid="person-statement-asof"
                  (input)="personError.set(null)"
                />
                <button
                  type="button"
                  data-testid="person-statement"
                  [attr.aria-label]="'Take a statement for ' + p.name"
                  (click)="statement(p, statementAsOf)"
                >
                  Statement
                </button>
                <button
                  type="button"
                  data-testid="person-export-statement"
                  [attr.aria-label]="'Export the statement for ' + p.name + ' as CSV'"
                  (click)="exportStatement(p)"
                >
                  Export statement
                </button>
                <button
                  type="button"
                  data-testid="person-unstate"
                  [attr.aria-label]="'Cancel the last statement for ' + p.name"
                  (click)="unstate(p)"
                >
                  Cancel statement
                </button>
                <label [for]="'person-inline-grade-' + p.id">Grade</label>
                <select
                  [id]="'person-inline-grade-' + p.id"
                  data-testid="person-inline-grade"
                  (change)="inlineGrades.set(p.id, $any(value($event))); personError.set(null)"
                >
                  @for (g of grades; track g) {
                    <option [value]="g" [selected]="g === gradeOf(p)">{{ g }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="person-inline-grade-save"
                  [attr.aria-label]="'Save the grade chosen for ' + p.name"
                  (click)="saveInlineGrade(p)"
                >
                  Save grade
                </button>
                <label [for]="'person-retire-date-' + p.id">Retire from</label>
                <input
                  type="date"
                  [id]="'person-retire-date-' + p.id"
                  data-testid="person-retire-date"
                  (input)="retireDates.set(p.id, value($event)); personError.set(null)"
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
                  [value]="holdDraft(p.id).from"
                  (input)="setHoldDraft(p.id, { from: value($event) })"
                />
                <label [for]="'person-hold-to-' + p.id">Hold to</label>
                <input
                  type="date"
                  [id]="'person-hold-to-' + p.id"
                  data-testid="person-hold-to"
                  [value]="holdDraft(p.id).to"
                  (input)="setHoldDraft(p.id, { to: value($event) })"
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
                  type="text"
                  [id]="'person-flag-reason-' + p.id"
                  data-testid="person-flag-reason"
                  [value]="flagDraft(p.id).reason"
                  (input)="setFlagDraft(p.id, { reason: value($event) })"
                />
                <label [for]="'person-flag-until-' + p.id">Flag until</label>
                <input
                  type="date"
                  [id]="'person-flag-until-' + p.id"
                  data-testid="person-flag-until"
                  [value]="flagDraft(p.id).until"
                  (input)="setFlagDraft(p.id, { until: value($event) })"
                />
                <button
                  type="button"
                  data-testid="person-flag"
                  [attr.aria-label]="'Flag ' + p.name"
                  (click)="flag(p)"
                >
                  Flag
                </button>
                <label [for]="'person-approver-input-' + p.id">Approver</label>
                <input
                  type="text"
                  [id]="'person-approver-input-' + p.id"
                  data-testid="person-approver-input"
                  [value]="approverDrafts().get(p.id) ?? ''"
                  (input)="setApproverDraft(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-approver-save"
                  [attr.aria-label]="'Set approver of ' + p.name"
                  (click)="setApprover(p)"
                >
                  Set approver
                </button>
                <label [for]="'person-merge-into-' + p.id">Merge into</label>
                <select
                  [id]="'person-merge-into-' + p.id"
                  data-testid="person-merge-into"
                  (change)="mergeChoices.set(p.id, value($event)); personError.set(null)"
                >
                  <option value="">Choose a person</option>
                  @for (other of people(); track other.id) {
                    <option [value]="other.id">{{ other.name }}</option>
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
                  type="text"
                  [id]="'person-transfer-to-' + p.id"
                  data-testid="person-transfer-to"
                  (input)="transferTargets.set(p.id, value($event)); personError.set(null)"
                />
                <button
                  type="button"
                  data-testid="person-transfer"
                  [attr.aria-label]="'Transfer ' + p.name + ' to the sheet typed'"
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
                @if (personError()?.id === p.id) {
                  <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
    <app-pools [people]="people()" [today]="today()" [crews]="crews()" />
    <app-crews [people]="people()" [today]="today()" [dueTotals]="crewDueTotals()" [(crews)]="crews" />
  `,
})
export class People {
  private readonly session = inject(Session);
  private readonly api = inject(PeopleApi);
  private readonly claimsApi = inject(ClaimsApi);
  private readonly ratesApi = inject(RatesApi);
  private readonly periodsApi = inject(PeriodsApi);
  private readonly settingsApi = inject(SettingsApi);
  protected readonly voidReasons = inject(VoidReasons);
  protected readonly columns = inject(Columns);
  protected readonly peopleColumns = PEOPLE_COLUMNS;

  protected readonly grades = GRADES;
  protected readonly people = signal<PersonView[]>([]);
  /** Whether retired people are listed; off when the screen opens, and kept across sheet loads. */
  protected readonly showRetired = signal(false);
  /** The role the signed-in user acts in on the sheet, as read with its people; null until read. */
  protected readonly myRole = signal<RoleName | null>(null);
  /**
   * The ids of the people a restricted user may see listed: their own person and the leads and members
   * of the crews that person leads or belongs to; null when the user is not restricted.
   */
  private readonly visibleIds = computed(() => {
    if (this.myRole() !== 'restricted') return null;
    const me = this.me();
    if (!me) return new Set<number>();
    const ids = new Set<number>([me.id]);
    for (const c of this.crews()) {
      const members = c.members.map((m) => m.personId);
      if (c.leadId !== me.id && !members.includes(me.id)) continue;
      ids.add(c.leadId);
      for (const id of members) ids.add(id);
    }
    return ids;
  });
  /**
   * The people listed: everyone, or only those not retired unless retired people are shown; for a
   * restricted user, only those they may see.
   */
  protected readonly shownPeople = computed(() => {
    const visible = this.visibleIds();
    const listed = visible === null ? this.people() : this.people().filter((p) => visible.has(p.id));
    return this.showRetired() ? listed : listed.filter((p) => p.retiredFrom === null);
  });
  protected readonly sortKeys = SORT_KEYS;
  /** What this screen orders the listed people by; added (list order) when it opens, and kept across sheet loads. */
  protected readonly sortKey = signal<SortKey>('added');
  /**
   * The listed people as this screen orders them. Only what is shown: the people keep their list
   * order everywhere else. Names compare as they are kept unique (trimmed, ignoring case), grades by
   * the one in force on the sheet's today, due-totals lowest first with n/a last; equal keys keep list order.
   */
  protected readonly sortedPeople = computed(() => {
    const shown = this.shownPeople();
    const by = this.sortKey();
    if (by === 'added') return shown;
    const keyOf = (p: PersonView): string | number | null =>
      by === 'name' ? p.name.trim().toLowerCase() : by === 'grade' ? this.gradeOf(p) : this.dueTotalCents(p);
    const keyed = shown.map((p) => ({ p, key: keyOf(p) }));
    return keyed
      .sort((a, b) => {
        if (a.key === b.key) return 0;
        if (a.key === null) return 1;
        if (b.key === null) return -1;
        return a.key < b.key ? -1 : 1;
      })
      .map((k) => k.p);
  });
  /** The from-date and to-date typed to compare snapshots (yyyy-mm-dd; a blank to-date means now). */
  protected readonly snapshotFrom = signal('');
  protected readonly snapshotTo = signal('');
  /** The last comparison made, one line per person in list order: name, then and now due-totals. */
  protected readonly snapshotLines = signal<string[]>([]);
  /** The retire-from date typed on each person's row, by person id; none means the sheet's today. */
  protected readonly retireDates = new Map<number, string>();
  /** The grade chosen on each person's row. */
  protected readonly inlineGrades = new Map<number, Grade>();
  /** The hold dates typed on each person's row, by person id; an empty from means the sheet's today. */
  protected readonly holdDrafts = signal(new Map<number, { from: string; to: string }>());
  /** The flag reason and until-date typed on each person's row, by person id. */
  protected readonly flagDrafts = signal(new Map<number, { reason: string; until: string }>());
  /** The approver user name typed on each person's row, by person id. */
  protected readonly approverDrafts = signal(new Map<number, string>());
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<ClaimView[]>([]);
  private readonly byPerson = computed(() => {
    const map = new Map<number, ClaimView[]>();
    for (const c of this.claims()) map.set(c.personId, [...(map.get(c.personId) ?? []), c]);
    return map;
  });
  /** The sheet's rates, each grade's by from-date; a grade with no rate is not listed. */
  protected readonly rates = signal<RateView[]>([]);
  /** The rates of the sheet's ancestors, nearest first: a grade with no rate in force falls back to them. */
  protected readonly inherited = signal<RateView[][]>([]);
  /** The sheet's periods by from-date. */
  protected readonly periods = signal<PeriodView[]>([]);
  /**
   * Each person's due-total for each period, in whole cents, by period id then person id. While the
   * period is open it is live: the sum of the due figures, as shown now, of the person's claims due by in
   * it that were OPEN, PENDING or SETTLED at the end of its last day, each at the grade in force on its
   * date (a claim with no figure adds nothing), leaving out those deferred (see countsInPeriod). Once closed or soft-closed it is the figure fixed then,
   * 0 for a person added since.
   */
  private readonly personPeriodCents = computed(() => {
    const figures = new Map<number, Map<number, number>>(
      this.periods().map((period) => [
        period.id,
        new Map(
          this.people().map((p) => [p.id, fixed(period) ? cents(period.personTotals[p.id] ?? '0.00') : 0]),
        ),
      ]),
    );
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const c of this.claims()) {
      const period = periodOn(this.periods(), c.dueBy);
      const person = byId.get(c.personId);
      if (!period || fixed(period) || !person || !countsInPeriod(c, person, this.today(), period)) continue;
      const row = figures.get(period.id)!;
      row.set(person.id, row.get(person.id)! + (this.dueCents(c, person) ?? 0));
    }
    return figures;
  });
  /**
   * Each person's carried figure for each period, in whole cents, by period id then person id: the sum
   * of the due figures, as shown now, of the person's claims that count which belong (by due-by) to a
   * period ending before this one starts and were still OPEN or PENDING at the end of its last day,
   * by the status log (see countsInPeriod), plus their opening balance for it. Once closed or
   * soft-closed it is the figure fixed then, 0 for a person added since.
   */
  private readonly personCarriedCents = computed(() => {
    const periods = this.periods();
    const frozen = (period: PeriodView) => fixed(period) && !!period.personCarried;
    const figures = new Map<number, Map<number, number>>(
      periods.map((period) => [
        period.id,
        new Map(
          this.people().map((p) => [
            p.id,
            cents((frozen(period) ? period.personCarried![p.id] : period.personOpenings?.[p.id]) ?? '0.00'),
          ]),
        ),
      ]),
    );
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const c of this.claims()) {
      const own = periodOn(periods, c.dueBy);
      const person = byId.get(c.personId);
      if (!own || !person) continue;
      for (const period of periods) {
        if (own.to >= period.from || frozen(period)) continue;
        const then = statusAt(c, period.to);
        if ((then !== 'OPEN' && then !== 'PENDING') || !countsInPeriod(c, person, this.today(), period)) continue;
        const row = figures.get(period.id)!;
        row.set(person.id, row.get(person.id)! + (this.dueCents(c, person) ?? 0));
      }
    }
    return figures;
  });
  /** Each period's due-total by period id: the sum of the people's figures for it. */
  protected readonly periodDueTotals = computed(
    () =>
      new Map(
        [...this.personPeriodCents()].map(([id, row]) => [id, money([...row.values()].reduce((a, b) => a + b, 0))]),
      ),
  );
  /**
   * Per grade: the people not retired who have it in force on the sheet's today, and their due-totals
   * added up, skipping those shown as n/a.
   */
  protected readonly gradeSummary = computed<GradeSummary[]>(() => {
    const current = this.people().filter((p) => p.retiredFrom === null);
    return GRADES.map((grade) => {
      const on = current.filter((p) => this.gradeOf(p) === grade);
      return {
        grade,
        count: on.length,
        active: on.filter((p) => this.activeOf(p)).length,
        dueTotal: money(on.reduce((sum, p) => sum + (this.dueTotalCents(p) ?? 0), 0)),
      };
    });
  });
  /**
   * Per period: its due-total as its row shows it, and its change from the period before it by start
   * date (empty for the earliest).
   */
  protected readonly periodSummary = computed<PeriodSummary[]>(() => {
    const totals = this.periodDueTotals();
    const dueTotalOf = (id: number) => totals.get(id) ?? '0.00';
    const byStart = [...this.periods()].sort((a, b) => a.from.localeCompare(b.from));
    return this.periods().map((p) => {
      const previous = byStart[byStart.indexOf(p) - 1];
      return {
        name: p.name,
        dueTotal: dueTotalOf(p.id),
        change: previous ? money(cents(dueTotalOf(p.id)) - cents(dueTotalOf(previous.id))) : '',
      };
    });
  });
  /**
   * Per period, the classic way: while open, the due figures, as shown now, of the claims due by in it that
   * count in the live totals by their status now (see countsLive); once closed or soft-closed, the figure
   * fixed then.
   */
  protected readonly classicPeriodSummary = computed<ClassicPeriodSummary[]>(() => {
    const totals = new Map<number, number>(
      this.periods().map((period) => [
        period.id,
        fixed(period) ? Object.values(period.personTotals).reduce((sum, t) => sum + cents(t), 0) : 0,
      ]),
    );
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const c of this.claims()) {
      const period = periodOn(this.periods(), c.dueBy);
      const person = byId.get(c.personId);
      if (!period || fixed(period) || !person || !countsLive(c, person, this.today())) continue;
      totals.set(period.id, totals.get(period.id)! + (this.dueCents(c, person) ?? 0));
    }
    return this.periods().map((p) => ({ name: p.name, dueTotal: money(totals.get(p.id) ?? 0) }));
  });
  /** Each person's due-total in whole cents as shown, by person id; null when shown as n/a. */
  protected readonly crewDueTotals = computed(
    () => new Map(this.people().map((p) => [p.id, this.dueTotalCents(p)] as [number, number | null])),
  );
  /** The sheet's crews, as the crews section reads them from the store. */
  protected readonly crews = signal<CrewView[]>([]);
  /** The due-totals of the crews the signed-in user's person leads, added up; empty when they lead none. */
  protected readonly myCrewDueTotal = computed(() => {
    const me = this.me();
    const led = me ? this.crews().filter((c) => c.leadId === me.id) : [];
    if (led.length === 0) return '';
    const dues = this.crewDueTotals();
    return money(led.reduce((sum, c) => sum + crewDueCents(c, dues), 0));
  });
  /** Everyone's due-total added up with no trimming, skipping those shown as n/a. */
  protected readonly classicDueTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + (this.dueTotalCents(p) ?? 0), 0)),
  );
  /** Per status: how many of the sheet's claims have it. */
  protected readonly statusSummary = computed<StatusSummary[]>(() =>
    SUMMARY_STATUSES.map((status) => ({
      status,
      count: this.claims().filter((c) => c.status === status).length,
    })),
  );
  /** How many of the sheet's REVIEW claims are late in review by the sheet's review SLA. */
  protected readonly reviewLateCount = computed(() =>
    this.claims().filter((c) => reviewLate(c, this.session.reviewSla(), this.session.today() ?? '')).length,
  );
  /** Per reason on the sheet's list: how many of the sheet's VOID claims were voided with it. */
  protected readonly voidReasonSummary = computed<VoidReasonSummary[]>(() =>
    this.voidReasons.list().map(({ reason }) => ({
      reason,
      count: this.claims().filter((c) => c.status === 'VOID' && c.voidReason === reason).length,
    })),
  );
  /** The sheet's cap in whole cents: a person's due-total is at most it; null when there is none. */
  private readonly capCents = computed(() => (this.session.cap() === '' ? null : cents(this.session.cap())));
  /** The sheet's today, on which each grade's rate in force is read. */
  /** The number of the sheet's last settle batch; null when there has been none. */
  protected readonly lastSettleBatch = computed(() => this.session.lastSettleBatch());
  protected readonly today = computed(() => this.session.today() ?? '');
  protected readonly claimedTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + this.claimedCents(p.id), 0)),
  );
  /**
   * Everyone's due-total added up, skipping those shown as n/a, and leaving out the highest and
   * lowest when three or more have one.
   */
  protected readonly dueTotal = computed(() =>
    money(
      trimmedCents(
        this.people()
          .map((p) => this.dueTotalCents(p))
          .filter((c): c is number => c !== null),
      ),
    ),
  );
  /** Everyone's settled-total added up (not trimmed). */
  protected readonly settledTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + this.settledCents(p), 0)),
  );
  /** The sheet's pools, which work out each person's cuts. */
  private readonly pools = viewChild.required(Pools);
  /** Each person's cut total in cents, by person id. */
  private readonly cutCents = computed(() => this.pools().personCuts());
  /** Everyone's cut total added up. */
  protected readonly cutTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + (this.cutCents().get(p.id) ?? 0), 0)),
  );
  /** Everyone's age buckets added up, bucket by bucket (not trimmed). */
  protected readonly agedTotal = computed(() => {
    const totals = [0, 0, 0];
    for (const p of this.people()) {
      this.agedCents(p).forEach((c, i) => (totals[i] += c));
    }
    return [money(totals[0]), money(totals[1]), money(totals[2])] as const;
  });
  /** Everyone's open-total added up (not trimmed). */
  protected readonly openTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + this.openCents(p), 0)),
  );
  /**
   * For every tag ever used on the sheet, sorted: the due of the claims that count in the live totals
   * (see countsLive) which carry it, or whose person carries it; a claim with two tags counts in both, and a
   * tag both the claim and its person carry counts it once (a claim with no figure adds nothing).
   */
  protected readonly tagTotals = computed(() => {
    const totals = new Map<string, number>();
    for (const c of this.claims()) for (const tag of c.tagsUsed ?? []) totals.set(tag, 0);
    for (const p of this.people()) for (const tag of p.tagsUsed ?? []) totals.set(tag, 0);
    for (const p of this.people()) {
      for (const c of this.countingOf(p.id)) {
        const due = this.dueCents(c, p) ?? 0;
        for (const tag of new Set([...(c.tags ?? []), ...(p.tags ?? [])])) totals.set(tag, (totals.get(tag) ?? 0) + due);
      }
    }
    return [...totals.keys()].sort().map((tag) => ({ tag, dueTotal: money(totals.get(tag)!) }));
  });
  /**
   * For every calendar month (yyyy-mm) any claim on the sheet is dated in, sorted: the due of the claims
   * dated in it that count in the live totals (see countsLive; a claim with no figure adds nothing).
   */
  protected readonly monthTotals = computed(() => {
    const totals = new Map<string, number>();
    for (const c of this.claims()) totals.set(c.date.slice(0, 7), 0);
    for (const p of this.people()) {
      for (const c of this.countingOf(p.id)) {
        const month = c.date.slice(0, 7);
        totals.set(month, (totals.get(month) ?? 0) + (this.dueCents(c, p) ?? 0));
      }
    }
    return [...totals.keys()].sort().map((month) => ({ month, dueTotal: money(totals.get(month)!) }));
  });
  /** How many overdue claims count in the live totals, across everyone. */
  protected readonly overdueCount = computed(() =>
    this.people().reduce((sum, p) => sum + this.overdueOf(p.id).length, 0),
  );
  /**
   * Everyone's overdue-total added up, leaving out the highest and lowest when there are three or
   * more people.
   */
  protected readonly overdueTotal = computed(() =>
    money(trimmedCents(this.people().map((p) => this.overdueCents(p)))),
  );
  /** The person on the sheet who goes by the signed-in user's name, by name or alias as names are compared; null when none does. */
  protected readonly me = computed(() => {
    const key = (name: string) => name.trim().toLowerCase();
    const user = this.session.user();
    if (user === null) return null;
    return (
      this.people().find((p) => key(p.name) === key(user) || (p.aliases ?? []).some((a) => key(a) === key(user))) ??
      null
    );
  });
  protected readonly name = signal('');
  protected readonly grade = signal<Grade>('A');
  /** The date the grade entered is in force from; the sheet's today unless another is typed. */
  protected readonly gradeFrom = signal('');
  /** The person's own terms as typed: a whole number of days, or blank for none. */
  protected readonly terms = signal('');
  /** The date the terms entered are in force from; blank for the beginning of time. */
  protected readonly termsFrom = signal('');
  /** The terms an edit just saved, shown on its row until the person is re-read. */
  private readonly savedTerms = new WeakMap<PersonView, number | null>();
  /** The person's contact as typed: blank for none. */
  protected readonly contact = signal('');
  /** The person's tags as typed, comma separated: blank for none. */
  protected readonly tags = signal('');
  /** Whether the terms or the contact were taken up on the form since it was opened. */
  protected readonly termsGiven = signal(false);
  /** Whether the grade was chosen on the form since it was filled. */
  protected readonly gradeGiven = signal(false);
  protected readonly contactGiven = signal(false);
  /** The id of the person open in the form, or null when the form adds a new person. */
  protected readonly editing = signal<number | null>(null);
  /** The version of the person open in the form, as it was loaded. */
  private readonly version = signal(0);
  protected readonly error = signal<string | null>(null);
  /** The refusal of the last action taken on a person's row, shown on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  /** The person chosen in each row's patron list, by person id. */
  protected readonly patronChoices = new Map<number, string>();
  /** The person chosen in each row's merge-into list, by person id. */
  protected readonly mergeChoices = new Map<number, string>();
  /** The sheet id typed in each row's transfer-to entry, by person id. */
  protected readonly transferTargets = new Map<number, string>();
  /** The ids of the people the signed-in user watches. */
  protected readonly watched = signal<ReadonlySet<number>>(new Set());
  /** The alias typed on each row, by person id. */
  protected readonly aliasDrafts = new Map<number, string>();

  /** The refusal of the last patron added on a person's row, shown on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;
  /** The ids of the claims ticked for a bulk action. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** How many claims the last bulk open opened and refused; null until one is done. */
  protected readonly bulkOpened = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal<string | null>(null);
  /** How many claims the last bulk settle settled (or sent PENDING) and refused; null until one is done. */
  protected readonly bulkSettled = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal<string | null>(null);
  /** The id of the person chosen to reassign the selected claims to; empty until one is chosen. */
  protected readonly bulkReassignTo = signal('');
  /** How many claims the last bulk reassign reassigned and refused; null until one is done. */
  protected readonly bulkReassigned = signal<{ reassigned: number; refused: number } | null>(null);
  protected readonly bulkReassignError = signal<string | null>(null);
  /** The date to re-date the selected claims to; empty until one is entered. */
  protected readonly bulkRedateDate = signal('');
  /** How many claims the last bulk re-date re-dated and refused; null until one is done. */
  protected readonly bulkRedated = signal<{ redated: number; refused: number } | null>(null);
  protected readonly bulkRedateError = signal<string | null>(null);
  /** The void reason chosen for the selected claims; empty for none. */
  protected readonly bulkVoidReason = signal('');
  /** How many claims the last bulk void voided and refused; null until one is done. */
  protected readonly bulkVoided = signal<{ voided: number; refused: number } | null>(null);
  protected readonly bulkVoidError = signal<string | null>(null);
  /** The ids of the people ticked to be given a grade together. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  /** The grade to give the selected people. */
  protected readonly bulkGrade = signal<Grade>('A');
  /** The date the selected people's grade is in force from; the sheet's today unless another is typed. */
  protected readonly bulkGradeFrom = linkedSignal(() => this.today());
  /** How many people the last bulk grade changed; null until one is done. */
  protected readonly bulkGraded = signal<number | null>(null);
  protected readonly bulkGradeError = signal<string | null>(null);
  /** The date the selected people are retired from; the sheet's today unless another is typed. */
  protected readonly bulkRetireDate = linkedSignal(() => this.today());
  /** How many people the last bulk retire retired; null until one is done. */
  protected readonly bulkRetired = signal<number | null>(null);
  protected readonly bulkRetireError = signal<string | null>(null);

  constructor() {
    // A new parent changes the rates a grade with none in force on the sheet falls back to.
    effect(() => {
      this.session.parent();
      untracked(() => {
        const sheet = this.session.sheet();
        if (sheet) void this.reloadInherited(sheet);
      });
    });
    // Every sheet load re-reads the sheet's people from the store.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      untracked(() => {
        this.people.set([]);
        this.myRole.set(null);
        this.snapshotLines.set([]);
        this.watched.set(new Set());
        this.claims.set([]);
        this.rates.set([]);
        this.inherited.set([]);
        this.periods.set([]);
        this.reset();
        this.personError.set(null);
        this.retireDates.clear();
        this.holdDrafts.set(new Map());
        this.flagDrafts.set(new Map());
        this.approverDrafts.set(new Map());
        this.patronError.set(null);
        this.patronChoices.clear();
        this.mergeChoices.clear();
        this.transferTargets.clear();
        this.aliasDrafts.clear();
        this.selectedClaims.set(new Set());
        this.bulkOpened.set(null);
        this.bulkOpenError.set(null);
        this.bulkSettled.set(null);
        this.bulkSettleError.set(null);
        this.bulkReassignTo.set('');
        this.bulkReassigned.set(null);
        this.bulkReassignError.set(null);
        this.bulkRedateDate.set('');
        this.bulkRedated.set(null);
        this.bulkRedateError.set(null);
        this.bulkVoidReason.set('');
        this.bulkVoided.set(null);
        this.bulkVoidError.set(null);
        this.selectedPeople.set(new Set());
        this.bulkGraded.set(null);
        this.bulkGradeError.set(null);
        this.bulkGradeFrom.set(this.today());
        this.bulkRetired.set(null);
        this.bulkRetireError.set(null);
        this.bulkRetireDate.set(this.today());
        if (sheet) void this.reload(sheet);
      });
    });
    // A change to the sheet's today may close periods past their grace days: re-read them.
    let shownToday: string | null = null;
    effect(() => {
      const today = this.session.today();
      untracked(() => {
        const sheet = this.session.sheet();
        const changed = shownToday !== null && today !== shownToday;
        shownToday = today;
        if (sheet && changed) void this.todayChanged(sheet);
      });
    });
  }

  /** Opening balances were imported: re-read the periods, which hold them. */
  protected openingsImported(): void {
    const sheet = this.session.sheet();
    if (sheet) void this.todayChanged(sheet);
  }

  /** The sheet's today changed: re-read its periods, some of which may have closed. */
  private async todayChanged(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const periods = await this.periodsApi.list(sheet);
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.periods.set(periods);
  }

  protected claimsOf(personId: number): ClaimView[] {
    return this.byPerson().get(personId) ?? [];
  }

  /** A person's claims that count in the live totals (see countsLive). */
  private countingOf(personId: number): ClaimView[] {
    const person = this.people().find((p) => p.id === personId);
    return person ? this.claimsOf(personId).filter((c) => countsLive(c, person, this.today())) : [];
  }

  /**
   * What a person has claimed: the sum of their OPEN and SETTLED claims' amounts (see countsLive),
   * held ones included, leaving out claims that correct another.
   */
  protected claimed(personId: number): string {
    return money(this.claimedCents(personId));
  }

  private claimedCents(personId: number): number {
    const person = this.people().find((p) => p.id === personId);
    if (!person) return 0;
    return this.claimsOf(personId)
      .filter((c) => countsLive(c, person, this.today()) && !c.corrects)
      .reduce((sum, c) => sum + cents(c.amount), 0);
  }

  /** Whether a person is active on the sheet's today (see activeOn). */
  protected activeOf(person: PersonView): boolean {
    return activeOn(this.claimsOf(person.id), person, this.today());
  }

  /** A person's overdue claims that count in the live totals (see countsLive), each past its grace. */
  protected overdueOf(personId: number): ClaimView[] {
    const person = this.people().find((p) => p.id === personId);
    return person ? this.countingOf(personId).filter((c) => overdue(c, this.today(), this.graceOf(c, person))) : [];
  }

  /** The grace days of a claim: those the grade its person has in force on its date has in force then. */
  private graceOf(claim: ClaimView, person: PersonView): number {
    return graceOn(this.session.graceHistory(), gradeOn(person.grades, claim.date), claim.date);
  }

  /** The due of a person's counting overdue claims added up (a claim with no figure adds nothing). */
  protected overdueTotalOf(person: PersonView): string {
    return money(this.overdueCents(person));
  }

  private overdueCents(person: PersonView): number {
    return this.overdueOf(person.id).reduce(
      (sum, c) => sum + (this.dueCents(c, person) ?? 0),
      0,
    );
  }

  /**
   * The due of a person's counting OPEN and PENDING claims split by their age on the sheet's today
   * (days from the claim's date): 0 to 30, 31 to 60 and 61 or more. A claim dated after today is 0 to 30.
   */
  protected agedOf(person: PersonView): [string, string, string] {
    const [young, middle, old] = this.agedCents(person);
    return [money(young), money(middle), money(old)];
  }

  /** A person's age buckets (see agedOf) in whole cents. */
  private agedCents(person: PersonView): [number, number, number] {
    const buckets: [number, number, number] = [0, 0, 0];
    const today = Date.parse(this.today());
    for (const c of this.countingOf(person.id).filter(isOpen)) {
      const days = Number.isNaN(today) ? 0 : (today - Date.parse(c.date)) / 86_400_000;
      buckets[days <= 30 ? 0 : days <= 60 ? 1 : 2] += this.dueCents(c, person) ?? 0;
    }
    return buckets;
  }

  /** The sort key chosen in the sort select. */
  protected sortKeyOf(event: Event): SortKey {
    const value = (event.target as HTMLSelectElement).value;
    return (SORT_KEYS as readonly string[]).includes(value) ? (value as SortKey) : 'added';
  }

  /**
   * The own terms a person's row shows: those in force on the sheet's today, or those an edit just saved
   * (from whatever date) until the person is re-read.
   */
  protected termsShown(person: PersonView): number | null {
    return this.savedTerms.has(person) ? (this.savedTerms.get(person) ?? null) : termsOn(person, this.today());
  }

  /** Terms as the form shows them: blank for none. */
  private termsText(terms: number | null): string {
    return terms === null ? '' : String(terms);
  }

  /** A person's name as the screen shows it: with the grade in force on the sheet's today. */
  protected shownName(person: PersonView): string {
    return `${person.name} (${this.gradeOf(person)})`;
  }

  /** The grade a person has in force on the sheet's today. */
  protected gradeOf(person: PersonView): Grade {
    return gradeOn(person.grades, this.today());
  }

  /** The rate a grade has in force on the sheet's today (a two-decimal percent), or null when it has none. */
  protected pctOf(grade: Grade): string | null {
    return rateOn(this.rates(), grade, this.today(), this.inherited());
  }

  /** A person's due-total: the sum of their counting claims' due figures, or n/a (see dueTotalCents). */
  /**
   * Compare two snapshot dates: per person in list order, their due-total kept by the snapshot taken as
   * the sheet's today moved on from the from-date, and from the to-date (their due-total now when the
   * to-date is blank); empty where no snapshot was kept for that date.
   */
  protected async compareSnapshots(event: Event): Promise<void> {
    event.preventDefault();
    const sheet = this.session.sheet();
    if (!sheet) return;
    const from = this.snapshotFrom();
    const to = this.snapshotTo();
    // Snapshot lines are kept as the sheet's today moves on: re-read them from the store.
    const kept = new Map((await this.api.list(sheet)).map((p) => [p.id, p.snapshots ?? []]));
    if (this.session.sheet() !== sheet) return;
    const keptOn = (p: PersonView, day: string) =>
      (kept.get(p.id) ?? []).filter((s) => s.asOf === day).at(-1)?.dueTotal ?? '';
    this.snapshotLines.set(
      this.people().map((p) => `${p.name} ${keptOn(p, from)} ${to === '' ? this.dueTotalOf(p) : keptOn(p, to)}`),
    );
  }

  protected dueTotalOf(person: PersonView): string {
    const total = this.dueTotalCents(person);
    return total === null ? NO_RATE : money(total);
  }

  /**
   * A person's due-total in whole cents, at most the sheet's cap: the sum of the due figures of their OPEN and SETTLED claims
   * (0 with none; see countsLive). Null (n/a) when the grade they have in force on the sheet's today has no
   * rate, unless a claim has a figure (a fixed one, or one at the grade in force on its own date): then it
   * is the sum of the figures there are.
   */
  private dueTotalCents(person: PersonView): number | null {
    const sum = this.dueSumCents(person);
    const cap = this.capCents();
    return sum !== null && cap !== null && sum > cap ? cap : sum;
  }

  /** A person's settled-total: the due of their counting SETTLED claims at their frozen figures. */
  protected settledTotalOf(person: PersonView): string {
    return money(this.settledCents(person));
  }

  /**
   * A person's score: the due of their counting SETTLED claims that correct nothing over 100, less 5
   * for each overdue claim of theirs that counts, rounded half to even to a whole number.
   */
  protected scoreOf(person: PersonView): string {
    return String(halfEven(this.scoreSettledCents(person) - 50000 * this.overdueOf(person.id).length, 10000));
  }

  /**
   * A person's band while both score thresholds are set: HIGH at or above the high one, MID at or
   * above the mid one, else LOW; empty while either is blank.
   */
  protected bandOf(person: PersonView): string {
    const mid = this.session.scoreMid();
    const high = this.session.scoreHigh();
    if (mid === '' || high === '') return '';
    const score = Number(this.scoreOf(person));
    if (score >= Number(high)) return 'HIGH';
    return score >= Number(mid) ? 'MID' : 'LOW';
  }

  /** A person's open-total: the due of their counting OPEN and PENDING claims (see countsLive). */
  protected openTotalOf(person: PersonView): string {
    return money(this.openCents(person));
  }

  /** A person's cut total: their cuts across every pool they are a member of, added up. */
  protected cutTotalOf(person: PersonView): string {
    return money(this.cutCents().get(person.id) ?? 0);
  }

  private settledCents(person: PersonView): number {
    return this.countingOf(person.id)
      .filter((c) => c.status === 'SETTLED')
      .reduce((sum, c) => sum + (this.dueCents(c, person) ?? 0), 0);
  }

  /** The due of a person's counting SETTLED claims that correct nothing, which makes their score. */
  private scoreSettledCents(person: PersonView): number {
    return this.countingOf(person.id)
      .filter((c) => c.status === 'SETTLED' && !c.corrects)
      .reduce((sum, c) => sum + (this.dueCents(c, person) ?? 0), 0);
  }

  private openCents(person: PersonView): number {
    return this.countingOf(person.id)
      .filter(isOpen)
      .reduce((sum, c) => sum + (this.dueCents(c, person) ?? 0), 0);
  }

  /** A person's due-total for a period (see personPeriodCents). */
  protected periodTotalOf(period: PeriodView, person: PersonView): string {
    return money(this.personPeriodCents().get(period.id)?.get(person.id) ?? 0);
  }

  /** A person's carried figure for a period (see personCarriedCents). */
  protected carriedOf(period: PeriodView, person: PersonView): string {
    return money(this.personCarriedCents().get(period.id)?.get(person.id) ?? 0);
  }

  /** A person's excess: by how much the sum of their figures is above the cap, else 0.00 (n/a is not capped). */
  protected excessOf(person: PersonView): string {
    const sum = this.dueSumCents(person);
    const cap = this.capCents();
    return money(sum !== null && cap !== null && sum > cap ? sum - cap : 0);
  }

  /** The sum of a person's due figures in whole cents before the cap, or null (n/a; see dueTotalCents). */
  private dueSumCents(person: PersonView): number | null {
    const dues = this.countingOf(person.id).map((c) => this.dueCents(c, person));
    if (this.pctOf(this.gradeOf(person)) === null && dues.every((d) => d === null)) return null;
    return dues.reduce<number>((sum, d) => sum + (d ?? 0), 0);
  }

  /**
   * A claim's due in whole cents at the grade its person has in force on its date, with the floor
   * in force on its date and the late percent on each 30-day boundary (see claimDueCents).
   */
  private dueCents(claim: ClaimView, person: PersonView): number | null {
    const halfUp = roundsHalfUp(claim, this.claimsOf(person.id), this.session.policyDate(), this.today(), this.session.roundingHistory());
    const floor = cents(valueOn(this.session.floorHistory(), claim.date, '1.00'));
    const late = { history: this.session.latePctHistory(), today: this.today(), grace: this.graceOf(claim, person) };
    return claimDueCents(claim, this.rates(), gradeOn(person.grades, claim.date), floor, late, halfUp, this.inherited());
  }

  /** A person's patrons, each as their name and share, sorted by name, comma and space separated. */
  protected patronsOf(person: PersonView): string {
    return this.patronShares(person)
      .map(({ patron, share }) => ({ name: patron.name, text: `${this.shownName(patron)} ${money(share)}` }))
      .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
      .map((p) => p.text)
      .join(', ');
  }

  /**
   * A person's backing: over their patrons, the settled figures of the patron's SETTLED claims dated
   * within the 90 days up to and including the sheet's today, each at its frozen figure (one with none
   * adds nothing), times the patron's share over 100, rounded half to even.
   */
  protected backingOf(person: PersonView): string {
    return money(
      this.patronShares(person).reduce((sum, { patron, share }) => sum + shareOf(this.recentSettledCents(patron.id), share), 0),
    );
  }

  private recentSettledCents(personId: number): number {
    const today = this.today();
    if (today === '') return 0;
    const since = new Date(Date.parse(today) - 89 * 86_400_000).toISOString().slice(0, 10);
    return this.claimsOf(personId)
      .filter((c) => c.status === 'SETTLED' && since <= c.date && c.date <= today)
      .reduce((sum, c) => sum + (c.settledDue === null ? 0 : cents(c.settledDue)), 0);
  }

  /**
   * A person's patrons in the order they were named, each with their share in hundredths of a percent:
   * 100.00 split equally, each rounded half to even, the remainder to the first named.
   */
  private patronShares(person: PersonView): { patron: PersonView; share: number }[] {
    const patrons = person.patronIds
      .map((id) => this.people().find((p) => p.id === id))
      .filter((p): p is PersonView => p !== undefined);
    if (patrons.length === 0) return [];
    const shares = patrons.map((patron) => ({ patron, share: halfEven(10000, patrons.length) }));
    shares[0].share += 10000 - shares.reduce((sum, s) => sum + s.share, 0);
    return shares;
  }

  /** A person's aliases, sorted as names are compared, comma and space separated. */
  protected aliasesOf(person: PersonView): string {
    const key = (name: string) => name.trim().toLowerCase();
    return [...(person.aliases ?? [])]
      .sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : a < b ? -1 : a > b ? 1 : 0))
      .join(', ');
  }

  /** Give a person the alias typed on their row; a blank one, or one already in use, is refused. */
  protected async addAlias(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const alias = (this.aliasDrafts.get(person.id) ?? '').trim();
    const refuse = (message: string) => this.personError.set({ id: person.id, message });
    if (!alias) {
      refuse('Type an alias to add.');
      return;
    }
    try {
      const saved = await this.api.addAlias(sheet, person.id, alias);
      if (this.session.sheet() !== sheet) return;
      this.aliasDrafts.delete(person.id);
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
    } catch {
      refuse('That alias could not be added: it may already be in use on this sheet.');
    }
  }

  /** Name the person chosen on a person's row as their patron. */
  protected async addPatron(person: PersonView): Promise<void> {
    this.patronError.set(null);
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const patronId = Number(this.patronChoices.get(person.id) ?? '');
    const refuse = (message: string) => this.patronError.set({ id: person.id, message });
    if (!patronId) {
      refuse('Choose a person to add as a patron.');
      return;
    }
    if (patronId === person.id) {
      refuse('A person cannot be their own patron.');
      return;
    }
    if (person.patronIds.includes(patronId)) {
      refuse('That person is already a patron.');
      return;
    }
    try {
      const saved = await this.api.addPatron(sheet, person.id, patronId);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
    } catch {
      refuse('The patron could not be added.');
    }
  }

  /**
   * Merge a person into the one chosen on their row (a steward only): they leave the list, and the
   * people and claims show what the store holds after it.
   */
  protected async merge(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const intoId = Number(this.mergeChoices.get(person.id) ?? '');
    const refuse = (message: string) => this.personError.set({ id: person.id, message });
    if (!intoId) {
      refuse('Choose a person to merge into.');
      return;
    }
    if (intoId === person.id) {
      refuse('A person cannot be merged into themselves.');
      return;
    }
    try {
      await this.api.merge(sheet, person.id, intoId);
      const [people, claims] = await Promise.all([this.api.list(sheet), this.claimsApi.list(sheet)]);
      if (this.session.sheet() !== sheet) return;
      this.mergeChoices.delete(person.id);
      this.claims.set(claims);
      this.people.set(people);
    } catch (e) {
      refuse(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward may merge people.'
          : 'The person could not be merged: neither may be retired.',
      );
    }
  }

  /**
   * Transfer a person to the sheet typed on their row (a steward only): they leave the list, and the
   * people and claims show what the store holds after it.
   */
  protected async transfer(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const toSheetId = (this.transferTargets.get(person.id) ?? '').trim();
    const refuse = (message: string) => this.personError.set({ id: person.id, message });
    if (!toSheetId) {
      refuse('Type the id of the sheet to transfer to.');
      return;
    }
    if (toSheetId === sheet) {
      refuse('A person cannot be transferred to their own sheet.');
      return;
    }
    try {
      await this.api.transfer(sheet, person.id, toSheetId);
      const [people, claims] = await Promise.all([this.api.list(sheet), this.claimsApi.list(sheet)]);
      if (this.session.sheet() !== sheet) return;
      this.transferTargets.delete(person.id);
      this.claims.set(claims);
      this.people.set(people);
    } catch (e) {
      refuse(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward may transfer people.'
          : 'The person could not be transferred: they may not be retired, and their name, aliases and contact must be free on the other sheet.',
      );
    }
  }

  /**
   * Undo the transfer that brought a person to this sheet today (a steward only): they go back to the
   * sheet they came from and leave the list, and the people and claims show what the store holds after it.
   */
  protected async transferUndo(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      await this.api.transferUndo(sheet, person.id);
      const [people, claims] = await Promise.all([this.api.list(sheet), this.claimsApi.list(sheet)]);
      if (this.session.sheet() !== sheet) return;
      this.claims.set(claims);
      this.people.set(people);
    } catch (e) {
      this.personError.set({
        id: person.id,
        message:
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Only a steward may undo a transfer.'
            : 'The transfer could not be undone: only one that brought the person here today can be, by the transfer rules.',
      });
    }
  }

  /** The last statement's due-total: n/a when it was, empty when none was taken. */
  protected statedTotalOf(person: PersonView): string {
    if (person.statementCount === 0) return '';
    return person.statementTotal ?? NO_RATE;
  }

  /**
   * Take a statement for a person, as of the date typed beside the button (blank: now): the row and
   * their claims show what the store holds after it, and the date is cleared for the next one.
   */
  protected async statement(person: PersonView, asOfInput: HTMLInputElement): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const asOf = asOfInput.value.trim() || null;
    try {
      const stated = await this.api.statement(sheet, person.id, asOf);
      if (this.session.sheet() !== sheet) return;
      asOfInput.value = '';
      this.people.update((list) => list.map((p) => (p.id === stated.person.id ? stated.person : p)));
      const byId = new Map(stated.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
    } catch {
      asOfInput.value = '';
      this.personError.set({ id: person.id, message: 'The statement could not be taken.' });
    }
  }

  /** Cancel a person's last statement: the row and their claims show what the store holds after it. */
  protected async unstate(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      const cancelled = await this.api.unstate(sheet, person.id);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === cancelled.person.id ? cancelled.person : p)));
      const byId = new Map(cancelled.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
    } catch {
      this.personError.set({ id: person.id, message: 'The statement could not be cancelled.' });
    }
  }

  /**
   * Swap a person with the one above (up) or below (down) them in the list; the top person cannot move
   * up nor the bottom one down. The list then shows the order the store holds.
   */
  protected async move(person: PersonView, direction: 'up' | 'down'): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      await this.api.move(sheet, person.id, direction);
      const people = await this.api.list(sheet);
      if (this.session.sheet() !== sheet) return;
      this.people.set(people);
    } catch {
      this.personError.set({
        id: person.id,
        message: direction === 'up' ? 'The top person cannot move up.' : 'The bottom person cannot move down.',
      });
    }
  }

  /** Record the grade chosen on a person's row from the sheet's today, as the person form would. */
  protected async saveInlineGrade(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const grade = this.inlineGrades.get(person.id) ?? this.gradeOf(person);
    try {
      const saved = await this.api.grade(sheet, person.id, grade, person.version);
      if (this.session.sheet() !== sheet) return;
      this.inlineGrades.delete(person.id);
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
    } catch {
      this.personError.set({ id: person.id, message: 'The grade could not be saved.' });
    }
  }

  /** Retire a person from the date typed on their row, or from the sheet's today with none. */
  protected async retire(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const from = this.retireDates.get(person.id)?.trim() || null;
    try {
      const retired = await this.api.retire(sheet, person.id, from);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === retired.id ? retired : p)));
    } catch {
      this.personError.set({ id: person.id, message: 'The person could not be retired.' });
    }
  }

  /** Unretire a retired person on the sheet's today. */
  protected async unretire(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      const unretired = await this.api.unretire(sheet, person.id);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === unretired.id ? unretired : p)));
    } catch {
      this.personError.set({ id: person.id, message: 'The person could not be unretired.' });
    }
  }

  /** The hold dates typed on a person's row. */
  protected holdDraft(personId: number): { from: string; to: string } {
    return this.holdDrafts().get(personId) ?? { from: '', to: '' };
  }

  /** Change the hold dates typed on a person's row. */
  protected setHoldDraft(personId: number, change: { from?: string; to?: string }): void {
    this.holdDrafts.update((map) => new Map(map).set(personId, { ...this.holdDraft(personId), ...change }));
    this.personError.set(null);
  }

  /** Put a person on hold from the date typed (none: the sheet's today) to the date typed (none: no end). */
  protected async hold(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const draft = this.holdDraft(person.id);
    const from = draft.from.trim() || null;
    const to = draft.to.trim() || null;
    if (from !== null && to !== null && to < from) {
      this.personError.set({ id: person.id, message: 'A hold cannot end before it starts.' });
      return;
    }
    try {
      const saved = await this.api.hold(sheet, person.id, from, to);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.holdDrafts.update((map) => {
        const next = new Map(map);
        next.delete(person.id);
        return next;
      });
    } catch {
      this.personError.set({ id: person.id, message: 'The person could not be put on hold.' });
    }
  }

  /** The reason a person's flag shows on the sheet's today: blank unless it is in force. */
  protected flagShown(person: PersonView): string {
    return flagOn(person, this.session.today() ?? '');
  }

  /** The flag reason and until-date typed on a person's row. */
  protected flagDraft(personId: number): { reason: string; until: string } {
    return this.flagDrafts().get(personId) ?? { reason: '', until: '' };
  }

  /** Change the flag reason or until-date typed on a person's row. */
  protected setFlagDraft(personId: number, change: { reason?: string; until?: string }): void {
    this.flagDrafts.update((map) => new Map(map).set(personId, { ...this.flagDraft(personId), ...change }));
    this.personError.set(null);
  }

  /** Flag a person with the reason typed until the date typed; a steward only, and a reason is needed. */
  protected async flag(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const draft = this.flagDraft(person.id);
    const reason = draft.reason.trim();
    const until = draft.until.trim();
    if (reason === '') {
      this.personError.set({ id: person.id, message: 'A flag needs a reason.' });
      return;
    }
    if (until === '') {
      this.personError.set({ id: person.id, message: 'A flag needs an until date.' });
      return;
    }
    try {
      const saved = await this.api.flag(sheet, person.id, reason, until);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.flagDrafts.update((map) => {
        const next = new Map(map);
        next.delete(person.id);
        return next;
      });
    } catch {
      this.personError.set({ id: person.id, message: 'The person could not be flagged.' });
    }
  }

  /** Change the approver user name typed on a person's row. */
  protected setApproverDraft(personId: number, user: string): void {
    this.approverDrafts.update((map) => new Map(map).set(personId, user));
    this.personError.set(null);
  }

  /** Set the user name typed as the person's approver: they may approve or reject the person's PENDING claims. */
  protected async setApprover(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = (this.approverDrafts().get(person.id) ?? '').trim();
    try {
      const saved = await this.api.setApprover(sheet, person.id, user);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      this.approverDrafts.update((map) => {
        const next = new Map(map);
        next.delete(person.id);
        return next;
      });
    } catch {
      this.personError.set({ id: person.id, message: "The person's approver could not be set." });
    }
  }

  /** Start or stop watching a person as the signed-in user. */
  protected async toggleWatch(person: PersonView): Promise<void> {
    this.personError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const on = !this.watched().has(person.id);
    try {
      await this.api.watch(sheet, person.id, on);
      if (this.session.sheet() !== sheet) return;
      this.watched.update((set) => {
        const next = new Set(set);
        if (on) next.add(person.id);
        else next.delete(person.id);
        return next;
      });
    } catch {
      this.personError.set({
        id: person.id,
        message: on ? 'The person could not be watched.' : 'The person could not be unwatched.',
      });
    }
  }

  /**
   * Download the people as a CSV file: a header line, then one line per person in list order, retired
   * people included, with the grade in force on the sheet's today, the retired-from date or nothing, and
   * the due-total as shown. Each line ends with a line feed.
   */
  protected async exportCsv(): Promise<void> {
    await this.session.settled();
    const lines = [
      ['name', 'grade', 'retired-from', 'due-total'],
      ...this.people().map((p) => [p.name, this.gradeOf(p), p.retiredFrom ?? '', this.dueTotalOf(p)]),
    ];
    this.download(lines, 'people');
  }

  /**
   * Download the claims as a CSV file: a header line, then one line per claim, people in list order
   * (retired people included) and each person's claims oldest first, with the number, period and due
   * as shown (an empty number or period, or n/a, as it is). Each line ends with a line feed.
   */
  protected async exportClaims(): Promise<void> {
    await this.session.settled();
    const lines = [
      ['person', 'number', 'date', 'amount', 'due', 'status', 'period'],
      ...this.people().flatMap((p) =>
        [...this.claimsOf(p.id)].reverse().map((c) => {
          const due = this.dueCents(c, p);
          return [
            p.name,
            c.number ?? '',
            c.date,
            c.amount,
            due === null ? NO_RATE : money(due),
            c.status,
            periodOn(this.periods(), c.dueBy)?.name ?? '',
          ];
        }),
      ),
    ];
    this.download(lines, 'claims');
  }

  /**
   * Download a person's statement as a CSV file: a header line, then one line per claim of theirs bound
   * by their last statement, oldest first, with the number, the bound figure as the claim shows it (n/a
   * as it is) and the status. Each line ends with a line feed.
   */
  protected async exportStatement(person: PersonView): Promise<void> {
    await this.session.settled();
    this.personError.set(null);
    const lines = [
      ['number', 'date', 'amount', 'figure', 'status'],
      ...[...this.claimsOf(person.id)]
        .reverse()
        .filter((c) => c.stated)
        .map((c) => {
          const due = this.dueCents(c, person);
          return [c.number ?? '', c.date, c.amount, due === null ? NO_RATE : money(due), c.status];
        }),
    ];
    this.download(lines, 'statement');
  }

  private download(lines: string[][], name: string): void {
    downloadCsv(lines, name, this.session.sheet());
  }

  /** A rate set for a grade, in place of any it had from the same date. */
  protected rateSaved(rate: RateView): void {
    this.rates.update((list) => [...list.filter((r) => !sameSlot(r, rate)), rate]);
  }

  /** A period added, kept in from-date order. */
  protected periodSaved(period: PeriodView): void {
    this.periods.update((list) => [...list, period].sort((a, b) => a.from.localeCompare(b.from)));
  }

  /** A period closed or reopened, in its place. */
  protected periodChanged(period: PeriodView): void {
    this.periods.update((list) => list.map((p) => (p.id === period.id ? period : p)));
  }

  /** Tick (on) or clear a person's checkbox. */
  protected selectPerson(id: number, on: boolean): void {
    this.selectedPeople.update((set) => {
      const next = new Set(set);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
    this.bulkGraded.set(null);
    this.bulkGradeError.set(null);
    this.bulkRetired.set(null);
    this.bulkRetireError.set(null);
  }

  /**
   * Give the selected people the grade from the date together: each is edited as the person form
   * would, in list order; how many were changed is shown, and the selection cleared.
   */
  protected async gradeSelected(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.bulkGraded.set(null);
    this.bulkGradeError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const ids = [...this.selectedPeople()];
    if (ids.length === 0) {
      this.bulkGradeError.set('Select the people to grade.');
      return;
    }
    this.busy = true;
    try {
      const changed = await this.api.gradeAll(sheet, ids, this.bulkGrade(), this.bulkGradeFrom() || null);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => changed.find((c) => c.id === p.id) ?? p));
      this.selectedPeople.set(new Set());
      this.bulkGradeFrom.set(this.today());
      this.bulkGraded.set(changed.length);
    } catch {
      this.bulkGradeError.set('The people could not be graded.');
    } finally {
      this.busy = false;
    }
  }

  /**
   * Retire the selected people from the date together: each is retired as the row's retire would, in
   * list order; how many were retired is shown, and the selection cleared.
   */
  protected async retireSelected(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.bulkRetired.set(null);
    this.bulkRetireError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const ids = [...this.selectedPeople()];
    if (ids.length === 0) {
      this.bulkRetireError.set('Select the people to retire.');
      return;
    }
    this.busy = true;
    try {
      const retired = await this.api.retireAll(sheet, ids, this.bulkRetireDate() || null);
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((p) => retired.find((r) => r.id === p.id) ?? p));
      this.selectedPeople.set(new Set());
      this.bulkRetireDate.set(this.today());
      this.bulkRetired.set(retired.length);
    } catch {
      this.bulkRetireError.set('The people could not be retired.');
    } finally {
      this.busy = false;
    }
  }

  /** Tick (on) or clear a claim's checkbox. */
  protected selectClaim(id: number, on: boolean): void {
    this.selectedClaims.update((set) => {
      const next = new Set(set);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
    this.bulkOpenError.set(null);
    this.bulkSettleError.set(null);
    this.bulkReassigned.set(null);
    this.bulkReassignError.set(null);
    this.bulkRedated.set(null);
    this.bulkRedateError.set(null);
    this.bulkVoided.set(null);
    this.bulkVoidError.set(null);
  }

  /**
   * Open the selected claims together: each that can be opened is, numbered in list order, and each
   * that cannot is left as it is; how many were opened and refused is shown, and the selection cleared.
   */
  protected async openSelected(): Promise<void> {
    if (this.busy) return;
    this.bulkOpened.set(null);
    this.bulkOpenError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    if (ids.length === 0) {
      this.bulkOpenError.set('Select the claims to open.');
      return;
    }
    this.busy = true;
    try {
      const result = await this.claimsApi.openAll(sheet, ids);
      if (this.session.sheet() !== sheet) return;
      for (const claim of result.opened) this.claimSaved(claim);
      this.selectedClaims.set(new Set());
      this.bulkOpened.set({ opened: result.opened.length, refused: result.refused });
    } catch {
      this.bulkOpenError.set('The claims could not be opened.');
    } finally {
      this.busy = false;
    }
  }

  /**
   * Settle the selected claims together: each is settled (or sent PENDING) as its Settle button would,
   * in list order, and each that cannot be is left as it is; how many were settled and refused is
   * shown, and the selection cleared.
   */
  protected async settleSelected(): Promise<void> {
    if (this.busy) return;
    this.bulkSettled.set(null);
    this.bulkSettleError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    if (ids.length === 0) {
      this.bulkSettleError.set('Select the claims to settle.');
      return;
    }
    this.busy = true;
    try {
      const result = await this.claimsApi.settleAll(sheet, ids);
      if (this.session.sheet() !== sheet) return;
      for (const claim of result.settled) this.claimSaved(claim);
      this.selectedClaims.set(new Set());
      if (result.batch != null) this.session.lastSettleBatch.set(result.batch);
      this.bulkSettled.set({ settled: result.settled.length, refused: result.refused });
    } catch {
      this.bulkSettleError.set('The claims could not be settled.');
    } finally {
      this.busy = false;
    }
  }

  /**
   * Reassign the selected claims to the chosen person together: each is moved as its Reassign button
   * would, in list order, and each that cannot be is left as it is; how many were reassigned and
   * refused is shown, and the selection cleared.
   */
  protected async reassignSelected(): Promise<void> {
    if (this.busy) return;
    this.bulkReassigned.set(null);
    this.bulkReassignError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    if (ids.length === 0) {
      this.bulkReassignError.set('Select the claims to reassign.');
      return;
    }
    const to = this.bulkReassignTo();
    if (to === '') {
      this.bulkReassignError.set('Choose a person to reassign the claims to.');
      return;
    }
    this.busy = true;
    try {
      const result = await this.claimsApi.reassignAll(sheet, ids, Number(to));
      if (this.session.sheet() !== sheet) return;
      for (const claim of result.reassigned) this.claimSaved(claim);
      this.selectedClaims.set(new Set());
      this.bulkReassigned.set({ reassigned: result.reassigned.length, refused: result.refused });
    } catch {
      this.bulkReassignError.set('The claims could not be reassigned.');
    } finally {
      this.busy = false;
    }
  }

  /**
   * Re-date the selected claims to the entered date together: each is re-dated as its row's re-date
   * would, in list order, and each that cannot be is left as it is; how many were re-dated and refused
   * is shown, and the selection cleared.
   */
  protected async redateSelected(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.bulkRedated.set(null);
    this.bulkRedateError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    if (ids.length === 0) {
      this.bulkRedateError.set('Select the claims to re-date.');
      return;
    }
    const date = this.bulkRedateDate().trim();
    if (date === '') {
      this.bulkRedateError.set('Enter the date to re-date the claims to.');
      return;
    }
    this.busy = true;
    try {
      const result = await this.claimsApi.redateAll(sheet, ids, date);
      if (this.session.sheet() !== sheet) return;
      for (const claim of result.redated) this.claimSaved(claim);
      this.selectedClaims.set(new Set());
      this.bulkRedated.set({ redated: result.redated.length, refused: result.refused });
    } catch {
      this.bulkRedateError.set('The claims could not be re-dated.');
    } finally {
      this.busy = false;
    }
  }

  /**
   * Void the selected claims together with the chosen reason: each is voided as its row's void would,
   * in list order, and each that cannot be is left as it is; how many were voided and refused is
   * shown, and the selection cleared.
   */
  protected async voidSelected(): Promise<void> {
    if (this.busy) return;
    this.bulkVoided.set(null);
    this.bulkVoidError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const ids = [...this.selectedClaims()];
    if (ids.length === 0) {
      this.bulkVoidError.set('Select the claims to void.');
      return;
    }
    this.busy = true;
    try {
      const result = await this.claimsApi.voidAll(sheet, ids, this.bulkVoidReason());
      if (this.session.sheet() !== sheet) return;
      for (const claim of result.voided) this.claimSaved(claim);
      this.selectedClaims.set(new Set());
      this.bulkVoided.set({ voided: result.voided.length, refused: result.refused });
    } catch {
      this.bulkVoidError.set('The claims could not be voided.');
    } finally {
      this.busy = false;
    }
  }

  /** A claim added (listed first, as the newest) or saved or moved (in its place). */
  protected claimSaved(claim: ClaimView): void {
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id) ? list.map((c) => (c.id === claim.id ? claim : c)) : [claim, ...list],
    );
  }

  /** A claim quick-added: its person listed when they were added for it, the claim first as the newest. */
  protected quickAdded(added: QuickAddView): void {
    if (added.personAdded) this.people.update((list) => [...list, added.person]);
    this.claimSaved(added.claim);
  }

  /** People imported: those updated replaced where they stand, those added each listed last in turn. */
  protected imported(imported: ImportView): void {
    this.people.update((list) => [
      ...list.map((p) => imported.updated.find((u) => u.id === p.id) ?? p),
      ...imported.added,
    ]);
  }

  /** Claims pasted or made by the plans, in the order added: each listed first in turn, so the last is the newest. */
  protected pasted(claims: ClaimView[]): void {
    for (const claim of claims) this.claimSaved(claim);
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Switch a column of the people list on or off on this user's screen. */
  protected showColumn(column: PeopleColumn, shown: boolean): void {
    void this.columns.set(column, shown);
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected gradeChosen(event: Event): Grade {
    return (event.target as HTMLSelectElement).value as Grade;
  }

  /**
   * Open a person's editor: the form takes their values as the store holds them now, so it edits from
   * their current version. The form stays empty until they are read.
   */
  protected async open(person: PersonView): Promise<void> {
    this.personError.set(null);
    this.editing.set(person.id);
    this.name.set('');
    this.error.set(null);
    const sheet = this.session.sheet();
    let current = person;
    if (sheet) {
      try {
        current = (await this.api.list(sheet)).find((p) => p.id === person.id) ?? person;
      } catch {
        // the editor takes the values shown
      }
      if (this.session.sheet() !== sheet || this.editing() !== person.id) return;
      this.people.update((list) => list.map((p) => (p.id === current.id ? current : p)));
    }
    this.fill(current);
  }

  /** The form takes the person's values. */
  private fill(person: PersonView): void {
    this.version.set(person.version);
    this.name.set(person.name);
    this.grade.set(this.gradeOf(person));
    this.gradeFrom.set(this.today());
    this.terms.set(this.termsText(termsOn(person, this.today())));
    this.termsFrom.set('');
    this.contact.set(person.contact ?? '');
    this.tags.set((person.tags ?? []).join(', '));
    this.termsGiven.set(false);
    this.gradeGiven.set(false);
    this.contactGiven.set(false);
    this.error.set(null);
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set(this.today());
    this.terms.set('');
    this.termsFrom.set('');
    this.contact.set('');
    this.tags.set('');
    this.termsGiven.set(false);
    this.gradeGiven.set(false);
    this.contactGiven.set(false);
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const typedTerms = this.terms().trim();
    if (typedTerms !== '' && !/^\d{1,5}$/.test(typedTerms)) {
      // Nothing is saved: an edit is closed, leaving the person as they were, and the form adds again.
      if (this.editing() !== null) this.reset();
      this.error.set('Terms are a whole number of days, or blank for none.');
      return;
    }
    const form = { name: this.name(), grade: this.grade(), terms: typedTerms === '' ? null : Number(typedTerms),
      contact: this.contact(), tags: this.tags(), termsGiven: this.termsGiven(), contactGiven: this.contactGiven(), gradeGiven: this.gradeGiven() };
    if (!form.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    const id = this.editing();
    const contactKey = form.contact.trim().toLowerCase();
    if (contactKey !== '' && this.people().some((p) => p.id !== id && p.contact === contactKey)) {
      // As when the store refuses it: a refused add leaves nothing typed behind.
      if (id === null) this.reset();
      this.error.set('That contact is already in use on this sheet.');
      return;
    }
    this.busy = true;
    try {
      if (id === null) {
        const added = await this.api.add(sheet, form);
        this.people.update((list) => [...list, added]);
      } else {
        const gradeFrom = this.gradeFrom().trim() || null;
        const termsFrom = this.termsFrom().trim() || null;
        // Terms left as they were keep the person's own from the beginning of time.
        const terms = form.termsGiven || termsFrom !== null
          ? form.terms : (this.people().find((p) => p.id === id)?.terms ?? null);
        const saved = await this.api.edit(sheet, id, { ...form, terms, termsFrom, gradeFrom, version: this.version() });
        if (form.termsGiven) this.savedTerms.set(saved, terms);
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      // A refused add leaves nothing typed behind for the next person added.
      if (id === null) this.reset();
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(sheet, id);
        this.error.set('Someone else has saved this person since you opened it. The form shows their values now.');
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.error.set('That name or contact is already in use on this sheet or its tree.');
      } else {
        this.error.set(id === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    } finally {
      this.busy = false;
    }
  }

  /** Someone else saved the person open in the form: show what the store holds for them now. */
  private async showCurrent(sheet: string, id: number): Promise<void> {
    try {
      const current = (await this.api.list(sheet)).find((p) => p.id === id);
      if (!current || this.session.sheet() !== sheet || this.editing() !== id) return;
      this.people.update((list) => list.map((p) => (p.id === id ? current : p)));
      this.version.set(current.version);
      this.name.set(current.name);
      this.grade.set(this.gradeOf(current));
      this.terms.set(this.termsText(termsOn(current, this.today())));
      this.contact.set(current.contact ?? '');
      this.tags.set((current.tags ?? []).join(', '));
    } catch {
      // the form keeps what was typed; the refusal is still shown
    }
  }

  /** Re-read the rates of the sheet's ancestors from the store. */
  private async reloadInherited(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const inherited = await this.ratesApi.inherited(sheet);
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.inherited.set(inherited);
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    try {
      const [people, watched, claims, rates, inherited, periods, mine] = await Promise.all([
        this.api.list(sheet),
        this.api.watched(sheet),
        this.claimsApi.list(sheet),
        this.ratesApi.list(sheet),
        this.ratesApi.inherited(sheet),
        this.periodsApi.list(sheet),
        this.settingsApi.myRole(sheet),
      ]);
      // A later load may have started while this one was in flight: only the latest is shown.
      if (this.session.sheet() === sheet && this.session.loads() === loads) {
        this.claims.set(claims);
        this.rates.set(rates);
        this.inherited.set(inherited);
        this.periods.set(periods);
        this.watched.set(new Set(watched));
        this.myRole.set(mine.role);
        this.people.set(people);
      }
    } finally {
      // The sheet shows as loaded once its people and claims are on screen (or could not be read).
      if (this.session.loads() === loads) this.session.loaded.set(loads);
    }
  }
}
