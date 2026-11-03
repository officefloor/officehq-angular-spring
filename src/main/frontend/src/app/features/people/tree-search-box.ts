import { Component, effect, inject, signal } from '@angular/core';
import { Found, TreeSearch } from '../../core/tree-search';
import { Session } from '../../core/session';

// The search box: lists everyone on this sheet and the other sheets of its tree whose name or an
// alias contains the typed text, case ignored, one line per person as <sheet id> <name> <due-total>.
@Component({
  selector: 'app-tree-search-box',
  template: `
    <div role="search" aria-label="Search the sheet's tree">
      <label for="search-name">Search people of the tree</label>
      <input id="search-name" data-testid="search-name" [value]="text()" (input)="typed($event)" />
      <button type="button" data-testid="search" (click)="search()">Search</button>
      @if (found() !== null) {
        <p role="status">Found <strong data-testid="search-count">{{ found()!.length }}</strong></p>
        <ul aria-label="People found">
          @for (f of found(); track $index) {
            <li data-testid="search-line">{{ f.sheet }} {{ f.name }} {{ f.dueTotal }}</li>
          }
        </ul>
      }
      @if (error()) {
        <p role="alert" data-testid="search-error">{{ error() }}</p>
      }
    </div>
  `,
})
export class TreeSearchBox {
  private readonly api = inject(TreeSearch);
  private readonly session = inject(Session);

  protected readonly text = signal('');
  protected readonly error = signal('');
  /** The people the last search found; null while one runs or before the first. */
  protected readonly found = signal<Found[] | null>(null);
  /** Counts searches, so only the latest one's answer is shown. */
  private searches = 0;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.found.set(null);
      this.error.set('');
    });
  }

  protected typed(event: Event): void {
    this.text.set((event.target as HTMLInputElement).value);
    this.error.set('');
  }

  protected async search(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const at = ++this.searches;
    this.error.set('');
    this.found.set(null);
    try {
      const found = await this.api.search(sheet, this.text());
      if (at === this.searches) this.found.set(found);
    } catch {
      if (at === this.searches) this.error.set('The search could not be made.');
    }
  }
}
