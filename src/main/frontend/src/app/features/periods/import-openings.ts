import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { PeriodView, PeriodsApi } from './periods-api';

// A box that takes lines of name,period,amount and adds each amount to the named person's opening
// balance for the named period, part of their carried figure for it. Every line is checked first, and
// when any is refused nothing is changed.
@Component({
  selector: 'app-import-openings',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Paste opening balances">
      <label for="opening-import">Paste opening balances, one per line: name,period,amount</label>
      <textarea id="opening-import" data-testid="opening-import" formControlName="text" rows="4"></textarea>
      <button type="submit" data-testid="opening-import-submit" [disabled]="saving()">Add opening balances</button>
      @if (count() !== null) {
        <p>Went through: <strong data-testid="opening-import-done">{{ count() }}</strong></p>
      }
      @if (error()) {
        <p role="alert" data-testid="opening-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class ImportOpenings {
  private readonly session = inject(Session);
  private readonly api = inject(PeriodsApi);

  /** The sheet's periods, as they stand once the balances are added. */
  readonly pasted = output<PeriodView[]>();

  protected readonly form = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly count = signal<number | null>(null);

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    // Each load of a sheet starts the box afresh.
    effect(() => {
      this.session.loaded();
      this.error.set('');
      this.count.set(null);
      this.form.reset({ text: '' }, { emitEvent: false });
    });
  }

  protected async submit(): Promise<void> {
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    this.count.set(null);
    const text = this.form.getRawValue().text;
    if (!text.trim()) {
      this.error.set('Paste at least one line of name,period,amount.');
      return;
    }
    this.saving.set(true);
    try {
      const result = await this.api.pasteOpenings(sheet.id, text);
      if (this.session.sheet()?.id === sheet.id) {
        this.count.set(result.added);
        this.pasted.emit(result.periods);
      }
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const reason = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' && e.error.message
        ? ' ' + e.error.message : '';
      this.error.set('No opening balances were added: a line was refused.' + reason);
    } finally {
      this.saving.set(false);
    }
  }
}
