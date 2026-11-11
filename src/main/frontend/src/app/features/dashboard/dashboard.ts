import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The steward's dashboard: how many of the claims of the sheet and its descendants are PENDING and in REVIEW (as the summary
// counts them), and how many claims that count are overdue (as the people list's totals count them).
// Only a steward (in their own right or by a delegation in force) sees the counts; anyone else sees
// the cells empty. Shown as of a date, it is the role the user had then that decides.
@Component({
  selector: 'app-dashboard',
  template: `
    <section aria-labelledby="dashboard-heading">
      <h2 id="dashboard-heading">Steward's dashboard</h2>
      <dl>
        <dt>Pending</dt>
        <dd data-testid="dash-pending-count">{{ steward() ? pendingCount() : '' }}</dd>
        <dt>In review</dt>
        <dd data-testid="dash-review-count">{{ steward() ? reviewCount() : '' }}</dd>
        <dt>Overdue</dt>
        <dd data-testid="dash-overdue-count">{{ steward() ? overdueCount() : '' }}</dd>
      </dl>
    </section>
  `,
})
export class Dashboard {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly pendingCount = input.required<number>();
  readonly reviewCount = input.required<number>();
  readonly overdueCount = input.required<number>();

  /** Whether the signed-in user is a steward on the sheet (as of the date shown). */
  protected readonly steward = signal(false);

  constructor() {
    // Re-read the user's role from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      const user = this.session.user();
      this.session.loads();
      this.steward.set(false);
      if (sheet && user) {
        void this.fetch(sheet, user);
      }
    });
  }

  private async fetch(sheet: string, user: string): Promise<void> {
    try {
      const mine = await firstValueFrom(
        this.http.post<{ user: string; role: string }>('/api/roles/mine/list', { sheet, user }),
      );
      if (this.session.sheet() === sheet && this.session.user() === user) {
        this.steward.set(mine.role === 'steward');
      }
    } catch {
      this.steward.set(false);
    }
  }
}
