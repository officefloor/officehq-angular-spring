import { Component, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Client, ClientService } from './client.service';

// Clients page: add a client (name + email) and list all clients, filterable by name.
@Component({
  selector: 'app-clients',
  imports: [ReactiveFormsModule],
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
            Enter a valid email address.
          </p>
        }
      </div>
      <button type="submit" data-testid="client-form-submit" [disabled]="saving()">Add client</button>
      @if (saveError()) {
        <p role="alert" data-testid="client-form-error">{{ saveError() }}</p>
      }
    </form>

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
      @if (filteredClients().length === 0) {
        <p role="status" data-testid="clients-no-match">No clients match your search.</p>
      } @else {
        <table data-testid="clients-table">
          <caption>All clients</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Email</th>
            </tr>
          </thead>
          <tbody>
            @for (c of filteredClients(); track c.id) {
              <tr [attr.data-testid]="'client-row-' + c.id">
                <td data-testid="client-name">{{ c.name }}</td>
                <td data-testid="client-email">{{ c.email }}</td>
              </tr>
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
  // Case-insensitive name filter; an empty query shows every client.
  protected readonly filteredClients = computed(() => {
    const q = this.query().trim().toLowerCase();
    const list = this.clients();
    return q ? list.filter((c) => c.name.toLowerCase().includes(q)) : list;
  });
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

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
  });

  constructor() {
    this.load();
  }

  protected onSearch(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  protected showError(field: 'name' | 'email'): boolean {
    const control = this.form.controls[field];
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, email } = this.form.getRawValue();
    this.saving.set(true);
    this.saveError.set(null);
    this.service.create({ name: name.trim(), email: email.trim() }).subscribe({
      next: (created) => {
        this.clients.update((list) => [...list, created]);
        this.form.reset();
        this.saving.set(false);
      },
      error: () => {
        this.saveError.set('Could not save the client. Please try again.');
        this.saving.set(false);
      },
    });
  }

  private load(): void {
    this.service.list().subscribe((list) => this.clients.set(list));
  }
}
