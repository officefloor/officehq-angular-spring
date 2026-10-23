import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { FigurePipe } from '../../core/locale';
import { ConsolidatedApi, ConsolidatedView } from '../../core/consolidated';
import { Session } from '../../core/session';

// The sheet's figures consolidated over it and all its descendants: the number of people, and each
// sheet's due-total and overdue-total, as that sheet shows them, added up. Re-read on every sheet load
// and whenever the sheet's own figures move.
@Component({
  selector: 'app-consolidated',
  imports: [FigurePipe],
  template: `
    <section aria-label="Consolidated figures">
      <p>Consolidated people <strong data-testid="consolidated-people-count">{{ figures()?.peopleCount }}</strong></p>
      <p>Consolidated due <strong data-testid="consolidated-due-total">{{ (figures()?.dueTotal) | figure }}</strong></p>
      <p>Consolidated overdue <strong data-testid="consolidated-overdue-total">{{ (figures()?.overdueTotal) | figure }}</strong></p>
    </section>
  `,
})
export class Consolidated {
  private readonly session = inject(Session);
  private readonly api = inject(ConsolidatedApi);

  /** The sheet's own figures as shown: a move in any of them re-reads the consolidated ones. */
  readonly peopleCount = input.required<number>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();

  protected readonly figures = signal<ConsolidatedView | null>(null);
  /** Counts reads, so only the answer to the latest one is shown. */
  private reads = 0;

  constructor() {
    // Every sheet load clears the figures shown and re-reads them.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      untracked(() => {
        this.figures.set(null);
        if (sheet) void this.reload(sheet);
      });
    });
    // A move in the sheet's own today or figures re-reads them.
    effect(() => {
      this.session.today();
      this.peopleCount();
      this.dueTotal();
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
      const figures = await this.api.get(sheet);
      if (read === this.reads && this.session.sheet() === sheet) this.figures.set(figures);
    } catch {
      // Left as it was: the next load or change reads them again.
    }
  }
}
