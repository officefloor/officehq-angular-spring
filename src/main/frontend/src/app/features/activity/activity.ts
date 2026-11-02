import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's activity: for every user and event in its audit lines, "<user> <EVENT> <count>",
// sorted by user then event.
@Component({
  selector: 'app-activity',
  template: `
    <section aria-labelledby="activity-heading">
      <h2 id="activity-heading">Activity</h2>
      <button type="button" data-testid="activity-show" (click)="show()">Show activity</button>
      @if (error()) {
        <p role="alert" class="error" data-testid="activity-error">{{ error() }}</p>
      }
      <ol aria-label="Activity lines">
        @for (line of lines(); track $index) {
          <li data-testid="activity-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class Activity {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal('');
  /** Counts the presses of show, so only the answer to the latest is shown. */
  private request = 0;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.request++;
      this.lines.set([]);
      this.error.set('');
    });
  }

  protected async show(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const request = ++this.request;
    this.lines.set([]);
    this.error.set('');
    try {
      const list = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/activity`, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (request === this.request) this.lines.set(list);
    } catch {
      if (request === this.request) this.error.set('Could not read the activity.');
    }
  }
}
