import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { AuditService } from './audit.service';

// The sheet's activity: for every user and every event in the sheet's audit lines, one line
// `<user> <EVENT> <count>`, sorted by user then event. It reads the file; it records nothing.
@Component({
  selector: 'app-activity',
  template: `
    <section aria-labelledby="activity-heading">
      <h2 id="activity-heading">Activity</h2>
      <button type="button" data-testid="activity-show" (click)="show()">Show activity</button>
      @if (error(); as message) {
        <p role="alert" class="error" data-testid="activity-error">{{ message }}</p>
      }
      @if (lines().length > 0) {
        <ol data-testid="activity-list">
          @for (line of lines(); track $index) {
            <li data-testid="activity-line">{{ line }}</li>
          }
        </ol>
      }
    </section>
  `,
  styles: `
    ol {
      list-style: none;
      padding: 0;
      font-family: monospace;
      overflow-wrap: anywhere;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class Activity {
  private readonly session = inject(Session);
  private readonly service = inject(AuditService);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  private asked = 0;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.asked++;
      this.lines.set([]);
      this.error.set(null);
    });
  }

  protected async show(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ask = ++this.asked;
    this.lines.set([]);
    this.error.set(null);
    try {
      const lines = await this.service.activity(sheet);
      if (ask === this.asked) this.lines.set(lines);
    } catch {
      if (ask === this.asked) this.error.set('The activity could not be read.');
    }
  }
}
