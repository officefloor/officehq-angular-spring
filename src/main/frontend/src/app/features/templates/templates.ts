import { Component, effect, inject, signal, untracked } from '@angular/core';
import { AMOUNT } from '../../core/claims';
import { Session } from '../../core/session';
import { Templates } from '../../core/templates';

// The sheet's claim templates and a form to save one: a blank name, or an amount the claim form
// would refuse, is refused; saving a name again replaces that template. Each person's row adds a
// DRAFT claim from one.
@Component({
  selector: 'app-templates',
  template: `
    <section aria-labelledby="templates-heading">
      <h2 id="templates-heading">Claim templates</h2>
      <form (submit)="save($event)" aria-label="Save a claim template">
        <label for="template-form-name">Name</label>
        <input id="template-form-name" data-testid="template-form-name" [value]="name()" (input)="name.set(value($event)); error.set(null)" />
        <label for="template-form-amount">Amount</label>
        <input id="template-form-amount" data-testid="template-form-amount" [value]="amount()" (input)="amount.set(value($event)); error.set(null)" />
        <label for="template-form-note">Note</label>
        <input id="template-form-note" data-testid="template-form-note" [value]="note()" (input)="note.set(value($event)); error.set(null)" />
        <button type="submit" data-testid="template-form-submit">Save template</button>
        @if (error(); as message) {
          <p role="alert" data-testid="template-form-error">{{ message }}</p>
        }
      </form>
      <table aria-label="Claim templates">
        <thead>
          <tr><th scope="col">Name</th><th scope="col">Amount</th><th scope="col">Note</th></tr>
        </thead>
        <tbody>
          @for (t of templates.list(); track t.id) {
            <tr [attr.data-testid]="'template-row-' + t.name">
              <td data-testid="template-name">{{ t.name }}</td>
              <td data-testid="template-amount">{{ t.amount }}</td>
              <td data-testid="template-note">{{ t.note }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class TemplatesList {
  private readonly session = inject(Session);
  protected readonly templates = inject(Templates);

  protected readonly name = signal('');
  protected readonly amount = signal('');
  protected readonly note = signal('');
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  constructor() {
    // A sheet load clears the refusal left on the last one.
    effect(() => {
      this.session.sheet();
      this.session.loads();
      untracked(() => this.error.set(null));
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.name().trim();
    const amount = this.amount().trim();
    if (name === '') {
      this.error.set('Type a name for the template.');
      return;
    }
    if (!AMOUNT.test(amount) || amount.startsWith('-')) {
      this.error.set('Type an amount of 0.00 or more with up to two decimals.');
      return;
    }
    this.busy = true;
    try {
      await this.templates.save(sheet, { name, amount, note: this.note() });
      this.name.set('');
      this.amount.set('');
      this.note.set('');
    } catch {
      this.error.set(`The template ${name} could not be saved.`);
    } finally {
      this.busy = false;
    }
  }
}
