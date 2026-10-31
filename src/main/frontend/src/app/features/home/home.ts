import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Session } from '../../core/session';
import { Sheets } from '../../core/sheet';
import { DelegationsPanel } from '../delegations/delegations';
import { HolidaysPanel } from '../holidays/holidays';
import { PeopleList } from '../people/people';
import { RatesPanel } from '../rates/rates';
import { SettingsPanel } from '../settings/settings';
import { VoidReasonsPanel } from '../void-reasons/void-reasons';

// The one screen: sign in by name, then choose a sheet by id. Everything else is per sheet.
@Component({
  selector: 'app-home',
  imports: [ReactiveFormsModule, PeopleList, RatesPanel, SettingsPanel, DelegationsPanel, HolidaysPanel,
    VoidReasonsPanel],
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
        <p>
          Archived: <strong data-testid="sheet-archived">{{ sheet.archived ? 'yes' : 'no' }}</strong>
          <button type="button" data-testid="sheet-archive" [disabled]="archiving()" (click)="archive()">Archive sheet</button>
        </p>

        <form [formGroup]="asOfForm" (ngSubmit)="setAsOf()" aria-label="Show the sheet as of a date">
          <p>As of: <strong data-testid="sheet-asof">{{ sheet.asOf ?? '' }}</strong></p>
          <label for="asof-date">Show as of</label>
          <input id="asof-date" type="date" data-testid="asof-date" formControlName="date" />
          <button type="submit" data-testid="asof-set" [disabled]="settingAsOf()">Set as of</button>
          <button type="button" data-testid="asof-clear" [disabled]="settingAsOf()" (click)="clearAsOf()">Clear as of</button>
          @if (asOfError(); as err) {
            <p role="alert" data-testid="asof-error">{{ err }}</p>
          }
        </form>

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
        <app-holidays />
        <app-void-reasons />
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
  /** The date to show the sheet as of. */
  protected readonly asOfForm = new FormGroup({
    date: new FormControl('', { nonNullable: true }),
  });
  protected readonly signInError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);
  protected readonly todayError = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly savingToday = signal(false);
  protected readonly archiving = signal(false);
  protected readonly asOfError = signal<string | null>(null);
  protected readonly settingAsOf = signal(false);

  constructor() {
    // An error is removed as soon as its form is changed.
    this.signInForm.valueChanges.subscribe(() => this.signInError.set(null));
    this.sheetForm.valueChanges.subscribe(() => this.sheetError.set(null));
    this.todayForm.valueChanges.subscribe(() => this.todayError.set(null));
    this.asOfForm.valueChanges.subscribe(() => this.asOfError.set(null));
  }

  /** Show the sheet as of the date typed: everything on screen as it stood at the end of that day. */
  protected setAsOf(): void {
    this.asOfError.set(null);
    const date = this.asOfForm.controls.date.value.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.asOfError.set('Choose a date to show the sheet as of.');
      return;
    }
    this.showAsOf(date);
  }

  /** Show the sheet as it is now again. */
  protected clearAsOf(): void {
    this.asOfError.set(null);
    this.showAsOf(null);
  }

  private showAsOf(date: string | null): void {
    // A second press before the first has answered is ignored.
    if (this.settingAsOf()) return;
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.settingAsOf.set(true);
    this.todayError.set(null);
    this.sheets.setAsOf(sheet.id, date).subscribe({
      next: (loaded) => {
        this.settingAsOf.set(false);
        this.todayForm.setValue({ today: loaded.today }, { emitEvent: false });
      },
      error: () => {
        this.settingAsOf.set(false);
        this.asOfError.set('Could not show the sheet as of that date.');
      },
    });
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

  protected archive(): void {
    // A second press before the first has answered is ignored.
    if (this.archiving()) return;
    this.todayError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.archiving.set(true);
    this.sheets.archive(sheet.id).subscribe({
      next: () => this.archiving.set(false),
      error: () => {
        this.archiving.set(false);
        this.todayError.set('Could not archive the sheet.');
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
