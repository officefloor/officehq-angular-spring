import { Component, effect, inject, signal } from '@angular/core';
import { AuditLog } from '../../core/audit-log';
import { Session } from '../../core/session';

// The sheet's activity: how many of each event every user has in the sheet's audit lines.
// It reads what the audit file holds; it records nothing of its own.
@Component({
  selector: 'app-activity-panel',
  template: `
    <section aria-labelledby="activity-heading">
      <h3 id="activity-heading">Activity</h3>
      <button type="button" data-testid="activity-show" (click)="show()">Show activity</button>
      @if (error()) {
        <p role="alert" data-testid="activity-error">{{ error() }}</p>
      }
      <ul data-testid="activity-lines">
        @for (line of lines(); track $index) {
          <li data-testid="activity-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class ActivityPanel {
  private readonly api = inject(AuditLog);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal('');
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.loads();
      this.session.sheetId();
      this.reads++;
      this.lines.set([]);
    });
  }

  protected async show(): Promise<void> {
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const read = ++this.reads;
    this.error.set('');
    this.lines.set([]);
    try {
      const lines = await this.api.activity(sheetId);
      if (read === this.reads) this.lines.set(lines);
    } catch {
      if (read === this.reads) this.error.set('The activity could not be read.');
    }
  }
}
