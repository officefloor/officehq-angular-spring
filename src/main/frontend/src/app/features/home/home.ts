import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Session } from '../../core/session';
import { Sheets } from '../../core/sheet';

// The one screen: sign in by name, then choose a sheet by id. Everything else is per sheet.
@Component({
  selector: 'app-home',
  imports: [ReactiveFormsModule],
  template: `
    @if (session.user(); as user) {
      <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>

      <form [formGroup]="sheetForm" (ngSubmit)="loadSheet()" aria-label="Load a sheet">
        <label for="sheet-id">Sheet id</label>
        <input id="sheet-id" data-testid="sheet-id-input" formControlName="id" autocomplete="off" />
        <button type="submit" data-testid="sheet-load" [disabled]="loading()">Load</button>
        @if (sheetError(); as err) {
          <p role="alert" data-testid="sheet-load-error">{{ err }}</p>
        }
      </form>

      @if (sheets.current(); as sheet) {
        <p>Sheet: <strong data-testid="sheet-current-id">{{ sheet.id }}</strong></p>
      } @else {
        <p data-testid="sheet-none">Load a sheet to begin.</p>
      }
    } @else {
      <form [formGroup]="signInForm" (ngSubmit)="signIn()" aria-label="Sign in">
        <label for="signin-name">Your name</label>
        <input id="signin-name" data-testid="signin-name" formControlName="name" autocomplete="username" />
        <button type="submit" data-testid="signin-submit">Sign in</button>
        @if (signInError(); as err) {
          <p role="alert" data-testid="signin-error">{{ err }}</p>
        }
      </form>
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
  protected readonly sheets = inject(Sheets);

  protected readonly signInForm = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: Validators.required }),
  });
  protected readonly sheetForm = new FormGroup({
    id: new FormControl('', { nonNullable: true, validators: Validators.required }),
  });
  protected readonly signInError = signal<string | null>(null);
  protected readonly sheetError = signal<string | null>(null);
  protected readonly loading = signal(false);

  constructor() {
    // An error is removed as soon as its form is changed.
    this.signInForm.valueChanges.subscribe(() => this.signInError.set(null));
    this.sheetForm.valueChanges.subscribe(() => this.sheetError.set(null));
  }

  protected signIn(): void {
    this.signInError.set(null);
    const name = this.signInForm.controls.name.value.trim();
    if (!name) {
      this.signInError.set('Type your name to sign in.');
      return;
    }
    this.session.signIn(name).subscribe({
      error: () => this.signInError.set('Could not sign in with that name.'),
    });
  }

  protected loadSheet(): void {
    this.sheetError.set(null);
    const id = this.sheetForm.controls.id.value.trim();
    if (!id) {
      this.sheetError.set('Type a sheet id to load.');
      return;
    }
    this.loading.set(true);
    this.sheets.load(id).subscribe({
      next: () => this.loading.set(false),
      error: () => {
        this.loading.set(false);
        this.sheetError.set('Could not load that sheet.');
      },
    });
  }
}
