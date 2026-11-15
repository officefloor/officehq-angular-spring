import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Session } from '../../core/session';
import { AuditViewer } from '../audit/audit-viewer';
import { People } from '../people/people';
import { Settings } from '../settings/settings';

// The one screen: sign in by name, then load a sheet by id and work on its people.
@Component({
  selector: 'app-home',
  imports: [ReactiveFormsModule, AuditViewer, People, Settings],
  template: `
    @if (session.user(); as user) {
      <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>
      <form [formGroup]="sheetForm" (ngSubmit)="load()" aria-label="Load sheet">
        <label for="sheet-id">Sheet id</label>
        <input id="sheet-id" data-testid="sheet-id-input" formControlName="sheetId" autocomplete="off" />
        <button type="submit" data-testid="sheet-load">Load</button>
        @if (sheetError(); as err) {
          <p role="alert" data-testid="sheet-error">{{ err }}</p>
        }
      </form>
      @if (session.sheetId(); as id) {
        <p>Sheet <strong data-testid="sheet-current-id">{{ id }}</strong></p>
        <p>Archived <strong data-testid="sheet-archived">{{ session.archived() ? 'yes' : 'no' }}</strong>
          <button type="button" data-testid="sheet-archive" (click)="archive()">Archive sheet</button>
        </p>
        <p>Today <strong data-testid="sheet-today">{{ session.today() }}</strong></p>
        <form [formGroup]="asOfForm" (ngSubmit)="setAsOf()" aria-label="Show the sheet as of a date">
          <p>As of <strong data-testid="sheet-asof">{{ session.shownAsOf() ?? '' }}</strong></p>
          <label for="asof-date">Show as of</label>
          <input id="asof-date" type="date" data-testid="asof-date" formControlName="date" />
          <button type="submit" data-testid="asof-set">Set as-of date</button>
          <button type="button" data-testid="asof-clear" (click)="clearAsOf()">Clear as-of date</button>
          @if (asOfError(); as err) {
            <p role="alert" data-testid="asof-error">{{ err }}</p>
          }
        </form>
        <form [formGroup]="todayForm" (ngSubmit)="saveToday()" aria-label="Sheet today">
          <label for="sheet-today-input">Change today</label>
          <input id="sheet-today-input" type="date" data-testid="sheet-today-input" formControlName="today" />
          <button type="submit" data-testid="sheet-today-save">Save today</button>
        </form>
        <app-settings />
        <app-people />
        <app-audit-viewer />
      } @else {
        <p data-testid="home-empty">Load a sheet to begin.</p>
      }
    } @else {
      <form [formGroup]="signinForm" (ngSubmit)="signIn()" aria-label="Sign in">
        <label for="signin-name">Name</label>
        <input id="signin-name" data-testid="signin-name" formControlName="name" autocomplete="username" />
        <button type="submit" data-testid="signin-submit">Sign in</button>
        @if (signinError(); as err) {
          <p role="alert" data-testid="signin-error">{{ err }}</p>
        }
      </form>
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);

  protected readonly signinForm = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
  });
  protected readonly sheetForm = new FormGroup({
    sheetId: new FormControl('', { nonNullable: true, validators: Validators.required }),
  });
  protected readonly todayForm = new FormGroup({
    today: new FormControl('', { nonNullable: true, validators: Validators.required }),
  });
  protected readonly asOfForm = new FormGroup({
    date: new FormControl('', { nonNullable: true, validators: Validators.required }),
  });
  protected readonly signinError = signal<string | null>(null);
  protected readonly asOfError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);

  constructor() {
    this.signinForm.valueChanges.subscribe(() => this.signinError.set(null));
    this.sheetForm.valueChanges.subscribe(() => this.sheetError.set(null));
    this.todayForm.valueChanges.subscribe(() => this.sheetError.set(null));
    this.asOfForm.valueChanges.subscribe(() => this.asOfError.set(null));
  }

  protected async signIn(): Promise<void> {
    this.signinError.set(null);
    const name = this.signinForm.controls.name.value.trim();
    if (!name) {
      this.signinError.set('Enter a name to sign in.');
      return;
    }
    this.signinError.set(await this.session.signIn(name));
  }

  protected async load(): Promise<void> {
    this.sheetError.set(null);
    const id = this.sheetForm.controls.sheetId.value.trim();
    if (!id) {
      this.sheetError.set('Enter a sheet id.');
      return;
    }
    this.sheetError.set(await this.session.loadSheet(id));
  }

  protected async archive(): Promise<void> {
    this.sheetError.set(null);
    this.sheetError.set(await this.session.archive());
  }

  protected async setAsOf(): Promise<void> {
    this.asOfError.set(null);
    const date = this.asOfForm.controls.date.value.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.asOfError.set('Enter a date as yyyy-mm-dd.');
      return;
    }
    this.asOfError.set(await this.session.showAsOf(date));
  }

  protected async clearAsOf(): Promise<void> {
    this.asOfError.set(null);
    this.asOfError.set(await this.session.showAsOf(null));
  }

  protected async saveToday(): Promise<void> {
    this.sheetError.set(null);
    const today = this.todayForm.controls.today.value.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
      this.sheetError.set('Enter a date as yyyy-mm-dd.');
      return;
    }
    this.sheetError.set(await this.session.setToday(today));
  }
}
