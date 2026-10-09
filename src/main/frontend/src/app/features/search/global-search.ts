import { Component, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { SearchResults, SearchService } from './search.service';

// One search box, shown in the shell on every page, that looks across both clients and projects by
// name and lists the matches grouped by kind; each match opens its detail page.
@Component({
  selector: 'app-global-search',
  imports: [RouterLink],
  template: `
    <div role="search">
      <label for="global-search">Search clients and projects</label>
      <input
        id="global-search"
        type="search"
        autocomplete="off"
        data-testid="global-search"
        [value]="query()"
        (input)="onSearch($event)"
      />
    </div>
    @if (results(); as r) {
      <div data-testid="search-results" aria-live="polite">
        <section data-testid="search-clients" aria-labelledby="search-clients-heading">
          <h2 id="search-clients-heading">Clients</h2>
          @if (r.clients.length === 0) {
            <p data-testid="search-clients-empty">No clients match.</p>
          } @else {
            <ul>
              @for (c of r.clients; track c.id) {
                <li [attr.data-testid]="'client-row-' + c.id">
                  <a [routerLink]="['/clients', c.id]" data-testid="client-name">{{ c.name }}</a>
                </li>
              }
            </ul>
          }
        </section>
        <section data-testid="search-projects" aria-labelledby="search-projects-heading">
          <h2 id="search-projects-heading">Projects</h2>
          @if (r.projects.length === 0) {
            <p data-testid="search-projects-empty">No projects match.</p>
          } @else {
            <ul>
              @for (p of r.projects; track p.id) {
                <li [attr.data-testid]="'project-row-' + p.id">
                  <a [routerLink]="['/projects', p.id]" data-testid="project-name">{{ p.name }}</a>
                  <span data-testid="project-client-name">({{ p.clientName }})</span>
                </li>
              }
            </ul>
          }
        </section>
      </div>
    }
  `,
})
export class GlobalSearch {
  private readonly service = inject(SearchService);

  protected readonly query = signal('');
  // The matches for the current query; null while the query is blank.
  protected readonly results = toSignal(
    toObservable(this.query).pipe(
      map((q) => q.trim()),
      debounceTime(200),
      distinctUntilChanged(),
      switchMap((q) =>
        q
          ? this.service.search(q).pipe(catchError(() => of<SearchResults>({ clients: [], projects: [] })))
          : of(null),
      ),
    ),
    { initialValue: null },
  );

  protected onSearch(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }
}
