import { Component, inject, linkedSignal, signal } from '@angular/core';
import { LOCALES, Locale, Session } from '../../core/session';
import { People } from '../people/people';

// The one screen: sign in by name, then load a sheet by id. Everything else hangs off the
// active sheet.
@Component({
  selector: 'app-home',
  imports: [People],
  template: `
    @if (session.user(); as user) {
      <section aria-label="Session">
        <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>
        <form aria-label="Locale" (submit)="saveLocale($event)">
          <p>Locale <strong data-testid="user-locale-current">{{ session.locale() }}</strong></p>
          <label for="user-locale">Show figures as</label>
          <select
            id="user-locale"
            data-testid="user-locale"
            (change)="localeInput.set($any($event.target).value); localeError.set(null)"
          >
            @for (l of locales; track l) {
              <option [value]="l" [selected]="l === localeInput()">{{ l }}</option>
            }
          </select>
          <button type="submit" data-testid="user-locale-save">Save locale</button>
          @if (localeError(); as err) {
            <p role="alert" data-testid="user-locale-error">{{ err }}</p>
          }
        </form>
        <form (submit)="load($event)">
          <label for="sheet-id-input">Sheet id</label>
          <input
            id="sheet-id-input"
            data-testid="sheet-id-input"
            [value]="sheetInput()"
            (input)="sheetInput.set($any($event.target).value); sheetError.set(null)"
          />
          <button type="submit" data-testid="sheet-load">Load</button>
          @if (sheetError(); as err) {
            <p role="alert" data-testid="sheet-error">{{ err }}</p>
          }
        </form>
        @if (session.sheet(); as sheet) {
          <p>Sheet <strong data-testid="sheet-current-id">{{ sheet }}</strong></p>
          <form aria-label="Sheet today" (submit)="saveToday($event)">
            <p>Today <strong data-testid="sheet-today">{{ session.today() }}</strong></p>
            <label for="sheet-today-input">Change today</label>
            <input
              id="sheet-today-input"
              type="date"
              data-testid="sheet-today-input"
              [value]="todayInput()"
              (input)="todayInput.set($any($event.target).value); sheetError.set(null)"
            />
            <button type="submit" data-testid="sheet-today-save">Set today</button>
          </form>
          <p>
            Archived <strong data-testid="sheet-archived">{{ session.archived() ? 'yes' : 'no' }}</strong>
            <button type="button" data-testid="sheet-archive" (click)="archive()">Archive sheet</button>
          </p>
          <form aria-label="Clone sheet" (submit)="cloneSheet($event)">
            <label for="sheet-clone-to">Clone to sheet id</label>
            <input
              id="sheet-clone-to"
              data-testid="sheet-clone-to"
              [value]="cloneInput()"
              (input)="cloneInput.set($any($event.target).value); sheetError.set(null)"
            />
            <button type="submit" data-testid="sheet-clone">Clone sheet</button>
            <p>Last clone made <strong data-testid="sheet-cloned-to">{{ clonedTo() }}</strong></p>
          </form>
          <p>Parent sheet <strong data-testid="sheet-parent">{{ session.parent() }}</strong></p>
          <form aria-label="As of" (submit)="setAsOf($event)">
            <p>As of <strong data-testid="sheet-asof">{{ session.asOf() ?? '' }}</strong></p>
            <label for="asof-date">Show as of</label>
            <input
              id="asof-date"
              type="date"
              data-testid="asof-date"
              [value]="asOfInput()"
              (input)="asOfInput.set($any($event.target).value); asOfError.set(null)"
            />
            <button type="submit" data-testid="asof-set">Set as of</button>
            <button type="button" data-testid="asof-clear" (click)="clearAsOf()">Clear as of</button>
            @if (asOfError(); as err) {
              <p role="alert" data-testid="asof-error">{{ err }}</p>
            }
          </form>
          <form aria-label="Preview date" (submit)="setPreview($event)">
            <p>Preview <strong data-testid="sheet-preview">{{ session.preview() ?? '' }}</strong></p>
            <label for="preview-date">Preview figures on</label>
            <input
              id="preview-date"
              type="date"
              data-testid="preview-date"
              [value]="previewInput()"
              (input)="previewInput.set($any($event.target).value); previewError.set(null)"
            />
            <button type="submit" data-testid="preview-set">Set preview</button>
            <button type="button" data-testid="preview-clear" (click)="clearPreview()">Clear preview</button>
            @if (previewError(); as err) {
              <p role="alert" data-testid="preview-error">{{ err }}</p>
            }
          </form>
          <app-people />
        } @else {
          <p data-testid="sheet-none">No sheet loaded.</p>
        }
      </section>
    } @else {
      <form aria-label="Sign in" (submit)="signIn($event)">
        <label for="signin-name">Your name</label>
        <input
          id="signin-name"
          data-testid="signin-name"
          [value]="nameInput()"
          (input)="nameInput.set($any($event.target).value); signinError.set(null)"
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
  protected readonly locales = LOCALES;
  protected readonly nameInput = signal('');
  protected readonly localeInput = linkedSignal<Locale>(() => this.session.locale());
  protected readonly localeError = signal<string | null>(null);
  protected readonly sheetInput = signal('');
  protected readonly signinError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);
  protected readonly todayInput = signal('');
  protected readonly asOfInput = signal('');
  protected readonly asOfError = signal<string | null>(null);
  protected readonly previewInput = signal('');
  protected readonly previewError = signal<string | null>(null);
  protected readonly cloneInput = signal('');
  /** The id of the last clone made of the sheet from this screen; blank for none. */
  protected readonly clonedTo = signal('');

  protected async signIn(event: Event): Promise<void> {
    event.preventDefault();
    this.signinError.set(null);
    const name = this.nameInput().trim();
    if (!name) {
      this.signinError.set('Enter a name to sign in.');
      return;
    }
    try {
      await this.session.signIn(name);
    } catch {
      this.signinError.set('Could not sign in.');
    }
  }

  protected async saveLocale(event: Event): Promise<void> {
    event.preventDefault();
    this.localeError.set(null);
    try {
      await this.session.saveLocale(this.localeInput());
    } catch {
      this.localeError.set('Could not save the locale.');
    }
  }

  protected async load(event: Event): Promise<void> {
    event.preventDefault();
    this.sheetError.set(null);
    const id = this.sheetInput().trim();
    if (!id) {
      this.sheetError.set('Enter a sheet id to load.');
      return;
    }
    try {
      if (id !== this.session.sheet()) this.clonedTo.set('');
      await this.session.loadSheet(id);
    } catch {
      this.sheetError.set('Could not load that sheet.');
    }
  }

  protected async saveToday(event: Event): Promise<void> {
    event.preventDefault();
    this.sheetError.set(null);
    const today = this.todayInput().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
      this.sheetError.set('Enter a date for today.');
      return;
    }
    try {
      await this.session.setToday(today);
    } catch {
      this.sheetError.set('Could not change the sheet\'s today.');
    }
  }

  protected async setAsOf(event: Event): Promise<void> {
    event.preventDefault();
    this.asOfError.set(null);
    const date = this.asOfInput().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.asOfError.set('Enter a date to show the sheet as of.');
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
      await this.session.clearAsOf();
    } catch {
      this.asOfError.set('Could not show the sheet as it is now.');
    }
  }

  protected async setPreview(event: Event): Promise<void> {
    event.preventDefault();
    this.previewError.set(null);
    const date = this.previewInput().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.previewError.set('Enter a date to preview the figures on.');
      return;
    }
    try {
      await this.session.setPreview(date);
    } catch {
      this.previewError.set('Could not preview the figures on that date.');
    }
  }

  protected async clearPreview(): Promise<void> {
    this.previewError.set(null);
    try {
      await this.session.clearPreview();
    } catch {
      this.previewError.set('Could not show the figures on the sheet\'s today.');
    }
  }

  protected async cloneSheet(event: Event): Promise<void> {
    event.preventDefault();
    this.sheetError.set(null);
    const to = this.cloneInput().trim();
    if (!to) {
      this.sheetError.set('Enter a sheet id to clone to.');
      return;
    }
    try {
      this.clonedTo.set(await this.session.cloneSheet(to));
    } catch {
      this.sheetError.set('Could not clone the sheet to that id.');
    }
  }

  protected async archive(): Promise<void> {
    this.sheetError.set(null);
    try {
      await this.session.archive();
    } catch {
      this.sheetError.set('Could not archive the sheet.');
    }
  }
}
