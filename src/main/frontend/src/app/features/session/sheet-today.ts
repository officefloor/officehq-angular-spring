import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { SheetService } from '../../core/sheet.service';

// Show the active sheet's "today" and change it.
@Component({
  selector: 'app-sheet-today',
  imports: [ReactiveFormsModule],
  template: `
    <p>Today: <span data-testid="sheet-today">{{ sheets.today() }}</span></p>
    <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Change today">
      <label for="sheet-today-input">Today</label>
      <input id="sheet-today-input" type="date" data-testid="sheet-today-input" formControlName="today" />
      <button type="submit" data-testid="sheet-today-save">Set today</button>
      @if (error()) {
        <p role="alert" data-testid="sheet-today-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class SheetToday {
  protected readonly sheets = inject(SheetService);
  protected readonly form = new FormGroup({ today: new FormControl('', { nonNullable: true }) });
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => this.form.setValue({ today: this.sheets.today() ?? '' }, { emitEvent: false }));
  }

  protected submit(): void {
    this.error.set('');
    const today = this.form.controls.today.value.trim();
    if (!isDate(today)) {
      this.error.set('Enter a date (yyyy-mm-dd).');
      return;
    }
    this.sheets.setToday(today).subscribe({ error: () => this.error.set('Could not change today.') });
  }
}

function isDate(s: string): boolean {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
  if (!m) return false;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const days = [31, y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return mo >= 1 && mo <= 12 && d >= 1 && d <= days[mo - 1];
}
