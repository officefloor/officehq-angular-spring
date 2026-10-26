import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';

// The preview date of this screen, set and cleared here. While it is set, every figure on the screen
// is shown as if the sheet's today were that date, over the sheet's current records; every change
// still uses the sheet's real today. Other users' screens are unaffected.
@Component({
  selector: 'app-sheet-preview',
  imports: [ReactiveFormsModule],
  template: `
    @if (session.sheet()) {
      <form [formGroup]="form" (ngSubmit)="set()" aria-label="Preview the sheet on a date">
        <span>Preview: <strong data-testid="sheet-preview">{{ session.preview() ?? '' }}</strong></span>
        <label for="preview-date">Preview on</label>
        <input id="preview-date" type="date" data-testid="preview-date" formControlName="date" />
        <button type="submit" data-testid="preview-set" [disabled]="busy()">Set preview</button>
        <button type="button" data-testid="preview-clear" [disabled]="busy()" (click)="clear()">Clear preview</button>
        @if (error()) {
          <p role="alert" data-testid="preview-error">{{ error() }}</p>
        }
      </form>
    }
  `,
})
export class SheetPreview {
  protected readonly session = inject(Session);

  protected readonly date = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ date: this.date });
  protected readonly busy = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.date.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async set(): Promise<void> {
    const date = this.date.value.trim();
    this.error.set('');
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Type a date as yyyy-mm-dd.');
      return;
    }
    await this.show(date);
  }

  protected async clear(): Promise<void> {
    this.error.set('');
    await this.show(null);
  }

  private async show(date: string | null): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    try {
      await this.session.setPreview(date);
    } catch {
      this.error.set('The sheet could not be previewed on that date.');
    } finally {
      this.busy.set(false);
    }
  }
}
