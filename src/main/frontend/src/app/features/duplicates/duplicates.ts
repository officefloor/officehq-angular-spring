import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The duplicate claims: for each person, date and amount with two or more claims (VOID and DELETED left out), one
// line "<name> <date> <amount> x<count>"; people in list order, then by date, then by amount.
@Component({
  selector: 'app-duplicates',
  template: `
    <section aria-label="Duplicate claims">
      <h2>Duplicate claims</h2>
      <button type="button" data-testid="dups-show" (click)="show()">Show duplicates</button>
      @if (error(); as err) {
        <p role="alert" data-testid="dups-error">{{ err }}</p>
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

  protected readonly lines = signal<readonly string[]>([]);
  protected readonly error = signal<string | null>(null);
  // Each press is numbered, so only the latest one's answer shows.
  private press = 0;

  constructor() {
    // A newly loaded sheet starts with nothing shown.
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.press++;
        this.error.set(null);
        this.lines.set([]);
      });
    });
  }

  protected async show(): Promise<void> {
    this.error.set(null);
    this.lines.set([]);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const press = ++this.press;
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/claims/duplicates`),
      );
      if (press === this.press) this.lines.set(lines);
    } catch {
      if (press === this.press) this.error.set('Could not show the duplicate claims.');
    }
  }
}
