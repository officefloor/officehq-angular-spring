import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The audit viewer: the active sheet's audit lines exactly as the file holds them, newest first. With a name in the
// filter, only the lines about that person (name= or person= that name, case and surrounding spaces ignored), then,
// for a person transferred to this sheet, the lines about them from the sheet they came from.
@Component({
  selector: 'app-audit-viewer',
  template: `
    <section aria-label="Audit">
      <h2>Audit</h2>
      <form (submit)="show($event)" aria-label="Show the audit">
        <label for="audit-filter">Person</label>
        <input
          id="audit-filter"
          data-testid="audit-filter"
          [value]="filter()"
          (input)="filter.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="audit-show">Show audit</button>
        @if (error(); as err) {
          <p role="alert" data-testid="audit-error">{{ err }}</p>
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

  protected readonly lines = signal<readonly string[]>([]);
  protected readonly filter = signal('');
  protected readonly error = signal<string | null>(null);
  // Each press is numbered, so only the latest one's answer shows.
  private press = 0;

  constructor() {
    // A newly loaded sheet starts with the list empty.
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

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async show(e: Event): Promise<void> {
    e.preventDefault();
    this.error.set(null);
    this.lines.set([]);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const press = ++this.press;
    try {
      const l = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/audit`, {
          params: { name: this.filter() },
        }),
      );
      if (press === this.press) this.lines.set(l);
    } catch {
      if (press === this.press) this.error.set('Could not read the audit.');
    }
  }
}
