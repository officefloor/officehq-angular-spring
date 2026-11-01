import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { GRADES, Grade, People } from '../../core/people';
import { Sheets } from '../../core/sheet';

// The sheet's grades, each shown retired or not, a form to retire one and a form to merge one into another.
@Component({
  selector: 'app-grades',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="grades-heading">
      <h2 id="grades-heading">Grades</h2>
      <ul aria-label="Grades">
        @for (g of grades; track g) {
          <li [attr.data-testid]="'grade-row-' + g">
            Grade {{ g }}, retired: <span data-testid="grade-retired">{{ retired().has(g) ? 'yes' : 'no' }}</span>
          </li>
        }
      </ul>
      <form [formGroup]="form" (ngSubmit)="retire()" aria-label="Retire a grade">
        <label for="grade-retire-select">Grade to retire</label>
        <select id="grade-retire-select" data-testid="grade-retire-select" formControlName="grade">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-retire" [disabled]="saving()">Retire grade</button>
        @if (error(); as err) {
          <p role="alert" data-testid="grade-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="mergeForm" (ngSubmit)="merge()" aria-label="Merge a grade into another">
        <label for="grade-merge-from">Grade to merge</label>
        <select id="grade-merge-from" data-testid="grade-merge-from" formControlName="from">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <label for="grade-merge-into">Merge into</label>
        <select id="grade-merge-into" data-testid="grade-merge-into" formControlName="into">
          @for (g of grades; track g) {
            <option [value]="g">{{ g }}</option>
          }
        </select>
        <button type="submit" data-testid="grade-merge" [disabled]="merging()">Merge grade</button>
        <p aria-live="polite">
          @if (merged() !== null) {
            Merged <span data-testid="grade-merged">{{ merged() }}</span>
          }
        </p>
      </form>
    </section>
  `,
})
export class GradesPanel {
  private readonly sheets = inject(Sheets);
  private readonly people = inject(People);

  protected readonly grades = GRADES;
  protected readonly retired = computed(() => new Set(this.sheets.current()?.retiredGrades ?? []));
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly merging = signal(false);
  /** How many people the last merge gave the other grade; null before one, and while one runs. */
  protected readonly merged = signal<number | null>(null);

  protected readonly form = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
  });

  protected readonly mergeForm = new FormGroup({
    from: new FormControl<Grade>('A', { nonNullable: true }),
    into: new FormControl<Grade>('B', { nonNullable: true }),
  });

  constructor() {
    toObservable(this.sheets.loaded)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        this.error.set(null);
        this.merged.set(null);
      });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
    this.mergeForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected retire(): void {
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const { grade } = this.form.getRawValue();
    this.saving.set(true);
    this.sheets.retireGrade(sheet.id, grade).subscribe({
      next: () => this.saving.set(false),
      error: () => {
        this.saving.set(false);
        this.error.set(`Grade ${grade} could not be retired: it is already retired.`);
      },
    });
  }

  protected merge(): void {
    if (this.merging()) return;
    this.error.set(null);
    this.merged.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const { from, into } = this.mergeForm.getRawValue();
    this.merging.set(true);
    this.people.mergeGrade(sheet.id, from, into).subscribe({
      next: ({ merged }) => {
        this.merging.set(false);
        this.merged.set(merged);
      },
      error: () => {
        this.merging.set(false);
        this.error.set(
          from === into
            ? `Grade ${from} cannot be merged into itself.`
            : `Grade ${from} could not be merged into ${into}: only a steward may merge a grade, into one not retired.`,
        );
      },
    });
  }
}
