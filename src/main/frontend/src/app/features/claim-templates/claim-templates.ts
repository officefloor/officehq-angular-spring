import { Component, inject, signal } from '@angular/core';
import { ClaimTemplates } from '../../core/claim-templates';

// The sheet's claim templates: a form saves one (saving a name again replaces it; a blank name, or an amount
// the claim form would refuse, is refused), and the list shows them. Each person's row makes a claim from one.
@Component({
  selector: 'app-claim-templates',
  template: `
    <section aria-label="Claim templates" data-testid="templates">
      <h2>Claim templates</h2>
      <form (submit)="save($event)" data-testid="template-form">
        <label for="template-form-name">Name</label>
        <input
          id="template-form-name"
          data-testid="template-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="template-form-amount">Amount</label>
        <input
          id="template-form-amount"
          data-testid="template-form-amount"
          inputmode="decimal"
          [value]="amount()"
          (input)="amount.set(value($event)); error.set(null)"
        />
        <label for="template-form-note">Note</label>
        <input
          id="template-form-note"
          data-testid="template-form-note"
          [value]="note()"
          (input)="note.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="template-form-submit" [disabled]="saving()">Save template</button>
        @if (error(); as err) {
          <p role="alert" data-testid="template-form-error">{{ err }}</p>
        }
      </form>
      <ul data-testid="template-list" aria-label="Claim templates">
        @for (t of templates.templates(); track t.id) {
          <li [attr.data-testid]="'template-row-' + t.name">
            <span data-testid="template-name">{{ t.name }}</span>
            <span data-testid="template-amount">{{ t.amount }}</span>
            <span data-testid="template-note">{{ t.note }}</span>
          </li>
        }
      </ul>
    </section>
  `,
})
export class ClaimTemplatesPanel {
  protected readonly templates = inject(ClaimTemplates);
  protected readonly name = signal('');
  protected readonly amount = signal('');
  protected readonly note = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.templates.save(this.name(), this.amount(), this.note());
      if (err) this.error.set(err);
      else {
        this.name.set('');
        this.amount.set('');
        this.note.set('');
      }
    } finally {
      this.saving.set(false);
    }
  }
}
