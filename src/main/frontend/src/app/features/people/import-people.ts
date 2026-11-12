import { Component, inject, signal } from '@angular/core';
import { People } from '../../core/people';

// An import box of name,grade,terms,contact lines, each a person added as the person form would; reports
// how many were added, or why the import was refused (then no one is added).
@Component({
  selector: 'app-import-people',
  template: `
    <form (submit)="import($event)" aria-label="Import people">
      <label for="import-people">Import people (one per line: name,grade,terms,contact; terms and contact may be left out)</label>
      <textarea
        id="import-people"
        data-testid="import-people"
        rows="4"
        [value]="text()"
        (input)="text.set(value($event)); error.set(null)"
      ></textarea>
      <button type="submit" data-testid="import-submit" [disabled]="running()">Import people</button>
      @if (added() !== null) {
        <p>Added <span data-testid="import-added">{{ added() }}</span></p>
      }
      @if (error(); as err) {
        <p role="alert" data-testid="import-error">{{ err }}</p>
      }
    </form>
  `,
})
export class ImportPeople {
  private readonly people = inject(People);
  protected readonly text = signal('');
  protected readonly running = signal(false);
  protected readonly added = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);

  protected async import(e: Event): Promise<void> {
    e.preventDefault();
    if (this.running()) return; // a second submit before the first has answered
    this.added.set(null);
    this.error.set(null);
    this.running.set(true);
    try {
      const done = await this.people.importPeople(this.text());
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
