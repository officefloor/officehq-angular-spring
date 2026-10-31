import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin, of, switchMap } from 'rxjs';
import { AMOUNT_PATTERN, ClaimView, Claims, cents, countsAtEndWithHolds, countsWithHolds, isOverdue, money, roundingDate } from '../../core/claims';
import { GRADES, Grade, People, PersonView, gradeOn } from '../../core/people';
import { PatronView, Patrons } from '../../core/patrons';
import { Periods, periodOn } from '../../core/periods';
import { DEFAULT_DUE_TERMS, DueTerms, NOT_AVAILABLE, RateLine, Rates, claimDueCents } from '../../core/rates';
import { Sheets, graceOn } from '../../core/sheet';
import { StatementSummary, Statements } from '../../core/statements';
import { Watches } from '../../core/watches';
import { Consolidated, DescendantFigures, NO_DESCENDANTS } from '../../core/consolidated';
import { PersonClaims } from '../claims/claims';
import { SheetHeader } from '../header/header';
import { PeriodsPanel } from '../periods/periods';
import { PoolsPanel } from '../pools/pools';
import { Pools } from '../../core/pools';
import { GradeSummary, PeriodSummary, StatusSummary, SummaryPanel, VoidReasonSummary } from '../summary/summary';
import { VoidReasons } from '../../core/void-reasons';

const NO_CLAIMS: ClaimView[] = [];
const NO_RATES: RateLine = [];

/** The statuses the summary counts claims in, in the order it lists them. */
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

/** A count of overdue claims and their dues in whole cents. */
interface Overdue {
  count: number;
  cents: number;
}
const NO_OVERDUE: Overdue = { count: 0, cents: 0 };

/** A due-total split in whole cents: the dues of the SETTLED claims that count, and of the OPEN and PENDING ones. */
interface Split {
  settled: number;
  open: number;
}
const NO_SPLIT: Split = { settled: 0, open: 0 };

/** The dues in whole cents of a person's OPEN and PENDING claims that count, by the claim's age on the sheet's
 *  today: 0 to 30 days (a claim dated after today included), 31 to 60, and 61 or more. */
interface Aged {
  upTo30: number;
  upTo60: number;
  over60: number;
}
const NO_AGED: Aged = { upTo30: 0, upTo60: 0, over60: 0 };
/** A person's terms the form accepts: a whole number of days, 0 or more. */
const TERMS_PATTERN = /^\d{1,5}$/;

/** A person's own terms as the form shows them: blank for none. */
function termsText(p: PersonView): string {
  return p.terms === null ? '' : String(p.terms);
}

/** A CSV field: wrapped in double quotes, with any quote inside doubled, when it holds a comma, a quote or a line break. */
/** The figures added up, leaving out the single highest and the single lowest when there are three or more. */
function trimmedSum(figures: number[]): number {
  const sum = figures.reduce((total, f) => total + f, 0);
  return figures.length < 3 ? sum : sum - Math.max(...figures) - Math.min(...figures);
}

function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replaceAll('"', '""') + '"' : value;
}

