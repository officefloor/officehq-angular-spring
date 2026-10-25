import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';

// Choose the active sheet by typing its id and loading it.
@Component({
  selector: 'app-sheet-picker',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="load()" aria-label="Load a sheet">
      <label for="sheet-id-input">Sheet id</label>
      <input id="sheet-id-input" data-testid="sheet-id-input" formControlName="sheetId" />
      <button type="submit" data-testid="sheet-load" [disabled]="loading()">Load</button>
      @if (session.sheet(); as sheet) {
        <span>Sheet: <strong data-testid="sheet-current-id">{{ sheet.id }}</strong></span>
      }
      @if (error()) {
        <p role="alert" data-testid="sheet-load-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class SheetPicker {
  protected readonly session = inject(Session);

  protected readonly sheetId = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ sheetId: this.sheetId });
  protected readonly loading = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.sheetId.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async load(): Promise<void> {
    const id = this.sheetId.value.trim();
    this.error.set('');
    if (!id) {
      this.error.set('Type a sheet id to load.');
      return;
    }
    this.loading.set(true);
    try {
      await this.session.loadSheet(id);
    } catch {
      this.error.set('The sheet could not be loaded.');
    } finally {
      this.loading.set(false);
    }
  }
}
