import { DOCUMENT } from '@angular/common';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { TreePerson, TreeSearchApi, TreeSearchHit } from './consolidated-api';

// A search over the sheet and every other sheet of its tree: each person whose name or an alias
// contains the typed text, case ignored, one line per person as `<sheet id> <name> <due-total>`.
// The count is cleared when the button is pressed and shown again once the lines are read.
// The export downloads every person of the tree in the same order as a CSV file.
@Component({
  selector: 'app-tree-search',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-label="Search the tree" data-testid="tree-search">
      <form (submit)="$event.preventDefault(); search()">
        <label for="search-name">Search the tree</label>
        <input id="search-name" data-testid="search-name" [formControl]="text" />
        <button type="submit" data-testid="search">Search</button>
        <span aria-live="polite">Found <span data-testid="search-count">{{ count() }}</span></span>
      </form>
      <button type="button" data-testid="export-tree" (click)="exportTree()">Export the tree's people as CSV</button>
      @if (error()) {
        <p role="alert" data-testid="search-error">The search could not be run.</p>
      }
      @if (exportError()) {
        <p role="alert" data-testid="export-tree-error">The tree could not be exported.</p>
      }
      <ul>
        @for (hit of hits(); track $index) {
          <li data-testid="search-line">{{ hit.sheetId }} {{ hit.name }} {{ hit.dueTotal }}</li>
        }
      </ul>
    </section>
  `,
})
export class TreeSearch {
  private readonly session = inject(Session);
  private readonly api = inject(TreeSearchApi);
  private readonly document = inject(DOCUMENT);

  protected readonly text = new FormControl('', { nonNullable: true });
  protected readonly hits = signal<TreeSearchHit[]>([]);
  protected readonly count = signal('');
  protected readonly error = signal(false);
  protected readonly exportError = signal(false);
  private run = 0;

  constructor() {
    effect(() => {
      // A newly loaded sheet starts with no search shown.
      this.session.sheet();
      this.run++;
      this.hits.set([]);
      this.count.set('');
      this.error.set(false);
      this.exportError.set(false);
    });
  }

  protected search(): void {
    const sheet = this.session.sheet();
    const run = ++this.run;
    this.hits.set([]);
    this.count.set('');
    this.error.set(false);
    if (sheet === null) return;
    this.api.search(sheet.id, this.text.value).then(
      (hits) => {
        if (run !== this.run) return;
        this.hits.set(hits);
        this.count.set(String(hits.length));
      },
      () => { if (run === this.run) this.error.set(true); });
  }

  /** Downloads every person of the tree: the sheet's own first in list order, then the other sheets' by
   *  sheet id, with their sheet, name, grade and due-total; a field holding a quote, comma or line end is
   *  quoted, and each line ends in a line feed. */
  protected async exportTree(): Promise<void> {
    const sheet = this.session.sheet();
    this.exportError.set(false);
    if (sheet === null) return;
    await this.session.settled();
    let people: TreePerson[];
    try {
      people = await this.api.everyone(sheet.id);
    } catch {
      this.exportError.set(true);
      return;
    }
    const field = (v: string) => /[",\r\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v;
    const lines = [['sheet', 'name', 'grade', 'due-total'],
      ...people.map((p) => [p.sheetId, p.name, p.grade, p.dueTotal])];
    const csv = lines.map((l) => l.map(field).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = 'tree.csv';
    link.click();
    // Released once the browser has taken the file.
    setTimeout(() => URL.revokeObjectURL(url));
  }
}
