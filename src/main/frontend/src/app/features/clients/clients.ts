import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Client, ClientService, byName } from './client.service';

// A proper email address: a mailbox, an @, and a domain with at least one dot — name@company.com,
// not name@company (a typo). The same rule the server applies.
const PROPER_EMAIL = /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/;

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
      validators: [Validators.required, Validators.pattern(PROPER_EMAIL)],
    }),
  });

  constructor() {
    this.service.list().subscribe((list) => this.clients.set(list));
  }

  protected add(): void {
    const name = this.form.controls.name.value.trim();
    const email = this.form.controls.email.value.trim();
    this.form.setValue({ name, email });
    const problem = this.problemWith(name, email);
    if (problem) {
      this.form.markAllAsTouched();
      this.error.set(problem);
      return;
    }
    this.saving.set(true);
    this.error.set('');
    this.service.add({ name, email }).subscribe({
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

  // What is wrong with the client, or '' when it can be saved. A duplicate email is checked here
  // for a quick answer; the server is the authority.
  private problemWith(name: string, email: string): string {
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
    return problems.join(' ');
  }
}
