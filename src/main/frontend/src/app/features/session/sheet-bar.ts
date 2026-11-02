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
    <p>Sheet: <strong data-testid="sheet-current-id">{{ session.shownId() ?? '' }}</strong></p>
    @if (session.sheetId()) {
      <p>Today: <strong data-testid="sheet-today">{{ session.sheetToday() ?? '' }}</strong></p>
      <form data-testid="sheet-today-form" [formGroup]="todayForm" (ngSubmit)="saveToday()">
        <label for="sheet-today-input">Sheet's today</label>
        <input id="sheet-today-input" type="date" data-testid="sheet-today-input" formControlName="today" />
        <button type="submit" data-testid="sheet-today-save">Set today</button>
      </form>
      <p>Archived: <strong data-testid="sheet-archived">{{ session.archived() ? 'yes' : 'no' }}</strong></p>
      <button type="button" data-testid="sheet-archive" (click)="archive()">Archive sheet</button>
      @if (sheetError()) {
        <p role="alert" data-testid="sheet-error">{{ sheetError() }}</p>
      }
      <form data-testid="asof-form" [formGroup]="asOfForm" (ngSubmit)="setAsOf()">
        <label for="asof-date">Show the sheet as of</label>
        <input id="asof-date" type="date" data-testid="asof-date" formControlName="day" />
        <button type="submit" data-testid="asof-set">Set as of</button>
        <button type="button" data-testid="asof-clear" (click)="clearAsOf()">Clear as of</button>
        @if (asOfError()) {
          <p role="alert" data-testid="asof-error">{{ asOfError() }}</p>
        }
      </form>
      <p>As of: <strong data-testid="sheet-asof">{{ session.asOf() ?? '' }}</strong></p>
    }
  `,
})
export class SheetBar {
  protected readonly session = inject(Session);
  protected readonly form = new FormGroup({ sheetId: new FormControl('', { nonNullable: true }) });
  protected readonly error = signal('');
  protected readonly todayForm = new FormGroup({ today: new FormControl('', { nonNullable: true }) });
  protected readonly sheetError = signal('');
  protected readonly asOfForm = new FormGroup({ day: new FormControl('', { nonNullable: true }) });
  protected readonly asOfError = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.todayForm.valueChanges.subscribe(() => this.sheetError.set(''));
    this.asOfForm.valueChanges.subscribe(() => this.asOfError.set(''));
  }

  /** Show the sheet, on this screen only, as it stood at the end of the day entered. */
  setAsOf(): void {
    this.asOfError.set('');
    this.sheetError.set('');
    const day = this.asOfForm.controls.day.value.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) {
      this.asOfError.set('Enter a date to show the sheet as of.');
      return;
    }
    this.session.setAsOf(day);
  }

  /** Show the sheet as it is now again. */
  clearAsOf(): void {
    this.asOfError.set('');
    this.sheetError.set('');
    this.asOfForm.controls.day.setValue('', { emitEvent: false });
    this.session.setAsOf(null);
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

  async archive(): Promise<void> {
    this.sheetError.set('');
    try {
      await this.session.archive();
    } catch {
      this.sheetError.set('The sheet could not be archived.');
    }
  }
}
