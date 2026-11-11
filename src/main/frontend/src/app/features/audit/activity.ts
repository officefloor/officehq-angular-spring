import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's activity: for every user and every event in the sheet's audit lines, one line
// "<user> <EVENT> <count>", sorted by user then event. It reads the file; it records nothing itself.
@Component({
  selector: 'app-activity',
  template: `
    <section aria-labelledby="activity-heading">
      <h2 id="activity-heading">Activity</h2>
      <button type="button" data-testid="activity-show" (click)="show()">Show activity</button>
      @if (error(); as err) {
        <p role="alert" data-testid="activity-error">{{ err }}</p>
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
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  /** Counts presses of show, so only the answer to the latest one is shown. */
  private shown = 0;

  constructor() {
    // A (re-)loaded sheet starts with nothing shown.
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.shown++;
      this.lines.set([]);
      this.error.set(null);
    });
  }

  protected async show(): Promise<void> {
    const press = ++this.shown;
    this.lines.set([]);
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      const res = await firstValueFrom(
        this.http.post<{ lines: string[] }>('/api/audit/activity', { sheet }),
      );
      if (press === this.shown) this.lines.set(res.lines);
    } catch {
      if (press === this.shown) this.error.set('Could not read the activity.');
    }
  }
}
