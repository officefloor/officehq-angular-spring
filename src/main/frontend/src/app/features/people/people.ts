import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, Claims, PersonGrade, bound, byListed, claimDue, counts, deferred, dueFixed, gradeOn, isOpen, overdue, plusDays, statusOn, sumAmounts, tagsOf } from '../claims/claims';
import { Period, Periods, periodOn } from '../periods/periods';
import { Rate, Rates, capped, changeFrom, excessOver, rateOn, sumDues, sumShared, trimmedSumDues } from '../rates/rates';
import { ClassicPeriodSummary, ClassicSummary, GradeSummary, PeriodSummary, StatusSummary, Summary, VoidReasonSummary } from '../summary/summary';
import { VoidReasons } from '../../core/void-reasons';
import { Header } from '../header/header';
import { Pools } from '../pools/pools';
import { PersonPlans } from '../plans/plans';
import { Plans } from '../../core/plans';

interface Person {
  id: number;
  name: string;
  /** The grade in force on the sheet's today when the person was read. */
  grade: string;
  /** Every grade of the person, each from its from-date; the first one given from the beginning of time. */
  grades: PersonGrade[];
  /** The person's own terms in whole days; null when they have none. */
  terms: number | null;
  /** The person's contact, trimmed and lower-cased; null when they have none. */
  contact: string | null;
  /** The person's tags: trimmed, lower-cased, without repeats, sorted and joined by commas; null when they have none. */
  tags: string | null;
  /** Raised on each save; the editor sends back the one it loaded. */
  version: number;
  /** How many statements the person has had. */
  statementCount: number;
  /** The due-total recorded by the last statement; null with none. */
  statedTotal: string | null;
  /** The date the last statement was taken as of (yyyy-mm-dd): the sheet's today then, or an earlier date given; null with none. */
  statementDate: string | null;
  /** Whether the person has a retirement date: they count as retired at once, whatever the date. */
  retired: boolean;
  /** The date the person is retired from (yyyy-mm-dd); null while not retired. */
  retiredFrom: string | null;
  /** The person's past retirements by from-date, each from its from-date to the day they were unretired. */
  retirements: { from: string; to: string }[];
  /** Whether the person has any hold. */
  held: boolean;
  /** The person's holds by from-date: their OPEN and PENDING claims dated within one, unless a statement binds them,
   *  count in no live total; their SETTLED ones still do. */
  holds: PersonHold[];
  /** The ids of the other people on the sheet the person has named as their patrons. */
  patronIds: number[];
  /** Each patron's share of 100.00, in the order of {@link patronIds}. */
  patronShares: string[];
  /** Whether the signed-in user watches the person. */
  watched: boolean;
  /** The other names the person goes by, sorted as names are compared. */
  aliases: string[];
  /** Whether the person's name or an alias is used on another sheet of the sheet's tree: every edit of them is
   *  refused until a rename clears it. */
  clashing: boolean;
  /** The sheet the person was transferred from; null for anyone else. */
  origin: string | null;
  /** One line per SNAPSHOT written for the person, oldest first: the day being left, then the due-total. */
  snapshotLines: string[];
}

/** A hold of a person from its from-date to its to-date (yyyy-mm-dd), both inclusive; to null: it does not end. */
interface PersonHold {
  from: string;
  to: string | null;
}

/** Whether the date is within any of the holds. */
function heldOn(holds: PersonHold[], date: string): boolean {
  return holds.some((h) => h.from <= date && (h.to === null || date <= h.to));
}

/** The figures of a sheet's descendants added up: their people, due-totals and overdue-totals. */
interface DescendantFigures {
  people: number;
  dueTotal: string;
  overdueTotal: string;
}

/** A name as names are compared: trimmed, ignoring case. */
function nameKey(name: string): string {
  return name.trim().toLowerCase();
}

type SortKey = 'added' | 'name' | 'grade' | 'due-total';

