import { Component, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ClientInvoices } from '../invoices/client-invoices';
import { CURRENCIES, Currency, money } from '../invoices/invoice-format';
import { ClientBalance, InvoiceService } from '../invoices/invoice.service';
import { Client, ClientService, byName } from './client.service';

// A proper email address: a mailbox, an @, and a domain with at least one dot — name@company.com,
// not name@company (a typo). The same rule the server applies.
const PROPER_EMAIL = /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/;
// The grace period, in days past the due date, a client is given when none is set: a week.
const STANDARD_GRACE_DAYS = 7;

// The workspace's clients: listed A to Z by name (ignoring capitals), with a form to add one.
// Clicking a client lists that client's invoices beside the clients. Typing an invoice number into
// the lookup box selects its client and opens the invoice; a mistyped number opens nothing.
@Component({
  selector: 'app-clients',
  imports: [ReactiveFormsModule, ClientInvoices],
  styles: `
    :host {
      display: flex;
      flex-wrap: wrap;
      gap: 2rem;
      align-items: flex-start;
    }
    .client {
      display: flex;
      gap: 1rem;
      width: 100%;
      text-align: left;
      font: inherit;
      color: inherit;
      background: none;
      border: 1px solid transparent;
      padding: 0.25rem 0.5rem;
      cursor: pointer;
    }
    .client[aria-pressed='true'] {
      border-color: currentColor;
      font-weight: bold;
    }
  `,
  template: `
    <section aria-labelledby="clients-heading">
      <form [formGroup]="lookup" (ngSubmit)="findInvoice()" aria-label="Open an invoice" novalidate>
        <label for="invoice-lookup">Invoice number</label>
        <input
          id="invoice-lookup"
          data-testid="invoice-lookup-input"
          formControlName="number"
          placeholder="INV-0000075"
          required
        />
        <button type="submit" data-testid="invoice-lookup-submit" [disabled]="looking()">
          Open invoice
        </button>
        @if (lookupError()) {
          <p role="alert" data-testid="invoice-lookup-error">{{ lookupError() }}</p>
        }
      </form>

      <h2 id="clients-heading">Clients</h2>

      <ul data-testid="client-list">
        @for (c of clients(); track c.id) {
          <li [attr.data-testid]="'client-row-' + c.id" (click)="selectClient(c.id)">
            <button
              type="button"
              class="client"
              [attr.aria-pressed]="selectedId() === c.id"
              (click)="$event.stopPropagation(); selectClient(c.id)"
            >
              <span data-testid="client-name">{{ c.name }}</span>
              <span data-testid="client-email">{{ c.email }}</span>
              <span>
                Owes <span data-testid="client-balance">{{ money(balanceOf(c.id).balance, c.currency) }}</span>,
                overdue <span data-testid="client-overdue">{{ money(balanceOf(c.id).overdue, c.currency) }}</span>,
                credit <span data-testid="client-credit">{{ money(balanceOf(c.id).credit, c.currency) }}</span>
              </span>
            </button>
          </li>
        } @empty {
          <li data-testid="home-empty">No clients yet.</li>
        }
      </ul>

      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a client" novalidate>
        <label for="client-name">Name</label>
        <input id="client-name" data-testid="client-form-name" formControlName="name" required />
        <label for="client-email">Email</label>
        <input
          id="client-email"
          data-testid="client-form-email"
          type="email"
          formControlName="email"
          required
        />
        <label for="client-terms">Payment terms in days (optional)</label>
        <input
          id="client-terms"
          data-testid="client-form-terms"
          type="number"
          inputmode="numeric"
          min="1"
          max="365"
          step="1"
          formControlName="terms"
        />
        <label for="client-discount">Standard discount % (optional)</label>
        <input
          id="client-discount"
          data-testid="client-form-discount-pct"
          type="number"
          inputmode="decimal"
          min="0"
          max="100"
          step="0.01"
          formControlName="discountPct"
        />
        <label for="client-retention">Standard retention % (optional)</label>
        <input
          id="client-retention"
          data-testid="client-form-retention-pct"
          type="number"
          inputmode="decimal"
          min="0"
          max="20"
          step="0.01"
          formControlName="retentionPct"
        />
        <label for="client-grace">Grace period in days, 0 to 30 (optional, a week if left out)</label>
        <input
          id="client-grace"
          data-testid="client-form-grace-days"
          type="number"
          inputmode="numeric"
          min="0"
          max="30"
          step="1"
          formControlName="graceDays"
        />
        <label for="client-early-pct">Early-payment discount % (optional)</label>
        <input
          id="client-early-pct"
          data-testid="client-form-early-pct"
          type="number"
          inputmode="decimal"
          min="0"
          max="100"
          step="0.01"
          formControlName="earlyPct"
        />
        <label for="client-early-days">Paid within days of issue for the early discount</label>
        <input
          id="client-early-days"
          data-testid="client-form-early-days"
          type="number"
          inputmode="numeric"
          min="1"
          max="365"
          step="1"
          formControlName="earlyDays"
        />
        <label for="client-currency">Currency</label>
        <select id="client-currency" data-testid="client-form-currency" formControlName="currency">
          @for (code of currencies; track code) {
            <option [value]="code">{{ code }}</option>
          }
        </select>
        <input
          id="client-tax-exempt"
          data-testid="client-form-tax-exempt"
          type="checkbox"
          formControlName="taxExempt"
        />
        <label for="client-tax-exempt">Tax exempt (no tax on any of their invoices)</label>
        <button type="submit" data-testid="client-form-submit" [disabled]="saving()">
          Add client
        </button>
        @if (error()) {
          <p role="alert" data-testid="client-form-error">{{ error() }}</p>
        }
      </form>
    </section>

    @if (selected(); as client) {
      <app-client-invoices
        [clientId]="client.id"
        [clientName]="client.name"
        [currency]="client.currency"
        [openInvoice]="openInvoice()"
        [picked]="picked()"
        (changed)="loadBalances()"
      />
    }
  `,
})
export class Clients {
  private readonly service = inject(ClientService);
  private readonly invoiceService = inject(InvoiceService);

