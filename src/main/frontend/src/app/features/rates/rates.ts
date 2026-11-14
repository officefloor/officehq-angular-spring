import { HttpClient } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { GradeStore } from '../grades/grades';

export interface Rate {
  grade: string;
  /** A percent with two decimals, e.g. "12.50". */
  rate: string;
  /** The date the rate applies from (yyyy-mm-dd); null when it applies from the beginning of time. */
  from: string | null;
  /** The last date the rate applies on (yyyy-mm-dd); null when it has no end. */
  to: string | null;
  /** How many versions the rate has; the one shown, in force, is the newest. */
  version: number;
  /** For a forward-only rate (saved with a from-date on or after the sheet's today), the id of the
   *  newest claim there was when it was saved: it applies only to claims with a higher id. Null when
   *  it applies to every claim. */
  forwardAfter: number | null;
  /** For a forward-only rate, its older versions, newest first: a claim it does not apply to keeps
   *  the newest of them that does. */
  earlier: EarlierRate[];
}

/** An older version of a rate: its value, to-date and forward-only mark as the rate has them. */
export interface EarlierRate {
  rate: string;
  to: string | null;
  forwardAfter: number | null;
}

/** Whether a rate (or a version of one) applies to a claim: it is not forward-only, or the claim was
 *  added after it was saved. */
function appliesTo(forwardAfter: number | null, claimId: number): boolean {
  return forwardAfter === null || claimId > forwardAfter;
}

/** A grade's rates as they apply to a claim: each forward-only rate saved before the claim was added
 *  is taken as the newest of its older versions that applies to the claim, or left out when none
 *  does. */
export function ratesFor(rates: Rate[], claimId: number): Rate[] {
  if (rates.every((r) => r.forwardAfter === null)) return rates;
  const theirs: Rate[] = [];
  for (const r of rates) {
    if (appliesTo(r.forwardAfter, claimId)) {
      theirs.push(r);
      continue;
    }
    const version = r.earlier.find((e) => appliesTo(e.forwardAfter, claimId));
    if (version) theirs.push({ ...r, rate: version.rate, to: version.to, forwardAfter: version.forwardAfter });
  }
  return theirs;
}


/** Shown for a due that has no rate to work from. */
export const NO_RATE = 'n/a';

/** The due on an amount in cents at a percent rate (two decimals), in cents: amount times rate
 *  divided by 100, rounded half up when `halfUp`, else half to even (a negative amount rounds as its
 *  opposite does). Worked in whole numbers so nothing is lost to floating point. */
export function dueCents(amountCents: number, rate: string, halfUp: boolean): number {
  if (amountCents < 0) return -dueCents(-amountCents, rate, halfUp);
  const hundredths = BigInt(Math.round(Number(rate) * 100));
  const product = BigInt(amountCents) * hundredths;
  const whole = product / 10000n;
  const rest = product % 10000n;
  return Number(rest > 5000n || (rest === 5000n && (halfUp || whole % 2n === 1n)) ? whole + 1n : whole);
}

/** The rate in force on a date among a grade's rates: the one whose from-date is the latest on or
 *  before the date (no from-date applies from the beginning of time); null when none applies, or
 *  when that one's to-date is before the date. */
