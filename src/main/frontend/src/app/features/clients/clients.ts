import { HttpErrorResponse } from '@angular/common/http';
import { CurrencyPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ClientEditForm } from './client-edit-form';
import { Client, ClientService } from './client.service';

// Clients page: add a client (name + email, optionally a phone number) and list all clients, filterable by name; each client
// opens its detail page. A client no longer worked with can be archived: it is kept but left off the
// list and search unless the archived toggle is on, where it can be restored. A client's name or email
// can be corrected in place from its row. The list can be sorted by name or by how much each client owes.
@Component({
  selector: 'app-clients',
  imports: [CurrencyPipe, ReactiveFormsModule, RouterLink, ClientEditForm],
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
      <button type="submit" data-testid="client-form-submit" [disabled]="saving()">Add client</button>
      @if (saveError()) {
        <p role="alert" data-testid="client-form-error">{{ saveError() }}</p>
      }
    </form>

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
                <td data-testid="client-name">
                  {{ c.name }}
                  @if (c.archived) {
                    <span [attr.data-testid]="'client-archived-' + c.id">(archived)</span>
                  }
                </td>
                <td data-testid="client-email">{{ c.email }}</td>
                <td data-testid="client-outstanding">{{ c.outstanding | currency: c.currency : 'symbol' : '1.2-2' : 'en-US' }}</td>
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

  protected showError(field: 'name' | 'email' | 'phone'): boolean {
    const control = this.form.controls[field];
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, email, phone } = this.form.getRawValue();
    this.saving.set(true);
    this.saveError.set(null);
    this.service.create({ name: name.trim(), email: email.trim(), phone: phone.trim() || null }).subscribe({
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

type ClientSort = 'added' | 'name' | 'outstanding';

const byName = (a: Client, b: Client) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });

/** Clients in the given order: as added, by name A-Z, or those owing the most first (then by name). */
function sortClients(list: Client[], sort: ClientSort): Client[] {
  switch (sort) {
    case 'name':
      return [...list].sort(byName);
    case 'outstanding':
      return [...list].sort((a, b) => b.outstanding - a.outstanding || byName(a, b));
    default:
      return list;
  }
}
