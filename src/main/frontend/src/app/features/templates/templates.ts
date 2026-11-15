import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { FigurePipe } from '../../core/figure.pipe';
import { Session } from '../../core/session';
import { AMOUNT_PATTERN } from '../claims/claims-api';
import { TemplatesApi, templateError } from './templates-api';

// The sheet's claim templates and the form that saves one; a person's row adds a claim from one.
@Component({
  selector: 'app-templates',
  imports: [FigurePipe, ReactiveFormsModule],
  template: `
    <section aria-labelledby="templates-heading">
      <h2 id="templates-heading">Claim templates</h2>
      <form [formGroup]="form" (ngSubmit)="save()" aria-label="Save a claim template">
        <label for="template-form-name">Name</label>
        <input id="template-form-name" data-testid="template-form-name" formControlName="name" autocomplete="off" />
        <label for="template-form-amount">Amount</label>
        <input id="template-form-amount" data-testid="template-form-amount" formControlName="amount"
          inputmode="decimal" autocomplete="off" />
        <label for="template-form-note">Note</label>
        <input id="template-form-note" data-testid="template-form-note" formControlName="note" autocomplete="off" />
        <button type="submit" data-testid="template-form-submit">Save template</button>
        @if (error(); as err) {
          <p role="alert" data-testid="template-form-error">{{ err }}</p>
        }
      </form>
      <ul aria-label="Claim templates" data-testid="templates-list">
        @for (t of session.templates(); track t.name) {
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
export class Templates {
  protected readonly session = inject(Session);
  private readonly api = inject(TemplatesApi);

  protected readonly error = signal<string | null>(null);
  private saving = false;
  /** Guards against a slower, older read landing over a newer one. */
  private readSeq = 0;

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.session.templates.set([]);
        this.error.set(null);
        this.form.reset({ name: '', amount: '', note: '' }, { emitEvent: false });
        if (id) void this.read(id);
      });
    });
  }

  protected async save(): Promise<void> {
    if (this.saving) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.form.getRawValue();
    const name = value.name.trim();
    const amount = value.amount.trim();
    if (name === '') {
      this.error.set('Enter a name for the template.');
      return;
    }
    if (!AMOUNT_PATTERN.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more with up to two decimals.');
      return;
    }
    this.saving = true;
    try {
      const templates = await this.api.save(sheetId, { name, amount, note: value.note.trim() });
      if (this.session.sheetId() === sheetId) {
        this.readSeq++;
        this.session.templates.set(templates);
      }
      this.form.reset({ name: '', amount: '', note: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(templateError(e));
    } finally {
      this.saving = false;
    }
  }

  private async read(sheetId: string): Promise<void> {
    const seq = ++this.readSeq;
    try {
      const templates = await this.api.list(sheetId);
      if (seq === this.readSeq && this.session.sheetId() === sheetId) this.session.templates.set(templates);
    } catch {
      if (seq === this.readSeq) this.error.set('Something went wrong loading templates; try again.');
    }
  }
}
