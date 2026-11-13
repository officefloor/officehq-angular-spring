import { Component, computed, inject, signal } from '@angular/core';
import { Figures } from '../../core/figures';
import { SheetCompare } from '../../core/sheet-compare';
import { Session } from '../../core/session';

// Compare this sheet with another: one line per grade of this sheet, <grade> <this sheet's summary due-total>
// <the other sheet's> (0.00 for a grade the other sheet has no one on); no lines for a sheet that does not exist.
@Component({
  selector: 'app-sheet-compare',
  template: `
    <section aria-label="Compare sheets" data-testid="sheet-compare-panel">
      <h2>Compare sheets</h2>
      <form (submit)="compare($event)">
        <label for="compare-sheet">Other sheet id</label>
        <input
          id="compare-sheet"
          data-testid="compare-sheet"
          [value]="other()"
          (input)="other.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="compare">Compare</button>
        @if (error(); as err) {
          <p role="alert" data-testid="compare-error">{{ err }}</p>
        }
      </form>
      <ol aria-label="Sheet comparison">
        @for (line of lines(); track $index) {
          <li data-testid="compare-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class SheetComparePanel {
  private readonly comparer = inject(SheetCompare);
  private readonly figures = inject(Figures);
  private readonly session = inject(Session);
  protected readonly other = signal('');
  protected readonly error = signal<string | null>(null);
  private readonly shown = signal<{ sheet: string | null; totals: Record<string, string> | null }>({
    sheet: null,
    totals: null,
  });
  /** The lines of the last comparison, while it is of the sheet loaded now, this sheet's side kept live. */
  protected readonly lines = computed(() => {
    const s = this.shown();
    const totals = s.totals;
    if (s.sheet !== this.session.sheetId() || totals === null) return [];
    return this.figures.gradeDueTotals().map((g) => `${g.grade} ${g.dueTotal} ${totals[g.grade] ?? '0.00'}`);
  });
  private compares = 0;

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async compare(e: Event): Promise<void> {
    e.preventDefault();
    const compare = ++this.compares;
    const sheet = this.session.sheetId();
    this.error.set(null);
    this.shown.set({ sheet: null, totals: null });
    const res = await this.comparer.gradeDueTotals(this.other());
    if (compare !== this.compares) return; // a later press has taken over
    if ('error' in res) this.error.set(res.error);
    else this.shown.set({ sheet, totals: res.totals });
  }
}
