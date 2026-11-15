import { Component, inject, signal } from '@angular/core';
import { Session, otherError } from '../../core/session';
import { AuditApi } from './audit-api';

// The sheet's activity: for every user and every event in the sheet's audit records, one line
// `<user> <EVENT> <count>`, sorted by user then event.
@Component({
  selector: 'app-activity',
  template: `
    <section aria-labelledby="activity-heading">
      <h2 id="activity-heading">Activity</h2>
      <button type="button" data-testid="activity-show" (click)="show()">Show activity</button>
      @if (error(); as err) {
        <p role="alert" data-testid="activity-error">{{ err }}</p>
      }
      <ul data-testid="activity-lines">
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
  protected readonly error = signal<string | null>(null);

  protected async show(): Promise<void> {
    this.error.set(null);
    this.lines.set([]);
    const id = this.session.sheetId();
    if (!id) {
      this.error.set('Load a sheet first.');
      return;
    }
    try {
      this.lines.set(await this.api.activity(id));
    } catch (e) {
      this.error.set(otherError(e));
    }
  }
}