// The people on the active sheet: one form to add a person or edit an existing one, the list with
// each person's claims and due-total, and the sheet's claimed-total and due-total.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule, SheetHeader, PersonClaims, PeriodsPanel, SummaryPanel, PoolsPanel],
  template: `
    <app-header [peopleCount]="people().length" [statuses]="summaryByStatus()" [dueTotal]="dueTotal()"
                [overdueTotal]="money(overdueTotals().cents)" />
    <app-periods [dueTotals]="dueByPeriod()" />
    <app-pools [people]="people()" />
    <app-summary [grades]="summaryByGrade()" [periods]="summaryByPeriod()" [statuses]="summaryByStatus()"
                 [voidReasons]="summaryByVoidReason()" />

    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <p>Consolidated over this sheet and its descendants:
        people <strong data-testid="consolidated-people-count">{{ consolidated().peopleCount }}</strong>,
        due <strong data-testid="consolidated-due-total">{{ consolidated().dueTotal }}</strong>,
        overdue <strong data-testid="consolidated-overdue-total">{{ consolidated().overdueTotal }}</strong></p>
      <p>Claimed in total: <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total: <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue: <strong data-testid="rollup-overdue-count">{{ overdueTotals().count }}</strong>
        claims, due <strong data-testid="rollup-overdue-total">{{ money(overdueTotals().cents) }}</strong></p>
      <p>Settled in total: <strong data-testid="rollup-settled-total">{{ money(splitTotals().settled) }}</strong>,
        open in total: <strong data-testid="rollup-open-total">{{ money(splitTotals().open) }}</strong></p>
      <p>Cut in total: <strong data-testid="rollup-cut-total">{{ money(cutTotal()) }}</strong></p>
      @if (tagTotals().length > 0) {
        <ul aria-label="Due by tag">
          @for (t of tagTotals(); track t.tag) {
            <li>{{ t.tag }}: <span [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ money(t.cents) }}</span></li>
          }
        </ul>
      }
      @if (monthTotals().length > 0) {
        <ul aria-label="Due by month">
          @for (m of monthTotals(); track m.month) {
            <li>{{ m.month }}: <span [attr.data-testid]="'month-' + m.month + '-due-total'">{{ money(m.cents) }}</span></li>
          }
        </ul>
      }

      <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="editingId() === null ? 'Add a person' : 'Edit a person'">
        <label for="person-form-name">Name</label>
        <input id="person-form-name" data-testid="person-form-name" formControlName="name" autocomplete="off" />
        <label for="person-form-grade">Grade</label>
        <select id="person-form-grade" data-testid="person-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        @if (editingId() !== null) {
          <label for="person-form-grade-from">Grade from</label>
          <input id="person-form-grade-from" type="date" data-testid="person-form-grade-from" formControlName="gradeFrom" />
        }
        <label for="person-form-terms">Terms (business days; blank: none of their own)</label>
        <input id="person-form-terms" data-testid="person-form-terms" formControlName="terms" inputmode="numeric" autocomplete="off" />
        <label for="person-form-contact">Contact (blank: none)</label>
        <input id="person-form-contact" data-testid="person-form-contact" formControlName="contact" autocomplete="off" />
        <button type="submit" data-testid="person-form-submit" [disabled]="saving()">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="reset()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>

      <form [formGroup]="quickForm" (ngSubmit)="quickAdd()" aria-label="Quick-add a claim">
        <label for="quick-name">Name</label>
        <input id="quick-name" data-testid="quick-name" formControlName="name" autocomplete="off" />
        <label for="quick-amount">Amount</label>
        <input id="quick-amount" data-testid="quick-amount" formControlName="amount" inputmode="decimal" autocomplete="off" />
        <button type="submit" data-testid="quick-submit" [disabled]="quickSaving()">Quick add</button>
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err }}</p>
        }
      </form>

      <form [formGroup]="pasteForm" (ngSubmit)="paste()" aria-label="Paste claims">
        <label for="paste-claims">Paste claims, one per line as name,amount,date,note (end the note with " #reference" to give one)</label>
        <textarea id="paste-claims" data-testid="paste-claims" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="paste-submit" [disabled]="pasting()">Add pasted claims</button>
        @if (pasteAdded() !== null) {
          Added <span data-testid="paste-added">{{ pasteAdded() }}</span>
        }
        @if (pasteError(); as err) {
          <p role="alert" data-testid="paste-error">{{ err }}</p>
        }
      </form>

      <form [formGroup]="importForm" (ngSubmit)="importPeople()" aria-label="Import people">
        <label for="import-people">Import people, one per line as name,grade,terms,contact (terms and contact may be left out)</label>
        <textarea id="import-people" data-testid="import-people" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="import-submit" [disabled]="importing()">Import people</button>
        @if (importAdded() !== null) {
          Added <span data-testid="import-added">{{ importAdded() }}</span>
        }
        @if (importUpdated() !== null) {
          Updated <span data-testid="import-updated">{{ importUpdated() }}</span>
        }
        @if (importError(); as err) {
          <p role="alert" data-testid="import-error">{{ err }}</p>
        }
      </form>

      <p>
        <input id="show-retired" type="checkbox" data-testid="show-retired"
               [checked]="api.showRetired()" (change)="api.showRetired.set($any($event.target).checked)" />
        <label for="show-retired">Show retired</label>
      </p>

      <p>
        <button type="button" data-testid="export-people" (click)="exportCsv()">Export people as CSV</button>
      </p>

      <p>
        <button type="button" data-testid="bulk-open" (click)="bulkOpen()" [disabled]="bulkOpening()">Open selected claims</button>
        @if (bulkOpenResult(); as r) {
          Opened <span data-testid="bulk-opened">{{ r.opened }}</span>,
          refused <span data-testid="bulk-refused">{{ r.refused }}</span>
        }
        @if (bulkOpenError(); as err) {
          <span role="alert" data-testid="bulk-open-error">{{ err }}</span>
        }
      </p>

      <p>
        <button type="button" data-testid="bulk-settle" (click)="bulkSettle()" [disabled]="bulkSettling()">Settle selected claims</button>
        @if (bulkSettleResult(); as r) {
          Settled <span data-testid="bulk-settled">{{ r.settled }}</span>,
          refused <span data-testid="bulk-settle-refused">{{ r.refused }}</span>
        }
        @if (bulkSettleError(); as err) {
          <span role="alert" data-testid="bulk-settle-error">{{ err }}</span>
        }
        — last batch <span data-testid="bulk-settle-batch">{{ sheets.current()?.settleBatch ?? '' }}</span>
      </p>

      <p>
        <label for="bulk-reassign-to">Move selected claims to</label>
        <select id="bulk-reassign-to" data-testid="bulk-reassign-to"
                [value]="bulkReassignTo()" (change)="bulkReassignToChanged($any($event.target).value)">
          <option value="">Choose a person</option>
          @for (p of listed(); track p.id) {
            <option [value]="p.id">{{ p.name }}</option>
          }
        </select>
        <button type="button" data-testid="bulk-reassign" (click)="bulkReassign()" [disabled]="bulkReassigning()">Move selected claims</button>
        @if (bulkReassignResult(); as r) {
          Moved <span data-testid="bulk-reassigned">{{ r.reassigned }}</span>,
          refused <span data-testid="bulk-reassign-refused">{{ r.refused }}</span>
        }
        @if (bulkReassignError(); as err) {
          <span role="alert" data-testid="bulk-reassign-error">{{ err }}</span>
        }
      </p>

      <form [formGroup]="bulkGradeForm" (ngSubmit)="bulkGrade()" aria-label="Grade the selected people">
        <label for="bulk-grade">Grade</label>
        <select id="bulk-grade" data-testid="bulk-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">from</label>
        <input id="bulk-grade-from" type="date" data-testid="bulk-grade-from" formControlName="from" />
        <button type="submit" data-testid="bulk-grade-apply" [disabled]="bulkGrading()">Grade selected people</button>
        @if (bulkGraded() !== null) {
          Changed <span data-testid="bulk-graded">{{ bulkGraded() }}</span>
        }
      </form>

      <form [formGroup]="bulkRetireForm" (ngSubmit)="bulkRetire()" aria-label="Retire the selected people">
        <label for="bulk-retire-date">Retire from</label>
        <input id="bulk-retire-date" type="date" data-testid="bulk-retire-date" formControlName="from" />
        <button type="submit" data-testid="bulk-retire" [disabled]="bulkRetiring()">Retire selected people</button>
        @if (bulkRetired() !== null) {
          Retired <span data-testid="bulk-retired">{{ bulkRetired() }}</span>
        }
      </form>

      <ul data-testid="person-list">
        @for (p of listed(); track p.id) {
          <li [attr.data-testid]="'person-row-' + p.id">
            <input type="checkbox" data-testid="person-select" [checked]="selectedPeople().has(p.id)"
                   (change)="togglePerson(p.id, $any($event.target).checked)" [attr.aria-label]="'Select ' + p.name" />
            <span data-testid="person-name">{{ p.name }}</span>
            <button type="button" data-testid="person-move-up" (click)="move(p, 'up')" [disabled]="moving() === p.id"
                    [attr.aria-label]="'Move ' + p.name + ' up'">Up</button>
            <button type="button" data-testid="person-move-down" (click)="move(p, 'down')" [disabled]="moving() === p.id"
                    [attr.aria-label]="'Move ' + p.name + ' down'">Down</button>
            — grade <span data-testid="person-grade">{{ gradeToday(p) }}</span>
            — terms <span data-testid="person-terms">{{ p.terms ?? '' }}</span>
            — contact <span data-testid="person-contact">{{ p.contact ?? '' }}</span>
            — aliases <span data-testid="person-aliases">{{ (p.aliases ?? []).join(', ') }}</span>
            — clashing <span data-testid="person-clashing">{{ p.clashing ? 'yes' : 'no' }}</span>
            — from <span data-testid="person-origin">{{ p.origin ?? '' }}</span>
            <label [attr.for]="'person-alias-input-' + p.id">Alias</label>
            <input [id]="'person-alias-input-' + p.id" data-testid="person-alias-input" #aliasInput autocomplete="off"
                   (input)="clearPersonError(p.id)" />
            <button type="button" data-testid="person-alias-add" (click)="addAlias(p, aliasInput)"
                    [disabled]="aliasing() === p.id" [attr.aria-label]="'Add an alias of ' + p.name">Add alias</button>
            — due <span data-testid="person-due-total">{{ dueOf(p) }}</span>
            @for (pd of periods.list(); track pd.id) {
              — {{ pd.name }} due <span [attr.data-testid]="'person-period-' + pd.name + '-due-total'">{{ periodDueOf(p.id, pd.id) }}</span>
            }
            settled <span data-testid="person-settled-total">{{ money(splitOf(p.id).settled) }}</span>
            open <span data-testid="person-open-total">{{ money(splitOf(p.id).open) }}</span>
            excess <span data-testid="person-excess">{{ money(excessOf(p.id)) }}</span>
            cut <span data-testid="person-cut-total">{{ money(cutOf(p.id)) }}</span>
            — overdue <span data-testid="person-overdue-count">{{ overdueOf(p.id).count }}</span>
            due <span data-testid="person-overdue-total">{{ money(overdueOf(p.id).cents) }}</span>
            — aged 0–30 <span data-testid="person-aged-0-30">{{ money(agedOf(p.id).upTo30) }}</span>
            31–60 <span data-testid="person-aged-31-60">{{ money(agedOf(p.id).upTo60) }}</span>
            61+ <span data-testid="person-aged-61">{{ money(agedOf(p.id).over60) }}</span>
            — statements <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
            — last stated <span data-testid="person-stated-total">{{ statementOf(p.id)?.dueTotal ?? '' }}</span>
            on <span data-testid="person-statement-date">{{ statementOf(p.id)?.date ?? '' }}</span>
            — retired <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
            from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span>
            @if (p.pastRetirements?.length) {
              <ul [attr.aria-label]="'Past retirements of ' + p.name">
                @for (r of p.pastRetirements; track $index) {
                  <li data-testid="person-retirement-line">{{ r.from }} to {{ r.to }}</li>
                }
              </ul>
            }
            — on hold <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
            — active <span data-testid="person-active">{{ activeByPerson().get(p.id) ? 'yes' : 'no' }}</span>
            @if (p.holds.length > 0) {
              <ul [attr.aria-label]="'Holds of ' + p.name">
                @for (h of p.holds; track $index) {
                  <li data-testid="person-hold-line">{{ h.from }} to {{ h.to ?? 'no end' }}</li>
                }
              </ul>
            }
            <label [attr.for]="'person-hold-from-' + p.id">Hold from</label>
            <input [id]="'person-hold-from-' + p.id" type="date" data-testid="person-hold-from" #holdFrom
                   (input)="clearPersonError(p.id)" />
            <label [attr.for]="'person-hold-to-' + p.id">to</label>
            <input [id]="'person-hold-to-' + p.id" type="date" data-testid="person-hold-to" #holdTo
                   (input)="clearPersonError(p.id)" />
            <button type="button" data-testid="person-hold-toggle" (click)="hold(p, holdFrom.value, holdTo.value)"
                    [disabled]="holding() === p.id" [attr.aria-label]="'Hold ' + p.name">Hold</button>
            <button type="button" [attr.data-testid]="'person-edit-' + p.id" (click)="edit(p)"
                    [attr.aria-label]="'Edit ' + p.name">Edit</button>
            <label [attr.for]="'person-statement-asof-' + p.id">Statement as of</label>
            <input [id]="'person-statement-asof-' + p.id" type="date" data-testid="person-statement-asof" #statementAsOf
                   (input)="clearPersonError(p.id)" />
            <button type="button" data-testid="person-statement" (click)="takeStatement(p, statementAsOf)" [disabled]="stating() === p.id"
                    [attr.aria-label]="'Take a statement of ' + p.name">Statement</button>
            <button type="button" data-testid="person-unstate" (click)="cancelStatement(p)" [disabled]="stating() === p.id"
                    [attr.aria-label]="'Cancel the last statement of ' + p.name">Cancel statement</button>
            <label [attr.for]="'person-retire-date-' + p.id">Retire from</label>
            <input [id]="'person-retire-date-' + p.id" type="date" data-testid="person-retire-date" #retireDate />
            <button type="button" data-testid="person-retire" (click)="retire(p, retireDate.value)" [disabled]="retiring() === p.id"
                    [attr.aria-label]="'Retire ' + p.name">Retire</button>
            <button type="button" data-testid="person-unretire" (click)="unretire(p)" [disabled]="retiring() === p.id"
                    [attr.aria-label]="'Unretire ' + p.name">Unretire</button>
            — patrons <span data-testid="person-patrons">{{ patronsOf(p.id) }}</span>
            — backing <span data-testid="person-backing">{{ backingOf(p.id) }}</span>
            <label [attr.for]="'person-patron-select-' + p.id">Patron</label>
            <select [id]="'person-patron-select-' + p.id" data-testid="person-patron-select" #patronSelect
                    (change)="patronError.set(null)">
              @for (q of people(); track q.id) {
                <option [value]="q.id">{{ q.name }}</option>
              }
            </select>
            <button type="button" data-testid="person-patron-add" (click)="addPatron(p, patronSelect.value)"
                    [disabled]="addingPatron() === p.id" [attr.aria-label]="'Add a patron of ' + p.name">Add patron</button>
            <label [attr.for]="'person-merge-into-' + p.id">Merge into</label>
            <select [id]="'person-merge-into-' + p.id" data-testid="person-merge-into" #mergeInto
                    (change)="clearPersonError(p.id)">
              @for (q of people(); track q.id) {
                <option [value]="q.id">{{ q.name }}</option>
              }
            </select>
            <button type="button" data-testid="person-merge" (click)="merge(p, mergeInto.value)"
                    [disabled]="merging() === p.id" [attr.aria-label]="'Merge ' + p.name">Merge</button>
            <label [attr.for]="'person-transfer-to-' + p.id">Transfer to sheet</label>
            <input [id]="'person-transfer-to-' + p.id" data-testid="person-transfer-to" #transferTo
                   (input)="clearPersonError(p.id)" />
            <button type="button" data-testid="person-transfer" (click)="transfer(p, transferTo.value)"
                    [disabled]="transferring() === p.id" [attr.aria-label]="'Transfer ' + p.name">Transfer</button>
            — watched <span data-testid="person-watched">{{ watched().has(p.id) ? 'yes' : 'no' }}</span>
            <button type="button" data-testid="person-watch" (click)="toggleWatch(p)" [disabled]="watching() === p.id"
                    [attr.aria-pressed]="watched().has(p.id)" [attr.aria-label]="'Watch ' + p.name">Watch</button>
            @if (patronError()?.id === p.id) {
              <p role="alert" data-testid="person-patron-error">{{ patronError()?.message }}</p>
            }
            @if (personError()?.id === p.id) {
              <p role="alert" data-testid="person-error">{{ personError()?.message }}</p>
            }
            <app-person-claims [personId]="p.id" [personName]="p.name" [claims]="claimsOf(p.id)" [ratesByGrade]="rates.lineByGrade()" [grades]="p"
                               [periods]="periods.list()" [holds]="p.holds" [retiredFrom]="p.retiredFrom" [others]="othersOf(p.id)"
                               [selected]="selectedClaims()" (selectToggled)="toggleSelected($event)"
                               (saved)="claimSaved($event)" />
          </li>
        }
      </ul>
    </section>
  `,
})
export class PeopleList {
  protected readonly sheets = inject(Sheets);
  protected readonly api = inject(People);
  private readonly claimsApi = inject(Claims);
  private readonly statementsApi = inject(Statements);
  private readonly patronsApi = inject(Patrons);
  private readonly watchesApi = inject(Watches);
  private readonly consolidatedApi = inject(Consolidated);
  protected readonly rates = inject(Rates);
  protected readonly periods = inject(Periods);
  private readonly pools = inject(Pools);

