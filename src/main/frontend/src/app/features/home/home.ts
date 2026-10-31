import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Session } from '../../core/session';
import { Sheets } from '../../core/sheet';
import { DelegationsPanel } from '../delegations/delegations';
import { PeopleList } from '../people/people';
import { RatesPanel } from '../rates/rates';
import { SettingsPanel } from '../settings/settings';

// The one screen: sign in by name, then choose a sheet by id. Everything else is per sheet.
@Component({
  selector: 'app-home',
  imports: [ReactiveFormsModule, PeopleList, RatesPanel, SettingsPanel, DelegationsPanel],
  template: `
    @if (session.user(); as user) {
      <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>

      <form [formGroup]="sheetForm" (ngSubmit)="loadSheet()" aria-label="Load a sheet">
        <label for="sheet-id">Sheet id</label>
        <input id="sheet-id" data-testid="sheet-id-input" formControlName="id" autocomplete="off" />
        <button type="submit" data-testid="sheet-load" [disabled]="loading()">Load</button>
        @if (sheetError(); as err) {
          <p role="alert" data-testid="sheet-load-error">{{ err }}</p>
        }
      </form>

      @if (sheets.current(); as sheet) {
        <p>Sheet: <strong data-testid="sheet-current-id">{{ sheet.id }}</strong></p>
        <p>Today: <strong data-testid="sheet-today">{{ sheet.today }}</strong></p>

        <form [formGroup]="todayForm" (ngSubmit)="saveToday()" aria-label="Change the sheet's today">
          <label for="sheet-today-input">Change today</label>
          <input id="sheet-today-input" type="date" data-testid="sheet-today-input" formControlName="today" />
          <button type="submit" data-testid="sheet-today-save" [disabled]="savingToday()">Set today</button>
          @if (todayError(); as err) {
            <p role="alert" data-testid="sheet-error">{{ err }}</p>
          }
        </form>

        <app-rates />
        <app-settings />
        <app-delegations />
        <app-people />
      } @else {
        <p data-testid="sheet-none">Load a sheet to begin.</p>
      }
    } @else {
      <form [formGroup]="signInForm" (ngSubmit)="signIn()" aria-label="Sign in">
        <label for="signin-name">Your name</label>
        <input id="signin-name" data-testid="signin-name" formControlName="name" autocomplete="username" />
        <button type="submit" data-testid="signin-submit">Sign in</button>
        @if (signInError(); as err) {
          <p role="alert" data-testid="signin-error">{{ err }}</p>
        }
      </form>
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
  protected readonly sheets = inject(Sheets);

  protected readonly signInForm = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
  });
  protected readonly sheetForm = new FormGroup({
    id: new FormControl('', { nonNullable: true, validators: Validators.required }),
  });
  protected readonly todayForm = new FormGroup({
    today: new FormControl('', { nonNullable: true, validators: Validators.required }),
  });
  protected readonly signInError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);
  protected readonly todayError = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly savingToday = signal(false);

  constructor() {
    // An error is removed as soon as its form is changed.
    this.signInForm.valueChanges.subscribe(() => this.signInError.set(null));
    this.sheetForm.valueChanges.subscribe(() => this.sheetError.set(null));
    this.todayForm.valueChanges.subscribe(() => this.todayError.set(null));
  }

  protected signIn(): void {
    this.signInError.set(null);
    const name = this.signInForm.controls.name.value.trim();
    if (!name) {
      this.signInError.set('Type your name to sign in.');
      return;
    }
    this.session.signIn(name).subscribe({
      error: () => this.signInError.set('Could not sign in with that name.'),
    });
  }

  protected loadSheet(): void {
    // A second submit before the first has answered is ignored.
    if (this.loading()) return;
    this.sheetError.set(null);
    const id = this.sheetForm.controls.id.value.trim();
    if (!id) {
      this.sheetError.set('Type a sheet id to load.');
      return;
    }
    this.loading.set(true);
    this.todayError.set(null);
    this.sheets.load(id).subscribe({
      next: (sheet) => {
        this.loading.set(false);
        this.todayForm.setValue({ today: sheet.today }, { emitEvent: false });
      },
      error: () => {
        this.loading.set(false);
        this.sheetError.set('Could not load that sheet.');
      },
    });
  }

  protected saveToday(): void {
    // A second submit before the first has answered is ignored.
    if (this.savingToday()) return;
    this.todayError.set(null);
    const sheet = this.sheets.current();
    const today = this.todayForm.controls.today.value.trim();
    if (!sheet) return;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
      this.todayError.set('Choose a date for today.');
      return;
    }
    this.savingToday.set(true);
    this.sheets.setToday(sheet.id, today).subscribe({
      next: () => this.savingToday.set(false),
      error: () => {
        this.savingToday.set(false);
        this.todayError.set('Could not change the sheet\'s today.');
      },
    });
  }
}
