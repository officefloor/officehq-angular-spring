import { Component, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { People } from '../people/people';
import { Rates } from '../rates/rates';
import { Settings } from '../settings/settings';
import { Delegation } from '../delegation/delegation';
import { Holidays } from '../holidays/holidays';
import { VoidReasons } from '../void-reasons/void-reasons';
import { ClaimTemplates } from '../claim-templates/claim-templates';
import { Pools } from '../pools/pools';
import { AuditViewer } from '../audit-viewer/audit-viewer';

// The one screen: sign in by name, then load a sheet by id.
@Component({
  selector: 'app-home',
  imports: [People, Rates, Settings, Delegation, Holidays, VoidReasons, ClaimTemplates, Pools, AuditViewer],
  template: `
    @if (session.user(); as user) {
      <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>

      <form (submit)="load($event)" aria-label="Load sheet">
        <label for="sheet-id">Sheet id</label>
        <input
          id="sheet-id"
          data-testid="sheet-id-input"
          [value]="sheetInput()"
          (input)="sheetInput.set(value($event)); sheetError.set(null)"
        />
        <button type="submit" data-testid="sheet-load">Load</button>
        @if (sheetError(); as err) {
          <p role="alert" data-testid="sheet-load-error">{{ err }}</p>
        }
      </form>

      @if (session.sheetId(); as id) {
        <p>Sheet: <strong data-testid="sheet-current-id">{{ id }}</strong></p>
        <p>Today: <strong data-testid="sheet-today">{{ session.sheetToday() }}</strong></p>
        <p>Archived: <strong data-testid="sheet-archived">{{ session.sheetArchived() ? 'yes' : 'no' }}</strong></p>

        <form (submit)="saveToday($event)" aria-label="Change today">
          <label for="sheet-today">New today</label>
          <input
            id="sheet-today"
            type="date"
            data-testid="sheet-today-input"
            [value]="todayInput()"
            (input)="todayInput.set(value($event)); todayError.set(null)"
          />
          <button type="submit" data-testid="sheet-today-save">Set today</button>
        </form>
        <button type="button" data-testid="sheet-archive" (click)="archive()">Archive sheet</button>

        <p>As of: <strong data-testid="sheet-asof">{{ session.sheetAsOf() ?? '' }}</strong></p>
        <form (submit)="setAsOf($event)" aria-label="Show the sheet as of a date">
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
          @if (asOfError(); as err) {
            <p role="alert" data-testid="asof-error">{{ err }}</p>
          }
        </form>
        @if (todayError(); as err) {
          <p role="alert" data-testid="sheet-error">{{ err }}</p>
        }

        <app-rates />
        <app-settings />
        <app-delegation />
        <app-holidays />
        <app-void-reasons />
        <app-claim-templates />
        <app-people />
        <app-pools />
        <app-audit-viewer />
      }
    } @else {
      <form (submit)="signIn($event)" aria-label="Sign in">
        <label for="signin-name">Your name</label>
        <input
          id="signin-name"
          data-testid="signin-name"
          [value]="nameInput()"
          (input)="nameInput.set(value($event)); signinError.set(null)"
        />
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
  protected readonly nameInput = signal('');
  protected readonly sheetInput = signal('');
  protected readonly signinError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);
  protected readonly todayInput = signal('');
  // The sheet's own error: a refused today change or archive.
  protected readonly todayError = signal<string | null>(null);

  protected readonly asOfInput = signal('');
  // The as-of entry's own error: a date that is not one, or a sheet that could not be shown as of it.
  protected readonly asOfError = signal<string | null>(null);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async signIn(e: Event): Promise<void> {
    e.preventDefault();
    this.signinError.set(null);
    const name = this.nameInput().trim();
    if (!name) {
      this.signinError.set('Enter a name.');
      return;
    }
    try {
      await this.session.signIn(name);
    } catch {
      this.signinError.set('Could not sign in.');
    }
  }

  protected async load(e: Event): Promise<void> {
    e.preventDefault();
    this.sheetError.set(null);
    const id = this.sheetInput().trim();
    if (!id) {
      this.sheetError.set('Enter a sheet id.');
      return;
    }
    try {
      await this.session.loadSheet(id);
    } catch {
      this.sheetError.set('Could not load that sheet.');
    }
  }

  protected async saveToday(e: Event): Promise<void> {
    e.preventDefault();
    this.todayError.set(null);
    const today = this.todayInput().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
      this.todayError.set('Enter a date as yyyy-mm-dd.');
      return;
    }
    try {
      await this.session.setToday(today);
    } catch {
      this.todayError.set('Could not change the date.');
    }
  }

  protected async setAsOf(e: Event): Promise<void> {
    e.preventDefault();
    this.asOfError.set(null);
    const date = this.asOfInput().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.asOfError.set('Enter a date as yyyy-mm-dd.');
      return;
    }
    try {
      await this.session.setAsOf(date);
    } catch {
      this.asOfError.set('Could not show the sheet as of that date.');
    }
  }

  protected async clearAsOf(): Promise<void> {
    this.asOfError.set(null);
    try {
      await this.session.setAsOf(null);
    } catch {
      this.asOfError.set('Could not show the sheet as it stands.');
    }
  }

  protected async archive(): Promise<void> {
    this.todayError.set(null);
    try {
      await this.session.archive();
    } catch {
      this.todayError.set('Could not archive the sheet.');
    }
  }
}
