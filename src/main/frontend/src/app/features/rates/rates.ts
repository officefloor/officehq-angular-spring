import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Rate {
  grade: string;
  /** The first date (yyyy-mm-dd) it applies on; null when it applies from the beginning of time. */
  from: string | null;
  /** The last date (yyyy-mm-dd) it applies on; null when it has no end. */
  to: string | null;
  /** A percent, always two decimals, e.g. "12.50". */
  pct: string;
  /** How many versions it has; the pct is the newest. */
  versions: number;
}

export const GRADES = ['A', 'B', 'C'];

/** Shown for a due that has no rate. */
export const NA = 'n/a';

/** Cents times a percent (two decimals), divided by 100, rounded half to even (or half up) to whole cents. */
export function dueCents(amountCents: number, pct: string, halfUp = false): number {
  const [whole, frac = ''] = pct.split('.');
  const pctHundredths = BigInt(whole) * 100n + BigInt(frac.padEnd(2, '0'));
  // Rounded on the size of the product, so a negative due rounds as its positive would.
  const sign = amountCents < 0 ? -1 : 1;
  const product = BigInt(Math.abs(amountCents)) * pctHundredths;
  const quotient = product / 10000n;
  const remainder = product % 10000n;
  const up = remainder > 5000n || (remainder === 5000n && (halfUp || quotient % 2n === 1n));
  return sign * Number(up ? quotient + 1n : quotient);
}

/** Of one grade's rates, the percent in force on a date (the latest from-date on or before it, unless the
 *  date is after that rate's to-date), or null. */
export function pctOn(rates: Rate[], date: string): string | null {
  return rateOn(rates, date)?.pct ?? null;
}

/** Of one grade's rates, the one in force on a date (as `pctOn` has it), or null. */
export function rateOn(rates: Rate[], date: string): Rate | null {
  // No from-date sorts as '', before every date.
  let best: Rate | null = null;
  for (const r of rates) {
    const from = r.from ?? '';
    if (from <= date && (best === null || from > (best.from ?? ''))) best = r;
  }
  if (best === null || (best.to !== null && date > best.to)) return null;
  return best;
}

/** Of one grade's rates in levels (the sheet's own, then each ancestor's, nearest first), the one in force on a
 *  date: the first level's that has one (as `rateOn` has it), or null. */
export function rateInLevels(levels: Rate[][], date: string): Rate | null {
  for (const rates of levels) {
    const rate = rateOn(rates, date);
    if (rate !== null) return rate;
  }
  return null;
}

