import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { FigPipe } from '../../core/locale';
import { DatedFigure, PeriodStore, classicPeriodDueTotals, periodDueTotals } from '../periods/periods';
import { difference, sumSkippingNa } from '../rates/rates';
import { SettingsStore } from '../settings/settings';
import { VoidReasonStore } from '../void-reasons/void-reasons';

/**
 * A person as the summary counts them: their grade today, their due-total (null for n/a), whether retired and
 * whether active.
 */
export interface SummaryPerson {
  grade: string;
  dueTotal: string | null;
  retired: boolean;
  active: boolean;
}

// Another sheet's figures as the comparison reads them: whether it exists, and per grade its people's due-totals.
interface OtherGradeDueTotals {
  exists: boolean;
  gradeDueTotals: Record<string, string>;
}

// Every status a claim's count is shown for.
export const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'REVIEW'];

// The sheet's figures at a glance, each worked out the same way as the list shows it.
@Component({
  selector: 'app-summary',
  imports: [FigPipe],
  template: `
    <section aria-label="Summary">
      <h2>Summary</h2>
      <table>
        <caption>By grade</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">People</th><th scope="col">Due</th><th scope="col">Active</th></tr>
        </thead>
        <tbody>
          @for (g of byGrade(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade }}</th>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-count'">{{ g.count | fig }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal | fig }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-active'">{{ g.active | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due</th><th scope="col">Change</th></tr>
        </thead>
        <tbody>
          @for (p of byPeriod(); track p.name) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ p.dueTotal | fig }}</td>
              <td [attr.data-testid]="'summary-period-' + p.name + '-change'">{{ p.change | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>By status</caption>
        <thead>
          <tr><th scope="col">Status</th><th scope="col">Claims</th></tr>
        </thead>
        <tbody>
          @for (s of byStatus(); track s.status) {
            <tr>
              <th scope="row">{{ s.status }}</th>
              <td [attr.data-testid]="'summary-status-' + s.status + '-count'">{{ s.count | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
      <form aria-label="Compare with another sheet" (submit)="$event.preventDefault(); compare()">
        <label>
          Other sheet
          <input
            data-testid="compare-sheet"
            [value]="compareWith()"
            (input)="compareWith.set(value($event))"
          />
        </label>
        <button type="submit" data-testid="compare">Compare</button>
      </form>
      <ul aria-label="Due by grade, this sheet then the other">
        @for (line of compareLines(); track $index) {
          <li data-testid="compare-line">{{ line }}</li>
        }
      </ul>
      <p>Late in review: <strong data-testid="summary-review-late-count">{{ reviewLateCount() | fig }}</strong></p>
      <p>Late in pending: <strong data-testid="summary-pending-late-count">{{ pendingLateCount() | fig }}</strong></p>
      <table>
        <caption>By void reason</caption>
        <thead>
          <tr><th scope="col">Reason</th><th scope="col">Voided claims</th></tr>
        </thead>
        <tbody>
          @for (r of byVoidReason(); track r.reason) {
            <tr>
              <th scope="row">{{ r.reason }}</th>
              <td [attr.data-testid]="'summary-void-' + r.reason + '-count'">{{ r.count | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
    <section aria-label="Classic summary">
      <h2>Classic summary</h2>
      <table>
        <caption>By period, counting each claim by its status now</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due</th></tr>
        </thead>
        <tbody>
          @for (p of classicByPeriod(); track p.name) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'classic-period-' + p.name + '-due-total'">{{ p.dueTotal | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
      <p>Due in total, not trimmed: <strong data-testid="classic-due-total">{{ classicDueTotal() | fig }}</strong></p>
    </section>
  `,
})
export class Summary {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  private readonly periods = inject(PeriodStore);
  private readonly voidReasons = inject(VoidReasonStore);
  private readonly grades = inject(SettingsStore).grades;

