import { Component, computed, inject, input } from '@angular/core';
import { Delegations } from '../../core/delegations';
import { Roles } from '../../core/roles';
import { Session } from '../../core/session';
import { Sheets } from '../../core/sheet';

// The steward's dashboard: how many claims are PENDING, how many are in REVIEW and how many that count are
// overdue, over the sheet and all its descendants. The cells stay empty for a user who is not a steward; under an as-of date the roles and
// delegations are those the sheet held then, so the role is the one the user had then.
@Component({
  selector: 'app-dashboard',
  template: `
    <section aria-labelledby="dash-heading">
      <h2 id="dash-heading">Steward's dashboard</h2>
      <p>Pending: <strong data-testid="dash-pending-count">{{ steward() ? pending() : '' }}</strong>,
        in review: <strong data-testid="dash-review-count">{{ steward() ? review() : '' }}</strong>,
        overdue: <strong data-testid="dash-overdue-count">{{ steward() ? overdue() : '' }}</strong></p>
    </section>
  `,
})
export class Dashboard {
  readonly pending = input.required<number>();
  readonly review = input.required<number>();
  readonly overdue = input.required<number>();

  private readonly session = inject(Session);
  private readonly sheets = inject(Sheets);
  private readonly roles = inject(Roles);
  private readonly delegations = inject(Delegations);

  /** Whether the signed-in user acts as a steward on the sheet's today: by a delegation holding it, else by the
   *  role assigned to them. */
  protected readonly steward = computed(() => {
    const user = this.session.user();
    const today = this.sheets.today();
    if (!user || !today) return false;
    if (this.delegations.all().some((d) => d.user === user && d.from <= today && today <= d.to)) return true;
    return this.roles.all().some((r) => r.user === user && r.role === 'steward');
  });
}
