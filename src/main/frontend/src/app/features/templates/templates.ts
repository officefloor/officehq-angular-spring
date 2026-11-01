import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ClaimTemplate, Session } from '../../core/session';

// The sheet's claim templates, each name once: saving a name again replaces its amount and note. A
// person's row makes a DRAFT claim from one with the amount and note it has at that moment.
@Component({
  selector: 'app-templates',
  imports: [FigurePipe, ReactiveFormsModule],
  template: `
    <section aria-labelledby="templates-heading">
      <h2 id="templates-heading">Claim templates</h2>
      <form [formGroup]="form" (ngSubmit)="save()" aria-label="Save a claim template">
        <label for="template-form-name">Template name</label>
        <input id="template-form-name" data-testid="template-form-name" formControlName="name" />
        <label for="template-form-amount">Amount</label>
        <input id="template-form-amount" data-testid="template-form-amount" formControlName="amount" inputmode="decimal" />
        <label for="template-form-note">Note</label>
        <input id="template-form-note" data-testid="template-form-note" formControlName="note" />
        <button type="submit" data-testid="template-form-submit">Save template</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="template-form-error">{{ error() }}</p>
        }
      </form>
      <ul aria-label="Claim templates">
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
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.session.templates.set([]);
      this.error.set('');
      if (id) void this.load(id);
    });
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/templates`;
  }

  private async load(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.get<ClaimTemplate[]>(this.base(sheet), { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.session.templates.set(list);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the claim templates of this sheet.');
    }
  }

  /** Save a template under its trimmed name; a blank name or an amount the claim form would refuse is refused. */
  protected async save(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.form.getRawValue();
    const name = raw.name.trim();
    const amount = raw.amount.trim();
    if (name === '' || name.length > 200) {
      this.error.set('Enter a template name (at most 200 characters).');
      return;
    }
    if (!/^\d{1,12}(\.\d{1,2})?$/.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more, with at most two decimals.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<ClaimTemplate>(this.base(sheet), { name, amount, note: raw.note.trim() }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.session.templates.update((list) =>
        [...list.filter((t) => t.name !== saved.name), saved].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)));
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.error.set(e instanceof HttpErrorResponse && e.status === 403
        ? 'Your role may not save a claim template on this sheet.'
        : 'Could not save that claim template.');
    } finally {
      this.submitting = false;
    }
  }
}
