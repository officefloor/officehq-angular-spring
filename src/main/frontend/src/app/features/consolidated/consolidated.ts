import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { sumSkippingNa } from '../rates/rates';

// The figures of the sheet's descendants (the sheets naming it as parent, theirs, and so on), added up by the store.
interface DescendantFigures {
  peopleCount: number;
  dueTotal: string;
  overdueTotal: string;
}

// The figures over the sheet and all its descendants: the sheet's own, as it shows them, plus each descendant's.
@Component({
  selector: 'app-consolidated',
  template: `
    <section aria-label="Consolidated figures">
      <h2>Consolidated (this sheet and its descendants)</h2>
      <dl>
        <dt>People</dt>
        <dd data-testid="consolidated-people-count">{{ peopleCount() }}</dd>
        <dt>Due</dt>
        <dd data-testid="consolidated-due-total">{{ dueTotal() }}</dd>
        <dt>Overdue</dt>
        <dd data-testid="consolidated-overdue-total">{{ overdueTotal() }}</dd>
      </dl>
    </section>
  `,
})
export class Consolidated {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  // The sheet's own figures, as it shows them.
  readonly ownPeopleCount = input(0);
  readonly ownDueTotal = input('0.00');
  readonly ownOverdueTotal = input('0.00');

  private readonly descendants = signal<DescendantFigures>({ peopleCount: 0, dueTotal: '0.00', overdueTotal: '0.00' });

  protected readonly peopleCount = computed(() => this.ownPeopleCount() + this.descendants().peopleCount);
  protected readonly dueTotal = computed(() => sumSkippingNa([this.ownDueTotal(), this.descendants().dueTotal]));
  protected readonly overdueTotal = computed(() =>
    sumSkippingNa([this.ownOverdueTotal(), this.descendants().overdueTotal]),
  );

  constructor() {
    // Re-read the descendants' figures from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.descendants.set({ peopleCount: 0, dueTotal: '0.00', overdueTotal: '0.00' });
        if (id) void this.load(id);
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
}
