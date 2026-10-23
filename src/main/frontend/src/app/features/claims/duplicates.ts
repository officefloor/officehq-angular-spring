import { Component, effect, inject, signal, untracked } from '@angular/core';
import { ClaimsApi } from '../../core/claims';
import { Session } from '../../core/session';

// The sheet's duplicate claims: for each person, date and amount with two or more claims (VOID and
// DELETED left out), one line `<name> <date> <amount> x<count>`, people in list order, then by date,
// then by amount. It reads the store; it records nothing.
@Component({
  selector: 'app-duplicates',
  template: `
    <section aria-labelledby="dups-heading">
      <h2 id="dups-heading">Duplicate claims</h2>
      <button type="button" data-testid="dups-show" (click)="show()">Show duplicates</button>
      @if (error(); as message) {
        <p role="alert" data-testid="dups-error">{{ message }}</p>
      }
      <ol aria-label="Duplicate claims">
        @for (line of lines(); track $index) {
          <li data-testid="dup-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class Duplicates {
  private readonly session = inject(Session);
  private readonly api = inject(ClaimsApi);

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
      const lines = await this.api.duplicates(sheet);
      if (show === this.shows) this.lines.set(lines);
    } catch {
      if (show === this.shows) this.error.set('The duplicate claims could not be read.');
    }
  }
}
