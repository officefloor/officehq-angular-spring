import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';

// Sign in by name (no password), then load a sheet by id. Shows the signed-in name and the
// active sheet id.
@Component({
  selector: 'app-session-bar',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-label="Session" data-testid="session-bar">
      @if (session.user(); as user) {
        <p>
          Signed in as <strong data-testid="current-user">{{ user }}</strong>
        </p>
        <form [formGroup]="sheetForm" (ngSubmit)="load()">
          <label for="sheet-id-input">Sheet id</label>
          <input id="sheet-id-input" data-testid="sheet-id-input" formControlName="id" />
          <button type="submit" data-testid="sheet-load">Load sheet</button>
        </form>
        @if (sheetError(); as e) {
          <p role="alert" data-testid="sheet-load-error">{{ e }}</p>
        }
        <p>
          Sheet: <strong data-testid="sheet-current-id">{{ session.sheetId() ?? '' }}</strong>
        </p>
      } @else {
        <form [formGroup]="signInForm" (ngSubmit)="signIn()">
          <label for="signin-name">Your name</label>
          <input id="signin-name" data-testid="signin-name" formControlName="name" />
          <button type="submit" data-testid="signin-submit">Sign in</button>
        </form>
        @if (signInError(); as e) {
          <p role="alert" data-testid="signin-error">{{ e }}</p>
        }
      }
    </section>
  `,
})
export class SessionBar {
  protected readonly session = inject(Session);
  protected readonly signInForm = new FormGroup({ name: new FormControl('', { nonNullable: true }) });
  protected readonly sheetForm = new FormGroup({ id: new FormControl('', { nonNullable: true }) });
  protected readonly signInError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);

  constructor() {
    this.signInForm.valueChanges.subscribe(() => this.signInError.set(null));
    this.sheetForm.valueChanges.subscribe(() => this.sheetError.set(null));
  }

  protected signIn(): void {
    this.signInError.set(null);
    const name = this.signInForm.getRawValue().name.trim();
    if (!name) {
      this.signInError.set('Type your name to sign in.');
      return;
    }
    this.session.signIn(name).subscribe({
      error: () => this.signInError.set('Could not sign in.'),
    });
  }

  protected load(): void {
    this.sheetError.set(null);
    const id = this.sheetForm.getRawValue().id.trim();
    if (!id || /\s/.test(id)) {
      this.sheetError.set('Type a sheet id without spaces.');
      return;
    }
    this.session.loadSheet(id).subscribe({
      error: () => this.sheetError.set('Could not load that sheet.'),
    });
  }
}
