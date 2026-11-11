import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface Found {
  sheet: string;
  name: string;
  dueTotal: string;
  grade: string;
}

function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replace(/"/g, '""') + '"' : value;
}

// The tree search: every person on the sheet and on every other sheet of its tree whose name or an
// alias contains the typed text (case ignored), one line each as <sheet id> <name> (<grade>) <due-total>,
// the sheet's people first in list order, then the other sheets' by sheet id. It only reads.
// The export button downloads every person of the tree, in the same order, as a CSV file
// (sheet, name, grade, due-total).
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
      <button type="button" data-testid="export-tree" (click)="exportCsv()">Export the tree's people (CSV)</button>
      @if (exportError(); as err) {
        <p role="alert" data-testid="export-tree-error">{{ err }}</p>
      }
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
  protected readonly exportError = signal<string | null>(null);
  /** Counts presses of search, so only the answer to the latest one is shown. */
  private pressed = 0;

  constructor() {
    // A (re-)loaded sheet starts with nothing found.
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.pressed++;
      this.clear();
      this.exportError.set(null);
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
      this.lines.set(res.people.map((p) => `${p.sheet} ${p.name} (${p.grade}) ${p.dueTotal}`));
      this.count.set(String(res.people.length));
    } catch {
      if (press === this.pressed) this.error.set('Could not search the tree.');
    }
  }

  /** Download everyone on the sheet and the other sheets of its tree, in the search's order, as a CSV file. */
  protected async exportCsv(): Promise<void> {
    this.exportError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    let people: Found[];
    try {
      people = (await firstValueFrom(this.http.post<{ people: Found[] }>('/api/search/tree/list', { sheet, text: '' })))
        .people;
    } catch {
      this.exportError.set('Could not read the people of the tree.');
      return;
    }
    if (this.session.sheet() !== sheet) return;
    const lines = ['sheet,name,grade,due-total'];
    for (const p of people) lines.push([p.sheet, p.name, p.grade, p.dueTotal].map(csvField).join(','));
    const url = URL.createObjectURL(new Blob([lines.map((l) => l + '\n').join('')], { type: 'text/csv' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'tree-' + sheet + '.csv';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  private clear(): void {
    this.lines.set([]);
    this.count.set('');
    this.error.set(null);
  }
}
