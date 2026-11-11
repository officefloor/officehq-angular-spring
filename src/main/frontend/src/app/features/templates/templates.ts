import { Component, inject, signal } from '@angular/core';
import { Templates } from '../../core/templates';

// The sheet's claim templates: a name, an amount and a note; saving a name again replaces that template.
@Component({
  selector: 'app-templates',
  template: `
    <section aria-labelledby="templates-heading">
      <h2 id="templates-heading">Claim templates</h2>
      <form (submit)="save($event)" aria-labelledby="templates-heading">
        <label for="template-form-name">Template name</label>
        <input
          id="template-form-name"
          data-testid="template-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="template-form-amount">Template amount</label>
        <input
          id="template-form-amount"
          data-testid="template-form-amount"
          inputmode="decimal"
          [value]="amount()"
          (input)="amount.set(value($event)); error.set(null)"
        />
        <label for="template-form-note">Template note</label>
        <input
          id="template-form-note"
          data-testid="template-form-note"
          [value]="note()"
          (input)="note.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="template-form-submit">Save template</button>
        @if (error(); as message) {
          <p role="alert" data-testid="template-form-error">{{ message }}</p>
        }
      </form>
      @if (templates.list().length > 0) {
        <table>
          <caption class="visually-hidden">Claim templates</caption>
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
      }
    </section>
  `,
})
export class TemplatesList {
  protected readonly templates = inject(Templates);

  protected readonly name = signal('');
  protected readonly amount = signal('');
  protected readonly note = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a save is waiting for its answer: a second save meanwhile is ignored. */
  private saving = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const name = this.name().trim();
    if (name === '') {
      this.error.set('Type a name for the template.');
      return;
    }
    const amount = this.amount().trim();
    if (!/^\d{1,10}(\.\d{1,2})?$/.test(amount)) {
      this.error.set('Type an amount of 0.00 or more with at most two decimals.');
      return;
    }
    const note = this.note().trim();
    if (note.length > 1000) {
      this.error.set('Type a note of at most 1000 characters.');
      return;
    }
    this.saving = true;
    try {
      await this.templates.save({ name, amount, note });
      this.name.set('');
      this.amount.set('');
      this.note.set('');
    } catch {
      this.error.set(`The template ${name} could not be saved.`);
    } finally {
      this.saving = false;
    }
  }
}
