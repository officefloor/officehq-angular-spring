import { Component, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { SheetGradeDues } from '../../core/consolidated';
import { Sheets } from '../../core/sheet';
import { GradeSummary } from './summary';

// Compare this sheet with another: per grade of this sheet, its summary due-total and the other sheet's (0.00 with no
// one on it there); a sheet that does not exist gives no lines.
@Component({
  selector: 'app-sheet-compare',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="sheet-compare-heading">
      <h2 id="sheet-compare-heading">Compare with another sheet</h2>
      <form [formGroup]="form" (ngSubmit)="compare()" aria-label="Compare with another sheet">
        <label for="compare-sheet">Other sheet id</label>
        <input id="compare-sheet" data-testid="compare-sheet" formControlName="other" />
        <button type="submit" data-testid="compare" [disabled]="comparing()">Compare</button>
        @if (error(); as err) {
          <p role="alert" data-testid="compare-error">{{ err }}</p>
        }
      </form>
      <ul aria-label="Sheet comparison">
        @for (line of lines(); track $index) {
          <li data-testid="compare-line">{{ line }}</li>
        }
      </ul>
    </section>
  `,
})
export class SheetComparePanel {
  private readonly sheets = inject(Sheets);
  private readonly gradeDues = inject(SheetGradeDues);

  /** This sheet's summary by grade. */
  readonly grades = input.required<readonly GradeSummary[]>();

  protected readonly form = new FormGroup({
    other: new FormControl('', { nonNullable: true }),
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
    const other = this.form.getRawValue().other.trim();
    this.error.set(null);
    this.lines.set([]);
    if (!/^[A-Za-z0-9._-]{1,100}$/.test(other)) {
      this.error.set('Enter a sheet id.');
      return;
    }
    this.comparing.set(true);
    this.gradeDues.of(other).subscribe({
      next: ({ exists, dueByGrade }) => {
        this.comparing.set(false);
        this.lines.set(exists
          ? this.grades().map((g) => `${g.grade} ${g.dueTotal} ${dueByGrade[g.grade] ?? '0.00'}`)
          : []);
      },
      error: () => {
        this.comparing.set(false);
        this.error.set('Could not compare the sheets.');
      },
    });
  }
}
