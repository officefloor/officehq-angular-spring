import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { TemplatesService } from './templates.service';

const AMOUNT = /^-?\d{1,10}(\.\d{1,2})?$/;

// The sheet's claim templates, one line each by name, and the form that saves one (a blank name, or an
// amount the claim form would refuse, is refused; saving a name again replaces that template).
@Component({
  selector: 'app-templates',
  template: `
    <section aria-labelledby="templates-heading">
      <h2 id="templates-heading">Claim templates</h2>
      <form (submit)="submit($event)" aria-label="Claim template" data-testid="template-form">
        <label for="template-form-name">Name</label>
        <input id="template-form-name" data-testid="template-form-name" [value]="name()" (input)="name.set(value($event)); error.set(null)" />
        <label for="template-form-amount">Amount</label>
        <input
          id="template-form-amount"
          data-testid="template-form-amount"
          inputmode="decimal"
          [value]="amount()"
          (input)="amount.set(value($event)); error.set(null)"
        />
        <label for="template-form-note">Note</label>
        <input id="template-form-note" data-testid="template-form-note" [value]="note()" (input)="note.set(value($event)); error.set(null)" />
        <button type="submit" data-testid="template-form-submit">Save template</button>
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="template-form-error">{{ message }}</p>
        }
      </form>
      @if (service.templates().length > 0) {
        <ul data-testid="template-list">
          @for (t of service.templates(); track t.id) {
            <li [attr.data-testid]="'template-row-' + t.name">
              <span data-testid="template-name">{{ t.name }}</span>
              <span data-testid="template-amount">{{ t.amount }}</span>
              <span data-testid="template-note">{{ t.note }}</span>
            </li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    li span {
      margin-right: 0.75rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class Templates {
  private readonly session = inject(Session);
  protected readonly service = inject(TemplatesService);

  protected readonly name = signal('');
  protected readonly amount = signal('');
  protected readonly note = signal('');
  protected readonly error = signal<string | null>(null);
  private saving = false;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.name.set('');
      this.amount.set('');
      this.note.set('');
      this.error.set(null);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.name().trim();
    const amount = this.amount().trim();
    if (name === '') {
      this.error.set('Type a name.');
      return;
    }
    if (!AMOUNT.test(amount)) {
      this.error.set('Type an amount with up to two decimals.');
      return;
    }
    this.saving = true;
    try {
      await this.service.save(sheet, { name, amount, note: this.note() });
      if (this.session.sheetId() === sheet) {
        this.name.set('');
        this.amount.set('');
        this.note.set('');
      }
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('That template could not be saved.');
    } finally {
      this.saving = false;
    }
  }
}
