import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { FigurePipe } from '../../core/locale';
import { Session } from '../../core/session';

// Duplicate claims: for each person, date and amount with two or more claims (VOID and DELETED claims left out), one
// line as <name> <date> <amount> x<count>; people in list order, then by date, then by amount. It records nothing.
@Component({
  selector: 'app-duplicates',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="dups-heading">
      <h2 id="dups-heading">Duplicate claims</h2>
      <button type="button" data-testid="dups-show" (click)="show()">Show duplicate claims</button>
      @if (error(); as message) {
        <p role="alert" data-testid="dups-error">{{ message | fig }}</p>
      }
      <ol aria-label="Duplicate claims">
        @for (line of lines(); track $index) {
          <li data-testid="dup-line">{{ line | fig }}</li>
        }
      </ol>
    </section>
  `,
})
export class Duplicates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

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

  protected async show(): Promise<void> {
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const read = ++this.reads;
    this.lines.set([]);
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/duplicates`,
          this.session.headers(),
        ),
      );
      // A later read wins over a slower earlier one.
      if (read === this.reads && this.session.sheet() === sheet) this.lines.set(lines);
    } catch {
      if (read === this.reads) this.error.set('The duplicate claims could not be shown.');
    }
  }
}
