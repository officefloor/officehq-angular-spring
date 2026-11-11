import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The audit viewer: the sheet's audit lines exactly as the audit file holds them, newest first. A name in the
// filter lists only the lines about that person. It reads the file; it records nothing.
@Component({
  selector: 'app-audit-viewer',
  template: `
    <section aria-labelledby="audit-heading">
      <h2 id="audit-heading">Audit</h2>
      <form (submit)="show($event)" aria-labelledby="audit-heading">
        <label for="audit-filter">Person name (blank for every line)</label>
        <input
          id="audit-filter"
          data-testid="audit-filter"
          [value]="filter()"
          (input)="filter.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="audit-show">Show audit</button>
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
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly filter = signal('');
  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.reads++;
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
    const read = ++this.reads;
    this.lines.set([]);
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/audit`, {
          ...this.session.headers(),
          params: { name: this.filter() },
        }),
      );
      // A later show wins over a slower earlier one.
      if (read === this.reads && this.session.sheet() === sheet) this.lines.set(lines);
    } catch {
      if (read === this.reads) this.error.set('The audit could not be read.');
    }
  }
}
