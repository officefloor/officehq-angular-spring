import { Component, inject, signal } from '@angular/core';
import { Session } from '../../core/session';

// The one screen: sign in by name, then load a sheet by id.
@Component({
  selector: 'app-home',
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
}
