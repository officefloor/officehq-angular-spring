import { Component, inject, signal } from '@angular/core';
import { People } from '../../core/people';

// An import box of name,grade,terms,contact lines, each updating the person it names (by name or alias) or
// adding one, as the person form would; reports how many were added and updated, or why the import was
// refused (then no one is changed).
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
      @if (done(); as d) {
        <p>
          Added <span data-testid="import-added">{{ d.added }}</span>, updated
          <span data-testid="import-updated">{{ d.updated }}</span>
        </p>
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
  protected readonly done = signal<{ added: number; updated: number } | null>(null);
  protected readonly error = signal<string | null>(null);

  protected async import(e: Event): Promise<void> {
    e.preventDefault();
    if (this.running()) return; // a second submit before the first has answered
    this.done.set(null);
    this.error.set(null);
    this.running.set(true);
    try {
      const done = await this.people.importPeople(this.text());
      if (typeof done === 'string') this.error.set(done);
      else {
        this.done.set(done);
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
