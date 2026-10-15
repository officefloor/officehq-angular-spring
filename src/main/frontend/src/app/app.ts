import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Route, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { Report } from './features/report/report';

// Minimal base shell. It renders the nav bar and the routed page. The nav comes from the ROUTER'S
// OWN CONFIG — every route carrying `data.section` becomes a link — so the shell holds no list of
// pages and is not edited when one is added. Every observable element carries a stable data-testid.
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, Report],
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
      <main data-testid="app-home">
        <router-outlet />
      </main>
      @if (!onReport()) {
        <aside data-testid="app-report">
          <app-report />
        </aside>
      }
    </div>
  `,
})
export class App {
  private readonly router = inject(Router);
  // The report shows beside every page except its own screen, where it is the page.
  protected readonly onReport = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects.split(/[?#]/)[0] === '/report'),
    ),
    { initialValue: false },
  );
  protected readonly nav: Route[] = this.router
    .config.filter((r) => !!r.data?.['section'])
    .sort(
      (a, b) =>
        ((a.data?.['order'] as number) ?? 0) - ((b.data?.['order'] as number) ?? 0) ||
        String(a.data?.['section']).localeCompare(String(b.data?.['section'])),
    );
}
