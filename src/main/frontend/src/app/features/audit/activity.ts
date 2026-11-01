import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { AuditLog } from '../../core/audit';
import { Sheets } from '../../core/sheet';

// The sheet's activity: for every user and every event in its audit lines, how many there are, by user then event.
@Component({
  selector: 'app-activity',
  template: `
    <section aria-labelledby="activity-heading">
      <h3 id="activity-heading">Activity</h3>
      <button type="button" data-testid="activity-show" [disabled]="loading()" (click)="show()">Show</button>
      @if (error(); as err) {
        <p role="alert" data-testid="activity-error">{{ err }}</p>
      }
      <ul aria-label="Activity by user and event">
        @for (line of lines(); track $index) {
          <li data-testid="activity-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class ActivityPanel {
  private readonly audit = inject(AuditLog);
  private readonly sheets = inject(Sheets);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly loading = signal(false);

  constructor() {
    // Another sheet's activity is not left showing once a sheet is (re)loaded.
    toObservable(this.sheets.loaded)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        this.lines.set([]);
        this.error.set(null);
      });
  }

  protected show(): void {
    if (this.loading()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.loading.set(true);
    this.lines.set([]);
    this.audit.activity(sheet.id).subscribe({
      next: (lines) => {
        this.loading.set(false);
        this.lines.set(lines);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Could not read the activity.');
      },
    });
  }
}
