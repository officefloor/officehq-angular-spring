import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, model, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

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
}

/** The grades that may have their own terms. */
const GRADES = ['A', 'B', 'C'] as const;
type Grade = (typeof GRADES)[number];

/** A limit or floor: 0.00 or more, with up to two decimals. */
const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;

const DEFAULT_FLOOR = '1.00';

/** Terms: a whole number of days. */
const TERMS = /^\d{1,4}$/;

const DEFAULT_TERMS = 14;

/** A late percent: 0.00 to 999.99, with up to two decimals. */
const PCT = /^\d{1,3}(\.\d{1,2})?$/;

const DEFAULT_LATE_PCT = '0.00';

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
        <label for="setting-floor">Floor (blank: leave as it is)</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floorInput() ?? ''"
          (input)="floorInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-terms">Terms in days (blank: leave as they are)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="termsInput() ?? ''"
          (input)="termsInput.set($any($event.target).value); error.set(null)"
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
        }
        <label for="setting-late-pct">Late percent per 30 days overdue (blank: leave as it is)</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePctInput() ?? ''"
          (input)="latePctInput.set($any($event.target).value); error.set(null)"
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
          <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ gradeTerms()[g] ?? '' }}</strong>
          @if (gradeTerms()[g] === undefined) {
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
    </section>
  `,
})
export class Settings {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's floor, shared with whoever works out what is due. */
  readonly floor = model(DEFAULT_FLOOR);
  /** The sheet's terms in days: what a grade without its own terms uses. */
  protected readonly terms = signal(DEFAULT_TERMS);
  protected readonly grades = GRADES;
  /** The terms set for each grade that has its own. */
  protected readonly gradeTerms = signal<Partial<Record<Grade, number>>>({});
  /** The terms typed for each grade; blank (or missing) leaves them as they are. */
  protected readonly gradeTermsInput = signal<Partial<Record<Grade, string>>>({});
  /** The sheet's late percent, shared with whoever works out an overdue claim's late charge. */
  readonly latePct = model(DEFAULT_LATE_PCT);
  /** The sheet's policy date (blank: its today), shared with whoever works out how a claim rounds. */
  readonly policyDate = model('');

  protected readonly limit = signal('');
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
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's settings from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.limit.set('');
      this.floor.set(DEFAULT_FLOOR);
      this.terms.set(DEFAULT_TERMS);
      this.gradeTerms.set({});
      this.gradeTermsInput.set({});
      this.latePct.set(DEFAULT_LATE_PCT);
      this.policyDate.set('');
      this.numberingFrom.set('');
      this.limitInput.set(null);
      this.floorInput.set(null);
      this.termsInput.set(null);
      this.latePctInput.set(null);
      this.policyInput.set(null);
      this.numberingInput.set(null);
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

  protected typeGradeTerms(grade: Grade, value: string): void {
    this.gradeTermsInput.update((m) => ({ ...m, [grade]: value }));
    this.error.set(null);
  }

  private show(res: SettingsResponse): void {
    this.limit.set(res.clerkLimit);
    this.floor.set(res.floor);
    this.terms.set(res.terms);
    this.gradeTerms.set(res.gradeTerms ?? {});
    this.latePct.set(res.latePct);
    this.policyDate.set(res.policyDate);
    this.numberingFrom.set(res.periodNumberingFrom);
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
    } catch {
      this.error.set('Could not save the settings.');
    } finally {
      this.submitting = false;
    }
  }
}
