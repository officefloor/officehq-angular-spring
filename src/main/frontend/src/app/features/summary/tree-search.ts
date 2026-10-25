import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { TreeSearchApi, TreeSearchHit } from './consolidated-api';

// A search over the sheet and every other sheet of its tree: each person whose name or an alias
// contains the typed text, case ignored, one line per person as `<sheet id> <name> <due-total>`.
// The count is cleared when the button is pressed and shown again once the lines are read.
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
      @if (error()) {
        <p role="alert" data-testid="search-error">The search could not be run.</p>
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

  protected readonly text = new FormControl('', { nonNullable: true });
  protected readonly hits = signal<TreeSearchHit[]>([]);
  protected readonly count = signal('');
  protected readonly error = signal(false);
  private run = 0;

  constructor() {
    effect(() => {
      // A newly loaded sheet starts with no search shown.
      this.session.sheet();
      this.run++;
      this.hits.set([]);
      this.count.set('');
      this.error.set(false);
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
}
