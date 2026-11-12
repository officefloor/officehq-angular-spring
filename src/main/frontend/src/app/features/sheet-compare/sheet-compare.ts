import { HttpClient } from '@angular/common/http';
import { FigurePipe } from '../../core/locale';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// Sheet comparison: one line per grade of this sheet as <grade> <this sheet's summary due-total> <the other sheet's>,
// 0.00 for a grade the other sheet has no one on; a sheet that does not exist gives no lines. It records nothing.
@Component({
  selector: 'app-sheet-compare',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="compare-heading">
      <h2 id="compare-heading">Compare with another sheet</h2>
      <form (submit)="compare($event)" aria-labelledby="compare-heading">
        <label for="compare-sheet">Other sheet id</label>
        <input
          id="compare-sheet"
          data-testid="compare-sheet"
          [value]="other()"
          (input)="other.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="compare">Compare</button>
        @if (error(); as message) {
          <p role="alert" data-testid="compare-error">{{ message | fig }}</p>
        }
      </form>
      <ol aria-label="Comparison by grade">
        @for (line of lines(); track $index) {
          <li data-testid="compare-line">{{ line | fig }}</li>
        }
      </ol>
    </section>
  `,
})
export class SheetCompare {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly other = signal('');
  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.reads++;
        this.lines.set([]);
        this.error.set(null);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async compare(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const read = ++this.reads;
    this.lines.set([]);
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/compare`, {
          ...this.session.headers(),
          params: { other: this.other() },
        }),
      );
      // A later comparison wins over a slower earlier one.
      if (read === this.reads && this.session.sheet() === sheet) this.lines.set(lines);
    } catch {
      if (read === this.reads) this.error.set('The sheets could not be compared.');
    }
  }
}
