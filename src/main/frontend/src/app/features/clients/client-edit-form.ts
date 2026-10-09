import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, OnInit, afterNextRender, inject, input, output, signal, viewChild } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Client, ClientService } from './client.service';

// Form to correct one client's name, email, phone number, tax number, whether their prices include tax or whether they are tax exempt. Emits the saved client, or cancelled when abandoned.
@Component({
  selector: 'app-client-edit-form',
  imports: [ReactiveFormsModule],
  template: `
    <form
      [formGroup]="form"
      (ngSubmit)="submit()"
      data-testid="client-edit-form"
      [attr.aria-label]="'Edit ' + client().name"
      novalidate
    >
      <div>
        <label for="client-edit-name">Name</label>
        <input
          #nameInput
          id="client-edit-name"
          type="text"
          formControlName="name"
          autocomplete="organization"
          data-testid="client-edit-form-name"
          [attr.aria-invalid]="showError('name')"
          [attr.aria-describedby]="showError('name') ? 'client-edit-name-error' : null"
        />
        @if (showError('name')) {
          <p id="client-edit-name-error" role="alert" data-testid="client-edit-form-name-error">
            Name is required.
          </p>
        }
      </div>
      <div>
        <label for="client-edit-email">Email</label>
        <input
          id="client-edit-email"
          type="email"
          formControlName="email"
          autocomplete="email"
          data-testid="client-edit-form-email"
          [attr.aria-invalid]="showError('email')"
          [attr.aria-describedby]="showError('email') ? 'client-edit-email-error' : null"
        />
        @if (showError('email')) {
          <p id="client-edit-email-error" role="alert" data-testid="client-edit-form-email-error">
            @if (form.controls.email.hasError('taken')) {
              A client with this email already exists.
            } @else {
              Enter a valid email address.
            }
          </p>
        }
      </div>
      <div>
        <label for="client-edit-phone">Phone (optional)</label>
        <input
          id="client-edit-phone"
          type="tel"
          formControlName="phone"
          autocomplete="tel"
          data-testid="client-edit-form-phone"
          [attr.aria-invalid]="showError('phone')"
          [attr.aria-describedby]="showError('phone') ? 'client-edit-phone-error' : null"
        />
        @if (showError('phone')) {
          <p id="client-edit-phone-error" role="alert" data-testid="client-edit-form-phone-error">
            Phone number must be 50 characters or fewer.
          </p>
        }
      </div>
      <div>
        <label for="client-edit-tax-number">Tax number (optional)</label>
        <input
          id="client-edit-tax-number"
          type="text"
          formControlName="taxNumber"
          autocomplete="off"
          data-testid="client-edit-form-tax-number"
          [attr.aria-invalid]="showError('taxNumber')"
          [attr.aria-describedby]="showError('taxNumber') ? 'client-edit-tax-number-error' : null"
        />
        @if (showError('taxNumber')) {
          <p id="client-edit-tax-number-error" role="alert" data-testid="client-edit-form-tax-number-error">
            Tax number must be 50 characters or fewer.
          </p>
        }
      </div>
      <div>
        <label for="client-edit-billing-address">Billing address (optional)</label>
        <textarea
          id="client-edit-billing-address"
          rows="3"
          formControlName="billingAddress"
          autocomplete="street-address"
          data-testid="client-edit-form-billing-address"
          [attr.aria-invalid]="showError('billingAddress')"
          [attr.aria-describedby]="showError('billingAddress') ? 'client-edit-billing-address-error' : null"
        ></textarea>
        @if (showError('billingAddress')) {
          <p id="client-edit-billing-address-error" role="alert" data-testid="client-edit-form-billing-address-error">
            Billing address must be 500 characters or fewer.
          </p>
        }
      </div>
      <div>
        <label>
          <input type="checkbox" formControlName="taxInclusive" data-testid="client-edit-form-tax-inclusive" />
          Prices include tax
        </label>
      </div>
      <div>
        <label>
          <input type="checkbox" formControlName="taxExempt" data-testid="client-edit-form-tax-exempt" />
          Tax exempt (no tax on any invoice)
        </label>
      </div>
      <div>
        <label>
          <input type="checkbox" formControlName="keyAccount" data-testid="client-edit-form-key-account" />
          Key account
        </label>
      </div>
      <button type="submit" data-testid="client-edit-form-submit" [disabled]="saving()">Save</button>
      <button type="button" data-testid="client-edit-form-cancel" (click)="cancelled.emit()">Cancel</button>
      @if (saveError()) {
        <p role="alert" data-testid="client-edit-form-error">{{ saveError() }}</p>
      }
    </form>
  `,
})
export class ClientEditForm implements OnInit {
  private readonly service = inject(ClientService);

  readonly client = input.required<Client>();
  readonly saved = output<Client>();
  readonly cancelled = output<void>();

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  private readonly nameInput = viewChild.required<ElementRef<HTMLInputElement>>('nameInput');

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(255)]],
    email: [
      '',
      [
        Validators.required,
        Validators.email,
        // Validators.email accepts "a@b"; also require a dotted domain (matches the server rule).
        Validators.pattern(/^[^@\s]+@[^@\s]+\.[^@\s]+$/),
        Validators.maxLength(255),
      ],
    ],
    phone: ['', Validators.maxLength(50)],
    taxNumber: ['', Validators.maxLength(50)],
    billingAddress: ['', Validators.maxLength(500)],
    taxInclusive: false,
    taxExempt: false,
    keyAccount: false,
  });

  constructor() {
    afterNextRender(() => this.nameInput().nativeElement.focus());
  }

  ngOnInit(): void {
    const { name, email, phone, taxNumber, billingAddress, taxInclusive, taxExempt, keyAccount } = this.client();
    this.form.setValue({ name, email, phone: phone ?? '', taxNumber: taxNumber ?? '', billingAddress: billingAddress ?? '', taxInclusive, taxExempt, keyAccount });
  }

  protected showError(field: 'name' | 'email' | 'phone' | 'taxNumber' | 'billingAddress'): boolean {
    const control = this.form.controls[field];
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, email, phone, taxNumber, billingAddress, taxInclusive, taxExempt, keyAccount } = this.form.getRawValue();
    this.saving.set(true);
    this.saveError.set(null);
    this.service.update(this.client().id, { name: name.trim(), email: email.trim(), phone: phone.trim() || null, taxNumber: taxNumber.trim() || null, billingAddress: billingAddress.trim() || null, taxInclusive, taxExempt, keyAccount }).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.saved.emit(updated);
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 409) {
          // The server owns uniqueness; flag the email field until it is changed.
          const control = this.form.controls.email;
          control.setErrors({ taken: true });
          control.markAsTouched();
        } else {
          this.saveError.set('Could not save the client. Please try again.');
        }
        this.saving.set(false);
      },
    });
  }
}
