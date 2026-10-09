import { Component, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Contact, ContactService } from './contact.service';

type ContactField = 'name' | 'email' | 'role';

// A client's contacts: add a contact (name, email, role) and list the contacts kept for that client.
@Component({
  selector: 'app-client-contacts',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="client-contacts-heading" data-testid="client-contacts">
      <h2 id="client-contacts-heading">Contacts</h2>

      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="contact-form" novalidate>
        <div>
          <label for="contact-name">Name</label>
          <input
            id="contact-name"
            type="text"
            formControlName="name"
            autocomplete="name"
            data-testid="contact-form-name"
            [attr.aria-invalid]="showError('name')"
            [attr.aria-describedby]="showError('name') ? 'contact-name-error' : null"
          />
          @if (showError('name')) {
            <p id="contact-name-error" role="alert" data-testid="contact-form-name-error">
              Name is required.
            </p>
          }
        </div>
        <div>
          <label for="contact-email">Email</label>
          <input
            id="contact-email"
            type="email"
            formControlName="email"
            autocomplete="email"
            data-testid="contact-form-email"
            [attr.aria-invalid]="showError('email')"
            [attr.aria-describedby]="showError('email') ? 'contact-email-error' : null"
          />
          @if (showError('email')) {
            <p id="contact-email-error" role="alert" data-testid="contact-form-email-error">
              Enter a valid email address.
            </p>
          }
        </div>
        <div>
          <label for="contact-role">Role</label>
          <input
            id="contact-role"
            type="text"
            formControlName="role"
            autocomplete="organization-title"
            data-testid="contact-form-role"
            [attr.aria-invalid]="showError('role')"
            [attr.aria-describedby]="showError('role') ? 'contact-role-error' : null"
          />
          @if (showError('role')) {
            <p id="contact-role-error" role="alert" data-testid="contact-form-role-error">
              Role is required.
            </p>
          }
        </div>
        <button type="submit" data-testid="contact-form-submit" [disabled]="saving()">
          Add contact
        </button>
        @if (saveError()) {
          <p role="alert" data-testid="contact-form-error">{{ saveError() }}</p>
        }
      </form>

      @if (contacts.error()) {
        <p role="alert" data-testid="client-contacts-error">Could not load the contacts.</p>
      } @else if (contacts.hasValue()) {
        @if (contacts.value().length === 0) {
          <p data-testid="client-contacts-empty">No contacts for this client yet.</p>
        } @else {
          @if (primaryError()) {
            <p role="alert" data-testid="contact-primary-error">{{ primaryError() }}</p>
          }
          <table data-testid="client-contacts-table">
            <caption>Contacts for this client</caption>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Email</th>
                <th scope="col">Role</th>
                <th scope="col">Main contact</th>
              </tr>
            </thead>
            <tbody>
              @for (c of contacts.value(); track c.id) {
                <tr [attr.data-testid]="'contact-row-' + c.id">
                  <td data-testid="contact-name">{{ c.name }}</td>
                  <td data-testid="contact-email">{{ c.email }}</td>
                  <td data-testid="contact-role">{{ c.role }}</td>
                  <td>
                    <button
                      type="button"
                      [attr.data-testid]="'contact-primary-' + c.id"
                      [attr.aria-pressed]="c.primary"
                      [attr.aria-label]="c.primary ? c.name + ' is the main contact' : 'Make ' + c.name + ' the main contact'"
                      [disabled]="c.primary || choosingPrimary()"
                      (click)="makePrimary(c)"
                    >
                      {{ c.primary ? 'Main contact' : 'Make main contact' }}
                    </button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      }
    </section>
  `,
})
export class ClientContacts {
  private readonly service = inject(ContactService);

  readonly clientId = input.required<number>();
  /** Emits each contact once it has been saved. */
  readonly contactAdded = output<Contact>();
  /** Emits the contact that has just become the client's main contact. */
  readonly primaryChanged = output<Contact>();

  protected readonly contacts = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.listForClient(params),
  });
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly choosingPrimary = signal(false);
  protected readonly primaryError = signal<string | null>(null);

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
    role: ['', [Validators.required, Validators.maxLength(255)]],
  });

  protected showError(field: ContactField): boolean {
    const control = this.form.controls[field];
    return control.invalid && (control.touched || control.dirty);
  }

  protected makePrimary(contact: Contact): void {
    this.choosingPrimary.set(true);
    this.primaryError.set(null);
    this.service.makePrimary(this.clientId(), contact.id).subscribe({
      next: (updated) => {
        this.contacts.update((list) => list?.map((c) => ({ ...c, primary: c.id === updated.id })));
        this.primaryChanged.emit(updated);
        this.choosingPrimary.set(false);
      },
      error: () => {
        this.primaryError.set('Could not change the main contact. Please try again.');
        this.choosingPrimary.set(false);
      },
    });
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, email, role } = this.form.getRawValue();
    this.saving.set(true);
    this.saveError.set(null);
    this.service
      .create(this.clientId(), { name: name.trim(), email: email.trim(), role: role.trim() })
      .subscribe({
        next: (created) => {
          this.contacts.update((list) => [...(list ?? []), created]);
          this.contactAdded.emit(created);
          this.form.reset();
          this.saving.set(false);
        },
        error: () => {
          this.saveError.set('Could not save the contact. Please try again.');
          this.saving.set(false);
        },
      });
  }
}
