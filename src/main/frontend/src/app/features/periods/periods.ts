import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, model, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { heldOut, retiredOut, type Claim, type ClaimStatus, type PersonWithClaims } from '../claims/claims';

export interface Period {
  id: number;
  name: string;
  /** The first and last date of the period (yyyy-mm-dd), both inclusive. */
  from: string;
  to: string;
  closed: boolean;
  /** Whether the period is soft-closed: its figures are fixed, but claims due by a date inside it can still change. */
  softClosed: boolean;
  /** The due-total fixed when the period was closed or soft-closed; null while it is open. */
  closedTotal: string | null;
  /** Per person id, the person's due-total fixed when the period was closed; empty while it is open. */
  personTotals: Record<number, string>;
  /** Per person id, the person's carried figure fixed when the period was closed; empty while it is open. */
  personCarried: Record<number, string>;
  /** Per person id, the opening balances pasted for the person in the period, added up: part of their carried figure. */
  personOpenings: Record<number, string>;
  /** The fiscal year its from-date falls in: FY and the calendar year that fiscal year's first day falls in. */
  fy: string;
  /** The most OPEN or PENDING claims a person may have in the period; null for no cap. */
  cap: number | null;
  /** Whether a steward has locked the period: a locked period cannot be reopened. */
  locked: boolean;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const YEAR = /^\d{4}$/;
const WHOLE = /^\d+$/;

/** The refusal shown on a period's row when the server gives no reason. */
const REFUSED = {
  close: 'The period is already closed.',
  'soft-close': 'The period is already closed.',
  reopen: 'The period cannot be reopened.',
  lock: 'The period cannot be locked.',
  unlock: 'The period cannot be unlocked.',
} as const;

/** The period whose dates contain the date, or undefined when none does. */
export function periodOf(periods: Period[], date: string): Period | undefined {
  return periods.find((p) => p.from <= date && date <= p.to);
}

/** Whether the period's figures are fixed: it is closed or soft-closed. */
export function isFixed(p: Period): boolean {
  return p.closed || p.softClosed;
}

/** Cents in an amount shown with two decimals ("12.50" is 1250). */
function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** The status the claim had at the end of the day given, as its status log records it; undefined when it was
 * added after that day. */
export function statusAsOf(c: Claim, day: string): ClaimStatus | undefined {
  let status: ClaimStatus | undefined;
  for (const l of c.log) if (l.on <= day) status = l.status;
  return status;
}

/** Per period id, the person's due-total for it: the dues as shown now of their claims due by a date in it that counted
 * (OPEN, PENDING or SETTLED) at the end of its last day, added up, or once closed or soft-closed the figure fixed then (0.00
 * for a person added later); an unbound OPEN claim dated within a hold of its person counts in none, nor does an
 * unbound OPEN or PENDING claim of a person whose retirement date the sheet's today has reached, nor does
 * a deferred claim. */
export function personPeriodTotals(periods: Period[], person: PersonWithClaims): Record<number, string> {
  const totals: Record<number, number> = {};
  for (const p of periods) totals[p.id] = 0;
  for (const c of person.claims) {
    if (c.due === null || heldOut(c, person.holds) || retiredOut(c, person.retiredNow) || c.deferred) continue;
    const period = periodOf(periods, c.dueBy);
    if (!period || isFixed(period)) continue;
    const status = statusAsOf(c, period.to);
    if (status === 'OPEN' || status === 'PENDING' || status === 'SETTLED') totals[period.id] += cents(c.due);
  }
  const shown: Record<number, string> = {};
  for (const p of periods) {
    shown[p.id] = isFixed(p) ? (p.personTotals?.[person.id] ?? '0.00') : (totals[p.id] / 100).toFixed(2);
  }
  return shown;
}

/** Per period id, the person's carried figure for it: the dues as shown now of their claims that count (the same
 * claims left out as personPeriodTotals) due by a date in a period ending before it starts and still OPEN or PENDING
 * at the end of its last day, by the status log, added up, with the opening balances pasted for them in it; or once closed or soft-closed the figure fixed then (0.00
 * for a person added later). */
export function personPeriodCarried(periods: Period[], person: PersonWithClaims): Record<number, string> {
  const totals: Record<number, number> = {};
  for (const p of periods) totals[p.id] = cents(p.personOpenings?.[person.id] ?? '0.00');
  for (const c of person.claims) {
    if (c.due === null || heldOut(c, person.holds) || retiredOut(c, person.retiredNow) || c.deferred) continue;
    const home = periodOf(periods, c.dueBy);
    if (!home) continue;
    for (const p of periods) {
      if (home.to >= p.from) continue;
      const status = statusAsOf(c, p.to);
      if (status === 'OPEN' || status === 'PENDING') totals[p.id] += cents(c.due);
    }
  }
  const shown: Record<number, string> = {};
  for (const p of periods) {
    shown[p.id] = isFixed(p) ? (p.personCarried?.[person.id] ?? '0.00') : (totals[p.id] / 100).toFixed(2);
  }
  return shown;
}

/** Per period id, its due-total: the people's due-totals for it, added up. */
export function periodDueTotals(periods: Period[], people: PersonWithClaims[]): Record<number, string> {
  const totals: Record<number, number> = {};
  for (const p of periods) totals[p.id] = 0;
  for (const person of people) {
    const figures = personPeriodTotals(periods, person);
    for (const p of periods) totals[p.id] += cents(figures[p.id]);
  }
  const shown: Record<number, string> = {};
  for (const p of periods) shown[p.id] = (totals[p.id] / 100).toFixed(2);
  return shown;
}

/** Per period id, its classic due-total: the dues as shown now of the people's claims due by a date in it that count
 * (OPEN, PENDING or SETTLED) by their status now, added up, with the same claims left out as personPeriodTotals; or once
 * closed or soft-closed the figure fixed then. */
export function classicPeriodDueTotals(periods: Period[], people: PersonWithClaims[]): Record<number, string> {
  const totals: Record<number, number> = {};
  for (const p of periods) totals[p.id] = 0;
  for (const person of people) {
    for (const c of person.claims) {
      if (c.due === null || heldOut(c, person.holds) || retiredOut(c, person.retiredNow) || c.deferred) continue;
      const period = periodOf(periods, c.dueBy);
      if (!period || isFixed(period)) continue;
      if (c.status === 'OPEN' || c.status === 'PENDING' || c.status === 'SETTLED') totals[period.id] += cents(c.due);
    }
  }
  const shown: Record<number, string> = {};
  for (const p of periods) shown[p.id] = isFixed(p) ? (p.closedTotal ?? '0.00') : (totals[p.id] / 100).toFixed(2);
  return shown;
}

// The sheet's periods, earliest first, each with its due-total: the people's due-totals for it added up,
// each the dues of their claims whose date falls in it that counted (OPEN, PENDING and SETTLED) at the end
// of its last day, see personPeriodTotals, or once
// closed or soft-closed the figure fixed then; and the form that
// adds one. No two periods share a name or a date.
@Component({
  selector: 'app-periods',
  imports: [FigurePipe],
  template: `
    <section aria-label="Periods">
      <h2>Periods</h2>
      <table>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Fiscal year</th><th scope="col">Cap</th><th scope="col">Due total</th><th scope="col">Closed</th><th scope="col">Locked</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-fy">{{ p.fy }}</td>
              <td data-testid="period-cap">{{ (p.cap ?? '') | figure }}</td>
              <td data-testid="period-due-total">{{ dueTotals()[p.id] | figure }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : p.softClosed ? 'soft' : 'no' }}</td>
              <td data-testid="period-locked">{{ p.locked ? 'yes' : 'no' }}</td>
              <td>
                <button type="button" data-testid="period-close" [attr.aria-label]="'Close period ' + p.name" (click)="act(p, 'close')">Close</button>
                <button type="button" data-testid="period-soft-close" [attr.aria-label]="'Soft-close period ' + p.name" (click)="act(p, 'soft-close')">Soft-close</button>
                <button type="button" data-testid="period-reopen" [attr.aria-label]="'Reopen period ' + p.name" (click)="act(p, 'reopen')">Reopen</button>
                <button type="button" data-testid="period-lock" [attr.aria-label]="'Lock period ' + p.name" (click)="act(p, 'lock')">Lock</button>
                <button type="button" data-testid="period-unlock" [attr.aria-label]="'Unlock period ' + p.name" (click)="act(p, 'unlock')">Unlock</button>
                @if (rowError()?.id === p.id) {
                  <p role="alert" data-testid="period-error">{{ rowError()?.message }}</p>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
      <form (submit)="save($event)" aria-label="Add period">
        <label for="period-form-name">Name</label>
        <input
          id="period-form-name"
          data-testid="period-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set('')"
        />
        <label for="period-form-from">From</label>
        <input
          id="period-form-from"
          type="date"
          data-testid="period-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set('')"
        />
        <label for="period-form-to">To</label>
        <input
          id="period-form-to"
          type="date"
          data-testid="period-form-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set('')"
        />
        <label for="period-form-cap">Open claims cap per person</label>
        <input
          id="period-form-cap"
          inputmode="numeric"
          data-testid="period-form-cap"
          [value]="cap()"
          (input)="cap.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error()) {
          <p role="alert" data-testid="period-form-error">{{ error() }}</p>
        }
      </form>
      <form (submit)="generate($event)" aria-label="Generate monthly periods">
        <label for="period-generate-from">From</label>
        <input
          id="period-generate-from"
          type="date"
          data-testid="period-generate-from"
          [value]="genFrom()"
          (input)="genFrom.set(value($event)); genError.set('')"
        />
        <label for="period-generate-to">To</label>
        <input
          id="period-generate-to"
          type="date"
          data-testid="period-generate-to"
          [value]="genTo()"
          (input)="genTo.set(value($event)); genError.set('')"
        />
        <button type="submit" data-testid="period-generate">Generate monthly periods</button>
        @if (generated() !== null) {
          <span>Made <strong data-testid="period-generated">{{ generated() }}</strong></span>
        }
        @if (genError()) {
          <p role="alert" data-testid="period-generate-error">{{ genError() }}</p>
        }
      </form>
      <form (submit)="generateFy($event)" aria-label="Generate a fiscal year's monthly periods">
        <label for="period-generate-fy-year">Fiscal year (the calendar year its first day falls in)</label>
        <input
          id="period-generate-fy-year"
          inputmode="numeric"
          data-testid="period-generate-fy-year"
          [value]="fyYear()"
          (input)="fyYear.set(value($event)); genError.set('')"
        />
        <button type="submit" data-testid="period-generate-fy">Generate fiscal year</button>
      </form>
      <form (submit)="importOpenings($event)" aria-label="Paste opening balances">
        <label for="opening-import">Paste opening balances, a line each: name,period,amount</label>
        <textarea
          id="opening-import"
          data-testid="opening-import"
          rows="4"
          [value]="openingText()"
          (input)="openingText.set(value($event)); openingError.set('')"
        ></textarea>
        <button type="submit" data-testid="opening-import-submit">Paste opening balances</button>
        @if (openingDone() !== null) {
          <span>Added <strong data-testid="opening-import-done">{{ openingDone() }}</strong></span>
        }
        @if (openingError()) {
          <p role="alert" data-testid="opening-error">{{ openingError() }}</p>
        }
      </form>
    </section>
  `,
})
export class Periods {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly people = input.required<PersonWithClaims[]>();
  readonly periods = model<Period[]>([]);

