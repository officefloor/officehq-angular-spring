import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Comparison, SheetCompare } from '../../core/sheet-compare';
import { Session } from '../../core/session';
import { GradeSummary } from './summary-panel';

// The comparison with another sheet: one line per grade of this sheet as
// <grade> <this sheet's summary due-total> <the other sheet's> (0.00 for a grade the other sheet has
// no one on); a sheet that does not exist gives no lines.
@Component({
  selector: 'app-sheet-compare-box',
  template: `
    <div role="group" aria-label="Compare with another sheet">
      <label for="compare-sheet">Compare with sheet</label>
      <input id="compare-sheet" data-testid="compare-sheet" [value]="other()" (input)="typed($event)" />
      <button type="button" data-testid="compare" (click)="compare()">Compare</button>
      @if (comparison() !== null) {
        <ul aria-label="Due per grade, this sheet and the other">
          @for (l of lines(); track $index) {
            <li data-testid="compare-line">{{ l }}</li>
          }
        </ul>
      }
      @if (error()) {
        <p role="alert" data-testid="compare-error">{{ error() }}</p>
      }
    </div>
  `,
})
export class SheetCompareBox {
  private readonly api = inject(SheetCompare);
  private readonly session = inject(Session);

  /** This sheet's summary by grade. */
  readonly grades = input.required<GradeSummary[]>();

  protected readonly other = signal('');
  protected readonly error = signal('');
  /** The other sheet's figures from the last comparison; null while one runs or before the first. */
  protected readonly comparison = signal<Comparison | null>(null);
  protected readonly lines = computed(() => {
    const c = this.comparison();
    if (c === null || !c.found) return [];
    return this.grades().map((g) => `${g.grade} ${g.dueTotal} ${c.gradeDueTotals[g.grade] ?? '0.00'}`);
  });
  /** Counts comparisons, so only the latest one's answer is shown. */
  private comparisons = 0;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.comparison.set(null);
      this.error.set('');
    });
  }

  protected typed(event: Event): void {
    this.other.set((event.target as HTMLInputElement).value);
    this.error.set('');
  }

  protected async compare(): Promise<void> {
    const sheet = this.session.sheetId();
    const other = this.other().trim();
    if (!sheet) return;
    const at = ++this.comparisons;
    this.error.set('');
    this.comparison.set(null);
    if (!other) {
      this.error.set('Enter the id of the sheet to compare with.');
      return;
    }
    try {
      const c = await this.api.compare(sheet, other);
      if (at === this.comparisons) this.comparison.set(c);
    } catch {
      if (at === this.comparisons) this.error.set('The comparison could not be made.');
    }
  }
}
