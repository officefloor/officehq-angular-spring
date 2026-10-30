import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, input, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface RoleRow {
  user: string;
  role: string;
}

interface Settings {
  /** The most due a clerk may settle, with two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** The least live due a claim has, with two decimals. */
  floor: string;
  /** The days a claim has to be paid by, from its date. */
  terms: string;
  /** The days a claim of grade A, B or C has to be paid by, from its date; null when the grade uses the sheet's terms. */
  termsA: string | null;
  termsB: string | null;
  termsC: string | null;
  /** The percent of its figure an overdue claim's due gains for every full 30 days overdue, with two decimals. */
  latePct: string;
  /** The date a claim's rounding turns on (half up before it, half to even on or after); null when blank (the sheet's today). */
  policyDate: string | null;
  /** From the sheet's today on this date, a claim settled is numbered within its period; null when blank (off). */
  periodNumberingFrom: string | null;
  /** The days after a period's to-date before a change of the sheet's today closes it; null when blank (off). */
  graceDays: string | null;
  /** While set, the date a new claim takes when none is given, in place of the sheet's today; null when blank (off). */
  entryDate: string | null;
  /** The sheet's pot, shared out over each balanced pool, with two decimals; null when there is none. Not dated. */
  pot: string | null;
  /** A person whose due-total goes above it is alerted (two decimals); null when blank (off). Not dated. */
  alertThreshold: string | null;
  /** A claim reminded at least this many days after its previous reminder is escalated; null when blank (off). Not dated. */
  escalateAfter: string | null;
  /** A REVIEW claim whose review day plus this many days is before the sheet's today is late; null when blank (none). Not dated. */
  reviewSla: string | null;
  /** The kinds of proof (receipt, photo), comma separated, a claim above the proof threshold needs one of each of; null when none. Not dated. */
  proofKinds: string | null;
  /** The grade quick-add and an import line with a blank grade give a new person; null when blank (A). Not dated. */
  defaultGrade: string | null;
  /** The id of the sheet's parent sheet, whose rates a grade with none in force takes; null when none. Not dated. */
  parent: string | null;
  /** The month (1 to 12) the sheet's fiscal year starts in; 1 unless set. Not dated. */
  fiscalStartMonth: string;
  /** The most a person's due-total comes to, with two decimals; null when there is no cap. */
  cap: string | null;
  /** The most due a claim may have to be settled or approved with no proof, with two decimals; null when there is none. */
  proofThreshold: string | null;
  /** The most due a claim may have to be opened with an empty note, with two decimals; null when there is none. */
  noteThreshold: string | null;
  /** A claim with a due above it goes to REVIEW when opened, with two decimals; null when there is none. */
  reviewThreshold: string | null;
  /** The roles that may open, settle, void, reassign, close or reopen, comma separated; null when blank (the standing rule). */
  permOpen: string | null;
  permSettle: string | null;
  permVoid: string | null;
  permReassign: string | null;
  permClose: string | null;
  permReopen: string | null;
  /** The days past a claim's due-by before a claim of grade A, B or C is overdue; null when the grade has none. */
  graceA: string | null;
  graceB: string | null;
  graceC: string | null;
  /** The most due a claim of grade A, B or C may have to be settled or approved with no proof; null when the grade has none (it uses the sheet's). */
  proofThresholdA: string | null;
  proofThresholdB: string | null;
  proofThresholdC: string | null;
  /** How a claim's figures round: half-up or half-even; null when none (the policy date decides). */
  rounding: string | null;
  /** The most due a clerk may settle on a claim of grade A, B or C; null when the grade has none (it uses the sheet's). */
  clerkLimitA: string | null;
  clerkLimitB: string | null;
  clerkLimitC: string | null;
}

/** The steward role delegated by a steward (by) to a user, from a from-date to a to-date (yyyy-mm-dd), inclusive. */
interface DelegationRow {
  id: number;
  user: string;
  by: string;
  from: string;
  to: string;
}

interface AccessView {
  roles: RoleRow[];
  settings: Settings;
  delegations: DelegationRow[];
}

const ROLES = ['clerk', 'steward', 'viewer', 'restricted'];
const GRADES = ['A', 'B', 'C'] as const;
type Grade = (typeof GRADES)[number];
const GRADE_TERMS = { A: 'termsA', B: 'termsB', C: 'termsC' } as const;
const GRADE_GRACE = { A: 'graceA', B: 'graceB', C: 'graceC' } as const;
const GRADE_PROOF = { A: 'proofThresholdA', B: 'proofThresholdB', C: 'proofThresholdC' } as const;
const GRADE_LIMIT = { A: 'clerkLimitA', B: 'clerkLimitB', C: 'clerkLimitC' } as const;
/** The settings that carry a figure: each is saved from a from-date. */
const DATED = ['clerkLimit', 'floor', 'terms', 'termsA', 'termsB', 'termsC', 'latePct', 'cap', 'proofThreshold', 'noteThreshold', 'reviewThreshold', 'graceA', 'graceB', 'graceC', 'proofThresholdA', 'proofThresholdB', 'proofThresholdC', 'rounding', 'clerkLimitA', 'clerkLimitB', 'clerkLimitC'] as const;
type Dated = (typeof DATED)[number];
const NO_FROM: Record<Dated, string> = { clerkLimit: '', floor: '', terms: '', termsA: '', termsB: '', termsC: '', latePct: '', cap: '', proofThreshold: '', noteThreshold: '', reviewThreshold: '', graceA: '', graceB: '', graceC: '', proofThresholdA: '', proofThresholdB: '', proofThresholdC: '', rounding: '', clerkLimitA: '', clerkLimitB: '', clerkLimitC: '' };
/** The permission matrix: each action with its setting and the roles that take it when left blank. */
const PERMS = [
  { action: 'open', field: 'permOpen', standing: 'clerk, steward' },
  { action: 'settle', field: 'permSettle', standing: 'clerk, steward' },
  { action: 'void', field: 'permVoid', standing: 'clerk, steward' },
  { action: 'reassign', field: 'permReassign', standing: 'steward' },
  { action: 'close', field: 'permClose', standing: 'clerk, steward' },
  { action: 'reopen', field: 'permReopen', standing: 'steward' },
] as const;
type Perm = (typeof PERMS)[number]['field'];
const NO_PERMS: Record<Perm, string> = { permOpen: '', permSettle: '', permVoid: '', permReassign: '', permClose: '', permReopen: '' };
const NO_PERMS_TOUCHED: Record<Perm, boolean> = { permOpen: false, permSettle: false, permVoid: false, permReassign: false, permClose: false, permReopen: false };
const PERM_ROLES = /^\s*(clerk|steward)\s*(,\s*(clerk|steward)\s*)*$/;
const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;
const DAYS = /^\d{1,5}$/;
/** Proof kinds: a comma separated list of receipt and photo. */
const PROOF_KINDS = /^\s*(receipt|photo)\s*(,\s*(receipt|photo)\s*)*$/;
const MONTH = /^([1-9]|0[1-9]|1[0-2])$/;
const PERCENT = /^\d{1,10}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const ROUNDING = /^(half-up|half-even)$/;

