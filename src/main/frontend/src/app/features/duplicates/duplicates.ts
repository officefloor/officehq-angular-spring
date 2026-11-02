import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's duplicate claims: for each person, date and amount with two or more claims (VOID and
// DELETED left out), "<name> <date> <amount> x<count>", people in list order, then by date and amount.
@Component({
  selector: 'app-duplicates',
  template: `
    <section aria-labelledby="dups-heading">
      <h2 id="dups-heading">Duplicate claims</h2>
      <button type="button" data-testid="dups-show" (click)="show()">Show duplicates</button>
      @if (error()) {
        <p role="alert" class="error" data-testid="dups-error">{{ error() }}</p>
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
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal('');
  /** Counts the presses of show, so only the answer to the latest is shown. */
  private request = 0;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.request++;
      this.lines.set([]);
      this.error.set('');
    });
  }

  protected async show(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const request = ++this.request;
    this.lines.set([]);
    this.error.set('');
    try {
      const list = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/duplicate-claims`, {
          headers: { 'X-User': this.session.user() ?? '' },
        }),
      );
      if (request === this.request) this.lines.set(list);
    } catch {
      if (request === this.request) this.error.set('Could not read the duplicate claims.');
    }
  }
}