export function rateOn(rates: Rate[], date: string): string | null {
  let best: Rate | null = null;
  for (const r of rates) {
    if (r.from !== null && r.from > date) continue;
    if (best === null || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  if (best?.to && best.to < date) return null;
  return best?.rate ?? null;
}

/** A grade a person was given, in force from its from-date. */
export interface PersonGrade {
  grade: string;
  /** The date the grade applies from (yyyy-mm-dd); null when it applies from the beginning of time,
   *  as the first grade a person was given does. */
  from: string | null;
}

/** The grade in force on a date among a person's grades: the one whose from-date is the latest on
 *  or before the date; null when none applies. */
export function gradeOn(grades: PersonGrade[], date: string): string | null {
  let best: PersonGrade | null = null;
  for (const g of grades) {
    if (g.from !== null && g.from > date) continue;
    if (best === null || (best.from ?? '') < (g.from ?? '')) best = g;
  }
  return best?.grade ?? null;
}

/** Rates in the order they are listed: by grade, then from the earliest. */
function byGradeAndFrom(a: Rate, b: Rate): number {
  return a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? '');
}

/** Rates by grade, each grade's in the order given; a grade with no rate is absent. */
function byGradeOf(rates: Rate[]): Map<string, Rate[]> {
  const map = new Map<string, Rate[]>();
  for (const r of rates) map.set(r.grade, [...(map.get(r.grade) ?? []), r]);
  return map;
}

/** How a candidate rate would change the dues of a grade's open claims. */
export interface RatePreview {
  /** How many OPEN and PENDING claims would show a different due. */
  count: number;
  /** The sum of the differences (new due minus current due), two decimals. */
  diff: string;
}

const PERCENT = /^\d{1,5}(\.\d{1,2})?$/;

// The active sheet's rates for each grade, each from its from-date, and those of the sheets they fall
// back to (its parent, the parent's parent, up to three levels up, then the sheet it was cloned from
// and its own), re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class RateStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  private readonly gradeStore = inject(GradeStore);

  /** The sheet's rates by grade, each grade's from the earliest. */
  readonly rates = signal<Rate[]>([]);
  /** The rates of the sheets the sheet's rates fall back to, nearest first, each listed as `rates`. */
  readonly inherited = signal<Rate[][]>([]);
  /** Each grade's rates by grade; a grade with no rate is absent. */
  readonly byGrade = computed(() => byGradeOf(this.rates()));
  /** Each fall-back sheet's rates by grade, nearest first. */
  private readonly inheritedByGrade = computed(() => this.inherited().map(byGradeOf));
  /** Each grade's rate in force on the sheet's today, falling back to the parent sheets'; a grade
   *  with none is absent. */
  readonly current = computed(() => {
    const today = this.session.today();
    const map = new Map<string, string>();
    if (!today) return map;
    for (const grade of this.gradeStore.codes()) {
      const rate = rateOn(this.ofOn(grade, today), today);
      if (rate !== null) map.set(grade, rate);
    }
    return map;
  });
  /** The months (yyyy-mm) of the calendar year of the sheet's today; none when it has no today. */
  readonly months = computed(() => {
    const year = this.session.today()?.slice(0, 4);
    if (!year) return [];
    return Array.from({ length: 12 }, (_, i) => `${year}-${String(i + 1).padStart(2, '0')}`);
  });
  /** For each grade and month of `months`, the rate in force on the month's first day, falling back
   *  to the parent sheets' as `current` does; null when none is. Keyed grade-yyyy-mm. */
  readonly calendar = computed(() => {
    const map = new Map<string, string | null>();
    for (const grade of this.gradeStore.codes()) {
      for (const month of this.months()) {
        const first = `${month}-01`;
        map.set(`${grade}-${month}`, rateOn(this.ofOn(grade, first), first));
      }
    }
    return map;
  });

  /** A grade's rates; none when the grade has no rate. */
  of(grade: string): Rate[] {
    return this.byGrade().get(grade) ?? [];
  }

  /** A grade's rates to work a date's rate from: the sheet's own when one is in force on the date,
   *  else those of the nearest parent sheet with one in force on it; the sheet's own when none is.
   *  For a claim (its id), only the rates as they apply to it count (see ratesFor). */
  ofOn(grade: string, date: string, claimId?: number): Rate[] {
    const of = (rates: Rate[]) => (claimId === undefined ? rates : ratesFor(rates, claimId));
    const own = of(this.of(grade));
    if (rateOn(own, date) !== null) return own;
    for (const level of this.inheritedByGrade()) {
      const theirs = of(level.get(grade) ?? []);
      if (rateOn(theirs, date) !== null) return theirs;
    }
    return own;
  }

  /** The rates of a person's grade in force on a claim's date to work the claim's rate from, as they
   *  apply to the claim (see ofOn); none when that grade has no rate. */
  ofGradeOn(grades: PersonGrade[], claim: { id: number; date: string }): Rate[] {
    const grade = gradeOn(grades, claim.date);
    return grade === null ? [] : this.ofOn(grade, claim.date, claim.id);
  }

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.rates.set([]);
      this.inherited.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Re-read the rates the active sheet falls back to: its parent sheet has changed. */
  async readInherited(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const load = this.session.loads();
    const inherited = await firstValueFrom(
      this.http.get<Rate[][]>(`/api/sheets/${encodeURIComponent(sheet)}/rates/inherited`),
    );
    if (this.session.loads() !== load || this.session.sheetId() !== sheet) return;
    this.inherited.set(inherited);
  }

  /** Set a grade's rate from a date (null: from the beginning of time) through a date (null: no
   *  end) on the active sheet, replacing the one the grade had from that date. */
  async set(grade: string, rate: string, from: string | null, to: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<Rate>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}`, { rate, from, to }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.rates.update((list) =>
      [...list.filter((r) => r.grade !== saved.grade || r.from !== saved.from), saved].sort(byGradeAndFrom),
    );
  }

  /** Withdraw the newest version of a grade's rate from a date (null: from the beginning of time) on
   *  the active sheet: the version before it applies again, or with none the rate is gone. The
   *  sheet's rates are then re-read. */
  async withdraw(grade: string, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const load = this.session.loads();
    await firstValueFrom(
      this.http.post<void>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}/withdraw`, null, {
        params: from === null ? {} : { from },
      }),
    );
    if (this.session.sheetId() === sheet) await this.read(sheet, load);
  }

  /** Set a rate per pasted line of grade,rate,from,to on the active sheet, each as `set` would; none
   *  when any line is refused. How many were set; the sheet's rates are then re-read. */
  async import(text: string): Promise<number> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const load = this.session.loads();
    const saved = await firstValueFrom(
      this.http.post<Rate[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates/import`, { text }),
    );
    if (this.session.sheetId() === sheet) await this.read(sheet, load);
    return saved.length;
  }

  /** Preview a candidate rate for a grade on the active sheet; nothing is saved. */
  async preview(grade: string, rate: string): Promise<RatePreview> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    return firstValueFrom(
      this.http.get<RatePreview>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}/preview`, {
        params: { rate },
      }),
    );
  }

  private async read(sheet: string, load: number): Promise<void> {
    const [list, inherited] = await Promise.all([
      firstValueFrom(this.http.get<Rate[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates`)),
      firstValueFrom(this.http.get<Rate[][]>(`/api/sheets/${encodeURIComponent(sheet)}/rates/inherited`)),
    ]);
    if (this.session.loads() !== load) return;
    this.rates.set(list);
    this.inherited.set(inherited);
  }
}

// The sheet's rate in force today for each grade, every rate with its from-date, and the form that
// sets one.
@Component({
  selector: 'app-rates',
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form (submit)="submit($event)">
        <label for="rate-form-grade">Grade</label>
        <select
          id="rate-form-grade"
          data-testid="rate-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input
          id="rate-form-pct"
          data-testid="rate-form-pct"
          inputmode="decimal"
          [value]="pct()"
          (input)="pct.set(value($event)); error.set(null)"
        />
        <label for="rate-form-from">From (blank: always)</label>
        <input
          id="rate-form-from"
          type="date"
          data-testid="rate-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="rate-form-to">To (blank: no end)</label>
        <input
          id="rate-form-to"
          type="date"
          data-testid="rate-form-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
      </form>
      @if (error(); as message) {
        <p role="alert" data-testid="rate-form-error">{{ message }}</p>
      }
      <form (submit)="importRates($event)" aria-label="Import rates">
        <label for="import-rates">Import rates, one per line as grade,rate,from,to (from and to may be left out)</label>
        <textarea
          id="import-rates"
          data-testid="import-rates"
          rows="4"
          [value]="importText()"
          (input)="importText.set(value($event)); importError.set(null)"
        ></textarea>
        <button type="submit" data-testid="import-rates-submit">Import rates</button>
        @if (importSet() !== null) {
          Set <strong data-testid="import-rates-set">{{ importSet() }}</strong>
        }
      </form>
      @if (importError(); as message) {
        <p role="alert" data-testid="import-rates-error">{{ message }}</p>
      }
      <form (submit)="runPreview($event)">
        <label for="rate-preview-grade">Preview grade</label>
        <select
          id="rate-preview-grade"
          data-testid="rate-preview-grade"
          [value]="previewGrade()"
          (change)="previewGrade.set(value($event)); previewChanged()"
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
          (input)="previewPct.set(value($event)); previewChanged()"
        />
        <button type="submit" data-testid="rate-preview">Preview</button>
      </form>
      @if (previewError(); as message) {
        <p role="alert" data-testid="rate-preview-error">{{ message }}</p>
      }
      @if (previewResult(); as result) {
        <p>
          Claims that would change: <span data-testid="rate-preview-count">{{ result.count }}</span>;
          difference: <span data-testid="rate-preview-diff">{{ result.diff }}</span>
        </p>
      }
      <table>
        <caption>In force today</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ store.current().get(g) ?? noRate }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>Rate calendar: in force on the first of each month</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            @for (m of store.months(); track m) {
              <th scope="col">{{ m }}</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              @for (m of store.months(); track m) {
                <td [attr.data-testid]="'rate-month-' + g + '-' + m">{{ store.calendar().get(g + '-' + m) ?? noRate }}</td>
              }
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>All rates</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">From</th>
            <th scope="col">To</th>
            <th scope="col">Rate (%)</th>
            <th scope="col">Versions</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (r of store.rates(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? 'always' }}</td>
              <td data-testid="rate-to">{{ r.to ?? '' }}</td>
              <td data-testid="rate-pct">{{ r.rate }}</td>
              <td data-testid="rate-version">{{ r.version }}</td>
              <td>
                <button
                  type="button"
                  data-testid="rate-withdraw"
                  [attr.aria-label]="'Withdraw rate ' + r.grade + ' from ' + (r.from ?? 'always')"
                  (click)="withdraw(r)"
                >
                  Withdraw
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Rates {
  protected readonly store = inject(RateStore);
  private readonly session = inject(Session);
  protected get grades(): string[] {
    return this.gradeStore.codes();
  }
  private readonly gradeStore = inject(GradeStore);
  protected readonly noRate = NO_RATE;
  protected readonly grade = signal('A');
  protected readonly pct = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly previewGrade = signal('A');
  protected readonly previewPct = signal('');
  protected readonly previewError = signal<string | null>(null);
  protected readonly previewResult = signal<RatePreview | null>(null);
  protected readonly importText = signal('');
  /** How many rates the last import set; null before one, or while one runs. */
  protected readonly importSet = signal<number | null>(null);
  protected readonly importError = signal<string | null>(null);
  private readonly importBusy = signal(false);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);

  constructor() {
    // A loaded sheet starts the import box afresh: its last count and error were for the sheet before.
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.importText.set('');
        this.importSet.set(null);
        this.importError.set(null);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  /** A changed grade or rate drops the last preview and its error: they were for the old one. */
  protected previewChanged(): void {
    this.previewError.set(null);
    this.previewResult.set(null);
  }

  protected async runPreview(event: Event): Promise<void> {
    event.preventDefault();
    this.previewError.set(null);
    this.previewResult.set(null);
    const pct = this.previewPct().trim();
    if (!PERCENT.test(pct)) {
      this.previewError.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    try {
      this.previewResult.set(await this.store.preview(this.previewGrade(), pct));
    } catch {
      this.previewError.set('The rate could not be previewed.');
    }
  }

  /** Set a rate per pasted line of grade,rate,from,to, each as the form would; when any line is
   *  refused nothing is set. How many were set then shows. */
  protected async importRates(event: Event): Promise<void> {
    event.preventDefault();
    if (this.importBusy()) return;
    this.importError.set(null);
    this.importSet.set(null);
    const text = this.importText();
    if (!text.trim()) {
      this.importError.set('Paste at least one line of grade,rate,from,to.');
      return;
    }
    this.importBusy.set(true);
    try {
      this.importSet.set(await this.store.import(text));
      this.importText.set('');
    } catch {
      this.importError.set(
        'The rates could not be imported: every line needs a grade of A, B or C, a rate of 0.00 or more ' +
          'with up to two decimals, and dates (yyyy-mm-dd) with the to-date not before the from-date.',
      );
    } finally {
      this.importBusy.set(false);
    }
  }

  /** Withdraw a rate's newest version; a refusal shows on the rate form's error. */
  protected async withdraw(rate: Rate): Promise<void> {
    if (this.busy()) return;
    this.error.set(null);
    this.busy.set(true);
    try {
      await this.store.withdraw(rate.grade, rate.from);
    } catch {
      this.error.set('The rate could not be withdrawn: only a steward withdraws a rate.');
    } finally {
      this.busy.set(false);
    }
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const pct = this.pct().trim();
    if (!PERCENT.test(pct)) {
      this.error.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    const from = this.from().trim() || null;
    const to = this.to().trim() || null;
    if (from !== null && to !== null && to < from) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.set(this.grade(), pct, from, to);
    } catch {
      this.error.set('The rate could not be set.');
    } finally {
      this.busy.set(false);
    }
  }
}
