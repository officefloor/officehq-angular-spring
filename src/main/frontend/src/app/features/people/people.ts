import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT, Claim, PersonClaims, cents, counts, deferred, isOpen, money, overdue, plusDays, roundingDate, statusAt } from '../claims/claims';
import { DatedDue, Period, Periods, periodDueTotal, personPeriodDueTotal } from '../periods/periods';
import { DatedGrade, DatedValue, Lateness, Rate, Rates, chargedDueCents, dueText, gradeOn, pctOn, valueOn } from '../rates/rates';
import { Roles } from '../roles/roles';
import { Settings } from '../settings/settings';
import { Header } from '../header/header';
import { GradeSummary, PeriodSummary, StatusSummary, Summary } from '../summary/summary';

// The people on the active sheet, in the order they were added, and the one form that both adds
// a person and edits an existing one (opening a person's editor fills the form with their values).
// A person's grade is dated: their first applies from the beginning of time, and each save records
// the form's grade from its from-date (the sheet's today when blank), keeping those recorded before;
// a save leaving the grade as the row shows it, with the from-date blank, records none. The row
// shows the grade in force on the sheet's today; each claim uses the one in force on its date.
// A person may have their own terms, a whole number of days (blank for none), shown on the row; a
// claim's due-by is fixed by the server when it is created, from these before the grade's or sheet's.
// The editor remembers the version of the person it opened; if someone else has saved the person
// since, the save is refused and the form is refilled with the values the store holds now.
// Each row shows what the person has claimed and lists their claims; the sheet's claimed-total
// adds everyone's up. Only OPEN, PENDING and SETTLED claims count toward any of these totals. Each claim shows
// what is due at the rate for the person's grade in force on the claim's date (a settled claim the
// figure fixed when settled), each person the sum of those figures over the claims that count, and
// the sheet's due-total adds up every person's due-total that has one. A live due is never below
// the floor in force on the claim's date (a figure fixed when settled, bound by a statement or by closing a period keeps its own).
// Taking a person's statement binds each of their claims that counts to the figure it has then
// (a stated claim shows and totals that figure); each row shows how many statements the person
// has taken, and the last one's due-total and date. A steward can cancel the last statement: the
// claims it bound are live again, and the row shows the statement before it (or none). A statement
// can be taken as of a date on or before the sheet's today: it binds the claims that counted at the
// end of that date to their figures as they stood then, and shows that date as its date.
// Each claim shows the period its date falls in, and each period the due of the claims that counted in it
// at its end (by the status each had then, as its log records it).
// A person can be retired from a date (the sheet's today when left blank); with a date set they count
// as retired at once, whatever the date. No claim of theirs can be dated after it. Retired people are
// left out of the list unless "show retired" is on. Once the sheet's today reaches their retirement
// date their OPEN and PENDING claims leave every live total, as a hold's would (unless a statement binds them).
// A person can be put on hold from a date (the sheet's today when left blank) to an optional to-date,
// and may have several holds over time; a hold ends on its to-date. Their OPEN and PENDING claims dated
// within a hold count in no total (their own, the sheet's or a period's); their other claims still do,
// and so does a claim bound by a statement, whatever its date. A period counts a claim open at its
// end but since settled or voided whatever its holds. "On hold" shows whether they have any hold.
// The quick-add bar adds a DRAFT claim of an amount, dated the sheet's today with an empty note, for
// the person of a name, adding the person with grade A first when the name is not on the sheet.
// The paste box takes lines of name,amount,date,note (the date blank for the sheet's today, the note
// optional and free to hold commas) and adds a DRAFT claim per line, in order, for the person of that
// name. Every line is held to the claim form's rules; if any is refused, nothing is added.
// Each person, and the sheet, shows how many of the claims that count are overdue and the sum of
// their figures (the overdue-total). An overdue claim's figure has its late charge (the sheet's late
// percent of it for every full 30 days overdue) added on top, in every figure and total.
// A person can name other people on the sheet as their patrons, one at a time from their row (not
// themselves, and not the same person twice). Each row shows the person's patrons by name, sorted,
// and their backing: the sum of their patrons' settled figures over their SETTLED claims dated within
// the 90 days up to and including the sheet's today (each at its frozen figure), 0.00 with none.
// A user can watch a person, and stop watching them, from their row; "Watched" shows whether the
// signed-in user watches them.
// The export button downloads everyone, retired people included, in list order, as a CSV file
// (name, grade in force today, retired-from date or nothing, due-total as shown).
// The sheet may have a cap (the one in force on its today): a person's due-total is at most it, and their excess is how far the
// sum of their figures is above it (0.00 otherwise; a due-total of n/a is not capped). Every total
// of due-totals (the sheet's, a grade's, the export) adds up the capped figures.
// A person is active when they have an OPEN or PENDING claim that counts dated within the 30 days up
// to and including the sheet's today; the summary counts the active people per grade, retired excluded.
interface Person {
  id: number;
  name: string;
  /** Every grade recorded for the person, the one from the beginning of time first. */
  grades: DatedGrade[];
  /** The version the store holds; an edit sends the version it was opened at. */
  version: number;
  retired: boolean;
  /** The date the person is retired from, or null. */
  retiredFrom: string | null;
  /** Whether the person has any hold. */
  held: boolean;
  /** The person's holds, by from-date. */
  holds: DatedHold[];
  /** The person's own terms in days, or null when they have none. */
  terms: number | null;
}

/** One of a person's holds: from its from-date to its to-date (null: open-ended), both inclusive. */
interface DatedHold {
  from: string;
  to: string | null;
}

/** Whether a day lies within one of the person's holds. */
function heldOn(p: Person, date: string): boolean {
  return p.holds.some((h) => h.from <= date && (h.to === null || date <= h.to));
}

