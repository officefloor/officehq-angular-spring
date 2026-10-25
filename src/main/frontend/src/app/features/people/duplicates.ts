import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { ClaimsService } from './claims.service';

// The sheet's duplicate claims: for each person, date and amount with two or more claims (VOID and
// DELETED ones left out), one line `<name> <date> <amount> x<count>`, people in list order, then by
// date, then by amount. It reads the claims; it records nothing.
@Component({
  selector: 'app-duplicates',
  template: `
    <section aria-labelledby="dups-heading">
      <h2 id="dups-heading">Duplicate claims</h2>
      <button type="button" data-testid="dups-show" (click)="show()">Show duplicates</button>
      @if (error(); as message) {
        <p role="alert" class="error" data-testid="dups-error">{{ message }}</p>
      }
      @if (lines().length > 0) {
        <ol data-testid="dups-list">
          @for (line of lines(); track $index) {
            <li data-testid="dup-line">{{ line }}</li>
          }
        </ol>
      }
    </section>
  `,
  styles: `
    ol {
      list-style: none;
      padding: 0;
      font-family: monospace;
      overflow-wrap: anywhere;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class Duplicates {
  private readonly session = inject(Session);
  private readonly service = inject(ClaimsService);

  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  private asked = 0;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.asked++;
      this.lines.set([]);
      this.error.set(null);
    });
  }

  protected async show(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const ask = ++this.asked;
    this.lines.set([]);
    this.error.set(null);
    try {
      const lines = await this.service.duplicates(sheet);
      if (ask === this.asked) this.lines.set(lines);
    } catch {
      if (ask === this.asked) this.error.set('The duplicate claims could not be read.');
    }
  }
}
