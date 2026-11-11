import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { PEOPLE_COLUMNS, PeopleColumn, Session } from '../../core/session';
import { AMOUNT, Claim, PersonClaims, cents, counts, dayNumber, deferred, isCorrection, isOpen, money, overdue, plusDays, reviewLate, roundingDate, statusAt } from '../claims/claims';
import { AuditView } from '../audit/audit-view';
import { Activity } from '../audit/activity';
import { TreeSearch } from '../search/tree-search';
import { Holidays } from '../holidays/holidays';
import { DatedDue, Period, Periods, classicPeriodDueTotal, periodDueTotal, periodOn, personPeriodCarried, personPeriodDueTotal } from '../periods/periods';
import { DatedGrade, DatedValue, Lateness, Rate, Rates, chargedDueCents, dueCents, dueText, graceOn, gradeOn, pctOn, valueOn } from '../rates/rates';
import { Roles } from '../roles/roles';
import { Settings } from '../settings/settings';
import { Header } from '../header/header';
import { Dashboard } from '../dashboard/dashboard';
import { ClassicPeriodSummary, ClassicSummary, GradeSummary, PeriodSummary, StatusSummary, Summary, VoidReasonSummary } from '../summary/summary';
import { VoidReasons } from '../void-reasons/void-reasons';
import { Templates } from '../templates/templates';
import { Notes } from '../notes/notes';
import { Pool, Pools } from '../pools/pools';
import { Crew, CrewPerson, Crews, crewDueCents } from '../crews/crews';
import { PersonPlans, Plan } from '../plans/plans';
import { VoidReasons as VoidReasonList } from '../../core/void-reasons';
import { FigurePipe } from '../../core/figure';

// The people on the active sheet, in list order (each person's position: new people go to the end,
// and a row's up and down buttons swap them with the person above or below), and the one form that both adds
// a person and edits an existing one (opening a person's editor fills the form with their values).
// A person's grade is dated: their first applies from the beginning of time, and each save records
// the form's grade from its from-date (the sheet's today when blank), keeping those recorded before;
// a save leaving the grade as the row shows it, with the from-date blank, records none. The row
// shows the grade in force on the sheet's today; each claim uses the one in force on its date.
// A person may have their own terms, a whole number of days (blank for none). A save with a terms
// from-date records them from that date, keeping those recorded before; with it blank, from the
// beginning of time. The row shows the terms in force on the sheet's today; a claim's due-by is fixed
// by the server when it is created, from those in force on the sheet's today then before the grade's or sheet's.
// A person may have a contact (blank for none), stored trimmed and lower-cased and shown on the row;
// a contact another person on the sheet already has is refused.
// A person may carry tags, typed comma separated: each trimmed and lower-cased, repeats dropped,
// shown sorted. A claim counts in a tag's total when the claim or its person carries the tag, once.
// A person may have aliases, added one at a time from their row and shown sorted as names are compared
// (without regard to case), comma and space separated. An alias shares the names' uniqueness: one equal
// to any name or alias on the sheet is refused, as is a name equal to an alias. The quick-add bar and
// the paste box find a person by an alias as well as by the name.
// Nor may a name or alias be used when another sheet of the sheet's tree (its ancestors, and its
// descendants and theirs) uses it as a name or alias. A person who already clashes so shows clashing
// yes, and every edit of them is refused until it renames them to a name that does not clash.
// The editor remembers the version of the person it opened; if someone else has saved the person
// since, the save is refused and the form is refilled with the values the store holds now.
// Each row shows what the person has claimed (leaving out corrections) and lists their claims; the
// sheet's claimed-total adds everyone's up. Only OPEN, PENDING and SETTLED claims count toward any of these totals. Each claim shows
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
// The export-statement button downloads a person's statement as a CSV file: number, date, amount,
// figure and status of each claim of theirs bound by their last statement, oldest first, the figure as the claim shows it.
// Each claim shows the period its due-by falls in, and each period the due of the claims that counted in it
// at its end (by the status each had then, as its log records it).
// A person can be retired from a date (the sheet's today when left blank); with a date set they count
// as retired at once, whatever the date. No claim of theirs can be dated after it. Retired people are
// left out of the list unless "show retired" is on. Once the sheet's today reaches their retirement
// date their OPEN and PENDING claims leave every live total (unless a statement binds them).
// A retired person can be unretired (on the sheet's today; anyone not retired is refused). Each past
// retirement stays on the row as from-date to the day unretired; no claim can be dated after the one and before the other.
// A person can be put on hold from a date (the sheet's today when left blank) to an optional to-date,
// and may have several holds over time; a hold ends on its to-date. A hold takes none of their claims out
// of any total: while one is in force on the sheet's today they only take no cut of a pool.
// "On hold" shows whether they have any hold.
// The quick-add bar adds a DRAFT claim of an amount, dated the sheet's today with an empty note, for
// the person of a name, adding the person with grade A first when the name is not on the sheet.
// The paste box takes lines of name,amount,date,note (the date blank for the sheet's today, the note
// optional and free to hold commas) and adds a claim per line, in order, for the person of that
// name, each then opened as the open button would (so it arrives OPEN). A line matching one of the
// person's claims by date and note updates the newest matching DRAFT to its amount and opens it
// instead. Every line is held to the claim form's and the open button's rules; if any is refused,
// nothing changes. The count shows how many lines went through. A note
// ending in " #<reference>" gives the claim that reference (not part of the note); a reference
// repeated within the paste or already used on the sheet refuses the whole paste.
// Each person, and the sheet, shows how many of the claims that count are overdue and the sum of
// their figures (the overdue-total). An overdue claim's figure has its late charge (the sheet's late
// percent of it for every full 30 days overdue) added on top, in every figure and total.
// A person can name other people on the sheet as their patrons, one at a time from their row (not
// themselves, and not the same person twice). The patrons carry shares: 100.00 split equally among
// them in the order they were named, each rounded half to even to two decimals, the remainder going
// to the first-named. Each row shows the person's patrons as "<name> (<grade>) <share>", sorted by name, and
// their backing: the sum over the patrons of the patron's figure (their settled figures over their
// SETTLED claims dated within the 90 days up to and including the sheet's today, each at its frozen
// figure) times their share divided by 100, rounded half to even; 0.00 with none.
// A user can watch a person, and stop watching them, from their row; "Watched" shows whether the
// signed-in user watches them.
// The export button downloads everyone, retired people included, in list order, as a CSV file
// (name, grade in force today, retired-from date or nothing, due-total as shown). It and the
// export-claims button first wait for the screen's reads to be answered, so an export taken while
// an as-of date is set has the as-of figures (the pools export reads the pools afresh as of it).
// The export-claims button downloads every claim as a CSV file the same way: people in list order,
// retired included, each person's claims oldest first, with person, number, date, amount, due,
// status and period as the claim's row shows them (an empty number or period, or n/a, as it is).
// The sheet may have a cap (the one in force on its today): a person's due-total is at most it, and their excess is how far the
// sum of their figures is above it (0.00 otherwise; a due-total of n/a is not capped). Every total
// of due-totals (the sheet's, a grade's, the export) adds up the capped figures.
// People can be ticked and given a grade together from a from-date (the sheet's today when blank):
// each, in list order, is edited as the person form would, all or none; the count shows how many.
// A person is active when they have an OPEN or PENDING claim that counts dated within the 30 days up
// to and including the sheet's today; the summary counts the active people per grade, retired excluded.
// Each person shows the figures of their OPEN and PENDING claims that count split by age on the
// sheet's today (the days from the claim's date): 0 to 30, 31 to 60 and 61 or more; a claim dated
// after today is 0 to 30.
// Each person shows their settled-total (the figures of their SETTLED claims that count, at their
// frozen figures, corrections included) and their open-total (the figures of their OPEN and PENDING
// claims that count); together they make the sum of their figures before the cap. The sheet's
// settled-total and open-total add up everyone's, untrimmed.
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
  /** The person's contact, trimmed and lower-cased, or null when they have none. */
  contact: string | null;
  /** The person's tags: trimmed, lower-cased, without repeats and sorted; empty when they have none. */
  tags: string[];
  /** The person's aliases, sorted as names are compared. */
  aliases: string[];
  /** Whether the person's name or an alias is used on another sheet of the sheet's tree. */
  clashing: boolean;
  /** The sheet the person was transferred from, or null when they were not transferred in. */
  origin: string | null;
  /** The person's past retirements, each from its from-date to the day they were unretired. */
  retirements: PastRetirement[];
  /** The person's snapshot lines, oldest first: the day being left when today moved, and their due-total then. */
  snapshots: SnapshotLine[];
  /** The reason of the person's flag while it is in force on the sheet's today, or null. */
  flag: string | null;
  /** The user who may approve or reject the person's PENDING claims as a steward may, or null. */
  approver: string | null;
}

/** One of a person's snapshot lines: the day being left and their due-total then ('n/a' when none). */
interface SnapshotLine {
  day: string;
  dueTotal: string;
}

/** One of a person's past retirements: from its from-date to the day they were unretired. */
interface PastRetirement {
  from: string;
  to: string;
}

/** One of a person's holds: from its from-date to its to-date (null: open-ended), both inclusive. */
/** The figures of a sheet's descendants added up: each sheet's as that sheet shows it. */
interface Descendants {
  peopleCount: number;
  dueTotal: string;
  overdueTotal: string;
  /** The sheet's own figures as the store holds them (as of the as-of date, when one is set). */
  own: { peopleCount: number; dueTotal: string; overdueTotal: string } | null;
}

const NO_DESCENDANTS: Descendants = { peopleCount: 0, dueTotal: '0.00', overdueTotal: '0.00', own: null };

interface DatedHold {
  from: string;
  to: string | null;
}

/** Whether the person's retirement date is reached on a day (the day on or after it). */
function retiredOn(p: Person, date: string | null): boolean {
  return p.retiredFrom !== null && date !== null && date >= p.retiredFrom;
}

/**
 * Whether a claim of the person counts toward the totals: an OPEN or PENDING claim does not once the sheet's today
 * reaches their retirement date, unless a statement binds it; nor does a claim deferred on the sheet's today. A hold
 * (the person's or the claim's) takes a claim out of no total; it only takes the person out of the pools' cuts.
 */