  protected readonly clients = signal<Client[]>([]);
  // What each client owes, by client id; a client not listed owes nothing.
  protected readonly balances = signal<Map<number, ClientBalance>>(new Map());
  protected readonly money = money;
  protected readonly currencies = CURRENCIES;
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly selectedId = signal<number | null>(null);
  protected readonly selected = computed(
    () => this.clients().find((c) => c.id === this.selectedId()) ?? null,
  );

  protected readonly looking = signal(false);
  protected readonly lookupError = signal('');
  protected readonly openInvoice = signal<{ id: number; clientId: number } | null>(null);
  // Bumped each time a client is picked from the list, so its invoices are shown with none open.
  protected readonly picked = signal(0);
  protected readonly lookup = new FormGroup({
    number: new FormControl('', { nonNullable: true }),
  });

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    email: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(PROPER_EMAIL)],
    }),
    terms: new FormControl<number | null>(null),
    discountPct: new FormControl<number | null>(null),
    retentionPct: new FormControl<number | null>(null),
    graceDays: new FormControl<number | null>(null),
    earlyPct: new FormControl<number | null>(null),
    earlyDays: new FormControl<number | null>(null),
    taxExempt: new FormControl(false, { nonNullable: true }),
    currency: new FormControl<Currency>('USD', { nonNullable: true }),
  });

  constructor() {
    this.service.list().subscribe((list) => this.clients.set(list));
    this.loadBalances();
  }

  protected balanceOf(clientId: number): ClientBalance {
    return this.balances().get(clientId) ?? { clientId, balance: '0', overdue: '0', credit: '0' };
  }

  // Fetches what every client owes again, after a payment or other change to an invoice.
  protected loadBalances(): void {
    this.invoiceService
      .balances()
      .subscribe((list) => this.balances.set(new Map(list.map((b) => [b.clientId, b]))));
  }

  protected add(): void {
    const name = this.form.controls.name.value.trim();
    const email = this.form.controls.email.value.trim();
    const terms = this.form.controls.terms.value;
    const termsDays = terms === null || (terms as unknown) === '' ? null : Number(terms);
    const discount = this.form.controls.discountPct.value;
    const discountPct = discount === null || (discount as unknown) === '' ? null : Number(discount);
    const retention = this.form.controls.retentionPct.value;
    const retentionPct = retention === null || (retention as unknown) === '' ? null : Number(retention);
    const grace = this.form.controls.graceDays.value;
    const graceDays = grace === null || (grace as unknown) === '' ? null : Number(grace);
    const taxExempt = this.form.controls.taxExempt.value;
    const currency = this.form.controls.currency.value;
    const early = this.form.controls.earlyPct.value;
    const earlyPayPct = early === null || (early as unknown) === '' ? null : Number(early);
    const earlyDays = this.form.controls.earlyDays.value;
    const earlyPayDays = earlyDays === null || (earlyDays as unknown) === '' ? null : Number(earlyDays);
    this.form.setValue({
      name,
      email,
      terms: termsDays,
      discountPct,
      retentionPct,
      graceDays,
      earlyPct: earlyPayPct,
      earlyDays: earlyPayDays,
      taxExempt,
      currency,
    });
    const problem = this.problemWith(
      name,
      email,
      termsDays,
      discountPct,
      retentionPct,
      graceDays,
      earlyPayPct,
      earlyPayDays,
    );
    if (problem) {
      this.form.markAllAsTouched();
      this.error.set(problem);
      return;
    }
    this.saving.set(true);
    this.error.set('');
    const client = {
      name,
      email,
      termsDays,
      discountPct: discountPct ?? 0,
      taxExempt,
      currency,
      earlyPayPct: earlyPayPct ?? 0,
      earlyPayDays,
      retentionPct: retentionPct ?? 0,
      graceDays: graceDays ?? STANDARD_GRACE_DAYS,
    };
    this.service.add(client).subscribe({
      next: (created) => {
        this.clients.update((list) => [...list, created].sort(byName));
        this.form.reset();
        this.saving.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.error.set(
          typeof detail === 'string' && detail ? detail : 'The client could not be added.',
        );
        this.saving.set(false);
      },
    });
  }

  protected selectClient(id: number): void {
    this.openInvoice.set(null);
    this.selectedId.set(id);
    this.picked.update((n) => n + 1);
  }

  // Opens the invoice with the typed number and selects its client. The server checks the number's
  // check digit and refuses a mistyped one.
  protected findInvoice(): void {
    const number = this.lookup.controls.number.value.trim();
    if (!number) {
      this.lookupError.set('Enter an invoice number, like INV-0000075.');
      return;
    }
    this.looking.set(true);
    this.lookupError.set('');
    this.invoiceService.openByNumber(number).subscribe({
      next: (found) => {
        this.selectedId.set(found.clientId);
        this.openInvoice.set({ id: found.id, clientId: found.clientId });
        this.looking.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.lookupError.set(
          typeof detail === 'string' && detail ? detail : 'The invoice could not be opened.',
        );
        this.looking.set(false);
      },
    });
  }

  // What is wrong with the client, or '' when it can be saved. A duplicate email is checked here
  // for a quick answer; the server is the authority.
  private problemWith(
    name: string,
    email: string,
    termsDays: number | null,
    discountPct: number | null,
    retentionPct: number | null,
    graceDays: number | null,
    earlyPayPct: number | null,
    earlyPayDays: number | null,
  ): string {
    const problems: string[] = [];
    if (!name) {
      problems.push("Enter the client's name.");
    }
    if (!email) {
      problems.push("Enter the client's email address.");
    } else if (!PROPER_EMAIL.test(email)) {
      problems.push('Enter a proper email address, like name@company.com.');
    } else if (this.clients().some((c) => c.email.toLowerCase() === email.toLowerCase())) {
      problems.push(`Another client already has the email ${email}.`);
    }
    if (termsDays !== null && !(Number.isInteger(termsDays) && termsDays >= 1 && termsDays <= 365)) {
      problems.push('Payment terms must be from 1 to 365 days.');
    }
    if (discountPct !== null) {
      if (!(Number.isFinite(discountPct) && discountPct >= 0 && discountPct <= 100)) {
        problems.push('The standard discount must be from 0 to 100 percent.');
      } else if (Math.abs(Math.round(discountPct * 100) - discountPct * 100) > 1e-6) {
        problems.push('The standard discount can have at most two decimals.');
      }
    }
    if (retentionPct !== null) {
      if (!(Number.isFinite(retentionPct) && retentionPct >= 0 && retentionPct <= 20)) {
        problems.push('The standard retention must be from 0 to 20 percent.');
      } else if (Math.abs(Math.round(retentionPct * 100) - retentionPct * 100) > 1e-6) {
        problems.push('The standard retention can have at most two decimals.');
      }
    }
    if (graceDays !== null && !(Number.isInteger(graceDays) && graceDays >= 0 && graceDays <= 30)) {
      problems.push('The grace period must be from 0 to 30 days.');
    }
    if (earlyPayPct !== null) {
      if (!(Number.isFinite(earlyPayPct) && earlyPayPct >= 0 && earlyPayPct <= 100)) {
        problems.push('The early-payment discount must be from 0 to 100 percent.');
      } else if (Math.abs(Math.round(earlyPayPct * 100) - earlyPayPct * 100) > 1e-6) {
        problems.push('The early-payment discount can have at most two decimals.');
      }
    }
    if (earlyPayDays !== null && !(Number.isInteger(earlyPayDays) && earlyPayDays >= 1 && earlyPayDays <= 365)) {
      problems.push('The early-payment window must be from 1 to 365 days.');
    }
    if ((earlyPayPct === null || earlyPayPct === 0) !== (earlyPayDays === null)) {
      problems.push('Enter both the early-payment discount and its number of days, or neither.');
    }
    return problems.join(' ');
  }
}
