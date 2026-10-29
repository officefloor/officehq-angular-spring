import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The steward's dashboard: over the sheet, the number of PENDING claims, of REVIEW claims and of overdue claims that
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
        <dd data-testid="dash-overdue-count">{{ steward() ? (overdueCount() | fig) : '' }}</dd>
      </dl>
    </section>
  `,
})
export class Dashboard {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  // The status of every claim on the sheet, and the number of overdue claims counted live.
  readonly statuses = input<readonly string[]>([]);
  readonly overdueCount = input(0);

  // The role the signed-in user acts in on the sheet, as the store answers it; null until read.
  private readonly role = signal<string | null>(null);

  protected readonly steward = computed(() => this.role() === 'steward');
  protected readonly pendingCount = computed(() => this.statuses().filter((s) => s === 'PENDING').length);
  protected readonly reviewCount = computed(() => this.statuses().filter((s) => s === 'REVIEW').length);

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
