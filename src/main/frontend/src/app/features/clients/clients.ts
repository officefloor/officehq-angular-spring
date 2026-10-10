import { HttpErrorResponse } from '@angular/common/http';
import { MoneyPipe } from '../currencies/money.pipe';
import { Component, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ClientEditForm } from './client-edit-form';
import { ClientSegments } from './client-segments';
import { Client, ClientService } from './client.service';

// Clients page: add a client (name + email, optionally a phone number and tax number) and list all clients, filterable by name; each client
// opens its detail page. A client no longer worked with can be archived: it is kept but left off the
// list and search unless the archived toggle is on, where it can be restored. A client's name or email
// can be corrected in place from its row. Key accounts carry a marker beside their name, and each client shows the segment it is in; a segments panel counts the clients in each segment. The list can be sorted by name, by how much each client owes, or with key accounts first.
@Component({
  selector: 'app-clients',
  imports: [MoneyPipe, ReactiveFormsModule, RouterLink, ClientEditForm, ClientSegments],
  styles: `
    .key-account {
      margin-inline-start: 0.5em;
      padding: 0 0.4em;
      border: 1px solid #7a5200;
      border-radius: 0.25em;
      color: #5c3d00;
      background: #fff4d6;
      font-size: 0.85em;
    }
  `,
  template: `
    <h1>Clients</h1>

    <form [formGroup]="form" (ngSubmit)="submit()" data-testid="client-form" novalidate>
      <div>
        <label for="client-name">Name</label>
        <input
          id="client-name"
          type="text"
          formControlName="name"
          autocomplete="organization"
          data-testid="client-form-name"
          [attr.aria-invalid]="showError('name')"
          [attr.aria-describedby]="showError('name') ? 'client-name-error' : null"
        />
        @if (showError('name')) {
          <p id="client-name-error" role="alert" data-testid="client-form-name-error">
            Name is required.
          </p>
        }
      </div>
      <div>
        <label for="client-email">Email</label>
        <input
          id="client-email"
          type="email"
          formControlName="email"
          autocomplete="email"
          data-testid="client-form-email"
          [attr.aria-invalid]="showError('email')"
          [attr.aria-describedby]="showError('email') ? 'client-email-error' : null"
        />
        @if (showError('email')) {
          <p id="client-email-error" role="alert" data-testid="client-form-email-error">
            @if (form.controls.email.hasError('taken')) {
              A client with this email already exists.
            } @else {
              Enter a valid email address.
            }
          </p>
        }
      </div>
      <div>
        <label for="client-phone">Phone (optional)</label>
        <input
          id="client-phone"
          type="tel"
          formControlName="phone"
          autocomplete="tel"
          data-testid="client-form-phone"
          [attr.aria-invalid]="showError('phone')"
          [attr.aria-describedby]="showError('phone') ? 'client-phone-error' : null"
        />
        @if (showError('phone')) {
          <p id="client-phone-error" role="alert" data-testid="client-form-phone-error">
            Phone number must be 50 characters or fewer.
          </p>
        }
      </div>
      <div>
        <label for="client-language">Preferred language (optional)</label>
        <input
          id="client-language"
          type="text"
          formControlName="language"
          autocomplete="language"
          data-testid="client-form-language"
          [attr.aria-invalid]="showError('language')"
          [attr.aria-describedby]="showError('language') ? 'client-language-error' : null"
        />
        @if (showError('language')) {
          <p id="client-language-error" role="alert" data-testid="client-form-language-error">
            Preferred language must be 50 characters or fewer.
          </p>
        }
      </div>
      <div>
        <label for="client-account-manager">Account manager (optional)</label>
        <input
          id="client-account-manager"
          type="text"
          formControlName="accountManager"
          autocomplete="off"
          data-testid="client-form-account-manager"
          [attr.aria-invalid]="showError('accountManager')"
          [attr.aria-describedby]="showError('accountManager') ? 'client-account-manager-error' : null"
        />
        @if (showError('accountManager')) {
          <p id="client-account-manager-error" role="alert" data-testid="client-form-account-manager-error">
            Account manager must be 255 characters or fewer.
          </p>
        }
      </div>
      <div>
        <label for="client-billing-contact">Billing contact (optional)</label>
        <input
          id="client-billing-contact"
          type="text"
          formControlName="billingContact"
          autocomplete="off"
          data-testid="client-form-billing-contact"
          [attr.aria-invalid]="showError('billingContact')"
          [attr.aria-describedby]="showError('billingContact') ? 'client-billing-contact-error' : null"
        />
        @if (showError('billingContact')) {
          <p id="client-billing-contact-error" role="alert" data-testid="client-form-billing-contact-error">
            Billing contact must be 255 characters or fewer.
          </p>
        }
      </div>
      <div>
        <label for="client-segment">Segment (optional)</label>
        <input
          id="client-segment"
          type="text"
          formControlName="segment"
          autocomplete="off"
          data-testid="client-form-segment"
          [attr.aria-invalid]="showError('segment')"
          [attr.aria-describedby]="showError('segment') ? 'client-segment-error' : null"
        />
        @if (showError('segment')) {
          <p id="client-segment-error" role="alert" data-testid="client-form-segment-error">
            Segment must be 50 characters or fewer.
          </p>
        }
      </div>
      <div>
        <label for="client-tax-number">Tax number (optional)</label>
        <input
          id="client-tax-number"
          type="text"
          formControlName="taxNumber"
          autocomplete="off"
          data-testid="client-form-tax-number"
          [attr.aria-invalid]="showError('taxNumber')"
          [attr.aria-describedby]="showError('taxNumber') ? 'client-tax-number-error' : null"
        />
        @if (showError('taxNumber')) {
          <p id="client-tax-number-error" role="alert" data-testid="client-form-tax-number-error">
            Tax number must be 50 characters or fewer.
          </p>
        }
      </div>
      <div>
        <label for="client-billing-address">Billing address (optional)</label>
        <textarea
          id="client-billing-address"
          rows="3"
          formControlName="billingAddress"
          autocomplete="street-address"
          data-testid="client-form-billing-address"
          [attr.aria-invalid]="showError('billingAddress')"
          [attr.aria-describedby]="showError('billingAddress') ? 'client-billing-address-error' : null"
        ></textarea>
        @if (showError('billingAddress')) {
          <p id="client-billing-address-error" role="alert" data-testid="client-form-billing-address-error">
            Billing address must be 500 characters or fewer.
          </p>
        }
      </div>
      <div>
        <label>
          <input type="checkbox" formControlName="taxInclusive" data-testid="client-form-tax-inclusive" />
          Prices include tax
        </label>
      </div>
      <div>
        <label>
          <input type="checkbox" formControlName="taxExempt" data-testid="client-form-tax-exempt" />
          Tax exempt (no tax on any invoice)
        </label>
      </div>
      <div>
        <label>
          <input type="checkbox" formControlName="keyAccount" data-testid="client-form-key-account" />
          Key account
        </label>
      </div>
      <div>
        <label for="client-default-discount">Standard discount % (applied to new invoices)</label>
        <input
          id="client-default-discount"
          type="number"
          min="0"
          max="100"
          step="0.01"
          formControlName="defaultDiscountPct"
          data-testid="client-form-default-discount"
          [attr.aria-invalid]="showError('defaultDiscountPct')"
          [attr.aria-describedby]="showError('defaultDiscountPct') ? 'client-default-discount-error' : null"
        />
        @if (showError('defaultDiscountPct')) {
          <p id="client-default-discount-error" role="alert" data-testid="client-form-default-discount-error">
            Standard discount must be between 0 and 100.
          </p>
        }
      </div>
      <button type="submit" data-testid="client-form-submit" [disabled]="saving()">Add client</button>
      @if (saveError()) {
        <p role="alert" data-testid="client-form-error">{{ saveError() }}</p>
      }
    </form>

    <div>
      <button
        type="button"
        data-testid="client-segments-open"
        aria-controls="client-segments"
        [attr.aria-expanded]="segmentsOpen()"
        (click)="segmentsOpen.set(!segmentsOpen())"
      >
        Segments
      </button>
      @if (segmentsOpen()) {
        <section id="client-segments" aria-label="Client segments" data-testid="client-segments">
          <app-client-segments />
        </section>
      }
    </div>

    @if (actionError()) {
      <p role="alert" data-testid="client-archive-error">{{ actionError() }}</p>
    }

    <div>
      <input
        id="clients-show-archived"
        type="checkbox"
        data-testid="clients-show-archived"
        [checked]="showArchived()"
        (change)="toggleArchived()"
      />
      <label for="clients-show-archived">Show archived clients</label>
    </div>

    @if (clients().length === 0) {
      <p data-testid="clients-empty">No clients yet.</p>
    } @else {
      <div role="search">
        <label for="client-search">Search clients by name</label>
        <input
          id="client-search"
          type="search"
          autocomplete="off"
          data-testid="client-search"
          [value]="query()"
          (input)="onSearch($event)"
        />
      </div>
      <div>
        <label for="client-sort">Sort by</label>
        <select id="client-sort" data-testid="client-sort" [value]="sort()" (change)="onSort($event)">
          <option value="added">Date added</option>
          <option value="name">Name</option>
          <option value="outstanding">Amount owed</option>
          <option value="key-account">Key accounts first</option>
        </select>
      </div>
      @if (filteredClients().length === 0) {
        <p role="status" data-testid="clients-no-match">No clients match your search.</p>
      } @else {
        <table data-testid="clients-table">
          <caption>All clients</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Email</th>
              <th scope="col">Owed</th>
              <th scope="col"><span class="visually-hidden">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            @for (c of filteredClients(); track c.id) {
              <tr [attr.data-testid]="'client-row-' + c.id">
                <td>
                  <span data-testid="client-name">{{ c.name }}</span>
                  @if (c.keyAccount) {
                    <span class="key-account" data-testid="client-key-account">Key account</span>
                  }
                  @if (c.segment) {
                    <span data-testid="client-segment">({{ c.segment }})</span>
                  }
                  @if (c.archived) {
                    <span [attr.data-testid]="'client-archived-' + c.id">(archived)</span>
                  }
                </td>
                <td data-testid="client-email">{{ c.email }}</td>
                <td data-testid="client-outstanding">{{ c.outstanding | money: c.currency }}</td>
                <td>
                  <a
                    [routerLink]="['/clients', c.id]"
                    [attr.data-testid]="'client-open-' + c.id"
                    [attr.aria-label]="'Open ' + c.name"
                    >Open</a
                  >
                  <button
                    type="button"
                    [attr.data-testid]="'client-edit-' + c.id"
                    [attr.aria-label]="'Edit ' + c.name"
                    [attr.aria-expanded]="editing() === c.id"
                    (click)="edit(c)"
                  >
                    Edit
                  </button>
                  @if (c.archived) {
                    <button
                      type="button"
                      [attr.data-testid]="'client-restore-' + c.id"
                      [attr.aria-label]="'Restore ' + c.name"
                      [disabled]="busy() === c.id"
                      (click)="restore(c)"
                    >
                      Restore
                    </button>
                  } @else {
                    <button
                      type="button"
                      [attr.data-testid]="'client-archive-' + c.id"
                      [attr.aria-label]="'Archive ' + c.name"
                      [disabled]="busy() === c.id"
                      (click)="archive(c)"
                    >
                      Archive
                    </button>
                  }
                </td>
              </tr>
              @if (editing() === c.id) {
                <tr [attr.data-testid]="'client-edit-row-' + c.id">
                  <td colspan="4">
                    <app-client-edit-form [client]="c" (saved)="onSaved($event)" (cancelled)="closeEdit(c)" />
                  </td>
                </tr>
              }
            }
          </tbody>
        </table>
      }
    }
  `,
})
export class Clients {
  private readonly service = inject(ClientService);

