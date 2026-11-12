import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's activity: for every user and every event in the sheet's audit records, one line
// "<user> <EVENT> <count>", sorted by user then event. It reads the audit file; it records nothing.
@Component({
  selector: 'app-activity',
  template: `
    <section aria-labelledby="activity-heading">
      <h2 id="activity-heading">Activity</h2>
      <button type="button" data-testid="activity-show" (click)="show()">Show activity</button>
      @if (error(); as message) {
        <p role="alert" data-testid="activity-error">{{ message }}</p>
      }
      <ul aria-label="Activity lines">
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
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.reads++;
        this.lines.set([]);
        this.error.set(null);
      });
    });
  }

  protected async show(): Promise<void> {
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const read = ++this.reads;
    this.lines.set([]);
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/activity`, this.session.headers()),
      );
      // A later show wins over a slower earlier one.
      if (read === this.reads && this.session.sheet() === sheet) this.lines.set(lines);
    } catch {
      if (read === this.reads) this.error.set('The activity could not be read.');
    }
  }
}
