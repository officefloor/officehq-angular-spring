import { Component, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { SettingsService } from './settings.service';

// The yearly billings target, in the home currency: what the business aims to bill over the year. The dashboard tracks
// what has been billed this year against it. Left empty, no target is set.
@Component({
  selector: 'app-billing-target',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="save()" data-testid="billing-target-form" novalidate>
      <div>
        <label for="settings-billing-target">
          Billings target for the year{{ settings.hasValue() ? ' (' + settings.value().homeCurrency + ')' : '' }}
        </label>
        <input
          id="settings-billing-target"
          type="number"
          inputmode="decimal"
          min="0.01"
          step="0.01"
          formControlName="billingTarget"
          data-testid="settings-billing-target"
          [attr.aria-invalid]="showError()"
          [attr.aria-describedby]="showError() ? 'settings-billing-target-error' : 'settings-billing-target-hint'"
        />
        <p id="settings-billing-target-hint">Leave empty for no target.</p>
        @if (showError()) {
          <p id="settings-billing-target-error" role="alert" data-testid="settings-billing-target-error">
            Enter an amount above zero with at most two decimal places.
          </p>
        }
      </div>
      <button type="submit" data-testid="settings-billing-target-save" [disabled]="saving()">Save target</button>
      @if (saved()) {
        <p role="status" data-testid="settings-billing-target-saved">Billings target saved.</p>
      }
      @if (saveError()) {
        <p role="alert" data-testid="settings-billing-target-save-error">{{ saveError() }}</p>
      }
    </form>
  `,
})
export class BillingTarget {
  private readonly service = inject(SettingsService);

  protected readonly settings = rxResource({ stream: () => this.service.get() });

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    billingTarget: ['', [Validators.pattern(/^\d+(\.\d{1,2})?$/), Validators.min(0.01)]],
  });

  // Starts the field from the saved target once the settings load, unless it has already been edited.
  private readonly syncForm = effect(() => {
    if (this.settings.hasValue() && !this.form.controls.billingTarget.dirty) {
      const target = this.settings.value().billingTarget;
      this.form.controls.billingTarget.setValue(target == null ? '' : String(target));
    }
  });

  protected showError(): boolean {
    const control = this.form.controls.billingTarget;
    return control.invalid && (control.touched || control.dirty);
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saved.set(false);
    this.saveError.set(null);
    const raw = String(this.form.getRawValue().billingTarget ?? '').trim();
    this.service.updateBillingTarget(raw === '' ? null : Number(raw)).subscribe({
      next: (updated) => {
        this.settings.set(updated);
        this.form.reset({ billingTarget: updated.billingTarget == null ? '' : String(updated.billingTarget) });
        this.saving.set(false);
        this.saved.set(true);
      },
      error: () => {
        this.saveError.set('Could not save the billings target. Please try again.');
        this.saving.set(false);
      },
    });
  }
}
