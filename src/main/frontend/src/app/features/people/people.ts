import { DOCUMENT, Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Session, otherError } from '../../core/session';
import { AMOUNT_PATTERN, Claim, ClaimsApi, byListed, cents, counts, deferred, dueBy, isBound, isOpen, money, overdue, pasteError, quickAddError, roundsHalfUp, statusOn, tagsOf } from '../claims/claims-api';
import { PersonClaims } from '../claims/person-claims';
import { PersonPlans } from '../plans/person-plans';
import { PlansApi } from '../plans/plans-api';
import { Pools } from '../pools/pools';
import { Periods } from '../periods/periods';
import { Period, PeriodsApi, periodOn, personPeriodDueCents } from '../periods/periods-api';
import { Rates } from '../rates/rates';
import { Summary, SummaryPerson } from '../summary/summary';
import { Header } from '../header/header';
import { Lateness, Rate, RatePreview, RatesApi, claimDueCents, effectiveRates, pctOn, plusDays } from '../rates/rates-api';
import { DescendantFigures, GRADES, PeopleApi, Person, aliasError, bulkGradeError, bulkRetireError, csvLine, gradeOn, heldOn, holdError, importError, isStale, mergeError, moveError, patronError, personError, personTags, cancelStatementError, retireError, statementError, transferError, unretireError, watchError } from './people-api';

