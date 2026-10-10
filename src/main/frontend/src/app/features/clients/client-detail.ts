import { Component, computed, inject, input, signal, viewChild } from '@angular/core';
import { MoneyPipe } from '../currencies/money.pipe';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { ClientContacts } from '../contacts/client-contacts';
import { ClientContactHistory } from '../contact-history/client-contact-history';
import { Notes } from '../notes/notes';
import { Contact } from '../contacts/contact.service';
import { ClientCredit } from '../credit/client-credit';
import { ClientAging } from '../invoices/client-aging';
import { ClientFinancialSummaryPanel } from '../invoices/client-financial-summary';
import { ClientDeposits } from '../deposits/client-deposits';
import { ClientPaymentForm, PaymentSource } from '../payments/client-payment';
import { ClientRemittances } from '../payments/client-remittances';
import { ClientProjects } from '../projects/client-projects';
import { ClientCreditLimit } from './client-credit-limit';
import { ClientCurrency } from './client-currency';
import { ClientPaymentTerms } from './client-payment-terms';
import { ClientEarlyPaymentWindow } from './client-early-payment-window';
import { ClientExport } from './client-export';
import { ClientMerge } from './client-merge';
import { Client, ClientService } from './client.service';

// A single client's page: their name, email, phone number, preferred language, account manager, billing contact, tax number, billing address, main contact, currency, credit limit and payment terms, an export of their contact details to a file, a link to their statement, how old their debt is, a one-screen financial summary (billed, paid, outstanding and overdue), a form to record a lump payment split across their invoices or to put their held deposits toward those invoices the same way, the payments they have made with a remittance note listing the invoices each covered, the credit they have to spend (unused deposits and credit notes) with a form to refund it, the deposits they have paid up front, counts of their projects and contacts, the total ever billed to them (net of credits and write-offs), their lifetime value (what they have actually paid, less refunds), their contacts, the history of when they were contacted (newest first), the projects being done for them, and a form to merge this client into a duplicate of it.
@Component({
  selector: 'app-client-detail',
  imports: [MoneyPipe, RouterLink, ClientCurrency, ClientCreditLimit, ClientPaymentTerms, ClientEarlyPaymentWindow, ClientContacts, ClientContactHistory, Notes, ClientProjects, ClientPaymentForm, ClientRemittances, ClientDeposits, ClientCredit, ClientMerge, ClientExport, ClientAging, ClientFinancialSummaryPanel],
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
        Preferred language:
        @if (c.language) {
          <span data-testid="client-language">{{ c.language }}</span>
        } @else {
          <span data-testid="client-language-none">Not given</span>
        }
      </p>
      <p>
        Account manager:
        @if (c.accountManager) {
          <span data-testid="client-account-manager">{{ c.accountManager }}</span>
        } @else {
          <span data-testid="client-account-manager-none">Not recorded</span>
        }
      </p>
      <p>
        Billing contact:
        @if (c.billingContact) {
          <span data-testid="client-billing-contact">{{ c.billingContact }}</span>
        } @else {
          <span data-testid="client-billing-contact-none">Same as client</span>
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
      <app-client-export [clientId]="clientId()" />
      <app-client-currency [client]="c" (changed)="currencyChanged($event)" />
      <app-client-credit-limit [client]="c" (changed)="creditLimitChanged($event)" />
      <app-client-payment-terms [client]="c" (changed)="paymentTermsChanged($event)" />
      <app-client-early-payment-window [client]="c" (changed)="paymentTermsChanged($event)" />
      @if (summary.hasValue()) {
        <ul class="client-badges" aria-label="At a glance" data-testid="client-badges">
          <li>
            Jobs: <span data-testid="client-projects-count">{{ summary.value().projectCount }}</span>
          </li>
          <li>
            Contacts: <span data-testid="client-contacts-count">{{ summary.value().contactCount }}</span>
          </li>
          <li>
            Billed to date (net of credits and write-offs):
            <span data-testid="client-lifetime-billed">{{
              summary.value().lifetimeBilled | money: c.currency
            }}</span>
          </li>
          <li>
            Lifetime value:
            <span data-testid="client-lifetime-value">{{ summary.value().lifetimeValue | money: c.currency }}</span>
          </li>
        </ul>
      }
      <p>
        <a [routerLink]="['/clients', clientId(), 'statement']" data-testid="client-statement-open">View statement</a>
      </p>
      <p>
        <button
          type="button"
          data-testid="client-summary-open"
          aria-controls="client-summary-panel"
          [attr.aria-expanded]="showingSummary()"
          (click)="showingSummary.set(!showingSummary())"
        >
          {{ showingSummary() ? 'Hide financial summary' : 'Show financial summary' }}
        </button>
      </p>
      <div id="client-summary-panel">
        @if (showingSummary()) {
          <app-client-financial-summary [clientId]="clientId()" />
        }
      </div>
      <app-client-aging [clientId]="clientId()" />
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
      <app-client-remittances [clientId]="clientId()" [currency]="c.currency" />
      <app-client-credit [clientId]="clientId()" [currency]="c.currency" (refunded)="creditRefunded()" />
      <app-client-deposits [clientId]="clientId()" [currency]="c.currency" />
      <app-client-contacts
        [clientId]="clientId()"
        (contactAdded)="contactAdded()"
        (primaryChanged)="primaryChanged($event)"
        (contactArchived)="contactArchived($event)"
      />
      <app-client-contact-history [clientId]="clientId()" />
      <app-notes [clientId]="clientId()" />
      <app-client-projects [clientId]="clientId()" />
      <app-client-merge [client]="c" (merged)="clientMerged($event)" />
    }
  `,
})
export class ClientDetail {
  private readonly service = inject(ClientService);
  private readonly router = inject(Router);

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

  /** Whether the billed, paid, outstanding and overdue summary is shown. */
  protected readonly showingSummary = signal(false);
  private readonly financialSummaryPanel = viewChild(ClientFinancialSummaryPanel);

  /** Which split form is open, if any: a payment received, or the client's held deposits. */
  protected readonly recordingPayment = signal<PaymentSource | null>(null);
  protected readonly paymentSaved = signal(false);
  protected readonly depositApplied = signal(false);
  private readonly paymentForm = viewChild(ClientPaymentForm);
  private readonly depositsPanel = viewChild(ClientDeposits);
  private readonly remittancesPanel = viewChild(ClientRemittances);
  private readonly creditPanel = viewChild(ClientCredit);
  private readonly agingPanel = viewChild(ClientAging);
  private readonly mergePanel = viewChild(ClientMerge);
  /** Set once the page is being left, so a merge finishing meanwhile does not pull the user back. */
  private leaving = false;

  /** Resolves to true once any payment being recorded, or merge under way, has finished, so the page can be left safely. */
  async canLeave(): Promise<boolean> {
    this.leaving = true;
    await Promise.all([this.paymentForm()?.settled(), this.mergePanel()?.settled()]);
    this.leaving = false;
    return true;
  }

  protected toggle(source: PaymentSource): void {
    this.recordingPayment.update((open) => (open === source ? null : source));
  }

  protected paymentRecorded(source: PaymentSource): void {
    this.recordingPayment.set(null);
    this.paymentSaved.set(source === 'payment');
    this.depositApplied.set(source === 'deposit');
    // A payment may use up the client's credit or leave some over as credit, so both panels can change.
    this.depositsPanel()?.reload();
    this.creditPanel()?.reload();
    this.agingPanel()?.reload();
    this.financialSummaryPanel()?.reload();
    this.remittancesPanel()?.reload();
    this.summary.reload();
    this.client.reload();
  }

  protected creditRefunded(): void {
    this.depositsPanel()?.reload();
    this.summary.reload();
  }

  protected contactAdded(): void {
    this.summary.reload();
    // The first contact added becomes the main contact.
    if (!this.client.value()?.primaryContact) {
      this.client.reload();
    }
  }

  protected contactArchived(contact: Contact): void {
    this.summary.reload();
    // An archived contact is no longer the main contact.
    if (this.client.value()?.primaryContact?.id === contact.id) {
      this.client.update((c) => c && { ...c, primaryContact: null });
    }
  }

  protected currencyChanged(client: Client): void {
    this.client.set(client);
  }

  protected creditLimitChanged(client: Client): void {
    this.client.set(client);
  }

  protected paymentTermsChanged(client: Client): void {
    this.client.set(client);
  }

  protected clientMerged(kept: Client): void {
    // This client no longer exists; carry on at the one it was merged into, unless the user is already leaving.
    if (this.leaving) {
      return;
    }
    void this.router.navigate(['/clients', kept.id]);
  }

  protected primaryChanged(contact: Contact): void {
    this.client.update((c) => c && { ...c, primaryContact: { id: contact.id, name: contact.name } });
  }
}
