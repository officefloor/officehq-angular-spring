import { Component, inject, input, linkedSignal, output, signal } from '@angular/core';
import { Client, ClientService } from './client.service';

// The number of days a client has to pay an invoice (e.g. net 30), with a form to set it or remove it.
@Component({
  selector: 'app-client-payment-terms',
  template: `
    <p>
      Payment terms:
      @if (client().paymentTermsDays !== null) {
        <span data-testid="client-payment-terms">Net {{ client().paymentTermsDays }}</span>
      } @else {
        <span data-testid="client-payment-terms-none">None agreed</span>
      }
    </p>
    <form (submit)="$event.preventDefault(); save()">
      <label for="client-payment-terms-input">Payment terms (days, blank for none)</label>
      <input
        id="client-payment-terms-input"
        type="number"
        min="0"
        max="365"
        step="1"
        data-testid="client-payment-terms-input"
        [value]="days()"
        (input)="days.set($any($event.target).value)"
        [attr.aria-invalid]="invalid()"
        [attr.aria-describedby]="invalid() ? 'client-payment-terms-invalid' : null"
      />
      <button type="submit" data-testid="client-payment-terms-save" [disabled]="saving()">Save payment terms</button>
      @if (invalid()) {
        <p id="client-payment-terms-invalid" role="alert" data-testid="client-payment-terms-invalid">
          Payment terms must be a whole number of days from 0 to 365.
        </p>
      }
      @if (saveError()) {
        <p role="alert" data-testid="client-payment-terms-error">{{ saveError() }}</p>
      }
    </form>
  `,
})
export class ClientPaymentTerms {
  private readonly service = inject(ClientService);

  readonly client = input.required<Client>();
  /** Emits the client once their payment terms have been saved. */
  readonly changed = output<Client>();

  protected readonly days = linkedSignal(() => this.client().paymentTermsDays?.toString() ?? '');
  protected readonly invalid = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected save(): void {
    const text = this.days().toString().trim();
    const days = text === '' ? null : Number(text);
    if (days !== null && (!Number.isInteger(days) || days < 0 || days > 365)) {
      this.invalid.set(true);
      return;
    }
    this.invalid.set(false);
    this.saveError.set(null);
    this.saving.set(true);
    this.service.changePaymentTerms(this.client().id, days).subscribe({
      next: (client) => {
        this.saving.set(false);
        this.changed.emit(client);
      },
      error: () => {
        this.saving.set(false);
        this.saveError.set('Could not save the payment terms. Please try again.');
      },
    });
  }
}
