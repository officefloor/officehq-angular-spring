import { Component, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { AuditViewer } from '../audit/audit-viewer';
import { People } from '../people/people';
import { SettingStore } from '../settings/settings';

// The one screen: sign in by name, then load a sheet by id.
@Component({
  selector: 'app-home',
  imports: [AuditViewer, People],
  template: `
    @if (session.user(); as user) {
      <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>

      <form (submit)="load($event)">
        <label for="sheet-id">Sheet id</label>
        <input
          id="sheet-id"
          data-testid="sheet-id-input"
          [value]="sheetInput()"
          (input)="sheetInput.set(value($event)); sheetError.set(null)"
        />
        <button type="submit" data-testid="sheet-load">Load</button>
      </form>
      @if (sheetError(); as error) {
        <p role="alert" data-testid="sheet-load-error">{{ error }}</p>
      }

      @if (session.sheetId(); as sheet) {
        <p>Sheet <strong data-testid="sheet-current-id">{{ sheet }}</strong></p>
        <p>Today <strong data-testid="sheet-today">{{ session.today() }}</strong></p>
        <form (submit)="saveToday($event)">
          <label for="sheet-today-date">Change today</label>
          <input
            id="sheet-today-date"
            type="date"
            data-testid="sheet-today-input"
            [value]="todayInput()"
            (input)="todayInput.set(value($event)); todayError.set(null)"
          />
          <button type="submit" data-testid="sheet-today-save">Set today</button>
        </form>
        <p>
          Archived <strong data-testid="sheet-archived">{{ session.archived() ? 'yes' : 'no' }}</strong>
          <button type="button" data-testid="sheet-archive" (click)="archive()">Archive sheet</button>
        </p>
        <p>Parent sheet <strong data-testid="sheet-parent">{{ settings.parent() }}</strong></p>
        <form (submit)="cloneSheet($event)">
          <label for="sheet-clone-to">Clone to sheet id</label>
          <input
            id="sheet-clone-to"
            data-testid="sheet-clone-to"
            [value]="cloneInput()"
            (input)="cloneInput.set(value($event)); todayError.set(null)"
          />
          <button type="submit" data-testid="sheet-clone">Clone sheet</button>
          Last clone made <strong data-testid="sheet-cloned-to">{{ clonedTo() }}</strong>
        </form>
        @if (todayError(); as error) {
          <p role="alert" data-testid="sheet-error">{{ error }}</p>
        }
        <form (submit)="setAsOf($event)">
          <label for="asof-date">Show as of</label>
          <input
            id="asof-date"
            type="date"
            data-testid="asof-date"
            [value]="asOfInput()"
            (input)="asOfInput.set(value($event)); asOfError.set(null)"
          />
          <button type="submit" data-testid="asof-set">Set as of</button>
          <button type="button" data-testid="asof-clear" (click)="clearAsOf()">Clear as of</button>
          As of <strong data-testid="sheet-asof">{{ session.asOf() ?? '' }}</strong>
        </form>
        @if (asOfError(); as error) {
          <p role="alert" data-testid="asof-error">{{ error }}</p>
        }
        <app-people />
        <app-audit-viewer />
      } @else {
        <p data-testid="home-empty">No sheet loaded.</p>
      }
    } @else {
      <form (submit)="signIn($event)">
        <label for="signin-name">Name</label>
        <input
          id="signin-name"
          data-testid="signin-name"
          [value]="nameInput()"
          (input)="nameInput.set(value($event)); signinError.set(null)"
        />
        <button type="submit" data-testid="signin-submit">Sign in</button>
      </form>
      @if (signinError(); as error) {
        <p role="alert" data-testid="signin-error">{{ error }}</p>
      }
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
  protected readonly settings = inject(SettingStore);
  protected readonly nameInput = signal('');
  protected readonly sheetInput = signal('');
  protected readonly signinError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);
  protected readonly todayInput = signal('');
  protected readonly todayError = signal<string | null>(null);
  protected readonly asOfInput = signal('');
  protected readonly cloneInput = signal('');
  /** The id of the last clone this screen made of the active sheet. */
  protected readonly clonedTo = signal('');
  protected readonly asOfError = signal<string | null>(null);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected signIn(event: Event): void {
    event.preventDefault();
    this.signinError.set(this.session.signIn(this.nameInput()) ? null : 'Type a name to sign in.');
  }

  protected async load(event: Event): Promise<void> {
    event.preventDefault();
    this.sheetError.set(null);
    this.todayError.set(null);
    const id = this.sheetInput().trim();
    if (!id) {
      this.sheetError.set('Type a sheet id to load.');
      return;
    }
    try {
      await this.session.loadSheet(id);
      this.clonedTo.set('');
    } catch {
      this.sheetError.set('The sheet could not be loaded.');
    }
  }

  protected async archive(): Promise<void> {
    this.todayError.set(null);
    try {
      await this.session.archive();
    } catch {
      this.todayError.set('The sheet could not be archived.');
    }
  }

  protected async cloneSheet(event: Event): Promise<void> {
    event.preventDefault();
    this.todayError.set(null);
    this.clonedTo.set('');
    const to = this.cloneInput().trim();
    if (!to) {
      this.todayError.set('Type a sheet id to clone to.');
      return;
    }
    try {
      this.clonedTo.set(await this.session.cloneTo(to));
    } catch {
      this.todayError.set('The sheet could not be cloned.');
    }
  }

  /** Show the sheet as of the typed date on this screen only. */
  protected setAsOf(event: Event): void {
    event.preventDefault();
    this.asOfError.set(null);
    this.todayError.set(null);
    const date = this.asOfInput().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.asOfError.set('Type a date (yyyy-mm-dd) to show the sheet as of.');
      return;
    }
    this.session.setAsOf(date);
  }

  /** Show the sheet as it is now again. */
  protected clearAsOf(): void {
    this.asOfError.set(null);
    this.todayError.set(null);
    this.asOfInput.set('');
    this.session.clearAsOf();
  }

  protected async saveToday(event: Event): Promise<void> {
    event.preventDefault();
    this.todayError.set(null);
    const today = this.todayInput().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
      this.todayError.set('Type a date (yyyy-mm-dd) for today.');
      return;
    }
    try {
      await this.session.setToday(today);
    } catch {
      this.todayError.set('Today could not be changed.');
    }
  }
}
