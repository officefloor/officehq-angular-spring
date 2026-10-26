import { Component, effect, inject, input, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { CompareSheetApi } from './consolidated-api';
import { GradeSummary } from './summary';

// This sheet compared with another by id: one line per grade of this sheet, as
// `<grade> <this sheet's summary due-total> <the other sheet's>`, 0.00 for a grade the other sheet has
// no one on. A sheet that does not exist gives no lines. The lines are cleared when the button is pressed.
@Component({
  selector: 'app-compare-sheet',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-label="Compare with another sheet" data-testid="compare-section">
      <form (submit)="$event.preventDefault(); compare()">
        <label for="compare-sheet">Compare with sheet</label>
        <input id="compare-sheet" data-testid="compare-sheet" [formControl]="other" />
        <button type="submit" data-testid="compare">Compare</button>
      </form>
      @if (error()) {
        <p role="alert" data-testid="compare-error">The sheets could not be compared.</p>
      }
      <ul aria-live="polite">
        @for (line of lines(); track $index) {
          <li data-testid="compare-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class CompareSheet {
  private readonly session = inject(Session);
  private readonly api = inject(CompareSheetApi);

  /** This sheet's summary by grade. */
  readonly grades = input.required<GradeSummary[]>();

  protected readonly other = new FormControl('', { nonNullable: true });
  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal(false);
  private run = 0;

  constructor() {
    effect(() => {
      // A newly loaded sheet starts with no comparison shown.
      this.session.sheet();
      this.run++;
      this.lines.set([]);
      this.error.set(false);
    });
  }

  protected compare(): void {
    const run = ++this.run;
    this.lines.set([]);
    this.error.set(false);
    const other = this.other.value.trim();
    if (other === '') return;
    this.api.gradeDueTotals(other).then(
      (totals) => {
        if (run !== this.run || Object.keys(totals).length === 0) return;
        this.lines.set(this.grades().map((g) => `${g.grade} ${g.dueTotal} ${totals[g.grade] ?? '0.00'}`));
      },
      () => { if (run === this.run) this.error.set(true); });
  }
}
