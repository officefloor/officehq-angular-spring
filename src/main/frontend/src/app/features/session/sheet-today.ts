import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';

// The active sheet's today, shown and changeable. Every date decision and default date in the app
// uses it, never the real clock.
@Component({
  selector: 'app-sheet-today',
  imports: [ReactiveFormsModule],
  template: `
    @if (session.sheet(); as sheet) {
      <form [formGroup]="form" (ngSubmit)="save()" aria-label="Sheet today">
        <span>Today: <strong data-testid="sheet-today">{{ sheet.today }}</strong></span>
        <label for="sheet-today-input">Change today</label>
        <input id="sheet-today-input" type="date" data-testid="sheet-today-input" formControlName="today" />
        <button type="submit" data-testid="sheet-today-save" [disabled]="saving()">Set today</button>
        @if (error()) {
          <p role="alert" data-testid="sheet-error">{{ error() }}</p>
        }
      </form>
    }
  `,
})
export class SheetToday {
  protected readonly session = inject(Session);

  protected readonly today = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ today: this.today });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.today.valueChanges.subscribe(() => this.error.set(''));
    // A freshly loaded sheet starts the field at its today and clears any error from the last one.
    effect(() => {
      const sheet = this.session.sheet();
      this.error.set('');
      this.today.setValue(sheet?.today ?? '', { emitEvent: false });
    });
  }

  protected async save(): Promise<void> {
    const today = this.today.value.trim();
    this.error.set('');
    if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
      this.error.set('Type a date as yyyy-mm-dd.');
      return;
    }
    this.saving.set(true);
    try {
      await this.session.setToday(today);
    } catch {
      this.error.set("The sheet's today could not be changed.");
    } finally {
      this.saving.set(false);
    }
  }
}
