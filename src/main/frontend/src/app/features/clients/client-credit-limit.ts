import { Component, inject, input, linkedSignal, output, signal } from '@angular/core';
import { MoneyPipe } from '../currencies/money.pipe';
import { Client, ClientService } from './client.service';

// The most a client may owe, in their currency, with a form to set it or remove it.
@Component({
  selector: 'app-client-credit-limit',
  imports: [MoneyPipe],
  template: `
    <p>
      Credit limit:
      @if (client().creditLimit !== null) {
        <span data-testid="client-credit-limit">{{ client().creditLimit | money: client().currency }}</span>
      } @else {
        <span data-testid="client-credit-limit-none">No limit</span>
      }
    </p>
    <form (submit)="$event.preventDefault(); save()">
      <label for="client-credit-limit-input">Credit limit ({{ client().currency }}, blank for no limit)</label>
      <input
        id="client-credit-limit-input"
        type="number"
        min="0"
        step="0.01"
        data-testid="client-credit-limit-input"
        [value]="amount()"
        (input)="amount.set($any($event.target).value)"
        [attr.aria-invalid]="invalid()"
        [attr.aria-describedby]="invalid() ? 'client-credit-limit-invalid' : null"
      />
      <button type="submit" data-testid="client-credit-limit-save" [disabled]="saving()">Save credit limit</button>
      @if (invalid()) {
        <p id="client-credit-limit-invalid" role="alert" data-testid="client-credit-limit-invalid">
          Credit limit must be zero or more.
        </p>
      }
      @if (saveError()) {
        <p role="alert" data-testid="client-credit-limit-error">{{ saveError() }}</p>
      }
    </form>
  `,
})
export class ClientCreditLimit {
  private readonly service = inject(ClientService);

  readonly client = input.required<Client>();
  /** Emits the client once their credit limit has been saved. */
  readonly changed = output<Client>();

  protected readonly amount = linkedSignal(() => this.client().creditLimit?.toString() ?? '');
  protected readonly invalid = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected save(): void {
    const text = this.amount().toString().trim();
    const limit = text === '' ? null : Number(text);
    if (limit !== null && (!Number.isFinite(limit) || limit < 0)) {
      this.invalid.set(true);
      return;
    }
    this.invalid.set(false);
    this.saveError.set(null);
    this.saving.set(true);
    this.service.changeCreditLimit(this.client().id, limit).subscribe({
      next: (client) => {
        this.saving.set(false);
        this.changed.emit(client);
      },
      error: () => {
        this.saving.set(false);
        this.saveError.set('Could not save the credit limit. Please try again.');
      },
    });
  }
}
