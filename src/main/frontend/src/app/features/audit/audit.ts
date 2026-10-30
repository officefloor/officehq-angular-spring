import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The audit viewer: the sheet's audit lines exactly as the audit file holds them, newest first. With a name in
// the filter, only the lines about that person (name= or person= that name, case and surrounding spaces ignored).
// It reads the file; it records nothing.
@Component({
  selector: 'app-audit',
  template: `
    <section aria-label="Audit">
      <h2>Audit</h2>
      <form (submit)="show($event)" aria-label="Show audit">
        <label for="audit-filter">Person</label>
        <input
          id="audit-filter"
          data-testid="audit-filter"
          [value]="filter()"
          (input)="filter.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="audit-show">Show</button>
        @if (error()) {
          <p role="alert" data-testid="audit-error">{{ error() }}</p>
        }
      </form>
      <ol>
        @for (line of lines(); track $index) {
          <li data-testid="audit-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class Audit {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly filter = signal('');
  protected readonly lines = signal<string[]>([]);
  protected readonly error = signal('');
  /** Bumped on every press, so only the latest press's answer is shown. */
  private shows = 0;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.shows++;
      this.lines.set([]);
      this.error.set('');
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async show(e: Event): Promise<void> {
    e.preventDefault();
    const sheet = this.session.sheet();
    if (!sheet) return;
    const show = ++this.shows;
    this.lines.set([]);
    this.error.set('');
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/audit`, { params: { name: this.filter() } }),
      );
      if (show === this.shows) this.lines.set(lines);
    } catch {
      if (show === this.shows) this.error.set('Could not read the audit.');
    }
  }
}
