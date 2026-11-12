import { Component, computed, inject, signal } from '@angular/core';
import { AuditLog } from '../../core/audit';
import { Session } from '../../core/session';

// The audit viewer: with the filter blank it lists every audit line of the sheet, newest first, exactly as
// the file holds it; with a name, only the lines about that person (name= or person=). It reads the file;
// it records nothing.
@Component({
  selector: 'app-audit',
  template: `
    <section aria-label="Audit" data-testid="audit">
      <h2>Audit</h2>
      <form (submit)="show($event)">
        <label for="audit-filter">Person (blank for all)</label>
        <input
          id="audit-filter"
          data-testid="audit-filter"
          [value]="filter()"
          (input)="filter.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="audit-show">Show</button>
        @if (error(); as err) {
          <p role="alert" data-testid="audit-error">{{ err }}</p>
        }
      </form>
      <ol data-testid="audit-list" aria-label="Audit lines">
        @for (line of lines(); track $index) {
          <li data-testid="audit-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class AuditPanel {
  private readonly audit = inject(AuditLog);
  private readonly session = inject(Session);
  protected readonly filter = signal('');
  protected readonly error = signal<string | null>(null);
  private readonly shown = signal<{ sheet: string | null; lines: string[] }>({ sheet: null, lines: [] });
  /** The lines last shown, while they are of the sheet loaded now. */
  protected readonly lines = computed(() => {
    const s = this.shown();
    return s.sheet === this.session.sheetId() ? s.lines : [];
  });
  private shows = 0;

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async show(e: Event): Promise<void> {
    e.preventDefault();
    const show = ++this.shows;
    const sheet = this.session.sheetId();
    this.error.set(null);
    this.shown.set({ sheet: null, lines: [] });
    const res = await this.audit.lines(this.filter());
    if (show !== this.shows) return; // a later press has taken over
    if ('error' in res) this.error.set(res.error);
    else this.shown.set({ sheet, lines: res.lines });
  }
}
