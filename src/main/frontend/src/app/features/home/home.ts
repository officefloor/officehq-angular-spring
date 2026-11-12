import { Component, inject, signal } from '@angular/core';
import { Session } from '../../core/session';

// The one screen: sign in by name, then choose a sheet by id. Everything else is kept per sheet.
@Component({
  selector: 'app-home',
  template: `
    @if (session.user(); as user) {
      <section aria-label="Session" data-testid="session-bar">
        <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>
        <form (submit)="load($event)">
          <label for="sheet-id-input">Sheet id</label>
          <input
            id="sheet-id-input"
            data-testid="sheet-id-input"
            [value]="sheetInput()"
            (input)="sheetInput.set(value($event)); sheetError.set(null)"
          />
          <button type="submit" data-testid="sheet-load" [disabled]="loading()">Load</button>
        </form>
        @if (sheetError(); as err) {
          <p role="alert" data-testid="sheet-load-error">{{ err }}</p>
        }
        <p>Sheet: <strong data-testid="sheet-current-id">{{ session.sheetId() ?? '' }}</strong></p>
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
  protected readonly signinError = signal<string | null>(null);
  protected readonly sheetInput = signal('');
  protected readonly sheetError = signal<string | null>(null);
  protected readonly loading = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected signIn(e: Event): void {
    e.preventDefault();
    this.signinError.set(this.session.signIn(this.nameInput()) ? null : 'Enter your name.');
  }

  protected async load(e: Event): Promise<void> {
    e.preventDefault();
    this.sheetError.set(null);
    this.loading.set(true);
    try {
      this.sheetError.set(await this.session.loadSheet(this.sheetInput()));
    } finally {
      this.loading.set(false);
    }
  }
}
