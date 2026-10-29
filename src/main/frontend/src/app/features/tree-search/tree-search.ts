import { HttpClient, HttpContext } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { firstValueFrom } from 'rxjs';
import { LIVE } from '../../core/as-of';
import { Session } from '../../core/session';

// A person found over the sheet's tree: their sheet, name and due-total (two decimals, or n/a).
interface Found {
  sheetId: string;
  name: string;
  grade: string | null;
  dueTotal: string;
}

// The tree search: every person on the active sheet or another sheet of its tree whose name or an alias contains the
// text, case ignored, as the store holds them now; the sheet's own people first in list order, then the other sheets'
// by sheet id.
@Component({
  selector: 'app-tree-search',
  imports: [FigPipe],
  template: `
    <section aria-label="Tree search">
      <h2>Search the tree</h2>
      <form (submit)="search($event)" aria-label="Search the tree">
        <label for="search-name">Name or alias</label>
        <input
          id="search-name"
          data-testid="search-name"
          [value]="text()"
          (input)="text.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="search">Search</button>
        @if (error(); as err) {
          <p role="alert" data-testid="search-error">{{ err | fig }}</p>
        }
      </form>
      <p>Found: <strong data-testid="search-count">{{ (count() ?? '') | fig }}</strong></p>
      <ol aria-label="Found people">
        @for (f of found(); track $index) {
          <li data-testid="search-line">{{ f.sheetId }} {{ f.name }} ({{ f.grade ?? '' }}) {{ f.dueTotal }}</li>
        }
      </ol>
    </section>
  `,
})
export class TreeSearch {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly text = signal('');
  protected readonly found = signal<readonly Found[]>([]);
  protected readonly count = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  // Each press is numbered, so only the latest one's answer shows.
  private press = 0;

  constructor() {
    // A newly loaded sheet starts with nothing found.
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.press++;
        this.error.set(null);
        this.found.set([]);
        this.count.set(null);
      });
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async search(e: Event): Promise<void> {
    e.preventDefault();
    this.error.set(null);
    this.found.set([]);
    this.count.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const press = ++this.press;
    try {
      const f = await firstValueFrom(
        this.http.get<Found[]>(`/api/sheets/${encodeURIComponent(sheet)}/people/search`, {
          params: { text: this.text() },
          context: new HttpContext().set(LIVE, true),
        }),
      );
      if (press === this.press) {
        this.found.set(f);
        this.count.set(f.length);
      }
    } catch {
      if (press === this.press) this.error.set('Could not search the tree.');
    }
  }
}
