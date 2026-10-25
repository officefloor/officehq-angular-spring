import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { CLAIM_AMOUNT } from '../claims/claims-api';
import { Templates } from './templates-api';

// The sheet's claim templates, with a form to save one. Saving a name again replaces its template.
@Component({
  selector: 'app-templates',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="templates-heading">
      <h2 id="templates-heading">Claim templates</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Save a claim template">
        <label for="template-form-name">Name</label>
        <input id="template-form-name" data-testid="template-form-name" formControlName="name" />
        <label for="template-form-amount">Amount</label>
        <input id="template-form-amount" data-testid="template-form-amount" formControlName="amount" inputmode="decimal" />
        <label for="template-form-note">Note</label>
        <input id="template-form-note" data-testid="template-form-note" formControlName="note" />
        <button type="submit" data-testid="template-form-submit" [disabled]="saving()">Save template</button>
        @if (error() || store.loadError(); as message) {
          <p role="alert" data-testid="template-form-error">{{ message }}</p>
        }
      </form>
      <ul aria-label="Claim templates">
        @for (t of store.templates(); track t.id) {
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
export class TemplatesList {
  private readonly session = inject(Session);
  protected readonly store = inject(Templates);

  protected readonly name = new FormControl('', { nonNullable: true });
  protected readonly amount = new FormControl('', { nonNullable: true });
  protected readonly note = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ name: this.name, amount: this.amount, note: this.note });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const value = this.form.getRawValue();
    const name = value.name.trim();
    const amount = value.amount.trim();
    if (name === '') {
      this.error.set('Type a template name.');
      return;
    }
    // A template's amount is one the claim form takes without naming a claim it corrects.
    if (!CLAIM_AMOUNT.test(amount) || Number(amount) < 0) {
      this.error.set('Type an amount of zero or more with up to two decimals.');
      return;
    }
    this.saving.set(true);
    try {
      await this.store.save(sheet.id, { name, amount, note: value.note.trim() });
      if (this.session.sheet()?.id === sheet.id) this.form.reset({ name: '', amount: '', note: '' }, { emitEvent: false });
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(status === 403 ? 'Your role may not save a claim template on this sheet.'
        : status === 422 ? 'Type a template name, and an amount of zero or more with up to two decimals.'
        : 'The claim template could not be saved.');
    } finally {
      this.saving.set(false);
    }
  }
}
