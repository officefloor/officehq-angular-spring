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
}
