import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's settings: its clerk limit (two decimals; blank for no limit). A clerk settles a
// claim only when its due is at or below the limit.
interface SettingsResponse {
  /** The clerk limit, blank for none. */
  clerkLimit: string;
}

/** A limit: 0.00 or more, with up to two decimals. */
const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;

@Component({
  selector: 'app-settings',
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form aria-label="Settings" (submit)="save($event)">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="limitInput()"
          (input)="limitInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (error(); as err) {
          <p role="alert" data-testid="settings-error">{{ err }}</p>
        }
      </form>
      <p>
        Clerk limit in force:
        <strong data-testid="setting-clerk-limit-current">{{ limit() }}</strong>
        @if (limit() === '') {
          <span>(no limit)</span>
        }
      </p>
    </section>
  `,
})
export class Settings {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly limit = signal('');
  protected readonly limitInput = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's settings from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.limit.set('');
      this.limitInput.set('');
      this.error.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const res = await firstValueFrom(
        this.http.post<SettingsResponse>('/api/settings/get', { sheet, user: this.session.user() }),
      );
      if (this.session.sheet() === sheet) this.limit.set(res.clerkLimit);
    } catch {
      this.error.set('Could not read the settings of this sheet.');
    }
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const clerkLimit = this.limitInput().trim();
    if (clerkLimit !== '' && !LIMIT.test(clerkLimit)) {
      this.error.set('Enter a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    this.submitting = true;
    try {
      const res = await firstValueFrom(
        this.http.post<SettingsResponse>('/api/settings/set', { sheet, user: this.session.user(), clerkLimit }),
      );
      if (this.session.sheet() !== sheet) return;
      this.limit.set(res.clerkLimit);
      this.limitInput.set('');
    } catch {
      this.error.set('Could not save the settings.');
    } finally {
      this.submitting = false;
    }
  }
}