// The sheet's rates, each for a grade from a from-date (or from the start), with one form to set
// a grade's rate from a date (and through a date, if it ends); and per grade, the rate in force on the sheet's today
// (its own, else its nearest ancestor's in force then).
@Component({
  selector: 'app-rates',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form [formGroup]="form" (ngSubmit)="save()" aria-label="Set rate">
        <label for="rate-form-grade">Grade</label>
        <select id="rate-form-grade" data-testid="rate-form-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input id="rate-form-pct" data-testid="rate-form-pct" formControlName="pct" inputmode="decimal" />
        <label for="rate-form-from">From (blank: from the start)</label>
        <input id="rate-form-from" type="date" data-testid="rate-form-from" formControlName="from" />
        <label for="rate-form-to">To (blank: no end)</label>
        <input id="rate-form-to" type="date" data-testid="rate-form-to" formControlName="to" />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="rate-form-error">{{ error() }}</p>
        }
      </form>
      <form [formGroup]="importForm" (ngSubmit)="importRates()" aria-label="Import rates">
        <label for="import-rates">Import rates (grade,rate,from,to per line; from and to may be left out)</label>
        <textarea id="import-rates" data-testid="import-rates" formControlName="text" rows="4"></textarea>
        <button type="submit" data-testid="import-rates-submit">Import</button>
        @if (importSet() !== null) {
          <p role="status">Set <span data-testid="import-rates-set">{{ importSet() }}</span></p>
        }
        @if (importError()) {
          <p role="alert" class="error" data-testid="import-rates-error">{{ importError() }}</p>
        }
      </form>
      <form [formGroup]="previewForm" (ngSubmit)="preview()" aria-label="Preview rate">
        <label for="rate-preview-grade">Grade</label>
        <select id="rate-preview-grade" data-testid="rate-preview-grade" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="rate-preview-pct">Candidate rate (%)</label>
        <input id="rate-preview-pct" data-testid="rate-preview-pct" formControlName="pct" inputmode="decimal" />
        <button type="submit" data-testid="rate-preview">Preview</button>
        @if (previewed(); as p) {
          <p aria-live="polite">
            Claims changed <span data-testid="rate-preview-count">{{ p.count }}</span>,
            difference <span data-testid="rate-preview-diff">{{ p.diff }}</span>
          </p>
        }
        @if (previewError()) {
          <p role="alert" class="error" data-testid="rate-preview-error">{{ previewError() }}</p>
        }
      </form>
      <table>
        <caption>In force today</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <td>{{ g }}</td>
              <td [attr.data-testid]="'rate-current-' + g">{{ currentOf(g) ?? na }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>All rates</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Rate (%)</th><th scope="col">Versions</th></tr>
        </thead>
        <tbody>
          @for (r of sorted(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <td>{{ r.grade }}</td>
              <td data-testid="rate-from">{{ r.from ?? 'start' }}</td>
              <td data-testid="rate-to">{{ r.to ?? '' }}</td>
              <td data-testid="rate-pct">{{ r.pct }}</td>
              <td data-testid="rate-version">{{ r.versions }}</td>
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

  readonly rates = input.required<Rate[]>();
  /** The rates of the sheet's ancestors, nearest first: a grade with none of its own in force on a date takes theirs. */
  readonly inherited = input<Rate[][]>([]);
  /** The sheet's today, for the rate in force on it. */
  readonly today = input.required<string | null>();
  /** A rate set, as the store now holds it. */
  readonly saved = output<Rate>();
  /** The rates an import set, as the store now holds them, in the order of their lines. */
  readonly imported = output<Rate[]>();

  protected readonly grades = GRADES;
  protected readonly na = NA;
  protected readonly error = signal('');
  /** A submit is under way; another one waits for its answer rather than storing twice. */
  private saving = false;
  protected readonly form = new FormGroup({
    grade: new FormControl('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });
  protected readonly importForm = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  /** How many rates the last import set; null while none has been done since the box was pressed. */
  protected readonly importSet = signal<number | null>(null);
  protected readonly importError = signal('');
  private importing = false;
  protected readonly previewForm = new FormGroup({
    grade: new FormControl('A', { nonNullable: true }),
    pct: new FormControl('', { nonNullable: true }),
  });
  /** The last preview's answer: how many open claims' dues would change, and the sum of the differences. */
  protected readonly previewed = signal<{ count: number; diff: string } | null>(null);
  protected readonly previewError = signal('');
  /** Counts each preview, so only the latest one's answer is shown. */
  private previewSeq = 0;
  /** By grade, then from-date, the one from the start first. */
  protected readonly sorted = computed(() =>
    [...this.rates()].sort((a, b) =>
      a.grade !== b.grade ? (a.grade < b.grade ? -1 : 1) : (a.from ?? '') < (b.from ?? '') ? -1 : 1,
    ),
  );

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.importForm.valueChanges.subscribe(() => this.importError.set(''));
    this.previewForm.valueChanges.subscribe(() => {
      this.previewSeq++;
      this.previewed.set(null);
      this.previewError.set('');
    });
  }

  protected currentOf(grade: string): string | null {
    const today = this.today();
    if (today === null) return null;
    const levels = [this.rates(), ...this.inherited()].map((rates) => rates.filter((r) => r.grade === grade));
    return rateInLevels(levels, today)?.pct ?? null;
  }

  protected async save(): Promise<void> {
    if (this.saving) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.form.getRawValue();
    const pct = raw.pct.trim();
    const from = raw.from.trim();
    const to = raw.to.trim();
    if (!GRADES.includes(raw.grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    if (!/^\d{1,5}(\.\d{1,2})?$/.test(pct)) {
      this.error.set('Enter a percent of 0.00 or more, with at most two decimals.');
      return;
    }
    if (from && !/^\d{4}-\d{2}-\d{2}$/.test(from)) {
      this.error.set('Enter a from-date, or leave it blank for from the start.');
      return;
    }
    if (to && !/^\d{4}-\d{2}-\d{2}$/.test(to)) {
      this.error.set('Enter a to-date, or leave it blank for no end.');
      return;
    }
    if (to && from && to < from) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    this.saving = true;
    try {
      const rate = await firstValueFrom(
        this.http.put<Rate>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${raw.grade}`, { pct, from: from || null, to: to || null }, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(rate);
    } catch {
      this.error.set('Could not set that rate.');
    } finally {
      this.saving = false;
    }
  }

  /**
   * Set a rate per pasted line of grade,rate,from,to, in order, each as the form sets one. Every line
   * is checked first; if any is refused, nothing is set.
   */
  protected async importRates(): Promise<void> {
    if (this.importing) return;
    this.importError.set('');
    this.importSet.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = this.importForm.getRawValue().text;
    if (!text.trim()) {
      this.importError.set('Paste at least one line of grade,rate,from,to.');
      return;
    }
    this.importing = true;
    try {
      const done = await firstValueFrom(
        this.http.post<{ set: number; rates: Rate[] }>(`/api/sheets/${encodeURIComponent(sheet)}/rates/import`, { text }, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.imported.emit(done.rates);
      this.importSet.set(done.set);
      this.importForm.reset({ text: '' }, { emitEvent: false });
    } catch {
      if (this.session.sheetId() !== sheet) return;
      this.importError.set('Every line must be grade,rate,from,to as the rate form takes them; nothing was set.');
    } finally {
      this.importing = false;
    }
  }

  /** Ask the store what the candidate rate would change for the grade; nothing is saved. */
  protected async preview(): Promise<void> {
    const seq = ++this.previewSeq;
    this.previewed.set(null);
    this.previewError.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.previewForm.getRawValue();
    const pct = raw.pct.trim();
    if (!GRADES.includes(raw.grade)) {
      this.previewError.set('Choose a grade.');
      return;
    }
    if (!/^\d{1,5}(\.\d{1,2})?$/.test(pct)) {
      this.previewError.set('Enter a percent of 0.00 or more, with at most two decimals.');
      return;
    }
    try {
      const result = await firstValueFrom(
        this.http.get<{ count: number; diff: string }>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${raw.grade}/preview`, {
          params: { pct },
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (seq !== this.previewSeq || this.session.sheetId() !== sheet) return;
      this.previewed.set(result);
    } catch {
      if (seq === this.previewSeq) this.previewError.set('Could not preview that rate.');
    }
  }
}
