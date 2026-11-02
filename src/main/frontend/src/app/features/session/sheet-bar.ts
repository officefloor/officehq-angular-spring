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
    @if (session.sheetId()) {
      <p>Today: <strong data-testid="sheet-today">{{ session.today() ?? '' }}</strong></p>
      <form data-testid="sheet-today-form" [formGroup]="todayForm" (ngSubmit)="saveToday()">
        <label for="sheet-today-input">Sheet's today</label>
        <input id="sheet-today-input" type="date" data-testid="sheet-today-input" formControlName="today" />
        <button type="submit" data-testid="sheet-today-save">Set today</button>
      </form>
      @if (sheetError()) {
        <p role="alert" data-testid="sheet-error">{{ sheetError() }}</p>
      }
    }
  `,
})
export class SheetBar {
  protected readonly session = inject(Session);
  protected readonly form = new FormGroup({ sheetId: new FormControl('', { nonNullable: true }) });
  protected readonly error = signal('');
  protected readonly todayForm = new FormGroup({ today: new FormControl('', { nonNullable: true }) });
  protected readonly sheetError = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.todayForm.valueChanges.subscribe(() => this.sheetError.set(''));
  }

  async load(): Promise<void> {
    this.error.set('');
    const id = this.form.controls.sheetId.value.trim();
    if (!id) {
      this.error.set('Enter a sheet id to load.');
      return;
    }
    this.sheetError.set('');
    try {
      await this.session.loadSheet(id);
    } catch {
      this.error.set('The sheet could not be loaded.');
    }
  }

  async saveToday(): Promise<void> {
    this.sheetError.set('');
    const today = this.todayForm.controls.today.value.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
      this.sheetError.set('Enter a date for the sheet\'s today.');
      return;
    }
    try {
      await this.session.setToday(today);
    } catch {
      this.sheetError.set('The sheet\'s today could not be changed.');
    }
  }
}
