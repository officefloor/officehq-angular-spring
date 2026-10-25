import { HttpErrorResponse } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { ClaimView, ClaimsApi, Lateness, SplitView, cents, claimOwedCents, counts, isBound, isDeferred, isOpen, isOverdue, keepsFigure, money, statusAsOf } from '../claims/claims-api';
import { PersonClaims } from '../claims/person-claims';
import { Periods } from '../periods/periods';
import { PeriodView, PeriodsApi, periodOf } from '../periods/periods-api';
import { PoolsList } from '../pools/pools';
import { Pools } from '../pools/pools-api';
import { Rates } from '../rates/rates';
import { RateView, RatesApi, rateInForce, sameRate } from '../rates/rates-api';
import { SheetCap } from '../settings/sheet-cap';
import { SheetFloor } from '../settings/sheet-floor';
import { SheetGrace } from '../settings/sheet-grace';
import { SheetLatePct } from '../settings/sheet-late-pct';
import { SheetParent } from '../settings/sheet-parent';
import { SheetPolicyDate } from '../settings/sheet-policy-date';
import { StatementView, StatementsApi } from '../statements/statements-api';
import { SheetHeader } from '../summary/sheet-header';
import { GradeSummary, PeriodSummary, StatusSummary, Summary, VoidReasonSummary } from '../summary/summary';
import { VoidReasons } from '../void-reasons/void-reasons-api';
import { GRADES, Grade, PeopleApi, PersonView, QuickAddView, gradeOn, heldOn } from './people-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;
/** The statuses the summary counts claims of; no claim is ever pending, so its count stays 0. */
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;
import { QuickAdd } from './quick-add';
import { PasteClaims } from '../claims/paste-claims';
import { Consolidated } from '../summary/consolidated';

