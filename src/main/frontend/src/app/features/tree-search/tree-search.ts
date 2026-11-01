import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The people of this sheet and every other sheet of its tree whose name or an alias contains the
// text (case ignored), one line each as "<sheet id> <name> <due-total>": this sheet's first in list
// order, then the other sheets' by sheet id.
@Component({
  selector: 'app-tree-search',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="search-heading">
      <h2 id="search-heading">Search the tree</h2>
      <form [formGroup]="form" (ngSubmit)="search()" aria-label="Search the tree">
        <label for="search-name">Name or alias</label>
        <input id="search-name" data-testid="search-name" formControlName="text" />
        <button type="submit" data-testid="search">Search</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="search-error">{{ error() }}</p>
        }
      </form>
      @if (count() !== null) {
        <p>Found <span data-testid="search-count">{{ count() }}</span></p>
      }
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

  protected readonly lines = signal<string[]>([]);
  protected readonly count = signal<number | null>(null);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({ text: new FormControl('', { nonNullable: true }) });
  /** Counts the presses of search, so only the answer to the latest is shown. */
  private request = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.request++;
      this.lines.set([]);
      this.count.set(null);
      this.error.set('');
    });
  }

  protected async search(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const request = ++this.request;
    this.lines.set([]);
    this.count.set(null);
    this.error.set('');
    try {
      const list = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/tree-search`, {
          headers: { 'X-User': this.session.user() ?? '' },
          params: { text: this.form.controls.text.value },
        }),
      );
      if (request !== this.request) return;
      this.lines.set(list);
      this.count.set(list.length);
    } catch {
      if (request === this.request) this.error.set('Could not search the tree.');
    }
  }
}
