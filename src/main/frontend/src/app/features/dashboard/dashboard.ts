import { HttpClient, HttpContext } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { firstValueFrom } from 'rxjs';
import { AsOf, LIVE } from '../../core/as-of';
import { Session } from '../../core/session';
import { DescendantFigures, NONE } from '../consolidated/consolidated';

// The steward's dashboard: over the sheet and all its descendants, the number of PENDING claims, of REVIEW claims and of overdue claims that
// count. Blank unless the signed-in user acts as a steward on the sheet; while the sheet is shown as of a date, the
// counts and the role are as they stood then.
@Component({
  selector: 'app-dashboard',
  imports: [FigPipe],
  template: `
    <section aria-label="Steward's dashboard">
      <h2>Steward's dashboard</h2>
      <dl>
        <dt>Pending</dt>
        <dd data-testid="dash-pending-count">{{ steward() ? (pendingCount() | fig) : '' }}</dd>
        <dt>In review</dt>
        <dd data-testid="dash-review-count">{{ steward() ? (reviewCount() | fig) : '' }}</dd>
        <dt>Overdue</dt>
        <dd data-testid="dash-overdue-count">{{ steward() ? (treeOverdueCount() | fig) : '' }}</dd>
      </dl>
    </section>
  `,
})
export class Dashboard {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  private readonly asOf = inject(AsOf);

  // The status of every claim on the sheet, and the number of overdue claims counted live.
  readonly statuses = input<readonly string[]>([]);
  readonly overdueCount = input(0);

  // The role the signed-in user acts in on the sheet, as the store answers it; null until read.
  private readonly role = signal<string | null>(null);

  // The descendants' figures added up by the store; and, while the sheet is shown as of a date, the whole tree's as
  // they stood then (the sheet's own included).
  private readonly descendants = signal<DescendantFigures>(NONE);
  private readonly asOfFigures = signal<DescendantFigures | null>(null);

  protected readonly steward = computed(() => this.role() === 'steward');
  protected readonly pendingCount = computed(() => this.countOf('PENDING'));
  protected readonly reviewCount = computed(() => this.countOf('REVIEW'));
  protected readonly treeOverdueCount = computed(
    () => this.asOfFigures()?.overdueCount ?? this.overdueCount() + (this.descendants().overdueCount ?? 0),
  );

  constructor() {
    // Re-read the user's role from the store each time a sheet is loaded (or shown as of another date).
    effect(() => {
      const id = this.session.sheetId();
      this.session.user();
      this.session.loads();
      untracked(() => {
        this.role.set(null);
        if (id) void this.load(id);
      });
    });
    // Re-read the descendants' figures from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      const asOf = this.asOf.date();
      this.session.loads();
      untracked(() => {
        this.descendants.set(NONE);
        this.asOfFigures.set(null);
        if (id && asOf) void this.loadTreeAsOf(id, asOf);
        else if (id) void this.loadDescendants(id);
      });
    });
  }

  // The count of claims in the status over the tree.
  private countOf(status: string): number {
    const asOf = this.asOfFigures();
    if (asOf) return asOf.statusCounts?.[status] ?? 0;
    return this.statuses().filter((s) => s === status).length + (this.descendants().statusCounts?.[status] ?? 0);
  }

  private async loadDescendants(id: string): Promise<void> {
    try {
      const figures = await firstValueFrom(
        this.http.get<DescendantFigures>(`/api/sheets/${encodeURIComponent(id)}/descendants/figures`),
      );
      if (this.session.sheetId() === id) this.descendants.set(figures);
    } catch {
      // with none read, the sheet's own counts stand alone
    }
  }

  // Read without the as-of header: the store opens each sheet of the tree as of the date itself.
  private async loadTreeAsOf(id: string, asOf: string): Promise<void> {
    try {
      const figures = await firstValueFrom(
        this.http.get<DescendantFigures>(`/api/sheets/${encodeURIComponent(id)}/descendants/consolidated`, {
          params: { asOf },
          context: new HttpContext().set(LIVE, true),
        }),
      );
      if (this.session.sheetId() === id && this.asOf.date() === asOf) this.asOfFigures.set(figures);
    } catch {
      // with none read, the counts shown as of the date stand
    }
  }

  private async load(id: string): Promise<void> {
    try {
      const r = await firstValueFrom(
        this.http.get<{ user: string; role: string }>(`/api/sheets/${encodeURIComponent(id)}/my-role`),
      );
      if (this.session.sheetId() === id) this.role.set(r.role);
    } catch {
      // with no role read, the dashboard stays blank
    }
  }
}
