import { Component, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RecognitionBasis, SettingsService } from './settings.service';
import { CurrencyRounding } from '../currencies/currency-rounding';
import { FxRates } from '../fx-rates/fx-rates';
import { BillingTarget } from './billing-target';

// The app-wide settings: the standard sales tax rate that every new invoice starts with, whether revenue counts when an invoice is sent or when it is paid, the yearly billings target, how each currency rounds, and the exchange rate history.
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule, CurrencyRounding, FxRates, BillingTarget],
  template: `
    <h1>Settings</h1>
    @if (settings.error()) {
      <p role="alert" data-testid="settings-error">Could not load the settings.</p>
    }
    <form [formGroup]="form" (ngSubmit)="save()" data-testid="settings-form" novalidate>
      <div>
        <label for="settings-default-tax-rate">Default tax rate (%) for new invoices</label>
        <input
          id="settings-default-tax-rate"
          type="number"
          inputmode="decimal"
          min="0"
          max="100"
          step="0.01"
          formControlName="defaultTaxPct"
          data-testid="settings-default-tax-rate"
          [attr.aria-invalid]="showError()"
          [attr.aria-describedby]="showError() ? 'settings-default-tax-rate-error' : null"
        />
        @if (showError()) {
          <p id="settings-default-tax-rate-error" role="alert" data-testid="settings-default-tax-rate-error">
            Enter a percentage from 0 to 100 with at most two decimal places.
          </p>
        }
      </div>
      <div>
        <label for="settings-recognition-basis">Count revenue when an invoice is</label>
        <select
          id="settings-recognition-basis"
          formControlName="revenueRecognitionBasis"
          data-testid="settings-recognition-basis"
        >
          <option value="sent">Sent</option>
          <option value="paid">Paid</option>
        </select>
      </div>
      <button type="submit" data-testid="settings-save" [disabled]="saving()">Save settings</button>
      @if (saved()) {
        <p role="status" data-testid="settings-saved">Settings saved.</p>
      }
      @if (saveError()) {
        <p role="alert" data-testid="settings-save-error">{{ saveError() }}</p>
      }
    </form>
    <app-billing-target />
    <app-currency-rounding />
    <app-fx-rates />
  `,
})
export class SettingsPage {
  private readonly service = inject(SettingsService);

  protected readonly settings = rxResource({ stream: () => this.service.get() });

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    defaultTaxPct: [
      '',
      [Validators.required, Validators.min(0), Validators.max(100), Validators.pattern(/^\d+(\.\d{1,2})?$/)],
    ],
    revenueRecognitionBasis: ['sent' as RecognitionBasis],
  });

  // Starts each field from the saved settings once they load, unless that field has already been edited.
  private readonly syncForm = effect(() => {
    if (this.settings.hasValue()) {
      const settings = this.settings.value();
      const { defaultTaxPct, revenueRecognitionBasis } = this.form.controls;
      if (!defaultTaxPct.dirty) {
        defaultTaxPct.setValue(String(settings.defaultTaxPct));
      }
      if (!revenueRecognitionBasis.dirty) {
        revenueRecognitionBasis.setValue(settings.revenueRecognitionBasis ?? 'sent');
      }
    }
  });

  protected showError(): boolean {
    const control = this.form.controls.defaultTaxPct;
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
    const { revenueRecognitionBasis } = this.form.getRawValue();
    const defaultTaxPct = Number(this.form.getRawValue().defaultTaxPct);
    this.service.update({ defaultTaxPct, revenueRecognitionBasis }).subscribe({
      next: (updated) => {
        this.settings.set(updated);
        this.form.reset({
          defaultTaxPct: String(updated.defaultTaxPct),
          revenueRecognitionBasis: updated.revenueRecognitionBasis ?? 'sent',
        });
        this.saving.set(false);
        this.saved.set(true);
      },
      error: () => {
        this.saveError.set('Could not save the settings. Please try again.');
        this.saving.set(false);
      },
    });
  }
}
