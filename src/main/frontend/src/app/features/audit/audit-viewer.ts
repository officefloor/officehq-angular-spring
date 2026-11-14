import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The audit viewer: the active sheet's audit lines exactly as the file holds them, newest first;
// with a name in the filter, only the lines about that person, followed for a transferred person by
// the lines about them on the sheet they came from.
@Component({
  selector: 'app-audit-viewer',
  template: `
    <section aria-labelledby="audit-heading">
      <h2 id="audit-heading">Audit</h2>
      <form (submit)="show($event)">
        <label for="audit-filter">Person</label>
        <input
          id="audit-filter"
          data-testid="audit-filter"
          [value]="filter()"
          (input)="filter.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="audit-show">Show audit</button>
        @if (error(); as message) {
          <p role="alert" data-testid="audit-error">{{ message }}</p>
        }
      </form>
      <ol>
        @for (line of lines(); track $index) {
          <li data-testid="audit-line">{{ line }}</li>
        }
      </ol>
      <h3 id="activity-heading">Activity</h3>
      <button type="button" data-testid="activity-show" (click)="showActivity()">Show activity</button>
      @if (activityError(); as message) {
        <p role="alert" data-testid="activity-error">{{ message }}</p>
      }
      <ul aria-labelledby="activity-heading">
        @for (line of activity(); track $index) {
          <li data-testid="activity-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class AuditViewer {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  protected readonly filter = signal('');
  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  /** The sheet's activity as last shown: one "<user> <EVENT> <count>" line per user and event. */
  protected readonly activity = signal<string[]>([]);
  protected readonly activityError = signal<string | null>(null);
  /** Counts presses, so only the latest press's answer is shown. */
  private shows = 0;
  /** Counts activity presses, so only the latest press's answer is shown. */
  private activityShows = 0;

  constructor() {
    // A sheet (re-)load clears what an earlier press showed.
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.shows++;
      this.lines.set([]);
      this.activityShows++;
      this.activity.set([]);
      this.activityError.set(null);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Read the sheet's audit lines, filtered to the typed name when one is typed. */
  protected async show(event: Event): Promise<void> {
    event.preventDefault();
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const press = ++this.shows;
    this.error.set(null);
    this.lines.set([]);
    const name = this.filter().trim();
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/audit`, {
          params: name ? { name } : {},
        }),
      );
      if (press === this.shows) this.lines.set(lines);
    } catch {
      if (press === this.shows) this.error.set('The audit could not be read.');
    }
  }

  /** Read the sheet's activity: its audit lines counted per user and event. */
  protected async showActivity(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const press = ++this.activityShows;
    this.activityError.set(null);
    this.activity.set([]);
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/activity`),
      );
      if (press === this.activityShows) this.activity.set(lines);
    } catch {
      if (press === this.activityShows) this.activityError.set('The activity could not be read.');
    }
  }
}
