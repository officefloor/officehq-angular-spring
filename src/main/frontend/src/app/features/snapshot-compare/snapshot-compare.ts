import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { FigurePipe } from '../../core/locale';
import { Session } from '../../core/session';

// Two snapshot dates compared: for each person in list order, one line as <name> <then> <now>, then the due-total of
// the SNAPSHOT written on leaving the from-date, now that of the to-date (or the due-total now when the to-date is
// blank); a figure with no SNAPSHOT is left out. It records nothing.
@Component({
  selector: 'app-snapshot-compare',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="snapshot-compare-heading">
      <h2 id="snapshot-compare-heading">Compare snapshots</h2>
      <form (submit)="compare($event)">
        <label for="snapshot-from">From</label>
        <input
          id="snapshot-from"
          type="date"
          data-testid="snapshot-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="snapshot-to">To</label>
        <input
          id="snapshot-to"
          type="date"
          data-testid="snapshot-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="snapshot-compare">Compare</button>
      </form>
      @if (error(); as message) {
        <p role="alert" data-testid="snapshot-compare-error">{{ message | fig }}</p>
      }
      <ol aria-label="Snapshot comparison">
        @for (line of lines(); track $index) {
          <li data-testid="snapshot-line">{{ line | fig }}</li>
        }
      </ol>
    </section>
  `,
})
export class SnapshotCompare {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly from = signal('');
  protected readonly to = signal('');
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
    const params: Record<string, string> = {};
    if (this.from().trim()) params['from'] = this.from().trim();
    if (this.to().trim()) params['to'] = this.to().trim();
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/people/snapshot-compare`, {
          ...this.session.headers(),
          params,
        }),
      );
      // A later read wins over a slower earlier one.
      if (read === this.reads && this.session.sheet() === sheet) this.lines.set(lines);
    } catch {
      if (read === this.reads) this.error.set('The snapshots could not be compared.');
    }
  }
}
