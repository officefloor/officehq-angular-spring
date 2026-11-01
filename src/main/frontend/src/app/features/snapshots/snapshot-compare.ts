import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Sheets } from '../../core/sheet';
import { Snapshots } from '../../core/snapshots';

// Compare two snapshot dates: each person's due-total in the snapshot of the from-date and of the to-date (or now).
@Component({
  selector: 'app-snapshot-compare',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="snapshot-compare-heading">
      <h2 id="snapshot-compare-heading">Compare snapshots</h2>
      <form [formGroup]="form" (ngSubmit)="compare()" aria-label="Compare two snapshot dates">
        <label for="snapshot-from">From</label>
        <input id="snapshot-from" type="date" data-testid="snapshot-from" formControlName="from" />
        <label for="snapshot-to">To (blank: now)</label>
        <input id="snapshot-to" type="date" data-testid="snapshot-to" formControlName="to" />
        <button type="submit" data-testid="snapshot-compare" [disabled]="comparing()">Compare</button>
        @if (error(); as err) {
          <p role="alert" data-testid="snapshot-compare-error">{{ err }}</p>
        }
      </form>
      <ul aria-label="Snapshot comparison">
        @for (line of lines(); track $index) {
          <li data-testid="snapshot-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class SnapshotComparePanel {
  private readonly sheets = inject(Sheets);
  private readonly snapshots = inject(Snapshots);

  protected readonly form = new FormGroup({
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });
  protected readonly lines = signal<readonly string[]>([]);
  protected readonly comparing = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // A (re)loaded sheet starts with no comparison shown.
    toObservable(this.sheets.loaded)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        this.lines.set([]);
        this.error.set(null);
      });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected compare(): void {
    // A second press before the first has answered is ignored.
    if (this.comparing()) return;
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.error.set(null);
    this.lines.set([]);
    this.comparing.set(true);
    const { from, to } = this.form.getRawValue();
    this.snapshots.compare(sheet.id, from.trim(), to.trim()).subscribe({
      next: (lines) => {
        this.comparing.set(false);
        this.lines.set(lines);
      },
      error: () => {
        this.comparing.set(false);
        this.error.set('Could not compare the snapshots.');
      },
    });
  }
}
