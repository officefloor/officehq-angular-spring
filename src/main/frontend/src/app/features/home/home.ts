import { Component, inject, signal } from '@angular/core';
import { Session } from '../../core/session';

// The one screen: sign in by name, then load a sheet by id.
@Component({
  selector: 'app-home',
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
}
