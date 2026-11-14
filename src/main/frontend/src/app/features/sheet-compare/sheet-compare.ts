import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { cents, money } from '../claims/claims';
import { GradeSummary } from '../summary/summary';

/** Another sheet's figures per grade as its summary shows them. */
interface GradeDueTotals {
  exists: boolean;
  gradeDueTotals: Record<string, string>;
}

/** A grade of this sheet with its summary due-total here and on the other sheet. */
interface CompareLine {
  grade: string;
  mine: string;
  theirs: string;
}

// This sheet compared with another, read afresh on each press: per grade of this sheet, its summary
// due-total here and on the other sheet (0.00 when no one there is on it); a sheet never used gives
// no lines.
@Component({
  selector: 'app-sheet-compare',
  template: `
    <section aria-labelledby="sheet-compare-heading">
      <h2 id="sheet-compare-heading">Compare with another sheet</h2>
      <form (submit)="compare($event)">
        <label for="compare-sheet">Other sheet id</label>
        <input
          id="compare-sheet"
          data-testid="compare-sheet"
          [value]="other()"
          (input)="other.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="compare">Compare</button>
      </form>
      @if (error(); as message) {
        <p role="alert" data-testid="compare-error">{{ message }}</p>
      }
      <ul>
        @for (l of lines(); track l.grade) {
          <li data-testid="compare-line">{{ l.grade }} {{ l.mine }} {{ l.theirs }}</li>
        }
      </ul>
    </section>
  `,
})
export class SheetCompare {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  /** This sheet's summary per grade. */
  readonly grades = input.required<GradeSummary[]>();
  protected readonly other = signal('');
  protected readonly lines = signal<CompareLine[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // A sheet load clears the lines shown for the sheet before.
    effect(() => {
      this.session.loads();
      this.lines.set([]);
      this.error.set(null);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async compare(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    this.lines.set([]);
    const sheet = this.session.sheetId();
    const other = this.other().trim();
    if (!sheet || !other) return;
    try {
      const theirs = await firstValueFrom(
        this.http.get<GradeDueTotals>(`/api/sheets/${encodeURIComponent(other)}/grade-due-totals`),
      );
      if (this.session.sheetId() !== sheet || !theirs.exists) return;
      this.lines.set(
        this.grades().map((g) => ({
          grade: g.grade,
          mine: g.dueTotal,
          theirs: money(cents(theirs.gradeDueTotals[g.grade] ?? '0.00')),
        })),
      );
    } catch {
      this.error.set('The sheets could not be compared.');
    }
  }
}
