import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Found, SearchApi } from './search-api';

// Search the sheet's tree for people by name or alias: one line per person found.
@Component({
  selector: 'app-search',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="search-heading">
      <h2 id="search-heading">Search the tree</h2>
      <form [formGroup]="form" (ngSubmit)="search()" aria-label="Search people over the sheet's tree">
        <label for="search-name">Name or alias</label>
        <input id="search-name" data-testid="search-name" formControlName="text" autocomplete="off" />
        <button type="submit" data-testid="search">Search</button>
        <p>Found <strong data-testid="search-count">{{ found()?.length ?? '' }}</strong></p>
        @if (error(); as err) {
          <p role="alert" data-testid="search-error">{{ err }}</p>
        }
      </form>
      <ul aria-label="People found">
        @for (f of found() ?? []; track $index) {
          <li data-testid="search-line">{{ f.sheetId }} {{ f.name }} ({{ f.grade }}) {{ f.dueTotal }}</li>
        }
      </ul>
    </section>
  `,
})
export class Search {
  private readonly session = inject(Session);
  private readonly api = inject(SearchApi);

  protected readonly form = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  /** The people the last search found; null before one, and while one runs. */
  protected readonly found = signal<Found[] | null>(null);
  protected readonly error = signal<string | null>(null);
  private seq = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    effect(() => {
      this.session.sheetId();
      untracked(() => {
        this.seq++;
        this.found.set(null);
        this.error.set(null);
      });
    });
  }

  protected async search(): Promise<void> {
    const id = this.session.sheetId();
    if (!id) return;
    const seq = ++this.seq;
    this.found.set(null);
    this.error.set(null);
    try {
      const found = await this.api.search(id, this.form.controls.text.value);
      if (seq === this.seq) this.found.set(found);
    } catch {
      if (seq === this.seq) this.error.set('The search could not be run.');
    }
  }
}