function countsFor(p: Person | undefined, c: Claim, today: string | null): boolean {
  return counts(c) && !deferred(c, today)
    && (c.stated || !(isOpen(c) && p !== undefined && retiredOn(p, today)));
}

/** One of a person's patrons. */
interface PatronLink {
  personId: number;
  patronId: number;
}

/**
 * A person's statements: how many, the last one's due-total (n/a when it had none) and date, and a
 * line for each one still standing, oldest first.
 */
interface StatementSummary {
  personId: number;
  count: number;
  dueTotal: string;
  date: string;
  lines: StatementLine[];
}

/** One of a person's statements still standing: its date and due-total ('n/a' when it had none). */
interface StatementLine {
  date: string;
  dueTotal: string;
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
const SUMMARY_STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'REVIEW'] as const;

@Component({
  selector: 'app-people',
  imports: [FigurePipe, AuditView, Activity, Header, Dashboard, Holidays, PersonClaims, PersonPlans, Periods, Rates, Roles, Pools, Crews, Settings, Summary, ClassicSummary, Templates, Notes, TreeSearch, VoidReasons],
  template: `
    <app-header
      [peopleCount]="people().length"
      [statuses]="statusSummary()"
      [dueTotal]="dueTotal()"
      [overdueTotal]="money(overdueTotals().cents)"
      [consolidatedPeopleCount]="consolidated().peopleCount"
      [consolidatedDueTotal]="consolidated().dueTotal"
      [consolidatedOverdueTotal]="consolidated().overdueTotal"
      [myDueTotal]="myFigures()?.dueTotal ?? ''"
      [myOverdueTotal]="myFigures()?.overdueTotal ?? ''"
      [myCrewDueTotal]="myCrewDueTotal()"
    />
    <app-dashboard
      [pendingCount]="statusCount('PENDING')"
      [reviewCount]="statusCount('REVIEW')"
      [overdueCount]="overdueTotals().count"
    />
    <app-rates [(rates)]="rates" />
    <app-roles />
    <app-settings [(floors)]="floors" [(latePcts)]="latePcts" [(graces)]="graces" [(policyDate)]="policyDate" [(roundings)]="roundings" [(caps)]="caps" [(pot)]="pot" />
    <app-periods [(periods)]="periods" [dues]="dues()" />
    <app-holidays />
    <app-void-reasons />
    <app-templates />
    <app-notes />
    <app-pools [people]="people()" [pot]="pot()" [crews]="crews()" [periods]="periods()" [(pools)]="pools" />
    <app-crews [people]="crewPeople()" [(crews)]="crews" />
    <app-audit-view />
    <app-activity />
    <app-tree-search />
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
        <label for="person-form-terms-from">Terms from</label>
        <input
          type="date"
          id="person-form-terms-from"
          data-testid="person-form-terms-from"
          [value]="termsFromInput()"
          (input)="termsFromInput.set($any($event.target).value); error.set(null)"
        />
        <label for="person-form-contact">Contact</label>
        <input
          id="person-form-contact"
          data-testid="person-form-contact"
          [value]="contactInput()"
          (input)="contactInput.set($any($event.target).value); error.set(null)"
        />
        <label for="person-form-tags">Tags (comma separated)</label>
        <input
          id="person-form-tags"
          data-testid="person-form-tags"
          [value]="tagsInput()"
          (input)="tagsInput.set($any($event.target).value); error.set(null)"
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
      <form aria-label="Import people" (submit)="importPeople($event)">
        <label for="import-people">Import people, one name,grade,terms,contact per line</label>
        <textarea
          id="import-people"
          data-testid="import-people"
          rows="4"
          [value]="importText()"
          (input)="importText.set($any($event.target).value); importError.set(null)"
        ></textarea>
        <button type="submit" data-testid="import-submit">Import</button>
        @if (importAdded() !== null) {
          <p>Added <strong data-testid="import-added">{{ importAdded() }}</strong></p>
        }
        @if (importUpdated() !== null) {
          <p>Updated <strong data-testid="import-updated">{{ importUpdated() }}</strong></p>
        }
        @if (importError(); as err) {
          <p role="alert" data-testid="import-error">{{ err }}</p>
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
      <button type="button" data-testid="plan-generate" (click)="generatePlans()">Generate plan claims</button>
      @if (planGenerated() !== null) {
        <p>Made <strong data-testid="plan-generated-count">{{ planGenerated() }}</strong></p>
      }
      @if (planGenerateError(); as err) {
        <p role="alert" data-testid="plan-generate-error">{{ err }}</p>
      }
      <label>
        <input
          type="checkbox"
          data-testid="show-retired"
          [checked]="session.showRetired()"
          (change)="session.showRetired.set($any($event.target).checked)"
        />
        Show retired
      </label>
      <fieldset>
        <legend>Columns</legend>
        @for (c of columns; track c) {
          <label>
            <input
              type="checkbox"
              [attr.data-testid]="'column-' + c"
              [checked]="!session.hidden().has(c)"
              (change)="session.showColumn(c, $any($event.target).checked)"
            />
            {{ c }}
          </label>
        }
      </fieldset>
      <label>
        Sort by
        <select
          data-testid="sort-by"
          [value]="session.sortBy()"
          (change)="session.sortBy.set($any($event.target).value)"
        >
          <option value="added">added</option>
          <option value="name">name</option>
          <option value="grade">grade</option>
          <option value="due-total">due-total</option>
        </select>
      </label>
      <button type="button" data-testid="export-people" (click)="exportCsv()">Export people (CSV)</button>
      <button type="button" data-testid="export-claims" (click)="exportClaims()">Export claims (CSV)</button>
      <button type="button" data-testid="dups-show" (click)="dupsShown.set(true)">Show duplicate claims</button>
      @if (dupsShown()) {
        <ul aria-label="Duplicate claims">
          @for (d of dups(); track d) {
            <li data-testid="dup-line">{{ d }}</li>
          }
        </ul>
      }
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
      <span>Last batch <strong data-testid="bulk-settle-batch">{{ session.lastBatch() ?? '' }}</strong></span>
      @if (bulkSettleResult(); as r) {
        <p>
          Settled <strong data-testid="bulk-settled">{{ r.settled }}</strong>,
          refused <strong data-testid="bulk-settle-refused">{{ r.refused }}</strong>
        </p>
      }
      @if (bulkSettleError(); as err) {
        <p role="alert" data-testid="bulk-settle-error">{{ err }}</p>
      }
      <label for="bulk-reassign-to">Move selected claims to</label>
      <select
        id="bulk-reassign-to"
        data-testid="bulk-reassign-to"
        (change)="bulkReassignTo.set($any($event.target).value); bulkReassignError.set(null)"
      >
        <option value="" [selected]="bulkReassignTo() === ''">Choose a person</option>
        @for (p of people(); track p.id) {
          <option [value]="p.id" [selected]="bulkReassignTo() === '' + p.id">{{ p.name }}</option>
        }
      </select>
      <button type="button" data-testid="bulk-reassign" (click)="bulkReassign()">Move selected claims</button>
      @if (bulkReassignResult(); as r) {
        <p>
          Moved <strong data-testid="bulk-reassigned">{{ r.reassigned }}</strong>,
          refused <strong data-testid="bulk-reassign-refused">{{ r.refused }}</strong>
        </p>
      }
      @if (bulkReassignError(); as err) {
        <p role="alert" data-testid="bulk-reassign-error">{{ err }}</p>
      }
      <label for="bulk-redate-date">Re-date selected claims to</label>
      <input
        type="date"
        id="bulk-redate-date"
        data-testid="bulk-redate-date"
        [value]="bulkRedateDate()"
        (input)="bulkRedateDate.set($any($event.target).value); bulkRedateError.set(null)"
      />
      <button type="button" data-testid="bulk-redate" (click)="bulkRedate()">Re-date selected claims</button>
      @if (bulkRedateResult(); as r) {
        <p>
          Re-dated <strong data-testid="bulk-redated">{{ r.redated }}</strong>,
          refused <strong data-testid="bulk-redate-refused">{{ r.refused }}</strong>
        </p>
      }
      @if (bulkRedateError(); as err) {
        <p role="alert" data-testid="bulk-redate-error">{{ err }}</p>
      }
      @if (voidReasons.list().length > 0) {
        <label for="bulk-void-reason">Void reason</label>
        <select
          id="bulk-void-reason"
          data-testid="bulk-void-reason"
          (change)="bulkVoidReason.set($any($event.target).value); bulkVoidError.set(null)"
        >
          <option value="" [selected]="bulkVoidReason() === ''">(choose)</option>
          @for (r of voidReasons.list(); track r.id) {
            <option [value]="r.reason" [selected]="bulkVoidReason() === r.reason">{{ r.reason }}</option>
          }
        </select>
      }
      <button type="button" data-testid="bulk-void" (click)="bulkVoid()">Void selected claims</button>
      @if (bulkVoidResult(); as r) {
        <p>
          Voided <strong data-testid="bulk-voided">{{ r.voided }}</strong>,
          refused <strong data-testid="bulk-void-refused">{{ r.refused }}</strong>
        </p>
      }
      @if (bulkVoidError(); as err) {
        <p role="alert" data-testid="bulk-void-error">{{ err }}</p>
      }
      <form aria-label="Grade selected people" (submit)="bulkGrade($event)">
        <label for="bulk-grade">Grade selected people</label>
        <select
          id="bulk-grade"
          data-testid="bulk-grade"
          [value]="bulkGradeInput()"
          (change)="bulkGradeInput.set($any($event.target).value); bulkGradeError.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === bulkGradeInput()">{{ g }}</option>
          }
        </select>
        <label for="bulk-grade-from">from</label>
        <input
          type="date"
          id="bulk-grade-from"
          data-testid="bulk-grade-from"
          [value]="bulkGradeFrom()"
          (input)="bulkGradeFrom.set($any($event.target).value); bulkGradeError.set(null)"
        />
        <button type="submit" data-testid="bulk-grade-apply">Give grade</button>
        @if (bulkGraded() !== null) {
          <p>Changed <strong data-testid="bulk-graded">{{ bulkGraded() }}</strong></p>
        }
        @if (bulkGradeError(); as err) {
          <p role="alert" data-testid="person-error">{{ err }}</p>
        }
      </form>
      <form aria-label="Retire selected people" (submit)="bulkRetire($event)">
        <label for="bulk-retire-date">Retire selected people from</label>
        <input
          type="date"
          id="bulk-retire-date"
          data-testid="bulk-retire-date"
          [value]="bulkRetireDate()"
          (input)="bulkRetireDate.set($any($event.target).value); bulkRetireError.set(null)"
        />
        <button type="submit" data-testid="bulk-retire">Retire selected</button>
        @if (bulkRetired() !== null) {
          <p>Retired <strong data-testid="bulk-retired">{{ bulkRetired() }}</strong></p>
        }
        @if (bulkRetireError(); as err) {
          <p role="alert" data-testid="person-error">{{ err }}</p>
        }
      </form>
      <p>Claimed in total <strong data-testid="rollup-claimed-total">{{ claimedTotal() | figure }}</strong></p>
      <p>Due in total <strong data-testid="rollup-due-total">{{ dueTotal() | figure }}</strong></p>
      <p>Settled in total <strong data-testid="rollup-settled-total">{{ money(settledOpenTotals().settled) | figure }}</strong></p>
      <p>Open in total <strong data-testid="rollup-open-total">{{ money(settledOpenTotals().open) | figure }}</strong></p>
      <p>Overdue claims <strong data-testid="rollup-overdue-count">{{ overdueTotals().count }}</strong></p>
      <p>Overdue in total <strong data-testid="rollup-overdue-total">{{ money(overdueTotals().cents) | figure }}</strong></p>
      <p>Due aged 0 to 30 days <strong data-testid="rollup-aged-0-30">{{ money(agedTotals()[0]) | figure }}</strong></p>
      <p>Due aged 31 to 60 days <strong data-testid="rollup-aged-31-60">{{ money(agedTotals()[1]) | figure }}</strong></p>
      <p>Due aged 61 or more days <strong data-testid="rollup-aged-61">{{ money(agedTotals()[2]) | figure }}</strong></p>
      <p>Cut in total <strong data-testid="rollup-cut-total">{{ money(cutTotal()) | figure }}</strong></p>
      @if (tagTotals().length > 0) {
        <table aria-label="Due by tag">
          <thead>
            <tr>
              <th scope="col">Tag</th>
              <th scope="col">Due</th>
            </tr>
          </thead>
          <tbody>
            @for (t of tagTotals(); track t.tag) {
              <tr>
                <th scope="row">{{ t.tag }}</th>
                <td [attr.data-testid]="'tag-' + t.tag + '-due-total'">{{ money(t.cents) | figure }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
      @if (monthTotals().length > 0) {
        <table aria-label="Due by month">
          <thead>
            <tr>
              <th scope="col">Month</th>
              <th scope="col">Due</th>
            </tr>
          </thead>
          <tbody>
            @for (m of monthTotals(); track m.month) {
              <tr>
                <th scope="row">{{ m.month }}</th>
                <td [attr.data-testid]="'month-' + m.month + '-due-total'">{{ money(m.cents) | figure }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
      <app-summary [grades]="gradeSummary()" [periods]="periodSummary()" [statuses]="statusSummary()" [voidReasons]="voidReasonSummary()" [reviewLateCount]="reviewLateCount()" />
      <app-classic-summary [periods]="classicPeriodSummary()" [dueTotal]="classicDueTotal()" />
      <table data-testid="people-list">
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th>
            <th scope="col">Name</th>
            <th scope="col">Grade</th>
            @if (shown('terms')) { <th scope="col">Terms</th> }
            @if (shown('contact')) { <th scope="col">Contact</th> }
            <th scope="col">Tags</th>
            @if (shown('claimed')) { <th scope="col">Claimed</th> }
            @if (shown('due-total')) { <th scope="col">Due</th> }
            <th scope="col">Settled</th>
            <th scope="col">Open</th>
            @for (pd of periods(); track pd.id) {
              <th scope="col">Due in {{ pd.name }}</th>
              <th scope="col">Carried into {{ pd.name }}</th>
            }
            <th scope="col">Aged 0–30</th>
            <th scope="col">Aged 31–60</th>
            <th scope="col">Aged 61+</th>
            <th scope="col">Overdue</th>
            <th scope="col">Active</th>
            <th scope="col">Statements</th>
            <th scope="col">Retired</th>
            <th scope="col">On hold</th>
            <th scope="col">Aliases</th>
            <th scope="col">Clashing</th>
            <th scope="col">Came from</th>
            <th scope="col">Patrons</th>
            <th scope="col">Watched</th>
            <th scope="col">Claims</th>
            <th scope="col">Plans</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
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
                  (change)="selectPerson(p.id, $any($event.target).checked)"
                />
              </td>
              <td data-testid="person-name">{{ shownName(p) }}</td>
              <td data-testid="person-grade">{{ gradeToday(p) }}</td>
              @if (shown('terms')) {
                <td data-testid="person-terms">{{ p.terms ?? '' }}</td>
              }
              @if (shown('contact')) {
                <td data-testid="person-contact">{{ p.contact ?? '' }}</td>
              }
              <td data-testid="person-tags">{{ p.tags.join(', ') }}</td>
              @if (shown('claimed')) {
                <td data-testid="person-claimed">{{ claimedOf(p.id) | figure }}</td>
              }
              @if (shown('due-total')) {
                <td data-testid="person-due-total">{{ dueText(dueOf(p)) | figure }}</td>
              }
              <td data-testid="person-settled-total">{{ money(settledOpenOf(p).settled) | figure }}</td>
              <td data-testid="person-open-total">{{ money(settledOpenOf(p).open) | figure }}</td>
              @for (pd of periods(); track pd.id) {
                <td [attr.data-testid]="'person-period-' + pd.name + '-due-total'">{{ periodDueOf(pd, p.id) | figure }}</td>
                <td [attr.data-testid]="'person-period-' + pd.name + '-carried'">{{ periodCarriedOf(pd, p.id) | figure }}</td>
              }
              <td data-testid="person-aged-0-30">{{ money(agedOf(p)[0]) | figure }}</td>
              <td data-testid="person-aged-31-60">{{ money(agedOf(p)[1]) | figure }}</td>
              <td data-testid="person-aged-61">{{ money(agedOf(p)[2]) | figure }}</td>
              <td>
                @if (shown('overdue-count')) {
                  <span data-testid="person-overdue-count">{{ overdueOf(p).count }}</span>
                }
                @if (shown('overdue-total')) {
                  <span>totalling <span data-testid="person-overdue-total">{{ money(overdueOf(p).cents) | figure }}</span></span>
                }
              </td>
              <td data-testid="person-active">{{ isActive(p) ? 'yes' : 'no' }}</td>
              <td>
                @if (shown('statement-count')) {
                  <span data-testid="person-statement-count">{{ statementOf(p.id)?.count ?? 0 }}</span>
                }
                @if (shown('stated-total')) {
                  <span>Last due <span data-testid="person-stated-total">{{ (statementOf(p.id)?.dueTotal ?? '') | figure }}</span></span>
                }
                @if (shown('statement-date')) {
                  <span>on <span data-testid="person-statement-date">{{ statementOf(p.id)?.date ?? '' }}</span></span>
                }
                <ul aria-label="Statements">
                  @for (l of statementOf(p.id)?.lines ?? []; track $index) {
                    <li data-testid="person-statement-line-line">{{ l.date }} {{ l.dueTotal }}</li>
                  }
                </ul>
              </td>
              <td>
                @if (shown('retired')) {
                  <span data-testid="person-retired">{{ p.retired ? 'yes' : 'no' }}</span>
                }
                <span>from <span data-testid="person-retired-from">{{ p.retiredFrom ?? '' }}</span></span>
                <ul>
                  @for (r of p.retirements; track $index) {
                    <li data-testid="person-retirement-line">{{ r.from }} to {{ r.to }}</li>
                  }
                </ul>
                <span>Flag: <span data-testid="person-flag-shown">{{ p.flag ?? '' }}</span></span>
                <span>Approver: <span data-testid="person-approver">{{ p.approver ?? '' }}</span></span>
                <ul aria-label="Snapshots">
                  @for (n of p.snapshots; track $index) {
                    <li data-testid="person-snapshot-line">{{ n.day }} {{ n.dueTotal }}</li>
                  }
                </ul>
              </td>
              <td>
                @if (shown('hold')) {
                  <span data-testid="person-hold">{{ p.held ? 'yes' : 'no' }}</span>
                }
                <ul>
                  @for (h of p.holds; track $index) {
                    <li data-testid="person-hold-line">{{ h.from }} to {{ h.to ?? 'open' }}</li>
                  }
                </ul>
              </td>
              <td>
                @if (shown('aliases')) {
                  <span data-testid="person-aliases">{{ p.aliases.join(', ') }}</span>
                }
                <label [for]="'person-alias-input-' + p.id" class="visually-hidden">Alias for {{ p.name }}</label>
                <input
                  type="text"
                  [id]="'person-alias-input-' + p.id"
                  data-testid="person-alias-input"
                  [value]="aliasInputs().get(p.id) ?? ''"
                  (input)="setAliasInput(p.id, $any($event.target).value)"
                />
                <button
                  type="button"
                  data-testid="person-alias-add"
                  [attr.aria-label]="'Add alias for ' + p.name"
                  (click)="addAlias(p)"
                >
                  Add alias
                </button>
              </td>
              <td>
                <span data-testid="person-clashing">{{ p.clashing ? 'yes' : 'no' }}</span>
              </td>
              <td data-testid="person-origin">{{ p.origin ?? '' }}</td>
              <td>
                @if (shown('patrons')) {
                  <span data-testid="person-patrons">{{ patronNamesOf(p.id) }}</span>
                }
                @if (shown('backing')) {
                  <span>backing <span data-testid="person-backing">{{ money(backingOf(p.id)) | figure }}</span></span>
                }
                @if (shown('excess')) {
                  <span>excess <span data-testid="person-excess">{{ money(excessOf(p)) | figure }}</span></span>
                }
                <span>cut <span data-testid="person-cut-total">{{ money(cutTotalOf(p.id)) | figure }}</span></span>
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
                  [graces]="graces()"
                  [policyDate]="policyDate()"
                  [roundings]="roundings()"
                  [periods]="periods()"
                  [selected]="selectedClaims()"
                  [people]="people()"
                  (saved)="claimSaved($event)"
                  (reassigned)="claimMoved($event)"
                  (selectedChange)="selectClaim($event.id, $event.on)"
                />
              </td>
              <td>
                <app-person-plans
                  [personId]="p.id"
                  [personName]="p.name"
                  [plans]="plansOf(p.id)"
                  (added)="planAdded($event)"
                />
              </td>
              <td>
                <button
                  type="button"
                  data-testid="person-move-up"
                  [attr.aria-label]="'Move ' + p.name + ' up'"
                  (click)="move(p, 'up')"
                >
                  Up
                </button>
                <button
                  type="button"
                  data-testid="person-move-down"
                  [attr.aria-label]="'Move ' + p.name + ' down'"
                  (click)="move(p, 'down')"
                >
                  Down
                </button>
                <button
                  type="button"
                  [attr.data-testid]="'person-edit-' + p.id"
                  [attr.aria-label]="'Edit ' + p.name"
                  (click)="edit(p)"
                >
                  Edit
                </button>
                <label [for]="'person-inline-grade-' + p.id">Grade from today</label>
                <select
                  [id]="'person-inline-grade-' + p.id"
                  data-testid="person-inline-grade"
                  [value]="inlineGrades().get(p.id) ?? gradeToday(p)"
                  (change)="setInlineGrade(p.id, $any($event.target).value)"
                >
                  @for (g of grades; track g) {
                    <option [value]="g" [selected]="g === (inlineGrades().get(p.id) ?? gradeToday(p))">{{ g }}</option>
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
                <button
                  type="button"
                  data-testid="person-export-statement"
                  [attr.aria-label]="'Export statement for ' + p.name"
                  (click)="exportStatement(p)"
                >
                  Export statement
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
                <button
                  type="button"
                  data-testid="person-unretire"
                  [attr.aria-label]="'Unretire ' + p.name"
                  (click)="unretire(p)"
                >
                  Unretire
                </button>
                <label [for]="'person-flag-reason-' + p.id">Flag reason</label>
                <input
                  type="text"
                  [id]="'person-flag-reason-' + p.id"
                  data-testid="person-flag-reason"
                  [value]="flagReasons().get(p.id) ?? ''"
                  (input)="setFlagReason(p.id, $any($event.target).value)"
                />
                <label [for]="'person-flag-until-' + p.id">until</label>
                <input
                  type="date"
                  [id]="'person-flag-until-' + p.id"
                  data-testid="person-flag-until"
                  [value]="flagUntils().get(p.id) ?? ''"
                  (input)="setFlagUntil(p.id, $any($event.target).value)"
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
                  [value]="approverInputs().get(p.id) ?? ''"
                  (input)="setApproverInput(p.id, $any($event.target).value)"
                />
                <button
                  type="button"
                  data-testid="person-approver-save"
                  [attr.aria-label]="'Set the approver of ' + p.name"
                  (click)="saveApprover(p)"
                >
                  Set approver
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
                <label [for]="'person-merge-into-' + p.id">Merge into</label>
                <select
                  [id]="'person-merge-into-' + p.id"
                  data-testid="person-merge-into"
                  [value]="mergeChoices().get(p.id) ?? ''"
                  (change)="setMergeChoice(p.id, $any($event.target).value)"
                >
                  <option value="" [selected]="!mergeChoices().get(p.id)">Choose a person</option>
                  @for (o of people(); track o.id) {
                    <option [value]="'' + o.id" [selected]="mergeChoices().get(p.id) === '' + o.id">{{ o.name }}</option>
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
                <label [for]="'person-transfer-to-' + p.id">Transfer to sheet</label>
                <input
                  type="text"
                  [id]="'person-transfer-to-' + p.id"
                  data-testid="person-transfer-to"
                  [value]="transferTos().get(p.id) ?? ''"
                  (input)="setTransferTo(p.id, $any($event.target).value)"
                />
                <button
                  type="button"
                  data-testid="person-transfer"
                  [attr.aria-label]="'Transfer ' + p.name + ' to the sheet entered'"
                  (click)="transfer(p)"
                >
                  Transfer
                </button>
                <button
                  type="button"
                  data-testid="person-transfer-undo"
                  [attr.aria-label]="'Undo the transfer that brought ' + p.name + ' here'"
                  (click)="undoTransfer(p)"
                >
                  Undo transfer
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
  protected readonly columns = PEOPLE_COLUMNS;
  /** Whether the signed-in user shows this column of the list on this screen. */
  protected shown(column: PeopleColumn): boolean {
    return !this.session.hidden().has(column);
  }
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  /** The people on the list: everyone, or only those not retired unless show-retired is on. */
  protected readonly listed = computed(() =>
    this.session.showRetired() ? this.people() : this.people().filter((p) => !p.retired),
  );
  /** The listed people in the order this screen sorts them by; equal keys keep list order. Only the
   *  rows shown follow it: every total and action keeps to list order. */
  protected readonly sorted = computed(() => {
    const listed = this.listed();
    const by = this.session.sortBy();
    if (by === 'added') return listed;
    const key = (p: Person): string | number | null =>
      by === 'name' ? p.name.toLowerCase() : by === 'grade' ? this.gradeToday(p) || null : this.dueOf(p);
    const keyed = listed.map((p) => ({ p, k: key(p) }));
    return keyed
      .sort((a, b) => {
        if (a.k === b.k) return 0;
        if (a.k === null) return 1;
        if (b.k === null) return -1;
        return a.k < b.k ? -1 : 1;
      })
      .map((e) => e.p);
  });
  /** The sheet id typed on each person's row to transfer them to, by person id. */
  protected readonly transferTos = signal<ReadonlyMap<number, string>>(new Map());
  /** The grade chosen on each person's row, by person id; none chosen shows the grade in force today. */
  protected readonly inlineGrades = signal<ReadonlyMap<number, string>>(new Map());
  /** The retire-from date typed on each person's row, by person id. */
  protected readonly retireDates = signal<ReadonlyMap<number, string>>(new Map());
  /** The flag reason and until-date typed on each person's row, by person id. */
  protected readonly flagReasons = signal<ReadonlyMap<number, string>>(new Map());
  protected readonly flagUntils = signal<ReadonlyMap<number, string>>(new Map());
  /** The approver typed on each person's row, by person id. */
  protected readonly approverInputs = signal<ReadonlyMap<number, string>>(new Map());
  /** The alias typed on each person's row, by person id. */
  protected readonly aliasInputs = signal<ReadonlyMap<number, string>>(new Map());
  /** The hold from- and to-dates typed on each person's row, by person id. */
  protected readonly holdFroms = signal<ReadonlyMap<number, string>>(new Map());
  /** The date each person's next statement is taken as of, by person id; blank for now. */
  protected readonly statementAsOfs = signal<ReadonlyMap<number, string>>(new Map());
  protected readonly holdTos = signal<ReadonlyMap<number, string>>(new Map());
  /** The sheet's claims, newest first. */
  protected readonly claims = signal<Claim[]>([]);
  /** The sheet's plans, in the order they were added. */
  protected readonly plans = signal<Plan[]>([]);
  /** How many claims the last generate made; null before one, and while one runs. */
  protected readonly planGenerated = signal<number | null>(null);
  protected readonly planGenerateError = signal<string | null>(null);
  /** The ids of the claims ticked for a bulk action. */
  protected readonly selectedClaims = signal<ReadonlySet<number>>(new Set());
  /** What the last bulk open did; cleared while the next one runs. */
  protected readonly bulkOpenResult = signal<{ opened: number; refused: number } | null>(null);
  protected readonly bulkOpenError = signal<string | null>(null);
  protected readonly bulkSettleResult = signal<{ settled: number; refused: number } | null>(null);
  protected readonly bulkSettleError = signal<string | null>(null);
  /** The id of the person the selected claims are moved to; blank until one is chosen. */
  protected readonly bulkReassignTo = signal('');
  protected readonly bulkReassignResult = signal<{ reassigned: number; refused: number } | null>(null);
  protected readonly bulkReassignError = signal<string | null>(null);
  /** The date the selected claims are re-dated to; blank until one is typed. */
  protected readonly bulkRedateDate = signal('');
  protected readonly bulkRedateResult = signal<{ redated: number; refused: number } | null>(null);
  protected readonly bulkRedateError = signal<string | null>(null);
  /** The void reason the selected claims are voided with; blank until one is chosen. */
  protected readonly bulkVoidReason = signal('');
  protected readonly bulkVoidResult = signal<{ voided: number; refused: number } | null>(null);
  protected readonly bulkVoidError = signal<string | null>(null);
  /** The ids of the people ticked for a bulk action. */
  protected readonly selectedPeople = signal<ReadonlySet<number>>(new Set());
  protected readonly bulkGradeInput = signal<string>('A');
  /** The from-date of the bulk grade; blank for the sheet's today. */
  protected readonly bulkGradeFrom = signal('');
  /** How many people the last bulk grade changed; cleared while the next one runs. */
  protected readonly bulkGraded = signal<number | null>(null);
  protected readonly bulkGradeError = signal<string | null>(null);
  /** The from-date of the bulk retire; blank for the sheet's today. */
  protected readonly bulkRetireDate = signal('');
  /** How many people the last bulk retire retired; cleared while the next one runs. */
  protected readonly bulkRetired = signal<number | null>(null);
  protected readonly bulkRetireError = signal<string | null>(null);
  protected readonly importText = signal('');
  /** How many people the last import added; cleared while the next one runs. */
  protected readonly importAdded = signal<number | null>(null);
  protected readonly importUpdated = signal<number | null>(null);
  protected readonly importError = signal<string | null>(null);
  private readonly claimsByPerson = computed(() => {
    const by = new Map<number, Claim[]>();
    for (const c of this.claims()) by.set(c.personId, [...(by.get(c.personId) ?? []), c]);
    return by;
  });
  /** Whether the duplicate claims are shown; they stay shown, kept up to date, once asked for. */
  protected readonly dupsShown = signal(false);
  /** For each person, date and amount with two or more claims (leaving out VOID and DELETED):
   *  "<name> <date> <amount> x<count>", people in list order, then by date, then by amount. */
  protected readonly dups = computed(() => {
    const lines: string[] = [];
    for (const p of this.people()) {
      const groups = new Map<string, { date: string; cents: number; count: number }>();
      for (const c of this.claimsOf(p.id)) {
        if (c.status === 'VOID' || c.status === 'DELETED') continue;
        const key = `${c.date} ${cents(c.amount)}`;
        const g = groups.get(key) ?? { date: c.date, cents: cents(c.amount), count: 0 };
        g.count++;
        groups.set(key, g);
      }
      [...groups.values()]
        .filter((g) => g.count >= 2)
        .sort((a, b) => a.date.localeCompare(b.date) || a.cents - b.cents)
        .forEach((g) => lines.push(`${p.name} ${g.date} ${money(g.cents)} x${g.count}`));
    }
    return lines;
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
  /** Each grade's grace days, dated (blank: none): a claim whose person's grade on its date has some
   *  on its date is overdue only once its due-by plus them is before the sheet's today. */
  protected readonly graces = signal<Record<string, DatedValue[]>>({});
  /** The sheet's policy date (blank: its today): a claim dated before it rounds half up, else half to even. */
  protected readonly policyDate = signal('');
  /** The sheet's rounding modes, dated (blank: none): the one in force on a claim's date, when there is one, is how it rounds. */
  protected readonly roundings = signal<DatedValue[]>([]);
  /** The sheet's pot, blank for none: what the pools cut. */
  protected readonly pot = signal('');
  /** The sheet's pools, as the pools list last read them. */
  protected readonly pools = signal<Pool[]>([]);
  /** The sheet's crews, as the crews list last read them. */
  protected readonly crews = signal<Crew[]>([]);
  /** Everyone with their due-total as shown, for the crews' due-totals. */
  protected readonly crewPeople = computed<CrewPerson[]>(() =>
    this.people().map((p) => ({ id: p.id, name: p.name, shown: this.shownName(p), due: this.dueOf(p) })),
  );
  /** Per person id: their cuts across every pool they are a member of, in whole cents. */
  private readonly cutTotals = computed(() => {
    const totals = new Map<number, number>();
    for (const pool of this.pools()) {
      for (const m of pool.members) {
        if (m.pool === null && m.cut !== '') totals.set(m.personId, (totals.get(m.personId) ?? 0) + cents(m.cut));
      }
    }
    return totals;
  });
  /** The sheet's cut total: everyone's cut totals added up, in whole cents. */
  protected readonly cutTotal = computed(() =>
    this.people().reduce((sum, p) => sum + this.cutTotalOf(p.id), 0),
  );
  /** What works out an overdue claim's late charge on the sheet's today. */
  private readonly lateness = computed<Lateness>(() => ({
    latePcts: this.latePcts(),
    graces: this.graces(),
    today: this.session.today(),
    policyDate: this.policyDate(),
    roundings: this.roundings(),
  }));
  /** The due-by (which places it in a period) and figure of each claim that has a figure, and whether it counts by its status at
   *  the end of a day, for the periods' due-totals. */
  protected readonly dues = computed(() => {
    const out: DatedDue[] = [];
    const today = this.session.today();
    for (const c of this.claims()) {
      const p = this.peopleById().get(c.personId);
      if (p === undefined) continue;
      const due = chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floors(), this.lateness(), this.roundOn(c));
      if (due === null) continue;
      // a retirement takes out only a claim that is open now as well
      const countsAt = (day: string): boolean => {
        const status = statusAt(c, day);
        return status !== null && (isOpen(c) ? countsFor(p, { ...c, status }, today)
          : countsFor(undefined, { ...c, status }, today));
      };
      const openAt = (day: string): boolean => {
        const status = statusAt(c, day);
        return (status === 'OPEN' || status === 'PENDING') && countsAt(day);
      };
      out.push({ personId: c.personId, date: c.dueBy, cents: due, countsAt, countsNow: countsFor(p, c, today), openAt });
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
  /** The person chosen on each person's row to merge them into, by person id (the other's id as text). */
  protected readonly mergeChoices = signal<ReadonlyMap<number, string>>(new Map());
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
  /** The sheet's settled-total and open-total: everyone's added up, untrimmed, in whole cents. */
  protected readonly settledOpenTotals = computed(() => {
    const each = this.people().map((p) => this.settledOpenOf(p));
    return {
      settled: each.reduce((sum, t) => sum + t.settled, 0),
      open: each.reduce((sum, t) => sum + t.open, 0),
    };
  });
  protected readonly dueText = dueText;
  protected readonly money = money;
  protected readonly cents = cents;
  /** The figures of the sheet's descendants as the store holds them, added to its own for the consolidated figures. */
  protected readonly descendants = signal<Descendants>(NO_DESCENDANTS);
  /**
   * The figures over the sheet and its descendants. An as-of view shows every sheet of the tree as it
   * stood at the end of that day, adding the sheet's own figures as the store held them then; else the screen's own.
   */
  protected readonly consolidated = computed(() => {
    const d = this.descendants();
    const own = this.session.asOf() && d.own
      ? { peopleCount: d.own.peopleCount, due: cents(d.own.dueTotal), overdue: cents(d.own.overdueTotal) }
      : { peopleCount: this.people().length, due: cents(this.dueTotal()), overdue: this.overdueTotals().cents };
    return {
      peopleCount: own.peopleCount + d.peopleCount,
      dueTotal: money(own.due + cents(d.dueTotal)),
      overdueTotal: money(own.overdue + cents(d.overdueTotal)),
    };
  });
  /** The person on the sheet with the user's name, by name or alias (compared without regard to case). */
  private readonly me = computed(() => {
    const user = this.session.user()?.toLowerCase();
    if (!user) return undefined;
    return this.people().find(
      (p) => p.name.toLowerCase() === user || p.aliases.some((a) => a.toLowerCase() === user),
    );
  });
  /** The due-totals of the crews the user's person leads, added up; blank when they lead none. */
  protected readonly myCrewDueTotal = computed(() => {
    const me = this.me();
    const led = me ? this.crews().filter((c) => c.leadId === me.id) : [];
    if (led.length === 0) return '';
    const people = this.crewPeople();
    return money(led.reduce((sum, c) => sum + crewDueCents(c, people), 0));
  });
  /**
   * The signed-in user's own figures: the due- and overdue-totals of the person on the sheet with
   * the user's name, by name or alias (compared without regard to case); none when there is no such person.
   */
  protected readonly myFigures = computed(() => {
    const me = this.me();
    return me ? { dueTotal: dueText(this.dueOf(me)), overdueTotal: money(this.overdueOf(me).cents) } : null;
  });
  /** The sheet's OPEN and PENDING claims that count, in whole cents by age: the people's buckets added up, untrimmed. */
  protected readonly agedTotals = computed(() =>
    this.people().map((p) => this.agedOf(p)).reduce<[number, number, number]>(
      (sum, a) => [sum[0] + a[0], sum[1] + a[1], sum[2] + a[2]],
      [0, 0, 0],
    ),
  );
  /** The sheet's overdue claims that count: how many, and the sum of people's figures in whole cents, trimmed. */
  protected readonly overdueTotals = computed(() => {
    const each = this.people().map((p) => this.overdueOf(p));
    return {
      count: each.reduce((sum, o) => sum + o.count, 0),
      cents: trimmedSum(each.map((o) => o.cents)),
    };
  });
  /** Every tag ever used on the sheet, as the store held them when the sheet was loaded. */
  protected readonly usedTags = signal<string[]>([]);
  /**
   * Per tag ever used on the sheet (sorted): the figures of the claims that count which carry it, or
   * whose person carries it, in whole cents, untrimmed and without the cap; a claim with two tags
   * counts in both, and once in a tag both it and its person carry.
   */
  protected readonly tagTotals = computed(() => {
    const totals = new Map<string, number>(this.usedTags().map((t) => [t, 0]));
    for (const c of this.claims()) for (const t of c.tags) if (!totals.has(t)) totals.set(t, 0);
    for (const p of this.people()) for (const t of p.tags) if (!totals.has(t)) totals.set(t, 0);
    const today = this.session.today();
    for (const p of this.people()) {
      for (const c of this.claimsOf(p.id)) {
        const tags = new Set([...c.tags, ...p.tags]);
        if (tags.size === 0 || !countsFor(p, c, today)) continue;
        const figure = chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floors(), this.lateness(), this.roundOn(c)) ?? 0;
        for (const t of tags) totals.set(t, (totals.get(t) ?? 0) + figure);
      }
    }
    return [...totals].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([tag, cents]) => ({ tag, cents }));
  });
  /**
   * Per calendar month (yyyy-mm, sorted) that any claim on the sheet is dated in: the figures of the
   * claims dated in it that count, in whole cents, untrimmed and without the cap.
   */
  protected readonly monthTotals = computed(() => {
    const totals = new Map<string, number>();
    for (const c of this.claims()) totals.set(c.date.slice(0, 7), 0);
    const today = this.session.today();
    for (const p of this.people()) {
      for (const c of this.claimsOf(p.id)) {
        if (!countsFor(p, c, today)) continue;
        const figure = chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floors(), this.lateness(), this.roundOn(c)) ?? 0;
        const month = c.date.slice(0, 7);
        totals.set(month, (totals.get(month) ?? 0) + figure);
      }
    }
    return [...totals].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).map(([month, cents]) => ({ month, cents }));
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
  /** Per period: its due-total, as the periods list shows it, and its change from the period
   *  before it by start date (none for the earliest). */
  protected readonly periodSummary = computed<PeriodSummary[]>(() => {
    const totals = new Map(this.periods().map((p) => [p.id, periodDueTotal(p, this.dues())]));
    const byStart = [...this.periods()].sort((a, b) => (a.from < b.from ? -1 : a.from > b.from ? 1 : 0));
    const change = new Map<number, string>();
    byStart.forEach((p, i) => {
      const before = byStart[i - 1];
      change.set(p.id, before ? money(cents(totals.get(p.id)!) - cents(totals.get(before.id)!)) : '');
    });
    return this.periods().map((p) => ({ name: p.name, dueTotal: totals.get(p.id)!, change: change.get(p.id)! }));
  });
  /** Per period: its classic due-total, each claim counted by its status now (fixed while closed). */
  protected readonly classicPeriodSummary = computed<ClassicPeriodSummary[]>(() =>
    this.periods().map((p) => ({ name: p.name, dueTotal: classicPeriodDueTotal(p, this.dues()) })),
  );
  /** The sheet's classic due-total: people's due-totals added up (skipping n/a), untrimmed. */
  protected readonly classicDueTotal = computed(() =>
    money(this.people().reduce((sum, p) => sum + (this.dueOf(p) ?? 0), 0)),
  );
  /** A person's due-total for a period, as their row shows it. */
  protected periodDueOf(period: Period, personId: number): string {
    return personPeriodDueTotal(period, personId, this.dues());
  }
  /** A person's carried figure for a period, as their row shows it. */
  protected periodCarriedOf(period: Period, personId: number): string {
    return personPeriodCarried(period, this.periods(), personId, this.dues());
  }
  /** Per status: how many of the sheet's claims have it, retired people's included. */
  protected readonly statusSummary = computed<StatusSummary[]>(() =>
    SUMMARY_STATUSES.map((status) => ({
      status,
      count: this.claims().filter((c) => c.status === status).length,
    })),
  );
  /** How many of the sheet's claims the summary counts in a status. */
  protected statusCount(status: string): number {
    return this.statusSummary().find((s) => s.status === status)?.count ?? 0;
  }
  /** How many of the sheet's REVIEW claims are late for review on its today. */
  protected readonly reviewLateCount = computed(() =>
    this.claims().filter((c) => reviewLate(c, this.session.today(), this.session.reviewSla())).length,
  );
  protected readonly voidReasons = inject(VoidReasonList);
  /** Per reason on the sheet's void reasons: how many VOID claims were voided with it. */
  protected readonly voidReasonSummary = computed<VoidReasonSummary[]>(() =>
    this.voidReasons.list().map((r) => ({
      reason: r.reason,
      count: this.claims().filter((c) => c.status === 'VOID' && c.voidReason === r.reason).length,
    })),
  );
  protected readonly nameInput = signal('');
  protected readonly gradeInput = signal<string>('A');
  /** The date the form's grade is recorded from; blank for the sheet's today. */
  protected readonly gradeFromInput = signal('');
  /** The person's own terms in days; blank for none. */
  protected readonly termsInput = signal('');
  /** The date the form's terms are recorded from; blank for the beginning of time. */
  protected readonly termsFromInput = signal('');
  /** The person's contact; blank for none. */
  protected readonly contactInput = signal('');
  /** The person's tags, comma separated; blank for none. */
  protected readonly tagsInput = signal('');
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
  /** Whether a generate is waiting for its answer: a second press meanwhile is ignored. */
  private planGenerating = false;

  constructor() {
    // Re-read the sheet's people from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.people.set([]);
      this.claims.set([]);
      this.plans.set([]);
      this.planGenerated.set(null);
      this.planGenerateError.set(null);
      this.descendants.set(NO_DESCENDANTS);
      this.usedTags.set([]);
      this.selectedClaims.set(new Set());
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
      this.bulkVoidReason.set('');
      this.bulkVoidResult.set(null);
      this.bulkVoidError.set(null);
      this.selectedPeople.set(new Set());
      this.bulkGradeInput.set('A');
      this.bulkGradeFrom.set('');
      this.bulkGraded.set(null);
      this.bulkGradeError.set(null);
      this.bulkRetireDate.set('');
      this.bulkRetired.set(null);
      this.bulkRetireError.set(null);
      this.importText.set('');
      this.importAdded.set(null);
      this.importUpdated.set(null);
      this.importError.set(null);
      this.statements.set(new Map());
      this.rowErrors.set(new Map());
      this.patrons.set([]);
      this.patronChoices.set(new Map());
      this.patronErrors.set(new Map());
      this.mergeChoices.set(new Map());
      this.transferTos.set(new Map());
      this.watched.set(new Set());
      this.retireDates.set(new Map());
      this.inlineGrades.set(new Map());
      this.holdFroms.set(new Map());
      this.flagReasons.set(new Map());
      this.flagUntils.set(new Map());
      this.approverInputs.set(new Map());
      this.aliasInputs.set(new Map());
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
      const [list, claims, statements, patrons, watched, tags, descendants, plans] = await Promise.all([
        firstValueFrom(this.http.post<Person[]>('/api/people/list', { sheet })),
        firstValueFrom(this.http.post<Claim[]>('/api/claims/list', { sheet })),
        firstValueFrom(this.http.post<StatementSummary[]>('/api/statements/list', { sheet })),
        firstValueFrom(this.http.post<PatronLink[]>('/api/people/patrons/list', { sheet })),
        firstValueFrom(this.http.post<number[]>('/api/people/watched', { sheet, user: this.session.user() })),
        firstValueFrom(this.http.post<string[]>('/api/claims/tags', { sheet })),
        firstValueFrom(this.http.post<Descendants>('/api/rollup/descendants/list', { sheet })),
        firstValueFrom(this.http.post<Plan[]>('/api/plans/list', { sheet })),
      ]);
      if (this.session.sheet() === sheet) {
        this.claims.set(claims);
        this.plans.set(plans);
        this.descendants.set(descendants);
        this.usedTags.set(tags);
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

  /** The person's name as the screen shows it: "<name> (<grade in force on the sheet's today>)". */
  protected shownName(p: Person): string {
    return `${p.name} (${this.gradeToday(p)})`;
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

  /** A claim's amount in whole cents when it counts toward claimed, else nothing: a correction never does. */
  private countedCents(p: Person | undefined, c: Claim): number {
    return countsFor(p, c, this.session.today()) && !isCorrection(c) ? cents(c.amount) : 0;
  }

  /** What the person owes, in whole cents: the sum of their figures held to the sheet's cap. */
  protected dueOf(p: Person): number | null {
    const sum = this.figuresOf(p);
    const cap = this.capCents();
    return sum === null || cap === null ? sum : Math.min(sum, cap);
  }

  /** The person's cut total, in whole cents: 0 when no pool shows them a cut. */
  protected cutTotalOf(id: number): number {
    return this.cutTotals().get(id) ?? 0;
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
   * The figures of the person's claims that count, in whole cents, split into those of their
   * SETTLED claims and those of their OPEN and PENDING claims; together the sum before the cap.
   */
  protected settledOpenOf(p: Person): { settled: number; open: number } {
    const out = { settled: 0, open: 0 };
    for (const c of this.claimsOf(p.id)) {
      if (!countsFor(p, c, this.session.today())) continue;
      const figure = chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floors(), this.lateness(), this.roundOn(c)) ?? 0;
      if (isOpen(c)) out.open += figure;
      else out.settled += figure;
    }
    return out;
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

  /**
   * The figures of the person's OPEN and PENDING claims that count, in whole cents, by age on the
   * sheet's today: 0 to 30 days (a claim dated after today included), 31 to 60, and 61 or more.
   */
  protected agedOf(p: Person): [number, number, number] {
    const today = this.session.today();
    const aged: [number, number, number] = [0, 0, 0];
    if (!today) return aged;
    for (const c of this.claimsOf(p.id)) {
      if (!isOpen(c) || !countsFor(p, c, today)) continue;
      const age = dayNumber(today) - dayNumber(c.date);
      const figure = chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floors(), this.lateness(), this.roundOn(c)) ?? 0;
      aged[age <= 30 ? 0 : age <= 60 ? 1 : 2] += figure;
    }
    return aged;
  }

  /** The person's overdue claims that count: how many, and the sum of their figures in whole cents. */
  protected overdueOf(p: Person): { count: number; cents: number } {
    const today = this.session.today();
    const late = this.claimsOf(p.id).filter((c) => countsFor(p, c, this.session.today())
      && overdue(c, today, graceOn(this.graces(), gradeOn(p.grades, c.date), c.date)));
    return {
      count: late.length,
      cents: late.reduce((sum, c) => sum + (chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floors(), this.lateness(), this.roundOn(c)) ?? 0), 0),
    };
  }

  protected statementOf(personId: number): StatementSummary | undefined {
    return this.statements().get(personId);
  }

  /** The person's patrons as "<name> <share>", sorted by name, comma and space separated. */
  protected patronNamesOf(personId: number): string {
    return this.sharesOf(personId)
      .map(({ patron, share }) => ({ name: patron.name, shown: this.shownName(patron), share }))
      .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
      .map(({ shown, share }) => shown + ' ' + money(share))
      .join(', ');
  }

  /**
   * The person's backing in whole cents: over their patrons, the patron's frozen figures over their
   * SETTLED claims dated within the 90 days up to and including the sheet's today, times the patron's
   * share divided by 100, rounded half to even, added up.
   */
  protected backingOf(personId: number): number {
    const today = this.session.today();
    if (!today) return 0;
    const since = plusDays(today, -89);
    return this.sharesOf(personId).reduce((sum, { patron, share }) => {
      const figure = this.claimsOf(patron.id)
        .filter((c) => c.status === 'SETTLED' && c.date >= since && c.date <= today)
        .reduce((total, c) => total + (c.settledDue === null ? 0 : cents(c.settledDue)), 0);
      return sum + (dueCents(figure, money(share)) ?? 0);
    }, 0);
  }

  /**
   * The person's patrons with their shares in whole hundredths: 100.00 split equally in the order
   * they were named, each rounded half to even, the remainder to the first-named.
   */
  private sharesOf(personId: number): { patron: Person; share: number }[] {
    const patrons = this.patronsOf(personId);
    if (patrons.length === 0) return [];
    const n = patrons.length;
    const whole = Math.floor(10000 / n);
    const rest = 10000 - whole * n;
    const each = 2 * rest > n || (2 * rest === n && whole % 2 === 1) ? whole + 1 : whole;
    const remainder = 10000 - each * n;
    return patrons.map((patron, i) => ({ patron, share: i === 0 ? each + remainder : each }));
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

  protected setMergeChoice(id: number, value: string): void {
    this.mergeChoices.update((m) => new Map(m).set(id, value));
    this.clearRowError(id);
  }

  /**
   * Merge the person into the one chosen on their row (a steward only): their claims, aliases,
   * patrons and pool memberships go to the other and they leave the list, so the sheet is re-read.
   */
  protected async merge(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const intoId = Number(this.mergeChoices().get(p.id) ?? '');
    const fail = (err: string) => this.rowErrors.update((m) => new Map(m).set(p.id, err));
    if (!intoId) {
      fail('Choose a person to merge ' + p.name + ' into.');
      return;
    }
    if (intoId === p.id) {
      fail('A person cannot be merged into themselves.');
      return;
    }
    try {
      await firstValueFrom(
        this.http.post<Person>('/api/people/merge', { sheet, user: this.session.user(), id: p.id, intoId }),
      );
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      fail(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can merge people.'
          : e instanceof HttpErrorResponse && e.status === 409
            ? 'Neither person may be retired to merge ' + p.name + '.'
            : 'Could not merge ' + p.name + '.',
      );
      return;
    }
    if (this.session.sheet() !== sheet) return;
    this.mergeChoices.update((m) => {
      const next = new Map(m);
      next.delete(p.id);
      return next;
    });
    if (sheet) await this.fetch(sheet);
  }

  protected setTransferTo(id: number, value: string): void {
    this.transferTos.update((m) => new Map(m).set(id, value));
    this.clearRowError(id);
  }

  /**
   * Transfer the person to the sheet whose id is typed on their row (a steward only): they arrive
   * there with their grades, details and claims and leave this sheet, so the sheet is re-read.
   */
  protected async transfer(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const to = (this.transferTos().get(p.id) ?? '').trim();
    const fail = (err: string) => this.rowErrors.update((m) => new Map(m).set(p.id, err));
    if (to === '') {
      fail('Enter the id of the sheet to transfer ' + p.name + ' to.');
      return;
    }
    if (to === sheet) {
      fail(p.name + ' is already on this sheet.');
      return;
    }
    if (p.retired) {
      fail('A retired person cannot be transferred.');
      return;
    }
    try {
      await firstValueFrom(
        this.http.post<Person>('/api/people/transfer', { sheet, user: this.session.user(), id: p.id, to }),
      );
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      fail(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can transfer people.'
          : e instanceof HttpErrorResponse && e.status === 409
            ? 'Could not transfer ' + p.name + ': a name, alias or contact is already on sheet ' + to + ', or ' + p.name + ' is retired.'
            : 'Could not transfer ' + p.name + '.',
      );
      return;
    }
    if (this.session.sheet() !== sheet) return;
    this.transferTos.update((m) => {
      const next = new Map(m);
      next.delete(p.id);
      return next;
    });
    if (sheet) await this.fetch(sheet);
  }

  /**
   * Undo the transfer that brought the person to this sheet (a steward, on the sheet's today they
   * arrived): they go back to the sheet they came from, so the sheet is re-read.
   */
  protected async undoTransfer(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    try {
      await firstValueFrom(
        this.http.post<Person>('/api/people/transfer/undo', { sheet, user: this.session.user(), id: p.id }),
      );
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) =>
        new Map(m).set(
          p.id,
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Only a steward can undo a transfer.'
            : e instanceof HttpErrorResponse && e.status === 409
              ? 'Could not undo the transfer of ' + p.name + ': they did not arrive by transfer today, or cannot go back.'
              : 'Could not undo the transfer of ' + p.name + '.',
        ),
      );
      return;
    }
    if (this.session.sheet() !== sheet) return;
    if (sheet) await this.fetch(sheet);
  }

  /** Swap the person with the one above or below them, then re-read the list in its new order. */
  protected async move(p: Person, direction: 'up' | 'down'): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    try {
      await firstValueFrom(
        this.http.post<Person>('/api/people/move', { sheet, user: this.session.user(), id: p.id, direction }),
      );
      const list = await firstValueFrom(this.http.post<Person[]>('/api/people/list', { sheet }));
      if (this.session.sheet() !== sheet) return;
      // the move bumps the version of each person it renumbers; an open editor follows only that
      const was = this.people().find((x) => x.id === this.editingId());
      const now = list.find((x) => x.id === this.editingId());
      if (was && now && this.editingVersion === was.version) this.editingVersion = now.version;
      this.people.set(list);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) =>
        new Map(m).set(p.id, 'Could not move ' + p.name + ' ' + direction + ': they are already at the ' + (direction === 'up' ? 'top.' : 'bottom.')),
      );
    }
  }

  protected setInlineGrade(id: number, grade: string): void {
    this.inlineGrades.update((m) => new Map(m).set(id, grade));
    this.clearRowError(id);
  }

  /**
   * Record the grade chosen on the person's row from the sheet's today, saving the person exactly as
   * the person form would with their other details as the row holds them.
   */
  protected async saveInlineGrade(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const grade = this.inlineGrades().get(p.id) ?? this.gradeToday(p);
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>('/api/people/update', {
          sheet,
          user: this.session.user(),
          id: p.id,
          version: p.version,
          name: p.name,
          grade,
          gradeFrom: null,
          terms: p.terms,
          contact: p.contact,
          tags: p.tags.join(', '),
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      this.inlineGrades.update((m) => {
        const next = new Map(m);
        next.delete(p.id);
        return next;
      });
      if (this.editingId() === saved.id && this.editingVersion === p.version) this.editingVersion = saved.version;
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Could not save the grade of ' + p.name + '.'));
    }
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

  /** Unretire the person on the sheet's today; the retirement stays on the row as a past one. */
  protected async unretire(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>('/api/people/unretire', { sheet, user: this.session.user(), id: p.id }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editingId() === saved.id && this.editingVersion === p.version) this.editingVersion = saved.version;
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Could not unretire ' + p.name + '.'));
    }
  }

  protected setAliasInput(id: number, alias: string): void {
    this.aliasInputs.update((m) => new Map(m).set(id, alias));
    this.clearRowError(id);
  }

  /** Give the person the alias typed on their row. */
  protected async addAlias(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const alias = (this.aliasInputs().get(p.id) ?? '').trim();
    if (alias === '') {
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Enter an alias for ' + p.name + '.'));
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>('/api/people/aliases/add', { sheet, user: this.session.user(), id: p.id, alias }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      this.aliasInputs.update((m) => new Map(m).set(p.id, ''));
      if (this.editingId() === saved.id && this.editingVersion === p.version) this.editingVersion = saved.version;
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) =>
        new Map(m).set(
          p.id,
          e instanceof HttpErrorResponse && e.status === 409
            ? 'That alias is already a name or alias on this sheet.'
            : 'Could not add an alias for ' + p.name + '.',
        ),
      );
    }
  }

  protected setFlagReason(id: number, reason: string): void {
    this.flagReasons.update((m) => new Map(m).set(id, reason));
    this.clearRowError(id);
  }

  protected setFlagUntil(id: number, date: string): void {
    this.flagUntils.update((m) => new Map(m).set(id, date));
    this.clearRowError(id);
  }

  /** Flag the person with the reason on their row until its date (a steward only). */
  protected async flag(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const reason = (this.flagReasons().get(p.id) ?? '').trim();
    const until = (this.flagUntils().get(p.id) ?? '').trim();
    if (reason === '' || !/^\d{4}-\d{2}-\d{2}$/.test(until)) {
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Enter a reason and an until date to flag ' + p.name + '.'));
      return;
    }
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>('/api/people/flag', { sheet, user: this.session.user(), id: p.id, reason, until }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editingId() === saved.id && this.editingVersion === p.version) this.editingVersion = saved.version;
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) =>
        new Map(m).set(
          p.id,
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Only a steward can flag ' + p.name + '.'
            : 'Could not flag ' + p.name + '.',
        ),
      );
    }
  }

  protected setApproverInput(id: number, approver: string): void {
    this.approverInputs.update((m) => new Map(m).set(id, approver));
    this.clearRowError(id);
  }

  /** Give the person the approver typed on their row (blank: none). */
  protected async saveApprover(p: Person): Promise<void> {
    this.clearRowError(p.id);
    const sheet = this.session.sheet();
    const approver = (this.approverInputs().get(p.id) ?? '').trim();
    try {
      const saved = await firstValueFrom(
        this.http.post<Person>('/api/people/approver', { sheet, user: this.session.user(), id: p.id, approver }),
      );
      if (this.session.sheet() !== sheet) return;
      this.people.update((list) => list.map((x) => (x.id === saved.id ? saved : x)));
      if (this.editingId() === saved.id && this.editingVersion === p.version) this.editingVersion = saved.version;
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.rowErrors.update((m) => new Map(m).set(p.id, 'Could not set the approver of ' + p.name + '.'));
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
   * (or unbound) as it left them. The date is cleared whether it is taken or refused, so a date typed
   * for a refused statement never carries into the next one.
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
      this.statementAsOfs.update((m) => new Map(m).set(p.id, ''));
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

  protected plansOf(personId: number): Plan[] {
    return this.plans().filter((pl) => pl.personId === personId);
  }

  protected planAdded(plan: Plan): void {
    this.plans.update((list) => [...list, plan]);
  }

  /** Make the DRAFT claims the sheet's plans are due: the server adds them in order, and the
   *  screen lists each as its person's newest claim and shows how many were made. */
  protected async generatePlans(): Promise<void> {
    if (this.planGenerating) return;
    this.planGenerated.set(null);
    this.planGenerateError.set(null);
    const sheet = this.session.sheet();
    this.planGenerating = true;
    try {
      const done = await firstValueFrom(
        this.http.post<{ made: number; claims: Claim[]; plans: Plan[] }>('/api/plans/generate', {
          sheet,
          user: this.session.user(),
        }),
      );
      if (this.session.sheet() !== sheet) return;
      for (const c of done.claims) this.claimSaved(c);
      this.plans.set(done.plans);
      this.planGenerated.set(done.made);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.planGenerateError.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role may not generate plan claims.'
          : 'Could not generate the plan claims.',
      );
    } finally {
      this.planGenerating = false;
    }
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

  /** Settle the selected claims together, all or nothing: the server settles each in list order by
   *  the rules of settling one (a clerk's above the limit goes PENDING), or, when any one would be
   *  refused, settles none and counts every one refused. */
  protected async bulkSettle(): Promise<void> {
    this.bulkSettleResult.set(null);
    this.bulkSettleError.set(null);
    const sheet = this.session.sheet();
    const ids = [...this.selectedClaims()];
    try {
      const done = await firstValueFrom(
        this.http.post<{ settled: number; refused: number; claims: Claim[]; lastBatch: number | null }>(
          '/api/claims/bulk-settle', {
          sheet,
          user: this.session.user(),
          ids,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      const byId = new Map(done.claims.map((c) => [c.id, c]));
      this.claims.update((list) => list.map((c) => byId.get(c.id) ?? c));
      this.selectedClaims.set(new Set());
      this.session.lastBatch.set(done.lastBatch);
      this.bulkSettleResult.set({ settled: done.settled, refused: done.refused });
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkSettleError.set('Could not settle the selected claims.');
    }
  }

  /** Move the selected claims to the chosen person together: the server moves each it can, in list
   *  order, by the rules of moving one, and refuses the rest, leaving them as they are. */
  protected async bulkReassign(): Promise<void> {
    this.bulkReassignResult.set(null);
    this.bulkReassignError.set(null);
    const sheet = this.session.sheet();
    const personId = Number(this.bulkReassignTo());
    if (!personId) {
      this.bulkReassignError.set('Choose the person to move the selected claims to.');
      return;
    }
    const ids = [...this.selectedClaims()];
    try {
      const done = await firstValueFrom(
        this.http.post<{ reassigned: number; refused: number; claims: Claim[] }>('/api/claims/bulk-reassign', {
          sheet,
          user: this.session.user(),
          ids,
          personId,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      for (const c of done.claims) this.claimMoved(c);
      this.selectedClaims.set(new Set());
      this.bulkReassignResult.set({ reassigned: done.reassigned, refused: done.refused });
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkReassignError.set('Could not move the selected claims.');
    }
  }

  /** Re-date the selected claims to the date typed together: the server re-dates each it can, in
   *  list order, by the rules of re-dating one, and refuses the rest, leaving them as they are. */
  protected async bulkRedate(): Promise<void> {
    this.bulkRedateResult.set(null);
    this.bulkRedateError.set(null);
    const sheet = this.session.sheet();
    const date = this.bulkRedateDate().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.bulkRedateError.set('Enter the date to re-date the selected claims to.');
      return;
    }
    const ids = [...this.selectedClaims()];
    try {
      const done = await firstValueFrom(
        this.http.post<{ redated: number; refused: number; claims: Claim[] }>('/api/claims/bulk-redate', {
          sheet,
          user: this.session.user(),
          ids,
          date,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      for (const c of done.claims) this.claimSaved(c);
      this.selectedClaims.set(new Set());
      this.bulkRedateResult.set({ redated: done.redated, refused: done.refused });
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkRedateError.set('Could not re-date the selected claims.');
    }
  }

  /** Void the selected claims together with the reason chosen: the server voids each it can, in
   *  list order, by the rules of voiding one, and refuses the rest, leaving them as they are. */
  protected async bulkVoid(): Promise<void> {
    this.bulkVoidResult.set(null);
    this.bulkVoidError.set(null);
    const sheet = this.session.sheet();
    const reason = this.bulkVoidReason();
    const ids = [...this.selectedClaims()];
    try {
      const done = await firstValueFrom(
        this.http.post<{ voided: number; refused: number; claims: Claim[] }>('/api/claims/bulk-void', {
          sheet,
          user: this.session.user(),
          ids,
          ...(reason ? { reason } : {}),
        }),
      );
      if (this.session.sheet() !== sheet) return;
      for (const c of done.claims) this.claimSaved(c);
      this.selectedClaims.set(new Set());
      this.bulkVoidReason.set('');
      this.bulkVoidResult.set({ voided: done.voided, refused: done.refused });
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkVoidError.set('Could not void the selected claims.');
    }
  }

  protected selectPerson(id: number, on: boolean): void {
    this.selectedPeople.update((ids) => {
      const next = new Set(ids);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
    this.bulkGradeError.set(null);
    this.bulkRetireError.set(null);
    this.clearRowError(id);
  }

  /** Give the selected people the grade from the from-date (the sheet's today when blank): the
   *  server edits each, in list order, as the person form would; all or none. */
  protected async bulkGrade(event: Event): Promise<void> {
    event.preventDefault();
    this.bulkGraded.set(null);
    this.bulkGradeError.set(null);
    const sheet = this.session.sheet();
    const from = this.bulkGradeFrom().trim();
    const ids = this.people().filter((p) => this.selectedPeople().has(p.id)).map((p) => p.id);
    if (ids.length === 0) {
      this.bulkGradeError.set('Select at least one person.');
      return;
    }
    if (from !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.bulkGradeError.set('Enter a date, or leave it blank for the sheet\'s today.');
      return;
    }
    try {
      const done = await firstValueFrom(
        this.http.post<{ graded: number; people: Person[] }>('/api/people/bulk-grade', {
          sheet,
          user: this.session.user(),
          ids,
          grade: this.bulkGradeInput(),
          from: from || null,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      const byId = new Map(done.people.map((p) => [p.id, p]));
      this.people.update((list) => list.map((p) => byId.get(p.id) ?? p));
      this.selectedPeople.set(new Set());
      this.bulkGraded.set(done.graded);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkGradeError.set('Could not grade the selected people.');
    }
  }

  /** Retire the selected people from the date (the sheet's today when blank): the server retires
   *  each, in list order, as the row's retire would; all or none. */
  protected async bulkRetire(event: Event): Promise<void> {
    event.preventDefault();
    this.bulkRetired.set(null);
    this.bulkRetireError.set(null);
    const sheet = this.session.sheet();
    const date = this.bulkRetireDate().trim();
    const ids = this.people().filter((p) => this.selectedPeople().has(p.id)).map((p) => p.id);
    if (ids.length === 0) {
      this.bulkRetireError.set('Select at least one person.');
      return;
    }
    if (date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.bulkRetireError.set('Enter a date, or leave it blank for the sheet\'s today.');
      return;
    }
    try {
      const done = await firstValueFrom(
        this.http.post<{ retired: number; people: Person[] }>('/api/people/bulk-retire', {
          sheet,
          user: this.session.user(),
          ids,
          date: date || null,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      const byId = new Map(done.people.map((p) => [p.id, p]));
      this.people.update((list) => list.map((p) => byId.get(p.id) ?? p));
      this.selectedPeople.set(new Set());
      this.bulkRetired.set(done.retired);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.bulkRetireError.set('Could not retire the selected people.');
    }
  }

  /**
   * Import the pasted people: the server checks every line first, then updates each person a line
   * names as the person form would edit them, and adds each other as the person form would; all or none.
   */
  protected async importPeople(event: Event): Promise<void> {
    event.preventDefault();
    this.importAdded.set(null);
    this.importUpdated.set(null);
    this.importError.set(null);
    const sheet = this.session.sheet();
    try {
      const done = await firstValueFrom(
        this.http.post<{ added: number; updated: number; people: Person[] }>('/api/people/import', {
          sheet,
          user: this.session.user(),
          text: this.importText(),
        }),
      );
      if (this.session.sheet() !== sheet) return;
      const byId = new Map(done.people.map((p) => [p.id, p]));
      this.people.update((list) => [
        ...list.map((p) => byId.get(p.id) ?? p),
        ...done.people.filter((p) => !list.some((q) => q.id === p.id)),
      ]);
      this.importAdded.set(done.added);
      this.importUpdated.set(done.updated);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const reason = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' ? e.error.message : '';
      this.importError.set(reason || 'Could not import these people; nothing was added.');
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

  /** Add (or update a matching DRAFT) and open a claim per pasted line, in order; the server refuses the whole paste if any line fails. */
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
  protected async exportCsv(): Promise<void> {
    const sheet = this.session.sheet();
    await this.session.settled();
    if (this.session.sheet() !== sheet) return;
    const lines = ['name,grade,retired-from,due-total'];
    for (const p of this.people()) {
      lines.push([p.name, this.gradeToday(p), p.retiredFrom ?? '', dueText(this.dueOf(p))].map(csvField).join(','));
    }
    this.download('people', lines);
  }

  /** Download every claim, people in list order (retired included) and each one's oldest first, as a CSV file. */
  protected async exportClaims(): Promise<void> {
    const sheet = this.session.sheet();
    await this.session.settled();
    if (this.session.sheet() !== sheet) return;
    const lines = ['person,number,date,amount,due,status,period'];
    for (const p of this.people()) {
      for (const c of [...this.claimsOf(p.id)].reverse()) {
        const due = dueText(chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floors(), this.lateness(), this.roundOn(c)));
        const period = periodOn(this.periods(), c.dueBy)?.name ?? '';
        lines.push([p.name, c.number ?? '', c.date, c.amount, due, c.status, period].map(csvField).join(','));
      }
    }
    this.download('claims', lines);
  }

  /** Download the person's statement: each claim of theirs bound by their last statement, oldest first, with the bound figure as the claim shows it. */
  protected async exportStatement(p: Person): Promise<void> {
    const sheet = this.session.sheet();
    await this.session.settled();
    if (this.session.sheet() !== sheet) return;
    const lines = ['number,date,amount,figure,status'];
    for (const c of [...this.claimsOf(p.id)].reverse()) {
      if (!c.stated) continue;
      const figure = dueText(chargedDueCents(c, this.rates(), gradeOn(p.grades, c.date), this.floors(), this.lateness(), this.roundOn(c)));
      lines.push([c.number ?? '', c.date, c.amount, figure, c.status].map(csvField).join(','));
    }
    this.download('statement-' + p.id, lines);
  }

  /** Download CSV lines, each ended by a line feed, as <what>-<sheet>.csv. */
  private download(what: string, lines: string[]): void {
    const url = URL.createObjectURL(new Blob([lines.map((l) => l + '\n').join('')], { type: 'text/csv' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = what + '-' + (this.session.sheet() ?? 'sheet') + '.csv';
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
    this.termsFromInput.set('');
    this.contactInput.set(p.contact ?? '');
    this.tagsInput.set(p.tags.join(', '));
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
    this.termsFromInput.set('');
    this.contactInput.set('');
    this.tagsInput.set('');
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
    const termsFrom = this.termsFromInput().trim();
    const contact = this.contactInput().trim().toLowerCase();
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
    if (termsFrom !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(termsFrom)) {
      this.refuse('Enter a date the terms are from, or leave it blank for the beginning of time.');
      return;
    }
    const id = this.editingId();
    const body = {
      sheet,
      user: this.session.user(),
      name,
      grade,
      terms: terms === '' ? null : Number(terms),
      termsFrom: termsFrom || null,
      contact: contact === '' ? null : contact,
      tags: this.tagsInput(),
    };
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
        this.refuse('That name or contact is already in use on this sheet.');
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
        this.contactInput.set(current.contact ?? '');
        this.tagsInput.set(current.tags.join(', '));
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
