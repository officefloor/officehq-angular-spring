import { CurrencyPipe } from '@angular/common';
import { Component, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ProjectService } from './project.service';

// A project's budget: what it is, how much has been invoiced against it (sent invoices, paid or not),
// and what is left. Set or change the budget, or clear it by leaving the amount blank.
@Component({
  selector: 'app-project-budget',
  imports: [ReactiveFormsModule, CurrencyPipe],
  template: `
    <section aria-labelledby="project-budget-heading" data-testid="project-budget-section">
      <h2 id="project-budget-heading">Budget</h2>

      @if (budget.error()) {
        <p role="alert" data-testid="project-budget-error">Could not load the budget.</p>
      } @else if (budget.hasValue()) {
        @let b = budget.value();
        <dl>
          <dt>Budget</dt>
          <dd data-testid="project-budget">
            @if (b.budget === null) {
              No budget set
            } @else {
              {{ b.budget | currency: 'USD' : 'symbol' : '1.2-2' : 'en-US' }}
            }
          </dd>
          <dt>Invoiced</dt>
          <dd data-testid="project-invoiced">{{ b.invoiced | currency: 'USD' : 'symbol' : '1.2-2' : 'en-US' }}</dd>
          <dt>Remaining</dt>
          <dd data-testid="project-remaining" [class.over]="b.remaining !== null && b.remaining < 0">
            @if (b.remaining === null) {
              —
            } @else {
              {{ b.remaining | currency: 'USD' : 'symbol' : '1.2-2' : 'en-US' }}
            }
          </dd>
        </dl>
        @if (b.remaining !== null && b.remaining < 0) {
          <p data-testid="project-over-budget">Invoiced over budget.</p>
        }
      }

      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="project-budget-form" novalidate>
        <label for="project-budget-amount">Set budget (leave blank to clear)</label>
        <input
          id="project-budget-amount"
          type="number"
          inputmode="decimal"
          min="0"
          step="0.01"
          formControlName="budget"
          data-testid="project-budget-input"
          [attr.aria-invalid]="showError()"
          [attr.aria-describedby]="showError() ? 'project-budget-amount-error' : null"
        />
        <button type="submit" data-testid="project-budget-submit" [disabled]="saving()">Save budget</button>
        @if (showError()) {
          <p id="project-budget-amount-error" role="alert" data-testid="project-budget-input-error">
            Enter an amount of zero or more with at most two decimal places, or leave it blank.
          </p>
        }
        @if (saveError()) {
          <p role="alert" data-testid="project-budget-save-error">{{ saveError() }}</p>
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
    .over {
      font-weight: bold;
    }
  `,
})
export class ProjectBudgetPanel {
  private readonly service = inject(ProjectService);

  readonly projectId = input.required<number>();

  protected readonly budget = rxResource({
    params: () => this.projectId(),
    stream: ({ params }) => this.service.budget(params),
  });

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    budget: ['', [Validators.min(0), Validators.pattern(/^\d+(\.\d{1,2})?$/)]],
  });

  /** Reloads the figures, e.g. after an invoice on the project has been sent. */
  reload(): void {
    this.budget.reload();
  }

  protected showError(): boolean {
    const control = this.form.controls.budget;
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    const { budget } = this.form.getRawValue();
    const value = budget === '' || budget === null ? null : Number(budget);
    this.service.setBudget(this.projectId(), value).subscribe({
      next: (updated) => {
        this.budget.set(updated);
        this.form.reset();
        this.saving.set(false);
      },
      error: () => {
        this.saveError.set('Could not save the budget. Please try again.');
        this.saving.set(false);
      },
    });
  }
}