// The sheet's people in the order they were added, with one form to add a person or edit one,
// each with their claims and what they have claimed and is due, the sheet's claimed-total and
// due-total, the sheet's rates, and its periods with the due-total of the counting claims dated in each. Each person's statement button records their due-total and binds
// their counting claims to the figures they show then.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, PersonClaims, Rates, Periods, QuickAdd, PasteClaims, Summary, SheetHeader, Consolidated, PoolsList],
  template: `
    <app-sheet-header [peopleCount]="people().length" [statuses]="statusSummary()"
      [dueTotal]="sheetDueTotal()" [overdueTotal]="money(sheetOverdue().cents)" />
    <app-consolidated [ownPeopleCount]="people().length" [ownDueTotal]="sheetDueTotal()"
      [ownOverdueTotal]="money(sheetOverdue().cents)" />
    @if (session.sheet()) {
      <app-rates [rates]="rates()" (saved)="rateSaved($event)" />
      <app-periods [periods]="periods()" [dueTotals]="periodDueTotals()" (added)="periodAdded($event)" (changed)="periodChanged($event)" />
      <app-pools [people]="people()" />
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
          <label for="person-form-contact">Contact</label>
          <input id="person-form-contact" data-testid="person-form-contact" formControlName="contact"
            aria-describedby="person-form-contact-hint" />
          <span id="person-form-contact-hint">Blank: none</span>
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
        <label>
          <input type="checkbox" data-testid="show-retired" [checked]="showRetired()"
            (change)="showRetired.set($any($event.target).checked)" />
          Show retired
        </label>
        <button type="button" data-testid="export-people" (click)="exportCsv()">Export people as CSV</button>
        <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
        <p>Due in total: <strong data-testid="rollup-due-total">{{ sheetDueTotal() }}</strong></p>
        <p>Settled in total: <strong data-testid="rollup-settled-total">{{ money(sheetSettledOpen().settled) }}</strong>,
          open in total: <strong data-testid="rollup-open-total">{{ money(sheetSettledOpen().open) }}</strong></p>
        <p>Overdue: <strong data-testid="rollup-overdue-count">{{ sheetOverdue().count }}</strong>
          claims, due <strong data-testid="rollup-overdue-total">{{ money(sheetOverdue().cents) }}</strong></p>
        @if (tagTotals().length > 0) {
          <table aria-label="Due by tag">
            <thead>
              <tr><th scope="col">Tag</th><th scope="col">Due</th></tr>
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
          <table aria-label="Due by month">
            <thead>
              <tr><th scope="col">Month</th><th scope="col">Due</th></tr>
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
        <app-summary [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()" [voidReasons]="voidReasonSummary()" />
        <table data-testid="people-list">
          <thead>
            <tr><th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">Name</th><th scope="col">Aliases</th><th scope="col">Grade</th><th scope="col">Terms</th><th scope="col">Contact</th><th scope="col">Claimed</th><th scope="col">Due</th><th scope="col">Settled</th><th scope="col">Open</th>@for (q of periods(); track q.id) {<th scope="col">Due in {{ q.name }}</th>}<th scope="col">Excess</th><th scope="col">Overdue</th><th scope="col">Due aged 0–30 days</th><th scope="col">Due aged 31–60 days</th><th scope="col">Due aged 61+ days</th><th scope="col">Active</th><th scope="col">Retired</th><th scope="col">On hold</th><th scope="col">Statements</th><th scope="col">Patrons</th><th scope="col">Watched</th><th scope="col">Claims</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
          </thead>
          <tbody>
            @for (p of shownPeople(); track p.id) {
              <tr [attr.data-testid]="'person-row-' + p.id">
                <td>
                  <input type="checkbox" data-testid="person-select" [checked]="selectedPeople().has(p.id)"
                    (change)="selectPerson(p.id, $any($event.target).checked)" [attr.aria-label]="'Select ' + p.name" />
                </td>
                <td data-testid="person-name">{{ p.name }}</td>
                <td>
                  <span data-testid="person-aliases">{{ p.aliases.join(', ') }}</span>
                  <label [for]="'person-alias-input-' + p.id" class="visually-hidden">Alias for {{ p.name }}</label>
                  <input [id]="'person-alias-input-' + p.id" data-testid="person-alias-input" #aliasInput
                    (input)="clearPersonError(p.id)" />
                  <button type="button" data-testid="person-alias-add" (click)="addAlias(p, aliasInput)"
                    [disabled]="addingAlias() === p.id" [attr.aria-label]="'Add an alias for ' + p.name">Add alias</button>
                </td>
                <td data-testid="person-grade">{{ gradeToday(p) }}</td>
                <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
                <td data-testid="person-contact">{{ p.contact ?? '' }}</td>
                <td data-testid="person-claimed">{{ claimed(p.id) }}</td>
                <td data-testid="person-due-total">{{ personDueTotal(p) }}</td>
                <td data-testid="person-settled-total">{{ money(settledOpenOf(p.id).settled) }}</td>
                <td data-testid="person-open-total">{{ money(settledOpenOf(p.id).open) }}</td>
                @for (q of periods(); track q.id) {
                  <td [attr.data-testid]="'person-period-' + q.name + '-due-total'">{{ personPeriodDue(p.id, q.id) }}</td>
                }
                <td data-testid="person-excess">{{ excess(p) }}</td>
                <td>
                  <span data-testid="person-overdue-count">{{ overdueOf(p.id).count }}</span>
                  due <span data-testid="person-overdue-total">{{ money(overdueOf(p.id).cents) }}</span>
                </td>
                <td data-testid="person-aged-0-30">{{ money(agedOf(p.id).upTo30) }}</td>
                <td data-testid="person-aged-31-60">{{ money(agedOf(p.id).upTo60) }}</td>
                <td data-testid="person-aged-61">{{ money(agedOf(p.id).over60) }}</td>
                <td data-testid="person-active">{{ activeIds().has(p.id) ? 'yes' : 'no' }}</td>
                <td>
                  <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
                  from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span>
                  <label [for]="'person-retire-date-' + p.id" class="visually-hidden">Retire {{ p.name }} from</label>
                  <input type="date" [id]="'person-retire-date-' + p.id" data-testid="person-retire-date"
                    #retireDate (input)="clearPersonError(p.id)" />
                  <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)"
                    [disabled]="retiring() === p.id" [attr.aria-label]="'Retire ' + p.name">Retire</button>
                </td>
                <td>
                  <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
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
                  <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
                  taken; last <span data-testid="person-stated-total">{{ statementOf(p.id)?.lastDueTotal ?? '' }}</span>
                  on <span data-testid="person-statement-date">{{ statementOf(p.id)?.lastDate ?? '' }}</span>
                  <label [for]="'person-statement-asof-' + p.id" class="visually-hidden">Take {{ p.name }}'s statement as of</label>
                  <input type="date" [id]="'person-statement-asof-' + p.id" data-testid="person-statement-asof"
                    #statementAsOf (input)="clearPersonError(p.id)" />
                  <button type="button" data-testid="person-statement" (click)="takeStatement(p, statementAsOf)"
                    [disabled]="stating() === p.id" [attr.aria-label]="'Take a statement of ' + p.name">Statement</button>
                  <button type="button" data-testid="person-unstate" (click)="cancelStatement(p)"
                    [disabled]="stating() === p.id" [attr.aria-label]="'Cancel the last statement of ' + p.name">Cancel statement</button>
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
                  <span data-testid="person-patrons">{{ patronNames(p) }}</span>
                  backing <span data-testid="person-backing">{{ backing(p) }}</span>
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
  private readonly voidReasons = inject(VoidReasons);
  private readonly api = inject(PeopleApi);
  private readonly claimsApi = inject(ClaimsApi);
  private readonly ratesApi = inject(RatesApi);
  private readonly statementsApi = inject(StatementsApi);
  private readonly pools = inject(Pools);
  private readonly periodsApi = inject(PeriodsApi);
  private readonly floor = inject(SheetFloor);
  private readonly cap = inject(SheetCap);
  private readonly latePct = inject(SheetLatePct);
  private readonly sheetGrace = inject(SheetGrace);
  private readonly sheetParent = inject(SheetParent);
  private readonly policyDate = inject(SheetPolicyDate);
  private readonly document = inject(DOCUMENT);
  /** What the late charges depend on now. */
  private readonly lateness = computed<Lateness>(() =>
    ({ today: this.session.sheet()?.today, latePct: (date: string) => this.latePct.pctOn(date),
      grace: (claim: ClaimView) => this.graceOf(claim),
      policyDate: this.policyDate.date() ?? this.session.sheet()?.today }));

  protected readonly grades = GRADES;
  protected readonly name = new FormControl('', { nonNullable: true });
  protected readonly grade = new FormControl<Grade>('A', { nonNullable: true });
  /** The date an edit gives the grade from; blank means the sheet's today. */
  protected readonly gradeFrom = new FormControl('', { nonNullable: true });
  /** The person's own terms, in whole days; blank means none. */
  protected readonly terms = new FormControl('', { nonNullable: true });
  /** The person's contact; blank means none. */
  protected readonly contact = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ name: this.name, grade: this.grade, gradeFrom: this.gradeFrom,
    terms: this.terms, contact: this.contact });

  /** Everyone on the sheet, retired or not: their claims count in every total. */
  protected readonly people = signal<PersonView[]>([]);
  /** Whether retired people are listed; off when the screen opens, and kept as it is by loading a sheet. */
  protected readonly showRetired = signal(false);
  /** The people listed: the retired ones only while show retired is on. */
  protected readonly shownPeople = computed(() => this.people().filter((p) => this.showRetired() || !p.retired));
  /** The person being retired. */
  protected readonly retiring = signal<number | null>(null);
  /** The person an alias is being added to. */
  protected readonly addingAlias = signal<number | null>(null);
  /** The person a patron is being added to. */
  protected readonly addingPatron = signal<number | null>(null);
  /** The refusal of the last patron added on a person's row, shown on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** The person a hold is being put on. */
  protected readonly holding = signal<number | null>(null);
  /** The holds of each person, by id. */
  private readonly holdsById = computed(() => new Map(this.people().map((p) => [p.id, p.holds])));
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
    for (const c of this.claims()) if (this.countsLive(c)) by.set(c.personId, (by.get(c.personId) ?? 0) + cents(c.amount));
    return by;
  });
  protected readonly claimedTotal = computed(() => money(this.claims().reduce((t, c) => (this.countsLive(c) ? t + cents(c.amount) : t), 0)));
  /** The sheet's rates, then its parents' (by level); a grade with no rate is left out. */
  protected readonly rates = signal<RateView[]>([]);
  private readonly ratesByGrade = computed(() => {
    const by = new Map<Grade, RateView[]>();
    for (const r of this.rates()) by.set(r.grade, [...(by.get(r.grade) ?? []), r]);
    return by;
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
  /** For every tag ever used on the sheet (those the store keeps and those claims carry now), sorted: the
   *  figures of the claims that count which carry it added up, skipping those that are n/a; a claim with
   *  several tags counts in each. */
  protected readonly tagTotals = computed(() => {
    const totals = new Map<string, number>(this.sheetTags().map((t) => [t, 0]));
    const byId = new Map(this.people().map((p) => [p.id, p]));
    for (const c of this.claims()) {
      for (const t of c.tags) if (!totals.has(t)) totals.set(t, 0);
      const person = byId.get(c.personId);
      if (person === undefined || c.tags.length === 0 || !this.countsLive(c)) continue;
      const due = claimOwedCents(c, this.ratesOn(person, c.date), this.floor.centsOn(c.date), this.lateness(), this.claims()) ?? 0;
      for (const t of c.tags) totals.set(t, totals.get(t)! + due);
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
  /** The sheet's overdue claims that count: how many, and everyone's overdue-total added up, trimmed. */
  protected readonly sheetOverdue = computed(() => {
    let count = 0;
    for (const o of this.overdueByPerson().values()) count += o.count;
    const figures = this.people().map((p) => this.overdueByPerson().get(p.id)?.cents ?? 0);
    return { count, cents: trimmedSum(figures) };
  });
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
  /** Each period's due-total, as its row shows it. */
  protected readonly periodSummary = computed<PeriodSummary[]>(() =>
    this.periods().map((p) => ({ name: p.name, dueTotal: this.periodDueTotals().get(p.id) ?? '0.00' })));
  /** How many of the sheet's claims have each status. */
  protected readonly statusSummary = computed<StatusSummary[]>(() =>
    SUMMARY_STATUSES.map((status) => ({ status, count: this.claims().filter((c) => c.status === status).length })));
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
  /** The ids of the people selected to be given a grade together. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  /** Whether the selected people are being given a grade. */
  protected readonly bulkGrading = signal(false);
  /** How many the last bulk grade changed; cleared while the next one runs. */
  protected readonly bulkGraded = signal<number | null>(null);
  protected readonly bulkGradeError = signal('');
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
      this.selectedPeople.set(new Set());
      this.bulkGraded.set(null);
      this.bulkGradeError.set('');
      if (sheet) void this.reload(sheet.id);
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
      const [people, claims, rates, statements, periods, tags] = await Promise.all([
        this.api.list(sheetId), this.claimsApi.list(sheetId), this.ratesApi.list(sheetId),
        this.statementsApi.list(sheetId), this.periodsApi.list(sheetId), this.claimsApi.tags(sheetId)]);
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
   *  statements and pools, which the merge changes. */
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
      await Promise.all([this.reload(sheet.id), this.pools.refresh(sheet.id)]);
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
   *  claims, statements and pools, which the transfer changes. */
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
      await Promise.all([this.reload(sheet.id), this.pools.refresh(sheet.id)]);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        this.personError.set({ id: person.id, message: e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can transfer people.' : 'The person could not be transferred.' });
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

  /** The person's due-total for the period, with two decimals. */
  protected personPeriodDue(personId: number, periodId: number): string {
    return money(this.personPeriodCents().get(personId)?.get(periodId) ?? 0);
  }

  /** Whether the claim counts in the live totals: open (PENDING included) and settled claims do,
   *  except open claims dated within a hold of their person, or of a person whose retirement date the
   *  sheet's today has reached, that no statement binds, and claims
   *  deferred on the sheet's today. */
  private countsLive(claim: ClaimView): boolean {
    return counts(claim) && !isDeferred(claim, this.session.sheet()?.today) && !(isOpen(claim) && !isBound(claim)
      && this.leftOut(claim));
  }

  /** Whether the claim, open and unbound, leaves the live totals: dated within a hold of its person, or
   *  its person's retirement date reached by the sheet's today. */
  private leftOut(claim: ClaimView): boolean {
    const retiredFrom = this.retiredFromById().get(claim.personId) ?? null;
    const today = this.session.sheet()?.today;
    return heldOn(this.holdsById().get(claim.personId) ?? [], claim.date)
      || (retiredFrom !== null && today !== undefined && retiredFrom <= today);
  }

  /** Whether the claim counts in a period ending on `end`: as countsLive decides, but by the status
   *  its log records at the end of that day (none when added after it); deferral is as today, and a hold
   *  only leaves out a claim that is still open now, as holds touch only live claims. */
  private countsAt(claim: ClaimView, end: string): boolean {
    const status = statusAsOf(claim, end);
    if (status === null) return false;
    const then: ClaimView = { ...claim, status };
    return counts(then) && !isDeferred(claim, this.session.sheet()?.today) && !(isOpen(then) && isOpen(claim) && !isBound(then)
      && this.leftOut(claim));
  }

  /** The person's patrons, each as <name> <share>, sorted by name, comma and space separated. */
  protected patronNames(person: PersonView): string {
    const nameOf = new Map(this.people().map((p) => [p.id, p.name]));
    return sharesOf(person.patronIds).map(([id, share]) => [nameOf.get(id) ?? '', share] as const)
      .filter(([n]) => n !== '').sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
      .map(([n, share]) => `${n} ${money(Number(share))}`).join(', ');
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

  /** The due of the person's settled and of their open claims that count, in cents. */
  protected settledOpenOf(personId: number): SettledOpen {
    return this.settledOpenByPerson().get(personId) ?? { settled: 0, open: 0 };
  }

  /** The due of the person's open claims that count, in cents, by age. */
  protected agedOf(personId: number): Aged {
    return this.agedByPerson().get(personId) ?? { upTo30: 0, upTo60: 0, over60: 0 };
  }

  /** The sum of the person's open and settled claims, with two decimals. */
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

  /** Downloads everyone on the sheet, retired or not, in list order: their name, grade in force today,
   *  retired-from date and due-total as shown, one line each after the header, each ending in a line feed. */
  protected exportCsv(): void {
    const field = (v: string) => /[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
    const lines = [['name', 'grade', 'retired-from', 'due-total'],
      ...this.people().map((p) => [p.name, this.gradeToday(p), p.retiredFrom ?? '', this.personDueTotal(p)])];
    const csv = lines.map((l) => l.map(field).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = 'people.csv';
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
    if (!this.people().some((p) => p.id === added.person.id)) this.people.update((list) => [...list, added.person]);
    this.claimAdded(added.claim);
  }

  /** A paste's claims, in the order of their lines: the last line's claim is the newest. */
  protected pasted(claims: ClaimView[]): void {
    this.claims.update((list) => [...[...claims].reverse(), ...list]);
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

  protected selectPerson(id: number, on: boolean): void {
    this.bulkGradeError.set('');
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

  protected claimSaved(claim: ClaimView): void {
    this.claims.update((list) => list.map((c) => (c.id === claim.id ? claim : c)));
  }

  private reset(): void {
    this.editingId.set(null);
    this.error.set('');
    this.form.reset({ name: '', grade: 'A', gradeFrom: '', terms: '', contact: '' }, { emitEvent: false });
  }

  protected edit(person: PersonView): void {
    this.personError.set(null);
    this.patronError.set(null);
    this.editorsOpened++;
    this.editingId.set(person.id);
    this.editingVersion.set(person.version);
    this.error.set('');
    this.form.setValue({ name: person.name, grade: this.gradeToday(person), gradeFrom: '',
      terms: termsText(person), contact: person.contact ?? '' }, { emitEvent: false });
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
          terms: termsText(current), contact: current.contact ?? '' }, { emitEvent: false });
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
          { name: value.name.trim(), grade: value.grade, terms, contact: value.contact });
        if (this.session.sheet()?.id === sheet.id) this.people.update((list) => [...list, added]);
      } else {
        const saved = await this.api.update(sheet.id, id,
          { name: value.name.trim(), grade: value.grade, gradeFrom: gradeFrom === '' ? null : gradeFrom, terms,
            contact: value.contact, version: this.editingVersion() });
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

/** The person's own terms as the form shows them: blank when they have none. */
function termsText(person: PersonView): string {
  return person.terms === null ? '' : String(person.terms);
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