// The sheet's rates; its periods, each with its due-total; its pools; the people on the active sheet, in the order they were added, each with their
// claims, what they have claimed, their due-total (held to the sheet's cap), its excess over the cap and its settled and open parts, their overdue claims, their open claims' dues by age, and their patrons with their shares and backing (their patrons' recent settled figures by share); the sheet's claimed-total,
// due-total and overdue claims; a header of counters repeating them; a summary of them by grade, period and status; and
// the one form that adds a person or, with a
// person's editor open, saves that person.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, Header, PersonClaims, PersonPlans, Periods, Pools, Rates, Summary],
  template: `
    <app-header [peopleCount]="people().length" [claims]="claims()" [dueTotal]="dueTotal()" [overdueTotal]="overdueTotal()" [consolidated]="consolidated()"
      [myDueTotal]="myFigures()?.dueTotal ?? null" [myOverdueTotal]="myFigures()?.overdueTotal ?? null" />
    <app-rates [rates]="rates()" [effective]="effectiveRates()" [preview]="previewRate" (saved)="rateSaved($event)" />
    <app-periods [periods]="periods()" [dueTotals]="dueByPeriod()" (saved)="periodSaved($event)" />
    <app-summary [people]="summaryPeople()" [claims]="claims()" [periods]="periods()" [dueTotals]="dueByPeriod()" />
    <app-pools [people]="people()" (cutTotals)="cutTotals.set($event)" />
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
        <button type="submit" data-testid="paste-submit">Add pasted claims</button>
        @if (pasted() !== null) {
          <p role="status">Added <strong data-testid="paste-added">{{ pasted() }}</strong></p>
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
      <p>Claimed total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue <strong data-testid="rollup-overdue-count">{{ overdueCount() }}</strong>
        totalling <strong data-testid="rollup-overdue-total">{{ overdueTotal() }}</strong></p>
      <p>Settled total <strong data-testid="rollup-settled-total">{{ settledTotal() }}</strong>
        open total <strong data-testid="rollup-open-total">{{ openTotal() }}</strong></p>
      <p>Cut total <strong data-testid="rollup-cut-total">{{ money(cutTotal()) }}</strong></p>
      @if (tagTotals().length > 0) {
        <table aria-label="Due total by tag">
          <thead><tr><th scope="col">Tag</th><th scope="col">Due total</th></tr></thead>
          <tbody>
            @for (t of tagTotals(); track t.tag) {
              <tr><th scope="row">{{ t.tag }}</th><td [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ money(t.cents) }}</td></tr>
            }
          </tbody>
        </table>
      }
      @if (monthTotals().length > 0) {
        <table aria-label="Due total by month">
          <thead><tr><th scope="col">Month</th><th scope="col">Due total</th></tr></thead>
          <tbody>
            @for (m of monthTotals(); track m.month) {
              <tr><th scope="row">{{ m.month }}</th><td [attr.data-testid]="'month-' + m.month + '-due-total'">{{ money(m.cents) }}</td></tr>
            }
          </tbody>
        </table>
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
          <tr><th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">Name</th><th scope="col">From sheet</th><th scope="col">Aliases</th><th scope="col">Clashing</th><th scope="col">Grade</th><th scope="col">Terms</th><th scope="col">Contact</th><th scope="col">Tags</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Cut</th><th scope="col">By period</th><th scope="col">Overdue</th><th scope="col">Aged</th><th scope="col">Statements</th><th scope="col">Retired</th><th scope="col">Active</th><th scope="col">Hold</th><th scope="col">Watched</th><th scope="col">Patrons</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of shownPeople(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td>
                <input type="checkbox" data-testid="person-select" [checked]="selectedPeople().has(p.id)"
                  (change)="personToggled(p.id, $any($event.target).checked)" [attr.aria-label]="'Select ' + p.name" />
              </td>
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-origin">{{ p.origin ?? '' }}</td>
              <td>
                <span data-testid="person-aliases">{{ p.aliases.join(', ') }}</span>
                <label [for]="'person-alias-input-' + p.id" class="visually-hidden">Alias for {{ p.name }}</label>
                <input [id]="'person-alias-input-' + p.id" data-testid="person-alias-input" #aliasInput autocomplete="off"
                  (input)="clearRowError(p.id)" />
                <button type="button" data-testid="person-alias-add" (click)="addAlias(p, aliasInput)"
                  [attr.aria-label]="'Add alias for ' + p.name">Add alias</button>
              </td>
              <td data-testid="person-clashing">{{ p.clashing ? 'yes' : 'no' }}</td>
              <td data-testid="person-grade">{{ gradeOf(p) }}</td>
              <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              <td data-testid="person-contact">{{ p.contact ?? '' }}</td>
              <td data-testid="person-tags">{{ personTags(p).join(', ') }}</td>
              <td data-testid="person-claimed">{{ claimedBy(p.id) }}</td>
              <td>
                <span data-testid="person-due-total">{{ dueOf(p) }}</span>
                <span>excess <span data-testid="person-excess">{{ money(excessOf(p.id)) }}</span></span>
                <span>settled <span data-testid="person-settled-total">{{ money(settledOpenOf(p.id).settled) }}</span></span>
                <span>open <span data-testid="person-open-total">{{ money(settledOpenOf(p.id).open) }}</span></span>
              </td>
              <td data-testid="person-cut-total">{{ money(cutTotals().get(p.id) ?? 0) }}</td>
              <td>
                @for (period of periods(); track period.id) {
                  <span>{{ period.name }} <span [attr.data-testid]="'person-period-' + period.name + '-due-total'">{{ money(periodDueOf(p.id, period)) }}</span></span>
                }
              </td>
              <td>
                <span data-testid="person-overdue-count">{{ overdueOf(p.id).count }}</span>
                <span>totalling <span data-testid="person-overdue-total">{{ money(overdueOf(p.id).cents) }}</span></span>
              </td>
              <td>
                <span>0–30 <span data-testid="person-aged-0-30">{{ money(agedOf(p.id).upTo30) }}</span></span>
                <span>31–60 <span data-testid="person-aged-31-60">{{ money(agedOf(p.id).upTo60) }}</span></span>
                <span>61+ <span data-testid="person-aged-61">{{ money(agedOf(p.id).over60) }}</span></span>
              </td>
              <td>
                <span data-testid="person-statement-count">{{ p.statementCount }}</span>
                <span>Last <span data-testid="person-stated-total">{{ statedTotalOf(p) }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ p.statementDate ?? '' }}</span></span>
                <label [for]="'person-statement-asof-' + p.id" class="visually-hidden">Statement of {{ p.name }} as of</label>
                <input [id]="'person-statement-asof-' + p.id" type="date" data-testid="person-statement-asof" #statementAsOf
                  (input)="clearRowError(p.id)" />
                <button type="button" data-testid="person-statement" (click)="statement(p, statementAsOf)"
                  [attr.aria-label]="'Take statement of ' + p.name">Statement</button>
                <button type="button" data-testid="person-unstate" (click)="cancelStatement(p)"
                  [attr.aria-label]="'Cancel last statement of ' + p.name">Cancel statement</button>
              </td>
              <td>
                <span data-testid="person-retired">{{ p.retiredFrom === null ? 'no' : 'yes' }}</span>
                <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
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
              <td>
                <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                <ul>
                  @for (h of p.holds; track $index) {
                    <li data-testid="person-hold-line">{{ h.from }} to {{ h.to ?? 'open' }}</li>
                  }
                </ul>
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
                <span data-testid="person-watched">{{ p.watched ? 'yes' : 'no' }}</span>
                <button type="button" data-testid="person-watch" (click)="watch(p)" [attr.aria-pressed]="p.watched"
                  [attr.aria-label]="'Watch ' + p.name">{{ p.watched ? 'Unwatch' : 'Watch' }}</button>
              </td>
              <td>
                <span data-testid="person-patrons">{{ patronNamesOf(p) }}</span>
                <span>backing <span data-testid="person-backing">{{ backingOf(p) }}</span></span>
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
                <app-person-plans [personId]="p.id" [personName]="p.name" />
                <app-person-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [rates]="effectiveRates()" [grades]="p.grades" [others]="patronChoicesFor(p)" [periods]="periods()"
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
  protected readonly people = signal<Person[]>([]);
  /** The people the list shows: retired ones (any with a retirement date) only with "show retired" on. */
  protected readonly shownPeople = computed(() => {
    const shown = this.session.showRetired() ? this.people() : this.people().filter((p) => p.retiredFrom === null);
    return this.sorted(shown);
  });
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  /** The holds of each person with any, by person id: their open (OPEN or PENDING) claims dated within one count in no live total. */
  private readonly holdsById = computed(() => new Map(this.people().filter((p) => p.held).map((p) => [p.id, p.holds])));
  /** The retirement date of each person with one, by person id: from the sheet's today on or after it, their
   *  open (OPEN or PENDING) claims count in no live total unless bound by a statement. */
  private readonly retiredFromById = computed(() =>
    new Map(this.people().filter((p) => p.retiredFrom !== null).map((p) => [p.id, p.retiredFrom as string])));
  /** Whether an unbound open claim is left out of the live totals: dated within a hold of its person, or its
   *  person's retirement date reached on the sheet's today. */
  private readonly leftOut = computed(() => {
    const holds = this.holdsById();
    const retired = this.retiredFromById();
    const today = this.session.today();
    return (c: Claim) => {
      const from = retired.get(c.personId);
      return heldOn(holds.get(c.personId) ?? [], c.date) || (from !== undefined && today !== null && from <= today);
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
  /** Each person's claims (newest first) and claimed in cents (counting claims only), by person id. */
  private readonly byPerson = computed(() => {
    const out = new Map<number, { claims: Claim[]; cents: number }>();
    const live = this.countsLive();
    for (const c of this.claims()) {
      const entry = out.get(c.personId) ?? { claims: [], cents: 0 };
      entry.claims.push(c);
      if (live(c)) entry.cents += cents(c.amount);
      out.set(c.personId, entry);
    }
    return out;
  });
  /** The sheet's rates (a percent with two decimals), each for a grade from a from-date. */
  protected readonly rates = signal<Rate[]>([]);
  /** The rates of each of the sheet's ancestors, its parent's first. */
  private readonly inherited = signal<Rate[][]>([]);
  /** The rates in force on the sheet: its own, else (for a grade and date with none) its ancestors'. */
  protected readonly effectiveRates = computed(() => effectiveRates(this.rates(), this.inherited()));
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
    for (const p of this.people()) {
      const dues = this.claimsOf(p.id).filter(this.countsLive())
        .map((c) => this.claimDueCents(p, c, late))
        .filter((d): d is number => d !== null);
      if (pctOn(this.ratesOf(this.gradeOf(p)), this.session.today()) === null && dues.length === 0) {
        out.set(p.id, null);
        continue;
      }
      out.set(p.id, dues.reduce((sum, d) => sum + d, 0));
    }
    return out;
  });
  /** Each person's due-total in cents, by person id, held to the sheet's cap: the cap when their
   *  uncapped one is above it (n/a is not capped). */
  private readonly dueByPerson = computed(() => {
    const cap = this.session.cap();
    const capCents = cap === null ? null : cents(cap);
    const out = new Map<number, number | null>();
    for (const [id, due] of this.uncappedDueByPerson()) {
      out.set(id, due === null || capCents === null ? due : Math.min(due, capCents));
    }
    return out;
  });
  /** Each person's uncapped due-total split in cents, by person id: the dues (n/a ones skipped) their
   *  counting SETTLED claims show (at their fixed figures) and those their counting open (OPEN and
   *  PENDING) claims show. */
  private readonly settledOpenByPerson = computed(() => {
    const out = new Map<number, { settled: number; open: number }>();
    const late = this.lateness();
    for (const p of this.people()) {
      const split = { settled: 0, open: 0 };
      for (const c of this.claimsOf(p.id).filter(this.countsLive())) {
        const due = this.claimDueCents(p, c, late) ?? 0;
        if (c.status === 'SETTLED') split.settled += due;
        else if (isOpen(c)) split.open += due;
      }
      out.set(p.id, split);
    }
    return out;
  });
  protected settledOpenOf(personId: number): { settled: number; open: number } {
    return this.settledOpenByPerson().get(personId) ?? { settled: 0, open: 0 };
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
  /** The person's excess in cents: how far their uncapped due-total is above their capped one (0 otherwise). */
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
  protected agedOf(personId: number): Aged {
    return this.agedByPerson().get(personId) ?? { upTo30: 0, upTo60: 0, over60: 0 };
  }
  protected readonly overdueCount = computed(() =>
    [...this.overdueByPerson().values()].reduce((sum, o) => sum + o.count, 0));
  protected readonly overdueTotal = computed(() =>
    money(trimmedSum([...this.overdueByPerson().values()].map((o) => o.cents))));
  protected readonly money = money;
  protected readonly personTags = personTags;
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
  /** Each person as the summary counts them: grade today, whether retired (as the list hides them), due-total, whether active. */
  protected readonly summaryPeople = computed<SummaryPerson[]>(() => {
    const dues = this.dueByPerson();
    const active = this.activeIds();
    return this.people().map((p) => ({ grade: this.gradeOf(p), retired: p.retiredFrom !== null, due: dues.get(p.id) ?? null,
      active: active.has(p.id) }));
  });
  /** The names of the people on the sheet, by person id (retired ones too). */
  private readonly namesById = computed(() => new Map(this.people().map((p) => [p.id, p.name])));
  /** Everyone's due-total added up, skipping n/a, trimmed. */
  protected readonly dueTotal = computed(() =>
    money(trimmedSum([...this.dueByPerson().values()].filter((d): d is number => d !== null))));
  /** The signed-in user's own figures: the due-total and overdue-total of the person on the sheet
   *  who goes by the user's name (their name or an alias, ignoring case); null when there is none. */
  protected readonly myFigures = computed(() => {
    const user = this.session.user()?.toLowerCase();
    if (!user) return null;
    const me = this.people().find((p) => [p.name, ...p.aliases].some((n) => n.toLowerCase() === user));
    return me ? { dueTotal: this.dueOf(me), overdueTotal: money(this.overdueOf(me.id).cents) } : null;
  });
  /** The figures of the sheet's descendants added up, as last read from the store. */
  private readonly descendants = signal<DescendantFigures>({ people: 0, dueTotal: '0.00', overdueTotal: '0.00' });
  /** The sheet's own figures as it is now, read from the store while the screen shows it as of a
   *  date; null when it shows the sheet as it stands (the screen's own figures are then live). */
  private readonly ownLive = signal<DescendantFigures | null>(null);
  /** The sheet's own figures as it is now and its descendants' added up. */
  protected readonly consolidated = computed<DescendantFigures>(() => {
    const d = this.descendants();
    const own = this.ownLive() ?? {
      people: this.people().length, dueTotal: this.dueTotal(), overdueTotal: this.overdueTotal(),
    };
    return {
      people: own.people + d.people,
      dueTotal: money(cents(own.dueTotal) + cents(d.dueTotal)),
      overdueTotal: money(cents(own.overdueTotal) + cents(d.overdueTotal)),
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

  /** The paste box: lines of name,amount,date,note, each a DRAFT claim. */
  protected readonly pasteForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  /** How many claims the last paste added; null while one runs or before the first. */
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
        this.descendants.set({ people: 0, dueTotal: '0.00', overdueTotal: '0.00' });
        this.ownLive.set(null);
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
    this.form.setValue({ name: p.name, grade: this.gradeOf(p), gradeFrom: '', terms: termsText(p), contact: p.contact ?? '',
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

  /** A claim's due in cents, at the rate of the person's grade in force on the claim's date (or at
   *  the given rates in its place). */
  private claimDueCents(p: Person, c: Claim, late: Lateness, rates?: Rate[]): number | null {
    return claimDueCents(rates ?? this.ratesOf(gradeOn(p.grades, c.date)), c, cents(this.session.floorOn(c.date)),
      { ...late, graceOf: (o) => this.graceOf(p, o) },
      roundsHalfUp(c, this.claimsOf(p.id), this.session.policyDate(), late.today));
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

  /** The person's patrons as <name> <share>, sorted by name, comma and space separated. */
  protected patronNamesOf(p: Person): string {
    const names = this.namesById();
    const shares = patronShares(p.patronIds.length);
    return p.patronIds
      .map((id, i) => ({ name: names.get(id) ?? '', share: shares[i] }))
      .filter((o) => o.name)
      .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
      .map((o) => `${o.name} ${money(o.share)}`)
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
          { ...value, gradeFrom: value.gradeFrom.trim() || null, terms: value.terms.trim(), contact: value.contact.trim(),
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
      if (added.personAdded) this.people.update((ps) => [...ps.filter((p) => p.id !== added.person.id), added.person]);
      else this.taken(added.person);
      this.claimSaved(added.claim);
      this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
    } catch (e) {
      this.quickError.set(quickAddError(e));
    } finally {
      this.quickAdding = false;
    }
  }

  /** Add a DRAFT claim per pasted line, all or none; then show how many were added. */
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
      const { claims, plans } = await this.plansApi.generate(sheetId);
      if (this.session.sheetId() !== sheetId) return;
      for (const claim of claims) this.claimSaved(claim);
      this.session.plans.set(plans);
      this.planGenerated.set(claims.length);
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
        terms: termsText(current), contact: current.contact ?? '', tags: personTags(current).join(', ') },
        { emitEvent: false });
    } catch {
      // the error shown already says the save was refused
    }
  }

  private reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A', gradeFrom: '', terms: '', contact: '', tags: '' });
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
      const [people, claims, rates, inherited, periods, tags, descendants, own, plans] = await Promise.all([
        this.api.list(sheetId), this.claimsApi.list(sheetId), this.ratesApi.list(sheetId), this.ratesApi.inherited(sheetId),
        this.periodsApi.list(sheetId), this.claimsApi.tags(sheetId), this.api.descendantFigures(sheetId),
        asOf ? this.api.ownFigures(sheetId) : Promise.resolve(null), this.plansApi.list(sheetId)]);
      if (seq === this.readSeq) {
        this.session.plans.set(plans);
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
/** The shares, in hundredths, of a person's patrons in the order they were named: 100.00 split
 *  equally, each rounded half to even, with the remainder given to the first-named patron. */
function patronShares(count: number): number[] {
  if (count === 0) return [];
  const share = divideHalfEven(10000, count);
  const shares = Array<number>(count).fill(share);
  shares[0] += 10000 - share * count;
  return shares;
}

/** The integer quotient of the integers, rounded half to even. */
function divideHalfEven(n: number, d: number): number {
  const sign = n < 0 ? -1 : 1;
  const a = Math.abs(n);
  const q = Math.floor(a / d);
  const r = a - q * d;
  const up = 2 * r > d || (2 * r === d && q % 2 === 1);
  return sign * (up ? q + 1 : q);
}

function trimmedSum(figures: number[]): number {
  const sum = figures.reduce((a, b) => a + b, 0);
  return figures.length < 3 ? sum : sum - Math.max(...figures) - Math.min(...figures);
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