/** Whether the person's retirement date is reached on a day (the day on or after it). */
function retiredOn(p: Person, date: string | null): boolean {
  return p.retiredFrom !== null && date !== null && date >= p.retiredFrom;
}

/**
 * Whether a claim of the person counts toward the totals: an OPEN or PENDING claim dated within one
 * of their holds does not, nor does one once the sheet's today reaches their retirement date, unless
 * a statement binds it; nor does a claim deferred on the sheet's today.
 */
function countsFor(p: Person | undefined, c: Claim, today: string | null): boolean {
  return counts(c) && !deferred(c, today)
    && (c.stated || !(p !== undefined && isOpen(c) && (heldOn(p, c.date) || retiredOn(p, today))));
}

/** One of a person's patrons. */
interface PatronLink {
  personId: number;
  patronId: number;
}

/** A person's statements: how many, and the last one's due-total (n/a when it had none) and date. */
interface StatementSummary {
  personId: number;
  count: number;
  dueTotal: string;
  date: string;
}

/** A statement just taken, with the person's claims as it left them. */
interface StatementTaken {
  summary: StatementSummary;
  claims: Claim[];
}

/** What a quick-add made: the claim, and its person (`created` when added for it). */
interface QuickAdded {
  person: Person;
  created: boolean;
  claim: Claim;
}

const GRADES = ['A', 'B', 'C'] as const;

/** A CSV field: wrapped in double quotes, with inner quotes doubled, when it holds a comma, quote or line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replace(/"/g, '""') + '"' : value;
}

/** The statuses the summary counts claims in, each shown even when no claim has it. */
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

