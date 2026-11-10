import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface Found {
  sheet: string;
  name: string;
  dueTotal: string;
}

// The tree search: every person on the sheet and on every other sheet of its tree whose name or an
// alias contains the typed text (case ignored), one line each as <sheet id> <name> <due-total>,
// the sheet's people first in list order, then the other sheets' by sheet id. It only reads.
@Component({
  selector: 'app-tree-search',
  template: `
    <section aria-labelledby="search-heading">
      <h2 id="search-heading">Search the tree</h2>
      <form aria-label="Tree search" (submit)="search($event)">
        <label for="search-name">Name or alias</label>
        <input
          id="search-name"
          data-testid="search-name"
          [value]="textInput()"
          (input)="textInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="search">Search</button>
        <output data-testid="search-count" aria-label="People found">{{ count() }}</output>
        @if (error(); as err) {
          <p role="alert" data-testid="search-error">{{ err }}</p>
        }
      </form>
      <ol aria-label="People found">
        @for (line of lines(); track $index) {
          <li data-testid="search-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class TreeSearch {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly textInput = signal('');
  protected readonly lines = signal<string[]>([]);
  protected readonly count = signal('');
  protected readonly error = signal<string | null>(null);
  /** Counts presses of search, so only the answer to the latest one is shown. */
  private pressed = 0;

  constructor() {
    // A (re-)loaded sheet starts with nothing found.
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.pressed++;
      this.clear();
    });
  }

  protected async search(event: Event): Promise<void> {
    event.preventDefault();
    const press = ++this.pressed;
    this.clear();
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      const res = await firstValueFrom(
        this.http.post<{ people: Found[] }>('/api/search/tree/list', { sheet, text: this.textInput() }),
      );
      if (press !== this.pressed) return;
      this.lines.set(res.people.map((p) => `${p.sheet} ${p.name} ${p.dueTotal}`));
      this.count.set(String(res.people.length));
    } catch {
      if (press === this.pressed) this.error.set('Could not search the tree.');
    }
  }

  private clear(): void {
    this.lines.set([]);
    this.count.set('');
    this.error.set(null);
  }
}
