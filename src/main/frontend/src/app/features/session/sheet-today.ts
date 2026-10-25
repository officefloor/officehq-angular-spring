import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';

// The active sheet's today, shown and changeable. Every date decision and default date in the app
// uses it, never the real clock. A steward can archive the sheet here too, or clone it to another id.
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
        <span>Archived: <strong data-testid="sheet-archived">{{ sheet.archived ? 'yes' : 'no' }}</strong></span>
        <button type="button" data-testid="sheet-archive" [disabled]="saving()" (click)="archive()">Archive sheet</button>
        @if (error()) {
          <p role="alert" data-testid="sheet-error">{{ error() }}</p>
        }
      </form>
      <form [formGroup]="cloneForm" (ngSubmit)="cloneSheet()" aria-label="Clone sheet">
        <label for="sheet-clone-to">Clone to sheet</label>
        <input id="sheet-clone-to" data-testid="sheet-clone-to" formControlName="to" />
        <button type="submit" data-testid="sheet-clone" [disabled]="saving()">Clone sheet</button>
        <span>Last clone: <strong data-testid="sheet-cloned-to">{{ clonedTo() }}</strong></span>
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
  protected readonly cloneTo = new FormControl('', { nonNullable: true });
  protected readonly cloneForm = new FormGroup({ to: this.cloneTo });
  /** The id of the last clone made of this sheet from this screen. */
  protected readonly clonedTo = signal('');

  constructor() {
    this.today.valueChanges.subscribe(() => this.error.set(''));
    this.cloneTo.valueChanges.subscribe(() => this.error.set(''));
    // A freshly loaded sheet starts the field at its today and clears any error and clone from the last one.
    effect(() => {
      const sheet = this.session.sheet();
      this.error.set('');
      this.clonedTo.set('');
      this.today.setValue(sheet?.today ?? '', { emitEvent: false });
    });
  }

  protected async save(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
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

  protected async archive(): Promise<void> {
    if (this.saving()) return;
    this.error.set('');
    this.saving.set(true);
    try {
      await this.session.archive();
    } catch {
      this.error.set('The sheet could not be archived.');
    } finally {
      this.saving.set(false);
    }
  }

  protected async cloneSheet(): Promise<void> {
    if (this.saving()) return;
    const to = this.cloneTo.value.trim();
    this.error.set('');
    this.clonedTo.set('');
    if (to === '') {
      this.error.set('Type the id of the sheet to clone to.');
      return;
    }
    this.saving.set(true);
    try {
      this.clonedTo.set(await this.session.cloneSheet(to));
    } catch {
      this.error.set('The sheet could not be cloned: only a steward can, to a sheet never used.');
    } finally {
      this.saving.set(false);
    }
  }
}
