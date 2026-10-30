import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A person, date and amount with two or more claims, and how many. */
interface Duplicate {
  name: string;
  date: string;
  amount: string;
  count: number;
}

// The duplicate claims: for each person, date and amount with two or more claims (VOID and DELETED left out), one
// line as <name> <date> <amount> x<count>, people in list order, then by date, then by amount. It reads the store; it
// records nothing.
@Component({
  selector: 'app-duplicates',
  template: `
    <section aria-label="Duplicate claims">
      <h2>Duplicate claims</h2>
      <button type="button" data-testid="dups-show" (click)="show()">Show duplicates</button>
      @if (error()) {
        <p role="alert" data-testid="dups-error">{{ error() }}</p>
      }
      <ol>
        @for (line of lines(); track $index) {
          <li data-testid="dup-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class Duplicates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

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

  protected async show(): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const show = ++this.shows;
    this.lines.set([]);
    this.error.set('');
    try {
      const found = await firstValueFrom(
        this.http.get<Duplicate[]>(`/api/sheets/${encodeURIComponent(sheet)}/claims/duplicates`),
      );
      if (show !== this.shows) return;
      this.lines.set(found.map((d) => `${d.name} ${d.date} ${d.amount} x${d.count}`));
    } catch {
      if (show === this.shows) this.error.set('Could not find the duplicate claims.');
    }
  }
}
