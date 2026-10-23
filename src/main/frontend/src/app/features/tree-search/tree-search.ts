import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FoundView, TreeSearchApi } from '../../core/tree-search';
import { Session } from '../../core/session';

// Search the sheet's whole tree for people whose name or an alias contains the typed text, case
// ignored: this sheet's people first in list order, then the other sheets' by sheet id.
@Component({
  selector: 'app-tree-search',
  template: `
    <section aria-label="Search the sheet's tree">
      <form (submit)="search($event)">
        <label for="search-name">Name or alias contains</label>
        <input id="search-name" data-testid="search-name" [value]="text()" (input)="text.set(value($event)); error.set(null)" />
        <button type="submit" data-testid="search">Search</button>
        @if (error(); as message) {
          <p role="alert" data-testid="search-error">{{ message }}</p>
        }
      </form>
      @if (found(); as lines) {
        <p>Found <strong data-testid="search-count">{{ lines.length }}</strong></p>
        <ul>
          @for (line of lines; track $index) {
            <li data-testid="search-line">{{ line.sheetId }} {{ line.name }} ({{ line.grade }}) {{ line.dueTotal }}</li>
          }
        </ul>
      }
    </section>
  `,
})
export class TreeSearch {
  private readonly session = inject(Session);
  private readonly api = inject(TreeSearchApi);

  protected readonly text = signal('');
  protected readonly found = signal<FoundView[] | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Counts searches, so only the answer to the latest one is shown. */
  private searches = 0;

  constructor() {
    // Every sheet load clears what was found.
    effect(() => {
      this.session.sheet();
      this.session.loads();
      untracked(() => {
        this.searches++;
        this.found.set(null);
        this.error.set(null);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async search(event: Event): Promise<void> {
    event.preventDefault();
    const sheet = this.session.sheet();
    if (!sheet) return;
    const read = ++this.searches;
    this.found.set(null);
    this.error.set(null);
    try {
      const found = await this.api.search(sheet, this.text());
      if (read === this.searches) this.found.set(found);
    } catch {
      if (read === this.searches) this.error.set('The search could not be made.');
    }
  }
}
