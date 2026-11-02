import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';

// The signed-in name, and the form that loads a sheet by id.
@Component({
  selector: 'app-sheet-bar',
  imports: [ReactiveFormsModule],
  template: `
    <p>Signed in as <strong data-testid="current-user">{{ session.user() }}</strong></p>
    <form data-testid="sheet-form" [formGroup]="form" (ngSubmit)="load()">
      <label for="sheet-id-input">Sheet id</label>
      <input id="sheet-id-input" data-testid="sheet-id-input" formControlName="sheetId" />
      <button type="submit" data-testid="sheet-load">Load sheet</button>
      @if (error()) {
        <p role="alert" data-testid="sheet-load-error">{{ error() }}</p>
      }
    </form>
    <p>Sheet: <strong data-testid="sheet-current-id">{{ session.sheetId() ?? '' }}</strong></p>
  `,
})
export class SheetBar {
  protected readonly session = inject(Session);
  protected readonly form = new FormGroup({ sheetId: new FormControl('', { nonNullable: true }) });
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  async load(): Promise<void> {
    this.error.set('');
    const id = this.form.controls.sheetId.value.trim();
    if (!id) {
      this.error.set('Enter a sheet id to load.');
      return;
    }
    try {
      await this.session.loadSheet(id);
    } catch {
      this.error.set('The sheet could not be loaded.');
    }
  }
}
