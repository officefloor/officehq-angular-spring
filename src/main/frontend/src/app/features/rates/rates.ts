import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, output, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { firstValueFrom } from 'rxjs';
import { GradeList } from '../grades/grades';
import { Session } from '../../core/session';

interface Rate {
  grade: string;
  /** The first date the rate applies to (yyyy-mm-dd); null when it applies from the beginning of time. */
  from: string | null;
  /** The last date the rate applies to (yyyy-mm-dd); null when it has no end. */
  to: string | null;
  pct: string;
  /** How many versions the rate has had; the newest is the one in force. */
  version: number;
  /** Whether it was saved with a from-date on or after the sheet's today: it applies only to claims added after. */
  forwardOnly: boolean;
}

/** What a candidate rate would do: how many claims' dues would differ, and the sum of the differences. */
interface Preview {
  count: number;
  diff: string;
}

const PCT = /^\d{1,12}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Per grade, the rate in force on the date: the one with the latest from-date on or before it, unless its to-date is before it. */
function inForce(rates: Rate[], date: string): Record<string, string> {
  const latest: Record<string, Rate> = {};
  for (const r of rates) {
    if ((r.from === null || r.from <= date) && (latest[r.grade] === undefined || (latest[r.grade].from ?? '') <= (r.from ?? ''))) {
      latest[r.grade] = r;
    }
  }
  const found: Record<string, string> = {};
  for (const [g, r] of Object.entries(latest)) {
    if (r.to === null || r.to >= date) found[g] = r.pct;
  }
  return found;
}

/** Each grade's rates, earliest first, one with no from-date before all. */
function byGradeAndFrom(a: Rate, b: Rate): number {
  return a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? '');
}