  protected readonly grades = GRADES;
  protected readonly money = money;
  protected readonly people = signal<PersonView[]>([]);
  /** The figures of the sheet's descendants, as each of them shows its own, re-read on each (re)load. */
  private readonly descendants = signal<DescendantFigures>(NO_DESCENDANTS);
  /** The people listed: retired people only while "show retired" is on. Totals still count everyone. */
  protected readonly listed = computed(() =>
    this.api.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The person being retired, so a second press waits for the first. */
  protected readonly retiring = signal<number | null>(null);
  /** The person being moved, so a second press waits for the first. */
  protected readonly moving = signal<number | null>(null);
  /** The person a hold is being added to, so a second press waits for the first. */
  protected readonly holding = signal<number | null>(null);
  /** Every claim on the sheet, newest first. */
  protected readonly claims = signal<ClaimView[]>([]);
  /** The ids of the claims selected for a bulk action. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** What the last bulk open did, until the button is pressed again. */
  protected readonly bulkOpenResult = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal<string | null>(null);
  /** Whether a bulk open is running, so a second press waits for the first. */
  protected readonly bulkOpening = signal(false);
  /** What the last bulk settle did, until the button is pressed again. */
  protected readonly bulkSettleResult = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal<string | null>(null);
  /** Whether a bulk settle is running, so a second press waits for the first. */
  protected readonly bulkSettling = signal(false);
  /** The person the selected claims are to move to (an id), or '' before one is chosen. */
  protected readonly bulkReassignTo = signal('');
  /** What the last bulk move did, until the button is pressed again. */
  protected readonly bulkReassignResult = signal<{ reassigned: number; refused: number } | null>(null);
  protected readonly bulkReassignError = signal<string | null>(null);
  /** Whether a bulk move is running, so a second press waits for the first. */
  protected readonly bulkReassigning = signal(false);
  /** The ids of the people selected for a bulk action. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  /** How many people the last bulk grade changed; null while one runs or before any. */
  protected readonly bulkGraded = signal<number | null>(null);
  /** Whether a bulk grade is running, so a second press waits for the first. */
  protected readonly bulkGrading = signal(false);
  /** The grade to give the selected people, and the date it is from (blank takes the sheet's today). */
  protected readonly bulkGradeForm = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
  });
  /** How many people the last bulk retire retired; null while one runs or before any. */
  protected readonly bulkRetired = signal<number | null>(null);
  /** Whether a bulk retire is running, so a second press waits for the first. */
  protected readonly bulkRetiring = signal(false);
  /** The date the selected people are retired from (blank takes the sheet's today). */
  protected readonly bulkRetireForm = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
  });
  /** Each person's statements, by person; one with none is missing. */
  private readonly statements = signal<Map<number, StatementSummary>>(new Map());
  /** The person whose statement is being taken, so a second press waits for the first. */
  protected readonly stating = signal<number | null>(null);
  /** Every patron named on the sheet. */
  private readonly patrons = signal<PatronView[]>([]);
  /** The person a patron is being added to, so a second press waits for the first. */
  protected readonly addingPatron = signal<number | null>(null);
  /** The error of the last patron added on a person's row, until the next action on that row. */
  protected readonly patronError = signal<{ id: number; message: string } | null>(null);
  /** The ids of the people the signed-in user watches. */
  protected readonly watched = signal<ReadonlySet<number>>(new Set());
  /** The person whose watch is being changed, so a second press waits for the first. */
  protected readonly watching = signal<number | null>(null);
  /** Each person's patrons' ids, by person. */
  private readonly patronIdsByPerson = computed(() => {
    const byPerson = new Map<number, number[]>();
    for (const pt of this.patrons()) byPerson.set(pt.personId, [...(byPerson.get(pt.personId) ?? []), pt.patronId]);
    return byPerson;
  });
  /** The person being merged into another, so a second press waits for the first. */
  protected readonly merging = signal<number | null>(null);
  /** The person being transferred to another sheet, so a second press waits for the first. */
  protected readonly transferring = signal<number | null>(null);
  /** The person an alias is being added to, so a second press waits for the first. */
  protected readonly aliasing = signal<number | null>(null);
  /** The error of the last action taken on a person's row, until the next action on that row. */
  protected readonly personError = signal<{ id: number; message: string } | null>(null);
  private readonly claimsByPerson = computed(() => {
    const byPerson = new Map<number, ClaimView[]>();
    for (const c of this.claims()) byPerson.set(c.personId, [...(byPerson.get(c.personId) ?? []), c]);
    return byPerson;
  });
  /** For each person, the other people on the sheet their claims can be moved to. */
  private readonly othersByPerson = computed(() => {
    const people = this.people();
    return new Map(people.map((p) => [p.id, people.filter((q) => q.id !== p.id)]));
  });
  /** The sheet's today ('' before a sheet is loaded). */
  private readonly todayNow = computed(() => this.sheets.current()?.today ?? '');
  /** Whether each claim counts in the live totals: a deferred claim does not, nor do a person's open claims
   *  dated within one of their holds, or all of them once their retirement date is reached, when not bound by a
   *  statement. */
  private readonly countsNow = computed(() => {
    const today = this.todayNow();
    const byPerson = new Map(this.people().map((p) => [p.id, countsWithHolds(p.holds, today, p.retiredFrom)]));
    return (c: ClaimView) => (byPerson.get(c.personId) ?? countsWithHolds([], today))(c);
  });
  protected readonly claimedTotal = computed(() =>
    money(this.claims().filter(this.countsNow()).reduce((sum, c) => sum + cents(c.amount), 0)),
  );
  /** Each person's due-total in whole cents: the sum of the dues of their claims that count (0 with none),
   *  at most the sheet's cap; null when neither their grade in force today nor the grade any of those claims is at has a rate in
   *  force today, and none of those claims has a due. */
  private readonly dueByPerson = computed(() => {
    const cap = this.capCents();
    return new Map([...this.uncappedDueByPerson()].map(([id, due]) =>
      [id, due === null || cap === null ? due : Math.min(due, cap)]));
  });
  /** The sheet's cap in whole cents; null with none. */
  private readonly capCents = computed(() => {
    const cap = this.sheets.current()?.cap;
    return cap === null || cap === undefined ? null : cents(cap);
  });
  /** Each person's due-total before the cap, in whole cents; null as for the due-total. */
  private readonly uncappedDueByPerson = computed(() => {
    const today = this.rates.byGrade();
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, number | null>();
    for (const p of this.people()) {
      const counting = this.claimsOf(p.id).filter(countsWithHolds(p.holds, this.todayNow(), p.retiredFrom));
      const grades = [this.gradeToday(p), ...counting.map((c) => gradeOn(p, c.date))];
      const dues = counting.map((c) => this.claimDue(p, c, dueTerms)).filter((due) => due !== null);
      if (!grades.some((g) => today.has(g)) && dues.length === 0) {
        byPerson.set(p.id, null);
        continue;
      }
      byPerson.set(p.id, dues.reduce((sum, due) => sum + due, 0));
    }
    return byPerson;
  });
  /** Each person's due-total before the cap split by status, in whole cents: the dues of their SETTLED claims that
   *  count (at their frozen figures, corrections included) and of their OPEN and PENDING claims that count. */
  private readonly splitByPerson = computed(() => {
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, Split>();
    for (const p of this.people()) {
      const split = { ...NO_SPLIT };
      for (const c of this.claimsOf(p.id).filter(countsWithHolds(p.holds, this.todayNow(), p.retiredFrom))) {
        const due = this.claimDue(p, c, dueTerms) ?? 0;
        if (c.status === 'SETTLED') split.settled += due;
        else if (c.status === 'OPEN' || c.status === 'PENDING') split.open += due;
      }
      byPerson.set(p.id, split);
    }
    return byPerson;
  });
  /** Each person's cut total in whole cents: their cuts across every pool they are a member of, added up; a pool
   *  that shows no cuts adds nothing. */
  private readonly cutByPerson = computed(() => {
    const byPerson = new Map<number, number>();
    for (const pool of this.pools.all()) {
      for (const m of pool.members) {
        if (m.personId !== null && m.cut !== null) {
          byPerson.set(m.personId, (byPerson.get(m.personId) ?? 0) + cents(m.cut));
        }
      }
    }
    return byPerson;
  });
  protected cutOf(personId: number): number {
    return this.cutByPerson().get(personId) ?? 0;
  }
  /** The sheet's cut total in whole cents: everyone's cut total added up. */
  protected readonly cutTotal = computed(() =>
    this.people().reduce((sum, p) => sum + this.cutOf(p.id), 0));
  /** Everyone's settled-total and open-total, each simply added up (not trimmed). */
  protected readonly splitTotals = computed(() => {
    const total = { ...NO_SPLIT };
    for (const split of this.splitByPerson().values()) {
      total.settled += split.settled;
      total.open += split.open;
    }
    return total;
  });
  /** For every tag used on the sheet's claims, in tag order, the dues in whole cents of the claims that count which
   *  carry it (none counting as 0); a claim with two tags counts in both. */
  protected readonly tagTotals = computed(() => {
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const totals = new Map<string, number>();
    for (const c of this.claims()) for (const tag of c.tags) totals.set(tag, 0);
    for (const p of this.people()) {
      for (const c of this.claimsOf(p.id).filter(countsWithHolds(p.holds, this.todayNow(), p.retiredFrom))) {
        if (c.tags.length === 0) continue;
        const due = this.claimDue(p, c, dueTerms) ?? 0;
        for (const tag of c.tags) totals.set(tag, (totals.get(tag) ?? 0) + due);
      }
    }
    return [...totals].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([tag, cents]) => ({ tag, cents }));
  });
  /** For every calendar month (yyyy-mm) any of the sheet's claims is dated in, in month order, the dues in whole
   *  cents of the claims dated in it that count (none counting as 0). */
  protected readonly monthTotals = computed(() => {
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const totals = new Map<string, number>();
    for (const c of this.claims()) totals.set(c.date.slice(0, 7), 0);
    for (const p of this.people()) {
      for (const c of this.claimsOf(p.id).filter(countsWithHolds(p.holds, this.todayNow(), p.retiredFrom))) {
        const month = c.date.slice(0, 7);
        totals.set(month, (totals.get(month) ?? 0) + (this.claimDue(p, c, dueTerms) ?? 0));
      }
    }
    return [...totals].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([month, cents]) => ({ month, cents }));
  });
  /** Everyone's due-total added up, skipping those with none, trimmed of the highest and lowest. */
  protected readonly dueTotal = computed(() =>
    money(trimmedSum([...this.dueByPerson().values()].filter((due) => due !== null))),
  );
  /** The sheet's own people count, due-total and overdue-total, as shown here, added to its descendants'; while
   *  the sheet is shown as of a date, its own live figures, as the consolidated ones are not dated. */
  protected readonly consolidated = computed(() => {
    const d = this.descendants();
    if (this.sheets.asOf()) return d;
    return {
      peopleCount: this.people().length + d.peopleCount,
      dueTotal: money(cents(this.dueTotal()) + cents(d.dueTotal)),
      overdueTotal: money(this.overdueTotals().cents + cents(d.overdueTotal)),
    };
  });
  /** Each person's overdue claims that count: how many, and their dues added up in whole cents (none counting as 0). */
  private readonly overdueByPerson = computed(() => {
    const sheet = this.sheets.current();
    const dueTerms = sheet ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, Overdue>();
    for (const p of this.people()) {
      const overdue = sheet
        ? this.claimsOf(p.id).filter((c) => countsWithHolds(p.holds, sheet.today, p.retiredFrom)(c) && isOverdue(c, sheet.today, graceOn(sheet.settings, gradeOn(p, c.date), c.date)))
        : NO_CLAIMS;
      byPerson.set(p.id, {
        count: overdue.length,
        cents: overdue.reduce((sum, c) => sum + (this.claimDue(p, c, dueTerms) ?? 0), 0),
      });
    }
    return byPerson;
  });
  /** Each person's aged dues: their OPEN and PENDING claims that count, by age in days on the sheet's today. */
  private readonly agedByPerson = computed(() => {
    const today = this.todayNow();
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, Aged>();
    for (const p of this.people()) {
      const aged = { ...NO_AGED };
      if (today) {
        for (const c of this.claimsOf(p.id).filter(countsWithHolds(p.holds, today, p.retiredFrom))) {
          if (c.status !== 'OPEN' && c.status !== 'PENDING') continue;
          const due = this.claimDue(p, c, dueTerms) ?? 0;
          const age = Math.round((Date.parse(today) - Date.parse(c.date)) / 86_400_000);
          if (age <= 30) aged.upTo30 += due;
          else if (age <= 60) aged.upTo60 += due;
          else aged.over60 += due;
        }
      }
      byPerson.set(p.id, aged);
    }
    return byPerson;
  });
  /** Everyone's overdue claims added up: the count of them all, the dues trimmed of the highest and lowest person's. */
  protected readonly overdueTotals = computed(() => {
    const overdue = [...this.overdueByPerson().values()];
    return {
      count: overdue.reduce((sum, o) => sum + o.count, 0),
      cents: trimmedSum(overdue.map((o) => o.cents)),
    };
  });
  /** Each person's due-total for each period, in whole cents, by person id then period id: the dues as shown now
   *  of their claims whose due-by falls in it and that counted by the status they had at the end of its last day
   *  (none counting as 0); a closed or soft-closed period keeps the figures fixed when it was closed, 0 for a person added since. */
  private readonly dueByPersonPeriod = computed(() => {
    const periods = this.periods.list();
    const dueTerms = this.sheets.current() ?? DEFAULT_DUE_TERMS;
    const byPerson = new Map<number, Map<number, number>>();
    for (const p of this.people()) {
      const live = new Map<number, number>(periods.map((pd) => [pd.id, 0]));
      for (const c of this.claimsOf(p.id)) {
        const period = periodOn(periods, c.dueBy);
        if (period && countsAtEndWithHolds(p.holds, this.todayNow(), period.to, p.retiredFrom)(c)) {
          live.set(period.id, (live.get(period.id) ?? 0) + (this.claimDue(p, c, dueTerms) ?? 0));
        }
      }
      for (const pd of periods) {
        if (pd.closed || pd.softClosed) live.set(pd.id, cents(pd.closedPersonTotals[String(p.id)] ?? '0.00'));
      }
      byPerson.set(p.id, live);
    }
    return byPerson;
  });
  /** Each period's due-total, by period id: the sum of the people's figures for it. */
  protected readonly dueByPeriod = computed(() => {
    const totals = new Map<number, number>(this.periods.list().map((pd) => [pd.id, 0]));
    for (const figures of this.dueByPersonPeriod().values()) {
      for (const [id, due] of figures) totals.set(id, (totals.get(id) ?? 0) + due);
    }
    return new Map([...totals].map(([id, total]) => [id, money(total)]));
  });
  /** Whether each person is active, by person id: they have an OPEN or PENDING claim that counts dated within the
   *  30 days up to and including the sheet's today. */
  protected readonly activeByPerson = computed(() => {
    const today = this.todayNow();
    const from = today ? new Date(Date.parse(today) - 29 * 86_400_000).toISOString().slice(0, 10) : '';
    return new Map(this.people().map((p) => [p.id, today !== '' && this.claimsOf(p.id).some((c) =>
      (c.status === 'OPEN' || c.status === 'PENDING') && countsWithHolds(p.holds, today, p.retiredFrom)(c)
      && from <= c.date && c.date <= today)]));
  });
  /** Per grade: the people not retired on it today, their due-totals added up (skipping those with none), and how
   *  many of them are active. */
  protected readonly summaryByGrade = computed<GradeSummary[]>(() => {
    const due = this.dueByPerson();
    const active = this.activeByPerson();
    const current = this.people().filter((p) => !p.retired);
    return GRADES.map((grade) => {
      const onGrade = current.filter((p) => this.gradeToday(p) === grade);
      return {
        grade,
        count: onGrade.length,
        dueTotal: money(onGrade.reduce((sum, p) => sum + (due.get(p.id) ?? 0), 0)),
        active: onGrade.filter((p) => active.get(p.id)).length,
      };
    });
  });
  /** Per period: its due-total, as its row shows it. */
  protected readonly summaryByPeriod = computed<PeriodSummary[]>(() => {
    const due = this.dueByPeriod();
    return this.periods.list().map((p) => ({ name: p.name, dueTotal: due.get(p.id) ?? '0.00' }));
  });
  /** Per status: how many of the sheet's claims are in it. */
  protected readonly summaryByStatus = computed<StatusSummary[]>(() => {
    const claims = this.claims();
    return SUMMARY_STATUSES.map((status) => ({ status, count: claims.filter((c) => c.status === status).length }));
  });
  private readonly voidReasons = inject(VoidReasons);
  /** Per reason on the sheet's list, how many VOID claims were voided with it. */
  protected readonly summaryByVoidReason = computed<VoidReasonSummary[]>(() => {
    const claims = this.claims();
    return this.voidReasons.all().map(({ reason }) => ({
      reason,
      count: claims.filter((c) => c.status === 'VOID' && c.voidReason === reason).length,
    }));
  });
  /** A person's due-total for a period, with two decimals. */
  protected periodDueOf(personId: number, periodId: number): string {
    return money(this.dueByPersonPeriod().get(personId)?.get(periodId) ?? 0);
  }
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor loaded. */
  private editingVersion = 0;
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
    grade: new FormControl<Grade>('A', { nonNullable: true }),
    /** While editing: the date the grade is from (yyyy-mm-dd); blank takes the sheet's today. */
    gradeFrom: new FormControl('', { nonNullable: true }),
    /** The person's own terms in days; blank for none. */
    terms: new FormControl('', { nonNullable: true }),
    /** The person's contact; blank for none. */
    contact: new FormControl('', { nonNullable: true }),
  });

  protected readonly quickError = signal<string | null>(null);
  protected readonly quickSaving = signal(false);

  protected readonly quickForm = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
  });

  protected readonly pasteError = signal<string | null>(null);
  protected readonly pasting = signal(false);
  /** How many claims the last paste added; null while one runs or before any. */
  protected readonly pasteAdded = signal<number | null>(null);

  protected readonly pasteForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });

  protected readonly importError = signal<string | null>(null);
  protected readonly importing = signal(false);
  /** How many people the last import added; null while one runs or before any. */
  protected readonly importAdded = signal<number | null>(null);
  /** How many people the last import updated; null while one runs or before any. */
  protected readonly importUpdated = signal<number | null>(null);

  protected readonly importForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its people from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.people.set([]);
          this.claims.set([]);
          this.selectedClaims.set(new Set());
          this.selectedPeople.set(new Set());
          this.bulkGraded.set(null);
          this.bulkGradeForm.reset({ grade: 'A', from: '' }, { emitEvent: false });
          this.bulkRetired.set(null);
          this.bulkRetireForm.reset({ from: '' }, { emitEvent: false });
          this.bulkOpenResult.set(null);
          this.bulkOpenError.set(null);
          this.bulkSettleResult.set(null);
          this.bulkSettleError.set(null);
          this.bulkReassignTo.set('');
          this.bulkReassignResult.set(null);
          this.bulkReassignError.set(null);
          this.statements.set(new Map());
          this.descendants.set(NO_DESCENDANTS);
          this.patrons.set([]);
          this.watched.set(new Set());
          this.personError.set(null);
          this.patronError.set(null);
          this.reset();
          this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
          this.quickError.set(null);
          this.pasteForm.reset({ text: '' }, { emitEvent: false });
          this.pasteError.set(null);
          this.pasteAdded.set(null);
          this.importForm.reset({ text: '' }, { emitEvent: false });
          this.importError.set(null);
          this.importAdded.set(null);
          this.importUpdated.set(null);
          return sheet
            ? forkJoin({
                people: this.api.list(sheet.id),
                claims: this.claimsApi.list(sheet.id),
                statements: this.statementsApi.list(sheet.id),
                patrons: this.patronsApi.list(sheet.id),
                watched: this.watchesApi.list(sheet.id),
                descendants: this.consolidatedApi.descendants(sheet.id, this.sheets.asOf() !== null),
              })
            : of({ people: [], claims: [], statements: [], patrons: [], watched: [], descendants: NO_DESCENDANTS });
        }),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: ({ people, claims, statements, patrons, watched, descendants }) => {
          this.descendants.set(descendants);
          this.patrons.set(patrons);
          this.watched.set(new Set(watched));
          this.people.set(people);
          this.claims.set(claims);
          this.statements.set(new Map(statements.map((s) => [s.personId, s])));
        },
      });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
    this.quickForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.quickError.set(null));
    this.pasteForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.pasteError.set(null));
    this.importForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.importError.set(null));
  }

  protected claimsOf(personId: number): ClaimView[] {
    return this.claimsByPerson().get(personId) ?? NO_CLAIMS;
  }

  /** The person's grade in force on the sheet's today. */
  protected gradeToday(p: PersonView): Grade {
    return gradeOn(p, this.sheets.current()?.today ?? '');
  }

  /** A claim's due in whole cents, at the rates of its person's grade in force on its date; null with none. */
  private claimDue(p: PersonView, c: ClaimView, dueTerms: DueTerms): number | null {
    const grade = gradeOn(p, c.date);
    return claimDueCents(c, this.rates.lineByGrade().get(grade) ?? NO_RATES, dueTerms,
      roundingDate(c, this.claimsOf(p.id)), grade);
  }

  protected dueOf(p: PersonView): string {
    const due = this.dueByPerson().get(p.id);
    return due === null || due === undefined ? NOT_AVAILABLE : money(due);
  }

  protected splitOf(personId: number): Split {
    return this.splitByPerson().get(personId) ?? NO_SPLIT;
  }

  /** How far the person's figures add up above the cap, in whole cents; 0 when they do not, or have no due-total. */
  protected excessOf(personId: number): number {
    const sum = this.uncappedDueByPerson().get(personId) ?? null;
    const due = this.dueByPerson().get(personId) ?? null;
    return sum === null || due === null ? 0 : sum - due;
  }

  /** Download the people as CSV, in list order, retired people included. */
  protected exportCsv(): void {
    const lines = [
      ['name', 'grade', 'retired-from', 'due-total'],
      ...this.people().map((p) => [p.name, this.gradeToday(p), p.retiredFrom ?? '', this.dueOf(p)]),
    ].map((fields) => fields.map(csvField).join(',') + '\n');
    const url = URL.createObjectURL(new Blob(lines, { type: 'text/csv' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'people-' + (this.sheets.current()?.id ?? 'sheet') + '.csv';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  /** The person's patrons, each as <name> <share>, sorted by name, comma and space separated. */
  protected patronsOf(personId: number): string {
    const nameOf = new Map(this.people().map((q) => [q.id, q.name]));
    return patronShares(this.patronIdsByPerson().get(personId) ?? [])
      .map(({ id, share }) => ({ name: nameOf.get(id) ?? '', share }))
      .sort((a, b) => a.name.localeCompare(b.name))
      .map(({ name, share }) => name + ' ' + money(share))
      .join(', ');
  }

  /** The person's backing: over their patrons, each patron's settled figures over their SETTLED claims dated within
   *  the 90 days up to and including the sheet's today, times the patron's share divided by 100, added up and rounded
   *  half to even; 0.00 with none. */
  protected backingOf(personId: number): string {
    const today = this.todayNow();
    if (today === '') return money(0);
    const from = new Date(Date.parse(today) - 89 * 86_400_000).toISOString().slice(0, 10);
    // Figures in cents times shares in hundredths: the exact backing in ten-thousandths of a cent.
    const scaled = patronShares(this.patronIdsByPerson().get(personId) ?? []).reduce((sum, { id, share }) => sum
      + share * this.claimsOf(id)
        .filter((c) => c.status === 'SETTLED' && c.settledDue !== null && from <= c.date && c.date <= today)
        .reduce((s, c) => s + cents(c.settledDue ?? '0'), 0), 0);
    return money(divideHalfEven(scaled, 10_000));
  }

  /** Start watching the person, or stop when the signed-in user already watches them. */
  protected toggleWatch(p: PersonView): void {
    if (this.watching() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.watching.set(p.id);
    this.watchesApi.set(sheet.id, p.id, !this.watched().has(p.id)).subscribe({
      next: ({ personId, watched }) => {
        this.watching.set(null);
        this.watched.update((set) => {
          const next = new Set(set);
          if (watched) next.add(personId);
          else next.delete(personId);
          return next;
        });
      },
      error: () => {
        this.watching.set(null);
        this.personError.set({ id: p.id, message: 'Could not change whether you watch ' + p.name + '.' });
      },
    });
  }

  /** Name the chosen person as the person's patron; refused when it is themselves or already their patron. */
  protected addPatron(p: PersonView, patronId: string): void {
    if (this.addingPatron() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet || patronId === '') return;
    this.addingPatron.set(p.id);
    this.patronsApi.add(sheet.id, p.id, Number(patronId)).subscribe({
      next: (added) => {
        this.addingPatron.set(null);
        this.patrons.update((list) => [...list, added]);
      },
      error: (err: unknown) => {
        this.addingPatron.set(null);
        this.patronError.set({
          id: p.id,
          message:
            err instanceof HttpErrorResponse && err.status === 422
              ? p.name + ' cannot be their own patron.'
              : err instanceof HttpErrorResponse && err.status === 409
                ? 'That person is already a patron of ' + p.name + '.'
                : 'Could not add that patron.',
        });
      },
    });
  }

  protected agedOf(personId: number): Aged {
    return this.agedByPerson().get(personId) ?? NO_AGED;
  }

  protected overdueOf(personId: number): Overdue {
    return this.overdueByPerson().get(personId) ?? NO_OVERDUE;
  }

  protected statementOf(personId: number): StatementSummary | undefined {
    return this.statements().get(personId);
  }

  /** Take a statement of the person, as of the date in its entry (blank: now), then show their summary and their
   *  claims as it left them; the entry is cleared once it is taken or refused. */
  protected takeStatement(p: PersonView, asOf: HTMLInputElement): void {
    if (this.stating() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.stating.set(p.id);
    this.statementsApi.take(sheet.id, p.id, asOf.value || null).subscribe({
      next: ({ summary, claims }) => {
        this.stating.set(null);
        asOf.value = '';
        this.statements.update((map) => new Map(map).set(summary.personId, summary));
        const byId = new Map(claims.map((c) => [c.id, c]));
        this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      },
      error: () => {
        this.stating.set(null);
        asOf.value = '';
        this.personError.set({ id: p.id, message: 'Could not take a statement of ' + p.name + '.' });
      },
    });
  }

  /** Cancel the person's last statement, then show the statement before it and their claims live again. */
  protected cancelStatement(p: PersonView): void {
    if (this.stating() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.stating.set(p.id);
    this.statementsApi.cancel(sheet.id, p.id).subscribe({
      next: ({ summary, claims }) => {
        this.stating.set(null);
        this.statements.update((map) => {
          const next = new Map(map);
          if (summary.count === 0) next.delete(summary.personId);
          else next.set(summary.personId, summary);
          return next;
        });
        const byId = new Map(claims.map((c) => [c.id, c]));
        this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      },
      error: () => {
        this.stating.set(null);
        this.personError.set({ id: p.id, message: 'Could not cancel the last statement of ' + p.name + '.' });
      },
    });
  }

  /** Retire the person from a date; a blank date takes the sheet's today. */
  protected retire(p: PersonView, from: string): void {
    if (this.retiring() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.retiring.set(p.id);
    this.api.retire(sheet.id, p.id, from === '' ? null : from).subscribe({
      next: (retired) => {
        this.retiring.set(null);
        this.people.update((list) => list.map((q) => (q.id === retired.id ? retired : q)));
      },
      error: () => {
        this.retiring.set(null);
        this.personError.set({ id: p.id, message: 'Could not retire ' + p.name + '.' });
      },
    });
  }

  /** Unretire the person on the sheet's today; anyone not retired is refused. */
  protected unretire(p: PersonView): void {
    if (this.retiring() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.retiring.set(p.id);
    this.api.unretire(sheet.id, p.id).subscribe({
      next: (unretired) => {
        this.retiring.set(null);
        this.people.update((list) => list.map((q) => (q.id === unretired.id ? unretired : q)));
      },
      error: () => {
        this.retiring.set(null);
        this.personError.set({ id: p.id, message: 'Only a retired person can be unretired; ' + p.name + ' is not retired.' });
      },
    });
  }

  /** Merge the person into the chosen one; once merged the sheet is re-read, as everything on it may follow. */
  protected merge(p: PersonView, intoId: string): void {
    if (this.merging() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet || intoId === '') return;
    this.merging.set(p.id);
    this.api.merge(sheet.id, p.id, Number(intoId)).subscribe({
      next: () => {
        this.merging.set(null);
        this.sheets.load(sheet.id).subscribe();
      },
      error: () => {
        this.merging.set(null);
        this.personError.set({ id: p.id, message: 'Could not merge ' + p.name + ' into that person.' });
      },
    });
  }

  /** Transfer the person to the sheet typed; once transferred the sheet is re-read, as their links go with them. */
  protected transfer(p: PersonView, toSheetId: string): void {
    if (this.transferring() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const target = toSheetId.trim();
    if (target === '' || target === sheet.id) {
      this.personError.set({ id: p.id, message: 'Choose another sheet to transfer ' + p.name + ' to.' });
      return;
    }
    this.transferring.set(p.id);
    this.api.transfer(sheet.id, p.id, target).subscribe({
      next: () => {
        this.transferring.set(null);
        this.sheets.load(sheet.id).subscribe();
      },
      error: () => {
        this.transferring.set(null);
        this.personError.set({ id: p.id, message: 'Could not transfer ' + p.name + ' to sheet ' + target + '.' });
      },
    });
  }

  /** Give the person the alias typed; a blank one is refused on the row. */
  protected addAlias(p: PersonView, input: HTMLInputElement): void {
    if (this.aliasing() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    if (input.value.trim() === '') {
      this.personError.set({ id: p.id, message: 'An alias cannot be blank.' });
      return;
    }
    this.aliasing.set(p.id);
    this.api.addAlias(sheet.id, p.id, input.value).subscribe({
      next: (saved) => {
        this.aliasing.set(null);
        input.value = '';
        this.people.update((list) => list.map((q) => (q.id === saved.id ? saved : q)));
      },
      error: () => {
        this.aliasing.set(null);
        this.personError.set({ id: p.id, message: 'That alias is already a name or alias on this sheet.' });
      },
    });
  }

  /** Put the person on hold over the dates given: a blank from-date takes the sheet's today, a blank to-date has no end. */
  protected hold(p: PersonView, from: string, to: string): void {
    if (this.holding() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.holding.set(p.id);
    this.api.hold(sheet.id, p.id, from === '' ? null : from, to === '' ? null : to).subscribe({
      next: (saved) => {
        this.holding.set(null);
        this.people.update((list) => list.map((q) => (q.id === saved.id ? saved : q)));
      },
      error: () => {
        this.holding.set(null);
        this.personError.set({ id: p.id, message: 'Could not hold ' + p.name + ' over those dates.' });
      },
    });
  }

  /** Swap the person with the one above or below them in the list. */
  protected move(p: PersonView, direction: 'up' | 'down'): void {
    if (this.moving() === p.id) return;
    this.personError.set(null);
    this.patronError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.moving.set(p.id);
    this.api.move(sheet.id, p.id, direction).subscribe({
      next: (people) => {
        this.moving.set(null);
        this.people.set(people);
      },
      error: () => {
        this.moving.set(null);
        this.personError.set({ id: p.id, message: 'Could not move ' + p.name + ' ' + direction + '.' });
      },
    });
  }

  /** Clear the error shown on a person's row once its form is changed. */
  protected clearPersonError(id: number): void {
    if (this.personError()?.id === id) this.personError.set(null);
  }

  protected toggleSelected({ id, selected }: { id: number; selected: boolean }): void {
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (selected) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  protected togglePerson(id: number, selected: boolean): void {
    this.selectedPeople.update((ids) => {
      const next = new Set(ids);
      if (selected) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Give the selected people the chosen grade together, in list order, each as an edit in the person form would. */
  protected bulkGrade(): void {
    if (this.bulkGrading()) return;
    this.bulkGraded.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const ids = this.people().map((p) => p.id).filter((id) => this.selectedPeople().has(id));
    const first = ids[0];
    // A bulk grade is the next action on each selected row: no row's old error stays showing.
    this.personError.set(null);
    const { grade, from } = this.bulkGradeForm.getRawValue();
    this.bulkGrading.set(true);
    this.api.bulkGrade(sheet.id, ids, grade, from === '' ? null : from).subscribe({
      next: ({ changed, people }) => {
        this.bulkGrading.set(false);
        const byId = new Map(people.map((p) => [p.id, p]));
        this.people.update((list) => list.map((p) => byId.get(p.id) ?? p));
        this.selectedPeople.set(new Set());
        this.bulkGraded.set(changed);
      },
      error: () => {
        this.bulkGrading.set(false);
        // The refusal shows on the first selected person's row.
        if (first !== undefined) this.personError.set({ id: first, message: 'Could not grade the selected people.' });
      },
    });
  }

  /** Retire the selected people together from a date, in list order, each as the row's retire would. */
  protected bulkRetire(): void {
    if (this.bulkRetiring()) return;
    this.bulkRetired.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const ids = this.people().map((p) => p.id).filter((id) => this.selectedPeople().has(id));
    const first = ids[0];
    // A bulk retire is the next action on each selected row: no row's old error stays showing.
    this.personError.set(null);
    const { from } = this.bulkRetireForm.getRawValue();
    this.bulkRetiring.set(true);
    this.api.bulkRetire(sheet.id, ids, from === '' ? null : from).subscribe({
      next: ({ retired, people }) => {
        this.bulkRetiring.set(false);
        const byId = new Map(people.map((p) => [p.id, p]));
        this.people.update((list) => list.map((p) => byId.get(p.id) ?? p));
        this.selectedPeople.set(new Set());
        this.bulkRetired.set(retired);
      },
      error: () => {
        this.bulkRetiring.set(false);
        // The refusal shows on the first selected person's row.
        if (first !== undefined) this.personError.set({ id: first, message: 'Could not retire the selected people.' });
      },
    });
  }

  /** Open the selected claims together: each that can be is opened, numbered in list order; the rest are left as they are. */
  protected bulkOpen(): void {
    if (this.bulkOpening()) return;
    this.bulkOpenResult.set(null);
    this.bulkOpenError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.bulkOpening.set(true);
    this.claimsApi.bulkOpen(sheet.id, [...this.selectedClaims()]).subscribe({
      next: ({ opened, refused, claims }) => {
        this.bulkOpening.set(false);
        claims.forEach((c) => this.claimSaved(c));
        this.selectedClaims.set(new Set());
        this.bulkOpenResult.set({ opened, refused });
      },
      error: () => {
        this.bulkOpening.set(false);
        this.bulkOpenError.set('Could not open the selected claims.');
      },
    });
  }

  /** Settle the selected claims together, in list order, each by the rules of settling one; the rest are left as they are. */
  protected bulkSettle(): void {
    if (this.bulkSettling()) return;
    this.bulkSettleResult.set(null);
    this.bulkSettleError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.bulkSettling.set(true);
    this.claimsApi.bulkSettle(sheet.id, [...this.selectedClaims()]).subscribe({
      next: ({ settled, refused, claims, batch }) => {
        this.bulkSettling.set(false);
        claims.forEach((c) => this.claimSaved(c));
        if (batch !== null) this.sheets.settleBatched(batch);
        this.selectedClaims.set(new Set());
        this.bulkSettleResult.set({ settled, refused });
      },
      error: () => {
        this.bulkSettling.set(false);
        this.bulkSettleError.set('Could not settle the selected claims.');
      },
    });
  }

  protected bulkReassignToChanged(personId: string): void {
    this.bulkReassignTo.set(personId);
    this.bulkReassignError.set(null);
  }

  /** Move the selected claims to the chosen person together, in list order, each by the rules of moving one; the rest are left as they are. */
  protected bulkReassign(): void {
    if (this.bulkReassigning()) return;
    this.bulkReassignResult.set(null);
    this.bulkReassignError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const to = Number(this.bulkReassignTo());
    if (!to) {
      this.bulkReassignError.set('Choose the person to move the selected claims to.');
      return;
    }
    this.bulkReassigning.set(true);
    this.claimsApi.bulkReassign(sheet.id, [...this.selectedClaims()], to).subscribe({
      next: ({ reassigned, refused, claims }) => {
        this.bulkReassigning.set(false);
        claims.forEach((c) => this.claimSaved(c));
        this.selectedClaims.set(new Set());
        this.bulkReassignResult.set({ reassigned, refused });
      },
      error: () => {
        this.bulkReassigning.set(false);
        this.bulkReassignError.set('Could not move the selected claims.');
      },
    });
  }

  /** A claim as saved: a new one, or one moved to another person, is listed first (its person's newest). */
  protected claimSaved(saved: ClaimView): void {
    this.claims.update((list) => {
      const was = list.find((c) => c.id === saved.id);
      return was && was.personId === saved.personId
        ? list.map((c) => (c.id === saved.id ? saved : c))
        : [saved, ...list.filter((c) => c.id !== saved.id)];
    });
  }

  /** The other people on the sheet a person's claims can be moved to. */
  protected othersOf(personId: number): PersonView[] {
    return this.othersByPerson().get(personId) ?? [];
  }

  /** Quick-add a DRAFT claim for the named person, adding them first (grade A) when not on the sheet. */
  protected quickAdd(): void {
    // A second submit before the first has answered is ignored, so it stores one record.
    if (this.quickSaving()) return;
    this.quickError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.quickForm.getRawValue();
    if (value.name.trim() === '') {
      this.quickError.set('Type a name for the person.');
      return;
    }
    const amount = value.amount.trim();
    if (!AMOUNT_PATTERN.test(amount)) {
      this.quickError.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    this.quickSaving.set(true);
    this.claimsApi.quickAdd(sheet.id, value.name, amount).subscribe({
      next: ({ person, personAdded, claim }) => {
        this.quickSaving.set(false);
        if (personAdded) this.people.update((list) => [...list, person]);
        this.claimSaved(claim);
        this.quickForm.reset({ name: '', amount: '' }, { emitEvent: false });
      },
      error: (err: unknown) => {
        this.quickSaving.set(false);
        this.quickError.set(
          err instanceof HttpErrorResponse && err.status === 423
            ? 'That claim is due by inside a closed period.'
            : err instanceof HttpErrorResponse && err.status === 422
              ? 'That claim is dated after the person retired.'
            : err instanceof HttpErrorResponse && err.status === 409
              ? 'That name is already in use on this sheet.'
              : 'Could not add that claim.',
        );
      },
    });
  }

  /** Add a DRAFT claim per pasted line for people on the sheet; when any line is refused, none is added. */
  protected paste(): void {
    if (this.pasting()) return;
    this.pasteError.set(null);
    this.pasteAdded.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const text = this.pasteForm.getRawValue().text;
    if (text.trim() === '') {
      this.pasteError.set('Paste at least one line of name,amount,date,note.');
      return;
    }
    this.pasting.set(true);
    this.claimsApi.paste(sheet.id, text).subscribe({
      next: ({ added, claims }) => {
        this.pasting.set(false);
        claims.forEach((c) => this.claimSaved(c));
        this.pasteAdded.set(added);
      },
      error: (err: unknown) => {
        this.pasting.set(false);
        this.pasteError.set(
          err instanceof HttpErrorResponse && err.status === 423
            ? 'A pasted claim is due by inside a closed period; nothing was added.'
            : err instanceof HttpErrorResponse && err.status === 422
              ? 'A pasted claim is dated after its person retired; nothing was added.'
            : err instanceof HttpErrorResponse && err.status === 409
              ? 'A pasted reference is already used on this sheet or repeated in the paste; nothing was added.'
              : 'A pasted line is not a valid claim for someone on this sheet; nothing was added.',
        );
      },
    });
  }

  /** Add or update a person per pasted line, as the person form would; when any line is refused, none is imported. */
  protected importPeople(): void {
    if (this.importing()) return;
    this.importError.set(null);
    this.importAdded.set(null);
    this.importUpdated.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const text = this.importForm.getRawValue().text;
    if (text.trim() === '') {
      this.importError.set('Paste at least one line of name,grade,terms,contact.');
      return;
    }
    this.importing.set(true);
    this.api.importPeople(sheet.id, text).subscribe({
      next: ({ added, updated, people }) => {
        this.importing.set(false);
        this.people.update((list) => {
          const byId = new Map(people.map((p) => [p.id, p]));
          const kept = list.map((p) => byId.get(p.id) ?? p);
          return [...kept, ...people.filter((p) => !list.some((q) => q.id === p.id))];
        });
        this.importAdded.set(added);
        this.importUpdated.set(updated);
      },
      error: (err: unknown) => {
        this.importing.set(false);
        this.importError.set(
          err instanceof HttpErrorResponse && err.status === 409
            ? 'A pasted name or contact is already in use or repeated in the paste; nothing was imported.'
            : 'A pasted line is not a valid person; nothing was imported.',
        );
      },
    });
  }

  protected edit(p: PersonView): void {
    this.personError.set(null);
    this.patronError.set(null);
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.form.setValue({ name: p.name, grade: this.gradeToday(p), gradeFrom: '', terms: termsText(p), contact: p.contact ?? '' }, { emitEvent: false });
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', grade: 'A', gradeFrom: '', terms: '', contact: '' }, { emitEvent: false });
    this.error.set(null);
  }

  protected submit(): void {
    // A second submit before the first has answered is ignored, so it stores one record.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.form.getRawValue();
    if (value.name.trim() === '') {
      this.error.set('Type a name for the person.');
      return;
    }
    const termsValue = value.terms.trim();
    if (termsValue !== '' && !TERMS_PATTERN.test(termsValue)) {
      // Refused terms close the editor, saving nothing, so the form is ready for the next person.
      if (this.editingId() !== null) this.reset();
      this.error.set('Type terms of 0 or more whole days, or leave them blank.');
      return;
    }
    const terms = termsValue === '' ? null : Number(termsValue);
    const id = this.editingId();
    this.saving.set(true);
    const request = id === null
      ? this.api.add(sheet.id, { name: value.name, grade: value.grade, terms, contact: value.contact })
      : this.api.update(sheet.id, id, this.editingVersion,
          { name: value.name, grade: value.grade, gradeFrom: value.gradeFrom === '' ? null : value.gradeFrom, terms,
            contact: value.contact });
    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.people.update((list) =>
          id === null ? [...list, saved] : list.map((p) => (p.id === saved.id ? saved : p)),
        );
        this.reset();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (id !== null && err instanceof HttpErrorResponse && err.status === 412) {
          this.error.set('Someone else has saved this person since you opened it; their values are shown.');
          this.showCurrent(sheet.id, id);
        } else if (err instanceof HttpErrorResponse && err.status === 409) {
          this.error.set('That name or contact is already in use on this sheet.');
        } else {
          this.error.set(id === null ? 'Could not add that person.' : 'Could not save that person.');
        }
      },
    });
  }

  /** Re-read the people after a refused edit and show the person's current values in the editor. */
  private showCurrent(sheetId: string, id: number): void {
    this.api.list(sheetId).subscribe({
      next: (people) => {
        this.people.set(people);
        const current = people.find((p) => p.id === id);
        if (current && this.editingId() === id) {
          this.editingVersion = current.version;
          this.form.setValue({ name: current.name, grade: this.gradeToday(current), gradeFrom: '', terms: termsText(current), contact: current.contact ?? '' },
            { emitEvent: false });
        }
      },
    });
  }
}

/** Each patron's share in hundredths, in the order they were named: 100.00 split equally, each share rounded half to
 *  even, with the remainder given to the first-named patron. */
function patronShares(patronIds: number[]): { id: number; share: number }[] {
  if (patronIds.length === 0) return [];
  const each = divideHalfEven(10_000, patronIds.length);
  const first = 10_000 - each * (patronIds.length - 1);
  return patronIds.map((id, i) => ({ id, share: i === 0 ? first : each }));
}

/** An integer divided by a positive integer, rounded half to even. */
function divideHalfEven(numerator: number, divisor: number): number {
  const quotient = Math.floor(numerator / divisor);
  const twice = 2 * (numerator - quotient * divisor);
  if (twice > divisor || (twice === divisor && quotient % 2 !== 0)) return quotient + 1;
  return quotient;
}
