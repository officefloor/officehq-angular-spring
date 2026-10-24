import { Component, inject, signal } from '@angular/core';
import { Session } from './session';

// Sign-in by name (no password), the signed-in name, and the sheet chooser with the active id.
@Component({
  selector: 'app-session-bar',
  template: `
    @if (session.user(); as user) {
      <section class="bar" aria-label="Session">
        <span
          >Signed in as <strong data-testid="current-user">{{ user }}</strong></span
        >
        <form (submit)="load($event)">
          <label for="sheet-id">Sheet</label>
          <input
            id="sheet-id"
            data-testid="sheet-id-input"
            [value]="sheetInput()"
            (input)="sheetInput.set(value($event)); sheetError.set(false)"
          />
          <button type="submit" data-testid="sheet-load">Load</button>
        </form>
        @if (session.sheetId(); as sheet) {
          <span
            >Sheet <strong data-testid="sheet-current-id">{{ sheet }}</strong></span
          >
          <span
            >Today <strong data-testid="sheet-today">{{ session.today() }}</strong></span
          >
          <form (submit)="saveToday($event)">
            <label for="sheet-today-input">Change today</label>
            <input
              id="sheet-today-input"
              type="date"
              data-testid="sheet-today-input"
              [value]="todayInput()"
              (input)="todayInput.set(value($event)); todayError.set(false)"
            />
            <button type="submit" data-testid="sheet-today-save">Set today</button>
          </form>
          <span
            >Archived
            <strong data-testid="sheet-archived">{{ session.archived() ? 'yes' : 'no' }}</strong></span
          >
          <button type="button" data-testid="sheet-archive" (click)="archive()">
            Archive sheet
          </button>
          @if (todayError()) {
            <p role="alert" class="error" data-testid="sheet-error">
              That date could not be set as today.
            </p>
          }
          @if (archiveError()) {
            <p role="alert" class="error" data-testid="sheet-error">
              The sheet could not be archived.
            </p>
          }
        }
        @if (sheetError()) {
          <p role="alert" class="error" data-testid="sheet-load-error">
            That sheet could not be loaded.
          </p>
        }
      </section>
    } @else {
      <form class="bar" (submit)="signIn($event)" aria-label="Sign in">
        <label for="signin-name">Your name</label>
        <input
          id="signin-name"
          data-testid="signin-name"
          [value]="nameInput()"
          (input)="nameInput.set(value($event)); signinError.set(false)"
        />
        <button type="submit" data-testid="signin-submit">Sign in</button>
        @if (signinError()) {
          <p role="alert" class="error" data-testid="signin-error">Type a name to sign in.</p>
        }
      </form>
    }
  `,
  styles: `
    .bar {
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem;
      align-items: center;
      padding: 0.5rem 0;
    }
    form {
      display: flex;
      gap: 0.5rem;
      align-items: center;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class SessionBar {
  protected readonly session = inject(Session);
  protected readonly nameInput = signal('');
  protected readonly sheetInput = signal('');
  protected readonly signinError = signal(false);
  protected readonly sheetError = signal(false);
  protected readonly todayInput = signal('');
  protected readonly todayError = signal(false);
  protected readonly archiveError = signal(false);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected signIn(event: Event): void {
    event.preventDefault();
    this.signinError.set(!this.session.signIn(this.nameInput()));
  }

  protected async load(event: Event): Promise<void> {
    event.preventDefault();
    this.sheetError.set(false);
    this.todayError.set(false);
    this.archiveError.set(false);
    this.sheetError.set(!(await this.session.loadSheet(this.sheetInput())));
  }

  protected async saveToday(event: Event): Promise<void> {
    event.preventDefault();
    this.todayError.set(false);
    this.archiveError.set(false);
    this.todayError.set(!(await this.session.setToday(this.todayInput())));
  }

  protected async archive(): Promise<void> {
    this.todayError.set(false);
    this.archiveError.set(false);
    this.archiveError.set(!(await this.session.archive()));
  }
}
