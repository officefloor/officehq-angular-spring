import { Component, computed, inject, signal } from '@angular/core';
import { download } from '../../core/csv';
import { Found, TreeSearch } from '../../core/search';
import { Session } from '../../core/session';

// Search over this sheet and every other sheet of its tree: everyone whose name or an alias contains the
// text (case ignored), one line each as <sheet id> <name> (<grade>) <due-total>, this sheet's people first in list
// order, then the other sheets' by sheet id.
@Component({
  selector: 'app-search',
  template: `
    <section aria-label="Search" data-testid="search-panel">
      <h2>Search the tree</h2>
      <form (submit)="search($event)">
        <label for="search-name">Name or alias contains</label>
        <input
          id="search-name"
          data-testid="search-name"
          [value]="text()"
          (input)="text.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="search">Search</button>
        @if (error(); as err) {
          <p role="alert" data-testid="search-error">{{ err }}</p>
        }
      </form>
      <button type="button" data-testid="export-tree" (click)="exportTree()">Export the tree's people (CSV)</button>
      @if (exportError(); as err) {
        <p role="alert" data-testid="export-tree-error">{{ err }}</p>
      }
      <p>Found: <strong data-testid="search-count">{{ count() }}</strong></p>
      <ol aria-label="Search results">
        @for (f of found(); track $index) {
          <li data-testid="search-line">{{ f.sheetId }} {{ f.name }} ({{ f.grade }}) {{ f.dueTotal }}</li>
        }
      </ol>
    </section>
  `,
})
export class SearchPanel {
  private readonly searcher = inject(TreeSearch);
  private readonly session = inject(Session);
  protected readonly text = signal('');
  protected readonly error = signal<string | null>(null);
  private readonly shown = signal<{ sheet: string | null; found: Found[] | null }>({ sheet: null, found: null });
  /** The people last found, while they are of the sheet loaded now; null before a search or while one runs. */
  protected readonly found = computed(() => {
    const s = this.shown();
    return s.sheet === this.session.sheetId() ? (s.found ?? []) : [];
  });
  protected readonly count = computed(() => {
    const s = this.shown();
    return s.sheet === this.session.sheetId() && s.found ? String(s.found.length) : '';
  });
  protected readonly exportError = signal<string | null>(null);
  private searches = 0;

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async search(e: Event): Promise<void> {
    e.preventDefault();
    const search = ++this.searches;
    const sheet = this.session.sheetId();
    this.error.set(null);
    this.shown.set({ sheet: null, found: null });
    const res = await this.searcher.find(this.text());
    if (search !== this.searches) return; // a later press has taken over
    if ('error' in res) this.error.set(res.error);
    else this.shown.set({ sheet, found: res.found });
  }

  /** Download everyone of the tree as CSV: sheet, name, grade and due-total, this sheet's people first in
   *  list order, then the other sheets' by sheet id. */
  protected async exportTree(): Promise<void> {
    this.exportError.set(null);
    await this.session.loaded();
    const res = await this.searcher.find('');
    if ('error' in res) {
      this.exportError.set(res.error);
      return;
    }
    const lines = [['sheet', 'name', 'grade', 'due-total']];
    for (const f of res.found) lines.push([f.sheetId, f.name, f.grade, f.dueTotal]);
    download(lines, 'tree.csv');
  }
}
