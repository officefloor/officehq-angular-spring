import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, model, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { DatedValue, START_FLOOR, START_LATE_PCT, valueOn } from '../rates/rates';

// The sheet's settings: its clerk limit (two decimals; blank for no limit), and its floor (two
// decimals; 1.00 on a new sheet), its terms (whole days; 14 on a new sheet) and its late percent (two decimals; 0.00 on a new sheet). A
// clerk settles a claim only when its due is at or below the limit; a claim's live due is never below the floor; a claim is due by its date
// plus the terms of its person's grade (fixed when the claim is added); an overdue claim's due gains the late percent of its figure for every full 30 days overdue. Saving saves the clerk limit
// once its field has been entered (blank: no limit), and the floor, terms and late percent when typed (blank: left as they are).
// The policy date (blank: the sheet's today) decides how a claim's live figures round: half up when dated before it, half to even
// on or after it; it saves, like the clerk limit, once its field has been entered (blank: none).
// Period numbering from (blank: off): while the sheet's today is on or after it, a claim opened is
// numbered within its period (<period name>-<n>, or X-<n> in no period); it saves like the policy date.
// Each grade may have its own terms (whole days; blank: left as they are); a grade without its own
// uses the sheet's terms. A claim's due-by is fixed when it is added, so changing terms moves none.
// The cap (two decimals; blank for none) is the most a person's due-total can be; it saves, like
// the clerk limit, once its field has been entered (blank: none).
// The settings that carry a figure (the clerk limit, the floor, the terms, each grade's terms, the
// late percent and the cap) are dated: each saves its value from the date in its from field (blank:
// the sheet's today), keeping the values saved before (a value saved from the same date is
// replaced). A claim's floor, late percent and terms are the ones in force on its date; the clerk
// limit and the cap are the ones in force on the sheet's today. Each shows the value in force on
// the sheet's today: before its first from-date the floor is 1.00, the late percent 0.00 and the
// terms 14; a grade's terms, the clerk limit and the cap show nothing. The policy date and period
// numbering from are not dated.
// The proof threshold (two decimals; blank for none) is dated like the floor: a claim whose due is
// above the one in force on its date cannot be settled or approved while it has no proofs. It
// saves, like the clerk limit, once its field has been entered (blank: none).
// The permission matrix: for each of open, settle, void, reassign, close and reopen, the roles that
// may do it, comma separated (blank: the standing rule, open, settle, void and close by clerk and
// steward, reassign and reopen by steward). Each saves, like the clerk limit, once its field has
// been entered; it is not dated.
// Grace days (whole days; blank: off): when the sheet's today changes, every open period whose
// to-date plus the grace days is before the new today is closed. It saves, like the clerk limit,
// once its field has been entered; it is not dated.
/** A value saved for a dated setting, by key: blank from for the beginning of time, blank value for none. */
interface DatedSetting extends DatedValue {
  key: string;
}

interface SettingsResponse {
  /** The clerk limit, blank for none. */
  clerkLimit: string;
  floor: string;
  terms: number;
  latePct: string;
  /** The policy date, blank for none (the sheet's today). */
  policyDate: string;
  /** The period-numbering-from date, blank for off. */
  periodNumberingFrom: string;
  /** The terms set for each grade that has its own, by grade. */
  gradeTerms: Record<string, number>;
  /** The cap, blank for none. */
  cap: string;
  /** The proof threshold, blank for none. */
  proofThreshold: string;
  /** Every value saved for the dated settings, by key and from-date. */
  dated: DatedSetting[];
  /** The permission matrix: for each action, its roles comma separated, blank for the standing rule. */
  permissions: Partial<Record<PermAction, string>>;
  /** The grace days, blank for off. */
  graceDays: string;
}

/** The actions the permission matrix covers. */
const PERM_ACTIONS = ['open', 'settle', 'void', 'reassign', 'close', 'reopen'] as const;
type PermAction = (typeof PERM_ACTIONS)[number];

/** A permission matrix entry: roles comma separated. */
const ROLES = /^\s*(clerk|steward)(\s*,\s*(clerk|steward))*\s*$/i;

/** The grades that may have their own terms. */
const GRADES = ['A', 'B', 'C'] as const;
type Grade = (typeof GRADES)[number];

