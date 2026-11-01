import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';

// The one screen: sign in by name, then load a sheet by id.
@Component({
  selector: 'app-home',
  imports: [ReactiveFormsModule],
  template: `
    @if (session.user(); as user) {
      <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>
      <form [formGroup]="sheetForm" (ngSubmit)="loadSheet()" aria-label="Load sheet">
        <label for="sheet-id">Sheet id</label>
        <input id="sheet-id" data-testid="sheet-id-input" formControlName="id" />
        <button type="submit" data-testid="sheet-load">Load</button>
        @if (sheetError()) {
          <p role="alert" class="error" data-testid="sheet-error">{{ sheetError() }}</p>
        }
      </form>
      @if (session.sheetId(); as id) {
        <p>Sheet: <strong data-testid="sheet-current-id">{{ id }}</strong></p>
        <p>Today: <strong data-testid="sheet-today">{{ session.today() }}</strong></p>
        <form [formGroup]="todayForm" (ngSubmit)="saveToday()" aria-label="Sheet today">
          <label for="sheet-today">Change today</label>
          <input id="sheet-today" type="date" data-testid="sheet-today-input" formControlName="today" />
          <button type="submit" data-testid="sheet-today-save">Set today</button>
        </form>
      }
    } @else {
      <form [formGroup]="signinForm" (ngSubmit)="signIn()" aria-label="Sign in">
        <label for="signin-name">Name</label>
        <input id="signin-name" data-testid="signin-name" formControlName="name" />
        <button type="submit" data-testid="signin-submit">Sign in</button>
        @if (signinError()) {
          <p role="alert" class="error" data-testid="signin-error">{{ signinError() }}</p>
        }
      </form>
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
  protected readonly signinForm = new FormGroup({ name: new FormControl('', { nonNullable: true }) });
  protected readonly sheetForm = new FormGroup({ id: new FormControl('', { nonNullable: true }) });
  protected readonly todayForm = new FormGroup({ today: new FormControl('', { nonNullable: true }) });
  protected readonly signinError = signal('');
  protected readonly sheetError = signal('');

  constructor() {
    this.signinForm.valueChanges.subscribe(() => this.signinError.set(''));
    this.sheetForm.valueChanges.subscribe(() => this.sheetError.set(''));
    this.todayForm.valueChanges.subscribe(() => this.sheetError.set(''));
  }

  protected async signIn(): Promise<void> {
    this.signinError.set('');
    const name = this.signinForm.controls.name.value.trim();
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

  protected async loadSheet(): Promise<void> {
    this.sheetError.set('');
    const id = this.sheetForm.controls.id.value.trim();
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

  protected async saveToday(): Promise<void> {
    this.sheetError.set('');
    const today = this.todayForm.controls.today.value.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
      this.sheetError.set('Enter a date.');
      return;
    }
    try {
      await this.session.setToday(today);
    } catch {
      this.sheetError.set('Could not change today.');
    }
  }
}
