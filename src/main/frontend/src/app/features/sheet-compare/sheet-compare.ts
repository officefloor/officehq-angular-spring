import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { PersonWithClaims as Person } from '../claims/claims';
import { GradeList } from '../grades/grades';
import { gradeDueCents } from '../summary/summary';

// The sheet comparison: per grade of this sheet, in grade order, <grade> <this sheet's summary due-total>
// <the other sheet's>, 0.00 for a grade the other sheet has no one on; a sheet that was never loaded
// gives no lines. It reads the store; it records nothing (and never starts the other sheet).
@Component({
  selector: 'app-sheet-compare',
  template: `
    <section aria-label="Sheet comparison">
      <h2>Sheet comparison</h2>
      <form (submit)="compare($event)" aria-label="Compare with another sheet">
        <label for="compare-sheet">Other sheet id</label>
        <input
          id="compare-sheet"
          data-testid="compare-sheet"
          [value]="other()"
          (input)="other.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="compare">Compare</button>
        @if (error()) {
          <p role="alert" data-testid="compare-error">{{ error() }}</p>
        }
      </form>
      <ol>
        @for (line of lines(); track $index) {
          <li data-testid="compare-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class SheetCompare {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  private readonly grades = inject(GradeList);

  readonly people = input.required<Person[]>();

  protected readonly other = signal('');
  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal('');
  /** Bumped on every press, so only the latest press's answer is shown. */
  private compares = 0;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.compares++;
      this.lines.set([]);
      this.error.set('');
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async compare(e: Event): Promise<void> {
    e.preventDefault();
    const sheet = this.session.sheet();
    if (!sheet) return;
    const compare = ++this.compares;
    this.lines.set([]);
    this.error.set('');
    const other = this.other().trim();
    if (!other) {
      this.error.set('Enter the other sheet id.');
      return;
    }
    const mine = gradeDueCents(this.grades.codes(), this.people());
    const id = encodeURIComponent(other);
    try {
      const [, theirs] = await Promise.all([
        firstValueFrom(this.http.get(`/api/sheets/${id}`)),
        firstValueFrom(this.http.get<Person[]>(`/api/sheets/${id}/people`)),
      ]);
      if (compare !== this.compares) return;
      const theirDue = new Map(gradeDueCents(this.grades.codes(), theirs).map((g) => [g.grade, g.due]));
      this.lines.set(
        mine.map((g) => `${g.grade} ${(g.due / 100).toFixed(2)} ${((theirDue.get(g.grade) ?? 0) / 100).toFixed(2)}`),
      );
    } catch (err) {
      if (compare !== this.compares) return;
      if (!(err instanceof HttpErrorResponse && err.status === 404)) this.error.set('Could not compare the sheets.');
    }
  }
}