/** A limit or floor: 0.00 or more, with up to two decimals. */
const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;


/** Terms: a whole number of days. */
const TERMS = /^\d{1,4}$/;

const START_TERMS = '14';

/** A late percent: 0.00 to 999.99, with up to two decimals. */
const PCT = /^\d{1,3}(\.\d{1,2})?$/;


/** The keys of the dated settings, each with its own from field. */
const DATED_KEYS = ['clerk-limit', 'floor', 'terms', 'terms-A', 'terms-B', 'terms-C', 'late-pct', 'cap', 'proof-threshold'] as const;
type DatedKey = (typeof DATED_KEYS)[number];

/** A policy date: yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

@Component({
  selector: 'app-settings',
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form aria-label="Settings" (submit)="save($event)">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="limitInput() ?? ''"
          (focus)="enterLimit($any($event.target))"
          (input)="limitInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-clerk-limit-from">Clerk limit in force from (blank: the sheet's today)</label>
        <input
          id="setting-clerk-limit-from"
          data-testid="setting-clerk-limit-from"
          type="date"
          [value]="fromInput()['clerk-limit'] ?? ''"
          (input)="typeFrom('clerk-limit', $any($event.target).value)"
        />
        <label for="setting-floor">Floor (blank: leave as it is)</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floorInput() ?? ''"
          (input)="floorInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-floor-from">Floor in force from (blank: the sheet's today)</label>
        <input
          id="setting-floor-from"
          data-testid="setting-floor-from"
          type="date"
          [value]="fromInput()['floor'] ?? ''"
          (input)="typeFrom('floor', $any($event.target).value)"
        />
        <label for="setting-terms">Terms in days (blank: leave as they are)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="termsInput() ?? ''"
          (input)="termsInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-terms-from">Terms in force from (blank: the sheet's today)</label>
        <input
          id="setting-terms-from"
          data-testid="setting-terms-from"
          type="date"
          [value]="fromInput()['terms'] ?? ''"
          (input)="typeFrom('terms', $any($event.target).value)"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms in days for grade {{ g }} (blank: leave as they are)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [value]="gradeTermsInput()[g] ?? ''"
            (input)="typeGradeTerms(g, $any($event.target).value)"
          />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} in force from (blank: the sheet's today)</label>
          <input
            [id]="'setting-terms-' + g + '-from'"
            [attr.data-testid]="'setting-terms-' + g + '-from'"
            type="date"
            [value]="fromInput()[gradeKey(g)] ?? ''"
            (input)="typeFrom(gradeKey(g), $any($event.target).value)"
          />
        }
        <label for="setting-late-pct">Late percent per 30 days overdue (blank: leave as it is)</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePctInput() ?? ''"
          (input)="latePctInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-late-pct-from">Late percent in force from (blank: the sheet's today)</label>
        <input
          id="setting-late-pct-from"
          data-testid="setting-late-pct-from"
          type="date"
          [value]="fromInput()['late-pct'] ?? ''"
          (input)="typeFrom('late-pct', $any($event.target).value)"
        />
        <label for="setting-policy-date">Policy date (blank: the sheet's today)</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          type="date"
          [value]="policyInput() ?? ''"
          (focus)="enterPolicy($any($event.target))"
          (input)="policyInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-period-numbering-from">Period numbering from (blank: off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          type="date"
          [value]="numberingInput() ?? ''"
          (focus)="enterNumbering($any($event.target))"
          (input)="numberingInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-cap">Cap on a person's due-total (blank: no cap)</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="capInput() ?? ''"
          (focus)="enterCap($any($event.target))"
          (input)="capInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-cap-from">Cap in force from (blank: the sheet's today)</label>
        <input
          id="setting-cap-from"
          data-testid="setting-cap-from"
          type="date"
          [value]="fromInput()['cap'] ?? ''"
          (input)="typeFrom('cap', $any($event.target).value)"
        />
        <label for="setting-proof-threshold">Proof threshold (blank: none)</label>
        <input
          id="setting-proof-threshold"
          data-testid="setting-proof-threshold"
          inputmode="decimal"
          [value]="thresholdInput() ?? ''"
          (focus)="enterThreshold($any($event.target))"
          (input)="thresholdInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-proof-threshold-from">Proof threshold in force from (blank: the sheet's today)</label>
        <input
          id="setting-proof-threshold-from"
          data-testid="setting-proof-threshold-from"
          type="date"
          [value]="fromInput()['proof-threshold'] ?? ''"
          (input)="typeFrom('proof-threshold', $any($event.target).value)"
        />
        @for (a of permActions; track a) {
          <label [for]="'setting-perm-' + a">Roles that may {{ a }}, comma separated (blank: the standing rule)</label>
          <input
            [id]="'setting-perm-' + a"
            [attr.data-testid]="'setting-perm-' + a"
            [value]="permInput()[a] ?? ''"
            (focus)="enterPerm(a, $any($event.target))"
            (input)="typePerm(a, $any($event.target).value)"
          />
        }
        <label for="setting-grace-days">Grace days before a period closes itself (blank: off)</label>
        <input
          id="setting-grace-days"
          data-testid="setting-grace-days"
          inputmode="numeric"
          [value]="graceInput() ?? ''"
          (focus)="enterGrace($any($event.target))"
          (input)="graceInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (error(); as err) {
          <p role="alert" data-testid="settings-error">{{ err }}</p>
        }
      </form>
      <p>
        Clerk limit in force:
        <strong data-testid="setting-clerk-limit-current">{{ limit() }}</strong>
        @if (limit() === '') {
          <span>(no limit)</span>
        }
      </p>
      <p>
        Floor in force:
        <strong data-testid="setting-floor-current">{{ floor() }}</strong>
      </p>
      <p>
        Terms in force (days):
        <strong data-testid="setting-terms-current">{{ terms() }}</strong>
      </p>
      @for (g of grades; track g) {
        <p>
          Terms in force for grade {{ g }} (days):
          <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ gradeTerms()[g] }}</strong>
          @if (gradeTerms()[g] === '') {
            <span>(the sheet's terms)</span>
          }
        </p>
      }
      <p>
        Late percent in force:
        <strong data-testid="setting-late-pct-current">{{ latePct() }}</strong>
      </p>
      <p>
        Policy date in force:
        <strong data-testid="setting-policy-date-current">{{ policyDate() }}</strong>
        @if (policyDate() === '') {
          <span>(the sheet's today)</span>
        }
      </p>
      <p>
        Period numbering from:
        <strong data-testid="setting-period-numbering-from-current">{{ numberingFrom() }}</strong>
        @if (numberingFrom() === '') {
          <span>(off)</span>
        }
      </p>
      <p>
        Cap in force:
        <strong data-testid="setting-cap-current">{{ cap() }}</strong>
        @if (cap() === '') {
          <span>(no cap)</span>
        }
      </p>
      <p>
        Proof threshold in force:
        <strong data-testid="setting-proof-threshold-current">{{ threshold() }}</strong>
        @if (threshold() === '') {
          <span>(none)</span>
        }
      </p>
      <p>
        Grace days:
        <strong data-testid="setting-grace-days-current">{{ graceDays() }}</strong>
        @if (graceDays() === '') {
          <span>(off)</span>
        }
      </p>
      @for (a of permActions; track a) {
        <p>
          Roles that may {{ a }}:
          <strong [attr.data-testid]="'setting-perm-' + a + '-current'">{{ permissions()[a] ?? '' }}</strong>
          @if (!permissions()[a]) {
            <span>(the standing rule)</span>
          }
        </p>
      }
    </section>
  `,
})
export class Settings {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** Every value saved for the dated settings. */
  private readonly dated = signal<DatedSetting[]>([]);
  /** The sheet's floors, dated, shared with whoever works out what is due. */
  readonly floors = model<DatedValue[]>([]);
  /** The floor in force on the sheet's today. */
  protected readonly floor = computed(() => valueOn(this.floors(), this.session.today(), START_FLOOR));
  /** The sheet's terms in days in force on its today: what a grade without its own terms uses. */
  protected readonly terms = computed(() => this.inForce('terms', START_TERMS));
  protected readonly grades = GRADES;
  /** The terms in force on the sheet's today for each grade; blank for one without its own then. */
  protected readonly gradeTerms = computed(
    () => Object.fromEntries(GRADES.map((g) => [g, this.inForce(this.gradeKey(g), '')])) as Record<Grade, string>,
  );
  /** The terms typed for each grade; blank (or missing) leaves them as they are. */
  protected readonly gradeTermsInput = signal<Partial<Record<Grade, string>>>({});
  /** The sheet's late percents, dated, shared with whoever works out an overdue claim's late charge. */
  readonly latePcts = model<DatedValue[]>([]);
  /** The late percent in force on the sheet's today. */
  protected readonly latePct = computed(() => valueOn(this.latePcts(), this.session.today(), START_LATE_PCT));
  /** The sheet's policy date (blank: its today), shared with whoever works out how a claim rounds. */
  readonly policyDate = model('');
  /** The sheet's caps, dated (blank: none), shared with whoever works out a person's due-total. */
  readonly caps = model<DatedValue[]>([]);
  /** The cap in force on the sheet's today; blank for none. */
  protected readonly cap = computed(() => valueOn(this.caps(), this.session.today(), ''));
  /** The cap typed; null while its field has not been entered (left as it is). */
  protected readonly capInput = signal<string | null>(null);

  /** The proof threshold in force on the sheet's today; blank for none. */
  protected readonly threshold = computed(() => this.inForce('proof-threshold', ''));
  /** The proof threshold typed; null while its field has not been entered (left as it is). */
  protected readonly thresholdInput = signal<string | null>(null);

  /** The clerk limit in force on the sheet's today; blank for none. */
  protected readonly limit = computed(() => this.inForce('clerk-limit', ''));
  /** The from-date typed for each dated setting; blank (or missing) for the sheet's today. */
  protected readonly fromInput = signal<Partial<Record<DatedKey, string>>>({});
  /** The period-numbering-from date in force; blank for off. */
  protected readonly numberingFrom = signal('');
  /** The clerk limit typed; null while its field has not been entered (left as it is). */
  protected readonly limitInput = signal<string | null>(null);
  /** The floor typed; blank (or null) leaves it as it is. */
  protected readonly floorInput = signal<string | null>(null);
  /** The terms typed; blank (or null) leaves them as they are. */
  protected readonly termsInput = signal<string | null>(null);
  /** The late percent typed; blank (or null) leaves it as it is. */
  protected readonly latePctInput = signal<string | null>(null);
  /** The policy date typed; null while its field has not been entered (left as it is). */
  protected readonly policyInput = signal<string | null>(null);
  /** The period-numbering-from date typed; null while its field has not been entered (left as it is). */
  protected readonly numberingInput = signal<string | null>(null);
  /** The grace days in force; blank for off. */
  protected readonly graceDays = signal('');
  /** The grace days typed; null while its field has not been entered (left as they are). */
  protected readonly graceInput = signal<string | null>(null);
  protected readonly permActions = PERM_ACTIONS;
  /** The permission matrix in force: for each action, its roles, blank for the standing rule. */
  protected readonly permissions = signal<Partial<Record<PermAction, string>>>({});
  /** The roles typed for each action; missing while its field has not been entered (left as it is). */
  protected readonly permInput = signal<Partial<Record<PermAction, string>>>({});
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's settings from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      untracked(() => this.show({ dated: [], policyDate: '', periodNumberingFrom: '', permissions: {}, graceDays: '' }));
      this.permInput.set({});
      this.gradeTermsInput.set({});
      this.fromInput.set({});
      this.capInput.set(null);
      this.thresholdInput.set(null);
      this.limitInput.set(null);
      this.floorInput.set(null);
      this.termsInput.set(null);
      this.latePctInput.set(null);
      this.policyInput.set(null);
      this.numberingInput.set(null);
      this.graceInput.set(null);
      this.error.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const res = await firstValueFrom(
        this.http.post<SettingsResponse>('/api/settings/get', { sheet, user: this.session.user() }),
      );
      if (this.session.sheet() === sheet) this.show(res);
    } catch {
      this.error.set('Could not read the settings of this sheet.');
    }
  }

  /** Entering the clerk limit's field fills it with the limit in force, selected, so it saves as
   *  it is unless typed over. */
  protected enterLimit(input: HTMLInputElement): void {
    if (this.limitInput() !== null) return;
    input.value = this.limit();
    input.select();
    this.limitInput.set(this.limit());
  }

  /** Entering the cap's field fills it with the cap in force, selected, so it saves as it is
   *  unless typed over. */
  protected enterCap(input: HTMLInputElement): void {
    if (this.capInput() !== null) return;
    input.value = this.cap();
    input.select();
    this.capInput.set(this.cap());
  }

  /** Entering the proof threshold's field fills it with the threshold in force, selected, so it
   *  saves as it is unless typed over. */
  protected enterThreshold(input: HTMLInputElement): void {
    if (this.thresholdInput() !== null) return;
    input.value = this.threshold();
    input.select();
    this.thresholdInput.set(this.threshold());
  }

  /** Entering the policy date's field fills it with the date in force, so it saves as it is unless
   *  changed. */
  protected enterPolicy(input: HTMLInputElement): void {
    if (this.policyInput() !== null) return;
    input.value = this.policyDate();
    this.policyInput.set(this.policyDate());
  }

  /** Entering the period-numbering-from field fills it with the date in force, so it saves as it is
   *  unless changed. */
  protected enterNumbering(input: HTMLInputElement): void {
    if (this.numberingInput() !== null) return;
    input.value = this.numberingFrom();
    this.numberingInput.set(this.numberingFrom());
  }

  /** Entering the grace days' field fills it with the days in force, selected, so they save as
   *  they are unless typed over. */
  protected enterGrace(input: HTMLInputElement): void {
    if (this.graceInput() !== null) return;
    input.value = this.graceDays();
    input.select();
    this.graceInput.set(this.graceDays());
  }

  /** Entering an action's permission field fills it with its roles in force, selected, so it saves
   *  as it is unless typed over. */
  protected enterPerm(action: PermAction, input: HTMLInputElement): void {
    if (this.permInput()[action] !== undefined) return;
    const roles = this.permissions()[action] ?? '';
    input.value = roles;
    input.select();
    this.permInput.update((m) => ({ ...m, [action]: roles }));
  }

  protected typePerm(action: PermAction, value: string): void {
    this.permInput.update((m) => ({ ...m, [action]: value }));
    this.error.set(null);
  }

  protected gradeKey(grade: Grade): DatedKey {
    return `terms-${grade}`;
  }

  protected typeFrom(key: DatedKey, value: string): void {
    this.fromInput.update((m) => ({ ...m, [key]: value }));
    this.error.set(null);
  }

  /** The values saved for one dated setting. */
  private datedOf(key: string): DatedValue[] {
    return this.dated().filter((d) => d.key === key);
  }

  /** A dated setting's value in force on the sheet's today; `start` before its first from-date. */
  private inForce(key: string, start: string): string {
    return valueOn(this.datedOf(key), this.session.today(), start);
  }

  protected typeGradeTerms(grade: Grade, value: string): void {
    this.gradeTermsInput.update((m) => ({ ...m, [grade]: value }));
    this.error.set(null);
  }

  private show(
    res: Pick<SettingsResponse, 'dated' | 'policyDate' | 'periodNumberingFrom' | 'permissions' | 'graceDays'>,
  ): void {
    this.dated.set(res.dated);
    this.permissions.set(res.permissions);
    this.floors.set(this.datedOf('floor'));
    this.latePcts.set(this.datedOf('late-pct'));
    this.caps.set(this.datedOf('cap'));
    this.policyDate.set(res.policyDate);
    this.numberingFrom.set(res.periodNumberingFrom);
    this.graceDays.set(res.graceDays);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const clerkLimit = this.limitInput()?.trim() ?? null;
    const floor = this.floorInput()?.trim() || null;
    const terms = this.termsInput()?.trim() || null;
    const latePct = this.latePctInput()?.trim() || null;
    const policyDate = this.policyInput()?.trim() ?? null;
    const periodNumberingFrom = this.numberingInput()?.trim() ?? null;
    const cap = this.capInput()?.trim() ?? null;
    const proofThreshold = this.thresholdInput()?.trim() ?? null;
    const graceDays = this.graceInput()?.trim() ?? null;
    if (clerkLimit !== null && clerkLimit !== '' && !LIMIT.test(clerkLimit)) {
      this.error.set('Enter a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (floor !== null && !LIMIT.test(floor)) {
      this.error.set('Enter a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (terms !== null && !TERMS.test(terms)) {
      this.error.set('Enter terms of 0 or more whole days.');
      return;
    }
    const gradeTerms: Partial<Record<Grade, string>> = {};
    for (const g of GRADES) {
      const t = this.gradeTermsInput()[g]?.trim();
      if (!t) continue;
      if (!TERMS.test(t)) {
        this.error.set(`Enter terms for grade ${g} of 0 or more whole days.`);
        return;
      }
      gradeTerms[g] = t;
    }
    if (latePct !== null && !PCT.test(latePct)) {
      this.error.set('Enter a late percent of 0.00 to 999.99, with up to two decimals.');
      return;
    }
    if (policyDate !== null && policyDate !== '' && !DATE.test(policyDate)) {
      this.error.set('Enter a policy date as a date, or leave it blank for the sheet\'s today.');
      return;
    }
    if (periodNumberingFrom !== null && periodNumberingFrom !== '' && !DATE.test(periodNumberingFrom)) {
      this.error.set('Enter a period-numbering-from date as a date, or leave it blank for off.');
      return;
    }
    if (cap !== null && cap !== '' && !LIMIT.test(cap)) {
      this.error.set('Enter a cap of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (proofThreshold !== null && proofThreshold !== '' && !LIMIT.test(proofThreshold)) {
      this.error.set('Enter a proof threshold of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (graceDays !== null && graceDays !== '' && !TERMS.test(graceDays)) {
      this.error.set('Enter grace days of 0 or more whole days, or leave it blank for off.');
      return;
    }
    const perms: Partial<Record<PermAction, string>> = {};
    for (const a of PERM_ACTIONS) {
      const roles = this.permInput()[a]?.trim();
      if (roles === undefined) continue;
      if (roles !== '' && !ROLES.test(roles)) {
        this.error.set(`Enter the roles that may ${a} as clerk and/or steward, comma separated, or leave it blank.`);
        return;
      }
      perms[a] = roles;
    }
    const from: Partial<Record<DatedKey, string>> = {};
    for (const k of DATED_KEYS) {
      const f = this.fromInput()[k]?.trim();
      if (!f) continue;
      if (!DATE.test(f)) {
        this.error.set('Enter a from-date as a date, or leave it blank for the sheet\'s today.');
        return;
      }
      from[k] = f;
    }
    this.submitting = true;
    try {
      const res = await firstValueFrom(
        this.http.post<SettingsResponse>('/api/settings/set', {
          sheet,
          user: this.session.user(),
          clerkLimit,
          floor,
          terms,
          latePct,
          policyDate,
          periodNumberingFrom,
          termsA: gradeTerms.A ?? null,
          termsB: gradeTerms.B ?? null,
          termsC: gradeTerms.C ?? null,
          cap,
          clerkLimitFrom: from['clerk-limit'] ?? null,
          floorFrom: from.floor ?? null,
          termsFrom: from.terms ?? null,
          latePctFrom: from['late-pct'] ?? null,
          termsAFrom: from['terms-A'] ?? null,
          termsBFrom: from['terms-B'] ?? null,
          termsCFrom: from['terms-C'] ?? null,
          capFrom: from.cap ?? null,
          proofThreshold,
          proofThresholdFrom: from['proof-threshold'] ?? null,
          permOpen: perms.open ?? null,
          permSettle: perms.settle ?? null,
          permVoid: perms.void ?? null,
          permReassign: perms.reassign ?? null,
          permClose: perms.close ?? null,
          permReopen: perms.reopen ?? null,
          graceDays,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.show(res);
      this.limitInput.set(null);
      this.floorInput.set(null);
      this.termsInput.set(null);
      this.gradeTermsInput.set({});
      this.latePctInput.set(null);
      this.policyInput.set(null);
      this.numberingInput.set(null);
      this.capInput.set(null);
      this.thresholdInput.set(null);
      this.permInput.set({});
      this.graceInput.set(null);
      this.fromInput.set({});
    } catch {
      this.error.set('Could not save the settings.');
    } finally {
      this.submitting = false;
    }
  }
}
