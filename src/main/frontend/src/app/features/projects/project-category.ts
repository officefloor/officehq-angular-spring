import { Component, effect, inject, input, output, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Project, ProjectService } from './project.service';

// A job's category: shows it, and puts the job into one or takes it out (leave it blank to clear it).
@Component({
  selector: 'app-project-category',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="job-category-heading" data-testid="job-category-section">
      <h2 id="job-category-heading">Category</h2>
      @if (project().category; as category) {
        <p data-testid="job-category">{{ category }}</p>
      } @else {
        <p data-testid="job-category-none">Not in a category.</p>
      }

      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="job-category-form" novalidate>
        <label for="job-category-input">Category</label>
        <input
          id="job-category-input"
          type="text"
          maxlength="50"
          formControlName="category"
          data-testid="job-category-input"
        />
        <button type="submit" data-testid="job-category-submit" [disabled]="saving()">Save category</button>
        @if (saveError()) {
          <p role="alert" data-testid="job-category-save-error">{{ saveError() }}</p>
        }
      </form>
    </section>
  `,
})
export class ProjectCategory {
  private readonly service = inject(ProjectService);

  readonly project = input.required<Project>();
  /** Emits the job once its category has been saved. */
  readonly changed = output<Project>();

  protected readonly form = inject(NonNullableFormBuilder).group({ category: ['', Validators.maxLength(50)] });
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.form.setValue({ category: this.project().category ?? '' });
    });
  }

  protected submit(): void {
    const category = this.form.getRawValue().category.trim();
    this.saving.set(true);
    this.saveError.set(null);
    this.service.setCategory(this.project().id, category || null).subscribe({
      next: (updated) => {
        this.changed.emit(updated);
        this.saving.set(false);
      },
      error: () => {
        this.saveError.set('Could not save the category.');
        this.saving.set(false);
      },
    });
  }
}
