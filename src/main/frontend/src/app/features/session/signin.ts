import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { SessionService } from '../../core/session.service';

// Sign in by typing a name.
@Component({
  selector: 'app-signin',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Sign in">
      <label for="signin-name">Name</label>
      <input id="signin-name" data-testid="signin-name" formControlName="name" autocomplete="username" />
      <button type="submit" data-testid="signin-submit">Sign in</button>
      @if (error()) {
        <p role="alert" data-testid="signin-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class SignIn {
  private readonly session = inject(SessionService);
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
  });
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected submit(): void {
    this.error.set('');
    const name = this.form.controls.name.value.trim();
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    this.session.signIn(name).subscribe({ error: () => this.error.set('Could not sign in.') });
  }
}
