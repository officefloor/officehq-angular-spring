import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The policy versions, oldest first: one line "<version> <from> <key>=<value> <count>" each, the count being of the
// claims whose policy version (the one in force on their date) it is.
@Component({
  selector: 'app-policy-viewer',
  template: `
    <section aria-label="Policy versions">
      <h2>Policy versions</h2>
      <button type="button" data-testid="policy-show" (click)="show()">Show policy versions</button>
      @if (error(); as err) {
        <p role="alert" data-testid="policy-error">{{ err }}</p>
      }
      <ol aria-label="Policy versions">
        @for (line of lines(); track $index) {
          <li data-testid="policy-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class PolicyViewer {
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
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/claims/policy`),
      );
      if (press === this.press) this.lines.set(lines);
    } catch {
      if (press === this.press) this.error.set('Could not show the policy versions.');
    }
  }
}
