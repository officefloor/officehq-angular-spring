import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { ClaimTemplates } from '../../core/claim-templates';

// The sheet's claim templates and the form that saves one: a name, an amount and a note. A blank
// name, or an amount the claim form would refuse, is refused; saving a name again replaces that
// template. Each person's row adds a DRAFT claim from a chosen template.
@Component({
  selector: 'app-templates',
  template: `
    <section aria-labelledby="templates-heading">
      <h2 id="templates-heading">Claim templates</h2>
      <form aria-label="Claim template" (submit)="save($event)">
        <label for="template-form-name">Name</label>
        <input
          id="template-form-name"
          data-testid="template-form-name"
          [value]="nameInput()"
          (input)="nameInput.set($any($event.target).value); error.set(null)"
        />
        <label for="template-form-amount">Amount</label>
        <input
          id="template-form-amount"
          data-testid="template-form-amount"
          inputmode="decimal"
          [value]="amountInput()"
          (input)="amountInput.set($any($event.target).value); error.set(null)"
        />
        <label for="template-form-note">Note</label>
        <input
          id="template-form-note"
          data-testid="template-form-note"
          [value]="noteInput()"
          (input)="noteInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="template-form-submit">Save template</button>
        @if (error() ?? templates.loadError(); as err) {
          <p role="alert" data-testid="template-form-error">{{ err }}</p>
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
export class Templates {
  private readonly session = inject(Session);
  protected readonly templates = inject(ClaimTemplates);

  protected readonly nameInput = signal('');
  protected readonly amountInput = signal('');
  protected readonly noteInput = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.nameInput.set('');
      this.amountInput.set('');
      this.noteInput.set('');
      this.error.set(null);
    });
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const name = this.nameInput().trim();
    const amount = this.amountInput().trim();
    if (!name) {
      this.error.set('Enter a template name.');
      return;
    }
    if (!AMOUNT.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more with up to two decimals.');
      return;
    }
    const sheet = this.session.sheet();
    this.submitting = true;
    try {
      await this.templates.save(name, amount, this.noteInput().trim());
      if (this.session.sheet() === sheet) {
        this.nameInput.set('');
        this.amountInput.set('');
        this.noteInput.set('');
      }
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role may not save claim templates.'
          : 'Could not save this claim template.',
      );
    } finally {
      this.submitting = false;
    }
  }
}

/** An amount the claim form takes without a correction: 0.00 or more, up to two decimals. */
const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;
