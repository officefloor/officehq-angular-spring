import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, switchMap } from 'rxjs';
import { AMOUNT_PATTERN } from '../../core/claims';
import { Sheets } from '../../core/sheet';
import { Templates } from '../../core/templates';

// The sheet's claim templates: each person's row adds a DRAFT claim from one of them.
@Component({
  selector: 'app-templates',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="templates-heading">
      <h3 id="templates-heading">Claim templates</h3>
      <ul aria-label="Claim templates">
        @for (t of templates.all(); track t.id) {
          <li [attr.data-testid]="'template-row-' + t.name">
            <span data-testid="template-name">{{ t.name }}</span>:
            <span data-testid="template-amount">{{ t.amount }}</span>
            <span data-testid="template-note">{{ t.note }}</span>
          </li>
        }
      </ul>

      <form [formGroup]="form" (ngSubmit)="save()" aria-label="Save a claim template">
        <label for="template-form-name">Name</label>
        <input id="template-form-name" data-testid="template-form-name" formControlName="name" autocomplete="off" />
        <label for="template-form-amount">Amount</label>
        <input id="template-form-amount" data-testid="template-form-amount" formControlName="amount"
               inputmode="decimal" autocomplete="off" />
        <label for="template-form-note">Note</label>
        <input id="template-form-note" data-testid="template-form-note" formControlName="note" autocomplete="off" />
        <button type="submit" data-testid="template-form-submit" [disabled]="saving()">Save template</button>
        @if (error(); as err) {
          <p role="alert" data-testid="template-form-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class TemplatesPanel {
  protected readonly templates = inject(Templates);
  private readonly sheets = inject(Sheets);

  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its templates from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.error.set(null);
          return this.templates.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.error.set('Could not read the claim templates.') });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected save(): void {
    // A second submit before the first has answered is ignored, so it records one save.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.form.getRawValue();
    const name = value.name.trim();
    const amount = value.amount.trim();
    if (name === '') {
      this.error.set('Type the template\'s name.');
      return;
    }
    if (!AMOUNT_PATTERN.test(amount)) {
      this.error.set('Type an amount of zero or more with up to two decimals.');
      return;
    }
    this.saving.set(true);
    this.templates.save(sheet.id, { name, amount, note: value.note }).subscribe({
      next: () => {
        this.saving.set(false);
        this.form.reset({ name: '', amount: '', note: '' }, { emitEvent: false });
      },
      error: () => {
        this.saving.set(false);
        this.error.set('Could not save that template.');
      },
    });
  }
}
