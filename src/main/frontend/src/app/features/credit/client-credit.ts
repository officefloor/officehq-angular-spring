import { CurrencyPipe } from '@angular/common';
import { Component, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { CurrencyCode } from '../clients/client.service';
import { CreditService } from './credit.service';

// How much credit a client has to spend: their unused deposits and credit notes added up.
@Component({
  selector: 'app-client-credit',
  imports: [CurrencyPipe],
  template: `
    @if (credit.error()) {
      <p role="alert" data-testid="client-available-credit-error">Could not load the client's available credit.</p>
    } @else if (credit.hasValue()) {
      <p>
        Available credit:
        <span data-testid="client-available-credit">{{ credit.value().total | currency: currency() : 'symbol' : '1.2-2' : 'en-US' }}</span>
      </p>
    }
  `,
})
export class ClientCredit {
  private readonly service = inject(CreditService);

  readonly clientId = input.required<number>();
  /** The client's currency; their credit is held in it. */
  readonly currency = input.required<CurrencyCode>();

  protected readonly credit = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.available(params),
  });

  /** Loads the credit again, e.g. after deposits were put toward invoices elsewhere on the page. */
  reload(): void {
    this.credit.reload();
  }
}
