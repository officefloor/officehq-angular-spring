import { Component, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { People } from '../people/people';

// The one screen: sign in by name, then load a sheet by id.
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
        <app-people />
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
    try {
      await this.session.loadSheet(id);
    } catch {
      this.sheetError.set(`Sheet ${id} could not be loaded.`);
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