  readonly people = input<readonly SummaryPerson[]>([]);
  // The counted claims of the sheet: each one's date and figure, as the periods total them.
  readonly figures = input<readonly DatedFigure[]>([]);
  // The status of every claim on the sheet.
  readonly statuses = input<readonly string[]>([]);
  // The reason each VOID claim on the sheet was voided with (null for none).
  readonly voidedWith = input<readonly (string | null)[]>([]);
  // How many REVIEW claims on the sheet are late against the review SLA.
  readonly reviewLateCount = input(0);
  readonly pendingLateCount = input(0);

  // Per grade: the people on it today, leaving out retired ones, their due-totals added up, skipping n/a, and how
  // many of them are active.
  protected readonly byGrade = computed(() =>
    this.grades().map((grade) => {
      const on = this.people().filter((p) => !p.retired && p.grade === grade);
      return {
        grade,
        count: on.length,
        dueTotal: sumSkippingNa(on.map((p) => p.dueTotal)),
        active: on.filter((p) => p.active).length,
      };
    }),
  );
  // The id of the sheet to compare with, and the other sheet's per-grade due-totals once read (null before).
  protected readonly compareWith = signal('');
  private readonly other = signal<OtherGradeDueTotals | null>(null);
  // One line per grade of this sheet: its summary due-total, then the other sheet's (0.00 for a grade it has no one
  // on); none for a sheet that does not exist.
  protected readonly compareLines = computed(() => {
    const other = this.other();
    if (!other?.exists) return [];
    return this.byGrade().map((g) => `${g.grade} ${g.dueTotal} ${sumSkippingNa([other.gradeDueTotals[g.grade] ?? null])}`);
  });

  constructor() {
    // A comparison belongs to the sheet it was made on.
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => this.other.set(null));
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  // Reads the other sheet's per-grade due-totals; the old lines are gone while it is read.
  protected async compare(): Promise<void> {
    const id = this.compareWith().trim();
    this.other.set(null);
    if (!/^[A-Za-z0-9_.-]{1,100}$/.test(id)) return;
    try {
      const other = await firstValueFrom(
        this.http.get<OtherGradeDueTotals>(`/api/sheets/${encodeURIComponent(id)}/grade-due-totals`),
      );
      if (this.compareWith().trim() === id) this.other.set(other);
    } catch {
      // with nothing read, no lines show
    }
  }

  // Per period: its due-total, and its change from the period before it by from-date (none for the earliest).
  protected readonly byPeriod = computed(() => {
    const totals = periodDueTotals(this.periods.list(), this.figures());
    const byFrom = [...this.periods.list()].sort((a, b) => a.from.localeCompare(b.from));
    const changes = new Map(
      byFrom.map((p, i) => {
        const before = i > 0 ? totals.get(byFrom[i - 1].id) : undefined;
        const now = totals.get(p.id);
        return [p.id, before !== undefined && now !== undefined ? difference(now, before) : ''];
      }),
    );
    return this.periods.list().map((p) => ({
      name: p.name,
      dueTotal: totals.get(p.id) ?? '',
      change: changes.get(p.id) ?? '',
    }));
  });
  // Per period: its due-total with each claim counted by its status now; a closed period keeps its fixed figure.
  protected readonly classicByPeriod = computed(() => {
    const totals = classicPeriodDueTotals(this.periods.list(), this.figures());
    return this.periods.list().map((p) => ({ name: p.name, dueTotal: totals.get(p.id) ?? '' }));
  });
  // The sheet's due-total with no trimming: everyone's due-total added up, skipping n/a.
  protected readonly classicDueTotal = computed(() => sumSkippingNa(this.people().map((p) => p.dueTotal)));
  protected readonly byStatus = computed(() =>
    STATUSES.map((status) => ({ status, count: this.statuses().filter((s) => s === status).length })),
  );
  // Per reason on the sheet's list: the VOID claims voided with it.
  protected readonly byVoidReason = computed(() =>
    this.voidReasons.list().map(({ reason }) => ({
      reason,
      count: this.voidedWith().filter((r) => r === reason).length,
    })),
  );
}
