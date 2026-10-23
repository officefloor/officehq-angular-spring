import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { DashboardApi, DashboardView } from '../../core/dashboard';
import { Session } from '../../core/session';
import { StatusSummary } from '../summary/summary';

// The steward's dashboard: the sheet's PENDING claims, REVIEW claims and overdue claims that count, as
// the store holds them now. The cells are empty for a user who is not a steward, and while an as-of date is set. Re-read on every sheet load
// and whenever the sheet's claims or figures move.
@Component({
  selector: 'app-dashboard',
  template: `
    <section aria-label="Steward dashboard">
      <p>Pending claims <strong data-testid="dash-pending-count">{{ view()?.pendingCount ?? '' }}</strong></p>
      <p>Claims in review <strong data-testid="dash-review-count">{{ view()?.reviewCount ?? '' }}</strong></p>
      <p>Overdue claims <strong data-testid="dash-overdue-count">{{ view()?.overdueCount ?? '' }}</strong></p>
    </section>
  `,
})
export class Dashboard {
  private readonly session = inject(Session);
  private readonly api = inject(DashboardApi);

  /** The sheet's claim counts by status and its overdue-total as shown: a move in either re-reads the dashboard. */
  readonly statuses = input.required<StatusSummary[]>();
  readonly overdueTotal = input.required<string>();

  protected readonly view = signal<DashboardView | null>(null);
  /** Counts reads, so only the answer to the latest one is shown. */
  private reads = 0;

  constructor() {
    // Every sheet load clears the counts shown and re-reads them.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      untracked(() => {
        this.view.set(null);
        if (sheet) void this.reload(sheet);
      });
    });
    // A move in the sheet's today, its as-of date, the signed-in user, or its claims re-reads them.
    effect(() => {
      this.session.today();
      this.session.asOf();
      this.session.user();
      this.statuses();
      this.overdueTotal();
      untracked(() => {
        const sheet = this.session.sheet();
        if (sheet) void this.reload(sheet);
      });
    });
  }

  private async reload(sheet: string): Promise<void> {
    const read = ++this.reads;
    try {
      const view = await this.api.get(sheet);
      if (read === this.reads && this.session.sheet() === sheet) this.view.set(view);
    } catch {
      // Left as it was: the next load or change reads them again.
    }
  }
}
