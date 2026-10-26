import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure.pipe';
import { Session } from '../../core/session';
import { cents, money } from '../claims/claims-api';
import { ConsolidatedApi, DescendantsView } from './consolidated-api';

// The consolidated figures over the sheet and all its descendants (the sheets naming it as their
// parent, then theirs, and so on): the number of people, and each sheet's due-total and overdue-total
// as that sheet shows it, added up. The sheet's own figures are those it shows; its descendants' are
// re-read each time a sheet is loaded. A sheet with no children shows its own figures. While the screen
// shows the sheet as of a date, the consolidated figures are as of that date too: every sheet of the
// tree as it stood at the end of that day, with that date as its today.
@Component({
  selector: 'app-consolidated',
  imports: [FigurePipe],
  template: `
    <section aria-label="Consolidated figures" data-testid="consolidated">
      <dl>
        <dt>People (with descendants)</dt>
        <dd data-testid="consolidated-people-count">{{ peopleCount() }}</dd>
        <dt>Due (with descendants)</dt>
        <dd data-testid="consolidated-due-total">{{ dueTotal() | figure }}</dd>
        <dt>Overdue (with descendants)</dt>
        <dd data-testid="consolidated-overdue-total">{{ overdueTotal() | figure }}</dd>
      </dl>
    </section>
  `,
})
export class Consolidated {
  private readonly session = inject(Session);
  private readonly api = inject(ConsolidatedApi);

  /** The sheet's own figures, as it shows them. */
  readonly ownPeopleCount = input.required<number>();
  readonly ownDueTotal = input.required<string>();
  readonly ownOverdueTotal = input.required<string>();

  /** The loaded sheet's descendants' figures; null until read (or when the read failed). */
  private readonly descendants = signal<DescendantsView | null>(null);

  /** The sheet's own figures, as it shows them. */
  private readonly own = computed(() =>
    ({ peopleCount: this.ownPeopleCount(), dueTotal: this.ownDueTotal(), overdueTotal: this.ownOverdueTotal() }));

  protected readonly peopleCount = computed(() => this.own().peopleCount + (this.descendants()?.peopleCount ?? 0));
  protected readonly dueTotal = computed(() =>
    money(cents(this.own().dueTotal) + cents(this.descendants()?.dueTotal ?? '0.00')));
  protected readonly overdueTotal = computed(() =>
    money(cents(this.own().overdueTotal) + cents(this.descendants()?.overdueTotal ?? '0.00')));

  constructor() {
    effect(() => {
      // A new sheet view each time a sheet is loaded, so its descendants are read again.
      const sheet = this.session.sheet();
      this.descendants.set(null);
      if (sheet === null) return;
      this.api.descendants(sheet.id).then(
        (view) => { if (this.session.sheet() === sheet) this.descendants.set(view); },
        () => undefined);
    });
  }
}
