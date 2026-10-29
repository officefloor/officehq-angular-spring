import { HttpClient, HttpContext } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { firstValueFrom } from 'rxjs';
import { AsOf, LIVE } from '../../core/as-of';
import { Session } from '../../core/session';
import { GRADES, sumSkippingNa, trimmedSum } from '../rates/rates';
import { STATUSES, SummaryPerson } from '../summary/summary';

// One person of a sheet of the tree: their name and aliases, due-total (null for n/a) and overdue-total.
export interface TreeMember {
  names: string[];
  dueTotal: string | null;
  overdueTotal: string;
}

// The figures of the sheet's descendants (the sheets naming it as parent, theirs, and so on), added up by the store.
interface DescendantFigures {
  peopleCount: number;
  dueTotal: string;
  overdueTotal: string;
  // Per grade, the due-totals of the people on it today (retired people left out); a grade with none is missing.
  gradeDueTotals: Record<string, string>;
  // Per status, the count of claims in it; a status with none is missing.
  statusCounts: Record<string, number>;
  // Each person of the descendants, for the totals to be worked out again with the sheet's own people.
  people: TreeMember[];
}

// The people over the tree, each once: those whose names or aliases match, ignoring case, are one.
function distinctPeople(members: readonly TreeMember[]): number {
  const group = members.map((_, i) => i);
  const root = (i: number): number => (group[i] === i ? i : (group[i] = root(group[i])));
  const byName = new Map<string, number>();
  members.forEach((m, i) => {
    for (const n of m.names) {
      const key = n.toLowerCase();
      const j = byName.get(key);
      if (j === undefined) byName.set(key, i);
      else group[root(i)] = root(j);
    }
  });
  return new Set(members.map((_, i) => root(i))).size;
}

const NONE: DescendantFigures = {
  peopleCount: 0,
  dueTotal: '0.00',
  overdueTotal: '0.00',
  gradeDueTotals: {},
  statusCounts: {},
  people: [],
};

// The figures over the sheet and all its descendants: the sheet's own, as it shows them, plus each descendant's. While
// the sheet is shown as of a date they are as of that date too: every sheet of the tree as it stood at the end of that
// day, with that date as its today, added up by the store.
@Component({
  selector: 'app-consolidated',
  imports: [FigPipe],
  template: `
    <section aria-label="Consolidated figures">
      <h2>Consolidated (this sheet and its descendants)</h2>
      <dl>
        <dt>People</dt>
        <dd data-testid="consolidated-people-count">{{ peopleCount() | fig }}</dd>
        <dt>Due</dt>
        <dd data-testid="consolidated-due-total">{{ dueTotal() | fig }}</dd>
        <dt>Overdue</dt>
        <dd data-testid="consolidated-overdue-total">{{ overdueTotal() | fig }}</dd>
      </dl>
      <table>
        <caption>By grade</caption>
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Due</th></tr>
        </thead>
        <tbody>
          @for (g of byGrade(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade }}</th>
              <td [attr.data-testid]="'consolidated-grade-' + g.grade + '-due-total'">{{ g.dueTotal | fig }}</td>
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
              <td [attr.data-testid]="'consolidated-status-' + s.status + '-count'">{{ s.count | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Consolidated {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  private readonly asOf = inject(AsOf);

  // The sheet's own figures, as it shows them.
  readonly ownMembers = input<readonly TreeMember[]>([]);
  readonly ownPeople = input<readonly SummaryPerson[]>([]);
  readonly ownStatuses = input<readonly string[]>([]);

  private readonly descendants = signal<DescendantFigures>(NONE);

  // The sheet's and its descendants' figures as of the date the sheet is shown as of, read while it is.
  private readonly asOfFigures = signal<DescendantFigures | null>(null);

  // Every person over the tree: the sheet's own and each descendant's.
  private readonly members = computed(() => [...this.ownMembers(), ...(this.descendants().people ?? [])]);

  // Each person counted once; the due-total and overdue-total trimmed once over all of the tree's people.
  protected readonly peopleCount = computed(
    () => this.asOfFigures()?.peopleCount ?? distinctPeople(this.members()),
  );
  protected readonly dueTotal = computed(
    () => this.asOfFigures()?.dueTotal ?? trimmedSum(this.members().map((m) => m.dueTotal)),
  );
  protected readonly overdueTotal = computed(
    () => this.asOfFigures()?.overdueTotal ?? trimmedSum(this.members().map((m) => m.overdueTotal)),
  );

  // Per grade: the due-totals of the people on it today, retired ones left out and n/a skipped, over the tree.
  protected readonly byGrade = computed(() => {
    const asOf = this.asOfFigures();
    return GRADES.map((grade) => {
      const below = (asOf ?? this.descendants()).gradeDueTotals?.[grade] ?? null;
      const own = asOf
        ? []
        : this.ownPeople()
            .filter((p) => !p.retired && p.grade === grade)
            .map((p) => p.dueTotal);
      return { grade, dueTotal: sumSkippingNa([...own, below]) };
    });
  });
  // Per status: the count of claims in it over the tree.
  protected readonly byStatus = computed(() => {
    const asOf = this.asOfFigures();
    return STATUSES.map((status) => {
      const below = (asOf ?? this.descendants()).statusCounts?.[status] ?? 0;
      const own = asOf ? 0 : this.ownStatuses().filter((s) => s === status).length;
      return { status, count: own + below };
    });
  });

  constructor() {
    // Re-read the descendants' figures from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      const asOf = this.asOf.date();
      this.session.loads();
      untracked(() => {
        this.descendants.set(NONE);
        this.asOfFigures.set(null);
        if (id && asOf) void this.loadAsOf(id, asOf);
        else if (id) void this.load(id);
      });
    });
  }

  private async load(id: string): Promise<void> {
    try {
      const figures = await firstValueFrom(
        this.http.get<DescendantFigures>(`/api/sheets/${encodeURIComponent(id)}/descendants/figures`),
      );
      if (this.session.sheetId() === id) this.descendants.set(figures);
    } catch {
      // with none read, the sheet's own figures stand alone
    }
  }

  // Read without the as-of header: the store opens each sheet of the tree as of the date itself.
  private async loadAsOf(id: string, asOf: string): Promise<void> {
    try {
      const figures = await firstValueFrom(
        this.http.get<DescendantFigures>(`/api/sheets/${encodeURIComponent(id)}/descendants/consolidated`, {
          params: { asOf },
          context: new HttpContext().set(LIVE, true),
        }),
      );
      if (this.session.sheetId() === id && this.asOf.date() === asOf) this.asOfFigures.set(figures);
    } catch {
      // with none read, the figures shown as of the date stand
    }
  }
}
