import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, model, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, cents, daysOverdue, dueBy, isCorrection, isOpen, money, plusDays } from '../claims/claims';
import { FigurePipe } from '../../core/figure';

// The sheet's rates for each grade (a percent with two decimals, each from an optional from-date
// and through an optional to-date)
// and the form that sets one. Each grade shows the rate in force on the sheet's today, or n/a when
// none is. Setting a rate again for its grade and from-date keeps the one it replaces as an older
// version; each row shows how many versions it has had. What is due on an amount is worked out
// here from those rates.
export interface Rate {
  grade: string;
  /** The date the rate is in force from; null for a rate in force from the beginning of time. */
  from: string | null;
  /** The last date the rate is in force; null for a rate in force until the grade's next one. */
  to: string | null;
  pct: string;
  /** How many versions the rate has had: 1 when first set, one more each time it is set again. */
  version?: number;
  /** Whose rate it is: 0 (or missing) for the sheet's own, 1 for its parent's, 2 for the parent's parent, and so on. */
  level?: number;
  /** For a rate saved with a from-date on or after the sheet's today: it applies only to claims
   *  whose addedSeq is above this. Null (or missing) for a rate that applies to every claim. */
  forwardAfter?: number | null;
}

/** Whether a rate applies to a claim: it is not forward-only, or the claim was added after it was saved. */
function appliesTo(r: Rate, c: Claim): boolean {
  return r.forwardAfter == null || (c.addedSeq != null && c.addedSeq > r.forwardAfter);
}

const GRADES = ['A', 'B', 'C'] as const;

/** A percent: 0.00 to 99999.99, with up to two decimals. */
const PCT = /^\d{1,5}(\.\d{1,2})?$/;

/** A date as the date input gives it: yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** One of a person's grades, from a from-date (null: from the beginning of time). */
export interface DatedGrade {
  from: string | null;
  grade: string;
}

/**
 * The grade in force on a date (yyyy-mm-dd): the one with the latest from-date on or before it,
 * an undated grade counting as from the beginning of time. '' when none applies.
 */
export function gradeOn(grades: readonly DatedGrade[], date: string | null): string {
  let best: DatedGrade | undefined;
  for (const g of grades) {
    if (g.from !== null && (date === null || g.from > date)) continue;
    if (best === undefined || (best.from ?? '') < (g.from ?? '')) best = g;
  }
  return best?.grade ?? '';
}

/**
 * The grade's rate in force on a date (yyyy-mm-dd) on the sheet: the one with the latest from-date
 * on or before it, an undated rate counting as from the beginning of time; none when none applies yet,
 * or when that one's to-date has passed by the date. With none on the sheet, its parent's rate in
 * force then, then the parent's parent's, and so on. Undefined when no level has one. Given a claim,
 * only the rates that apply to it count (a forward-only rate applies only to claims added after it).
 */
export function pctOn(rates: readonly Rate[], grade: string, date: string | null, claim?: Claim): string | undefined {
  if (claim !== undefined) rates = rates.filter((r) => appliesTo(r, claim));
  const levels = [...new Set(rates.map((r) => r.level ?? 0))].sort((a, b) => a - b);
  for (const level of levels) {
    const pct = levelPctOn(rates.filter((r) => (r.level ?? 0) === level), grade, date);
    if (pct !== undefined) return pct;
  }
  return undefined;
}