@Component({
  selector: 'app-people',
  imports: [Header, PersonClaims, Periods, Rates, Roles, Settings, Summary],
  template: `
    <app-header
      [peopleCount]="people().length"
      [statuses]="statusSummary()"
      [dueTotal]="dueTotal()"
      [overdueTotal]="money(overdueTotals().cents)"
    />
    <app-rates [(rates)]="rates" />
    <app-roles />
    <app-settings [(floors)]="floors" [(latePcts)]="latePcts" [(policyDate)]="policyDate" [(caps)]="caps" />
    <app-periods [(periods)]="periods" [dues]="dues()" />
    <section aria-labelledby="people-heading">
      <h2 id="people-heading">People</h2>
      <form aria-label="Person" (submit)="save($event)">
        <label for="person-form-name">Name</label>
        <input
          id="person-form-name"
          data-testid="person-form-name"
          [value]="nameInput()"
          (input)="nameInput.set($any($event.target).value); error.set(null)"
        />
        <label for="person-form-grade">Grade</label>
        <select
          id="person-form-grade"
          data-testid="person-form-grade"
          [value]="gradeInput()"
          (change)="gradeInput.set($any($event.target).value); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === gradeInput()">{{ g }}</option>
          }
        </select>
        <label for="person-form-grade-from">Grade from</label>
        <input
          type="date"
          id="person-form-grade-from"
          data-testid="person-form-grade-from"
          [value]="gradeFromInput()"
          (input)="gradeFromInput.set($any($event.target).value); error.set(null)"
        />
        <label for="person-form-terms">Terms (days)</label>
        <input
          id="person-form-terms"
          data-testid="person-form-terms"
          inputmode="numeric"
          [value]="termsInput()"
          (input)="termsInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="person-form-submit">
          {{ editingId() === null ? 'Add person' : 'Save person' }}
        </button>
        @if (editingId() !== null) {
          <button type="button" data-testid="person-form-cancel" (click)="cancel()">Cancel</button>
        }
        @if (error(); as err) {
          <p role="alert" data-testid="person-form-error">{{ err }}</p>
        }
      </form>
      <form aria-label="Quick add" (submit)="quickAdd($event)">
        <label for="quick-name">Name</label>
        <input
          id="quick-name"
          data-testid="quick-name"
          [value]="quickName()"
          (input)="quickName.set($any($event.target).value); quickError.set(null)"
        />
        <label for="quick-amount">Amount</label>
        <input
          id="quick-amount"
          data-testid="quick-amount"
          inputmode="decimal"
          [value]="quickAmount()"
          (input)="quickAmount.set($any($event.target).value); quickError.set(null)"
        />
        <button type="submit" data-testid="quick-submit">Quick add</button>
        @if (quickError(); as err) {
          <p role="alert" data-testid="quick-error">{{ err }}</p>
        }
      </form>
      <form aria-label="Paste claims" (submit)="pasteClaims($event)">
        <label for="paste-claims">Paste claims (name,amount,date,note per line)</label>
        <textarea
          id="paste-claims"
          data-testid="paste-claims"
          rows="4"
          [value]="pasteText()"
          (input)="pasteText.set($any($event.target).value); pasteError.set(null)"
        ></textarea>
        <button type="submit" data-testid="paste-submit">Add pasted claims</button>
        @if (pasteAdded() !== null) {
          <p>Added <strong data-testid="paste-added">{{ pasteAdded() }}</strong></p>
        }
        @if (pasteError(); as err) {
          <p role="alert" data-testid="paste-error">{{ err }}</p>
        }
      </form>
      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="session.showRetired()"
          (change)="session.showRetired.set($any($event.target).checked)"
        />
        Show retired
      </label>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <button type="button" data-testid="bulk-open" (click)="bulkOpen()">Open selected claims</button>
      @if (bulkOpenResult(); as r) {
        <p>
          Opened <strong data-testid="bulk-opened">{{ r.opened }}</strong>,
          refused <strong data-testid="bulk-refused">{{ r.refused }}</strong>
        </p>
      }
      @if (bulkOpenError(); as err) {
        <p role="alert" data-testid="bulk-open-error">{{ err }}</p>
      }
      <button type="button" data-testid="bulk-settle" (click)="bulkSettle()">Settle selected claims</button>
      @if (bulkSettleResult(); as r) {
        <p>
          Settled <strong data-testid="bulk-settled">{{ r.settled }}</strong>,
          refused <strong data-testid="bulk-settle-refused">{{ r.refused }}</strong>
        </p>
      }
      @if (bulkSettleError(); as err) {
        <p role="alert" data-testid="bulk-settle-error">{{ err }}</p>
      }
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue claims <strong data-testid="rollup-overdue-count">{{ overdueTotals().count }}</strong></p>
      <p>Overdue in total <strong data-testid="rollup-overdue-total">{{ money(overdueTotals().cents) }}</strong></p>
      <app-summary [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()" />
      <table data-testid="people-list">
        <thead>
          <tr>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            <th scope="col">Terms</th>
            <th scope="col">Claimed</th>
            <th scope="col">Due</th>
            @for (pd of periods(); track pd.id) {
              <th scope="col">Due in {{ pd.name }}</th>
            }
            <th scope="col">Overdue</th>
            <th scope="col">Active</th>
            <th scope="col">Statements</th>
            <th scope="col">Retired</th>
            <th scope="col">On hold</th>
            <th scope="col">Patrons</th>
            <th scope="col">Watched</th>
            <th scope="col">Claims</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of listed(); track p.id) {
            <tr [attr.data-testid]="'person-row-' + p.id">
              <td data-testid="person-name">{{ p.name }}</td>
              <td data-testid="person-grade">{{ gradeToday(p) }}</td>
              <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              <td data-testid="person-claimed">{{ claimedOf(p.id) }}</td>
              <td data-testid="person-due-total">{{ dueText(dueOf(p)) }}</td>
              @for (pd of periods(); track pd.id) {
                <td [attr.data-testid]="'person-period-' + pd.name + '-due-total'">{{ periodDueOf(pd, p.id) }}</td>
              }
              <td>
                <span data-testid="person-overdue-count">{{ overdueOf(p).count }}</span>
                <span>totalling <span data-testid="person-overdue-total">{{ money(overdueOf(p).cents) }}</span></span>
              </td>
              <td data-testid="person-active">{{ isActive(p) ? 'yes' : 'no' }}</td>
              <td>
                <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
                <span>Last due <span data-testid="person-stated-total">{{ statementOf(p.id)?.dueTotal ?? '' }}</span></span>
                <span>on <span data-testid="person-statement-date">{{ statementOf(p.id)?.date ?? '' }}</span></span>
              </td>
              <td>
                <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
                <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
              </td>
              <td>
                <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                <ul>
                  @for (h of p.holds; track $index) {
                    <li data-testid="person-hold-line">{{ h.from }} to {{ h.to ?? 'open' }}</li>
                  }
                </ul>
              </td>
              <td>
                <span data-testid="person-patrons">{{ patronNamesOf(p.id) }}</span>
                <span>backing <span data-testid="person-backing">{{ money(backingOf(p.id)) }}</span></span>
                <span>excess <span data-testid="person-excess">{{ money(excessOf(p)) }}</span></span>
                <label [for]="'person-patron-select-' + p.id">Patron</label>
                <select
                  [id]="'person-patron-select-' + p.id"
                  data-testid="person-patron-select"
                  [value]="patronChoices().get(p.id) ?? ''"
                  (change)="setPatronChoice(p.id, $any($event.target).value)"
                >
                  <option value="" [selected]="!patronChoices().get(p.id)">Choose a person</option>
                  @for (o of people(); track o.id) {
                    <option [value]="'' + o.id" [selected]="patronChoices().get(p.id) === '' + o.id">{{ o.name }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="person-patron-add"
                  [attr.aria-label]="'Add patron for ' + p.name"
                  (click)="addPatron(p)"
                >
                  Add patron
                </button>
                @if (patronErrors().get(p.id); as err) {
                  <p role="alert" data-testid="person-patron-error">{{ err }}</p>
                }
              </td>
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
              <td>
                <app-person-claims
                  [personId]="p.id"
                  [personName]="p.name"
                  [claims]="claimsOf(p.id)"
                  [grades]="p.grades"
                  [rates]="rates()"
                  [floors]="floors()"
                  [latePcts]="latePcts()"
                  [policyDate]="policyDate()"
                  [periods]="periods()"
                  [selected]="selectedClaims()"
                  [people]="people()"
                  (saved)="claimSaved($event)"
                  (reassigned)="claimMoved($event)"
                  (selectedChange)="selectClaim($event.id, $event.on)"
                />
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
                <label [for]="'person-statement-asof-' + p.id">Statement as of</label>
                <input
                  type="date"
                  [id]="'person-statement-asof-' + p.id"
                  data-testid="person-statement-asof"
                  [value]="statementAsOfs().get(p.id) ?? ''"
                  (input)="setStatementAsOf(p.id, $any($event.target).value)"
                />
                <button
                  type="button"
                  data-testid="person-statement"
                  [attr.aria-label]="'Take statement for ' + p.name"
                  (click)="takeStatement(p)"
                >
                  Statement
                </button>
                <button
                  type="button"
                  data-testid="person-unstate"
                  [attr.aria-label]="'Cancel last statement for ' + p.name"
                  (click)="cancelStatement(p)"
                >
                  Cancel statement
                </button>
                <label [for]="'person-retire-date-' + p.id">Retire from</label>
                <input
                  type="date"
                  [id]="'person-retire-date-' + p.id"
                  data-testid="person-retire-date"
                  [value]="retireDates().get(p.id) ?? ''"
                  (input)="setRetireDate(p.id, $any($event.target).value)"
                />
                <button
                  type="button"
                  data-testid="person-retire"
                  [attr.aria-label]="'Retire ' + p.name"
                  (click)="retire(p)"
                >
                  Retire
                </button>
                <label [for]="'person-hold-from-' + p.id">Hold from</label>
                <input
                  type="date"
                  [id]="'person-hold-from-' + p.id"
                  data-testid="person-hold-from"
                  [value]="holdFroms().get(p.id) ?? ''"
                  (input)="setHoldFrom(p.id, $any($event.target).value)"
                />
                <label [for]="'person-hold-to-' + p.id">to</label>
                <input
                  type="date"
                  [id]="'person-hold-to-' + p.id"
                  data-testid="person-hold-to"
                  [value]="holdTos().get(p.id) ?? ''"
                  (input)="setHoldTo(p.id, $any($event.target).value)"
                />
                <button
                  type="button"
                  data-testid="person-hold-toggle"
                  [attr.aria-label]="'Hold ' + p.name"
                  (click)="hold(p)"
                >
                  Hold
                </button>
                @if (rowErrors().get(p.id); as err) {
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
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** The people on the list: everyone, or only those not retired unless show-retired is on. */
  protected readonly listed = computed(() =>
    this.session.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The retire-from date typed on each person's row, by person id. */
  protected readonly retireDates = signal<ReadonlyMap<number, string>>(new Map());
  /** The hold from- and to-dates typed on each person's row, by person id. */
  protected readonly holdFroms = signal<ReadonlyMap<number, string>>(new Map());
  /** The date each person's next statement is taken as of, by person id; blank for now. */
  protected readonly statementAsOfs = signal<ReadonlyMap<number, string>>(new Map());
  protected readonly holdTos = signal<ReadonlyMap<number, string>>(new Map());
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  /** The ids of the claims ticked for a bulk action. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** What the last bulk open did; cleared while the next one runs. */
  protected readonly bulkOpenResult = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal<string | null>(null);
  protected readonly bulkSettleResult = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal<string | null>(null);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  private readonly peopleById = computed(() => new Map(this.people().map((p) => [p.id, p])));
  protected readonly claimedTotal = computed(() =>
    money(this.claims().reduce((sum, c) => sum + this.countedCents(this.peopleById().get(c.personId), c), 0)),
  );
  protected readonly rates = signal<Rate[]>([]);
  protected readonly periods = signal<Period[]>([]);
  /** The sheet's floors, dated: the one in force on a claim's date is the least its live due can be. */
  protected readonly floors = signal<DatedValue[]>([]);
  /** The sheet's caps, dated (blank: none): the one in force on the sheet's today is the most a
   *  person's due-total can be. */
  protected readonly caps = signal<DatedValue[]>([]);
  private readonly capCents = computed(() => {
    const cap = valueOn(this.caps(), this.session.today(), '');
    return cap === '' ? null : cents(cap);
  });
  /** The sheet's late percents, dated: for each full 30 days a claim is overdue, the one in force on
   *  that boundary date (its due-by plus 30, 60, 90 ... days) is what it gains of its figure. */
  protected readonly latePcts = signal<DatedValue[]>([]);
  /** The sheet's policy date (blank: its today): a claim dated before it rounds half up, else half to even. */
  protected readonly policyDate = signal('');
  /** What works out an overdue claim's late charge on the sheet's today. */
  private readonly lateness = computed<Lateness>(() => ({
    latePcts: this.latePcts(),
    today: this.session.today(),
    policyDate: this.policyDate(),
  }));
  /** The date and figure of each claim that has a figure, and whether it counts by its status at
   *  the end of a day, for the periods' due-totals. */
  protected readonly dues = computed(() => {
    const out: DatedDue[] = [];
    const today = this.session.today();
    for (const c of this.claims()) {
      const p = this.peopleById().get(c.personId);
      if (p === undefined) continue;
      const due = chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floors(), this.lateness(), this.roundOn(c));
      if (due === null) continue;
      // a hold takes out only a claim that is open now as well
      const countsAt = (day: string): boolean => {
        const status = statusAt(c, day);
        return status !== null && (isOpen(c) ? countsFor(p, { ...c, status }, today)
          : countsFor(undefined, { ...c, status }, today));
      };
      out.push({ personId: c.personId, date: c.date, cents: due, countsAt });
    }
    return out;
  });
  /** Each person's statements, by person id. */
  protected readonly statements = signal<ReadonlyMap<number, StatementSummary>>(new Map());
  /** Each person's patrons, in the order they were named. */
  protected readonly patrons = signal<PatronLink[]>([]);
  private readonly patronIdsByPerson = computed(() => {
    const by = new Map<number, number[]>();
    for (const l of this.patrons()) by.set(l.personId, [...(by.get(l.personId) ?? []), l.patronId]);
    return by;
  });
  /** The patron chosen on each person's row, by person id (the patron's id as text). */
  protected readonly patronChoices = signal<ReadonlyMap<number, string>>(new Map());
  /** The error of each person's last refused patron add, by person id. */
  protected readonly patronErrors = signal<ReadonlyMap<number, string>>(new Map());
  /** The ids of the people the signed-in user watches. */
  protected readonly watched = signal<ReadonlySet<number>>(new Set());
  /** The error of each person's last refused row action, by person id. */
  protected readonly rowErrors = signal<ReadonlyMap<number, string>>(new Map());
  /** Each grade's rate in force on the sheet's today. */
  protected readonly pctOf = computed(() => {
    const today = this.session.today();
    return new Map<string, string | undefined>(GRADES.map((g) => [g, pctOn(this.rates(), g, today)]));
  });
  /** The sheet's due-total: people's due-totals added up (skipping n/a), trimmed. */
  protected readonly dueTotal = computed(() =>
    money(trimmedSum(this.people().map((p) => this.dueOf(p)).filter((d): d is number => d !== null))),
  );
  protected readonly dueText = dueText;
  protected readonly money = money;
  /** The sheet's overdue claims that count: how many, and the sum of people's figures in whole cents, trimmed. */
  protected readonly overdueTotals = computed(() => {
    const each = this.people().map((p) => this.overdueOf(p));
    return {
      count: each.reduce((sum, o) => sum + o.count, 0),
      cents: trimmedSum(each.map((o) => o.cents)),
    };
  });
  /** Per grade: the people not retired on it today, and their due-totals added up (skipping n/a). */
  protected readonly gradeSummary = computed<GradeSummary[]>(() => {
    const current = this.people().filter((p) => !p.retired);
    return GRADES.map((grade) => {
      const on = current.filter((p) => this.gradeToday(p) === grade);
      return {
        grade,
        count: on.length,
        dueTotal: money(on.reduce((sum, p) => sum + (this.dueOf(p) ?? 0), 0)),
        active: on.filter((p) => this.isActive(p)).length,
      };
    });
  });
  /** Per period: its due-total, as the periods list shows it. */
  protected readonly periodSummary = computed<PeriodSummary[]>(() =>
    this.periods().map((p) => ({ name: p.name, dueTotal: periodDueTotal(p, this.dues()) })),
  );
  /** A person's due-total for a period, as their row shows it. */
  protected periodDueOf(period: Period, personId: number): string {
    return personPeriodDueTotal(period, personId, this.dues());
  }
  /** Per status: how many of the sheet's claims have it, retired people's included. */
  protected readonly statusSummary = computed<StatusSummary[]>(() =>
    SUMMARY_STATUSES.map((status) => ({
      status,
      count: this.claims().filter((c) => c.status === status).length,
    })),
  );
  protected readonly nameInput = signal('');
  protected readonly gradeInput = signal<string>('A');
  /** The date the form's grade is recorded from; blank for the sheet's today. */
  protected readonly gradeFromInput = signal('');
  /** The person's own terms in days; blank for none. */
  protected readonly termsInput = signal('');
  protected readonly editingId = signal<number | null>(null);
  /** The version of the person the editor was filled from. */
  private editingVersion = 0;
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly error = signal<string | null>(null);
  protected readonly quickName = signal('');
  protected readonly quickAmount = signal('');
  protected readonly quickError = signal<string | null>(null);
  /** Whether a quick-add is waiting for its answer: a second one meanwhile is ignored. */
  private quickSubmitting = false;
  protected readonly pasteText = signal('');
  /** How many claims the last paste added; cleared while the next one runs. */
  protected readonly pasteAdded = signal<number | null>(null);
  protected readonly pasteError = signal<string | null>(null);
  /** Whether a paste is waiting for its answer: a second one meanwhile is ignored. */
  private pasteSubmitting = false;

  constructor() {
    // Re-read the sheet's people from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.selectedClaims.set(new Set());
      this.bulkOpenResult.set(null);
      this.bulkOpenError.set(null);
      this.bulkSettleResult.set(null);
      this.bulkSettleError.set(null);
      this.statements.set(new Map());
      this.rowErrors.set(new Map());
      this.patrons.set([]);
      this.patronChoices.set(new Map());
      this.patronErrors.set(new Map());
      this.watched.set(new Set());
      this.retireDates.set(new Map());
      this.holdFroms.set(new Map());
      this.statementAsOfs.set(new Map());
      this.holdTos.set(new Map());
      this.reset();
      this.quickName.set('');
      this.quickAmount.set('');
      this.quickError.set(null);
      this.pasteText.set('');
      this.pasteAdded.set(null);
      this.pasteError.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const [list, claims, statements, patrons, watched] = await Promise.all([
        firstValueFrom(this.http.post<Person[]>('/api/people/list', { sheet })),
        firstValueFrom(this.http.post<Claim[]>('/api/claims/list', { sheet })),
        firstValueFrom(this.http.post<StatementSummary[]>('/api/statements/list', { sheet })),
        firstValueFrom(this.http.post<PatronLink[]>('/api/people/patrons/list', { sheet })),
        firstValueFrom(this.http.post<number[]>('/api/people/watched', { sheet, user: this.session.user() })),
      ]);
      if (this.session.sheet() === sheet) {
        this.claims.set(claims);
        this.statements.set(new Map(statements.map((s) => [s.personId, s])));
        this.patrons.set(patrons);
        this.watched.set(new Set(watched));
        this.people.set(list);
      }
    } catch {
      this.error.set('Could not read the people on this sheet.');
    }
  }

  /** The person's grade in force on the sheet's today. */
  protected gradeToday(p: Person): string {
    return gradeOn(p.grades, this.session.today());
  }

  /** The date a claim's live figures round by (see {@link roundingDate}). */
  private roundOn(c: Claim): string {
    return roundingDate(c, this.claimsOf(c.personId));
  }

  protected claimsOf(personId: number): Claim[] {
    return this.claimsByPerson().get(personId) ?? [];
  }

  protected claimedOf(personId: number): string {
    const p = this.peopleById().get(personId);
    return money(this.claimsOf(personId).reduce((sum, c) => sum + this.countedCents(p, c), 0));
  }

  /** A claim's amount in whole cents when it counts toward the totals, else nothing. */
  private countedCents(p: Person | undefined, c: Claim): number {
    return countsFor(p, c, this.session.today()) ? cents(c.amount) : 0;
  }

  /** What the person owes, in whole cents: the sum of their figures held to the sheet's cap. */
  protected dueOf(p: Person): number | null {
    const sum = this.figuresOf(p);
    const cap = this.capCents();
    return sum === null || cap === null ? sum : Math.min(sum, cap);
  }

  /** How far the sum of the person's figures is above the cap, in whole cents; 0 otherwise. */
  protected excessOf(p: Person): number {
    const sum = this.figuresOf(p);
    const cap = this.capCents();
    return sum === null || cap === null ? 0 : Math.max(sum - cap, 0);
  }

  /**
   * The sum of the figures of the person's claims that count, in whole cents. Null when their
   * grade in force on the sheet's today has no rate in force then, unless a claim that counts has
   * a figure.
   */
  private figuresOf(p: Person): number | null {
    const figures = this.claimsOf(p.id)
      .filter((c) => countsFor(p, c, this.session.today()))
      .map((c) => chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floors(), this.lateness(), this.roundOn(c)));
    if (this.pctOf().get(this.gradeToday(p)) === undefined && figures.every((f) => f === null)) return null;
    return figures.reduce<number>((sum, f) => sum + (f ?? 0), 0);
  }

  /**
   * Whether the person is active: they have an OPEN or PENDING claim that counts dated within the
   * 30 days up to and including the sheet's today.
   */
  protected isActive(p: Person): boolean {
    const today = this.session.today();
    if (!today) return false;
    const since = plusDays(today, -29);
    return this.claimsOf(p.id).some(
      (c) => isOpen(c) && countsFor(p, c, today) && c.date >= since && c.date <= today,
    );
  }

  /** The person's overdue claims that count: how many, and the sum of their figures in whole cents. */
  protected overdueOf(p: Person): { count: number; cents: number } {
    const today = this.session.today();
    const late = this.claimsOf(p.id).filter((c) => countsFor(p, c, this.session.today()) && overdue(c, today));
    return {
      count: late.length,
      cents: late.reduce((sum, c) => sum + (chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floors(), this.lateness(), this.roundOn(c)) ?? 0), 0),
    };
  }

  protected statementOf(personId: number): StatementSummary | undefined {
    return this.statements().get(personId);
  }

  /** The person's patrons, by name, sorted, comma and space separated. */
  protected patronNamesOf(personId: number): string {
    return this.patronsOf(personId)
      .map((p) => p.name)
      .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
      .join(', ');
  }

  /**
   * The person's backing in whole cents: their patrons' frozen figures over their SETTLED claims
   * dated within the 90 days up to and including the sheet's today.
   */
  protected backingOf(personId: number): number {
    const today = this.session.today();
    if (!today) return 0;
    const since = plusDays(today, -89);
    return this.patronsOf(personId)
      .flatMap((p) => this.claimsOf(p.id))
      .filter((c) => c.status === 'SETTLED' && c.date >= since && c.date <= today)
      .reduce((sum, c) => sum + (c.settledDue === null ? 0 : cents(c.settledDue)), 0);
  }

  private patronsOf(personId: number): Person[] {
    const byId = this.peopleById();
    return (this.patronIdsByPerson().get(personId) ?? [])
      .map((id) => byId.get(id))
      .filter((p): p is Person => p !== undefined);
  }

  protected setPatronChoice(id: number, value: string): void {
    this.patronChoices.update((m) => new Map(m).set(id, value));
    this.clearRowError(id);
  }

  /** Name the person chosen on the row as one of the person's patrons. */
  protected async addPatron(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const patronId = Number(this.patronChoices().get(p.id) ?? '');
    const fail = (err: string) => this.patronErrors.update((m) => new Map(m).set(p.id, err));
    if (!patronId) {
      fail('Choose a person to add as a patron.');
      return;
    }
    if (patronId === p.id) {
      fail('A person cannot be their own patron.');
      return;
    }
    if ((this.patronIdsByPerson().get(p.id) ?? []).includes(patronId)) {
      fail('That person is already a patron of ' + p.name + '.');
      return;
    }
    try {
      const added = await firstValueFrom(
        this.http.post<PatronLink>('/api/people/patrons/add', { sheet, user: this.session.user(), id: p.id, patronId }),
      );
      if (this.session.sheet() !== sheet) return;
      this.patrons.update((list) => [...list, added]);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      fail(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That person cannot be added as a patron of ' + p.name + '.'
          : 'Could not add a patron for ' + p.name + '.',
      );
    }
  }

  private clearRowError(id: number): void {
    this.patronErrors.update((m) => {
      if (!m.has(id)) return m;
      const next = new Map(m);
      next.delete(id);
      return next;
    });
    this.rowErrors.update((m) => {
      if (!m.has(id)) return m;
      const next = new Map(m);
      next.delete(id);
      return next;
    });
  }

  protected setRetireDate(id: number, date: string): void {
    this.retireDates.update((m) => new Map(m).set(id, date));
    this.clearRowError(id);
  }

  /** Retire the person from the date on their row, or from the sheet's today when it is blank. */
  protected async retire(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const date = (this.retireDates().get(p.id) ?? '').trim();
    if (date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Enter a date, or leave it blank for the sheet\'s today.'));
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>('/api/people/retire', { sheet, user: this.session.user(), id: p.id, date: date || null }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editingId() === saved.id && this.editingVersion === p.version) this.editingVersion = saved.version;
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Could not retire ' + p.name + '.'));
    }
  }

  protected setHoldFrom(id: number, date: string): void {
    this.holdFroms.update((m) => new Map(m).set(id, date));
    this.clearRowError(id);
  }

  protected setHoldTo(id: number, date: string): void {
    this.holdTos.update((m) => new Map(m).set(id, date));
    this.clearRowError(id);
  }

  /** Put the person on hold from the from-date on their row (the sheet's today when blank) to its to-date, if any. */
  protected async hold(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const from = (this.holdFroms().get(p.id) ?? '').trim();
    const to = (this.holdTos().get(p.id) ?? '').trim();
    const isDate = (d: string) => d === '' || /^\d{4}-\d{2}-\d{2}$/.test(d);
    if (!isDate(from) || !isDate(to)) {
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Enter dates, or leave the from-date blank for the sheet\'s today.'));
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>('/api/people/hold', {
          sheet,
          user: this.session.user(),
          id: p.id,
          from: from || null,
          to: to || null,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editingId() === saved.id && this.editingVersion === p.version) this.editingVersion = saved.version;
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Could not hold ' + p.name + '.'));
    }
  }

  /** Start watching the person as the signed-in user, or stop when they already watch them. */
  protected async toggleWatch(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const on = !this.watched().has(p.id);
    try {
      await firstValueFrom(this.http.post<void>('/api/people/watch', { sheet, user: this.session.user(), id: p.id, watch: on }));
      if (this.session.sheet() !== sheet) return;
      this.watched.update((s) => {
        const next = new Set(s);
        if (on) next.add(p.id);
        else next.delete(p.id);
        return next;
      });
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Could not ' + (on ? 'watch ' : 'stop watching ') + p.name + '.'));
    }
  }

  protected setStatementAsOf(id: number, date: string): void {
    this.statementAsOfs.update((m) => new Map(m).set(id, date));
    this.clearRowError(id);
  }

  /**
   * Take the person's statement, now or as of the date on their row: their claims come back bound
   * (or unbound) as it left them, and the date is cleared.
   */
  protected async takeStatement(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const asOf = (this.statementAsOfs().get(p.id) ?? '').trim();
    if (asOf !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(asOf)) {
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Enter a date, or leave it blank for a statement now.'));
      return;
    }
    try {
      const taken = await firstValueFrom(
        this.http.post<StatementTaken>('/api/statements/take', {
          sheet,
          user: this.session.user(),
          personId: p.id,
          asOf: asOf || null,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.statementAsOfs.update((m) => new Map(m).set(p.id, ''));
      const byId = new Map(taken.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      this.statements.update((m) => new Map(m).set(p.id, taken.summary));
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Could not take a statement for ' + p.name + '.'));
    }
  }

  /** Cancel the person's last statement: their claims come back as it left them, the row shows the one before. */
  protected async cancelStatement(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    try {
      const cancelled = await firstValueFrom(
        this.http.post<StatementTaken>('/api/statements/cancel', { sheet, user: this.session.user(), personId: p.id }),
      );
      if (this.session.sheet() !== sheet) return;
      const byId = new Map(cancelled.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      this.statements.update((m) => {
        const next = new Map(m);
        if (cancelled.summary.count === 0) {
          next.delete(p.id);
        } else {
          next.set(p.id, cancelled.summary);
        }
        return next;
      });
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Could not cancel the statement for ' + p.name + '.'));
    }
  }

  /** A claim added (listed first, newest first) or edited (replaced where it is). */
  protected claimSaved(claim: Claim): void {
    this.claims.update((list) =>
      list.some((c) => c.id === claim.id) ? list.map((c) => (c.id === claim.id ? claim : c)) : [claim, ...list],
    );
  }

  /** A claim moved to another person: listed first, as their newest claim. */
  protected claimMoved(claim: Claim): void {
    this.claims.update((list) => [claim, ...list.filter((c) => c.id !== claim.id)]);
  }

  protected selectClaim(id: number, on: boolean): void {
    this.selectedClaims.update((ids) => {
      const next = new Set(ids);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  /** Open the selected claims together: the server opens and numbers each it can, in list order,
   *  and refuses the rest, leaving them as they are. */
  protected async bulkOpen(): Promise<void> {
    this.bulkOpenResult.set(null);
    this.bulkOpenError.set(null);
    const sheet = this.session.sheet();
    const ids = [...this.selectedClaims()];
    try {
      const done = await firstValueFrom(
        this.http.post<{ opened: number; refused: number; claims: Claim[] }>('/api/claims/bulk-open', {
          sheet,
          user: this.session.user(),
          ids,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      const byId = new Map(done.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      this.selectedClaims.set(new Set());
      this.bulkOpenResult.set({ opened: done.opened, refused: done.refused });
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkOpenError.set('Could not open the selected claims.');
    }
  }

  /** Settle the selected claims together: the server settles each it can, in list order, by the
   *  rules of settling one (a clerk's above the limit goes PENDING), and refuses the rest. */
  protected async bulkSettle(): Promise<void> {
    this.bulkSettleResult.set(null);
    this.bulkSettleError.set(null);
    const sheet = this.session.sheet();
    const ids = [...this.selectedClaims()];
    try {
      const done = await firstValueFrom(
        this.http.post<{ settled: number; refused: number; claims: Claim[] }>('/api/claims/bulk-settle', {
          sheet,
          user: this.session.user(),
          ids,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      const byId = new Map(done.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      this.selectedClaims.set(new Set());
      this.bulkSettleResult.set({ settled: done.settled, refused: done.refused });
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkSettleError.set('Could not settle the selected claims.');
    }
  }

  /** Quick-add a claim by name; the person is added with it when the name is new to the sheet. */
  protected async quickAdd(event: Event): Promise<void> {
    event.preventDefault();
    if (this.quickSubmitting) return;
    this.quickError.set(null);
    const sheet = this.session.sheet();
    const name = this.quickName().trim();
    const amount = this.quickAmount().trim();
    if (!name) {
      this.quickError.set('Enter a name.');
      return;
    }
    if (!AMOUNT.test(amount)) {
      this.quickError.set('Enter an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    this.quickSubmitting = true;
    try {
      const added = await firstValueFrom(
        this.http.post<QuickAdded>('/api/quick/add', { sheet, user: this.session.user(), name, amount }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => (list.some((p) => p.id === added.person.id) ? list : [...list, added.person]));
      this.claimSaved(added.claim);
      this.quickName.set('');
      this.quickAmount.set('');
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.quickError.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That name is already in use on this sheet.'
          : 'Could not quick-add this claim.',
      );
    } finally {
      this.quickSubmitting = false;
    }
  }

  /** Add a DRAFT claim per pasted line, in order; the server refuses the whole paste if any line fails. */
  protected async pasteClaims(event: Event): Promise<void> {
    event.preventDefault();
    if (this.pasteSubmitting) return;
    this.pasteAdded.set(null);
    this.pasteError.set(null);
    const sheet = this.session.sheet();
    const text = this.pasteText();
    if (!text.trim()) {
      this.pasteError.set('Paste at least one line of name,amount,date,note.');
      return;
    }
    this.pasteSubmitting = true;
    try {
      const done = await firstValueFrom(
        this.http.post<{ added: number; claims: Claim[] }>('/api/claims/paste', {
          sheet,
          user: this.session.user(),
          text,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      for (const c of done.claims) this.claimSaved(c);
      this.pasteText.set('');
      this.pasteAdded.set(done.added);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.pasteError.set('Could not add the pasted claims: every line needs a name on the sheet and a claim the claim form takes.');
    } finally {
      this.pasteSubmitting = false;
    }
  }

  /** Download every person, retired included, in list order, as a CSV file. */
  protected exportCsv(): void {
    const lines = ['name,grade,retired-from,due-total'];
    for (const p of this.people()) {
      lines.push([p.name, this.gradeToday(p), p.retiredFrom ?? '', dueText(this.dueOf(p))].map(csvField).join(','));
    }
    const url = URL.createObjectURL(new Blob([lines.map((l) => l + '\n').join('')], { type: 'text/csv' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'people-' + (this.session.sheet() ?? 'sheet') + '.csv';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  protected edit(p: Person): void {
    this.editingId.set(p.id);
    this.editingVersion = p.version;
    this.nameInput.set(p.name);
    this.gradeInput.set(this.gradeToday(p));
    this.gradeFromInput.set('');
    this.termsInput.set(p.terms === null ? '' : String(p.terms));
    this.error.set(null);
  }

  protected cancel(): void {
    this.reset();
  }

  private reset(): void {
    this.editingId.set(null);
    this.nameInput.set('');
    this.gradeInput.set('A');
    this.gradeFromInput.set('');
    this.termsInput.set('');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const name = this.nameInput().trim();
    const grade = this.gradeInput();
    const gradeFrom = this.gradeFromInput().trim();
    const terms = this.termsInput().trim();
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!GRADES.includes(grade as (typeof GRADES)[number])) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    if (gradeFrom !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(gradeFrom)) {
      this.error.set('Enter a date the grade is from, or leave it blank for the sheet\'s today.');
      return;
    }
    if (terms !== '' && !/^\d{1,6}$/.test(terms)) {
      this.refuse('Enter the terms as a whole number of days, or leave them blank for none.');
      return;
    }
    const id = this.editingId();
    const body = { sheet, user: this.session.user(), name, grade, terms: terms === '' ? null : Number(terms) };
    this.submitting = true;
    try {
      if (id === null) {
        const added = await firstValueFrom(this.http.post<Person>('/api/people/add', body));
        if (this.session.sheet() !== sheet) return;
        this.people.update((list) => [...list, added]);
      } else {
        const saved = await firstValueFrom(
          this.http.post<Person>('/api/people/update', { ...body, gradeFrom: gradeFrom || null, id, version: this.editingVersion }),
        );
        if (this.session.sheet() !== sheet) return;
        this.people.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
      }
      this.reset();
    } catch (e) {
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        await this.showCurrent(sheet, id);
        this.error.set('Someone else has saved this person since you opened it; the form now shows their values.');
      } else if (e instanceof HttpErrorResponse && e.status === 409) {
        this.refuse('That name is already in use on this sheet.');
      } else {
        this.refuse(id === null ? 'Could not add this person.' : 'Could not save this person.');
      }
    } finally {
      this.submitting = false;
    }
  }

  /**
   * Show why a save was refused. A refused edit saves nothing and closes the editor, so the form is
   * ready to add a person again; a refused add keeps what was typed.
   */
  private refuse(message: string): void {
    if (this.editingId() !== null) this.reset();
    this.error.set(message);
  }

  /** Re-read the sheet's people and refill the open editor with the person as the store holds them now. */
  private async showCurrent(sheet: string | null, id: number): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.post<Person[]>('/api/people/list', { sheet }));
      if (this.session.sheet() !== sheet) return;
      this.people.set(list);
      const current = list.find((p) => p.id === id);
      if (current && this.editingId() === id) {
        this.editingVersion = current.version;
        this.nameInput.set(current.name);
        this.gradeInput.set(this.gradeToday(current));
        this.termsInput.set(current.terms === null ? '' : String(current.terms));
      }
    } catch {
      // the refusal is shown either way
    }
  }
}

/**
 * A sheet total of people's figures: with three or more, the single highest and the single lowest
 * are left out.
 */
function trimmedSum(figures: readonly number[]): number {
  const sum = figures.reduce((s, f) => s + f, 0);
  return figures.length < 3 ? sum : sum - Math.max(...figures) - Math.min(...figures);
}
