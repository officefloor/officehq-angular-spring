import { Component, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { AuditViewer } from '../audit-viewer/audit-viewer';
import { Holidays } from '../holidays/holidays';
import { People } from '../people/people';
import { Settings } from '../settings/settings';
import { TemplatesList } from '../templates/templates';
import { TreeSearch } from '../tree-search/tree-search';
import { VoidReasonsList } from '../void-reasons/void-reasons';

// The one screen: sign in by name, then load a sheet by id.
@Component({
  selector: 'app-home',
  imports: [AuditViewer, Holidays, People, Settings, TemplatesList, TreeSearch, VoidReasonsList],
  template: `
    @if (session.user(); as user) {
      <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>
      <form (submit)="load($event)">
        <label for="sheet-id">Sheet id</label>
        <input
          id="sheet-id"
          data-testid="sheet-id-input"
          [value]="sheetId()"
          (input)="sheetId.set(value($event)); sheetError.set(null)"
        />
        <button type="submit" data-testid="sheet-load">Load</button>
        @if (sheetError(); as error) {
          <p role="alert" data-testid="sheet-error">{{ error }}</p>
        }
      </form>
      @if (session.sheet(); as sheet) {
        <p>Sheet <strong data-testid="sheet-current-id">{{ sheet }}</strong></p>
        <p>Today <strong data-testid="sheet-today">{{ session.today() }}</strong></p>
        <form (submit)="saveToday($event)">
          <label for="sheet-today-input">Change today</label>
          <input
            id="sheet-today-input"
            type="date"
            data-testid="sheet-today-input"
            [value]="todayInput()"
            (input)="todayInput.set(value($event)); sheetError.set(null)"
          />
          <button type="submit" data-testid="sheet-today-save">Set today</button>
        </form>
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
          @if (asOfError(); as error) {
            <p role="alert" data-testid="asof-error">{{ error }}</p>
          }
        </form>
        <p>As of <strong data-testid="sheet-asof">{{ session.asOf() ?? '' }}</strong></p>
        <p>Parent sheet <strong data-testid="sheet-parent">{{ session.parent() ?? '' }}</strong></p>
        <p>Archived <strong data-testid="sheet-archived">{{ session.archived() ? 'yes' : 'no' }}</strong></p>
        <button type="button" data-testid="sheet-archive" (click)="archive()">Archive sheet</button>
        <form (submit)="cloneSheet($event)">
          <label for="sheet-clone-to">Clone to sheet id</label>
          <input
            id="sheet-clone-to"
            data-testid="sheet-clone-to"
            [value]="cloneTo()"
            (input)="cloneTo.set(value($event)); sheetError.set(null)"
          />
          <button type="submit" data-testid="sheet-clone">Clone sheet</button>
        </form>
        <p>Cloned to <strong data-testid="sheet-cloned-to">{{ clonedTo() ?? '' }}</strong></p>
        <app-settings />
        <app-holidays />
        <app-void-reasons />
        <app-templates />
        <app-people />
        <app-tree-search />
        <app-audit-viewer />
      }
    } @else {
      <form (submit)="signIn($event)">
        <label for="signin-name">Name</label>
        <input
          id="signin-name"
          data-testid="signin-name"
          [value]="name()"
          (input)="name.set(value($event)); signinError.set(null)"
        />
        <button type="submit" data-testid="signin-submit">Sign in</button>
        @if (signinError(); as error) {
          <p role="alert" data-testid="signin-error">{{ error }}</p>
        }
      </form>
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
  protected readonly name = signal('');
  protected readonly sheetId = signal('');
  protected readonly signinError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);
  protected readonly todayInput = signal('');
  protected readonly asOfInput = signal('');
  protected readonly asOfError = signal<string | null>(null);
  protected readonly cloneTo = signal('');
  /** The id the active sheet was last cloned to from this screen; cleared while a clone is asked for. */
  protected readonly clonedTo = signal<string | null>(null);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected signIn(event: Event): void {
    event.preventDefault();
    const name = this.name().trim();
    if (!name) {
      this.signinError.set('Type a name to sign in.');
      return;
    }
    this.signinError.set(null);
    this.session.signIn(name);
  }

  protected async load(event: Event): Promise<void> {
    event.preventDefault();
    const id = this.sheetId().trim();
    this.sheetError.set(null);
    if (!id) {
      this.sheetError.set('Type a sheet id to load.');
      return;
    }
    this.clonedTo.set(null);
    try {
      await this.session.loadSheet(id);
    } catch {
      this.sheetError.set(`Sheet ${id} could not be loaded.`);
    }
  }

  protected async archive(): Promise<void> {
    this.sheetError.set(null);
    try {
      await this.session.archive();
    } catch {
      this.sheetError.set('The sheet could not be archived.');
    }
  }

  protected async cloneSheet(event: Event): Promise<void> {
    event.preventDefault();
    const to = this.cloneTo().trim();
    this.sheetError.set(null);
    this.clonedTo.set(null);
    if (!to) {
      this.sheetError.set('Type a sheet id to clone to.');
      return;
    }
    try {
      await this.session.cloneSheet(to);
      this.clonedTo.set(to);
    } catch {
      this.sheetError.set(`The sheet could not be cloned to ${to}.`);
    }
  }

  protected async setAsOf(event: Event): Promise<void> {
    event.preventDefault();
    const date = this.asOfInput().trim();
    this.asOfError.set(null);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.asOfError.set('Pick a date to show the sheet as of.');
      return;
    }
    try {
      await this.session.setAsOf(date);
    } catch {
      this.asOfError.set(`The sheet could not be shown as of ${date}.`);
    }
  }

  protected async clearAsOf(): Promise<void> {
    this.asOfError.set(null);
    try {
      await this.session.setAsOf(null);
    } catch {
      this.asOfError.set('The sheet could not be shown as it stands.');
    }
  }

  protected async saveToday(event: Event): Promise<void> {
    event.preventDefault();
    const today = this.todayInput().trim();
    this.sheetError.set(null);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
      this.sheetError.set('Pick a date for today.');
      return;
    }
    try {
      await this.session.setToday(today);
    } catch {
      this.sheetError.set(`Today could not be set to ${today}.`);
    }
  }
}
