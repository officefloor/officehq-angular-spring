import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Client, ClientService, byName } from './client.service';

// The workspace's clients: listed A to Z by name (ignoring capitals), with a form to add one.
@Component({
  selector: 'app-clients',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="clients-heading">
      <h2 id="clients-heading">Clients</h2>

      <ul data-testid="client-list">
        @for (c of clients(); track c.id) {
          <li [attr.data-testid]="'client-row-' + c.id">
            <span data-testid="client-name">{{ c.name }}</span>
            <span data-testid="client-email">{{ c.email }}</span>
          </li>
        } @empty {
          <li data-testid="home-empty">No clients yet.</li>
        }
      </ul>

      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a client">
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
        <button type="submit" data-testid="client-form-submit" [disabled]="saving()">
          Add client
        </button>
        @if (error()) {
          <p role="alert" data-testid="client-form-error">{{ error() }}</p>
        }
      </form>
    </section>
  `,
})
export class Clients {
  private readonly service = inject(ClientService);

  protected readonly clients = signal<Client[]>([]);
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    email: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.email],
    }),
  });

  constructor() {
    this.service.list().subscribe((list) => this.clients.set(list));
  }

  protected add(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.error.set('Enter a name and a valid email.');
      return;
    }
    this.saving.set(true);
    this.error.set('');
    this.service.add(this.form.getRawValue()).subscribe({
      next: (created) => {
        this.clients.update((list) => [...list, created].sort(byName));
        this.form.reset();
        this.saving.set(false);
      },
      error: () => {
        this.error.set('The client could not be added.');
        this.saving.set(false);
      },
    });
  }
}
