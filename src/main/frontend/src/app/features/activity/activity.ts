import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's activity: for every user and every event in the sheet's audit lines, one line
// `<user> <EVENT> <count>`, sorted by user then event. It reads the audit file; it records nothing.
@Component({
  selector: 'app-activity',
  template: `
    <section aria-label="Activity">
      <h2>Activity</h2>
      <button type="button" data-testid="activity-show" (click)="show()">Show activity</button>
      @if (error()) {
        <p role="alert" data-testid="activity-error">{{ error() }}</p>
      }
      <ul>
        @for (line of lines(); track $index) {
          <li data-testid="activity-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class Activity {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal('');
  /** Bumped on every press, so only the latest press's answer is shown. */
  private shows = 0;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.shows++;
      this.lines.set([]);
      this.error.set('');
    });
  }

  protected async show(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const show = ++this.shows;
    this.lines.set([]);
    this.error.set('');
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/audit/activity`),
      );
      if (show === this.shows) this.lines.set(lines);
    } catch {
      if (show === this.shows) this.error.set('Could not read the activity.');
    }
  }
}
