import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Sheets } from '../../core/sheet';
import { TreeFound, TreeSearch } from '../../core/tree-search';

/** A CSV field, quoted when it holds a quote, comma or line break. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replaceAll('"', '""') + '"' : value;
}

// Search this sheet and every other sheet of its tree for people by name or alias.
@Component({
  selector: 'app-search',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="search-heading">
      <h2 id="search-heading">Search the tree</h2>
      <form [formGroup]="form" (ngSubmit)="search()" aria-label="Search the sheet's tree for people">
        <label for="search-name">Name or alias contains</label>
        <input id="search-name" data-testid="search-name" formControlName="text" autocomplete="off" />
        <button type="submit" data-testid="search" [disabled]="searching()">Search</button>
        <p>Found: <strong data-testid="search-count">{{ count() }}</strong></p>
        @if (error(); as err) {
          <p role="alert" data-testid="search-error">{{ err }}</p>
        }
      </form>
      <ul aria-label="People found">
        @for (f of found(); track $index) {
          <li data-testid="search-line">{{ f.sheet }} {{ f.name }} ({{ f.grade }}) {{ f.dueTotal }}</li>
        }
      </ul>
      <p>
        <button type="button" data-testid="export-tree" (click)="exportTree()">Export the tree's people as CSV</button>
        @if (exportError(); as err) {
          <span role="alert" data-testid="export-tree-error">{{ err }}</span>
        }
      </p>
    </section>
  `,
})
export class SearchPanel {
  private readonly sheets = inject(Sheets);
  private readonly tree = inject(TreeSearch);

  protected readonly form = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  protected readonly found = signal<readonly TreeFound[]>([]);
  /** How many were found by the last search; cleared while a search runs. */
  protected readonly count = signal('');
  protected readonly searching = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly exportError = signal<string | null>(null);

  constructor() {
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.subscribe(() => this.error.set(null));
  }

  protected search(): void {
    // A second press before the first has answered is ignored.
    if (this.searching()) return;
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.error.set(null);
    this.count.set('');
    this.found.set([]);
    this.searching.set(true);
    this.tree.search(sheet.id, this.form.controls.text.value.trim()).subscribe({
      next: (found) => {
        this.searching.set(false);
        this.found.set(found);
        this.count.set(String(found.length));
      },
      error: () => {
        this.searching.set(false);
        this.error.set('Could not search the tree.');
      },
    });
  }

  /** Download the people of the whole tree as CSV, re-read from the store: this sheet's first in list order, then
   *  the other sheets' by sheet id. */
  protected exportTree(): void {
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.exportError.set(null);
    this.tree.search(sheet.id, '').subscribe({
      next: (found) => {
        const lines = [
          ['sheet', 'name', 'grade', 'due-total'],
          ...found.map((f) => [f.sheet, f.name, f.grade, f.dueTotal]),
        ].map((fields) => fields.map(csvField).join(',') + '\n');
        const url = URL.createObjectURL(new Blob(lines, { type: 'text/csv' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = 'tree-' + sheet.id + '.csv';
        link.click();
        setTimeout(() => URL.revokeObjectURL(url));
      },
      error: () => this.exportError.set('Could not read the tree.'),
    });
  }
}