function compareKeys(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

const NO_DESCENDANTS: DescendantFigures = { people: 0, dueTotal: '0.00', overdueTotal: '0.00' };

/** What decides which of a person's open claims leave the live totals: their holds and their retirement date. */
type LeftOutBy = Pick<Person, 'holds' | 'retiredFrom'>;

/**
 * Whether an open claim of the person, not bound by a statement, is left out of the live totals: one dated within a
 * hold of theirs, or any once their retirement date is reached (the sheet's today on or after it).
 */
function leftOut(claim: Claim, person: LeftOutBy, today: string | null): boolean {
  const retired = person.retiredFrom !== null && today !== null && person.retiredFrom <= today;
  return !bound(claim) && (heldOn(person.holds, claim.date) || retired);
}

/**
 * Whether the claim counts in the live totals: an OPEN, PENDING or SETTLED one, but an OPEN or PENDING one dated within
 * a hold of its person, or any once their retirement date is reached, not unless a statement binds it, and one deferred
 * on the sheet's today not at all.
 */
function live(claim: Claim, person: LeftOutBy, today: string | null): boolean {
  return counts(claim, today) && !(isOpen(claim) && leftOut(claim, person, today));
}

/**
 * Whether the claim counts in a period's due-total: by the status it had at the end of the period's last day, an OPEN,
 * PENDING or SETTLED one, but one open then and still open now and dated within a hold of its person, or any once
 * their retirement date is reached, not unless a statement binds it, and one deferred on the sheet's today not at all.
 */
function liveAtEnd(claim: Claim, person: LeftOutBy, today: string | null, end: string): boolean {
  const status = statusOn(claim, end);
  if (status === null || deferred(claim, today)) return false;
  const open = status === 'OPEN' || status === 'PENDING';
  return (open || status === 'SETTLED') && !(open && isOpen(claim) && leftOut(claim, person, today));
}

/** A CSV field: wrapped in double quotes, any quote inside doubled, when it holds a comma, a quote or a line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

// The people on the active sheet, in the order they were added, each with their claims and what
// they have claimed and their due-total, the sheet's claimed-total and due-total, the grades' rates, and the one form that adds a person or, once a
// person's editor is opened, saves that person.
@Component({
  selector: 'app-people',
  imports: [ClassicSummary, Claims, Header, Periods, PersonPlans, Pools, Rates, Summary],
  template: `
    <app-header
      [peopleCount]="people().length"
      [statuses]="statusSummary()"
      [dueTotal]="dueTotal()"
      [overdueTotal]="overdueTotal()"
      [myDueTotal]="myDueTotal()"
      [myOverdueTotal]="myOverdueTotal()"
    />
    <section aria-labelledby="consolidated-heading">
      <h2 id="consolidated-heading">Consolidated (this sheet and its descendants)</h2>
      <dl>
        <dt>People</dt>
        <dd data-testid="consolidated-people-count">{{ consolidatedPeople() }}</dd>
        <dt>Due total</dt>
        <dd data-testid="consolidated-due-total">{{ consolidatedDueTotal() }}</dd>
        <dt>Overdue total</dt>
        <dd data-testid="consolidated-overdue-total">{{ consolidatedOverdueTotal() }}</dd>
      </dl>
    </section>
    <app-rates [grades]="grades" [rates]="rates()" (saved)="rateSaved($event)" (imported)="ratesImported()" />
    <app-periods [periods]="periods()" [dueTotals]="periodDueTotals()" (saved)="periodSaved($event)" />
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
        <label for="person-form-grade-from">Grade from (blank: today)</label>
        <input
          id="person-form-grade-from"
          data-testid="person-form-grade-from"
          type="date"
          [value]="gradeFrom()"
          (input)="gradeFrom.set(value($event)); error.set(null)"
        />
        <label for="person-form-terms">Terms in days (blank: none)</label>
        <input
          id="person-form-terms"
          data-testid="person-form-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); error.set(null)"
        />
        <label for="person-form-contact">Contact (blank: none)</label>
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
          [value]="personTags()"
          (input)="personTags.set(value($event)); error.set(null)"
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
      <form aria-label="Quick add a claim" (submit)="quickAdd($event)">
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
          <p role="alert" data-testid="quick-error">{{ message }}</p>
        }
      </form>
      <p>
        <button type="button" data-testid="plan-generate" (click)="generatePlans()">Generate plan claims</button>
        @if (planGenerated() !== null) {
          Made <strong data-testid="plan-generated-count">{{ planGenerated() }}</strong>
        }
      </p>
      @if (planGenerateError(); as message) {
        <p role="alert" data-testid="plan-generate-error">{{ message }}</p>
      }
      <form aria-label="Paste claims" (submit)="pasteClaims($event)">
        <label for="paste-claims">Paste claims, one per line: name,amount,date,note (end the note with " #reference" to give a reference)</label>
        <textarea
          id="paste-claims"
          data-testid="paste-claims"
          rows="4"
          [value]="pasteText()"
          (input)="pasteText.set(value($event)); pasteError.set(null)"
        ></textarea>
        <button type="submit" data-testid="paste-submit">Add and open pasted claims</button>
        @if (pasteAdded() !== null) {
          Lines through <strong data-testid="paste-added">{{ pasteAdded() }}</strong>
        }
        @if (pasteError(); as message) {
          <p role="alert" data-testid="paste-error">{{ message }}</p>
        }
      </form>
      <form aria-label="Import people" (submit)="importPeople($event)">
        <label for="import-people">Import people, one per line: name,grade,terms,contact (terms and contact may be left out)</label>
        <textarea
          id="import-people"
          data-testid="import-people"
          rows="4"
          [value]="importText()"
          (input)="importText.set(value($event)); importError.set(null)"
        ></textarea>
        <button type="submit" data-testid="import-submit">Import people</button>
        @if (importAdded() !== null) {
          Added <strong data-testid="import-added">{{ importAdded() }}</strong>,
          updated <strong data-testid="import-updated">{{ importUpdated() }}</strong>
        }
        @if (importError(); as message) {
          <p role="alert" data-testid="import-error">{{ message }}</p>
        }
      </form>
      <p>
        <input
          id="show-retired"
          type="checkbox"
          data-testid="show-retired"
          [checked]="session.showRetired()"
          (change)="session.showRetired.set(checked($event))"
        />
        <label for="show-retired">Show retired</label>
      </p>
      <p>
        <label for="sort-by">Sort by</label>
        <select id="sort-by" data-testid="sort-by" [value]="sortBy()" (change)="sortBy.set(sortKey(value($event)))">
          @for (k of sortKeys; track k) {
            <option [value]="k" [selected]="k === sortBy()">{{ k }}</option>
          }
        </select>
      </p>
      <table>
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th>
            <th scope="col">Name</th><th scope="col">Grade</th><th scope="col">Terms</th><th scope="col">Contact</th><th scope="col">Tags</th><th scope="col">Aliases</th><th scope="col">From sheet</th><th scope="col">Clashing</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Settled</th><th scope="col">Open</th>
            @for (per of periods(); track per.id) {
              <th scope="col">Due in {{ per.name }}</th>
            }
            <th scope="col">Statements</th><th scope="col">Last stated</th><th scope="col">Stated on</th>
            <th scope="col">Retired</th><th scope="col">Retired from</th><th scope="col">Past retirements</th><th scope="col">Snapshots</th><th scope="col">On hold</th><th scope="col">Active</th>
            <th scope="col">Overdue</th><th scope="col">Overdue total</th>
            <th scope="col">Due aged 0–30</th><th scope="col">Due aged 31–60</th><th scope="col">Due aged 61+</th>
            <th scope="col">Patrons</th><th scope="col">Backing</th><th scope="col">Excess</th><th scope="col">Cut total</th><th scope="col">Watched</th>
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
                  (change)="selectPerson(p.id, checked($event))"
                />
              </td>
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              <td data-testid="person-contact">{{ p.contact ?? '' }}</td>
              <td data-testid="person-tags">{{ personTagsOf(p).join(', ') }}</td>
              <td data-testid="person-aliases">{{ p.aliases.join(', ') }}</td>
              <td data-testid="person-origin">{{ p.origin ?? '' }}</td>
              <td data-testid="person-clashing">{{ p.clashing ? 'yes' : 'no' }}</td>
              <td data-testid="person-claimed">{{ claimedBy(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueTotalOf(p) }}</td>
              <td data-testid="person-settled-total">{{ settledTotalOf(p) }}</td>
              <td data-testid="person-open-total">{{ openTotalOf(p) }}</td>
              @for (per of periods(); track per.id) {
                <td [attr.data-testid]="'person-period-' + per.name + '-due-total'">{{ periodDueTotalOf(p, per) }}</td>
              }
              <td data-testid="person-statement-count">{{ p.statementCount }}</td>
              <td data-testid="person-stated-total">{{ p.statedTotal ?? '' }}</td>
              <td data-testid="person-statement-date">{{ p.statementDate ?? '' }}</td>
              <td data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</td>
              <td data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</td>
              <td>
                @for (r of p.retirements; track $index) {
                  <div data-testid="person-retirement-line">{{ r.from }} to {{ r.to }}</div>
                }
              </td>
              <td>
                @for (line of p.snapshotLines; track $index) {
                  <div data-testid="person-snapshot-line">{{ line }}</div>
                }
              </td>
              <td data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</td>
              <td data-testid="person-active">{{ isActive(p) ? 'yes' : 'no' }}</td>
              <td data-testid="person-overdue-count">{{ overdueOf(p).length }}</td>
              <td data-testid="person-overdue-total">{{ overdueTotalOf(p) }}</td>
              @let aged = agedOf(p);
              <td data-testid="person-aged-0-30">{{ aged[0] }}</td>
              <td data-testid="person-aged-31-60">{{ aged[1] }}</td>
              <td data-testid="person-aged-61">{{ aged[2] }}</td>
              <td data-testid="person-patrons">{{ patronsOf(p) }}</td>
              <td data-testid="person-backing">{{ backingOf(p) }}</td>
              <td data-testid="person-excess">{{ excessOf(p) }}</td>
              <td data-testid="person-cut-total">{{ cutTotalOf(p) }}</td>
              <td data-testid="person-watched">{{ p.watched ? 'yes' : 'no' }}</td>
              <td>
                <button
                  type="button"
                  data-testid="person-watch"
                  [attr.aria-pressed]="p.watched"
                  [attr.aria-label]="(p.watched ? 'Stop watching ' : 'Watch ') + p.name"
                  (click)="watch(p)"
                >
                  {{ p.watched ? 'Unwatch' : 'Watch' }}
                </button>
                <button
                  type="button"
                  [attr.data-testid]="'person-edit-' + p.id"
                  [attr.aria-label]="'Edit ' + p.name"
                  (click)="edit(p)"
                >
                  Edit
                </button>
                <label [for]="'person-inline-grade-' + p.id" class="visually-hidden">Grade of {{ p.name }} from today</label>
                <select
                  [id]="'person-inline-grade-' + p.id"
                  data-testid="person-inline-grade"
                  (change)="setInlineGrade(p.id, value($event))"
                >
                  @for (g of grades; track g) {
                    <option [value]="g" [selected]="g === inlineGradeOf(p)">{{ g }}</option>
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
                <label [for]="'person-statement-asof-' + p.id" class="visually-hidden">Take a statement of {{ p.name }} as of</label>
                <input
                  [id]="'person-statement-asof-' + p.id"
                  type="date"
                  data-testid="person-statement-asof"
                  [value]="statementAsOfs()[p.id] ?? ''"
                  (input)="setStatementAsOf(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-statement"
                  [attr.aria-label]="'Take a statement of ' + p.name"
                  (click)="statement(p)"
                >
                  Statement
                </button>
                <button
                  type="button"
                  data-testid="person-unstate"
                  [attr.aria-label]="'Cancel the last statement of ' + p.name"
                  (click)="unstate(p)"
                >
                  Cancel statement
                </button>
                <label [for]="'person-retire-date-' + p.id" class="visually-hidden">Retire {{ p.name }} from</label>
                <input
                  [id]="'person-retire-date-' + p.id"
                  type="date"
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
                <label [for]="'person-alias-input-' + p.id" class="visually-hidden">Alias of {{ p.name }}</label>
                <input
                  [id]="'person-alias-input-' + p.id"
                  type="text"
                  data-testid="person-alias-input"
                  [value]="aliasInputs()[p.id] ?? ''"
                  (input)="setAliasInput(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-alias-add"
                  [attr.aria-label]="'Add an alias of ' + p.name"
                  (click)="addAlias(p)"
                >
                  Add alias
                </button>
                <label [for]="'person-merge-into-' + p.id" class="visually-hidden">Merge {{ p.name }} into</label>
                <select
                  [id]="'person-merge-into-' + p.id"
                  data-testid="person-merge-into"
                  [value]="mergeChoices()[p.id] ?? ''"
                  (change)="setMergeChoice(p.id, value($event))"
                >
                  <option value="" [selected]="!mergeChoices()[p.id]">Merge into…</option>
                  @for (o of mergeOptions(p); track o.id) {
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
                <label [for]="'person-transfer-to-' + p.id" class="visually-hidden">Transfer {{ p.name }} to sheet</label>
                <input
                  [id]="'person-transfer-to-' + p.id"
                  type="text"
                  data-testid="person-transfer-to"
                  placeholder="Sheet id"
                  [value]="transferTos()[p.id] ?? ''"
                  (input)="setTransferTo(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-transfer"
                  [attr.aria-label]="'Transfer ' + p.name + ' to the sheet typed'"
                  (click)="transfer(p)"
                >
                  Transfer
                </button>
                <ul class="holds">
                  @for (h of p.holds; track $index) {
                    <li data-testid="person-hold-line">Held {{ h.from }} to {{ h.to ?? 'open' }}</li>
                  }
                </ul>
                <label [for]="'person-hold-from-' + p.id" class="visually-hidden">Hold {{ p.name }} from</label>
                <input
                  [id]="'person-hold-from-' + p.id"
                  type="date"
                  data-testid="person-hold-from"
                  [value]="holdFroms()[p.id] ?? ''"
                  (input)="setHoldFrom(p.id, value($event))"
                />
                <label [for]="'person-hold-to-' + p.id" class="visually-hidden">Hold {{ p.name }} to</label>
                <input
                  [id]="'person-hold-to-' + p.id"
                  type="date"
                  data-testid="person-hold-to"
                  [value]="holdTos()[p.id] ?? ''"
                  (input)="setHoldTo(p.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="person-hold-toggle"
                  [attr.aria-label]="'Hold ' + p.name"
                  (click)="hold(p)"
                >
                  Hold
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
                @if (rowError(); as e) {
                  @if (e.id === p.id) {
                    <p role="alert" data-testid="person-error">{{ e.message }}</p>
                  }
                }
                <label [for]="'person-patron-select-' + p.id" class="visually-hidden">Patron of {{ p.name }}</label>
                <select
                  [id]="'person-patron-select-' + p.id"
                  data-testid="person-patron-select"
                  [value]="patronChoices()[p.id] ?? ''"
                  (change)="setPatronChoice(p.id, value($event))"
                >
                  <option value="" [selected]="!patronChoices()[p.id]">Choose a patron</option>
                  @for (o of patronOptions(p); track o.id) {
                    <option [value]="'' + o.id" [selected]="patronChoices()[p.id] === '' + o.id">{{ o.name }}</option>
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
                @if (patronError(); as e) {
                  @if (e.id === p.id) {
                    <p role="alert" data-testid="person-patron-error">{{ e.message }}</p>
                  }
                }
                <app-person-plans [personId]="p.id" [personName]="p.name" />
              </td>
              <td
                app-claims
                [personId]="p.id"
                [personName]="p.name"
                [claims]="claimsOf(p.id)"
                [grades]="p.grades"
                [rates]="rates()"
                [periods]="periods()"
                [people]="people()"
                [retiredFrom]="p.retiredFrom"
                [retirements]="p.retirements"
                [selected]="selectedClaims()"
                (selectToggled)="selectClaim($event.id, $event.checked)"
                (saved)="claimSaved($event)"
              ></td>
            </tr>
          }
        </tbody>
      </table>
      <p>
        <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
        @if (bulkOpenResult(); as r) {
          Opened <strong data-testid="bulk-opened">{{ r.opened }}</strong>,
          refused <strong data-testid="bulk-refused">{{ r.refused }}</strong>
        }
      </p>
      @if (bulkOpenError(); as message) {
        <p role="alert" data-testid="bulk-open-error">{{ message }}</p>
      }
      <p>
        <button type="button" data-testid="bulk-settle" (click)="bulkSettle()">Settle selected claims</button>
        Last batch <strong data-testid="bulk-settle-batch">{{ session.lastSettleBatch() ?? '' }}</strong>
        @if (bulkSettleResult(); as r) {
          Settled <strong data-testid="bulk-settled">{{ r.settled }}</strong>,
          refused <strong data-testid="bulk-settle-refused">{{ r.refused }}</strong>
        }
      </p>
      @if (bulkSettleError(); as message) {
        <p role="alert" data-testid="bulk-settle-error">{{ message }}</p>
      }
      <p>
        <label for="bulk-reassign-to">Reassign selected claims to</label>
        <select
          id="bulk-reassign-to"
          data-testid="bulk-reassign-to"
          (change)="bulkReassignTo.set(value($event)); bulkReassignError.set(null)"
        >
          <option value="" [selected]="!bulkReassignTo()">Pick a person…</option>
          @for (o of people(); track o.id) {
            <option [value]="o.id" [selected]="bulkReassignTo() === '' + o.id">{{ o.name }}</option>
          }
        </select>
        <button type="button" data-testid="bulk-reassign" (click)="bulkReassign()">Reassign selected claims</button>
        @if (bulkReassignResult(); as r) {
          Reassigned <strong data-testid="bulk-reassigned">{{ r.reassigned }}</strong>,
          refused <strong data-testid="bulk-reassign-refused">{{ r.refused }}</strong>
        }
      </p>
      @if (bulkReassignError(); as message) {
        <p role="alert" data-testid="bulk-reassign-error">{{ message }}</p>
      }
      <p>
        <label for="bulk-redate-date">Re-date selected claims to</label>
        <input
          id="bulk-redate-date"
          data-testid="bulk-redate-date"
          type="date"
          [value]="bulkRedateDate()"
          (input)="bulkRedateDate.set(value($event)); bulkRedateError.set(null)"
        />
        <button type="button" data-testid="bulk-redate" (click)="bulkRedate()">Re-date selected claims</button>
        @if (bulkRedateResult(); as r) {
          Re-dated <strong data-testid="bulk-redated">{{ r.redated }}</strong>,
          refused <strong data-testid="bulk-redate-refused">{{ r.refused }}</strong>
        }
      </p>
      @if (bulkRedateError(); as message) {
        <p role="alert" data-testid="bulk-redate-error">{{ message }}</p>
      }
      <p>
        <label for="bulk-grade">Grade for selected people</label>
        <select
          id="bulk-grade"
          data-testid="bulk-grade"
          [value]="bulkGradeValue()"
          (change)="bulkGradeValue.set(value($event)); clearBulkGradeError()"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === bulkGradeValue()">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">From (blank: today)</label>
        <input
          id="bulk-grade-from"
          data-testid="bulk-grade-from"
          type="date"
          [value]="bulkGradeFrom()"
          (input)="bulkGradeFrom.set(value($event)); clearBulkGradeError()"
        />
        <button type="button" data-testid="bulk-grade-apply" (click)="bulkGrade()">Grade selected people</button>
        @if (bulkGraded() !== null) {
          Changed <strong data-testid="bulk-graded">{{ bulkGraded() }}</strong>
        }
      </p>
      <p>
        <label for="bulk-retire-date">Retire selected people from (blank: today)</label>
        <input
          id="bulk-retire-date"
          data-testid="bulk-retire-date"
          type="date"
          [value]="bulkRetireFrom()"
          (input)="bulkRetireFrom.set(value($event)); clearBulkGradeError()"
        />
        <button type="button" data-testid="bulk-retire" (click)="bulkRetire()">Retire selected people</button>
        @if (bulkRetired() !== null) {
          Retired <strong data-testid="bulk-retired">{{ bulkRetired() }}</strong>
        }
      </p>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <button type="button" data-testid="export-claims" (click)="exportClaims()">Export claims (CSV)</button>
      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Settled total <strong data-testid="rollup-settled-total">{{ settledTotal() }}</strong></p>
      <p>Open total <strong data-testid="rollup-open-total">{{ openTotal() }}</strong></p>
      <p>Overdue <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong></p>
      <p>Overdue total <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>
      <p>Cut total <strong data-testid="rollup-cut-total">{{ cutTotal() }}</strong></p>
      @if (tagTotals().length > 0) {
        <table>
          <caption>Due total by tag</caption>
          <thead>
            <tr><th scope="col">Tag</th><th scope="col">Due total</th></tr>
          </thead>
          <tbody>
            @for (t of tagTotals(); track t.tag) {
              <tr>
                <th scope="row">{{ t.tag }}</th>
                <td [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ t.dueTotal }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
      @if (monthTotals().length > 0) {
        <table>
          <caption>Due total by month</caption>
          <thead>
            <tr><th scope="col">Month</th><th scope="col">Due total</th></tr>
          </thead>
          <tbody>
            @for (m of monthTotals(); track m.month) {
              <tr>
                <th scope="row">{{ m.month }}</th>
                <td [attr.data-testid]="'month-' + m.month + '-due-total'">{{ m.dueTotal }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
    <app-summary [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()" [voidReasons]="voidReasonSummary()" />
    <app-classic-summary [periods]="classicPeriodSummary()" [dueTotal]="classicDueTotal()" />
    <app-pools [people]="people()" />
  `,
})
export class People {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly grades = ['A', 'B', 'C'];
  protected readonly people = signal<Person[]>([]);
  /** The people listed: the retired ones only while "show retired" is on. Totals still count everyone. */
  protected readonly listed = computed(() =>
    this.session.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  protected readonly sortKeys: readonly SortKey[] = ['added', 'name', 'grade', 'due-total'];
  /** What this screen sorts the people listed by; it changes only the order shown, never the list order. */
  protected readonly sortBy = signal<SortKey>('added');
  /** The people listed in the order this screen shows them: equal keys keep list order (the sort is stable). */
  protected readonly sorted = computed(() => {
    const listed = this.listed();
    switch (this.sortBy()) {
      case 'added':
        return listed;
      case 'name':
        return [...listed].sort((a, b) => compareKeys(nameKey(a.name), nameKey(b.name)));
      case 'grade':
        return [...listed].sort((a, b) => compareKeys(this.gradeOf(a), this.gradeOf(b)));
      case 'due-total': {
        const due = new Map(listed.map((p) => [p.id, this.dueTotalOf(p)]));
        const num = (p: Person) => {
          const d = Number(due.get(p.id));
          return Number.isFinite(d) ? d : Infinity;
        };
        return [...listed].sort((a, b) => (num(a) < num(b) ? -1 : num(a) > num(b) ? 1 : 0));
      }
    }
  });
  /** The date typed to retire each person from, by id; blank retires from the sheet's today. */
  protected readonly retireDates = signal<Record<number, string>>({});
  protected readonly inlineGrades = signal<Record<number, string>>({});
  /** The alias typed on each person's row, by person id. */
  protected readonly aliasInputs = signal<Record<number, string>>({});
  /** The person chosen on each person's row to merge them into, by person id, as the chosen person's id. */
  protected readonly mergeChoices = signal<Record<number, string>>({});
  /** The sheet id typed on each person's row to transfer them to, by person id. */
  protected readonly transferTos = signal<Record<number, string>>({});
  /** The date typed beside each person's statement button, by person id: blank takes the statement now. */
  protected readonly statementAsOfs = signal<Record<number, string>>({});
  /** The from-date typed for each person's next hold, by person id. */
  protected readonly holdFroms = signal<Record<number, string>>({});
  /** The to-date typed for each person's next hold, by person id. */
  protected readonly holdTos = signal<Record<number, string>>({});
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) {
      const list = by.get(c.personId);
      if (list) list.push(c);
      else by.set(c.personId, [c]);
    }
    return by;
  });
  /** The pools, whose cuts each person's cut total adds up. */
  private readonly pools = viewChild(Pools);
  /** The sheet's cut total: everyone's cut totals added up. */
  protected readonly cutTotal = computed(() => this.pools()?.cutTotal() ?? '0.00');
  protected readonly claimedTotal = computed(() => sumAmounts(this.people().map((p) => this.claimedBy(p.id))));
  /** The sheet's rates, by grade and then from-date. */
  protected readonly rates = signal<Rate[]>([]);
  /** The sheet's periods in date order. */
  protected readonly periods = signal<Period[]>([]);
  /**
   * Each person's live due-total for each period, by person id and then period name: the dues as shown now of their
   * claims due by a date in it that were OPEN, PENDING or SETTLED at the end of its last day, skipping the n/a ones; a period they have none in is missing.
   */
  private readonly livePersonPeriodDues = computed(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const dues = new Map<number, Map<string, string[]>>();
    for (const c of this.claims()) {
      const period = periodOn(this.periods(), c.dueBy);
      const person = byId.get(c.personId);
      if (!period || person === undefined || !liveAtEnd(c, person, this.session.today(), period.to)) continue;
      const theirs = dues.get(person.id) ?? new Map<string, string[]>();
      const list = theirs.get(period.name) ?? [];
      list.push(claimDue(c, this.rates(), person.grades, this.session));
      theirs.set(period.name, list);
      dues.set(person.id, theirs);
    }
    return new Map(
      [...dues].map(([id, theirs]) => [id, new Map([...theirs].map(([name, list]) => [name, sumDues(list)]))]),
    );
  });
  /** Each period's due-total by name: the sum of everyone's due-total for it. */
  protected readonly periodDueTotals = computed(
    () =>
      new Map(
        this.periods().map((per) => [per.name, sumDues(this.people().map((p) => this.periodDueTotalOf(p, per)))]),
      ),
  );
  /** Everyone's due-total added up, skipping the n/a ones and, with three or more, the highest and lowest. */
  protected readonly dueTotal = computed(() => trimmedSumDues(this.people().map((p) => this.dueTotalOf(p))));
  /** Everyone's settled-total added up, not trimmed. */
  protected readonly settledTotal = computed(() => sumDues(this.people().map((p) => this.settledTotalOf(p))));
  /** Everyone's open-total added up, not trimmed. */
  protected readonly openTotal = computed(() => sumDues(this.people().map((p) => this.openTotalOf(p))));
  /** How many of everyone's counted claims are overdue. */
  protected readonly overdueCount = computed(() =>
    this.people().reduce((total, p) => total + this.overdueOf(p).length, 0),
  );
  /** Everyone's overdue-total added up, with three or more leaving out the highest and lowest. */
  protected readonly overdueTotal = computed(() => trimmedSumDues(this.people().map((p) => this.overdueTotalOf(p))));
  /** The person on the sheet with the signed-in user's name, by name or alias as names are compared; none if no one. */
  private readonly me = computed(() => {
    const key = nameKey(this.session.user() ?? '');
    if (!key) return undefined;
    const people = this.people();
    return (
      people.find((p) => nameKey(p.name) === key) ?? people.find((p) => p.aliases.some((a) => nameKey(a) === key))
    );
  });
  /** The signed-in user's own due-total, as their row shows it; blank when no one on the sheet has their name. */
  protected readonly myDueTotal = computed(() => {
    const me = this.me();
    return me ? this.dueTotalOf(me) : '';
  });
  /** The signed-in user's own overdue-total, as their row shows it; blank when no one on the sheet has their name. */
  protected readonly myOverdueTotal = computed(() => {
    const me = this.me();
    return me ? this.overdueTotalOf(me) : '';
  });
  /** The figures of the sheet's descendants added up, each as that sheet shows them; none until read. */
  private readonly descendantFigures = signal<DescendantFigures>(NO_DESCENDANTS);
  /** While the sheet is shown as of a date, the sheet's and its descendants' figures as the store holds them now. */
  private readonly liveConsolidated = signal<DescendantFigures | null>(null);
  /** The sheet's people and its descendants' added up. */
  protected readonly consolidatedPeople = computed(
    () => this.liveConsolidated()?.people ?? this.people().length + this.descendantFigures().people,
  );
  /** The sheet's due-total as shown and its descendants' added up. */
  protected readonly consolidatedDueTotal = computed(() =>
    this.liveConsolidated()?.dueTotal ?? sumDues([this.dueTotal(), this.descendantFigures().dueTotal]),
  );
  /** The sheet's overdue-total as shown and its descendants' added up. */
  protected readonly consolidatedOverdueTotal = computed(() =>
    this.liveConsolidated()?.overdueTotal ?? sumDues([this.overdueTotal(), this.descendantFigures().overdueTotal]),
  );
  /** The person's tags, sorted; none when they have none. */
  protected personTagsOf(person: Person): string[] {
    return person.tags ? person.tags.split(',') : [];
  }

  /** Every tag ever used on the sheet, sorted. */
  protected readonly tags = signal<string[]>([]);
  /**
   * Per tag ever used on the sheet: the dues of the claims that count in the live totals and carry it, skipping the
   * n/a ones (a claim with two tags counts in both).
   */
  protected readonly tagTotals = computed(() =>
    this.tags().map((tag) => ({
      tag,
      dueTotal: sumDues(this.people().map((p) => this.liveDuesOf(p, (c) => tagsOf(c).includes(tag) || this.personTagsOf(p).includes(tag)))),
    })),
  );
  /**
   * Per calendar month (yyyy-mm) any of the sheet's claims is dated in, oldest first: the dues of the claims dated in it
   * that count in the live totals, skipping the n/a ones.
   */
  protected readonly monthTotals = computed(() =>
    [...new Set(this.claims().map((c) => c.date.slice(0, 7)))].sort().map((month) => ({
      month,
      dueTotal: sumDues(this.people().map((p) => this.liveDuesOf(p, (c) => c.date.startsWith(month)))),
    })),
  );
  /** Per grade: the people not retired on it today, as the list shows their grade, and their due-totals added up. */
  protected readonly gradeSummary = computed<GradeSummary[]>(() =>
    this.grades.map((grade) => {
      const on = this.people().filter((p) => !p.retired && this.gradeOf(p) === grade);
      return {
        grade,
        count: on.length,
        dueTotal: sumDues(on.map((p) => this.dueTotalOf(p))),
        active: on.filter((p) => this.isActive(p)).length,
      };
    }),
  );
  /**
   * Per period: its due-total as the period list shows it, and its change from the period before it by start date
   * (blank for the earliest).
   */
  protected readonly periodSummary = computed<PeriodSummary[]>(() => {
    const totals = new Map(
      this.periods().map((p) => [p.name, p.closedTotal ?? this.periodDueTotals().get(p.name) ?? '0.00']),
    );
    const byStart = [...this.periods()].sort((a, b) => a.from.localeCompare(b.from));
    const changes = new Map(
      byStart.map((p, i) => [p.name, i === 0 ? '' : changeFrom(totals.get(p.name)!, totals.get(byStart[i - 1].name)!)]),
    );
    return this.periods().map((p) => ({ name: p.name, dueTotal: totals.get(p.name)!, change: changes.get(p.name)! }));
  });
  /**
   * Per period, the classic way: once it is closed, the figure fixed then; otherwise the dues of the claims due by a
   * date in it that count in the live totals by their status now, skipping the n/a ones.
   */
  protected readonly classicPeriodSummary = computed<ClassicPeriodSummary[]>(() => {
    const today = this.session.today();
    const byId = new Map(this.people().map((p) => [p.id, p]));
    const dues = new Map<string, string[]>();
    for (const c of this.claims()) {
      const period = periodOn(this.periods(), c.dueBy);
      const person = byId.get(c.personId);
      if (!period || person === undefined || !live(c, person, today)) continue;
      const list = dues.get(period.name) ?? [];
      list.push(claimDue(c, this.rates(), person.grades, this.session));
      dues.set(period.name, list);
    }
    return this.periods().map((p) => ({ name: p.name, dueTotal: p.closedTotal ?? sumDues(dues.get(p.name) ?? []) }));
  });
  /** Everyone's due-total added up, skipping the n/a ones, with no trimming. */
  protected readonly classicDueTotal = computed(() => sumDues(this.people().map((p) => this.dueTotalOf(p))));
  /** Per status: how many of the sheet's claims have it. */
  protected readonly statusSummary = computed<StatusSummary[]>(() =>
    ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'REVIEW'].map((status) => ({
      status,
      count: this.claims().filter((c) => c.status === status).length,
    })),
  );
  private readonly voidReasons = inject(VoidReasons);
  /** Per void reason on the sheet's list: how many VOID claims were voided with it. */
  protected readonly voidReasonSummary = computed<VoidReasonSummary[]>(() =>
    this.voidReasons.list().map((r) => ({
      reason: r.reason,
      count: this.claims().filter((c) => c.status === 'VOID' && c.voidReason === r.reason).length,
    })),
  );
  protected readonly name = signal('');
  protected readonly grade = signal('A');
  /** The date the form's grade applies from; blank for the sheet's today. */
  protected readonly gradeFrom = signal('');
  /** The person's own terms in whole days; blank for none. */
  protected readonly terms = signal('');
  /** The person's contact; blank for none. */
  protected readonly contact = signal('');
  /** The person's tags, comma separated; blank for none. */
  protected readonly personTags = signal('');
  /** The id of the person whose editor is open, or null when the form adds a person. */
  protected readonly editing = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion = 0;
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  /** The quick-add bar: a name and an amount, and the error of its last refused submit. */
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal<string | null>(null);
  private quickAdding = false;
  /** The paste box: its lines, how many its last paste added (null while it runs and before the first), its error. */
  protected readonly pasteText = signal('');
  protected readonly pasteAdded = signal<number | null>(null);
  protected readonly pasteError = signal<string | null>(null);
  private pasting = false;
  private readonly plans = inject(Plans);
  /** How many claims the last generate of the plans made (null while it runs and before the first), its error. */
  protected readonly planGenerated = signal<number | null>(null);
  protected readonly planGenerateError = signal<string | null>(null);
  private generating = false;
  /**
   * The import box: its lines, how many its last import added and updated (null while it runs and before the first),
   * its error.
   */
  protected readonly importText = signal('');
  protected readonly importAdded = signal<number | null>(null);
  protected readonly importUpdated = signal<number | null>(null);
  protected readonly importError = signal<string | null>(null);
  private importing = false;
  /** The error of the last action on a person's row that was refused, and the person it belongs to. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);
  /** The patron chosen on each person's row, by person id, as the chosen person's id; blank with none chosen. */
  protected readonly patronChoices = signal<Record<number, string>>({});
  /** The error of the last refused add of a patron, and the person it belongs to. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** The ids of the claims ticked to act on together. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** How many the last bulk open opened and refused; null while it runs and before the first. */
  protected readonly bulkOpenResult = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal<string | null>(null);
  /** How many the last bulk settle settled and refused; null while it runs and before the first. */
  protected readonly bulkSettleResult = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal<string | null>(null);
  /** The id (as text) of the person picked to move the ticked claims to; blank with none picked. */
  protected readonly bulkReassignTo = signal('');
  /** How many the last bulk reassign moved and refused; null while it runs and before the first. */
  protected readonly bulkReassignResult = signal<{ reassigned: number; refused: number } | null>(null);
  protected readonly bulkReassignError = signal<string | null>(null);
  /** The date (yyyy-mm-dd) to re-date the ticked claims to; blank with none entered. */
  protected readonly bulkRedateDate = signal('');
  /** How many the last bulk re-date re-dated and refused; null while it runs and before the first. */
  protected readonly bulkRedateResult = signal<{ redated: number; refused: number } | null>(null);
  protected readonly bulkRedateError = signal<string | null>(null);
  /** The ids of the people ticked to give a grade together. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  /** The grade, and its from-date (blank: the sheet's today), to give the ticked people. */
  protected readonly bulkGradeValue = signal('A');
  protected readonly bulkGradeFrom = signal('');
  /** How many people the last bulk grade changed; null while it runs and before the first. */
  protected readonly bulkGraded = signal<number | null>(null);
  /** The date to retire the ticked people from (blank: the sheet's today). */
  protected readonly bulkRetireFrom = signal('');
  /** How many people the last bulk retire retired; null while it runs and before the first. */
  protected readonly bulkRetired = signal<number | null>(null);
  private readonly byId = computed(() => new Map(this.people().map((p) => [p.id, p])));
  private reads = 0;

  constructor() {
    // The rates fall back on the parent sheet's: once it is read or changed, read them again.
    effect(() => {
      const parent = this.session.parent();
      untracked(() => {
        const sheet = this.session.sheet();
        if (parent !== null && sheet) void this.read(sheet);
      });
    });
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.people.set([]);
        this.claims.set([]);
        this.rates.set([]);
        this.periods.set([]);
        this.tags.set([]);
        this.descendantFigures.set(NO_DESCENDANTS);
        this.liveConsolidated.set(null);
        this.retireDates.set({});
        this.inlineGrades.set({});
        this.aliasInputs.set({});
        this.mergeChoices.set({});
        this.transferTos.set({});
        this.statementAsOfs.set({});
        this.holdFroms.set({});
        this.holdTos.set({});
        this.patronChoices.set({});
        this.patronError.set(null);
        this.selectedClaims.set(new Set());
        this.selectedPeople.set(new Set());
        this.bulkGradeValue.set('A');
        this.bulkGradeFrom.set('');
        this.bulkGraded.set(null);
        this.bulkRetireFrom.set('');
        this.bulkRetired.set(null);
        this.bulkOpenResult.set(null);
        this.bulkOpenError.set(null);
        this.bulkSettleResult.set(null);
        this.bulkSettleError.set(null);
        this.bulkReassignTo.set('');
        this.bulkReassignResult.set(null);
        this.bulkReassignError.set(null);
        this.bulkRedateDate.set('');
        this.bulkRedateResult.set(null);
        this.bulkRedateError.set(null);
        this.reset();
        this.quickName.set('');
        this.quickAmount.set('');
        this.quickError.set(null);
        this.pasteText.set('');
        this.pasteAdded.set(null);
        this.pasteError.set(null);
        this.planGenerated.set(null);
        this.planGenerateError.set(null);
        this.importText.set('');
        this.importAdded.set(null);
        this.importUpdated.set(null);
        this.importError.set(null);
        if (sheet) void this.read(sheet);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected sortKey(key: string): SortKey {
    return this.sortKeys.find((k) => k === key) ?? 'added';
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected setHoldFrom(id: number, date: string): void {
    this.holdFroms.update((dates) => ({ ...dates, [id]: date }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  protected setHoldTo(id: number, date: string): void {
    this.holdTos.update((dates) => ({ ...dates, [id]: date }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  protected setStatementAsOf(id: number, date: string): void {
    this.statementAsOfs.update((dates) => ({ ...dates, [id]: date }));
  }

  protected setAliasInput(id: number, alias: string): void {
    this.aliasInputs.update((aliases) => ({ ...aliases, [id]: alias }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  protected setMergeChoice(id: number, choice: string): void {
    this.mergeChoices.update((choices) => ({ ...choices, [id]: choice }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /** The people the person can be chosen to merge into: everyone else on the sheet. */
  protected mergeOptions(person: Person): Person[] {
    return this.people().filter((p) => p.id !== person.id);
  }

  /**
   * Merge the person into the chosen one (a steward only, neither retired): the person leaves the list, then re-read
   * so the other's claims, aliases, patrons and every total show what the store holds.
   */
  protected async merge(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const intoId = Number(this.mergeChoices()[person.id] ?? '');
    if (!intoId) {
      this.rowError.set({ id: person.id, message: 'Choose a person to merge into.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/merge`;
    try {
      await firstValueFrom(this.http.post<Person>(url, { intoId }, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.filter((p) => p.id !== person.id));
        this.mergeChoices.update((choices) => ({ ...choices, [person.id]: '' }));
        void this.read(sheet);
      }
    } catch {
      const into = this.byId().get(intoId)?.name ?? 'that person';
      this.rowError.set({ id: person.id, message: `${person.name} could not be merged into ${into}.` });
    }
  }

  protected setTransferTo(id: number, to: string): void {
    this.transferTos.update((tos) => ({ ...tos, [id]: to }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /**
   * Transfer the person to the sheet typed (a steward only, not retired, not this sheet): the person leaves the list,
   * then re-read so every total shows what the store holds.
   */
  protected async transfer(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const to = (this.transferTos()[person.id] ?? '').trim();
    if (!to) {
      this.rowError.set({ id: person.id, message: 'Type a sheet id to transfer to.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/transfer`;
    try {
      await firstValueFrom(this.http.post<Person>(url, { to }, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.filter((p) => p.id !== person.id));
        this.transferTos.update((tos) => ({ ...tos, [person.id]: '' }));
        void this.read(sheet);
      }
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be transferred to ${to}.` });
    }
  }

  /** The grade chosen in the person's row select: their grade in force until another is chosen. */
  protected inlineGradeOf(person: Person): string {
    return this.inlineGrades()[person.id] ?? this.gradeOf(person);
  }

  protected setInlineGrade(id: number, grade: string): void {
    this.inlineGrades.update((grades) => ({ ...grades, [id]: grade }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /** Save the grade chosen in the person's row from the sheet's today, as the person form would save it. */
  protected async saveInlineGrade(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const body = {
      name: person.name,
      grade: this.inlineGradeOf(person),
      gradeFrom: null,
      terms: person.terms === null ? null : String(person.terms),
      contact: person.contact,
      tags: null,
      version: null,
    };
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}`;
    try {
      const saved = await firstValueFrom(this.http.put<Person>(url, body, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        this.inlineGrades.update((grades) =>
          Object.fromEntries(Object.entries(grades).filter(([id]) => Number(id) !== person.id)),
        );
        void this.read(sheet);
      }
    } catch {
      this.rowError.set({ id: person.id, message: `The grade of ${person.name} could not be saved.` });
    }
  }

  protected setRetireDate(id: number, date: string): void {
    this.retireDates.update((dates) => ({ ...dates, [id]: date }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  /** The sum of the person's OPEN, PENDING and SETTLED claims' amounts, with two decimals, leaving out corrections; open ones dated within a hold, or any once their retirement date is reached, not unless a statement binds them. */
  protected claimedBy(personId: number): string {
    const person = this.people().find((p) => p.id === personId) ?? { holds: [], retiredFrom: null };
    return sumAmounts(this.claimsOf(personId).filter((c) => c.corrects === null && live(c, person, this.session.today())).map((c) => c.amount));
  }

  /** The person's grade in force on the sheet's today. */
  protected gradeOf(person: Person): string {
    const today = this.session.today();
    return (today === null ? undefined : gradeOn(person.grades, today)) ?? person.grade;
  }

  /** The grade's rate in force on the sheet's today, or undefined when none is. */
  protected rateOf(grade: string): string | undefined {
    const today = this.session.today();
    return today === null ? undefined : rateOn(this.rates(), grade, today);
  }

  /**
   * The sum of the person's OPEN, PENDING and SETTLED claims' dues, each already rounded, skipping the n/a ones; n/a when
   * their grade in force today has no rate in force today, unless a claim keeps a due fixed by settling or a statement
   * or has a due and is dated on or before today. Held down to the sheet's cap.
   */
  protected dueTotalOf(person: Person): string {
    return capped(this.uncappedDueTotalOf(person), this.session.cap());
  }

  /**
   * The person's due-total for the period: once it is closed or soft-closed, the figure fixed then (0.00 for a person added since);
   * while it is open, the dues of their counting claims dated in it.
   */
  protected periodDueTotalOf(person: Person, period: Period): string {
    if (period.closed || period.softClosed) return period.personTotals?.[person.id] ?? '0.00';
    return this.livePersonPeriodDues().get(person.id)?.get(period.name) ?? '0.00';
  }

  /** The person's cuts across every pool they are a member of, added up; a pool that shows no cuts adds nothing. */
  protected cutTotalOf(person: Person): string {
    return this.pools()?.cutTotals().get(person.id) ?? '0.00';
  }

  /** How far the sum of the person's dues is above the sheet's cap; 0.00 when it is not. */
  protected excessOf(person: Person): string {
    return excessOver(this.uncappedDueTotalOf(person), this.session.cap());
  }

  /** The sum of the person's dues as {@link dueTotalOf} has it, before the cap. */
  private uncappedDueTotalOf(person: Person): string {
    const counted = this.claimsOf(person.id).filter((c) => live(c, person, this.session.today()));
    const dues = counted.map((c) => claimDue(c, this.rates(), person.grades, this.session));
    const today = this.session.today();
    // A due under a grade the person had on an earlier date counts too.
    const anyDated = today !== null && counted.some((c, i) => dues[i] !== 'n/a' && c.date <= today);
    if (this.rateOf(this.gradeOf(person)) === undefined && !counted.some(dueFixed) && !anyDated) return 'n/a';
    return sumDues(dues);
  }

  /**
   * The dues of the person's SETTLED claims that count in the live totals, at their frozen figures, skipping the n/a
   * ones: with {@link openTotalOf} it adds up to the due-total before the cap.
   */
  protected settledTotalOf(person: Person): string {
    return this.liveDuesOf(person, (c) => c.status === 'SETTLED');
  }

  /** The dues of the person's OPEN and PENDING claims that count in the live totals, skipping the n/a ones. */
  protected openTotalOf(person: Person): string {
    return this.liveDuesOf(person, isOpen);
  }

  /** The sum of the dues of the person's claims that count in the live totals and pass the test. */
  private liveDuesOf(person: Person, test: (c: Claim) => boolean): string {
    const today = this.session.today();
    return sumDues(
      this.claimsOf(person.id)
        .filter((c) => test(c) && live(c, person, today))
        .map((c) => claimDue(c, this.rates(), person.grades, this.session)),
    );
  }

  /**
   * Whether the person is active: they have an OPEN or PENDING claim that counts in the live totals dated within the
   * 30 days up to and including the sheet's today.
   */
  protected isActive(person: Person): boolean {
    const today = this.session.today();
    if (today === null) return false;
    const since = plusDays(today, -29);
    return this.claimsOf(person.id).some(
      (c) => isOpen(c) && live(c, person, today) && since <= c.date && c.date <= today,
    );
  }

  /** The person's claims that count in the live totals and are overdue. */
  protected overdueOf(person: Person): Claim[] {
    return this.claimsOf(person.id).filter(
      (c) => live(c, person, this.session.today()) && overdue(c, person.grades, this.session),
    );
  }

  /** The sum of the dues of the person's overdue claims, skipping the n/a ones. */
  protected overdueTotalOf(person: Person): string {
    return sumDues(this.overdueOf(person).map((c) => claimDue(c, this.rates(), person.grades, this.session)));
  }

  /**
   * The dues of the person's OPEN and PENDING claims that count in the live totals, split by the claim's age on the
   * sheet's today (the days from its date): 0 to 30 (a claim dated after today too), 31 to 60, and 61 or more; each
   * skipping the n/a ones.
   */
  protected agedOf(person: Person): [string, string, string] {
    const today = this.session.today();
    const buckets: [string[], string[], string[]] = [[], [], []];
    if (today !== null) {
      for (const c of this.claimsOf(person.id)) {
        if (!isOpen(c) || !live(c, person, today)) continue;
        const days = Math.round((Date.parse(today) - Date.parse(c.date)) / 86_400_000);
        buckets[days <= 30 ? 0 : days <= 60 ? 1 : 2].push(claimDue(c, this.rates(), person.grades, this.session));
      }
    }
    return [sumDues(buckets[0]), sumDues(buckets[1]), sumDues(buckets[2])];
  }

  /** Each of the person's patrons as their name and share, sorted by name, comma and space separated. */
  protected patronsOf(person: Person): string {
    return this.patronsListOf(person)
      .sort((a, b) => a.patron.name.localeCompare(b.patron.name))
      .map((p) => `${p.patron.name} ${p.share}`)
      .join(', ');
  }

  /**
   * The person's patrons' settled figures, each times the patron's share divided by 100, added up and rounded half to
   * even: a patron's figure is their SETTLED claims dated within the 90 days up to and including the sheet's today,
   * each at its frozen figure; 0.00 with none.
   */
  protected backingOf(person: Person): string {
    const today = this.session.today();
    if (today === null) return '0.00';
    const since = plusDays(today, -89);
    return sumShared(
      this.patronsListOf(person).map(({ patron, share }) => ({
        share,
        figure: sumDues(
          this.claimsOf(patron.id)
            .filter((c) => c.status === 'SETTLED' && since <= c.date && c.date <= today)
            .map((c) => claimDue(c, this.rates(), patron.grades, this.session)),
        ),
      })),
    );
  }

  /** The people the person could still name as a patron: everyone else on the sheet not already named. */
  protected patronOptions(person: Person): Person[] {
    return this.people().filter((p) => p.id !== person.id && !person.patronIds.includes(p.id));
  }

  protected setPatronChoice(id: number, choice: string): void {
    this.patronChoices.update((choices) => ({ ...choices, [id]: choice }));
    if (this.patronError()?.id === id) this.patronError.set(null);
  }

  /** Name the chosen person as the person's patron, then re-read so the backing shows what the store holds. */
  protected async addPatron(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const patronId = Number(this.patronChoices()[person.id] ?? '');
    if (!patronId) {
      this.patronError.set({ id: person.id, message: 'Choose a person to name as a patron.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/patrons`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { patronId }, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        this.patronChoices.update((choices) => ({ ...choices, [person.id]: '' }));
        void this.read(sheet);
      }
    } catch {
      const patron = this.byId().get(patronId)?.name ?? 'That person';
      this.patronError.set({ id: person.id, message: `${patron} could not be named a patron of ${person.name}.` });
    }
  }

  /** The person's patrons, each with their share, in the order they were named. */
  private patronsListOf(person: Person): { patron: Person; share: string }[] {
    return person.patronIds.flatMap((id, i) => {
      const patron = this.byId().get(id);
      return patron === undefined ? [] : [{ patron, share: person.patronShares[i] ?? '0.00' }];
    });
  }

  /** Show a set rate at once, then re-read so the screen holds what the store does. */
  protected rateSaved(rate: Rate): void {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.rates.update((list) =>
      [...list.filter((r) => r.level !== rate.level || r.grade !== rate.grade || r.from !== rate.from), rate].sort(
        (a, b) => a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? ''),
      ),
    );
    void this.read(sheet);
  }

  /** Re-read once rates were imported, so the screen holds what the store does. */
  protected ratesImported(): void {
    const sheet = this.session.sheet();
    if (sheet) void this.read(sheet);
  }

  /** Show an added period at once, then re-read so the screen holds what the store does. */
  protected periodSaved(period: Period): void {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.periods.update((list) =>
      [...list.filter((p) => p.id !== period.id), period].sort((a, b) => a.from.localeCompare(b.from)),
    );
    void this.read(sheet);
  }

  /** Show an added or saved claim at once, then re-read so the screen holds what the store does. */
  protected claimSaved(claim: Claim): void {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id)
        ? list.map((c) => (c.id === claim.id ? claim : c)).sort(byListed)
        : [claim, ...list].sort(byListed),
    );
    void this.read(sheet);
  }

  protected selectClaim(id: number, checked: boolean): void {
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
    this.bulkOpenError.set(null);
    this.bulkSettleError.set(null);
    this.bulkReassignError.set(null);
    this.bulkRedateError.set(null);
  }

  protected selectPerson(id: number, checked: boolean): void {
    this.selectedPeople.update((ids) => {
      const next = new Set(ids);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /** A bulk grade's error shows on the row of the first ticked person; changing the form clears it. */
  protected clearBulkGradeError(): void {
    const error = this.rowError();
    if (error && this.selectedPeople().has(error.id)) this.rowError.set(null);
  }

  /**
   * Give the ticked people the chosen grade from the typed from-date (blank: the sheet's today), in list order, each as
   * the person form would; then re-read so the totals show what the store holds.
   */
  protected async bulkGrade(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.rowError.set(null);
    this.patronError.set(null);
    this.bulkGraded.set(null);
    const ids = this.people()
      .filter((p) => this.selectedPeople().has(p.id))
      .map((p) => p.id);
    const first = ids.length > 0 ? ids[0] : null;
    const from = this.bulkGradeFrom().trim();
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      if (first !== null) this.rowError.set({ id: first, message: 'Pick a date to grade from, or leave it blank for today.' });
      return;
    }
    try {
      const result = await firstValueFrom(
        this.http.post<{ changed: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/bulk-grade`,
          { ids, grade: this.bulkGradeValue(), from: from || null },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedPeople.set(new Set());
      this.bulkGraded.set(result.changed);
      await this.read(sheet);
    } catch {
      if (this.session.sheet() === sheet && first !== null) {
        this.rowError.set({ id: first, message: 'The selected people could not be given the grade.' });
      }
    }
  }

  /**
   * Retire the ticked people from the typed date (blank: the sheet's today), in list order, each as the row's retire
   * would; then re-read so the totals show what the store holds.
   */
  protected async bulkRetire(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.rowError.set(null);
    this.patronError.set(null);
    this.bulkRetired.set(null);
    const ids = this.people()
      .filter((p) => this.selectedPeople().has(p.id))
      .map((p) => p.id);
    const first = ids.length > 0 ? ids[0] : null;
    const from = this.bulkRetireFrom().trim();
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      if (first !== null) this.rowError.set({ id: first, message: 'Pick a date to retire from, or leave it blank for today.' });
      return;
    }
    try {
      const result = await firstValueFrom(
        this.http.post<{ retired: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/bulk-retire`,
          { ids, from: from || null },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedPeople.set(new Set());
      this.bulkRetired.set(result.retired);
      await this.read(sheet);
    } catch {
      if (this.session.sheet() === sheet && first !== null) {
        this.rowError.set({ id: first, message: 'The selected people could not be retired.' });
      }
    }
  }

  /** Open the ticked claims together, in list order; each that cannot be opened is left as it is. */
  protected async bulkOpen(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.bulkOpenResult.set(null);
    this.bulkOpenError.set(null);
    const ids = [...this.selectedClaims()];
    try {
      const result = await firstValueFrom(
        this.http.post<{ opened: number; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-open`,
          { ids },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedClaims.set(new Set());
      this.bulkOpenResult.set(result);
      await this.read(sheet);
    } catch {
      if (this.session.sheet() === sheet) this.bulkOpenError.set('The selected claims could not be opened.');
    }
  }

  /** Settle the ticked claims together, in list order; each that cannot be settled is left as it is. */
  protected async bulkSettle(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.bulkSettleResult.set(null);
    this.bulkSettleError.set(null);
    const ids = [...this.selectedClaims()];
    try {
      const result = await firstValueFrom(
        this.http.post<{ settled: number; refused: number; batch: number | null }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-settle`,
          { ids },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedClaims.set(new Set());
      this.bulkSettleResult.set(result);
      this.session.lastSettleBatch.set(result.batch);
      await this.read(sheet);
    } catch {
      if (this.session.sheet() === sheet) this.bulkSettleError.set('The selected claims could not be settled.');
    }
  }

  /** Move the ticked claims to the picked person together, in list order; each that cannot be moved is left as it is. */
  protected async bulkReassign(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.bulkReassignResult.set(null);
    this.bulkReassignError.set(null);
    const to = this.people().find((p) => String(p.id) === this.bulkReassignTo());
    if (!to) {
      this.bulkReassignError.set('Pick the person to reassign the selected claims to.');
      return;
    }
    const ids = [...this.selectedClaims()];
    try {
      const result = await firstValueFrom(
        this.http.post<{ reassigned: number; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-reassign`,
          { ids, personId: to.id },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedClaims.set(new Set());
      this.bulkReassignResult.set(result);
      await this.read(sheet);
    } catch {
      if (this.session.sheet() === sheet) this.bulkReassignError.set('The selected claims could not be reassigned.');
    }
  }

  /** Re-date the ticked claims together, in list order; each that cannot be re-dated is left as it is. */
  protected async bulkRedate(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.bulkRedateResult.set(null);
    this.bulkRedateError.set(null);
    const date = this.bulkRedateDate().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.bulkRedateError.set('Pick the date to re-date the selected claims to.');
      return;
    }
    const ids = [...this.selectedClaims()];
    try {
      const result = await firstValueFrom(
        this.http.post<{ redated: number; refused: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/bulk-redate`,
          { ids, date },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.selectedClaims.set(new Set());
      this.bulkRedateResult.set(result);
      await this.read(sheet);
    } catch {
      if (this.session.sheet() === sheet) this.bulkRedateError.set('The selected claims could not be re-dated.');
    }
  }

  /** Give the person the typed alias: not blank, and not a name or alias already on the sheet. */
  protected async addAlias(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const alias = (this.aliasInputs()[person.id] ?? '').trim();
    if (!alias) {
      this.rowError.set({ id: person.id, message: 'Type an alias to add.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/aliases`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { alias }, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        this.aliasInputs.update((aliases) => ({ ...aliases, [person.id]: '' }));
      }
    } catch {
      this.rowError.set({ id: person.id, message: `${alias} is already a name or alias on the sheet.` });
    }
  }

  /** Swap the person with the one above or below them in the list; refused at the top going up or the bottom going down. */
  protected async move(person: Person, direction: 'up' | 'down'): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/move`;
    try {
      const list = await firstValueFrom(this.http.post<Person[]>(url, { direction }, this.session.headers()));
      if (this.session.sheet() !== sheet) return;
      // A read started before the move would show the old order: it is dropped.
      this.reads++;
      this.people.set(list);
    } catch {
      this.rowError.set({
        id: person.id,
        message: `${person.name} is already at the ${direction === 'up' ? 'top' : 'bottom'} of the list.`,
      });
    }
  }

  /** Start or stop watching the person as the signed-in user. */
  protected async watch(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/watch`;
    try {
      const saved = await firstValueFrom(
        this.http.put<Person>(url, { on: !person.watched }, this.session.headers()),
      );
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be watched or unwatched.` });
    }
  }

  /**
   * Take a statement of the person as of the typed date (blank: now), then re-read so their claims show the dues it
   * bound them to.
   */
  protected async statement(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const asOf = (this.statementAsOfs()[person.id] ?? '').trim();
    if (asOf && !/^\d{4}-\d{2}-\d{2}$/.test(asOf)) {
      this.rowError.set({ id: person.id, message: 'Pick a date to take the statement as of, or leave it blank for now.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/statement`;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(url, asOf ? { asOf } : null, this.session.headers()),
      );
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        this.statementAsOfs.update((dates) => ({ ...dates, [person.id]: '' }));
        void this.read(sheet);
      }
    } catch {
      // The date of a refused statement does not carry into the next one.
      this.statementAsOfs.update((dates) => ({ ...dates, [person.id]: '' }));
      this.rowError.set({ id: person.id, message: `A statement of ${person.name} could not be taken.` });
    }
  }

  /** Cancel the person's last statement, then re-read so their claims show their live dues again. */
  protected async unstate(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/statement`;
    try {
      const saved = await firstValueFrom(this.http.delete<Person>(url, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        void this.read(sheet);
      }
    } catch {
      this.rowError.set({ id: person.id, message: `The last statement of ${person.name} could not be cancelled.` });
    }
  }

  /** Retire the person from the typed date, or from the sheet's today when none is typed. */
  protected async retire(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const from = (this.retireDates()[person.id] ?? '').trim();
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.rowError.set({ id: person.id, message: 'Pick a date to retire from, or leave it blank for today.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/retire`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, { from: from || null }, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        this.retireDates.update((dates) => ({ ...dates, [person.id]: '' }));
        void this.read(sheet);
      }
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be retired.` });
    }
  }

  /** Unretire the person on the sheet's today (refused when not retired), then re-read so the totals show it. */
  protected async unretire(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/unretire`;
    try {
      const saved = await firstValueFrom(this.http.post<Person>(url, {}, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        void this.read(sheet);
      }
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be unretired: they are not retired.` });
    }
  }

  /**
   * Hold the person from the typed from-date (blank: the sheet's today) to the typed to-date (blank: no end), then
   * re-read so the totals show what the store holds.
   */
  protected async hold(person: Person): Promise<void> {
    this.rowError.set(null);
    this.patronError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const from = (this.holdFroms()[person.id] ?? '').trim();
    const to = (this.holdTos()[person.id] ?? '').trim();
    const date = /^\d{4}-\d{2}-\d{2}$/;
    if ((from && !date.test(from)) || (to && !date.test(to))) {
      this.rowError.set({ id: person.id, message: 'Pick the dates of the hold, or leave them blank.' });
      return;
    }
    if (from && to && to < from) {
      this.rowError.set({ id: person.id, message: 'A hold cannot end before it starts.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${person.id}/hold`;
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>(url, { from: from || null, to: to || null }, this.session.headers()),
      );
      if (this.session.sheet() === sheet) {
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        this.holdFroms.update((dates) => ({ ...dates, [person.id]: '' }));
        this.holdTos.update((dates) => ({ ...dates, [person.id]: '' }));
        void this.read(sheet);
      }
    } catch {
      this.rowError.set({ id: person.id, message: `${person.name} could not be held.` });
    }
  }

  protected edit(person: Person): void {
    this.rowError.set(null);
    this.patronError.set(null);
    this.editing.set(person.id);
    this.editingVersion = person.version;
    this.name.set(person.name);
    this.grade.set(this.gradeOf(person));
    this.gradeFrom.set('');
    this.terms.set(person.terms === null ? '' : String(person.terms));
    this.contact.set(person.contact ?? '');
    this.personTags.set(this.personTagsOf(person).join(', '));
    this.error.set(null);
  }

  /** Show why the form was refused; a refused save closes the editor, so the form adds a person again. */
  private refuse(message: string): void {
    if (this.editing() !== null) this.reset();
    this.error.set(message);
  }

  protected reset(): void {
    this.editing.set(null);
    this.name.set('');
    this.grade.set('A');
    this.gradeFrom.set('');
    this.terms.set('');
    this.contact.set('');
    this.personTags.set('');
    this.error.set(null);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const editing = this.editing();
    const body = {
      name: this.name(),
      grade: this.grade(),
      gradeFrom: this.gradeFrom() || null,
      terms: this.terms().trim() || null,
      contact: this.contact().trim() || null,
      tags: this.personTags().trim(),
      version: editing === null ? null : this.editingVersion,
    };
    if (!body.name.trim()) {
      this.error.set('Type a name for the person.');
      return;
    }
    if (body.terms !== null && !/^\d{1,5}$/.test(body.terms)) {
      this.refuse('Terms are a whole number of days, or blank for none.');
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people`;
    this.submitting = true;
    try {
      if (editing === null) {
        const added = await firstValueFrom(this.http.post<Person>(url, body, this.session.headers()));
        if (this.session.sheet() === sheet) this.people.update((list) => [...list, added]);
      } else {
        const saved = await firstValueFrom(
          this.http.put<Person>(`${url}/${editing}`, body, this.session.headers()),
        );
        if (this.session.sheet() === sheet) {
          this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
        }
      }
      if (this.session.sheet() === sheet) {
        this.reset();
        // Re-read the list so a slower read started before this change cannot overwrite it.
        void this.read(sheet);
      }
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 412 && editing !== null) {
        this.error.set('Someone else saved this person in the meantime. The form now shows their values.');
        await this.reloadEditor(sheet, editing);
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.refuse('Another person on the sheet already has that name.');
      } else if (e instanceof HttpErrorResponse && e.status === 422) {
        this.refuse('Another person on the sheet already has that contact.');
      } else {
        this.refuse(editing === null ? 'The person could not be added.' : 'The person could not be saved.');
      }
    } finally {
      this.submitting = false;
    }
  }

  /**
   * Add a DRAFT claim of the amount, dated today with an empty note, for the person of the typed name; a name not on
   * the sheet adds the person first, with grade A. Refused as a whole when either would be.
   */
  protected async quickAdd(event: Event): Promise<void> {
    event.preventDefault();
    if (this.quickAdding) return;
    this.quickError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.quickName();
    if (!name.trim()) {
      this.quickError.set('Type the name of the person the claim is for.');
      return;
    }
    const amount = this.quickAmount().trim();
    if (!/^\d+(\.\d{1,2})?$/.test(amount)) {
      this.quickError.set('Type an amount of 0.00 or more, with at most two decimals.');
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/quick-add`;
    this.quickAdding = true;
    try {
      const added = await firstValueFrom(this.http.post<Claim>(url, { name, amount }, this.session.headers()));
      if (this.session.sheet() === sheet) {
        this.quickName.set('');
        this.quickAmount.set('');
        // Re-reads the sheet, which also brings in a person the quick-add created.
        this.claimSaved(added);
      }
    } catch {
      this.quickError.set('The claim could not be added.');
    } finally {
      this.quickAdding = false;
    }
  }

  /** Make a DRAFT claim for every plan date due and not yet made, then show the claims and how many were made. */
  protected async generatePlans(): Promise<void> {
    if (this.generating) return;
    this.planGenerateError.set(null);
    this.planGenerated.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.generating = true;
    try {
      const made = await this.plans.generate();
      if (this.session.sheet() !== sheet) return;
      await this.read(sheet);
      this.planGenerated.set(made);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const detail = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' ? ` ${e.error.message}` : '';
      this.planGenerateError.set('The plans could not be generated.' + detail);
    } finally {
      this.generating = false;
    }
  }

  /**
   * Add and open a claim per pasted line of name,amount,date,note, in order, for the person of that name on the sheet;
   * a line matching one of their DRAFTs by date and note updates and opens it instead. Refused as a whole when any
   * line would be.
   */
  protected async pasteClaims(event: Event): Promise<void> {
    event.preventDefault();
    if (this.pasting) return;
    this.pasteError.set(null);
    this.pasteAdded.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = this.pasteText();
    if (!text.trim()) {
      this.pasteError.set('Paste at least one line of name,amount,date,note.');
      return;
    }
    this.pasting = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ added: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/paste`,
          { text },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.pasteText.set('');
      await this.read(sheet);
      this.pasteAdded.set(result.added);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const detail = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' ? ` ${e.error.message}` : '';
      this.pasteError.set('The pasted claims could not be added and opened; none were.' + detail);
    } finally {
      this.pasting = false;
    }
  }

  /**
   * Add or update a person per pasted line of name,grade,terms,contact, in order, each as the person form would: a
   * line naming a person on the sheet updates them. Refused as a whole when any line would be.
   */
  protected async importPeople(event: Event): Promise<void> {
    event.preventDefault();
    if (this.importing) return;
    this.importError.set(null);
    this.importAdded.set(null);
    this.importUpdated.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = this.importText();
    if (!text.trim()) {
      this.importError.set('Paste at least one line of name,grade,terms,contact.');
      return;
    }
    this.importing = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ added: number; updated: number }>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/import`,
          { text },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.importText.set('');
      await this.read(sheet);
      this.importAdded.set(result.added);
      this.importUpdated.set(result.updated);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const detail = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' ? ` ${e.error.message}` : '';
      this.importError.set('The people could not be imported; none were.' + detail);
    } finally {
      this.importing = false;
    }
  }

  /**
   * Download everyone on the sheet, retired people included, in list order as a CSV file: name, the grade in force
   * today, the retired-from date or nothing, and the due-total as the list shows it.
   */
  protected exportCsv(): void {
    const lines = [['name', 'grade', 'retired-from', 'due-total']].concat(
      this.people().map((p) => [p.name, this.gradeOf(p), p.retiredFrom ?? '', this.dueTotalOf(p)]),
    );
    this.download(lines, `people-${this.session.sheet() ?? 'sheet'}.csv`);
  }

  /**
   * Download every claim on the sheet as a CSV file, retired people included, people in list order and each person's
   * claims oldest first: the person's name, then the claim's number, date, amount, due, status and period as the list
   * shows them.
   */
  protected exportClaims(): void {
    const lines = [['person', 'number', 'date', 'amount', 'due', 'status', 'period']].concat(
      this.people().flatMap((p) =>
        [...this.claimsOf(p.id)]
          .reverse()
          .map((c) => [
            p.name,
            c.number ?? '',
            c.date,
            c.amount,
            claimDue(c, this.rates(), p.grades, this.session),
            c.status,
            periodOn(this.periods(), c.dueBy)?.name ?? '',
          ]),
      ),
    );
    this.download(lines, `claims-${this.session.sheet() ?? 'sheet'}.csv`);
  }

  /** Save the lines of fields as a CSV file of the name, each line ended by a line feed. */
  private download(lines: string[][], name: string): void {
    const text = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  /** Re-read the sheet and show the stored values of the person in the open editor. */
  private async reloadEditor(sheet: string, id: number): Promise<void> {
    await this.read(sheet);
    const person = this.people().find((p) => p.id === id);
    if (person && this.editing() === id && this.session.sheet() === sheet) {
      this.editingVersion = person.version;
      this.name.set(person.name);
      this.grade.set(this.gradeOf(person));
      this.terms.set(person.terms === null ? '' : String(person.terms));
    this.contact.set(person.contact ?? '');
      this.personTags.set(this.personTagsOf(person).join(', '));
    }
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const url = `/api/sheets/${encodeURIComponent(sheet)}`;
    const asOf = this.session.asOf() !== null;
    const [list, claims, rates, periods, tags, descendants, live] = await Promise.all([
      firstValueFrom(this.http.get<Person[]>(`${url}/people`, this.session.headers())),
      firstValueFrom(this.http.get<Claim[]>(`${url}/claims`, this.session.headers())),
      firstValueFrom(this.http.get<Rate[]>(`${url}/rates`, this.session.headers())),
      firstValueFrom(this.http.get<Period[]>(`${url}/periods`, this.session.headers())),
      firstValueFrom(this.http.get<string[]>(`${url}/claims/tags`, this.session.headers())),
      firstValueFrom(this.http.get<DescendantFigures>(`${url}/descendants/figures`, this.session.headers())),
      asOf
        ? firstValueFrom(this.http.get<DescendantFigures>(`${url}/consolidated/figures`, this.session.headers()))
        : Promise.resolve(null),
    ]).catch(() => [null, null, null, null, null, null, null]);
    // A later read wins over a slower earlier one.
    if (list && claims && rates && periods && tags && descendants && read === this.reads && this.session.sheet() === sheet) {
      this.people.set(list);
      this.claims.set(claims);
      this.rates.set(rates);
      this.periods.set(periods);
      this.tags.set(tags);
      this.descendantFigures.set(descendants);
      this.liveConsolidated.set(live);
    }
  }
}
