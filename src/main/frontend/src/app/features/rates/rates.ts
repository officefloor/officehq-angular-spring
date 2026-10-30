import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface Rate {
  grade: string;
  /** The first date the rate applies to (yyyy-mm-dd); null when it applies from the beginning of time. */
  from: string | null;
  /** The last date the rate applies to (yyyy-mm-dd); null when it has no end. */
  to: string | null;
  pct: string;
}

/** What a candidate rate would do: how many claims' dues would differ, and the sum of the differences. */
interface Preview {
  count: number;
  diff: string;
}

const GRADES = ['A', 'B', 'C'];
const PCT = /^\d{1,3}(\.\d{1,2})?$/;
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
// beginning of time) through an optional to-date, the rate in force on the sheet's today per grade, and the form that sets one. A
// rate that was set is handed up as `changed`, so the dues it makes can be shown again.
@Component({
  selector: 'app-rates',
  template: `
    <section aria-label="Rates">
      <h2>Rates</h2>
      <table>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Rate (%)</th></tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ current()[g] ?? 'n/a' }}</td>
            </tr>
          }
        </tbody>
      </table>
      <ul aria-label="Rates set">
        @for (r of rates(); track r.grade + (r.from ?? '')) {
          <li [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
            Grade {{ r.grade }} from <span data-testid="rate-from">{{ r.from ?? 'start' }}</span>
            @if (r.to) {
              through <span data-testid="rate-to">{{ r.to }}</span>
            }
            : <span data-testid="rate-pct">{{ r.pct }}</span>%
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
          @for (g of grades; track g) {
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
      <form (submit)="preview($event)" aria-label="Preview rate">
        <label for="rate-preview-grade">Grade</label>
        <select
          id="rate-preview-grade"
          data-testid="rate-preview-grade"
          [value]="previewGrade()"
          (change)="previewGrade.set(value($event)); previewError.set('')"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === previewGrade()">{{ g }}</option>
          }
        </select>
        <label for="rate-preview-pct">Candidate rate (%)</label>
        <input
          id="rate-preview-pct"
          inputmode="decimal"
          data-testid="rate-preview-pct"
          [value]="previewPct()"
          (input)="previewPct.set(value($event)); previewError.set('')"
        />
        <button type="submit" data-testid="rate-preview">Preview</button>
        @if (previewed(); as p) {
          <p>
            Claims whose due would change: <span data-testid="rate-preview-count">{{ p.count }}</span>;
            difference: <span data-testid="rate-preview-diff">{{ p.diff }}</span>
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

  protected readonly grades = GRADES;
  protected readonly rates = signal<Rate[]>([]);
  /** The rates of the sheet's parent sheets, nearest first, up to three levels up. */
  private readonly inherited = signal<Rate[][]>([]);
  /**
   * Per grade, the rate in force on the sheet's today: the one with the latest from-date on or before it,
   * unless its to-date is before today (it has ended); with none, the parent sheet's, then its parent's.
   */
  protected readonly current = computed<Record<string, string>>(() => {
    const today = this.session.today() ?? '';
    const found: Record<string, string> = {};
    for (const level of [this.rates(), ...this.inherited()]) {
      for (const [g, pct] of Object.entries(inForce(level, today))) {
        if (found[g] === undefined) found[g] = pct;
      }
    }
    return found;
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
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

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
      if (sheet) void this.reload(sheet);
    });
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
    if (!GRADES.includes(grade)) {
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

  /** Show what the candidate rate would do to the grade's OPEN and PENDING claims; nothing is saved. */
  protected async preview(e: Event): Promise<void> {
    e.preventDefault();
    this.previewed.set(null);
    this.previewError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const grade = this.previewGrade();
    const pct = this.previewPct().trim();
    if (!GRADES.includes(grade)) {
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
