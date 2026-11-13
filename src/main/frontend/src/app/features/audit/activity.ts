import { Component, computed, inject, signal } from '@angular/core';
import { AuditLog } from '../../core/audit';
import { Session } from '../../core/session';

// The sheet's activity: for every user and every event in the sheet's audit lines, one line
// "<user> <EVENT> <count>", sorted by user then event. It reads the file; it records nothing.
@Component({
  selector: 'app-activity',
  template: `
    <section aria-label="Activity" data-testid="activity">
      <h2>Activity</h2>
      <button type="button" data-testid="activity-show" (click)="show()">Show activity</button>
      @if (error(); as err) {
        <p role="alert" data-testid="activity-error">{{ err }}</p>
      }
      <ul data-testid="activity-list" aria-label="Activity lines">
        @for (line of lines(); track $index) {
          <li data-testid="activity-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class ActivityPanel {
  private readonly audit = inject(AuditLog);
  private readonly session = inject(Session);
  protected readonly error = signal<string | null>(null);
  private readonly shown = signal<{ sheet: string | null; lines: string[] }>({ sheet: null, lines: [] });
  /** The lines last shown, while they are of the sheet loaded now. */
  protected readonly lines = computed(() => {
    const s = this.shown();
    return s.sheet === this.session.sheetId() ? s.lines : [];
  });
  private shows = 0;

  protected async show(): Promise<void> {
    const show = ++this.shows;
    const sheet = this.session.sheetId();
    this.error.set(null);
    this.shown.set({ sheet: null, lines: [] });
    const res = await this.audit.activity();
    if (show !== this.shows) return; // a later press has taken over
    if ('error' in res) this.error.set(res.error);
    else this.shown.set({ sheet, lines: res.lines });
  }
}