// The sheet's roles (any name without one is a clerk) with the form that sets one, and the
// sheet's settings: the clerk limit, the most due a clerk may settle (blank: no limit), and the floor,
// the least live due a claim has (blank: 1.00), and the terms, the days a claim has to be paid by
// from its date (blank: 14), and each grade's own terms (blank: the sheet's terms), and the late percent, what an overdue claim's due gains of its figure for
// every full 30 days overdue (blank: 0.00), and the policy date, the date a claim's rounding turns on
// (blank: the sheet's today), and the rounding mode, how a claim's figures round, half-up or half-even, overriding the
// policy date for a claim whose date has one in force (blank: none), and the period numbering from, the date from which a claim opened is
// numbered within its period (blank: off), and the grace days, the days after an open period's to-date
// before a change of the sheet's today closes it (blank: off; not dated), and each grade's grace days, the days
// past a claim's due-by before it is overdue and its late charge counts from (blank: none), and the entry date, the date a
// new claim given no date takes in place of the sheet's today (blank: off; not dated), and the cap, the most a person's due-total comes to (blank: no
// cap), and the proof threshold, the most due a claim may have to be settled or approved with no proof
// (blank: none), and each grade's own proof threshold, used for its claims in place of the sheet's (blank: none), and the permission matrix, the roles that may open, settle, void, reassign, close and
// reopen, each a comma separated list (blank: the standing rule; not dated). The settings that carry a figure are dated: each is saved from its from-date (blank: the sheet's
// today), and each shows, as does its input, the value in force on the sheet's today. A save sends only
// the settings whose input (or from-date) was changed (a Delete or Backspace in a blank input asks to
// save it blank).
@Component({
  selector: 'app-access',
  imports: [FigurePipe],
  template: `
    <section aria-label="Roles">
      <h2>Roles</h2>
      <p>Anyone without a role is a clerk. A viewer sees everything and can change nothing. A restricted user is listed only the people of the crews they lead or belong to, and themselves.</p>
      <ul aria-label="Roles set">
        @for (r of roles(); track r.user) {
          <li [attr.data-testid]="'role-row-' + r.user">
            <span data-testid="role-user">{{ r.user }}</span>:
            <span data-testid="role-value">{{ r.role }}</span>
          </li>
        }
      </ul>
      <form (submit)="saveRole($event)" aria-label="Set role">
        <label for="role-form-user">User name</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="roleUser()"
          (input)="roleUser.set(value($event)); roleError.set('')"
        />
        <label for="role-form-role">Role (clerk, steward, viewer or restricted)</label>
        <input
          id="role-form-role"
          data-testid="role-form-role"
          list="role-form-roles"
          [value]="role()"
          (input)="role.set(value($event)); roleError.set('')"
        />
        <datalist id="role-form-roles">
          @for (r of roleNames; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError()) {
          <p role="alert" data-testid="role-form-error">{{ roleError() }}</p>
        }
      </form>
    </section>
    <section aria-label="Delegations">
      <h2>Delegations</h2>
      <p>A steward can delegate the steward role to a user from a from-date to a to-date, inclusive, by the sheet's today.</p>
      <ul aria-label="Delegations made">
        @for (d of delegations(); track d.id) {
          <li data-testid="delegate-line">
            <span data-testid="delegate-line-user">{{ d.user }}</span> acts as steward for
            <span data-testid="delegate-line-by">{{ d.by }}</span> from
            <span data-testid="delegate-line-from">{{ d.from }}</span> to
            <span data-testid="delegate-line-to">{{ d.to }}</span>
          </li>
        }
      </ul>
      <form (submit)="delegate($event)" aria-label="Delegate the steward role">
        <label for="delegate-user">User name</label>
        <input
          id="delegate-user"
          data-testid="delegate-user"
          [value]="delegateUser()"
          (input)="delegateUser.set(value($event)); delegateError.set('')"
        />
        <label for="delegate-from">From (blank: the sheet's today)</label>
        <input
          id="delegate-from"
          type="date"
          data-testid="delegate-from"
          [value]="delegateFrom()"
          (input)="delegateFrom.set(value($event)); delegateError.set('')"
        />
        <label for="delegate-to">To</label>
        <input
          id="delegate-to"
          type="date"
          data-testid="delegate-to"
          [value]="delegateTo()"
          (input)="delegateTo.set(value($event)); delegateError.set('')"
        />
        <button type="submit" data-testid="delegate-submit">Delegate</button>
        @if (delegateError()) {
          <p role="alert" data-testid="delegate-error">{{ delegateError() }}</p>
        }
      </form>
    </section>
    <section aria-label="Settings">
      <h2>Settings</h2>
      <p>Clerk limit <strong data-testid="setting-clerk-limit-current">{{ (clerkLimit() ?? '') | figure }}</strong></p>
      @for (g of grades; track g) {
        <p>Clerk limit for grade {{ g }} <strong [attr.data-testid]="'setting-clerk-limit-' + g + '-current'">{{ (gradeLimit()[g] ?? '') | figure }}</strong></p>
      }
      <p>Floor <strong data-testid="setting-floor-current">{{ floor() | figure }}</strong></p>
      <p>Terms (days) <strong data-testid="setting-terms-current">{{ terms() }}</strong></p>
      @for (g of grades; track g) {
        <p>Terms for grade {{ g }} (days) <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ gradeTerms()[g] ?? '' }}</strong></p>
      }
      <p>Late percent <strong data-testid="setting-late-pct-current">{{ latePct() | figure }}</strong></p>
      <p>Rounding mode <strong data-testid="setting-rounding-current">{{ rounding() ?? '' }}</strong></p>
      <p>Policy date <strong data-testid="setting-policy-date-current">{{ policyDate() ?? '' }}</strong></p>
      <p>Period numbering from <strong data-testid="setting-period-numbering-from-current">{{ periodNumberingFrom() ?? '' }}</strong></p>
      <p>Grace days <strong data-testid="setting-grace-days-current">{{ graceDays() ?? '' }}</strong></p>
      @for (g of grades; track g) {
        <p>Grace days for grade {{ g }} <strong [attr.data-testid]="'setting-grace-' + g + '-current'">{{ gradeGrace()[g] ?? '' }}</strong></p>
      }
      <p>Entry date <strong data-testid="setting-entry-date-current">{{ entryDate() ?? '' }}</strong></p>
      <p>Pot <strong data-testid="setting-pot-current">{{ (pot() ?? '') | figure }}</strong></p>
      <p>Alert threshold <strong data-testid="setting-alert-threshold-current">{{ (alertThreshold() ?? '') | figure }}</strong></p>
      <p>Escalate after (days) <strong data-testid="setting-escalate-after-current">{{ escalateAfter() ?? '' }}</strong></p>
      <p>Review SLA (days) <strong data-testid="setting-review-sla-current">{{ reviewSla() ?? '' }}</strong></p>
      <p>Proof kinds <strong data-testid="setting-proof-kinds-current">{{ proofKinds() ?? '' }}</strong></p>
      <p>Default grade <strong data-testid="setting-default-grade-current">{{ defaultGrade() ?? '' }}</strong></p>
      <p>Parent sheet <strong data-testid="setting-parent-current">{{ parent() ?? '' }}</strong></p>
      <p>Fiscal year start month <strong data-testid="setting-fiscal-start-month-current">{{ fiscalStartMonth() }}</strong></p>
      <p>Cap <strong data-testid="setting-cap-current">{{ (cap() ?? '') | figure }}</strong></p>
      <p>Proof threshold <strong data-testid="setting-proof-threshold-current">{{ (proofThreshold() ?? '') | figure }}</strong></p>
      @for (g of grades; track g) {
        <p>Proof threshold for grade {{ g }} <strong [attr.data-testid]="'setting-proof-threshold-' + g + '-current'">{{ gradeProof()[g] ?? '' }}</strong></p>
      }
      <p>Note threshold <strong data-testid="setting-note-threshold-current">{{ (noteThreshold() ?? '') | figure }}</strong></p>
      <p>Review threshold <strong data-testid="setting-review-threshold-current">{{ (reviewThreshold() ?? '') | figure }}</strong></p>
      @for (p of perms; track p.field) {
        <p>Roles that may {{ p.action }} <strong [attr.data-testid]="'setting-perm-' + p.action + '-current'">{{ perm()[p.field] ?? '' }}</strong></p>
      }
      <form (submit)="saveSettings($event)" aria-label="Save settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input
          id="setting-clerk-limit"
          inputmode="decimal"
          data-testid="setting-clerk-limit"
          [value]="limitInput()"
          (input)="limitInput.set(value($event)); limitTouched = true; settingsError.set('')"
          (keydown)="limitTouched = limitTouched || clears($event)"
        />
        <label for="setting-clerk-limit-from">Clerk limit in force from (blank: the sheet's today)</label>
        <input
          id="setting-clerk-limit-from"
          type="date"
          data-testid="setting-clerk-limit-from"
          [value]="fromInput().clerkLimit"
          (input)="setFrom('clerkLimit', value($event)); limitTouched = true; settingsError.set('')"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-clerk-limit-' + g">Clerk limit for grade {{ g }}: used for its claims in place of the sheet's (blank: none)</label>
          <input
            [id]="'setting-clerk-limit-' + g"
            inputmode="decimal"
            [attr.data-testid]="'setting-clerk-limit-' + g"
            [value]="gradeLimitInput()[g]"
            (input)="setGradeLimitInput(g, value($event)); gradeLimitTouched[g] = true; settingsError.set('')"
            (keydown)="gradeLimitTouched[g] = gradeLimitTouched[g] || clears($event)"
          />
          <label [for]="'setting-clerk-limit-' + g + '-from'">Clerk limit for grade {{ g }} in force from (blank: the sheet's today)</label>
          <input
            [id]="'setting-clerk-limit-' + g + '-from'"
            type="date"
            [attr.data-testid]="'setting-clerk-limit-' + g + '-from'"
            [value]="fromInput()[GRADE_LIMIT[g]]"
            (input)="setFrom(GRADE_LIMIT[g], value($event)); gradeLimitTouched[g] = true; settingsError.set('')"
          />
        }
        <label for="setting-floor">Floor (blank: 1.00)</label>
        <input
          id="setting-floor"
          inputmode="decimal"
          data-testid="setting-floor"
          [value]="floorInput()"
          (input)="floorInput.set(value($event)); floorTouched = true; settingsError.set('')"
          (keydown)="floorTouched = floorTouched || clears($event)"
        />
        <label for="setting-floor-from">Floor in force from (blank: the sheet's today)</label>
        <input
          id="setting-floor-from"
          type="date"
          data-testid="setting-floor-from"
          [value]="fromInput().floor"
          (input)="setFrom('floor', value($event)); floorTouched = true; settingsError.set('')"
        />
        <label for="setting-terms">Terms in days (blank: 14)</label>
        <input
          id="setting-terms"
          inputmode="numeric"
          data-testid="setting-terms"
          [value]="termsInput()"
          (input)="termsInput.set(value($event)); termsTouched = true; settingsError.set('')"
          (keydown)="termsTouched = termsTouched || clears($event)"
        />
        <label for="setting-terms-from">Terms in force from (blank: the sheet's today)</label>
        <input
          id="setting-terms-from"
          type="date"
          data-testid="setting-terms-from"
          [value]="fromInput().terms"
          (input)="setFrom('terms', value($event)); termsTouched = true; settingsError.set('')"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} in days (blank: the sheet's terms)</label>
          <input
            [id]="'setting-terms-' + g"
            inputmode="numeric"
            [attr.data-testid]="'setting-terms-' + g"
            [value]="gradeTermsInput()[g]"
            (input)="setGradeTermsInput(g, value($event)); gradeTermsTouched[g] = true; settingsError.set('')"
            (keydown)="gradeTermsTouched[g] = gradeTermsTouched[g] || clears($event)"
          />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} in force from (blank: the sheet's today)</label>
          <input
            [id]="'setting-terms-' + g + '-from'"
            type="date"
            [attr.data-testid]="'setting-terms-' + g + '-from'"
            [value]="fromInput()[GRADE_TERMS[g]]"
            (input)="setFrom(GRADE_TERMS[g], value($event)); gradeTermsTouched[g] = true; settingsError.set('')"
          />
        }
        <label for="setting-late-pct">Late percent per full 30 days overdue (blank: 0.00)</label>
        <input
          id="setting-late-pct"
          inputmode="decimal"
          data-testid="setting-late-pct"
          [value]="latePctInput()"
          (input)="latePctInput.set(value($event)); latePctTouched = true; settingsError.set('')"
          (keydown)="latePctTouched = latePctTouched || clears($event)"
        />
        <label for="setting-late-pct-from">Late percent in force from (blank: the sheet's today)</label>
        <input
          id="setting-late-pct-from"
          type="date"
          data-testid="setting-late-pct-from"
          [value]="fromInput().latePct"
          (input)="setFrom('latePct', value($event)); latePctTouched = true; settingsError.set('')"
        />
        <label for="setting-rounding">Rounding mode: half-up or half-even (blank: none, the policy date decides)</label>
        <input
          id="setting-rounding"
          data-testid="setting-rounding"
          [value]="roundingInput()"
          (input)="roundingInput.set(value($event)); roundingTouched = true; settingsError.set('')"
          (keydown)="roundingTouched = roundingTouched || clears($event)"
        />
        <label for="setting-rounding-from">Rounding mode in force from (blank: the sheet's today)</label>
        <input
          id="setting-rounding-from"
          type="date"
          data-testid="setting-rounding-from"
          [value]="fromInput().rounding"
          (input)="setFrom('rounding', value($event)); roundingTouched = true; settingsError.set('')"
        />
        <label for="setting-policy-date">Policy date: claims before it round half up, on or after it half to even (blank: the sheet's today)</label>
        <input
          id="setting-policy-date"
          type="date"
          data-testid="setting-policy-date"
          [value]="policyDateInput()"
          (input)="policyDateInput.set(value($event)); policyDateTouched = true; settingsError.set('')"
          (keydown)="policyDateTouched = policyDateTouched || clears($event)"
        />
        <label for="setting-period-numbering-from">Period numbering from: claims settled from this date are numbered within their period (blank: off)</label>
        <input
          id="setting-period-numbering-from"
          type="date"
          data-testid="setting-period-numbering-from"
          [value]="numberingFromInput()"
          (input)="numberingFromInput.set(value($event)); numberingFromTouched = true; settingsError.set('')"
          (keydown)="numberingFromTouched = numberingFromTouched || clears($event)"
        />
        <label for="setting-grace-days">Grace days: an open period closes once the sheet's today is past its to-date by more than these days (blank: off)</label>
        <input
          id="setting-grace-days"
          inputmode="numeric"
          data-testid="setting-grace-days"
          [value]="graceDaysInput()"
          (input)="graceDaysInput.set(value($event)); graceDaysTouched = true; settingsError.set('')"
          (keydown)="graceDaysTouched = graceDaysTouched || clears($event)"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-grace-' + g">Grace days for grade {{ g }}: a claim is overdue only once its due-by plus these days has passed (blank: none)</label>
          <input
            [id]="'setting-grace-' + g"
            inputmode="numeric"
            [attr.data-testid]="'setting-grace-' + g"
            [value]="gradeGraceInput()[g]"
            (input)="setGradeGraceInput(g, value($event)); gradeGraceTouched[g] = true; settingsError.set('')"
            (keydown)="gradeGraceTouched[g] = gradeGraceTouched[g] || clears($event)"
          />
          <label [for]="'setting-grace-' + g + '-from'">Grace days for grade {{ g }} in force from (blank: the sheet's today)</label>
          <input
            [id]="'setting-grace-' + g + '-from'"
            type="date"
            [attr.data-testid]="'setting-grace-' + g + '-from'"
            [value]="fromInput()[GRADE_GRACE[g]]"
            (input)="setFrom(GRADE_GRACE[g], value($event)); gradeGraceTouched[g] = true; settingsError.set('')"
          />
        }
        <label for="setting-entry-date">Entry date: a new claim given no date takes it in place of the sheet's today (blank: off)</label>
        <input
          id="setting-entry-date"
          type="date"
          data-testid="setting-entry-date"
          [value]="entryDateInput()"
          (input)="entryDateInput.set(value($event)); entryDateTouched = true; settingsError.set('')"
          (keydown)="entryDateTouched = entryDateTouched || clears($event)"
        />
        <label for="setting-pot">Pot shared out over each balanced pool (blank: none)</label>
        <input
          id="setting-pot"
          inputmode="decimal"
          data-testid="setting-pot"
          [value]="potInput()"
          (input)="potInput.set(value($event)); potTouched = true; settingsError.set('')"
          (keydown)="potTouched = potTouched || clears($event)"
        />
        <label for="setting-alert-threshold">Alert when a person's due-total goes above (blank: off)</label>
        <input
          id="setting-alert-threshold"
          inputmode="decimal"
          data-testid="setting-alert-threshold"
          [value]="alertThresholdInput()"
          (input)="alertThresholdInput.set(value($event)); alertThresholdTouched = true; settingsError.set('')"
          (keydown)="alertThresholdTouched = alertThresholdTouched || clears($event)"
        />
        <label for="setting-escalate-after">Escalate a claim reminded at least this many days after its previous reminder (blank: off)</label>
        <input
          id="setting-escalate-after"
          inputmode="numeric"
          data-testid="setting-escalate-after"
          [value]="escalateAfterInput()"
          (input)="escalateAfterInput.set(value($event)); escalateAfterTouched = true; settingsError.set('')"
          (keydown)="escalateAfterTouched = escalateAfterTouched || clears($event)"
        />
        <label for="setting-review-sla">Review SLA: a claim in review more than this many days is late (blank: none)</label>
        <input
          id="setting-review-sla"
          inputmode="numeric"
          data-testid="setting-review-sla"
          [value]="reviewSlaInput()"
          (input)="reviewSlaInput.set(value($event)); reviewSlaTouched = true; settingsError.set('')"
          (keydown)="reviewSlaTouched = reviewSlaTouched || clears($event)"
        />
        <label for="setting-proof-kinds">Proof kinds: a claim above the proof threshold needs a proof of each (comma separated, receipt or photo; blank: none)</label>
        <input
          id="setting-proof-kinds"
          data-testid="setting-proof-kinds"
          [value]="proofKindsInput()"
          (input)="proofKindsInput.set(value($event)); proofKindsTouched = true; settingsError.set('')"
          (keydown)="proofKindsTouched = proofKindsTouched || clears($event)"
        />
        <label for="setting-default-grade">Default grade a quick-added or imported person without one gets: A, B or C (blank: A)</label>
        <input
          id="setting-default-grade"
          data-testid="setting-default-grade"
          [value]="defaultGradeInput()"
          (input)="defaultGradeInput.set(value($event)); defaultGradeTouched = true; settingsError.set('')"
          (keydown)="defaultGradeTouched = defaultGradeTouched || clears($event)"
        />
        <label for="setting-parent">Parent sheet id: its rates are used for a grade with none in force (blank: none)</label>
        <input
          id="setting-parent"
          data-testid="setting-parent"
          [value]="parentInput()"
          (input)="parentInput.set(value($event)); parentTouched = true; settingsError.set('')"
          (keydown)="parentTouched = parentTouched || clears($event)"
        />
        <label for="setting-fiscal-start-month">Fiscal year start month, 1 to 12 (blank: 1)</label>
        <input
          id="setting-fiscal-start-month"
          inputmode="numeric"
          data-testid="setting-fiscal-start-month"
          [value]="fiscalStartMonthInput()"
          (input)="fiscalStartMonthInput.set(value($event)); fiscalStartMonthTouched = true; settingsError.set('')"
          (keydown)="fiscalStartMonthTouched = fiscalStartMonthTouched || clears($event)"
        />
        <label for="setting-cap">Cap on a person's due-total (blank: no cap)</label>
        <input
          id="setting-cap"
          inputmode="decimal"
          data-testid="setting-cap"
          [value]="capInput()"
          (input)="capInput.set(value($event)); capTouched = true; settingsError.set('')"
          (keydown)="capTouched = capTouched || clears($event)"
        />
        <label for="setting-cap-from">Cap in force from (blank: the sheet's today)</label>
        <input
          id="setting-cap-from"
          type="date"
          data-testid="setting-cap-from"
          [value]="fromInput().cap"
          (input)="setFrom('cap', value($event)); capTouched = true; settingsError.set('')"
        />
        <label for="setting-proof-threshold">Proof threshold: a claim with a due above it needs a proof to be settled (blank: none)</label>
        <input
          id="setting-proof-threshold"
          inputmode="decimal"
          data-testid="setting-proof-threshold"
          [value]="proofThresholdInput()"
          (input)="proofThresholdInput.set(value($event)); proofThresholdTouched = true; settingsError.set('')"
          (keydown)="proofThresholdTouched = proofThresholdTouched || clears($event)"
        />
        <label for="setting-proof-threshold-from">Proof threshold in force from (blank: the sheet's today)</label>
        <input
          id="setting-proof-threshold-from"
          type="date"
          data-testid="setting-proof-threshold-from"
          [value]="fromInput().proofThreshold"
          (input)="setFrom('proofThreshold', value($event)); proofThresholdTouched = true; settingsError.set('')"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-proof-threshold-' + g">Proof threshold for grade {{ g }}: used for its claims in place of the sheet's (blank: none)</label>
          <input
            [id]="'setting-proof-threshold-' + g"
            inputmode="decimal"
            [attr.data-testid]="'setting-proof-threshold-' + g"
            [value]="gradeProofInput()[g]"
            (input)="setGradeProofInput(g, value($event)); gradeProofTouched[g] = true; settingsError.set('')"
            (keydown)="gradeProofTouched[g] = gradeProofTouched[g] || clears($event)"
          />
          <label [for]="'setting-proof-threshold-' + g + '-from'">Proof threshold for grade {{ g }} in force from (blank: the sheet's today)</label>
          <input
            [id]="'setting-proof-threshold-' + g + '-from'"
            type="date"
            [attr.data-testid]="'setting-proof-threshold-' + g + '-from'"
            [value]="fromInput()[GRADE_PROOF[g]]"
            (input)="setFrom(GRADE_PROOF[g], value($event)); gradeProofTouched[g] = true; settingsError.set('')"
          />
        }
        <label for="setting-note-threshold">Note threshold: a claim with a due above it needs a note to be opened (blank: none)</label>
        <input
          id="setting-note-threshold"
          inputmode="decimal"
          data-testid="setting-note-threshold"
          [value]="noteThresholdInput()"
          (input)="noteThresholdInput.set(value($event)); noteThresholdTouched = true; settingsError.set('')"
          (keydown)="noteThresholdTouched = noteThresholdTouched || clears($event)"
        />
        <label for="setting-note-threshold-from">Note threshold in force from (blank: the sheet's today)</label>
        <input
          id="setting-note-threshold-from"
          type="date"
          data-testid="setting-note-threshold-from"
          [value]="fromInput().noteThreshold"
          (input)="setFrom('noteThreshold', value($event)); noteThresholdTouched = true; settingsError.set('')"
        />
        <label for="setting-review-threshold">Review threshold: a claim with a due above it goes to review when opened (blank: none)</label>
        <input
          id="setting-review-threshold"
          inputmode="decimal"
          data-testid="setting-review-threshold"
          [value]="reviewThresholdInput()"
          (input)="reviewThresholdInput.set(value($event)); reviewThresholdTouched = true; settingsError.set('')"
          (keydown)="reviewThresholdTouched = reviewThresholdTouched || clears($event)"
        />
        <label for="setting-review-threshold-from">Review threshold in force from (blank: the sheet's today)</label>
        <input
          id="setting-review-threshold-from"
          type="date"
          data-testid="setting-review-threshold-from"
          [value]="fromInput().reviewThreshold"
          (input)="setFrom('reviewThreshold', value($event)); reviewThresholdTouched = true; settingsError.set('')"
        />
        @for (p of perms; track p.field) {
          <label [for]="'setting-perm-' + p.action">Roles that may {{ p.action }}, comma separated (blank: {{ p.standing }})</label>
          <input
            [id]="'setting-perm-' + p.action"
            [attr.data-testid]="'setting-perm-' + p.action"
            [value]="permInput()[p.field]"
            (input)="setPermInput(p.field, value($event)); permTouched[p.field] = true; settingsError.set('')"
            (keydown)="permTouched[p.field] = permTouched[p.field] || clears($event)"
          />
        }
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError()) {
          <p role="alert" data-testid="settings-error">{{ settingsError() }}</p>
        }
      </form>
    </section>
  `,
})
export class Access {
  /** Re-reads what a saved setting changes elsewhere on the screen (the people's dues); awaited before the saved values show. */
  readonly settingsSaved = input<() => Promise<void>>(() => Promise.resolve());
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly roleNames = ROLES;
  protected readonly grades = GRADES;
  protected readonly roles = signal<RoleRow[]>([]);
  protected readonly clerkLimit = signal<string | null>(null);
  protected readonly roleUser = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal('');
  protected readonly delegations = signal<DelegationRow[]>([]);
  protected readonly delegateUser = signal('');
  protected readonly delegateFrom = signal('');
  protected readonly delegateTo = signal('');
  protected readonly delegateError = signal('');
  protected readonly limitInput = signal('');
  protected readonly gradeLimit = signal<Record<Grade, string | null>>({ A: null, B: null, C: null });
  protected readonly gradeLimitInput = signal<Record<Grade, string>>({ A: '', B: '', C: '' });
  protected readonly floor = signal('');
  protected readonly floorInput = signal('');
  protected readonly terms = signal('');
  protected readonly termsInput = signal('');
  protected readonly gradeTerms = signal<Record<Grade, string | null>>({ A: null, B: null, C: null });
  protected readonly gradeTermsInput = signal<Record<Grade, string>>({ A: '', B: '', C: '' });
  protected readonly gradeGrace = signal<Record<Grade, string | null>>({ A: null, B: null, C: null });
  protected readonly gradeGraceInput = signal<Record<Grade, string>>({ A: '', B: '', C: '' });
  protected readonly latePct = signal('');
  protected readonly latePctInput = signal('');
  protected readonly rounding = signal<string | null>(null);
  protected readonly roundingInput = signal('');
  protected readonly policyDate = signal<string | null>(null);
  protected readonly policyDateInput = signal('');
  protected readonly periodNumberingFrom = signal<string | null>(null);
  protected readonly numberingFromInput = signal('');
  protected readonly graceDays = signal<string | null>(null);
  protected readonly graceDaysInput = signal('');
  protected readonly entryDate = signal<string | null>(null);
  protected readonly entryDateInput = signal('');
  protected readonly pot = signal<string | null>(null);
  protected readonly potInput = signal('');
  protected readonly alertThreshold = signal<string | null>(null);
  protected readonly alertThresholdInput = signal('');
  protected readonly escalateAfter = signal<string | null>(null);
  protected readonly escalateAfterInput = signal('');
  protected readonly reviewSla = signal<string | null>(null);
  protected readonly reviewSlaInput = signal('');
  protected readonly proofKinds = signal<string | null>(null);
  protected readonly proofKindsInput = signal('');
  protected readonly defaultGrade = signal<string | null>(null);
  protected readonly defaultGradeInput = signal('');
  protected readonly parent = signal<string | null>(null);
  protected readonly parentInput = signal('');
  protected readonly fiscalStartMonth = signal('1');
  protected readonly fiscalStartMonthInput = signal('');
  protected readonly cap = signal<string | null>(null);
  protected readonly capInput = signal('');
  protected readonly proofThreshold = signal<string | null>(null);
  protected readonly proofThresholdInput = signal('');
  protected readonly gradeProof = signal<Record<Grade, string | null>>({ A: null, B: null, C: null });
  protected readonly gradeProofInput = signal<Record<Grade, string>>({ A: '', B: '', C: '' });
  protected readonly noteThreshold = signal<string | null>(null);
  protected readonly noteThresholdInput = signal('');
  protected readonly reviewThreshold = signal<string | null>(null);
  protected readonly reviewThresholdInput = signal('');
  protected readonly GRADE_TERMS = GRADE_TERMS;
  protected readonly GRADE_GRACE = GRADE_GRACE;
  protected readonly GRADE_PROOF = GRADE_PROOF;
  protected readonly GRADE_LIMIT = GRADE_LIMIT;
  protected readonly perms = PERMS;
  protected readonly perm = signal<Record<Perm, string | null>>({ ...NO_PERMS });
  protected readonly permInput = signal<Record<Perm, string>>(NO_PERMS);
  protected permTouched: Record<Perm, boolean> = { ...NO_PERMS_TOUCHED };
  /** The from-date typed for each dated setting; blank: the sheet's today. */
  protected readonly fromInput = signal<Record<Dated, string>>(NO_FROM);
  /** Whether each settings input was changed since the settings were last shown. */
  protected limitTouched = false;
  protected gradeLimitTouched: Record<Grade, boolean> = { A: false, B: false, C: false };
  protected floorTouched = false;
  protected termsTouched = false;
  protected gradeTermsTouched: Record<Grade, boolean> = { A: false, B: false, C: false };
  protected gradeGraceTouched: Record<Grade, boolean> = { A: false, B: false, C: false };
  protected latePctTouched = false;
  protected roundingTouched = false;
  protected policyDateTouched = false;
  protected numberingFromTouched = false;
  protected graceDaysTouched = false;
  protected entryDateTouched = false;
  protected potTouched = false;
  protected alertThresholdTouched = false;
  protected escalateAfterTouched = false;
  protected reviewSlaTouched = false;
  protected proofKindsTouched = false;
  protected defaultGradeTouched = false;
  protected parentTouched = false;
  protected fiscalStartMonthTouched = false;
  protected capTouched = false;
  protected proofThresholdTouched = false;
  protected gradeProofTouched: Record<Grade, boolean> = { A: false, B: false, C: false };
  protected noteThresholdTouched = false;
  protected reviewThresholdTouched = false;
  protected readonly settingsError = signal('');
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.roles.set([]);
      this.roleUser.set('');
      this.role.set('');
      this.roleError.set('');
      this.delegations.set([]);
      this.delegateUser.set('');
      this.delegateFrom.set('');
      this.delegateTo.set('');
      this.delegateError.set('');
      this.showSettings({ clerkLimit: null, floor: '', terms: '', termsA: null, termsB: null, termsC: null, latePct: '', policyDate: null, periodNumberingFrom: null, graceDays: null, entryDate: null, pot: null, alertThreshold: null, escalateAfter: null, reviewSla: null, proofKinds: null, defaultGrade: null, parent: null, fiscalStartMonth: '1', cap: null, proofThreshold: null, noteThreshold: null, reviewThreshold: null, permOpen: null, permSettle: null, permVoid: null, permReassign: null, permClose: null, permReopen: null, graceA: null, graceB: null, graceC: null, proofThresholdA: null, proofThresholdB: null, proofThresholdC: null, rounding: null, clerkLimitA: null, clerkLimitB: null, clerkLimitC: null });
      this.settingsError.set('');
      if (sheet) void this.reload(sheet);
    });
  }

  /** Whether a key asks to clear the input: Delete or Backspace, even on an input already blank. */
  protected clears(e: KeyboardEvent): boolean {
    return e.key === 'Delete' || e.key === 'Backspace';
  }

  protected setFrom(key: Dated, v: string): void {
    this.fromInput.update((f) => ({ ...f, [key]: v }));
  }

  protected setGradeTermsInput(grade: Grade, v: string): void {
    this.gradeTermsInput.update((t) => ({ ...t, [grade]: v }));
  }

  protected setGradeGraceInput(grade: Grade, v: string): void {
    this.gradeGraceInput.update((t) => ({ ...t, [grade]: v }));
  }

  protected setGradeLimitInput(grade: Grade, v: string): void {
    this.gradeLimitInput.update((t) => ({ ...t, [grade]: v }));
  }

  protected setGradeProofInput(grade: Grade, v: string): void {
    this.gradeProofInput.update((t) => ({ ...t, [grade]: v }));
  }

  protected setPermInput(key: Perm, v: string): void {
    this.permInput.update((p) => ({ ...p, [key]: v }));
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async saveRole(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.roleError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = this.roleUser().trim();
    const role = this.role().trim();
    if (!user) {
      this.roleError.set('Enter a user name.');
      return;
    }
    if (!ROLES.includes(role)) {
      this.roleError.set('Enter a role: clerk, steward, viewer or restricted.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<RoleRow>(`/api/sheets/${encodeURIComponent(sheet)}/roles`, { user, role }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.roles.update((rs) => [...rs.filter((r) => r.user !== saved.user), saved].sort((a, b) => a.user.localeCompare(b.user)));
      this.roleUser.set('');
      this.role.set('');
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.roleError.set('Could not set the role.');
    } finally {
      this.busy = false;
    }
  }

  protected async delegate(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.delegateError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = this.delegateUser().trim();
    const from = this.delegateFrom().trim() || (this.session.today() ?? '');
    const to = this.delegateTo().trim();
    if (!user) {
      this.delegateError.set('Enter a user name.');
      return;
    }
    if (!DATE.test(from) || !DATE.test(to)) {
      this.delegateError.set('Enter a from-date (or leave it blank for the sheet\'s today) and a to-date.');
      return;
    }
    if (to < from) {
      this.delegateError.set('Enter a to-date on or after the from-date.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<DelegationRow[]>(`/api/sheets/${encodeURIComponent(sheet)}/delegations`, { user, from, to }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.delegations.set(saved);
      this.delegateUser.set('');
      this.delegateFrom.set('');
      this.delegateTo.set('');
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.delegateError.set('Could not delegate: only a steward can delegate the steward role.');
    } finally {
      this.busy = false;
    }
  }

  protected async saveSettings(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.settingsError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const clerkLimit = this.limitInput().trim();
    if (this.limitTouched && clerkLimit !== '' && !LIMIT.test(clerkLimit)) {
      this.settingsError.set('Enter a clerk limit of 0.00 or more, with up to two decimals, or leave it blank for no limit.');
      return;
    }
    const gradeLimit = this.gradeLimitInput();
    for (const g of GRADES) {
      const limit = gradeLimit[g].trim();
      if (this.gradeLimitTouched[g] && limit !== '' && !LIMIT.test(limit)) {
        this.settingsError.set(`Enter a clerk limit for grade ${g} of 0.00 or more, with up to two decimals, or leave it blank for the sheet's.`);
        return;
      }
    }
    const floor = this.floorInput().trim();
    if (this.floorTouched && floor !== '' && !LIMIT.test(floor)) {
      this.settingsError.set('Enter a floor of 0.00 or more, with up to two decimals, or leave it blank for 1.00.');
      return;
    }
    const terms = this.termsInput().trim();
    if (this.termsTouched && terms !== '' && !DAYS.test(terms)) {
      this.settingsError.set('Enter terms of 0 or more whole days, or leave them blank for 14.');
      return;
    }
    const gradeTerms = this.gradeTermsInput();
    for (const g of GRADES) {
      const days = gradeTerms[g].trim();
      if (this.gradeTermsTouched[g] && days !== '' && !DAYS.test(days)) {
        this.settingsError.set(`Enter terms for grade ${g} of 0 or more whole days, or leave them blank for the sheet's terms.`);
        return;
      }
    }
    const gradeGrace = this.gradeGraceInput();
    for (const g of GRADES) {
      const days = gradeGrace[g].trim();
      if (this.gradeGraceTouched[g] && days !== '' && !DAYS.test(days)) {
        this.settingsError.set(`Enter grace days for grade ${g} of 0 or more whole days, or leave them blank for none.`);
        return;
      }
    }
    const latePct = this.latePctInput().trim();
    if (this.latePctTouched && latePct !== '' && !PERCENT.test(latePct)) {
      this.settingsError.set('Enter a late percent of 0.00 or more, with up to two decimals, or leave it blank for 0.00.');
      return;
    }
    const rounding = this.roundingInput().trim();
    if (this.roundingTouched && rounding !== '' && !ROUNDING.test(rounding)) {
      this.settingsError.set('Enter a rounding mode of half-up or half-even, or leave it blank for none.');
      return;
    }
    const policyDate = this.policyDateInput().trim();
    if (this.policyDateTouched && policyDate !== '' && !DATE.test(policyDate)) {
      this.settingsError.set('Enter a policy date, or leave it blank for the sheet\'s today.');
      return;
    }
    const numberingFrom = this.numberingFromInput().trim();
    if (this.numberingFromTouched && numberingFrom !== '' && !DATE.test(numberingFrom)) {
      this.settingsError.set('Enter a period numbering from date, or leave it blank for off.');
      return;
    }
    const graceDays = this.graceDaysInput().trim();
    if (this.graceDaysTouched && graceDays !== '' && !DAYS.test(graceDays)) {
      this.settingsError.set('Enter grace days of 0 or more whole days, or leave them blank for off.');
      return;
    }
    const entryDate = this.entryDateInput().trim();
    if (this.entryDateTouched && entryDate !== '' && !DATE.test(entryDate)) {
      this.settingsError.set('Enter an entry date, or leave it blank for off.');
      return;
    }
    const pot = this.potInput().trim();
    if (this.potTouched && pot !== '' && !LIMIT.test(pot)) {
      this.settingsError.set('Enter a pot of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    const alertThreshold = this.alertThresholdInput().trim();
    if (this.alertThresholdTouched && alertThreshold !== '' && !LIMIT.test(alertThreshold)) {
      this.settingsError.set('Enter an alert threshold of 0.00 or more, with up to two decimals, or leave it blank for off.');
      return;
    }
    const escalateAfter = this.escalateAfterInput().trim();
    if (this.escalateAfterTouched && escalateAfter !== '' && !DAYS.test(escalateAfter)) {
      this.settingsError.set('Enter an escalation gap as a whole number of days, or leave it blank for off.');
      return;
    }
    const reviewSla = this.reviewSlaInput().trim();
    if (this.reviewSlaTouched && reviewSla !== '' && !DAYS.test(reviewSla)) {
      this.settingsError.set('Enter a review SLA as a whole number of days, or leave it blank for none.');
      return;
    }
    const proofKinds = this.proofKindsInput().trim();
    if (this.proofKindsTouched && proofKinds !== '' && !PROOF_KINDS.test(proofKinds)) {
      this.settingsError.set('Enter the proof kinds as a comma separated list of receipt and photo, or leave it blank for none.');
      return;
    }
    const defaultGrade = this.defaultGradeInput().trim();
    if (this.defaultGradeTouched && defaultGrade !== '' && !/^[ABC]$/.test(defaultGrade)) {
      this.settingsError.set('Enter a default grade of A, B or C, or leave it blank for A.');
      return;
    }
    const fiscalStartMonth = this.fiscalStartMonthInput().trim();
    if (this.fiscalStartMonthTouched && fiscalStartMonth !== '' && !MONTH.test(fiscalStartMonth)) {
      this.settingsError.set('Enter a fiscal year start month from 1 to 12, or leave it blank for 1.');
      return;
    }
    const cap = this.capInput().trim();
    if (this.capTouched && cap !== '' && !LIMIT.test(cap)) {
      this.settingsError.set('Enter a cap of 0.00 or more, with up to two decimals, or leave it blank for no cap.');
      return;
    }
    const proofThreshold = this.proofThresholdInput().trim();
    if (this.proofThresholdTouched && proofThreshold !== '' && !LIMIT.test(proofThreshold)) {
      this.settingsError.set('Enter a proof threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    const gradeProof = this.gradeProofInput();
    for (const g of GRADES) {
      const threshold = gradeProof[g].trim();
      if (this.gradeProofTouched[g] && threshold !== '' && !LIMIT.test(threshold)) {
        this.settingsError.set(`Enter a proof threshold for grade ${g} of 0.00 or more, with up to two decimals, or leave it blank for the sheet's.`);
        return;
      }
    }
    const noteThreshold = this.noteThresholdInput().trim();
    if (this.noteThresholdTouched && noteThreshold !== '' && !LIMIT.test(noteThreshold)) {
      this.settingsError.set('Enter a note threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    const reviewThreshold = this.reviewThresholdInput().trim();
    if (this.reviewThresholdTouched && reviewThreshold !== '' && !LIMIT.test(reviewThreshold)) {
      this.settingsError.set('Enter a review threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    const perms = this.permInput();
    for (const p of PERMS) {
      const roles = perms[p.field].trim();
      if (this.permTouched[p.field] && roles !== '' && !PERM_ROLES.test(roles)) {
        this.settingsError.set(`Enter the roles that may ${p.action} (clerk, steward), separated by commas, or leave it blank for ${p.standing}.`);
        return;
      }
    }
    const froms = this.fromInput();
    for (const k of DATED) {
      const from = froms[k].trim();
      if (from !== '' && !DATE.test(from)) {
        this.settingsError.set('Enter a from-date, or leave it blank for the sheet\'s today.');
        return;
      }
    }
    const body: Partial<Record<keyof Settings | `${Dated}From`, string>> = {};
    if (this.limitTouched) body.clerkLimit = clerkLimit;
    for (const g of GRADES) {
      if (this.gradeLimitTouched[g]) body[GRADE_LIMIT[g]] = gradeLimit[g].trim();
    }
    if (this.floorTouched) body.floor = floor;
    if (this.termsTouched) body.terms = terms;
    for (const g of GRADES) {
      if (this.gradeTermsTouched[g]) body[GRADE_TERMS[g]] = gradeTerms[g].trim();
    }
    if (this.latePctTouched) body.latePct = latePct;
    if (this.roundingTouched) body.rounding = rounding;
    if (this.policyDateTouched) body.policyDate = policyDate;
    if (this.numberingFromTouched) body.periodNumberingFrom = numberingFrom;
    if (this.graceDaysTouched) body.graceDays = graceDays;
    for (const g of GRADES) {
      if (this.gradeGraceTouched[g]) body[GRADE_GRACE[g]] = gradeGrace[g].trim();
    }
    if (this.entryDateTouched) body.entryDate = entryDate;
    if (this.potTouched) body.pot = pot;
    if (this.alertThresholdTouched) body.alertThreshold = alertThreshold;
    if (this.escalateAfterTouched) body.escalateAfter = escalateAfter;
    if (this.reviewSlaTouched) body.reviewSla = reviewSla;
    if (this.proofKindsTouched) body.proofKinds = proofKinds;
    if (this.defaultGradeTouched) body.defaultGrade = defaultGrade;
    if (this.parentTouched) body.parent = this.parentInput().trim();
    if (this.fiscalStartMonthTouched) body.fiscalStartMonth = fiscalStartMonth === '' ? '' : String(Number(fiscalStartMonth));
    if (this.capTouched) body.cap = cap;
    if (this.proofThresholdTouched) body.proofThreshold = proofThreshold;
    for (const g of GRADES) {
      if (this.gradeProofTouched[g]) body[GRADE_PROOF[g]] = gradeProof[g].trim();
    }
    if (this.noteThresholdTouched) body.noteThreshold = noteThreshold;
    if (this.reviewThresholdTouched) body.reviewThreshold = reviewThreshold;
    for (const p of PERMS) {
      if (this.permTouched[p.field]) body[p.field] = perms[p.field].trim();
    }
    for (const k of DATED) {
      if (body[k] !== undefined && froms[k].trim() !== '') body[`${k}From`] = froms[k].trim();
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<Settings>(`/api/sheets/${encodeURIComponent(sheet)}/settings`, body, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      await this.settingsSaved()();
      if (this.session.sheet() !== sheet) return;
      this.showSettings(saved);
      if (this.session.asOf() === null) this.session.parent.set(saved.parent);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.settingsError.set('Could not save the settings.');
    } finally {
      this.busy = false;
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const view = await firstValueFrom(this.http.get<AccessView>(`/api/sheets/${encodeURIComponent(sheet)}/access`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) {
      this.roles.set(view.roles);
      this.delegations.set(view.delegations);
      this.showSettings(view.settings);
    }
  }

  /** Show the settings saved, each input holding its value, none of them changed yet. */
  private showSettings(settings: Settings): void {
    this.clerkLimit.set(settings.clerkLimit);
    this.floor.set(settings.floor);
    this.limitInput.set(settings.clerkLimit ?? '');
    this.floorInput.set(settings.floor);
    this.terms.set(settings.terms);
    this.termsInput.set(settings.terms);
    this.latePct.set(settings.latePct);
    this.latePctInput.set(settings.latePct);
    this.limitTouched = false;
    this.gradeLimit.set({ A: settings.clerkLimitA, B: settings.clerkLimitB, C: settings.clerkLimitC });
    this.gradeLimitInput.set({ A: settings.clerkLimitA ?? '', B: settings.clerkLimitB ?? '', C: settings.clerkLimitC ?? '' });
    this.gradeLimitTouched = { A: false, B: false, C: false };
    this.floorTouched = false;
    this.termsTouched = false;
    this.gradeTerms.set({ A: settings.termsA, B: settings.termsB, C: settings.termsC });
    this.gradeTermsInput.set({ A: settings.termsA ?? '', B: settings.termsB ?? '', C: settings.termsC ?? '' });
    this.gradeTermsTouched = { A: false, B: false, C: false };
    this.gradeGrace.set({ A: settings.graceA, B: settings.graceB, C: settings.graceC });
    this.gradeGraceInput.set({ A: settings.graceA ?? '', B: settings.graceB ?? '', C: settings.graceC ?? '' });
    this.gradeGraceTouched = { A: false, B: false, C: false };
    this.rounding.set(settings.rounding);
    this.roundingInput.set(settings.rounding ?? '');
    this.roundingTouched = false;
    this.policyDate.set(settings.policyDate);
    this.policyDateInput.set(settings.policyDate ?? '');
    this.latePctTouched = false;
    this.policyDateTouched = false;
    this.periodNumberingFrom.set(settings.periodNumberingFrom);
    this.numberingFromInput.set(settings.periodNumberingFrom ?? '');
    this.numberingFromTouched = false;
    this.graceDays.set(settings.graceDays);
    this.graceDaysInput.set(settings.graceDays ?? '');
    this.graceDaysTouched = false;
    this.entryDate.set(settings.entryDate);
    this.entryDateInput.set(settings.entryDate ?? '');
    this.entryDateTouched = false;
    this.pot.set(settings.pot);
    this.potInput.set(settings.pot ?? '');
    this.potTouched = false;
    this.alertThreshold.set(settings.alertThreshold);
    this.alertThresholdInput.set(settings.alertThreshold ?? '');
    this.alertThresholdTouched = false;
    this.escalateAfter.set(settings.escalateAfter);
    this.escalateAfterInput.set(settings.escalateAfter ?? '');
    this.escalateAfterTouched = false;
    this.reviewSla.set(settings.reviewSla);
    this.reviewSlaInput.set(settings.reviewSla ?? '');
    this.reviewSlaTouched = false;
    this.proofKinds.set(settings.proofKinds);
    this.proofKindsInput.set(settings.proofKinds ?? '');
    this.proofKindsTouched = false;
    this.defaultGrade.set(settings.defaultGrade);
    this.defaultGradeInput.set(settings.defaultGrade ?? '');
    this.defaultGradeTouched = false;
    this.parent.set(settings.parent);
    this.parentInput.set(settings.parent ?? '');
    this.parentTouched = false;
    this.fiscalStartMonth.set(settings.fiscalStartMonth);
    this.fiscalStartMonthInput.set(settings.fiscalStartMonth);
    this.fiscalStartMonthTouched = false;
    this.cap.set(settings.cap);
    this.capInput.set(settings.cap ?? '');
    this.capTouched = false;
    this.proofThreshold.set(settings.proofThreshold);
    this.proofThresholdInput.set(settings.proofThreshold ?? '');
    this.proofThresholdTouched = false;
    this.gradeProof.set({ A: settings.proofThresholdA, B: settings.proofThresholdB, C: settings.proofThresholdC });
    this.gradeProofInput.set({ A: settings.proofThresholdA ?? '', B: settings.proofThresholdB ?? '', C: settings.proofThresholdC ?? '' });
    this.gradeProofTouched = { A: false, B: false, C: false };
    this.noteThreshold.set(settings.noteThreshold);
    this.noteThresholdInput.set(settings.noteThreshold ?? '');
    this.noteThresholdTouched = false;
    this.reviewThreshold.set(settings.reviewThreshold);
    this.reviewThresholdInput.set(settings.reviewThreshold ?? '');
    this.reviewThresholdTouched = false;
    this.fromInput.set(NO_FROM);
    const perm = { ...NO_PERMS } as Record<Perm, string | null>;
    const input = { ...NO_PERMS };
    for (const p of PERMS) {
      perm[p.field] = settings[p.field];
      input[p.field] = settings[p.field] ?? '';
    }
    this.perm.set(perm);
    this.permInput.set(input);
    this.permTouched = { ...NO_PERMS_TOUCHED };
  }
}
