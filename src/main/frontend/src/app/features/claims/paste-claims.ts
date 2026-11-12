import { Component, inject, signal } from '@angular/core';
import { Claims } from '../../core/claims';

// A paste box of name,amount,date,note lines, each a DRAFT claim for the named person; reports how many
// were added, or why the paste was refused (then nothing is added).
@Component({
  selector: 'app-paste-claims',
  template: `
    <form (submit)="paste($event)" aria-label="Paste claims">
      <label for="paste-claims">Paste claims (one per line: name,amount,date,note, the note ending in " #reference" to give one)</label>
      <textarea
        id="paste-claims"
        data-testid="paste-claims"
        rows="4"
        [value]="text()"
        (input)="text.set(value($event)); error.set(null)"
      ></textarea>
      <button type="submit" data-testid="paste-submit" [disabled]="running()">Add pasted claims</button>
      @if (added() !== null) {
        <p>Added <span data-testid="paste-added">{{ added() }}</span></p>
      }
      @if (error(); as err) {
        <p role="alert" data-testid="paste-error">{{ err }}</p>
      }
    </form>
  `,
})
export class PasteClaims {
  private readonly claims = inject(Claims);
  protected readonly text = signal('');
  protected readonly running = signal(false);
  protected readonly added = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  protected async paste(e: Event): Promise<void> {
    e.preventDefault();
    if (this.running()) return; // a second submit before the first has answered
    this.added.set(null);
    this.error.set(null);
    this.running.set(true);
    try {
      const done = await this.claims.paste(this.text());
      if (typeof done === 'string') this.error.set(done);
      else {
        this.added.set(done);
        this.text.set('');
      }
    } finally {
      this.running.set(false);
    }
  }

  protected value(e: Event): string {
    return (e.target as HTMLTextAreaElement).value;
  }
}
