import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A person found by a tree search: the sheet they are on, their name and their due-total there. */
interface Found {
  sheetId: string;
  name: string;
  dueTotal: string;
}

// Search the active sheet and every other sheet of its tree for people whose name or an alias
// contains the typed text (case ignored): the sheet's own people first in list order, then the
// other sheets' by sheet id.
@Component({
  selector: 'app-tree-search',
  template: `
    <section aria-labelledby="tree-search-heading">
      <h2 id="tree-search-heading">Search the tree</h2>
      <form (submit)="search($event)">
        <label for="search-name">Name or alias contains</label>
        <input
          id="search-name"
          data-testid="search-name"
          [value]="text()"
          (input)="text.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="search">Search</button>
        Found <strong data-testid="search-count">{{ count() }}</strong>
      </form>
      @if (error(); as message) {
        <p role="alert" data-testid="search-error">{{ message }}</p>
      }
      <ul>
        @for (f of found(); track $index) {
          <li data-testid="search-line">{{ f.sheetId }} {{ f.name }} {{ f.dueTotal }}</li>
        }
      </ul>
    </section>
  `,
})
export class TreeSearch {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  protected readonly text = signal('');
  protected readonly found = signal<Found[]>([]);
  /** How many people the last search found; blank before one, and while one runs. */
  protected readonly count = signal('');
  protected readonly error = signal<string | null>(null);

  constructor() {
    // A sheet load starts the search afresh.
    effect(() => {
      this.session.loads();
      this.found.set([]);
      this.count.set('');
      this.error.set(null);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async search(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    this.count.set('');
    this.found.set([]);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const found = await firstValueFrom(
        this.http.get<Found[]>(`/api/sheets/${encodeURIComponent(sheet)}/search`, {
          params: { name: this.text() },
        }),
      );
      this.found.set(found);
      this.count.set(String(found.length));
    } catch {
      this.error.set('The tree could not be searched.');
    }
  }
}
