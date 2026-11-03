import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ClaimTemplates } from '../../core/claim-templates';
import { CLAIM_AMOUNT } from '../../core/claims';
import { FigurePipe } from '../../core/figure';

// The sheet's claim templates and the form a template is saved with; saving a name again replaces
// that template.
@Component({
  selector: 'app-claim-template-form',
  imports: [ReactiveFormsModule, FigurePipe],
  template: `
    <section aria-labelledby="claim-templates-heading">
      <h3 id="claim-templates-heading">Claim templates</h3>
      <form data-testid="template-form" [formGroup]="form" (ngSubmit)="submit()">
        <label for="template-form-name">Template name</label>
        <input id="template-form-name" data-testid="template-form-name" formControlName="name" />
        <label for="template-form-amount">Template amount</label>
        <input id="template-form-amount" data-testid="template-form-amount" formControlName="amount"
          inputmode="decimal" />
        <label for="template-form-note">Template note</label>
        <input id="template-form-note" data-testid="template-form-note" formControlName="note" />
        <button type="submit" data-testid="template-form-submit">Save template</button>
        @if (error()) {
          <p role="alert" data-testid="template-form-error">{{ error() }}</p>
        }
      </form>
      <ul data-testid="templates">
        @for (t of templates.list(); track t.id) {
          <li [attr.data-testid]="'template-row-' + t.name">
            <span data-testid="template-name">{{ t.name }}</span>
            <span data-testid="template-amount">{{ t.amount | figure }}</span>
            <span data-testid="template-note">{{ t.note }}</span>
          </li>
        }
      </ul>
    </section>
  `,
})
export class ClaimTemplateForm {
  protected readonly templates = inject(ClaimTemplates);

  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { name, amount, note } = this.form.getRawValue();
    if (name.trim() === '') {
      this.error.set('Enter a template name.');
      return;
    }
    if (!CLAIM_AMOUNT.test(amount.trim())) {
      this.error.set('Enter an amount with up to two decimals; a negative amount is a correction.');
      return;
    }
    this.submitting = true;
    try {
      await this.templates.save({ name: name.trim(), amount: amount.trim(), note: note.trim() });
      this.form.reset({ name: '', amount: '', note: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'A viewer may not save a claim template.'
          : 'The claim template could not be saved.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