  protected readonly dueTotals = computed(() => periodDueTotals(this.periods(), this.people()));
  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly cap = signal('');
  protected readonly error = signal('');
  protected readonly genFrom = signal('');
  protected readonly genTo = signal('');
  protected readonly fyYear = signal('');
  /** How many periods the last generate made; null while it runs. */
  protected readonly generated = signal<number | null>(null);
  protected readonly genError = signal('');
  protected readonly openingText = signal('');
  /** How many lines the last paste of opening balances added; null while it runs. */
  protected readonly openingDone = signal<number | null>(null);
  protected readonly openingError = signal('');
  /** The refusal of the last action on a period's row, shown on that row. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.periods.set([]);
      this.name.set('');
      this.from.set('');
      this.to.set('');
      this.cap.set('');
      this.error.set('');
      this.genFrom.set('');
      this.genTo.set('');
      this.fyYear.set('');
      this.generated.set(null);
      this.genError.set('');
      this.openingText.set('');
      this.openingDone.set(null);
      this.openingError.set('');
      this.rowError.set(null);
      if (sheet) void this.reload(sheet);
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.name().trim();
    const from = this.from().trim() || (this.session.today() ?? '');
    const to = this.to().trim();
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!DATE.test(from) || !DATE.test(to)) {
      this.error.set('Enter a from-date and a to-date.');
      return;
    }
    if (to < from) {
      this.error.set('The to-date is before the from-date.');
      return;
    }
    const capText = this.cap().trim();
    if (capText && (!WHOLE.test(capText) || Number(capText) < 1)) {
      this.error.set('The cap must be a whole number of at least 1, or blank for none.');
      return;
    }
    const cap = capText ? Number(capText) : null;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Period>(`/api/sheets/${encodeURIComponent(sheet)}/periods`, { name, from, to, cap }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.periods.update((ps) => [...ps.filter((p) => p.id !== saved.id), saved].sort((a, b) => a.from.localeCompare(b.from)));
      this.name.set('');
      this.from.set('');
      this.to.set('');
      this.cap.set('');
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const message = err instanceof HttpErrorResponse && err.status === 409
        ? (err.error?.message ?? 'That period name is already in use on this sheet.')
        : 'Could not add the period.';
      this.error.set(message);
    } finally {
      this.busy = false;
    }
  }

  /**
   * Generate one period per calendar month from the from-date's month to the to-date's, named yyyy-mm; a month
   * whose name is taken or whose days overlap a period is left out. Shows how many were made.
   */
  protected async generate(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.generated.set(null);
    this.genError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const from = this.genFrom().trim() || (this.session.today() ?? '');
    const to = this.genTo().trim();
    if (!DATE.test(from) || !DATE.test(to)) {
      this.genError.set('Enter a from-date and a to-date.');
      return;
    }
    if (to < from) {
      this.genError.set('The to-date is before the from-date.');
      return;
    }
    await this.runGenerate(sheet, 'generate', { from, to });
  }

