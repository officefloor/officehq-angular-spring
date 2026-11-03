import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, model, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's settings: its clerk limit (two decimals; blank for no limit), and its floor (two
// decimals; 1.00 on a new sheet), and its terms (whole days; 14 on a new sheet). A clerk settles a claim only when its due is at or below the
// limit; a claim's live due is never below the floor; a claim is due by its date plus the terms. Saving saves the clerk limit once its field
// has been entered (blank: no limit), and the floor and terms when typed (blank: left as they are).
interface SettingsResponse {
  /** The clerk limit, blank for none. */
  clerkLimit: string;
  floor: string;
  terms: number;
}

/** A limit or floor: 0.00 or more, with up to two decimals. */
const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;

const DEFAULT_FLOOR = '1.00';

/** Terms: a whole number of days. */
const TERMS = /^\d{1,4}$/;

const DEFAULT_TERMS = 14;

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
          [value]="limitInput() ?? ''"
          (focus)="enterLimit($any($event.target))"
          (input)="limitInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-floor">Floor (blank: leave as it is)</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floorInput() ?? ''"
          (input)="floorInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-terms">Terms in days (blank: leave as they are)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="termsInput() ?? ''"
          (input)="termsInput.set($any($event.target).value); error.set(null)"
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
      <p>
        Floor in force:
        <strong data-testid="setting-floor-current">{{ floor() }}</strong>
      </p>
      <p>
        Terms in force (days):
        <strong data-testid="setting-terms-current">{{ terms() }}</strong>
      </p>
    </section>
  `,
})
export class Settings {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's floor, shared with whoever works out what is due. */
  readonly floor = model(DEFAULT_FLOOR);
  /** The sheet's terms in days, shared with whoever works out when a claim is due by. */
  readonly terms = model(DEFAULT_TERMS);

  protected readonly limit = signal('');
  /** The clerk limit typed; null while its field has not been entered (left as it is). */
  protected readonly limitInput = signal<string | null>(null);
  /** The floor typed; blank (or null) leaves it as it is. */
  protected readonly floorInput = signal<string | null>(null);
  /** The terms typed; blank (or null) leaves them as they are. */
  protected readonly termsInput = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's settings from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.limit.set('');
      this.floor.set(DEFAULT_FLOOR);
      this.terms.set(DEFAULT_TERMS);
      this.limitInput.set(null);
      this.floorInput.set(null);
      this.termsInput.set(null);
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
      if (this.session.sheet() === sheet) this.show(res);
    } catch {
      this.error.set('Could not read the settings of this sheet.');
    }
  }

  /** Entering the clerk limit's field fills it with the limit in force, selected, so it saves as
   *  it is unless typed over. */
  protected enterLimit(input: HTMLInputElement): void {
    if (this.limitInput() !== null) return;
    input.value = this.limit();
    input.select();
    this.limitInput.set(this.limit());
  }

  private show(res: SettingsResponse): void {
    this.limit.set(res.clerkLimit);
    this.floor.set(res.floor);
    this.terms.set(res.terms);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const clerkLimit = this.limitInput()?.trim() ?? null;
    const floor = this.floorInput()?.trim() || null;
    const terms = this.termsInput()?.trim() || null;
    if (clerkLimit !== null && clerkLimit !== '' && !LIMIT.test(clerkLimit)) {
      this.error.set('Enter a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (floor !== null && !LIMIT.test(floor)) {
      this.error.set('Enter a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (terms !== null && !TERMS.test(terms)) {
      this.error.set('Enter terms of 0 or more whole days.');
      return;
    }
    this.submitting = true;
    try {
      const res = await firstValueFrom(
        this.http.post<SettingsResponse>('/api/settings/set', {
          sheet,
          user: this.session.user(),
          clerkLimit,
          floor,
          terms,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.show(res);
      this.limitInput.set(null);
      this.floorInput.set(null);
      this.termsInput.set(null);
    } catch {
      this.error.set('Could not save the settings.');
    } finally {
      this.submitting = false;
    }
  }
}
