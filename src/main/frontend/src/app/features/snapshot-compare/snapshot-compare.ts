import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A person's due-total in each of the two snapshots compared: empty when there is none. */
interface SnapshotLine {
  name: string;
  then: string;
  now: string;
}

// Two snapshot dates compared, read afresh on each press: per person in list order, the due-total
// of the SNAPSHOT written as the sheet's today left the from-date, and of the one for the to-date
// (the person's due-total now when the to-date is blank).
@Component({
  selector: 'app-snapshot-compare',
  template: `
    <section aria-labelledby="snapshot-compare-heading">
      <h2 id="snapshot-compare-heading">Compare snapshots</h2>
      <form (submit)="compare($event)">
        <label for="snapshot-from">From</label>
        <input
          id="snapshot-from"
          type="date"
          data-testid="snapshot-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="snapshot-to">To</label>
        <input
          id="snapshot-to"
          type="date"
          data-testid="snapshot-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="snapshot-compare">Compare</button>
      </form>
      @if (error(); as message) {
        <p role="alert" data-testid="snapshot-error">{{ message }}</p>
      }
      <ul>
        @for (l of lines(); track $index) {
          <li data-testid="snapshot-line">{{ text(l) }}</li>
        }
      </ul>
    </section>
  `,
})
export class SnapshotCompare {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly lines = signal<SnapshotLine[]>([]);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // A sheet load clears the lines shown for the sheet before.
    effect(() => {
      this.session.loads();
      this.lines.set([]);
      this.error.set(null);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** A line trimmed: an empty figure leaves no trailing space. */
  protected text(l: SnapshotLine): string {
    return [l.name, l.then, l.now].join(' ').trim();
  }

  protected async compare(event: Event): Promise<void> {
    event.preventDefault();
    this.error.set(null);
    this.lines.set([]);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    let params = new HttpParams();
    if (this.from()) params = params.set('from', this.from());
    if (this.to()) params = params.set('to', this.to());
    try {
      const lines = await firstValueFrom(
        this.http.get<SnapshotLine[]>(`/api/sheets/${encodeURIComponent(sheet)}/snapshot-compare`, { params }),
      );
      if (this.session.sheetId() === sheet) this.lines.set(lines);
    } catch {
      this.error.set('The snapshots could not be compared.');
    }
  }
}