  protected readonly clients = signal<Client[]>([]);
  protected readonly query = signal('');
  protected readonly sort = signal<ClientSort>('added');
  // Case-insensitive name filter (an empty query shows every client), in the chosen order.
  protected readonly filteredClients = computed(() => {
    const q = this.query().trim().toLowerCase();
    const list = this.clients();
    const filtered = q ? list.filter((c) => c.name.toLowerCase().includes(q)) : list;
    return sortClients(filtered, this.sort());
  });
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly showArchived = signal(false);
  protected readonly busy = signal<number | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly editing = signal<number | null>(null);
  protected readonly segmentsOpen = signal(false);

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
    language: ['', Validators.maxLength(50)],
    accountManager: ['', Validators.maxLength(255)],
    billingContact: ['', Validators.maxLength(255)],
    segment: ['', Validators.maxLength(50)],
    taxNumber: ['', Validators.maxLength(50)],
    billingAddress: ['', Validators.maxLength(500)],
    taxInclusive: false,
    taxExempt: false,
    keyAccount: false,
    defaultDiscountPct: [0, [Validators.required, Validators.min(0), Validators.max(100)]],
  });

  constructor() {
    this.load();
  }

  protected onSearch(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  protected onSort(event: Event): void {
    this.sort.set((event.target as HTMLSelectElement).value as ClientSort);
  }

  protected showError(field: 'name' | 'email' | 'phone' | 'language' | 'accountManager' | 'billingContact' | 'segment' | 'taxNumber' | 'billingAddress' | 'defaultDiscountPct'): boolean {
    const control = this.form.controls[field];
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, email, phone, language, accountManager, billingContact, segment, taxNumber, billingAddress, taxInclusive, taxExempt, keyAccount, defaultDiscountPct } = this.form.getRawValue();
    this.saving.set(true);
    this.saveError.set(null);
    this.service.create({ name: name.trim(), email: email.trim(), phone: phone.trim() || null, language: language.trim() || null, accountManager: accountManager.trim() || null, billingContact: billingContact.trim() || null, segment: segment.trim() || null, taxNumber: taxNumber.trim() || null, billingAddress: billingAddress.trim() || null, taxInclusive, taxExempt, keyAccount, defaultDiscountPct }).subscribe({
      next: (created) => {
        this.clients.update((list) => [...list, created]);
        this.form.reset();
        this.saving.set(false);
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 409) {
          // The server owns uniqueness; flag the email field until it is changed.
          const email = this.form.controls.email;
          email.setErrors({ taken: true });
          email.markAsTouched();
        } else {
          this.saveError.set('Could not save the client. Please try again.');
        }
        this.saving.set(false);
      },
    });
  }

  protected edit(client: Client): void {
    this.editing.update((id) => (id === client.id ? null : client.id));
  }

  protected onSaved(updated: Client): void {
    this.clients.update((list) => list.map((c) => (c.id === updated.id ? updated : c)));
    this.closeEdit(updated);
  }

  protected closeEdit(client: Client): void {
    this.editing.set(null);
    // Return focus to the row's Edit button once the form is gone.
    setTimeout(() => document.querySelector<HTMLElement>(`[data-testid="client-edit-${client.id}"]`)?.focus());
  }

  protected toggleArchived(): void {
    this.showArchived.update((show) => !show);
    this.load();
  }

  protected archive(client: Client): void {
    this.busy.set(client.id);
    this.actionError.set(null);
    this.service.archive(client.id).subscribe({
      next: (archived) => {
        this.clients.update((list) =>
          this.showArchived()
            ? list.map((c) => (c.id === archived.id ? archived : c))
            : list.filter((c) => c.id !== archived.id),
        );
        this.busy.set(null);
      },
      error: () => {
        this.actionError.set(`Could not archive ${client.name}. Please try again.`);
        this.busy.set(null);
      },
    });
  }

  protected restore(client: Client): void {
    this.busy.set(client.id);
    this.actionError.set(null);
    this.service.restore(client.id).subscribe({
      next: (restored) => {
        // Upsert: the list may have been reloaded without archived clients while the restore was in flight.
        this.clients.update((list) =>
          [...list.filter((c) => c.id !== restored.id), restored].sort((a, b) => a.id - b.id),
        );
        this.busy.set(null);
      },
      error: () => {
        this.actionError.set(`Could not restore ${client.name}. Please try again.`);
        this.busy.set(null);
      },
    });
  }

  private load(): void {
    this.service.list(this.showArchived()).subscribe((list) => this.clients.set(list));
  }
}

type ClientSort = 'added' | 'name' | 'outstanding' | 'key-account';

const byName = (a: Client, b: Client) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });

/** Clients in the given order: as added, by name A-Z, those owing the most first (then by name), or key accounts first (then by name). */
function sortClients(list: Client[], sort: ClientSort): Client[] {
  switch (sort) {
    case 'name':
      return [...list].sort(byName);
    case 'outstanding':
      return [...list].sort((a, b) => b.outstanding - a.outstanding || byName(a, b));
    case 'key-account':
      return [...list].sort((a, b) => Number(b.keyAccount) - Number(a.keyAccount) || byName(a, b));
    default:
      return list;
  }
}
