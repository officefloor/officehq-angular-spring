import { Component, computed, inject, signal } from '@angular/core';
import { Claim, Claims } from '../../core/claims';
import { download } from '../../core/csv';
import { Figures } from '../../core/figures';
import { GRADES, People, Person } from '../../core/people';
import { Periods } from '../../core/periods';
import { Pools } from '../../core/pools';
import { Crews } from '../../core/crews';
import { Session } from '../../core/session';
import { COLUMNS, ColumnKey, UserColumns } from '../../core/user-columns';
import { NO_RATE, halfEven, money } from '../../core/rates';
import { PersonClaims } from '../claims/claims';
import { PersonPlans } from '../plans/person-plans';

const SORT_KEYS = ['added', 'name', 'grade', 'due-total'] as const;
type SortKey = (typeof SORT_KEYS)[number];

/** A name as names are compared: trimmed, ignoring case. */
function nameKey(name: string): string {
  return name.trim().toLowerCase();
}

function compareKeys(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

// The people on the sheet: one form adds a person, or edits the one whose editor is open, and the
// list shows everyone in the order they were added (or sorted on this screen), each with their claims.
@Component({
  selector: 'app-people',
  imports: [PersonClaims, PersonPlans],
  template: `
    <section aria-label="People" data-testid="people">
      <h2>People</h2>
      <form (submit)="submit($event)" data-testid="person-form">
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
        @if (editing() !== null) {
          <label for="person-form-grade-from">Grade from</label>
          <input
            id="person-form-grade-from"
            data-testid="person-form-grade-from"
            placeholder="YYYY-MM-DD"
            [value]="gradeFrom()"
            (input)="gradeFrom.set(value($event)); error.set(null)"
          />
        }
        <label for="person-form-terms">Terms (business days; blank for none)</label>
        <input
          id="person-form-terms"
          data-testid="person-form-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); error.set(null)"
        />
        @if (editing() !== null) {
          <label for="person-form-terms-from">Terms from (blank for the beginning of time)</label>
          <input
            id="person-form-terms-from"
            data-testid="person-form-terms-from"
            placeholder="YYYY-MM-DD"
            [value]="termsFrom()"
            (input)="termsFrom.set(value($event)); error.set(null)"
          />
        }
        <label for="person-form-contact">Contact (blank for none)</label>
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
        <button type="submit" data-testid="person-form-submit" [disabled]="saving()">
          {{ editing() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editing() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>
      <form (submit)="quickAdd($event)" data-testid="quick-form" aria-label="Quick-add a claim">
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
        <button type="submit" data-testid="quick-submit" [disabled]="quickSaving()">Quick-add claim</button>
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err }}</p>
        }
      </form>
      <p>Claimed total: <strong data-testid="rollup-claimed-total">{{ claims.total() }}</strong></p>
      <p>Due total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Settled total: <strong data-testid="rollup-settled-total">{{ figures.settledTotal() }}</strong></p>
      <p>Open total: <strong data-testid="rollup-open-total">{{ figures.openTotal() }}</strong></p>
      <p>Overdue: <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong></p>
      <p>Overdue total: <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>
      <p>Cut total: <strong data-testid="rollup-cut-total">{{ cutTotal() }}</strong></p>
      @let agedTotals = figures.agedTotals();
      <p>Aged 0 to 30 days: <strong data-testid="rollup-aged-0-30">{{ agedTotals.upTo30 }}</strong></p>
      <p>Aged 31 to 60 days: <strong data-testid="rollup-aged-31-60">{{ agedTotals.upTo60 }}</strong></p>
      <p>Aged 61 days or more: <strong data-testid="rollup-aged-61">{{ agedTotals.over60 }}</strong></p>
      @if (figures.tagTotals().length > 0) {
        <table data-testid="tag-totals">
          <caption>Due total by tag</caption>
          <thead>
            <tr><th scope="col">Tag</th><th scope="col">Due total</th></tr>
          </thead>
          <tbody>
            @for (t of figures.tagTotals(); track t.tag) {
              <tr>
                <th scope="row">{{ t.tag }}</th>
                <td [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ t.dueTotal }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
      @if (figures.monthTotals().length > 0) {
        <table data-testid="month-totals">
          <caption>Due total by month</caption>
          <thead>
            <tr><th scope="col">Month</th><th scope="col">Due total</th></tr>
          </thead>
          <tbody>
            @for (m of figures.monthTotals(); track m.month) {
              <tr>
                <th scope="row">{{ m.month }}</th>
                <td [attr.data-testid]="'month-' + m.month + '-due-total'">{{ m.dueTotal }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="people.showRetired()"
          (change)="people.showRetired.set(checked($event))"
        />
        Show retired
      </label>
      <label for="sort-by">Sort by</label>
      <select id="sort-by" data-testid="sort-by" [value]="sortBy()" (change)="setSortBy(value($event))">
        @for (k of sortKeys; track k) {
          <option [value]="k" [selected]="k === sortBy()">{{ k }}</option>
        }
      </select>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <button type="button" data-testid="export-claims" (click)="exportClaims()">Export claims (CSV)</button>
      <section aria-label="Grade or retire selected people">
        <label for="bulk-grade">Grade selected people</label>
        <select
          id="bulk-grade"
          data-testid="bulk-grade"
          [value]="bulkGrade()"
          (change)="bulkGrade.set(value($event)); bulkError.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === bulkGrade()">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">from</label>
        <input
          id="bulk-grade-from"
          data-testid="bulk-grade-from"
          placeholder="YYYY-MM-DD"
          [value]="bulkFrom()"
          (input)="bulkFrom.set(value($event)); bulkError.set(null)"
        />
        <button type="button" data-testid="bulk-grade-apply" [disabled]="bulkRunning()" (click)="applyBulkGrade()">
          Grade selected
        </button>
        @if (bulkGraded() !== null) {
          <p>Changed <span data-testid="bulk-graded">{{ bulkGraded() }}</span></p>
        }
        <label for="bulk-retire-date">Retire selected people from</label>
        <input
          id="bulk-retire-date"
          data-testid="bulk-retire-date"
          placeholder="YYYY-MM-DD"
          [value]="bulkRetireFrom()"
          (input)="bulkRetireFrom.set(value($event)); bulkError.set(null)"
        />
        <button type="button" data-testid="bulk-retire" [disabled]="bulkRunning()" (click)="applyBulkRetire()">
          Retire selected
        </button>
        @if (bulkRetired() !== null) {
          <p>Retired <span data-testid="bulk-retired">{{ bulkRetired() }}</span></p>
        }
        @if (bulkError(); as err) {
          <p role="alert" data-testid="person-error">{{ err }}</p>
        }
      </section>
      <fieldset>
        <legend>Columns shown on your screen</legend>
        @for (c of columnKeys; track c) {
          <label>
            <input
              type="checkbox"
              [attr.data-testid]="'column-' + c"
              [checked]="userColumns.shown(c)"
              (change)="showColumn(c, checked($event))"
            />
            {{ c }}
          </label>
        }
        @if (columnsError(); as err) {
          <p role="alert" data-testid="columns-error">{{ err }}</p>
        }
      </fieldset>
      <table data-testid="person-list">
        <thead>
          <tr><th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">Name</th><th scope="col">Came from</th>@if (col('aliases')) {<th scope="col">Aliases</th>}<th scope="col">Clashing</th><th scope="col">Grade</th><th scope="col">Active</th>@if (col('terms')) {<th scope="col">Terms</th>}@if (col('contact')) {<th scope="col">Contact</th>}<th scope="col">Tags</th>@if (col('claimed')) {<th scope="col">Claimed</th>}@if (col('due-total')) {<th scope="col">Due total</th>}<th scope="col">Settled total</th><th scope="col">Open total</th><th scope="col">Cut total</th><th scope="col">Due by period</th>@if (col('excess')) {<th scope="col">Excess</th>}@if (col('overdue-count')) {<th scope="col">Overdue</th>}@if (col('overdue-total')) {<th scope="col">Overdue total</th>}<th scope="col">Aged 0–30</th><th scope="col">Aged 31–60</th><th scope="col">Aged 61+</th>@if (col('statement-count')) {<th scope="col">Statements</th>}@if (col('stated-total')) {<th scope="col">Last stated</th>}@if (col('statement-date')) {<th scope="col">Stated on</th>}<th scope="col">Statement lines</th><th scope="col">Snapshots</th>@if (col('retired')) {<th scope="col">Retired</th>}@if (col('hold')) {<th scope="col">On hold</th>}@if (col('patrons')) {<th scope="col">Patrons</th>}@if (col('backing')) {<th scope="col">Backing</th>}<th scope="col">Watched</th><th scope="col">Plans</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of sorted(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td>
                <input
                  type="checkbox"
                  data-testid="person-select"
                  [attr.aria-label]="'Select ' + p.name"
                  [checked]="people.selected().has(p.id)"
                  (change)="people.select(p.id, checked($event)); bulkError.set(null); clearActionError(p.id)"
                />
              </td>
              <td data-testid="person-name">{{ figures.shownName(p) }}</td>
              <td data-testid="person-origin">{{ p.origin ?? '' }}</td>
              @if (col('aliases')) {
                <td>
                  <span data-testid="person-aliases">{{ p.aliases.join(', ') }}</span>
                  <label [for]="'person-alias-input-' + p.id">Alias<span class="visually-hidden"> for {{ p.name }}</span></label>
                  <input
                    [id]="'person-alias-input-' + p.id"
                    data-testid="person-alias-input"
                    [value]="aliasInputs()[p.id] ?? ''"
                    (input)="setAliasInput(p.id, value($event))"
                  />
                  <button
                    type="button"
                    data-testid="person-alias-add"
                    [disabled]="addingAlias() === p.id"
                    (click)="addAlias(p)"
                  >
                    Add<span class="visually-hidden"> alias for {{ p.name }}</span>
                  </button>
                </td>
              }
              <td data-testid="person-clashing">{{ p.clashing ? 'yes' : 'no' }}</td>
              <td>
                <span data-testid="person-grade">{{ gradeToday(p) }}</span>
                <label [for]="'person-inline-grade-' + p.id"><span class="visually-hidden">Grade for {{ p.name }}</span></label>
                <select
                  [id]="'person-inline-grade-' + p.id"
                  data-testid="person-inline-grade"
                  [value]="inlineGrades()[p.id] ?? gradeToday(p)"
                  (change)="setInlineGrade(p.id, value($event))"
                >
                  @for (g of grades; track g) {
                    <option [value]="g" [selected]="g === (inlineGrades()[p.id] ?? gradeToday(p))">{{ g }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="person-inline-grade-save"
                  [disabled]="inlineGrading() === p.id"
                  (click)="saveInlineGrade(p)"
                >
                  Save grade<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
              </td>
              <td data-testid="person-active">{{ figures.active(p) ? 'yes' : 'no' }}</td>
              @if (col('terms')) {
                <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              }
              @if (col('contact')) {
                <td data-testid="person-contact">{{ p.contact ?? '' }}</td>
              }
              <td data-testid="person-tags">{{ p.tags.join(', ') }}</td>
              @if (col('claimed')) {
                <td data-testid="person-claimed">{{ claims.claimed(p.id) }}</td>
              }
              @if (col('due-total')) {
                <td data-testid="person-due-total">{{ due(p) }}</td>
              }
              <td data-testid="person-settled-total">{{ money(figures.settledCents(p)) }}</td>
              <td data-testid="person-open-total">{{ money(figures.openCents(p)) }}</td>
              <td data-testid="person-cut-total">{{ cut(p) }}</td>
              <td>
                @for (period of periods.periods(); track period.id) {
                  <span class="person-period">{{ period.name }}:
                    <span [attr.data-testid]="'person-period-' + period.name + '-due-total'">{{ figures.personPeriodDue(period, p.id) }}</span>
                    carried <span [attr.data-testid]="'person-period-' + period.name + '-carried'">{{ figures.personPeriodCarried(period, p.id) }}</span></span>
                }
              </td>
              @if (col('excess')) {
                <td data-testid="person-excess">{{ excess(p) }}</td>
              }
              @if (col('overdue-count')) {
                <td data-testid="person-overdue-count">{{ overdue(p).length }}</td>
              }
              @if (col('overdue-total')) {
                <td data-testid="person-overdue-total">{{ overdueDue(p) }}</td>
              }
              @let aged = figures.agedCents(p);
              <td data-testid="person-aged-0-30">{{ money(aged.upTo30) }}</td>
              <td data-testid="person-aged-31-60">{{ money(aged.upTo60) }}</td>
              <td data-testid="person-aged-61">{{ money(aged.over60) }}</td>
              @if (col('statement-count')) {
                <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              }
              @if (col('stated-total')) {
                <td data-testid="person-stated-total">{{ p.statedTotal ?? '' }}</td>
              }
              @if (col('statement-date')) {
                <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              }
              <td>
                <ul class="holds">
                  @for (line of p.statementLines; track $index) {
                    <li data-testid="person-statement-line-line">{{ line }}</li>
                  }
                </ul>
              </td>
              <td>
                <ul class="holds">
                  @for (line of p.snapshots; track $index) {
                    <li data-testid="person-snapshot-line">{{ line }}</li>
                  }
                </ul>
              </td>
              @if (col('retired')) {
                <td>
                  <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
                  <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
                  <ul class="holds">
                    @for (r of p.retirements; track $index) {
                      <li data-testid="person-retirement-line">{{ r.from }} to {{ r.to }}</li>
                    }
                  </ul>
                </td>
              }
              @if (col('hold')) {
                <td>
                  <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                  <ul class="holds">
                    @for (h of p.holds; track $index) {
                      <li data-testid="person-hold-line">{{ h.from ?? 'always' }} to {{ h.to ?? 'no end' }}</li>
                    }
                  </ul>
                </td>
              }
              @if (col('patrons')) {
                <td>
                  <span data-testid="person-patrons">{{ patrons(p) }}</span>
                  <label [for]="'person-patron-select-' + p.id">Add patron<span class="visually-hidden"> for {{ p.name }}</span></label>
                  <select
                    [id]="'person-patron-select-' + p.id"
                    data-testid="person-patron-select"
                    [value]="patronChoices()[p.id] ?? ''"
                    (change)="setPatronChoice(p.id, value($event))"
                  >
                    <option value="" [selected]="!patronChoices()[p.id]">Choose…</option>
                    @for (o of others(p); track o.id) {
                      <option [value]="'' + o.id" [selected]="'' + o.id === patronChoices()[p.id]">{{ o.name }}</option>
                    }
                  </select>
                  <button
                    type="button"
                    data-testid="person-patron-add"
                    [disabled]="addingPatron() === p.id"
                    (click)="addPatron(p)"
                  >
                    Add<span class="visually-hidden"> patron for {{ p.name }}</span>
                  </button>
                  @if (patronError()?.id === p.id) {
                    <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
                  }
                </td>
              }
              @if (col('backing')) {
                <td data-testid="person-backing">{{ backing(p) }}</td>
              }
              <td>
                <span data-testid="person-watched">{{ p.watched ? 'yes' : 'no' }}</span>
                <button
                  type="button"
                  data-testid="person-watch"
                  [attr.aria-pressed]="p.watched"
                  [disabled]="watching() === p.id"
                  (click)="watch(p)"
                >
                  Watch<span class="visually-hidden"> {{ p.name }}</span>
                </button>
              </td>
              <td><app-person-plans [personId]="p.id" [personName]="p.name" /></td>
              <td><app-person-claims [personId]="p.id" [grades]="p.grades" /></td>
              <td>
                <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)">
                  Edit<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <label [for]="'person-statement-asof-' + p.id">Statement as of<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  [id]="'person-statement-asof-' + p.id"
                  data-testid="person-statement-asof"
                  placeholder="YYYY-MM-DD"
                  [value]="statementDates()[p.id] ?? ''"
                  (input)="setStatementDate(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-statement"
                  [disabled]="stating() === p.id"
                  (click)="statement(p)"
                >
                  Statement<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                <button
                  type="button"
                  data-testid="person-unstate"
                  [disabled]="stating() === p.id"
                  (click)="cancelStatement(p)"
                >
                  Cancel statement<span class="visually-hidden"> for {{ p.name }}</span>
                </button>
                <button
                  type="button"
                  data-testid="person-move-up"
                  [disabled]="moving() === p.id"
                  (click)="move(p, 'up')"
                >
                  Up<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button
                  type="button"
                  data-testid="person-move-down"
                  [disabled]="moving() === p.id"
                  (click)="move(p, 'down')"
                >
                  Down<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <label [for]="'person-retire-date-' + p.id">Retire from<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  [id]="'person-retire-date-' + p.id"
                  data-testid="person-retire-date"
                  placeholder="YYYY-MM-DD"
                  [value]="retireDates()[p.id] ?? ''"
                  (input)="setRetireDate(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-retire"
                  [disabled]="retiring() === p.id"
                  (click)="retire(p)"
                >
                  Retire<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button
                  type="button"
                  data-testid="person-unretire"
                  [disabled]="retiring() === p.id"
                  (click)="unretire(p)"
                >
                  Unretire<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <span>Flag: <span data-testid="person-flag-shown">{{ p.flag ?? '' }}</span></span>
                <label [for]="'person-flag-reason-' + p.id">Flag reason<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  [id]="'person-flag-reason-' + p.id"
                  data-testid="person-flag-reason"
                  [value]="flagEntries()[p.id]?.reason ?? ''"
                  (input)="setFlagEntry(p.id, 'reason', value($event))"
                />
                <label [for]="'person-flag-until-' + p.id">until<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  [id]="'person-flag-until-' + p.id"
                  data-testid="person-flag-until"
                  placeholder="YYYY-MM-DD"
                  [value]="flagEntries()[p.id]?.until ?? ''"
                  (input)="setFlagEntry(p.id, 'until', value($event))"
                />
                <button
                  type="button"
                  data-testid="person-flag"
                  [disabled]="flagging() === p.id"
                  (click)="flag(p)"
                >
                  Flag<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <label [for]="'person-hold-from-' + p.id">Hold from<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  [id]="'person-hold-from-' + p.id"
                  data-testid="person-hold-from"
                  placeholder="YYYY-MM-DD"
                  [value]="holdDates()[p.id]?.from"
                  (input)="setHoldDate(p.id, 'from', value($event))"
                />
                <label [for]="'person-hold-to-' + p.id">to<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  [id]="'person-hold-to-' + p.id"
                  data-testid="person-hold-to"
                  placeholder="YYYY-MM-DD"
                  [value]="holdDates()[p.id]?.to"
                  (input)="setHoldDate(p.id, 'to', value($event))"
                />
                <button
                  type="button"
                  data-testid="person-hold-toggle"
                  [disabled]="holding() === p.id"
                  (click)="hold(p)"
                >
                  Hold<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <label [for]="'person-merge-into-' + p.id">Merge into<span class="visually-hidden"> for {{ p.name }}</span></label>
                <select
                  [id]="'person-merge-into-' + p.id"
                  data-testid="person-merge-into"
                  [value]="mergeChoices()[p.id] ?? ''"
                  (change)="setMergeChoice(p.id, value($event))"
                >
                  <option value="" [selected]="!mergeChoices()[p.id]">Choose…</option>
                  @for (o of people.people(); track o.id) {
                    <option [value]="'' + o.id" [selected]="'' + o.id === mergeChoices()[p.id]">{{ o.name }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="person-merge"
                  [disabled]="merging() === p.id"
                  (click)="merge(p)"
                >
                  Merge<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <label [for]="'person-transfer-to-' + p.id">Transfer to sheet<span class="visually-hidden"> for {{ p.name }}</span></label>
                <input
                  [id]="'person-transfer-to-' + p.id"
                  data-testid="person-transfer-to"
                  [value]="transferTargets()[p.id] ?? ''"
                  (input)="setTransferTarget(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-transfer"
                  [disabled]="transferring() === p.id"
                  (click)="transfer(p)"
                >
                  Transfer<span class="visually-hidden"> {{ p.name }}</span>
                </button>
                <button
                  type="button"
                  data-testid="person-transfer-undo"
                  [disabled]="transferring() === p.id"
                  (click)="undoTransfer(p)"
                >
                  Undo transfer<span class="visually-hidden"> of {{ p.name }}</span>
                </button>
                @if (actionError()?.id === p.id) {
                  <p role="alert" data-testid="person-error">{{ actionError()?.message }}</p>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class PeopleList {
  protected readonly people = inject(People);
  protected readonly claims = inject(Claims);
  protected readonly figures = inject(Figures);
  protected readonly money = money;
  protected readonly periods = inject(Periods);
  private readonly pools = inject(Pools);
  private readonly crews = inject(Crews);
  private readonly session = inject(Session);
  protected readonly userColumns = inject(UserColumns);
  protected readonly columnKeys = COLUMNS;
  /** The last refused switch of a column, until the next switch. */
  protected readonly columnsError = signal<string | null>(null);
  protected readonly grades = GRADES;
  /** The people the list shows: everyone, or only those not retired unless show retired is on. */
  protected readonly shown = computed(() =>
    this.people.showRetired() ? this.people.people() : this.people.people().filter((p) => !p.retired),
  );
  /** The sort keys the list can be shown in. */
  protected readonly sortKeys = SORT_KEYS;
  /** How this user's screen orders the list; only what is shown, never the stored positions. */
  protected readonly sortBy = signal<SortKey>('added');
  /** The people shown, in the chosen order; equal keys keep list order (the sort is stable). */
  protected readonly sorted = computed(() => {
    const list = this.shown();
    switch (this.sortBy()) {
      case 'name':
        return [...list].sort((a, b) => compareKeys(nameKey(a.name), nameKey(b.name)));
      case 'grade':
        return [...list].sort((a, b) => compareKeys(this.gradeToday(a), this.gradeToday(b)));
      case 'due-total': {
        const due = new Map(list.map((p) => [p.id, this.dueCents(p)]));
        return [...list].sort((a, b) => {
          const x = due.get(a.id) ?? null;
          const y = due.get(b.id) ?? null;
          if (x === null || y === null) return (x === null ? 1 : 0) - (y === null ? 1 : 0);
          return x < y ? -1 : x > y ? 1 : 0;
        });
      }
      default:
        return list;
    }
  });
  /** The retire-from date typed on each person's row. */
  protected readonly retireDates = signal<Record<number, string>>({});
  /** The date typed on each person's row to take their statement as of; blank takes it now. */
  protected readonly statementDates = signal<Record<number, string>>({});
  /** The flag reason and until-date typed on each person's row. */
  protected readonly flagEntries = signal<Record<number, { reason: string; until: string }>>({});
  /** The id of the person being flagged. */
  protected readonly flagging = signal<number | null>(null);
  /** The id of the person being retired. */
  protected readonly retiring = signal<number | null>(null);
  /** The id of the person being moved. */
  protected readonly moving = signal<number | null>(null);
  /** The hold from- and to-dates typed on each person's row. */
  protected readonly holdDates = signal<Record<number, { from: string; to: string }>>({});
  /** The id of the person being put on hold. */
  protected readonly holding = signal<number | null>(null);
  /** The person chosen on each person's row to merge them into (a person id), blank while none is. */
  protected readonly mergeChoices = signal<Record<number, string>>({});
  /** The id of the person being merged into another. */
  protected readonly merging = signal<number | null>(null);
  /** The sheet id typed on each person's row to transfer them to. */
  protected readonly transferTargets = signal<Record<number, string>>({});
  /** The id of the person being transferred to another sheet. */
  protected readonly transferring = signal<number | null>(null);
  /** The id of the person whose watch is being toggled. */
  protected readonly watching = signal<number | null>(null);
  /** Everyone's due-total added up, skipping those whose grade has no rate. */
  protected readonly dueTotal = this.figures.dueTotal;
  /** Whether the user's screen shows this column of the list. */
  protected col(c: ColumnKey): boolean {
    return this.userColumns.shown(c);
  }

  protected async showColumn(c: ColumnKey, shown: boolean): Promise<void> {
    this.columnsError.set(null);
    this.columnsError.set(await this.userColumns.show(c, shown));
  }

  /** Everyone's overdue claims that count, counted. */
  protected readonly overdueCount = computed(() =>
    this.people.people().reduce((n, p) => n + this.overdue(p).length, 0),
  );
  /** The due of everyone's overdue claims that count, added up. */
  protected readonly overdueTotal = this.figures.overdueTotal;
  /** A person's cut total: their cuts across every pool they are a member of, added up. */
  protected cut(p: Person): string {
    return money(this.pools.cutCents().get(p.id) ?? 0n);
  }
  /** Everyone's cut total added up. */
  protected readonly cutTotal = computed(() => {
    const cuts = this.pools.cutCents();
    return money(this.people.people().reduce((sum, p) => sum + (cuts.get(p.id) ?? 0n), 0n));
  });
  protected readonly name = signal('');
  protected readonly grade = signal<string>('A');
  /** The date the editor's grade applies from; blank takes the sheet's today. */
  protected readonly gradeFrom = signal('');
  /** The person's own terms in days; blank for none. */
  protected readonly terms = signal('');
  /** The date the edit's terms apply from; blank for the beginning of time. */
  protected readonly termsFrom = signal('');
  /** The person's contact; blank for none. */
  protected readonly contact = signal('');
  /** The person's tags, comma separated; blank for none. */
  protected readonly tags = signal('');
  /** The id of the person whose editor is open, or null when the form adds. */
  protected readonly editing = signal<number | null>(null);
  /** The version of the person the editor loaded; a save from it is refused once someone else saved. */
  private readonly version = signal(0);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal<string | null>(null);
  protected readonly quickSaving = signal(false);
  /** The id of the person whose statement is being taken. */
  protected readonly stating = signal<number | null>(null);
  /** The patron chosen on each person's row (a person id), blank while none is. */
  protected readonly patronChoices = signal<Record<number, string>>({});
  /** The id of the person a patron is being added to. */
  protected readonly addingPatron = signal<number | null>(null);
  /** The last refused patron added to a person, shown on their row until the next action on it. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** The alias typed on each person's row. */
  protected readonly aliasInputs = signal<Record<number, string>>({});
  /** The id of the person an alias is being added to. */
  protected readonly addingAlias = signal<number | null>(null);
  /** The last refused action on a person, shown on their row until the next action on it. */
  protected readonly actionError = signal<{ id: number; message: string } | null>(null);

  /** The grade the bulk grade gives the selected people. */
  protected readonly bulkGrade = signal<string>('A');
  /** The date the bulk grade applies from; blank takes the sheet's today. */
  protected readonly bulkFrom = signal('');
  protected readonly bulkRunning = signal(false);
  /** How many the last bulk grade changed; gone while the next runs. */
  protected readonly bulkGraded = signal<number | null>(null);
  protected readonly bulkError = signal<string | null>(null);

  protected async applyBulkGrade(): Promise<void> {
    if (this.bulkRunning()) return;
    this.bulkGraded.set(null);
    this.bulkError.set(null);
    const err = this.actionError();
    if (err && this.people.selected().has(err.id)) this.actionError.set(null);
    this.bulkRunning.set(true);
    try {
      const done = await this.people.bulkGrade(this.bulkGrade(), this.bulkFrom());
      if (typeof done === 'string') this.bulkError.set(done);
      else this.bulkGraded.set(done);
    } finally {
      this.bulkRunning.set(false);
    }
  }

  /** The date the bulk retire retires the selected people from; blank takes the sheet's today. */
  protected readonly bulkRetireFrom = signal('');
  /** How many the last bulk retire retired; gone while the next runs. */
  protected readonly bulkRetired = signal<number | null>(null);

  protected async applyBulkRetire(): Promise<void> {
    if (this.bulkRunning()) return;
    this.bulkRetired.set(null);
    this.bulkError.set(null);
    const err = this.actionError();
    if (err && this.people.selected().has(err.id)) this.actionError.set(null);
    this.bulkRunning.set(true);
    try {
      const done = await this.people.bulkRetire(this.bulkRetireFrom());
      if (typeof done === 'string') this.bulkError.set(done);
      else this.bulkRetired.set(done);
    } finally {
      this.bulkRunning.set(false);
    }
  }

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  private dueCents(p: Person): bigint | null {
    return this.figures.dueCents(p);
  }

  private figureCents(c: Claim, p: Person): bigint {
    return this.figures.figureCents(c, p);
  }

  /** The grade in force on the sheet's today, the one the list shows. */
  protected setSortBy(key: string): void {
    this.sortBy.set((SORT_KEYS as readonly string[]).includes(key) ? (key as SortKey) : 'added');
  }

  protected gradeToday(p: Person): string {
    return this.figures.gradeToday(p);
  }

  /** A person's claims that count live and are overdue. */
  protected overdue(p: Person): Claim[] {
    return this.figures.overdue(p);
  }

  /** The due of a person's overdue claims that count, added up. */
  private overdueCents(p: Person): bigint {
    return this.figures.overdueCents(p);
  }

  protected overdueDue(p: Person): string {
    return money(this.overdueCents(p));
  }

  /** The people a person may choose among as a patron: everyone else on the sheet. */
  protected others(p: Person): Person[] {
    return this.people.people().filter((o) => o.id !== p.id);
  }

  /** A person's patrons in the order they were named, each with their share in hundredths: 100.00 split
   *  equally, each rounded half to even, the remainder to the first named. */
  private patronsOf(p: Person): { patron: Person; share: bigint }[] {
    const byId = new Map(this.people.people().map((o) => [o.id, o]));
    const named = p.patronIds.map((id) => byId.get(id)).filter((o): o is Person => o !== undefined);
    if (named.length === 0) return [];
    const each = halfEven(10000n, BigInt(named.length));
    const first = 10000n - each * BigInt(named.length - 1);
    return named.map((patron, i) => ({ patron, share: i === 0 ? first : each }));
  }

  /** A person's patrons as <name> (<grade>) <share>, sorted by name, comma and space separated. */
  protected patrons(p: Person): string {
    return this.patronsOf(p)
      .sort((a, b) => (a.patron.name < b.patron.name ? -1 : a.patron.name > b.patron.name ? 1 : 0))
      .map((o) => this.figures.shownName(o.patron) + ' ' + money(o.share))
      .join(', ');
  }

  /** A person's patrons' recent settled figures, each times their share over 100, added up and rounded half to
   *  even; 0.00 with no patrons. */
  protected backing(p: Person): string {
    const sum = this.patronsOf(p).reduce((s, o) => s + this.figures.recentSettledCents(o.patron) * o.share, 0n);
    return money(halfEven(sum, 10000n));
  }

  protected setPatronChoice(id: number, choice: string): void {
    this.clearActionError(id);
    this.patronChoices.update((cs) => ({ ...cs, [id]: choice }));
  }

  protected async addPatron(p: Person): Promise<void> {
    this.clearActionError(p.id);
    const choice = this.patronChoices()[p.id] ?? '';
    if (!choice) {
      this.patronError.set({ id: p.id, message: 'Choose a patron.' });
      return;
    }
    this.addingPatron.set(p.id);
    try {
      const err = await this.people.addPatron(p.id, Number(choice));
      if (err) this.patronError.set({ id: p.id, message: err });
      else this.patronChoices.update((cs) => ({ ...cs, [p.id]: '' }));
    } finally {
      this.addingPatron.set(null);
    }
  }

  protected setAliasInput(id: number, alias: string): void {
    this.clearActionError(id);
    this.aliasInputs.update((as) => ({ ...as, [id]: alias }));
  }

  protected async addAlias(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.addingAlias.set(p.id);
    try {
      const err = await this.people.addAlias(p.id, this.aliasInputs()[p.id] ?? '');
      if (err) this.actionError.set({ id: p.id, message: err });
      else this.aliasInputs.update((as) => ({ ...as, [p.id]: '' }));
    } finally {
      this.addingAlias.set(null);
    }
  }

  protected due(p: Person): string {
    const d = this.dueCents(p);
    return d === null ? NO_RATE : money(d);
  }

  /** How far the sum of a person's figures is above the sheet's cap; 0.00 when it is not. */
  protected excess(p: Person): string {
    return money(this.figures.excessCents(p));
  }

  /** Download everyone, retired included, in list order as CSV: name, grade in force today,
   *  retired-from date or nothing, and the due-total as the list shows it. */
  protected async exportCsv(): Promise<void> {
    await this.session.loaded();
    const lines = [['name', 'grade', 'retired-from', 'due-total']];
    for (const p of this.people.people()) lines.push([p.name, this.gradeToday(p), p.retiredFrom ?? '', this.due(p)]);
    download(lines, 'people.csv');
  }

  /** Download every claim as CSV: people in list order, retired included, each person's claims
   *  oldest first, with number, due and period as their rows show them. */
  protected async exportClaims(): Promise<void> {
    await this.session.loaded();
    const lines = [['person', 'number', 'date', 'amount', 'due', 'status', 'period']];
    for (const p of this.people.people()) {
      for (const c of [...this.claims.of(p.id)].reverse()) {
        lines.push([
          p.name,
          c.number ?? '',
          c.date,
          c.amount,
          this.figures.claimDue(c, p),
          c.status,
          this.periods.of(c.dueBy)?.name ?? '',
        ]);
      }
    }
    download(lines, 'claims.csv');
  }

  protected async statement(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.stating.set(p.id);
    try {
      const asOf = this.statementDates()[p.id] ?? '';
      this.statementDates.update((ds) => ({ ...ds, [p.id]: '' }));
      const stated = await this.people.statement(p.id, asOf);
      if (typeof stated === 'string') this.actionError.set({ id: p.id, message: stated });
      else this.claims.replaceOf(p.id, stated.claims);
    } finally {
      this.stating.set(null);
    }
  }

  protected async cancelStatement(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.stating.set(p.id);
    try {
      const unstated = await this.people.cancelStatement(p.id);
      if (typeof unstated === 'string') this.actionError.set({ id: p.id, message: unstated });
      else this.claims.replaceOf(p.id, unstated.claims);
    } finally {
      this.stating.set(null);
    }
  }

  protected setStatementDate(id: number, date: string): void {
    this.clearActionError(id);
    this.statementDates.update((ds) => ({ ...ds, [id]: date }));
  }

  protected setRetireDate(id: number, date: string): void {
    this.clearActionError(id);
    this.retireDates.update((ds) => ({ ...ds, [id]: date }));
  }

  protected async move(p: Person, direction: 'up' | 'down'): Promise<void> {
    this.clearActionError(p.id);
    this.moving.set(p.id);
    try {
      const err = await this.people.move(p.id, direction);
      if (err) this.actionError.set({ id: p.id, message: err });
    } finally {
      this.moving.set(null);
    }
  }

  protected setFlagEntry(id: number, field: 'reason' | 'until', text: string): void {
    this.clearActionError(id);
    this.flagEntries.update((fs) => ({ ...fs, [id]: { ...(fs[id] ?? { reason: '', until: '' }), [field]: text } }));
  }

  protected async flag(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.flagging.set(p.id);
    try {
      const entry = this.flagEntries()[p.id];
      const err = await this.people.flag(p.id, entry?.reason ?? '', entry?.until ?? '');
      if (err) this.actionError.set({ id: p.id, message: err });
    } finally {
      this.flagging.set(null);
    }
  }

  protected async retire(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.retiring.set(p.id);
    try {
      const err = await this.people.retire(p.id, this.retireDates()[p.id] ?? '');
      if (err) this.actionError.set({ id: p.id, message: err });
    } finally {
      this.retiring.set(null);
    }
  }

  protected async unretire(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.retiring.set(p.id);
    try {
      const err = await this.people.unretire(p.id);
      if (err) this.actionError.set({ id: p.id, message: err });
    } finally {
      this.retiring.set(null);
    }
  }

  protected setHoldDate(id: number, which: 'from' | 'to', date: string): void {
    this.clearActionError(id);
    this.holdDates.update((ds) => ({ ...ds, [id]: { ...(ds[id] ?? { from: '', to: '' }), [which]: date } }));
  }

  protected async hold(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.holding.set(p.id);
    try {
      const dates = this.holdDates()[p.id] ?? { from: '', to: '' };
      const err = await this.people.hold(p.id, dates.from, dates.to);
      if (err) this.actionError.set({ id: p.id, message: err });
      else this.holdDates.update((ds) => ({ ...ds, [p.id]: { from: '', to: '' } }));
    } finally {
      this.holding.set(null);
    }
  }

  protected setMergeChoice(id: number, choice: string): void {
    this.clearActionError(id);
    this.mergeChoices.update((cs) => ({ ...cs, [id]: choice }));
  }

  /** Merge a person into the one chosen on their row; once merged, everything on the sheet is re-read, as
   *  their claims, patrons, pools and figures now belong to the other. */
  protected async merge(p: Person): Promise<void> {
    this.clearActionError(p.id);
    const choice = this.mergeChoices()[p.id] ?? '';
    if (!choice) {
      this.actionError.set({ id: p.id, message: 'Choose a person to merge into.' });
      return;
    }
    this.merging.set(p.id);
    try {
      const err = await this.people.merge(p.id, Number(choice));
      if (err) {
        this.actionError.set({ id: p.id, message: err });
        return;
      }
      this.mergeChoices.update((cs) => ({ ...cs, [p.id]: '' }));
      await Promise.all([this.people.load(), this.claims.load(), this.pools.load(), this.crews.load(), this.periods.load()]);
    } finally {
      this.merging.set(null);
    }
  }

  protected setTransferTarget(id: number, target: string): void {
    this.clearActionError(id);
    this.transferTargets.update((ts) => ({ ...ts, [id]: target }));
  }

  /** Transfer a person to the sheet typed on their row; once transferred, everything on the sheet is re-read,
   *  as their claims, patron links, pool memberships and figures have left it. */
  protected async transfer(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.transferring.set(p.id);
    try {
      const err = await this.people.transfer(p.id, this.transferTargets()[p.id] ?? '');
      if (err) {
        this.actionError.set({ id: p.id, message: err });
        return;
      }
      this.transferTargets.update((ts) => ({ ...ts, [p.id]: '' }));
      await Promise.all([this.people.load(), this.claims.load(), this.pools.load(), this.crews.load(), this.periods.load()]);
    } finally {
      this.transferring.set(null);
    }
  }

  /** Undo the transfer that brought a person here: they go back to the sheet they came from, so everything on
   *  the sheet is re-read as after a transfer. */
  protected async undoTransfer(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.transferring.set(p.id);
    try {
      const err = await this.people.undoTransfer(p.id);
      if (err) {
        this.actionError.set({ id: p.id, message: err });
        return;
      }
      await Promise.all([this.people.load(), this.claims.load(), this.pools.load(), this.crews.load(), this.periods.load()]);
    } finally {
      this.transferring.set(null);
    }
  }

  /** The grade chosen in each row's grade select, by person id, until saved. */
  protected readonly inlineGrades = signal<Record<number, string>>({});
  protected readonly inlineGrading = signal<number | null>(null);

  protected setInlineGrade(id: number, grade: string): void {
    this.clearActionError(id);
    this.inlineGrades.update((m) => ({ ...m, [id]: grade }));
  }

  /** Record the row's chosen grade from the sheet's today, as the person form would save it. */
  protected async saveInlineGrade(p: Person): Promise<void> {
    if (this.inlineGrading() !== null) return;
    this.clearActionError(p.id);
    this.inlineGrading.set(p.id);
    try {
      const grade = this.inlineGrades()[p.id] ?? this.gradeToday(p);
      const refusal = await this.people.update(p.id, p.version, {
        name: p.name, grade, gradeFrom: '', terms: p.baseTerms === null ? '' : String(p.baseTerms),
        contact: p.contact ?? '', tags: p.tags.join(', '),
      });
      if (refusal) this.actionError.set({ id: p.id, message: refusal.error });
      else this.inlineGrades.update(({ [p.id]: _, ...rest }) => rest);
    } finally {
      this.inlineGrading.set(null);
    }
  }

  protected async watch(p: Person): Promise<void> {
    this.clearActionError(p.id);
    this.watching.set(p.id);
    try {
      const err = await this.people.watch(p.id, !p.watched);
      if (err) this.actionError.set({ id: p.id, message: err });
    } finally {
      this.watching.set(null);
    }
  }

  protected clearActionError(id: number): void {
    if (this.actionError()?.id === id) this.actionError.set(null);
    if (this.patronError()?.id === id) this.patronError.set(null);
  }

  protected edit(p: Person): void {
    this.clearActionError(p.id);
    this.editing.set(p.id);
    this.load(p);
    this.error.set(null);
  }

  private load(p: Person): void {
    this.version.set(p.version);
    this.name.set(p.name);
    this.grade.set(this.gradeToday(p));
    this.gradeFrom.set('');
    this.terms.set(p.baseTerms === null ? '' : String(p.baseTerms));
    this.termsFrom.set('');
    this.contact.set(p.contact ?? '');
    this.tags.set(p.tags.join(', '));
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
    this.error.set(null);
  }

  protected async quickAdd(e: Event): Promise<void> {
    e.preventDefault();
    if (this.quickSaving()) return; // a second submit before the first has answered
    this.quickError.set(null);
    this.quickSaving.set(true);
    try {
      const err = await this.claims.quickAdd(this.quickName(), this.quickAmount());
      if (err) this.quickError.set(err);
      else {
        this.quickName.set('');
        this.quickAmount.set('');
      }
    } finally {
      this.quickSaving.set(false);
    }
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const f = { name: this.name(), grade: this.grade(), gradeFrom: this.gradeFrom(), terms: this.terms(),
        termsFrom: this.termsFrom(), contact: this.contact(), tags: this.tags() };
      const id = this.editing();
      if (id === null) {
        const err = await this.people.add(f);
        if (err) this.error.set(err);
        else this.reset();
      } else {
        const refusal = await this.people.update(id, this.version(), f);
        if (!refusal) this.reset();
        else {
          // a conflict shows the stored values for review; any other refusal returns the form to adding
          if (refusal.current) this.load(refusal.current);
          else this.reset();
          this.error.set(refusal.error);
        }
      }
    } finally {
      this.saving.set(false);
    }
  }
}
