import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Session } from '../../core/session';

// Sign in by typing a name; there is no password.
@Component({
  selector: 'app-signin',
  imports: [ReactiveFormsModule],
  template: `
    <form data-testid="signin-form" [formGroup]="form" (ngSubmit)="submit()">
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
  protected readonly form = new FormGroup({ name: new FormControl('', { nonNullable: true, validators: Validators.required }) });
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  async submit(): Promise<void> {
    this.error.set('');
    const name = this.form.controls.name.value.trim();
    if (!name) {
      this.error.set('Enter a name to sign in.');
      return;
    }
    try {
      await this.session.signIn(name);
    } catch {
      this.error.set('Sign-in failed.');
    }
  }
}
