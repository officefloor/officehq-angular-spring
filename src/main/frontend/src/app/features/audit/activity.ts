import { Component, effect, inject, signal, untracked } from '@angular/core';
import { AuditApi } from '../../core/audit';
import { Session } from '../../core/session';

// The sheet's activity: for every user and every event in the sheet's audit lines, one line
// `<user> <EVENT> <count>`, sorted by user then event. It reads the file; it records nothing.
@Component({
  selector: 'app-activity',
  template: `
    <section aria-labelledby="activity-heading">
      <h2 id="activity-heading">Activity</h2>
      <button type="button" data-testid="activity-show" (click)="show()">Show activity</button>
      @if (error(); as message) {
        <p role="alert" data-testid="activity-error">{{ message }}</p>
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
  private readonly session = inject(Session);
  private readonly api = inject(AuditApi);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  /** Counts the shows asked for, so only the latest one's answer is shown. */
  private shows = 0;

  constructor() {
    // A sheet load clears what was shown for the sheet before.
    effect(() => {
      this.session.sheet();
      this.session.loads();
      untracked(() => {
        this.shows++;
        this.lines.set([]);
        this.error.set(null);
      });
    });
  }

  protected async show(): Promise<void> {
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const show = ++this.shows;
    this.lines.set([]);
    try {
      const lines = await this.api.activity(sheet);
      if (show === this.shows) this.lines.set(lines);
    } catch {
      if (show === this.shows) this.error.set('The activity could not be read.');
    }
  }
}
