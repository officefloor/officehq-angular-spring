import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's policy versions, shown on request: one line per version, oldest first, as
// <version> <from-date, or start> <key>=<value> <how many claims have it as their policy version>.
// Showing again re-reads them; the old lines are gone while they load.
@Component({
  selector: 'app-policy-viewer',
  template: `
    <section aria-labelledby="policy-viewer-heading">
      <h2 id="policy-viewer-heading">Policy versions</h2>
      <button type="button" data-testid="policy-show" (click)="show()">Show policy versions</button>
      @if (error(); as err) {
        <p role="alert" data-testid="policy-error">{{ err }}</p>
      }
      <ol aria-label="Policy versions">
        @for (line of lines(); track $index) {
          <li data-testid="policy-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class PolicyViewer {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.lines.set([]);
      this.error.set(null);
    });
  }

  protected async show(): Promise<void> {
    const sheet = this.session.sheet();
    this.lines.set([]);
    this.error.set(null);
    try {
      const lines = await firstValueFrom(
        this.http.post<string[]>('/api/settings/policy', { sheet, user: this.session.user() }),
      );
      if (this.session.sheet() === sheet) this.lines.set(lines);
    } catch {
      if (this.session.sheet() === sheet) this.error.set('The policy versions could not be read.');
    }
  }
}