// The sheet's rates for each grade, a percent with two decimals from a from-date (or from the
// beginning of time) through an optional to-date, the rate in force on the sheet's today per grade and on the first day of each month of its year, and the form that sets one. A
// rate that was set is handed up as `changed`, so the dues it makes can be shown again.
@Component({
  selector: 'app-rates',
  imports: [FigurePipe],
  template: `
    <section aria-label="Rates">
      <h2>Rates</h2>
      <table>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades(); track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ (current()[g] ?? 'n/a') | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      @if (calendar().length) {
        <table aria-label="Rate calendar">
          <caption>Rate calendar: the rate (%) in force on the first day of each month</caption>
          <thead>
            <tr>
              <th scope="col">Grade</th>
              @for (m of calendar(); track m.month) {
                <th scope="col">{{ m.month }}</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (g of grades(); track g) {
              <tr>
                <th scope="row">{{ g }}</th>
                @for (m of calendar(); track m.month) {
                  <td [attr.data-testid]="'rate-month-' + g + '-' + m.month">{{ (m.rates[g] ?? 'n/a') | figure }}</td>
                }
              </tr>
            }
          </tbody>
        </table>
      }
      <ul aria-label="Rates set">
        @for (r of rates(); track r.grade + (r.from ?? '')) {
          <li [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
            Grade {{ r.grade }} from <span data-testid="rate-from">{{ r.from ?? 'start' }}</span>
            @if (r.to) {
              through <span data-testid="rate-to">{{ r.to }}</span>
            }
            : <span data-testid="rate-pct">{{ r.pct | figure }}</span>%
            (version <span data-testid="rate-version">{{ r.version }}</span>)
            @if (r.forwardOnly) {
              <span data-testid="rate-forward-only">forward-only</span>
            }
          </li>
        }
      </ul>
      <form (submit)="save($event)" aria-label="Set rate">
        <label for="rate-form-grade">Grade</label>
        <select
          id="rate-form-grade"
          data-testid="rate-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set('')"
        >
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input
          id="rate-form-pct"
          inputmode="decimal"
          data-testid="rate-form-pct"
          [value]="pct()"
          (input)="pct.set(value($event)); error.set('')"
        />
        <label for="rate-form-from">From (blank: from the start)</label>
        <input
          id="rate-form-from"
          type="date"
          data-testid="rate-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set('')"
        />
        <label for="rate-form-to">To (blank: no end)</label>
        <input
          id="rate-form-to"
          type="date"
          data-testid="rate-form-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
        @if (error()) {
          <p role="alert" data-testid="rate-form-error">{{ error() }}</p>
        }
      </form>
      <form (submit)="importRates($event)" aria-label="Import rates">
        <label for="import-rates">Import rates, a line each: grade,rate,from,to (from and to may be left out or blank)</label>
        <textarea
          id="import-rates"
          data-testid="import-rates"
          rows="4"
          [value]="importText()"
          (input)="importText.set(value($event)); importError.set('')"
        ></textarea>
        <button type="submit" data-testid="import-rates-submit">Import rates</button>
        @if (importSet() !== null) {
          <span>Set <strong data-testid="import-rates-set">{{ importSet() }}</strong></span>
        }
        @if (importError()) {
          <p role="alert" data-testid="import-rates-error">{{ importError() }}</p>
        }
      </form>
      <form (submit)="preview($event)" aria-label="Preview rate">
        <label for="rate-preview-grade">Grade</label>
        <select
          id="rate-preview-grade"
          data-testid="rate-preview-grade"
          [value]="previewGrade()"
          (change)="previewGrade.set(value($event)); previewed.set(null); previewError.set('')"
        >
          @for (g of grades(); track g) {
            <option [value]="g" [selected]="g === previewGrade()">{{ g }}</option>
          }
        </select>
        <label for="rate-preview-pct">Candidate rate (%)</label>
        <input
          id="rate-preview-pct"
          inputmode="decimal"
          data-testid="rate-preview-pct"
          [value]="previewPct()"
          (input)="previewPct.set(value($event)); previewed.set(null); previewError.set('')"
        />
        <button type="submit" data-testid="rate-preview">Preview</button>
        @if (previewed(); as p) {
          <p>
            Claims whose due would change: <span data-testid="rate-preview-count">{{ p.count }}</span>;
            difference: <span data-testid="rate-preview-diff">{{ p.diff | figure }}</span>
          </p>
        }
        @if (previewError()) {
          <p role="alert" data-testid="rate-preview-error">{{ previewError() }}</p>
        }
      </form>
    </section>
  `,
})
export class Rates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly changed = output<void>();

  /** The sheet's grades, in order. */
  protected readonly grades = inject(GradeList).codes;
  protected readonly rates = signal<Rate[]>([]);
  /** The rates of the sheet's parent sheets, nearest first, up to three levels up. */
  private readonly inherited = signal<Rate[][]>([]);
  /**
   * Per grade, the rate in force on the sheet's today: the one with the latest from-date on or before it,
   * unless its to-date is before today (it has ended); with none, the parent sheet's, then its parent's.
   */
  protected readonly current = computed<Record<string, string>>(() => this.inForceOn(this.session.shownDay() ?? ''));
  /** Each month (yyyy-mm) of the sheet's today's calendar year, with the rates in force on its first day, as `current` is found. */
  protected readonly calendar = computed<{ month: string; rates: Record<string, string> }[]>(() => {
    const year = (this.session.shownDay() ?? '').slice(0, 4);
    if (!/^\d{4}$/.test(year)) return [];
    return Array.from({ length: 12 }, (_, i) => {
      const month = `${year}-${String(i + 1).padStart(2, '0')}`;
      return { month, rates: this.inForceOn(`${month}-01`) };
    });
  });
  protected readonly grade = signal('A');
  protected readonly pct = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal('');
  protected readonly previewGrade = signal('A');
  protected readonly previewPct = signal('');
  protected readonly previewed = signal<Preview | null>(null);
  protected readonly previewError = signal('');
  protected readonly importText = signal('');
  /** How many rates the last import set; null while it runs. */
  protected readonly importSet = signal<number | null>(null);
  protected readonly importError = signal('');
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;
  /** An import still waiting for its answer: another press meanwhile is ignored. */
  private importing = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.rates.set([]);
      this.inherited.set([]);
      this.grade.set('A');
      this.pct.set('');
      this.from.set('');
      this.to.set('');
      this.error.set('');
      this.previewGrade.set('A');
      this.previewPct.set('');
      this.previewed.set(null);
      this.previewError.set('');
      this.importText.set('');
      this.importSet.set(null);
      this.importError.set('');
      if (sheet) void this.reload(sheet);
    });
  }

  /** Per grade, the rate in force on the date; with none, the parent sheet's, then its parent's, then a clone's source sheet's. */
  private inForceOn(date: string): Record<string, string> {
    const found: Record<string, string> = {};
    for (const level of [this.rates(), ...this.inherited()]) {
      for (const [g, pct] of Object.entries(inForce(level, date))) {
        if (found[g] === undefined) found[g] = pct;
      }
    }
    return found;
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const grade = this.grade();
    const pct = this.pct().trim();
    const from = this.from().trim();
    const to = this.to().trim();
    if (!this.grades().includes(grade)) {
      this.error.set('Choose a grade.');
      return;
    }
    if (!PCT.test(pct)) {
      this.error.set('Enter a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    if (from !== '' && !DATE.test(from)) {
      this.error.set('Enter a from-date, or leave it blank for from the start.');
      return;
    }
    if (to !== '' && !DATE.test(to)) {
      this.error.set('Enter a to-date, or leave it blank for no end.');
      return;
    }
    if (from !== '' && to !== '' && to < from) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<Rate>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}`, { pct, from: from === '' ? null : from, to: to === '' ? null : to }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.rates.update((rs) =>
        [...rs.filter((r) => r.grade !== saved.grade || r.from !== saved.from), saved].sort(byGradeAndFrom),
      );
      this.pct.set('');
      this.from.set('');
      this.to.set('');
      this.changed.emit();
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.error.set('Could not set the rate.');
    } finally {
      this.busy = false;
    }
  }

  /** Set a rate per pasted line, in order, each as the rate form would; one refused line sets none. */
  protected async importRates(e: Event): Promise<void> {
    e.preventDefault();
    if (this.importing) return;
    this.importSet.set(null);
    this.importError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = this.importText();
    if (!text.trim()) {
      this.importError.set('Paste at least one line.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.importing = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ set: number }>(`/api/sheets/${encodeURIComponent(sheet)}/rates/import`, { text }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.rates.set(await firstValueFrom(this.http.get<Rate[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates`)));
      if (this.session.sheet() !== sheet) return;
      this.importText.set('');
      this.importSet.set(result.set);
      this.changed.emit();
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.importError.set(refusal ?? 'Could not import the rates.');
    } finally {
      this.importing = false;
    }
  }

  /** Show what the candidate rate would do to the grade's OPEN and PENDING claims; nothing is saved. */
  protected async preview(e: Event): Promise<void> {
    e.preventDefault();
    this.previewed.set(null);
    this.previewError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const grade = this.previewGrade();
    const pct = this.previewPct().trim();
    if (!this.grades().includes(grade)) {
      this.previewError.set('Choose a grade.');
      return;
    }
    if (!PCT.test(pct)) {
      this.previewError.set('Enter a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    try {
      const result = await firstValueFrom(
        this.http.get<Preview>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}/preview`, { params: { pct } }),
      );
      if (this.session.sheet() === sheet) this.previewed.set(result);
    } catch {
      if (this.session.sheet() === sheet) this.previewError.set('Could not preview the rate.');
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const [rates, inherited] = await Promise.all([
      firstValueFrom(this.http.get<Rate[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates`)),
      firstValueFrom(this.http.get<Rate[][]>(`/api/sheets/${encodeURIComponent(sheet)}/rates/inherited`)),
    ]);
    if (this.session.sheet() === sheet && this.session.loads() === loads) {
      this.rates.set(rates);
      this.inherited.set(inherited);
    }
  }
}
