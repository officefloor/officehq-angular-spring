import { Component, effect, inject, signal, untracked } from '@angular/core';
import { AuditApi } from '../../core/audit';
import { Session } from '../../core/session';

// The audit viewer: the sheet's audit lines as the file holds them, newest first, or with a name in
// the filter only the lines about that person. It reads the file; it records nothing.
@Component({
  selector: 'app-audit-viewer',
  template: `
    <section aria-labelledby="audit-heading">
      <h2 id="audit-heading">Audit</h2>
      <form (submit)="show($event)" aria-label="Show audit lines">
        <label for="audit-filter">Person</label>
        <input
          id="audit-filter"
          data-testid="audit-filter"
          [value]="filter()"
          (input)="filter.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="audit-show">Show</button>
        @if (error(); as message) {
          <p role="alert" data-testid="audit-error">{{ message }}</p>
        }
      </form>
      <ol aria-label="Audit lines">
        @for (line of lines(); track $index) {
          <li data-testid="audit-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class AuditViewer {
  private readonly session = inject(Session);
  private readonly api = inject(AuditApi);

  protected readonly filter = signal('');
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

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async show(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const show = ++this.shows;
    this.lines.set([]);
    try {
      const lines = await this.api.lines(sheet, this.filter());
      if (show === this.shows) this.lines.set(lines);
    } catch {
      if (show === this.shows) this.error.set('The audit lines could not be read.');
    }
  }
}
