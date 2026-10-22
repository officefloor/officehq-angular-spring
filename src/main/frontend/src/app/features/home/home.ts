import { Component, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { People } from '../people/people';

// The one screen: sign in by typing a name, then load a sheet by id and work on its people.
@Component({
  selector: 'app-home',
  imports: [People],
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
            <button type="submit" data-testid="sheet-today-save">Save today</button>
          </form>
          <app-people />
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
  protected readonly signinError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);
  /** A today change is waiting for its answer: a second submit meanwhile is ignored. */
  private savingToday = false;

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
}
