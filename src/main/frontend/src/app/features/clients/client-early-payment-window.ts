import { Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { Client, ClientService } from './client.service';

// Part of a client's payment terms: the number of days after issue within which paying an invoice earns its
// early-payment discount, with a form to set it (within the payment terms) or remove it.
@Component({
  selector: 'app-client-early-payment-window',
  template: `
    <p>
      Early-payment window:
      @if (client().earlyPaymentWindowDays !== null) {
        <span data-testid="client-early-payment-window">{{ client().earlyPaymentWindowDays }} days</span>
      } @else {
        <span data-testid="client-early-payment-window-none">None agreed</span>
      }
    </p>
    @if (maxDays() !== null) {
      <form (submit)="$event.preventDefault(); save()">
        <label for="client-early-payment-window-input">Early-payment window (days, blank for none)</label>
        <input
          id="client-early-payment-window-input"
          type="number"
          min="0"
          [max]="maxDays()"
          step="1"
          data-testid="client-early-payment-window-input"
          [value]="days()"
          (input)="days.set($any($event.target).value)"
          [attr.aria-invalid]="invalid()"
          [attr.aria-describedby]="invalid() ? 'client-early-payment-window-invalid' : null"
        />
        <button type="submit" data-testid="client-early-payment-window-save" [disabled]="saving()">Save early-payment window</button>
        @if (invalid()) {
          <p id="client-early-payment-window-invalid" role="alert" data-testid="client-early-payment-window-invalid">
            The early-payment window must be a whole number of days from 0 to {{ maxDays() }}, within the payment terms.
          </p>
        }
        @if (saveError()) {
          <p role="alert" data-testid="client-early-payment-window-error">{{ saveError() }}</p>
        }
      </form>
    } @else {
      <p data-testid="client-early-payment-window-needs-terms">Agree payment terms to set an early-payment window.</p>
    }
  `,
})
export class ClientEarlyPaymentWindow {
  private readonly service = inject(ClientService);

  readonly client = input.required<Client>();
  /** Emits the client once their early-payment window has been saved. */
  readonly changed = output<Client>();

  /** The longest window allowed: the client's payment terms; null when they have none. */
  protected readonly maxDays = computed(() => this.client().paymentTermsDays);
  protected readonly days = linkedSignal(() => this.client().earlyPaymentWindowDays?.toString() ?? '');
  protected readonly invalid = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected save(): void {
    const max = this.maxDays();
    const text = this.days().toString().trim();
    const days = text === '' ? null : Number(text);
    if (max === null || (days !== null && (!Number.isInteger(days) || days < 0 || days > max))) {
      this.invalid.set(true);
      return;
    }
    this.invalid.set(false);
    this.saveError.set(null);
    this.saving.set(true);
    this.service.changeEarlyPaymentWindow(this.client().id, days).subscribe({
      next: (client) => {
        this.saving.set(false);
        this.changed.emit(client);
      },
      error: () => {
        this.saving.set(false);
        this.saveError.set('Could not save the early-payment window. Please try again.');
      },
    });
  }
}
