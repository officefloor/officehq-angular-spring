import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A person found over the sheet's tree: their sheet, name and due-total as shown. */
interface Found {
  sheetId: string;
  name: string;
  dueTotal: string;
}

// The tree search: every person on the sheet or another sheet of its tree whose name or an alias contains the
// typed text (case ignored), one line each as <sheet id> <name> <due-total>, the sheet's own people first in
// list order, then the other sheets' by sheet id. It reads the store; it records nothing.
@Component({
  selector: 'app-search',
  template: `
    <section aria-label="Tree search">
      <h2>Tree search</h2>
      <form (submit)="search($event)" aria-label="Search the tree">
        <label for="search-name">Name or alias</label>
        <input
          id="search-name"
          data-testid="search-name"
          [value]="text()"
          (input)="text.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="search">Search</button>
        @if (count() !== null) {
          <span>Found <span data-testid="search-count">{{ count() }}</span></span>
        }
        @if (error()) {
          <p role="alert" data-testid="search-error">{{ error() }}</p>
        }
      </form>
      <ol>
        @for (line of lines(); track $index) {
          <li data-testid="search-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class Search {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly text = signal('');
  protected readonly lines = signal<string[]>([]);
  protected readonly count = signal<number | null>(null);
  protected readonly error = signal('');
  /** Bumped on every press, so only the latest press's answer is shown. */
  private searches = 0;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.searches++;
      this.lines.set([]);
      this.count.set(null);
      this.error.set('');
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async search(e: Event): Promise<void> {
    e.preventDefault();
    const sheet = this.session.sheet();
    if (!sheet) return;
    const search = ++this.searches;
    this.lines.set([]);
    this.count.set(null);
    this.error.set('');
    try {
      const found = await firstValueFrom(
        this.http.get<Found[]>(`/api/sheets/${encodeURIComponent(sheet)}/people/search`, {
          params: { text: this.text() },
        }),
      );
      if (search !== this.searches) return;
      this.lines.set(found.map((f) => `${f.sheetId} ${f.name} ${f.dueTotal}`));
      this.count.set(found.length);
    } catch {
      if (search === this.searches) this.error.set('Could not search the tree.');
    }
  }
}
