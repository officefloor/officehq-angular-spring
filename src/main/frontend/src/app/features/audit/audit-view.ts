import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The audit viewer: the sheet's audit lines exactly as the audit file holds them, newest first.
// With a name in the filter, only the lines about that person (name= or person= that name,
// compared ignoring case and surrounding spaces). It reads the file; it records nothing itself.
@Component({
  selector: 'app-audit-view',
  template: `
    <section aria-labelledby="audit-heading">
      <h2 id="audit-heading">Audit</h2>
      <form aria-label="Audit filter" (submit)="show($event)">
        <label for="audit-filter">Person</label>
        <input
          id="audit-filter"
          data-testid="audit-filter"
          [value]="filterInput()"
          (input)="filterInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="audit-show">Show</button>
        @if (error(); as err) {
          <p role="alert" data-testid="audit-error">{{ err }}</p>
        }
      </form>
      <ol data-testid="audit-lines" aria-label="Audit lines">
        @for (line of lines(); track $index) {
          <li data-testid="audit-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class AuditView {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly filterInput = signal('');
  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  /** Counts presses of show, so only the answer to the latest one is shown. */
  private shown = 0;

  constructor() {
    // A (re-)loaded sheet starts with nothing shown.
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.shown++;
      this.lines.set([]);
      this.error.set(null);
    });
  }

  protected async show(event: Event): Promise<void> {
    event.preventDefault();
    const press = ++this.shown;
    this.lines.set([]);
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      const res = await firstValueFrom(
        this.http.post<{ lines: string[] }>('/api/audit/list', { sheet, name: this.filterInput() }),
      );
      if (press === this.shown) this.lines.set(res.lines);
    } catch {
      if (press === this.shown) this.error.set('Could not read the audit lines.');
    }
  }
}
