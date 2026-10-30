import { Component, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { People } from '../people/people';

// The one screen: sign in by name, then load a sheet by id.
@Component({
  selector: 'app-home',
  imports: [People],
  template: `
    @if (session.user(); as user) {
      <section aria-label="Session">
        <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>
        <form (submit)="load($event)">
          <label for="sheet-id">Sheet id</label>
          <input
            id="sheet-id"
            data-testid="sheet-id-input"
            [value]="sheetInput()"
            (input)="sheetInput.set(value($event)); sheetError.set('')"
          />
          <button type="submit" data-testid="sheet-load">Load</button>
          @if (sheetError()) {
            <p role="alert" data-testid="sheet-error">{{ sheetError() }}</p>
          }
        </form>
        @if (session.sheet(); as sheet) {
          <p>Sheet <strong data-testid="sheet-current-id">{{ sheet }}</strong></p>
          <form (submit)="saveToday($event)" aria-label="Sheet today">
            <p>Today <strong data-testid="sheet-today">{{ session.today() }}</strong></p>
            <label for="sheet-today-input">Set today</label>
            <input
              id="sheet-today-input"
              type="date"
              data-testid="sheet-today-input"
              [value]="todayInput()"
              (input)="todayInput.set(value($event)); sheetError.set('')"
            />
            <button type="submit" data-testid="sheet-today-save">Set today</button>
          </form>
          <app-people />
        }
      </section>
    } @else {
      <form (submit)="signIn($event)" aria-label="Sign in">
        <label for="signin-name">Name</label>
        <input
          id="signin-name"
          data-testid="signin-name"
          [value]="nameInput()"
          (input)="nameInput.set(value($event)); signinError.set('')"
        />
        <button type="submit" data-testid="signin-submit">Sign in</button>
        @if (signinError()) {
          <p role="alert" data-testid="signin-error">{{ signinError() }}</p>
        }
      </form>
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
  protected readonly nameInput = signal('');
  protected readonly sheetInput = signal('');
  protected readonly signinError = signal('');
  protected readonly sheetError = signal('');
  protected readonly todayInput = signal('');

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async signIn(e: Event): Promise<void> {
    e.preventDefault();
    this.signinError.set('');
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
    this.sheetError.set('');
    const id = this.sheetInput().trim();
    if (!id) {
      this.sheetError.set('Enter a sheet id.');
      return;
    }
    try {
      await this.session.loadSheet(id);
    } catch {
      this.sheetError.set('Could not load the sheet.');
    }
  }

  protected async saveToday(e: Event): Promise<void> {
    e.preventDefault();
    this.sheetError.set('');
    const today = this.todayInput().trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
      this.sheetError.set('Enter a date for today.');
      return;
    }
    try {
      await this.session.setToday(today);
    } catch {
      this.sheetError.set('Could not change today.');
    }
  }
}
