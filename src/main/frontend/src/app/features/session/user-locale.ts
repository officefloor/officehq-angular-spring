import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { LOCALES, Locale, UserLocale } from '../../core/user-locale';

// Choose the signed-in user's locale: how figures are shown to them.
@Component({
  selector: 'app-user-locale',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-label="Locale">
      <label for="user-locale">Locale</label>
      <select id="user-locale" data-testid="user-locale" [formControl]="choice">
        @for (l of locales; track l) {
          <option [value]="l">{{ l }}</option>
        }
      </select>
      <button type="button" data-testid="user-locale-save" (click)="save()">Save locale</button>
      <span>Locale now: <strong data-testid="user-locale-current">{{ userLocale.locale() }}</strong></span>
      @if (error()) {
        <p role="alert" data-testid="user-locale-error">{{ error() }}</p>
      }
    </section>
  `,
})
export class UserLocaleChoice {
  protected readonly userLocale = inject(UserLocale);
  protected readonly locales = LOCALES;
  protected readonly choice = new FormControl<Locale>(this.userLocale.locale(), { nonNullable: true });
  protected readonly error = signal('');

  constructor() {
    this.choice.valueChanges.subscribe(() => this.error.set(''));
    effect(() => this.choice.setValue(this.userLocale.locale(), { emitEvent: false }));
  }

  protected async save(): Promise<void> {
    this.error.set('');
    try {
      await this.userLocale.save(this.choice.value);
    } catch {
      this.error.set('The locale could not be saved.');
    }
  }
}
