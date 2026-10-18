import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { SheetService } from '../../core/sheet.service';

// Choose the active sheet by typing its id and loading it.
@Component({
  selector: 'app-sheet-picker',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Load sheet">
      <label for="sheet-id-input">Sheet id</label>
      <input id="sheet-id-input" data-testid="sheet-id-input" formControlName="id" />
      <button type="submit" data-testid="sheet-load">Load</button>
      @if (error()) {
        <p role="alert" data-testid="sheet-error">{{ error() }}</p>
      }
    </form>
    @if (sheets.sheetId(); as current) {
      <p>Sheet: <span data-testid="sheet-current-id">{{ current }}</span></p>
    }
  `,
})
export class SheetPicker {
  protected readonly sheets = inject(SheetService);
  protected readonly form = new FormGroup({ id: new FormControl('', { nonNullable: true }) });
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected submit(): void {
    this.error.set('');
    const id = this.form.controls.id.value.trim();
    if (!/^[A-Za-z0-9_-]{1,100}$/.test(id)) {
      this.error.set('Enter a sheet id (letters, digits, - or _).');
      return;
    }
    this.sheets.load(id).subscribe({ error: () => this.error.set('Could not load that sheet.') });
  }
}
