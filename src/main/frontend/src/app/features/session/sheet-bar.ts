import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { LOCALES, Locale, Session } from '../../core/session';

// The signed-in name, and the form that loads a sheet by id.
@Component({
  selector: 'app-sheet-bar',
  imports: [ReactiveFormsModule],
  template: `
    <p>Signed in as <strong data-testid="current-user">{{ session.user() }}</strong></p>
    <form data-testid="user-locale-form" [formGroup]="localeForm" (ngSubmit)="saveLocale()">
      <label for="user-locale">Your locale</label>
      <select id="user-locale" data-testid="user-locale" formControlName="locale">
        @for (l of locales; track l) {
          <option [value]="l">{{ l }}</option>
        }
      </select>
      <button type="submit" data-testid="user-locale-save">Save locale</button>
      @if (localeError()) {
        <p role="alert" data-testid="user-locale-error">{{ localeError() }}</p>
      }
    </form>
    <p>Locale: <strong data-testid="user-locale-current">{{ session.locale() }}</strong></p>
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
      <p>Parent sheet: <strong data-testid="sheet-parent">{{ session.parent() ?? '' }}</strong></p>
      <button type="button" data-testid="sheet-archive" (click)="archive()">Archive sheet</button>
      <form data-testid="sheet-clone-form" [formGroup]="cloneForm" (ngSubmit)="cloneSheet()">
        <label for="sheet-clone-to">Clone the sheet to id</label>
        <input id="sheet-clone-to" data-testid="sheet-clone-to" formControlName="to" />
        <button type="submit" data-testid="sheet-clone">Clone sheet</button>
      </form>
      <p>Cloned to: <strong data-testid="sheet-cloned-to">{{ clonedTo() }}</strong></p>
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
      <form data-testid="preview-form" [formGroup]="previewForm" (ngSubmit)="setPreview()">
        <label for="preview-date">Preview the sheet as if today were</label>
        <input id="preview-date" type="date" data-testid="preview-date" formControlName="day" />
        <button type="submit" data-testid="preview-set">Set preview</button>
        <button type="button" data-testid="preview-clear" (click)="clearPreview()">Clear preview</button>
        @if (previewError()) {
          <p role="alert" data-testid="preview-error">{{ previewError() }}</p>
        }
      </form>
      <p>Preview: <strong data-testid="sheet-preview">{{ session.preview() ?? '' }}</strong></p>
    }
  `,
})
export class SheetBar {
  protected readonly session = inject(Session);
  protected readonly form = new FormGroup({ sheetId: new FormControl('', { nonNullable: true }) });
  protected readonly error = signal('');
  protected readonly todayForm = new FormGroup({ today: new FormControl('', { nonNullable: true }) });
  protected readonly sheetError = signal('');
  protected readonly cloneForm = new FormGroup({ to: new FormControl('', { nonNullable: true }) });
  /** The id the sheet was last cloned to from this screen; blank while a clone runs. */
  protected readonly clonedTo = signal('');
  protected readonly asOfForm = new FormGroup({ day: new FormControl('', { nonNullable: true }) });
  protected readonly asOfError = signal('');
  protected readonly previewForm = new FormGroup({ day: new FormControl('', { nonNullable: true }) });
  protected readonly previewError = signal('');
  protected readonly locales = LOCALES;
  protected readonly localeForm = new FormGroup({
    locale: new FormControl<Locale>(this.session.locale(), { nonNullable: true }),
  });
  protected readonly localeError = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.todayForm.valueChanges.subscribe(() => this.sheetError.set(''));
    this.asOfForm.valueChanges.subscribe(() => this.asOfError.set(''));
    this.previewForm.valueChanges.subscribe(() => this.previewError.set(''));
    this.cloneForm.valueChanges.subscribe(() => this.sheetError.set(''));
    this.localeForm.valueChanges.subscribe(() => this.localeError.set(''));
    // The select shows the locale the store holds for this user, re-read on every sheet load.
    effect(() => this.localeForm.controls.locale.setValue(this.session.locale(), { emitEvent: false }));
  }

  async saveLocale(): Promise<void> {
    this.localeError.set('');
    try {
      await this.session.setLocale(this.localeForm.controls.locale.value);
    } catch {
      this.localeError.set('The locale could not be saved.');
    }
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

  /** Show every figure, on this screen only, as if the sheet's today were the day entered. */
  setPreview(): void {
    this.previewError.set('');
    const day = this.previewForm.controls.day.value.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) {
      this.previewError.set('Enter a date to preview.');
      return;
    }
    this.session.setPreview(day);
  }

  /** Show every figure on the sheet's today again. */
  clearPreview(): void {
    this.previewError.set('');
    this.previewForm.controls.day.setValue('', { emitEvent: false });
    this.session.setPreview(null);
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

  async cloneSheet(): Promise<void> {
    this.sheetError.set('');
    this.clonedTo.set('');
    const to = this.cloneForm.controls.to.value.trim();
    if (!to) {
      this.sheetError.set('Enter a sheet id to clone to.');
      return;
    }
    try {
      this.clonedTo.set(await this.session.cloneSheet(to));
    } catch {
      this.sheetError.set('The sheet could not be cloned.');
    }
  }
}
