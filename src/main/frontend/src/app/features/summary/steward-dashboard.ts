import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { DashboardApi, DashboardView } from './dashboard-api';

// A steward's dashboard over the sheet: how many claims are PENDING, how many are in REVIEW, and how many
// are overdue and count. A user who is not a steward sees the cells empty. Re-read each time a sheet is
// loaded; while the screen shows the sheet as of a date, as it stood then, for the role the user had then.
@Component({
  selector: 'app-steward-dashboard',
  template: `
    <section aria-label="Steward dashboard" data-testid="steward-dashboard">
      <dl>
        <dt>Pending approval</dt>
        <dd data-testid="dash-pending-count">{{ view()?.pending ?? '' }}</dd>
        <dt>In review</dt>
        <dd data-testid="dash-review-count">{{ view()?.review ?? '' }}</dd>
        <dt>Overdue</dt>
        <dd data-testid="dash-overdue-count">{{ view()?.overdue ?? '' }}</dd>
      </dl>
    </section>
  `,
})
export class StewardDashboard {
  private readonly session = inject(Session);
  private readonly api = inject(DashboardApi);

  /** The loaded sheet's dashboard; null until read (or when the read failed). */
  protected readonly view = signal<DashboardView | null>(null);

  constructor() {
    effect(() => {
      // A new sheet view each time a sheet is loaded, so the dashboard is read again.
      const sheet = this.session.sheet();
      this.view.set(null);
      if (sheet === null) return;
      this.session.reading(this.api.dashboard(sheet.id)).then(
        (view) => { if (this.session.sheet() === sheet) this.view.set(view); },
        () => undefined);
    });
  }
}
