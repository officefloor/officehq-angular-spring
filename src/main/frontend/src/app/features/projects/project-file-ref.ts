import { Component, effect, inject, input, output, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Project, ProjectService } from './project.service';

// A job's file reference: shows it, and notes or clears it (leave it blank to clear it).
@Component({
  selector: 'app-project-file-ref',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="job-file-ref-heading" data-testid="job-file-ref-section">
      <h2 id="job-file-ref-heading">File reference</h2>
      @if (project().fileRef; as ref) {
        <p data-testid="job-file-ref">{{ ref }}</p>
      } @else {
        <p data-testid="job-file-ref-none">No file reference noted.</p>
      }

      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="job-file-ref-form" novalidate>
        <label for="job-file-ref-input">File reference</label>
        <input
          id="job-file-ref-input"
          type="text"
          maxlength="100"
          formControlName="fileRef"
          data-testid="job-file-ref-input"
        />
        <button type="submit" data-testid="job-file-ref-submit" [disabled]="saving()">Save file reference</button>
        @if (saveError()) {
          <p role="alert" data-testid="job-file-ref-save-error">{{ saveError() }}</p>
        }
      </form>
    </section>
  `,
})
export class ProjectFileRef {
  private readonly service = inject(ProjectService);

  readonly project = input.required<Project>();
  /** Emits the job once its file reference has been saved. */
  readonly changed = output<Project>();

  protected readonly form = inject(NonNullableFormBuilder).group({ fileRef: ['', Validators.maxLength(100)] });
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.form.setValue({ fileRef: this.project().fileRef ?? '' });
    });
  }

  protected submit(): void {
    const fileRef = this.form.getRawValue().fileRef.trim();
    this.saving.set(true);
    this.saveError.set(null);
    this.service.setFileRef(this.project().id, fileRef || null).subscribe({
      next: (updated) => {
        this.changed.emit(updated);
        this.saving.set(false);
      },
      error: () => {
        this.saveError.set('Could not save the file reference.');
        this.saving.set(false);
      },
    });
  }
}
