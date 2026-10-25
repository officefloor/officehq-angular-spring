import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';

// The date this screen shows the sheet as of, set and cleared here. While it is set, everything on
// the screen is as it stood at the end of that date, and every change is refused; other users'
// screens are unaffected.
@Component({
  selector: 'app-sheet-as-of',
  imports: [ReactiveFormsModule],
  template: `
    @if (session.sheet()) {
      <form [formGroup]="form" (ngSubmit)="set()" aria-label="Show the sheet as of a date">
        <span>As of: <strong data-testid="sheet-asof">{{ session.asOf() ?? '' }}</strong></span>
        <label for="asof-date">Show as of</label>
        <input id="asof-date" type="date" data-testid="asof-date" formControlName="date" />
        <button type="submit" data-testid="asof-set" [disabled]="busy()">Set as-of</button>
        <button type="button" data-testid="asof-clear" [disabled]="busy()" (click)="clear()">Clear as-of</button>
        @if (error()) {
          <p role="alert" data-testid="asof-error">{{ error() }}</p>
        }
      </form>
    }
  `,
})
export class SheetAsOf {
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
      await this.session.setAsOf(date);
    } catch {
      this.error.set('The sheet could not be shown as of that date.');
    } finally {
      this.busy.set(false);
    }
  }
}
