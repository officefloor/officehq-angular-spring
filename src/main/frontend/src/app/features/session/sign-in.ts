import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';

// Sign in by typing a name; there is no password.
@Component({
  selector: 'app-sign-in',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Sign in">
      <label for="signin-name">Your name</label>
      <input id="signin-name" data-testid="signin-name" formControlName="name" autocomplete="username" />
      <button type="submit" data-testid="signin-submit">Sign in</button>
      @if (error()) {
        <p role="alert" data-testid="signin-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class SignIn {
  private readonly session = inject(Session);

  protected readonly name = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ name: this.name });
  protected readonly error = signal('');

  constructor() {
    this.name.valueChanges.subscribe(() => this.error.set(''));
  }

  protected submit(): void {
    const name = this.name.value.trim();
    if (!name) {
      this.error.set('Type your name to sign in.');
      return;
    }
    this.error.set('');
    this.session.signIn(name);
  }
}