  /**
   * Generate the twelve monthly periods of the fiscal year whose first day falls in the year given, from the
   * sheet's fiscal start month on, as the monthly generate does. Shows how many were made.
   */
  protected async generateFy(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.generated.set(null);
    this.genError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const year = this.fyYear().trim();
    if (!YEAR.test(year)) {
      this.genError.set('Enter a year, such as 2026.');
      return;
    }
    await this.runGenerate(sheet, 'generate-fy', { year: Number(year) });
  }

  /**
   * Paste opening balances, lines of name,period,amount: each adds its amount to the person's carried figure for
   * the period. One refused line adds none. Shows how many lines went through.
   */
  protected async importOpenings(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.openingDone.set(null);
    this.openingError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = this.openingText();
    if (!text.trim()) {
      this.openingError.set('Paste at least one line.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const result = await firstValueFrom(
        this.http.post<{ added: number; periods: Period[] }>(
          `/api/sheets/${encodeURIComponent(sheet)}/periods/openings`, { text }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.periods.set(result.periods);
      this.openingText.set('');
      this.openingDone.set(result.added);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.openingError.set(refusal ?? 'Could not paste the opening balances.');
    } finally {
      this.busy = false;
    }
  }

  /** Post a generate and show the periods made and how many, or the refusal. */
  private async runGenerate(sheet: string, path: string, body: object): Promise<void> {
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const made = await firstValueFrom(
        this.http.post<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods/${path}`, body, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      const ids = new Set(made.map((p) => p.id));
      this.periods.update((ps) => [...ps.filter((p) => !ids.has(p.id)), ...made].sort((a, b) => a.from.localeCompare(b.from)));
      this.generated.set(made.length);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const message = err instanceof HttpErrorResponse && err.status === 409
        ? (err.error?.message ?? 'The periods could not be generated.')
        : 'Could not generate the periods.';
      this.genError.set(message);
    } finally {
      this.busy = false;
    }
  }

  /**
   * Close a period, fixing its due-total, soft-close it, fixing its figures while its claims can still change,
   * reopen a closed or soft-closed one (a steward only, and not while locked), making it live again, or lock or
   * unlock it (a steward only); a refusal is shown on its row.
   */
  protected async act(p: Period, action: 'close' | 'soft-close' | 'reopen' | 'lock' | 'unlock'): Promise<void> {
    if (this.busy) return;
    this.rowError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Period>(`/api/sheets/${encodeURIComponent(sheet)}/periods/${p.id}/${action}`, null, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.periods.update((ps) => ps.map((q) => (q.id === saved.id ? saved : q)));
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const message = err instanceof HttpErrorResponse && err.status === 409
        ? (err.error?.message ?? REFUSED[action])
        : `Could not ${action} the period.`;
      this.rowError.set({ id: p.id, message });
    } finally {
      this.busy = false;
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const periods = await firstValueFrom(this.http.get<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.periods.set(periods);
  }
}
