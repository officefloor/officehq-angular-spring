import { CurrencyPipe } from '@angular/common';
import { Component, computed, inject, input, signal, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ClientContacts } from '../contacts/client-contacts';
import { Contact } from '../contacts/contact.service';
import { ClientCredit } from '../credit/client-credit';
import { ClientDeposits } from '../deposits/client-deposits';
import { ClientPaymentForm, PaymentSource } from '../payments/client-payment';
import { ClientProjects } from '../projects/client-projects';
import { ClientCurrency } from './client-currency';
import { Client, ClientService } from './client.service';

// A single client's page: their name, email, phone number, tax number, billing address, main contact and currency, a link to their statement, a form to record a lump payment split across their invoices or to put their held deposits toward those invoices the same way, the credit they have to spend (unused deposits and credit notes), the deposits they have paid up front, counts of their projects and contacts, the total ever billed to them, their contacts, and the projects being done for them.
@Component({
  selector: 'app-client-detail',
  imports: [CurrencyPipe, RouterLink, ClientCurrency, ClientContacts, ClientProjects, ClientPaymentForm, ClientDeposits, ClientCredit],
  styles: `
    .client-badges {
      display: flex;
      gap: 1rem;
      padding: 0;
      list-style: none;
    }
    .client-billing-address {
      white-space: pre-line;
    }
  `,
  template: `
    <a routerLink="/clients" data-testid="client-back">Back to clients</a>
    @if (client.error()) {
      <p role="alert" data-testid="client-error">Could not load the client.</p>
    } @else if (client.value(); as c) {
      <h1 data-testid="client-detail-name">{{ c.name }}</h1>
      <p>Email: <span data-testid="client-detail-email">{{ c.email }}</span></p>
      <p>
        Phone:
        @if (c.phone) {
          <a [href]="'tel:' + c.phone" data-testid="client-phone">{{ c.phone }}</a>
        } @else {
          <span data-testid="client-phone-none">Not given</span>
        }
      </p>
      <p>
        Tax number:
        @if (c.taxNumber) {
          <span data-testid="client-tax-number">{{ c.taxNumber }}</span>
        } @else {
          <span data-testid="client-tax-number-none">Not tax registered</span>
        }
      </p>
      <p>
        Billing address:
        @if (c.billingAddress) {
          <span class="client-billing-address" data-testid="client-billing-address">{{ c.billingAddress }}</span>
        } @else {
          <span data-testid="client-billing-address-none">Not given</span>
        }
      </p>
      <p>
        Prices: <span data-testid="client-tax-mode">{{ c.taxInclusive ? 'Include tax' : 'Tax added on' }}</span>
      </p>
      <p>
        Tax exempt: <span data-testid="client-tax-exempt">{{ c.taxExempt ? 'Yes' : 'No' }}</span>
      </p>
      <p>
        Key account: <span data-testid="client-key-account-flag">{{ c.keyAccount ? 'Yes' : 'No' }}</span>
      </p>
      <p>
        Standard discount: <span data-testid="client-default-discount">{{ c.defaultDiscountPct > 0 ? c.defaultDiscountPct + '%' : 'None' }}</span>
      </p>
      <p>
        Main contact:
        @if (c.primaryContact; as primary) {
          <span data-testid="client-primary-contact">{{ primary.name }}</span>
        } @else {
          <span data-testid="client-primary-contact-none">None chosen</span>
        }
      </p>
      <app-client-currency [client]="c" (changed)="currencyChanged($event)" />
      @if (summary.hasValue()) {
        <ul class="client-badges" aria-label="At a glance" data-testid="client-badges">
          <li>
            Jobs: <span data-testid="client-projects-count">{{ summary.value().projectCount }}</span>
          </li>
          <li>
            Contacts: <span data-testid="client-contacts-count">{{ summary.value().contactCount }}</span>
          </li>
          <li>
            Billed to date:
            <span data-testid="client-lifetime-billed">{{
              summary.value().lifetimeBilled | currency: c.currency : 'symbol' : '1.2-2' : 'en-US'
            }}</span>
          </li>
        </ul>
      }
      <p>
        <a [routerLink]="['/clients', clientId(), 'statement']" data-testid="client-statement-open">View statement</a>
      </p>
      <p>
        <button
          type="button"
          data-testid="client-record-payment"
          aria-controls="client-payment-panel"
          [attr.aria-expanded]="recordingPayment() === 'payment'"
          (click)="toggle('payment')"
        >
          {{ recordingPayment() === 'payment' ? 'Close payment form' : 'Record a payment' }}
        </button>
        <button
          type="button"
          data-testid="client-allocate-deposit"
          aria-controls="client-payment-panel"
          [attr.aria-expanded]="recordingPayment() === 'deposit'"
          (click)="toggle('deposit')"
        >
          {{ recordingPayment() === 'deposit' ? 'Close deposit form' : 'Put a deposit toward invoices' }}
        </button>
      </p>
      <div id="client-payment-panel">
        @if (recordingPayment(); as source) {
          <app-client-payment [clientId]="clientId()" [source]="source" (recorded)="paymentRecorded(source)" />
        }
        @if (paymentSaved()) {
          <p role="status" data-testid="client-payment-recorded">Payment recorded.</p>
        }
        @if (depositApplied()) {
          <p role="status" data-testid="client-deposit-applied">Deposit applied.</p>
        }
      </div>
      <app-client-credit [clientId]="clientId()" [currency]="c.currency" />
      <app-client-deposits [clientId]="clientId()" [currency]="c.currency" />
      <app-client-contacts
        [clientId]="clientId()"
        (contactAdded)="contactAdded()"
        (primaryChanged)="primaryChanged($event)"
      />
      <app-client-projects [clientId]="clientId()" />
    }
  `,
})
export class ClientDetail {
  private readonly service = inject(ClientService);

  /** Bound from the `:id` route parameter. */
  readonly id = input.required<string>();
  protected readonly clientId = computed(() => Number(this.id()));

  protected readonly client = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.get(params),
  });

  protected readonly summary = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.summary(params),
  });

  /** Which split form is open, if any: a payment received, or the client's held deposits. */
  protected readonly recordingPayment = signal<PaymentSource | null>(null);
  protected readonly paymentSaved = signal(false);
  protected readonly depositApplied = signal(false);
  private readonly paymentForm = viewChild(ClientPaymentForm);
  private readonly depositsPanel = viewChild(ClientDeposits);
  private readonly creditPanel = viewChild(ClientCredit);

  /** Resolves to true once any payment being recorded has been saved, so the page can be left safely. */
  async canLeave(): Promise<boolean> {
    await this.paymentForm()?.settled();
    return true;
  }

  protected toggle(source: PaymentSource): void {
    this.recordingPayment.update((open) => (open === source ? null : source));
  }

  protected paymentRecorded(source: PaymentSource): void {
    this.recordingPayment.set(null);
    this.paymentSaved.set(source === 'payment');
    this.depositApplied.set(source === 'deposit');
    if (source === 'deposit') {
      this.depositsPanel()?.reload();
      this.creditPanel()?.reload();
    }
    this.client.reload();
  }

  protected contactAdded(): void {
    this.summary.reload();
    // The first contact added becomes the main contact.
    if (!this.client.value()?.primaryContact) {
      this.client.reload();
    }
  }

  protected currencyChanged(client: Client): void {
    this.client.set(client);
  }

  protected primaryChanged(contact: Contact): void {
    this.client.update((c) => c && { ...c, primaryContact: { id: contact.id, name: contact.name } });
  }
}
