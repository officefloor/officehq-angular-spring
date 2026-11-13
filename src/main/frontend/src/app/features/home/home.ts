import { Component, inject, signal } from '@angular/core';
import { Session } from '../../core/session';

// The one screen: sign in by name, then load a sheet by id.
@Component({
  selector: 'app-home',
  template: `
    @if (session.user(); as user) {
      <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>

      <form (submit)="load($event)">
        <label for="sheet-id">Sheet id</label>
        <input
          id="sheet-id"
          data-testid="sheet-id-input"
          [value]="sheetInput()"
          (input)="sheetInput.set(value($event)); sheetError.set(null)"
        />
        <button type="submit" data-testid="sheet-load">Load</button>
      </form>
      @if (sheetError(); as error) {
        <p role="alert" data-testid="sheet-load-error">{{ error }}</p>
      }

      @if (session.sheetId(); as sheet) {
        <p>Sheet <strong data-testid="sheet-current-id">{{ sheet }}</strong></p>
      } @else {
        <p data-testid="home-empty">No sheet loaded.</p>
      }
    } @else {
      <form (submit)="signIn($event)">
        <label for="signin-name">Name</label>
        <input
          id="signin-name"
          data-testid="signin-name"
          [value]="nameInput()"
          (input)="nameInput.set(value($event)); signinError.set(null)"
        />
        <button type="submit" data-testid="signin-submit">Sign in</button>
      </form>
      @if (signinError(); as error) {
        <p role="alert" data-testid="signin-error">{{ error }}</p>
      }
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
  protected readonly nameInput = signal('');
  protected readonly sheetInput = signal('');
  protected readonly signinError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected signIn(event: Event): void {
    event.preventDefault();
    this.signinError.set(this.session.signIn(this.nameInput()) ? null : 'Type a name to sign in.');
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
      this.sheetError.set('The sheet could not be loaded.');
    }
  }
}
