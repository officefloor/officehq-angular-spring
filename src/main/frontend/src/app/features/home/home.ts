import { Component, inject, linkedSignal, signal } from '@angular/core';
import { Session } from '../../core/session';
import { People } from '../people/people';

// The one screen: sign in by name, then choose a sheet by id.
@Component({
  selector: 'app-home',
  imports: [People],
  template: `
    @if (session.user(); as user) {
      <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>

      <form (submit)="load($event)">
        <label for="sheet-id">Sheet id</label>
        <input
          id="sheet-id"
          data-testid="sheet-id-input"
          [value]="sheetInput()"
          (input)="sheetInput.set($any($event.target).value); sheetError.set(null)"
        />
        <button type="submit" data-testid="sheet-load">Load</button>
        @if (sheetError(); as err) {
          <p role="alert" data-testid="sheet-error">{{ err }}</p>
        }
      </form>

      @if (session.sheetId(); as id) {
        <p>Sheet <strong data-testid="sheet-current-id">{{ id }}</strong></p>
        <p>Today <strong data-testid="sheet-today">{{ session.today() }}</strong></p>

        <form (submit)="saveToday($event)">
          <label for="sheet-today">Change today</label>
          <input
            id="sheet-today"
            type="date"
            data-testid="sheet-today-input"
            [value]="todayInput()"
            (input)="todayInput.set($any($event.target).value); todayError.set(null)"
          />
          <button type="submit" data-testid="sheet-today-save">Save</button>
          @if (todayError(); as err) {
            <p role="alert" data-testid="sheet-today-error">{{ err }}</p>
          }
        </form>

        <app-people />
      }
    } @else {
      <form (submit)="signIn($event)">
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
  protected readonly nameInput = signal('');
  protected readonly sheetInput = signal('');
  protected readonly signinError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);
  protected readonly todayInput = linkedSignal(() => this.session.today() ?? '');
  protected readonly todayError = signal<string | null>(null);

  protected signIn(event: Event): void {
    event.preventDefault();
    const name = this.nameInput().trim();
    if (!name) {
      this.signinError.set('Enter your name.');
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
      this.sheetError.set('Enter a sheet id.');
      return;
    }
    try {
      await this.session.loadSheet(id);
    } catch {
      this.sheetError.set('The sheet could not be loaded.');
    }
  }

  protected async saveToday(event: Event): Promise<void> {
    event.preventDefault();
    this.todayError.set(null);
    const today = this.todayInput().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
      this.todayError.set('Enter a date as yyyy-mm-dd.');
      return;
    }
    try {
      await this.session.setToday(today);
    } catch {
      this.todayError.set('That date could not be saved.');
    }
  }
}
