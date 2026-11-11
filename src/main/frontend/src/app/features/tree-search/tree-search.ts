import { HttpClient } from '@angular/common/http';
import { FigurePipe } from '../../core/locale';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// Tree search: every person on this sheet and the other sheets of its tree whose name or an alias contains the typed
// text (case ignored), one line each as <sheet id> <name> <due-total>; this sheet's people first in list order, then
// the other sheets' by sheet id. It records nothing.
@Component({
  selector: 'app-tree-search',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="tree-search-heading">
      <h2 id="tree-search-heading">Search the sheet tree</h2>
      <form (submit)="search($event)" aria-labelledby="tree-search-heading">
        <label for="search-name">Name or alias contains</label>
        <input
          id="search-name"
          data-testid="search-name"
          [value]="text()"
          (input)="text.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="search">Search</button>
        @if (error(); as message) {
          <p role="alert" data-testid="search-error">{{ message | fig }}</p>
        }
      </form>
      <p>Found <strong data-testid="search-count">{{ count() ?? '' | fig }}</strong></p>
      <ol aria-label="Search results">
        @for (line of lines(); track $index) {
          <li data-testid="search-line">{{ line | fig }}</li>
        }
      </ol>
    </section>
  `,
})
export class TreeSearch {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly text = signal('');
  protected readonly lines = signal<string[]>([]);
  /** How many lines the last search found; null while a search runs and before the first. */
  protected readonly count = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.reads++;
        this.lines.set([]);
        this.count.set(null);
        this.error.set(null);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async search(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const read = ++this.reads;
    this.lines.set([]);
    this.count.set(null);
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/tree/search`, {
          ...this.session.headers(),
          params: { name: this.text() },
        }),
      );
      // A later search wins over a slower earlier one.
      if (read === this.reads && this.session.sheet() === sheet) {
        this.lines.set(lines);
        this.count.set(lines.length);
      }
    } catch {
      if (read === this.reads) this.error.set('The sheet tree could not be searched.');
    }
  }
}
