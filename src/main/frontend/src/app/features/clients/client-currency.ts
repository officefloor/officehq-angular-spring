import { Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { Client, ClientService, CurrencyCode } from './client.service';
import { CurrencyService } from '../currencies/currency.service';

// The currency a client is billed in, with a picker to change it. All of the client's money is shown in it.
@Component({
  selector: 'app-client-currency',
  template: `
    <p>
      Currency: <span data-testid="client-currency">{{ client().currency }}</span>
    </p>
    <form (submit)="$event.preventDefault(); save()">
      <label for="client-currency-select">Bill this client in</label>
      <select
        id="client-currency-select"
        data-testid="client-currency-select"
        [value]="selected()"
        (change)="selected.set($any($event.target).value)"
      >
        @for (code of currencies(); track code) {
          <option [value]="code" [selected]="code === selected()">{{ code }}</option>
        }
      </select>
      <button type="submit" data-testid="client-currency-save" [disabled]="saving()">Save currency</button>
      @if (saveError()) {
        <p role="alert" data-testid="client-currency-error">{{ saveError() }}</p>
      }
    </form>
  `,
})
export class ClientCurrency {
  private readonly service = inject(ClientService);

  readonly client = input.required<Client>();
  /** Emits the client once their currency has been saved. */
  readonly changed = output<Client>();

  private readonly currencyService = inject(CurrencyService);

  // The known currencies, always including the client's own even before the list loads.
  protected readonly currencies = computed(() => {
    const codes = this.currencyService.list().map((c) => c.code);
    return codes.includes(this.client().currency) ? codes : [this.client().currency, ...codes];
  });
  protected readonly selected = linkedSignal<CurrencyCode>(() => this.client().currency);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected save(): void {
    this.saveError.set(null);
    this.saving.set(true);
    this.service.changeCurrency(this.client().id, this.selected()).subscribe({
      next: (client) => {
        this.saving.set(false);
        this.changed.emit(client);
      },
      error: () => {
        this.saving.set(false);
        this.saveError.set('Could not change the currency. Please try again.');
      },
    });
  }
}
