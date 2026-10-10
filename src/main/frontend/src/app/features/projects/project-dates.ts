import { Component, effect, inject, input, output, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Project, ProjectService } from './project.service';

// A job's start and end dates: shows them, and sets or clears them (leave a date blank to clear it).
@Component({
  selector: 'app-project-dates',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="job-dates-heading" data-testid="job-dates-section">
      <h2 id="job-dates-heading">Dates</h2>
      <dl>
        <dt>Start date</dt>
        <dd data-testid="job-start-date">{{ project().startDate ?? 'Not set' }}</dd>
        <dt>End date</dt>
        <dd data-testid="job-end-date">{{ project().endDate ?? 'Not set' }}</dd>
      </dl>

      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="job-dates-form" novalidate>
        <label for="job-start-date-input">Start date</label>
        <input id="job-start-date-input" type="date" formControlName="startDate" data-testid="job-start-date-input" />
        <label for="job-end-date-input">End date</label>
        <input
          id="job-end-date-input"
          type="date"
          formControlName="endDate"
          data-testid="job-end-date-input"
          [attr.aria-invalid]="orderError()"
          [attr.aria-describedby]="orderError() ? 'job-dates-order-error' : null"
        />
        <button type="submit" data-testid="job-dates-submit" [disabled]="saving()">Save dates</button>
        @if (orderError()) {
          <p id="job-dates-order-error" role="alert" data-testid="job-dates-order-error">
            The end date may not be before the start date.
          </p>
        }
        @if (saveError()) {
          <p role="alert" data-testid="job-dates-save-error">{{ saveError() }}</p>
        }
      </form>
    </section>
  `,
  styles: `
    dl {
      display: grid;
      grid-template-columns: max-content auto;
      gap: 0.25rem 1rem;
    }
    dd {
      margin: 0;
    }
  `,
})
export class ProjectDates {
  private readonly service = inject(ProjectService);

  readonly project = input.required<Project>();
  /** Emits the job once its dates have been saved. */
  readonly changed = output<Project>();

  protected readonly form = inject(NonNullableFormBuilder).group({ startDate: '', endDate: '' });
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly orderError = signal(false);

  constructor() {
    effect(() => {
      const p = this.project();
      this.form.setValue({ startDate: p.startDate ?? '', endDate: p.endDate ?? '' });
    });
  }

  protected submit(): void {
    const { startDate, endDate } = this.form.getRawValue();
    this.orderError.set(startDate !== '' && endDate !== '' && endDate < startDate);
    if (this.orderError()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    this.service.setDates(this.project().id, startDate || null, endDate || null).subscribe({
      next: (updated) => {
        this.changed.emit(updated);
        this.saving.set(false);
      },
      error: () => {
        this.saveError.set('Could not save the dates.');
        this.saving.set(false);
      },
    });
  }
}
