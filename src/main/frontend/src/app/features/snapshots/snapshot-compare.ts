import { Component, computed, inject, signal } from '@angular/core';
import { SnapshotCompare } from '../../core/snapshot-compare';
import { Session } from '../../core/session';

// Compare two snapshot dates: one line per person in list order, <name> <then> <now>, each the due-total in the
// snapshot written when the sheet's today moved on from that date (a blank to-date: their due-total now).
@Component({
  selector: 'app-snapshot-compare',
  template: `
    <section aria-label="Compare snapshots" data-testid="snapshot-compare-panel">
      <h2>Compare snapshots</h2>
      <form (submit)="compare($event)">
        <label for="snapshot-from">From</label>
        <input
          id="snapshot-from"
          placeholder="YYYY-MM-DD"
          data-testid="snapshot-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="snapshot-to">To</label>
        <input
          id="snapshot-to"
          placeholder="YYYY-MM-DD"
          data-testid="snapshot-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="snapshot-compare">Compare</button>
        @if (error(); as err) {
          <p role="alert" data-testid="snapshot-compare-error">{{ err }}</p>
        }
      </form>
      <ol aria-label="Snapshot comparison">
        @for (line of lines(); track $index) {
          <li data-testid="snapshot-line">{{ line }}</li>
        }
      </ol>
    </section>
  `,
})
export class SnapshotComparePanel {
  private readonly comparer = inject(SnapshotCompare);
  private readonly session = inject(Session);
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  private readonly shown = signal<{ sheet: string | null; lines: string[] }>({ sheet: null, lines: [] });
  /** The lines last compared, while they are of the sheet loaded now. */
  protected readonly lines = computed(() => {
    const s = this.shown();
    return s.sheet === this.session.sheetId() ? s.lines : [];
  });
  private compares = 0;

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async compare(e: Event): Promise<void> {
    e.preventDefault();
    const compare = ++this.compares;
    const sheet = this.session.sheetId();
    this.error.set(null);
    this.shown.set({ sheet: null, lines: [] });
    const res = await this.comparer.compare(this.from(), this.to());
    if (compare !== this.compares) return; // a later press has taken over
    if ('error' in res) this.error.set(res.error);
    else this.shown.set({ sheet, lines: res.lines });
  }
}
