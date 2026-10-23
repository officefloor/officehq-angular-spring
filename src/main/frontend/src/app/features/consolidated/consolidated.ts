import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FigurePipe } from '../../core/locale';
import { ConsolidatedApi, ConsolidatedView } from '../../core/consolidated';
import { GradesStore } from '../../core/grades';
import { Session } from '../../core/session';

/** The statuses the consolidated figures count claims by, in the order they list them. */
const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'REVIEW'] as const;

// The sheet's figures consolidated over it and all its descendants: the number of people, and each
// person's due-total, overdue-total, settled-total, open-total and cut total, as their sheet shows them (as of the as-of date while one is set),
// added up once over the tree (the single highest and lowest left out when three or more have one); per grade the due-totals of the people on it (retired people left out) added up, and per
// status the count of claims in it, over the tree. Re-read on every sheet load
// and whenever the sheet's own figures move.
@Component({
  selector: 'app-consolidated',
  imports: [FigurePipe],
  template: `
    <section aria-label="Consolidated figures">
      <p>Consolidated people <strong data-testid="consolidated-people-count">{{ figures()?.peopleCount }}</strong></p>
      <p>Consolidated due <strong data-testid="consolidated-due-total">{{ (figures()?.dueTotal) | figure }}</strong></p>
      <p>Consolidated overdue <strong data-testid="consolidated-overdue-total">{{ (figures()?.overdueTotal) | figure }}</strong></p>
      <p>Consolidated settled <strong data-testid="consolidated-settled-total">{{ (figures()?.settledTotal) | figure }}</strong>,
        open <strong data-testid="consolidated-open-total">{{ (figures()?.openTotal) | figure }}</strong></p>
      <p>Consolidated cut <strong data-testid="consolidated-cut-total">{{ (figures()?.cutTotal) | figure }}</strong></p>
      <table>
        <caption>Consolidated by grade</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Due</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade }}</th>
              <td [attr.data-testid]="'consolidated-grade-' + g.grade + '-due-total'">{{ g.dueTotal | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>Consolidated by status</caption>
        <thead>
          <tr>
            <th scope="col">Status</th>
            <th scope="col">Claims</th>
          </tr>
        </thead>
        <tbody>
          @for (s of statuses(); track s.status) {
            <tr>
              <th scope="row">{{ s.status }}</th>
              <td [attr.data-testid]="'consolidated-status-' + s.status + '-count'">{{ s.count }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Consolidated {
  private readonly session = inject(Session);
  private readonly api = inject(ConsolidatedApi);
  private readonly gradeStore = inject(GradesStore);

  /** The sheet's own figures as shown: a move in any of them re-reads the consolidated ones. */
  readonly peopleCount = input.required<number>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
  readonly settledTotal = input.required<string>();
  readonly openTotal = input.required<string>();
  readonly cutTotal = input.required<string>();

  protected readonly figures = signal<ConsolidatedView | null>(null);
  /** Per grade, its consolidated due-total; empty until the figures are read. */
  protected readonly grades = computed(() => {
    const figures = this.figures();
    // The sheet's own grades, then any other grade one of its descendants has.
    const codes = [...this.gradeStore.codes()];
    for (const grade of Object.keys(figures?.gradeDueTotals ?? {})) {
      if (!codes.includes(grade)) codes.push(grade);
    }
    return codes.map((grade) => ({
      grade,
      dueTotal: figures ? (figures.gradeDueTotals[grade] ?? '0.00') : '',
    }));
  });
  /** Per status, its consolidated count of claims; empty until the figures are read. */
  protected readonly statuses = computed(() => {
    const figures = this.figures();
    return STATUSES.map((status) => ({
      status,
      count: figures ? String(figures.statusCounts[status] ?? 0) : '',
    }));
  });
  /** Counts reads, so only the answer to the latest one is shown. */
  private reads = 0;

  constructor() {
    // Every sheet load clears the figures shown and re-reads them.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      untracked(() => {
        this.figures.set(null);
        if (sheet) void this.reload(sheet);
      });
    });
    // A move in the sheet's own today, its as-of date or its figures re-reads them.
    effect(() => {
      this.session.today();
      this.session.asOf();
      this.peopleCount();
      this.dueTotal();
      this.overdueTotal();
      this.settledTotal();
      this.openTotal();
      this.cutTotal();
      untracked(() => {
        const sheet = this.session.sheet();
        if (sheet) void this.reload(sheet);
      });
    });
  }

  private async reload(sheet: string): Promise<void> {
    const read = ++this.reads;
    try {
      const figures = await this.api.get(sheet);
      if (read === this.reads && this.session.sheet() === sheet) this.figures.set(figures);
    } catch {
      // Left as it was: the next load or change reads them again.
    }
  }
}
