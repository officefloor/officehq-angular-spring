import { Component, inject } from '@angular/core';
import { Route, Router, RouterLink, RouterOutlet } from '@angular/router';
import { GlobalSearch } from './features/search/global-search';

// Minimal base shell. It renders the nav bar and the routed page. The nav comes from the ROUTER'S
// OWN CONFIG — every route carrying `data.section` becomes a link — so the shell holds no list of
// pages and is not edited when one is added. Every observable element carries a stable data-testid.
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, GlobalSearch],
  template: `
    <div data-testid="app-root">
      <nav data-testid="app-nav">
        @for (r of nav; track r.path) {
          <a
            [routerLink]="'/' + r.path"
            [attr.data-testid]="'nav-' + r.data!['section']"
            >{{ r.data!['label'] }}</a
          >
        }
      </nav>
      <app-global-search />
      <main data-testid="app-home">
        <router-outlet />
      </main>
    </div>
  `,
})
export class App {
  protected readonly nav: Route[] = inject(Router)
    .config.filter((r) => !!r.data?.['section'])
    .sort(
      (a, b) =>
        ((a.data?.['order'] as number) ?? 0) - ((b.data?.['order'] as number) ?? 0) ||
        String(a.data?.['section']).localeCompare(String(b.data?.['section'])),
    );
}
