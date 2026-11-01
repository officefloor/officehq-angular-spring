import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { GRADES, Grade } from '../../core/people';
import { Sheets } from '../../core/sheet';

// The sheet's grades, each shown retired or not, and a form to retire one.
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
    </section>
  `,
})
export class GradesPanel {
  private readonly sheets = inject(Sheets);

  protected readonly grades = GRADES;
  protected readonly retired = computed(() => new Set(this.sheets.current()?.retiredGrades ?? []));
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly form = new FormGroup({
    grade: new FormControl<Grade>('A', { nonNullable: true }),
  });

  constructor() {
    toObservable(this.sheets.loaded)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.error.set(null));
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
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
}
