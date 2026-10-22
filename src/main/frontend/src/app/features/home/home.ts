import { Component, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { People } from '../people/people';
import { Settings } from '../settings/settings';
import { Delegation } from '../delegation/delegation';
import { Holidays } from '../holidays/holidays';
import { VoidReasonsList } from '../void-reasons/void-reasons';
import { TemplatesList } from '../templates/templates';
import { AuditViewer } from '../audit/audit';

// The one screen: sign in by typing a name, then load a sheet by id and work on its people.
@Component({
  selector: 'app-home',
  imports: [People, Settings, Delegation, Holidays, VoidReasonsList, TemplatesList, AuditViewer],
  template: `
    @if (session.user(); as user) {
      <section aria-label="Session">
        <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>
        <form (submit)="load($event)">
          <label for="sheet-id-input">Sheet id</label>
          <input
            id="sheet-id-input"
            data-testid="sheet-id-input"
            [value]="sheetInput()"
            (input)="sheetInput.set(value($event)); sheetError.set(null)"
          />
          <button type="submit" data-testid="sheet-load">Load</button>
          @if (sheetError(); as error) {
            <p role="alert" data-testid="sheet-error">{{ error }}</p>
          }
        </form>
        @if (session.sheet(); as sheet) {
          <p>Sheet
            @if (session.loaded() === session.loads()) {
              <strong data-testid="sheet-current-id">{{ sheet }}</strong>
            }
          </p>
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
            <button type="submit" data-testid="sheet-today-save">Save today</button>
          </form>
          <form (submit)="setAsOf($event)">
            <label for="asof-date">Show as of</label>
            <input
              id="asof-date"
              type="date"
              data-testid="asof-date"
              [value]="asOfInput()"
              (input)="asOfInput.set(value($event)); sheetError.set(null)"
            />
            <button type="submit" data-testid="asof-set">Set as-of date</button>
            <button type="button" data-testid="asof-clear" (click)="clearAsOf()">Clear as-of date</button>
          </form>
          <p>As of <strong data-testid="sheet-asof">{{ session.asOf() }}</strong></p>
          <p>Parent sheet <strong data-testid="sheet-parent">{{ session.parent() }}</strong></p>
          <p>Archived <strong data-testid="sheet-archived">{{ session.archived() ? 'yes' : 'no' }}</strong></p>
          <button type="button" data-testid="sheet-archive" (click)="archive()">Archive sheet</button>
          <app-settings />
          <app-delegation />
          <app-holidays />
          <app-void-reasons />
          <app-templates />
          <app-people />
          <app-audit-viewer />
        }
        <p data-testid="home-empty">Nothing here yet.</p>
      </section>
    } @else {
      <form aria-label="Sign in" (submit)="signIn($event)">
        <label for="signin-name">Your name</label>
        <input
          id="signin-name"
          data-testid="signin-name"
          [value]="nameInput()"
          (input)="nameInput.set(value($event)); signinError.set(null)"
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
  protected readonly nameInput = signal('');
  protected readonly sheetInput = signal('');
  protected readonly todayInput = signal('');
  protected readonly asOfInput = signal('');
  protected readonly signinError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);
  /** A today change is waiting for its answer: a second submit meanwhile is ignored. */
  private savingToday = false;
  /** An archive is waiting for its answer: a second click meanwhile is ignored. */
  private archiving = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected signIn(event: Event): void {
    event.preventDefault();
    const name = this.nameInput().trim();
    if (!name) {
      this.signinError.set('Type your name to sign in.');
      return;
    }
    this.signinError.set(null);
    this.session.signIn(name);
  }

  protected async load(event: Event): Promise<void> {
    event.preventDefault();
    this.sheetError.set(null);
    const id = this.sheetInput().trim();
    if (!id) {
      this.sheetError.set('Type a sheet id to load.');
      return;
    }
    try {
      await this.session.loadSheet(id);
    } catch {
      this.sheetError.set(`Sheet "${id}" could not be loaded.`);
    }
  }

  protected async saveToday(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingToday) return;
    this.sheetError.set(null);
    const date = this.todayInput().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.sheetError.set('Pick a date for today.');
      return;
    }
    this.savingToday = true;
    try {
      await this.session.setToday(date);
    } catch {
      this.sheetError.set(`Today could not be changed to ${date}.`);
    } finally {
      this.savingToday = false;
    }
  }

  protected async setAsOf(event: Event): Promise<void> {
    event.preventDefault();
    this.sheetError.set(null);
    const date = this.asOfInput().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.sheetError.set('Pick a date to show the sheet as of.');
      return;
    }
    try {
      await this.session.setAsOf(date);
    } catch {
      this.sheetError.set(`The sheet could not be shown as of ${date}.`);
    }
  }

  protected async clearAsOf(): Promise<void> {
    this.sheetError.set(null);
    this.asOfInput.set('');
    try {
      await this.session.setAsOf('');
    } catch {
      this.sheetError.set('The as-of date could not be cleared.');
    }
  }

  protected async archive(): Promise<void> {
    if (this.archiving) return;
    this.sheetError.set(null);
    this.archiving = true;
    try {
      await this.session.archive();
    } catch {
      this.sheetError.set('The sheet could not be archived.');
    } finally {
      this.archiving = false;
    }
  }
}
