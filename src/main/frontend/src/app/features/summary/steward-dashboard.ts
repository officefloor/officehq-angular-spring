import { Component, computed, input } from '@angular/core';
import { StatusSummary } from './summary-panel';

// A steward's dashboard: how many claims on the sheet and all its descendants are pending, how many
// await review, and how many of the claims that count are overdue. A user who is not a steward sees the cells empty.
@Component({
  selector: 'app-steward-dashboard',
  template: `
    <section aria-label="Steward's dashboard" data-testid="dash">
      <dl>
        <dt>Pending</dt>
        <dd data-testid="dash-pending-count">{{ steward() ? countOf('PENDING') : '' }}</dd>
        <dt>To review</dt>
        <dd data-testid="dash-review-count">{{ steward() ? countOf('REVIEW') : '' }}</dd>
        <dt>Overdue</dt>
        <dd data-testid="dash-overdue-count">{{ steward() ? overdueCount() : '' }}</dd>
      </dl>
    </section>
  `,
})
export class StewardDashboard {
  /** Whether the signed-in user is a steward (as of the as-of day when one is set). */
  readonly steward = input.required<boolean>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly overdueCount = input.required<number>();

  private readonly counts = computed(() => new Map(this.statuses().map((s) => [s.status, s.count])));

  protected countOf(status: string): number {
    return this.counts().get(status) ?? 0;
  }
}