/** The grade's rate in force on a date among one sheet's rates, as {@link pctOn} picks it. */
function levelPctOn(rates: readonly Rate[], grade: string, date: string | null): string | undefined {
  let best: Rate | undefined;
  for (const r of rates) {
    if (r.grade !== grade || (r.from !== null && (date === null || r.from > date))) continue;
    if (best === undefined || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  if (best?.to != null && (date === null || best.to < date)) return undefined;
  return best?.pct;
}

/**
 * What is due on an amount in whole cents at a percent: cents times the rate divided by 100,
 * rounded to whole cents half to even, or half up when `halfUp` (a negative amount rounds as its
 * opposite does, negated). Null when there is no rate.
 */
export function dueCents(amountCents: number, pct: string | undefined, halfUp = false): number | null {
  if (pct === undefined) return null;
  // cents x rate-in-hundredths-of-a-percent is 10000 times the due in cents
  const scaled = BigInt(Math.abs(amountCents)) * BigInt(cents(pct));
  const whole = scaled / 10000n;
  const rest = scaled % 10000n;
  const due = Number(rest > 5000n || (rest === 5000n && (halfUp || whole % 2n === 1n)) ? whole + 1n : whole);
  return amountCents < 0 ? -due : due;
}

/**
 * A claim's fixed figure in whole cents: the one bound by the person's last statement, else the
 * due fixed when it was settled. Null when it has none (its figure is live, or there was no rate).
 */
export function fixedDueCents(c: Claim): number | null {
  const fixed = c.stated ? c.statedDue : c.settledDue;
  return fixed !== null ? cents(fixed) : null;
}

/**
 * Whether a claim's live figures round half up: it rounds by a date (see `roundingDate`); when the
 * sheet has a rounding mode in force on that date (`roundings`, blank: none), by that mode. Else half
 * up before the sheet's policy date, the sheet's today when the policy date is blank; on or after
 * it, half to even.
 */
export function roundsHalfUp(
  roundOn: string,
  policyDate: string,
  today: string | null,
  roundings: readonly DatedValue[] = [],
): boolean {
  const mode = valueOn(roundings, roundOn, '');
  if (mode !== '') return mode === 'half-up';
  const policy = policyDate !== '' ? policyDate : today;
  return policy !== null && roundOn < policy;
}

/**
 * A claim's figure in whole cents: a stated claim's figure bound by the last statement, a settled
 * claim's due fixed when it was settled, any other claim's live due at the rate for the grade in
 * force on the claim's date, rounded half up when `halfUp` (else half to even). The floor is not
 * applied here (see {@link chargedDueCents}). Null when it has none.
 */
export function claimDueCents(c: Claim, rates: readonly Rate[], grade: string, halfUp = false): number | null {
  if (c.settledDue !== null) return cents(c.settledDue);
  if (c.stated) return fixedDueCents(c);
  return dueCents(cents(c.amount), pctOn(rates, grade, c.date, c), halfUp);
}

/** One value recorded for a dated setting: in force from its from-date (blank: from the
 *  beginning of time) until the next. A blank value is none (no limit, no cap). */
export interface DatedValue {
  from: string;
  value: string;
}

/**
 * The value of a dated setting in force on a date (yyyy-mm-dd): the one with the latest from-date
 * on or before it, one from the beginning of time counting as the earliest. `start` before every
 * from-date.
 */
export function valueOn(values: readonly DatedValue[], date: string | null, start: string): string {
  let best: DatedValue | undefined;
  for (const v of values) {
    if (v.from !== '' && (date === null || v.from > date)) continue;
    if (best === undefined || best.from < v.from) best = v;
  }
  return best?.value ?? start;
}

/** The floor a sheet starts with: in force before its first recorded floor. */
export const START_FLOOR = '1.00';

/** The late percent a sheet starts with: in force before its first recorded late percent. */
export const START_LATE_PCT = '0.00';

/** The grace days of a grade in force on a date (yyyy-mm-dd), from each grade's dated grace days
 *  (a blank value: none); 0 when it has none then. */
export function graceOn(graces: Readonly<Record<string, readonly DatedValue[]>>, grade: string, date: string | null): number {
  const days = valueOn(graces[grade] ?? [], date, '');
  return days === '' ? 0 : Number(days);
}

/** What works out an overdue claim's late charge and rounding: the sheet's late percents (each
 *  claim's is the one in force on its date), each grade's grace days (a claim's are those of its
 *  person's grade on its date, in force on its date), today, policy date (blank: the sheet's today)
 *  and rounding modes, dated (blank: none). */
export interface Lateness {
  latePcts: readonly DatedValue[];
  graces: Readonly<Record<string, readonly DatedValue[]>>;
  today: string | null;
  policyDate: string;
  roundings: readonly DatedValue[];
}

/**
 * A claim's due in whole cents as shown: its figure (see {@link claimDueCents}) with, when it is
 * overdue, a late charge added on top: for each full 30 days overdue, the late percent in force on
 * that boundary date (its due-by plus its grace days, plus 30, 60, 90 ... days) of that floored figure plus the charges of the boundaries before it, rounded to whole cents,
 * the charges added up. Both round as the claim rounds by `roundOn` (see {@link roundsHalfUp}).
 * The floor is applied first: the figure is raised to the floor in force on the claim's date, and
 * the charge is worked out on that floored figure. While a statement binds an open claim, its due is the one shown then,
 * late charge included; a settled claim's due is
 * frozen as it was settled, a correction has no floor and no charge, and a waived claim no charge. Null when it has no figure.
 */
export function chargedDueCents(
  c: Claim,
  rates: readonly Rate[],
  grade: string,
  floors: readonly DatedValue[],
  late: Lateness,
  roundOn: string,
): number | null {
  const halfUp = roundsHalfUp(roundOn, late.policyDate, late.today, late.roundings);
  const figure = claimDueCents(c, rates, grade, halfUp);
  if (figure === null || c.settledDue !== null) return figure;
  // while bound, an open claim shows the due as shown when its statement bound it, late charge included
  if (c.stated && c.statedCharged !== null && isOpen(c)) return cents(c.statedCharged);
  if (isCorrection(c)) return figure;
  const floored = Math.max(figure, cents(valueOn(floors, c.date, START_FLOOR)));
  if (!isOpen(c)) return floored;
  const grace = graceOn(late.graces, grade, c.date);
  const periods = c.waived ? 0 : Math.floor(daysOverdue(c, late.today, grace) / 30);
  let charge = 0;
  for (let k = 1; k <= periods; k++) {
    const boundary = plusDays(dueBy(c), grace + 30 * k);
    charge += dueCents(floored + charge, valueOn(late.latePcts, boundary, START_LATE_PCT), halfUp) ?? 0;
  }
  return floored + charge;
}

/** A due in whole cents with two decimals, or n/a when there is no rate. */
export function dueText(c: number | null): string {
  return c === null ? 'n/a' : money(c);
}

@Component({
  selector: 'app-rates',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form aria-label="Rate" (submit)="save($event)">
        <label for="rate-form-grade">Grade</label>
        <select
          id="rate-form-grade"
          data-testid="rate-form-grade"
          [value]="gradeInput()"
          (change)="gradeInput.set($any($event.target).value); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === gradeInput()">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input
          id="rate-form-pct"
          data-testid="rate-form-pct"
          inputmode="decimal"
          [value]="pctInput()"
          (input)="pctInput.set($any($event.target).value); error.set(null)"
        />
        <label for="rate-form-from">From (blank: always)</label>
        <input
          id="rate-form-from"
          type="date"
          data-testid="rate-form-from"
          [value]="fromInput()"
          (input)="fromInput.set($any($event.target).value); error.set(null)"
        />
        <label for="rate-form-to">To (blank: until the next rate)</label>
        <input
          id="rate-form-to"
          type="date"
          data-testid="rate-form-to"
          [value]="toInput()"
          (input)="toInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="rate-form-error">{{ err }}</p>
        }
      </form>
      <form aria-label="Import rates" (submit)="importRates($event)">
        <label for="import-rates">Import rates, one grade,rate,from,to per line</label>
        <textarea
          id="import-rates"
          data-testid="import-rates"
          rows="4"
          [value]="importText()"
          (input)="importText.set($any($event.target).value); importError.set(null)"
        ></textarea>
        <button type="submit" data-testid="import-rates-submit">Import rates</button>
        @if (importSet() !== null) {
          <p>Set <strong data-testid="import-rates-set">{{ importSet() }}</strong></p>
        }
        @if (importError(); as err) {
          <p role="alert" data-testid="import-rates-error">{{ err }}</p>
        }
      </form>
      <form aria-label="Rate preview" (submit)="preview($event)">
        <label for="rate-preview-grade">Preview grade</label>
        <select
          id="rate-preview-grade"
          data-testid="rate-preview-grade"
          [value]="previewGrade()"
          (change)="previewGrade.set($any($event.target).value); clearPreview()"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === previewGrade()">{{ g }}</option>
          }
        </select>
        <label for="rate-preview-pct">Candidate rate (%)</label>
        <input
          id="rate-preview-pct"
          data-testid="rate-preview-pct"
          inputmode="decimal"
          [value]="previewPct()"
          (input)="previewPct.set($any($event.target).value); clearPreview()"
        />
        <button type="submit" data-testid="rate-preview">Preview</button>
        @if (previewResult(); as res) {
          <p>
            Claims that would change: <span data-testid="rate-preview-count">{{ res.count }}</span>;
            difference: <span data-testid="rate-preview-diff">{{ res.diff | figure }}</span>
          </p>
        }
        @if (previewError(); as err) {
          <p role="alert" data-testid="rate-preview-error">{{ err }}</p>
        }
      </form>
      <table aria-label="Rates in force today">
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate today (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ (current().get(g) ?? 'n/a') | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table aria-label="Rate calendar">
        <thead>
          <tr>
            <th scope="col">Grade</th>
            @for (m of calendarMonths(); track m) {
              <th scope="col">{{ m }}</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              @for (m of calendarMonths(); track m) {
                <td [attr.data-testid]="'rate-month-' + g + '-' + m">{{ (calendar().get(g + '-' + m) ?? 'n/a') | figure }}</td>
              }
            </tr>
          }
        </tbody>
      </table>
      <table data-testid="rates-list" aria-label="All rates">
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">From</th>
            <th scope="col">To</th>
            <th scope="col">Rate (%)</th>
            <th scope="col">Versions</th>
          </tr>
        </thead>
        <tbody>
          @for (r of sorted(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? 'always' }}</td>
              <td data-testid="rate-to">{{ r.to ?? 'open' }}</td>
              <td data-testid="rate-pct">{{ r.pct | figure }}</td>
              <td data-testid="rate-version">{{ r.version ?? 1 }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Rates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's rates, shared with whoever works out what is due. */
  readonly rates = model<Rate[]>([]);

  protected readonly grades = GRADES;
  /** Each grade's own rates (not its ancestors'), the undated one first and then by from-date. */
  protected readonly sorted = computed(() =>
    this.rates().filter((r) => (r.level ?? 0) === 0).sort(
      (a, b) => a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? ''),
    ),
  );
  /** Each grade's rate in force on the sheet's today. */
  protected readonly current = computed(() => {
    const today = this.session.figureDay();
    return new Map(GRADES.map((g) => [g, pctOn(this.rates(), g, today)]));
  });
  /** Each month (yyyy-mm) of the sheet's today's calendar year; none without a today. */
  protected readonly calendarMonths = computed(() => {
    const year = this.session.figureDay()?.slice(0, 4);
    if (!year) return [];
    return Array.from({ length: 12 }, (_, i) => `${year}-${String(i + 1).padStart(2, '0')}`);
  });
  /** Each grade's rate in force on the first day of each calendar month, keyed grade-yyyy-mm. */
  protected readonly calendar = computed(() => {
    const rates = this.rates();
    const out = new Map<string, string | undefined>();
    for (const g of GRADES) {
      for (const m of this.calendarMonths()) out.set(`${g}-${m}`, pctOn(rates, g, `${m}-01`));
    }
    return out;
  });
  protected readonly gradeInput = signal<string>('A');
  protected readonly pctInput = signal('');
  protected readonly fromInput = signal('');
  protected readonly toInput = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly importText = signal('');
  /** How many rates the last import set; cleared while the next one runs. */
  protected readonly importSet = signal<number | null>(null);
  protected readonly importError = signal<string | null>(null);
  /** Whether an import is waiting for its answer: a second one meanwhile is ignored. */
  private importing = false;
  protected readonly previewGrade = signal<string>('A');
  protected readonly previewPct = signal('');
  /** The last preview's answer: how many claims would change and the sum of the differences. */
  protected readonly previewResult = signal<{ count: number; diff: string } | null>(null);
  protected readonly previewError = signal<string | null>(null);
  /** Counts each preview pressed, so only the latest one's answer is shown. */
  private previewRun = 0;
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's rates from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.rates.set([]);
      this.gradeInput.set('A');
      this.pctInput.set('');
      this.fromInput.set('');
      this.toInput.set('');
      this.error.set(null);
      this.importText.set('');
      this.importSet.set(null);
      this.importError.set(null);
      this.previewGrade.set('A');
      this.previewPct.set('');
      this.clearPreview();
      if (sheet) {
        void this.fetch(sheet);
      }
    });
    // Re-read them when the sheet's parent changes: its ancestors' rates stand in for missing ones.
    let parentSeen: string | null = null;
    effect(() => {
      const parent = this.session.parent();
      const sheet = untracked(() => this.session.sheet());
      if (parentSeen !== null && parent !== parentSeen && sheet) void this.fetch(sheet);
      parentSeen = parent;
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.post<Rate[]>('/api/rates/list', { sheet }));
      if (this.session.sheet() === sheet) this.rates.set(list);
    } catch {
      this.error.set('Could not read the rates on this sheet.');
    }
  }

  /** Set every pasted line's rate, or none when any line breaks the rate form's rules. */
  protected async importRates(event: Event): Promise<void> {
    event.preventDefault();
    if (this.importing) return;
    this.importSet.set(null);
    this.importError.set(null);
    const sheet = this.session.sheet();
    this.importing = true;
    try {
      const res = await firstValueFrom(
        this.http.post<{ set: number; rates: Rate[] }>('/api/rates/import', {
          sheet,
          user: this.session.user(),
          text: this.importText(),
        }),
      );
      if (!sheet || this.session.sheet() !== sheet) return;
      await this.fetch(sheet);
      this.importSet.set(res.set);
    } catch (e) {
      const reason = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' ? e.error.message : '';
      this.importError.set(reason || 'Could not import these rates.');
    } finally {
      this.importing = false;
    }
  }

  protected clearPreview(): void {
    this.previewRun++;
    this.previewResult.set(null);
    this.previewError.set(null);
  }

  /** Ask how many claims the candidate rate would change for the grade; nothing is saved. */
  protected async preview(event: Event): Promise<void> {
    event.preventDefault();
    this.clearPreview();
    const run = this.previewRun;
    const sheet = this.session.sheet();
    const grade = this.previewGrade();
    const pct = this.previewPct().trim();
    if (!GRADES.includes(grade as (typeof GRADES)[number])) {
      this.previewError.set('Choose a grade of A, B or C.');
      return;
    }
    if (!PCT.test(pct)) {
      this.previewError.set('Enter a rate from 0.00 to 99999.99, with up to two decimals.');
      return;
    }
    try {
      const res = await firstValueFrom(
        this.http.post<{ count: number; diff: string }>('/api/rates/preview', { sheet, grade, pct }),
      );
      if (run === this.previewRun && this.session.sheet() === sheet) this.previewResult.set(res);
    } catch {
      if (run === this.previewRun) this.previewError.set('Could not preview this rate.');
    }
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const grade = this.gradeInput();
    const pct = this.pctInput().trim();
    const from = this.fromInput().trim() || null;
    const to = this.toInput().trim() || null;
    if (!GRADES.includes(grade as (typeof GRADES)[number])) {
      this.error.set('Choose a grade of A, B or C.');
      return;
    }
    if (!PCT.test(pct)) {
      this.error.set('Enter a rate from 0.00 to 99999.99, with up to two decimals.');
      return;
    }
    if (from !== null && (!DATE.test(from) || Number.isNaN(Date.parse(from)))) {
      this.error.set('Enter a from-date as yyyy-mm-dd, or leave it blank for always.');
      return;
    }
    if (to !== null && (!DATE.test(to) || Number.isNaN(Date.parse(to)))) {
      this.error.set('Enter a to-date as yyyy-mm-dd, or leave it blank for until the next rate.');
      return;
    }
    if (to !== null && from !== null && to < from) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Rate>('/api/rates/set', { sheet, user: this.session.user(), grade, pct, from, to }),
      );
      if (this.session.sheet() !== sheet) return;
      this.rates.update((list) => [
        ...list.filter((r) => (r.level ?? 0) !== 0 || r.grade !== saved.grade || r.from !== saved.from),
        saved,
      ]);
      this.pctInput.set('');
      this.fromInput.set('');
      this.toInput.set('');
    } catch {
      this.error.set('Could not set this rate.');
    } finally {
      this.submitting = false;
    }
  }
}
