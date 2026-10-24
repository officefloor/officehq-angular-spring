import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Found, TreeSearchService } from './tree-search.service';

// The tree search: every person of this sheet and the other sheets of its tree whose name or an alias
// contains the typed text (case ignored), one line each as <sheet id> <name> <due-total>. It changes
// nothing.
@Component({
  selector: 'app-tree-search',
  template: `
    <section aria-labelledby="search-heading">
      <h2 id="search-heading">Search the tree</h2>
      <form (submit)="search($event)" aria-label="Tree search">
        <label for="search-name">Name or alias</label>
        <input id="search-name" data-testid="search-name" [value]="text()" (input)="text.set(value($event)); error.set(null)" />
        <button type="submit" data-testid="search">Search</button>
        <span>Found <span data-testid="search-count">{{ count() }}</span></span>
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="search-error">{{ message }}</p>
        }
      </form>
      @if (found().length > 0) {
        <ol data-testid="search-list">
          @for (f of found(); track $index) {
            <li data-testid="search-line">{{ f.sheet }} {{ f.name }} {{ f.dueTotal }}</li>
          }
        </ol>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    ol {
      list-style: none;
      padding: 0;
      font-family: monospace;
      overflow-wrap: anywhere;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class TreeSearch {
  private readonly session = inject(Session);
  private readonly service = inject(TreeSearchService);

  protected readonly text = signal('');
  protected readonly found = signal<Found[]>([]);
  protected readonly count = signal('');
  protected readonly error = signal<string | null>(null);
  private asked = 0;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.asked++;
      this.text.set('');
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
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ask = ++this.asked;
    this.found.set([]);
    this.count.set('');
    this.error.set(null);
    try {
      const found = await this.service.search(sheet, this.text());
      if (ask !== this.asked) return;
      this.found.set(found);
      this.count.set(String(found.length));
    } catch {
      if (ask === this.asked) this.error.set('The search could not be run.');
    }
  }
}
