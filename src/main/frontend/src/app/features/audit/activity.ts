import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { AuditApi } from './audit-api';

// The sheet's activity: for every user and every event in its audit records, one line
// "<user> <EVENT> <count>", sorted by user then event.
@Component({
  selector: 'app-activity',
  template: `
    <section aria-labelledby="activity-heading">
      <h2 id="activity-heading">Activity</h2>
      <button type="button" data-testid="activity-show" [disabled]="loading()" (click)="show()">Show activity</button>
      @if (error()) {
        <p role="alert" data-testid="activity-error">{{ error() }}</p>
      }
      <ul data-testid="activity-lines" aria-label="Activity lines">
        @for (line of lines(); track $index) {
          <li data-testid="activity-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class Activity {
  private readonly session = inject(Session);
  private readonly api = inject(AuditApi);

  protected readonly lines = signal<string[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  private request = 0;

  constructor() {
    // Each load of a sheet starts the activity afresh.
    effect(() => {
      this.session.loaded();
      this.request++;
      this.loading.set(false);
      this.error.set('');
      this.lines.set([]);
    });
  }

  protected async show(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const request = ++this.request;
    this.error.set('');
    this.lines.set([]);
    this.loading.set(true);
    try {
      const lines = await this.api.activity(sheet.id);
      if (request === this.request) this.lines.set(lines);
    } catch {
      if (request === this.request) this.error.set('The activity could not be read.');
    } finally {
      if (request === this.request) this.loading.set(false);
    }
  }
}
