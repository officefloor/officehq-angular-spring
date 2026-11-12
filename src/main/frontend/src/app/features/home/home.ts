import { Component, inject, signal } from '@angular/core';
import { Claims } from '../../core/claims';
import { People } from '../../core/people';
import { Periods } from '../../core/periods';
import { Rates } from '../../core/rates';
import { Roles } from '../../core/roles';
import { Session } from '../../core/session';
import { BulkOpen } from '../claims/bulk-open';
import { HeaderCounters } from '../header/header';
import { PeopleList } from '../people/people';
import { PeriodsPanel } from '../periods/periods';
import { RatesPanel } from '../rates/rates';
import { SettingsPanel } from '../settings/settings';
import { SummaryPanel } from '../summary/summary';

// The one screen: sign in by name, then choose a sheet by id. Everything else is kept per sheet.
@Component({
  selector: 'app-home',
  imports: [BulkOpen, HeaderCounters, PeopleList, PeriodsPanel, RatesPanel, SettingsPanel, SummaryPanel],
  template: `
    @if (session.user(); as user) {
      @if (session.sheetId()) {
        <app-header />
      }
      <section aria-label="Session" data-testid="session-bar">
        <p>Signed in as <strong data-testid="current-user">{{ user }}</strong></p>
        <form (submit)="load($event)">
          <label for="sheet-id-input">Sheet id</label>
          <input
            id="sheet-id-input"
            data-testid="sheet-id-input"
            [value]="sheetInput()"
            (input)="sheetInput.set(value($event)); sheetError.set(null)"
          />
          <button type="submit" data-testid="sheet-load" [disabled]="loading()">Load</button>
        </form>
        @if (sheetError(); as err) {
          <p role="alert" data-testid="sheet-load-error">{{ err }}</p>
        }
        <p>Sheet: <strong data-testid="sheet-current-id">{{ session.sheetId() ?? '' }}</strong></p>
        @if (session.sheetId()) {
          <p>Today: <strong data-testid="sheet-today">{{ session.today() ?? '' }}</strong></p>
          <form (submit)="saveToday($event)">
            <label for="sheet-today-input">Change today</label>
            <input
              id="sheet-today-input"
              data-testid="sheet-today-input"
              placeholder="YYYY-MM-DD"
              [value]="todayInput()"
              (input)="todayInput.set(value($event)); todayError.set(null)"
            />
            <button type="submit" data-testid="sheet-today-save" [disabled]="savingToday()">Set today</button>
          </form>
          @if (todayError(); as err) {
            <p role="alert" data-testid="sheet-error">{{ err }}</p>
          }
        }
      </section>
      @if (session.sheetId()) {
        <app-settings />
        <app-rates />
        <app-periods />
        <app-people />
        <app-bulk-open />
        <app-summary />
      }
    } @else {
      <form aria-label="Sign in" (submit)="signIn($event)">
        <label for="signin-name">Your name</label>
        <input
          id="signin-name"
          data-testid="signin-name"
          [value]="nameInput()"
          (input)="nameInput.set(value($event)); signinError.set(null)"
        />
        <button type="submit" data-testid="signin-submit">Sign in</button>
        @if (signinError(); as err) {
          <p role="alert" data-testid="signin-error">{{ err }}</p>
        }
      </form>
    }
  `,
})
export class Home {
  protected readonly session = inject(Session);
  private readonly people = inject(People);
  private readonly claims = inject(Claims);
  private readonly rates = inject(Rates);
  private readonly periods = inject(Periods);
  private readonly roles = inject(Roles);
  protected readonly nameInput = signal('');
  protected readonly signinError = signal<string | null>(null);
  protected readonly sheetInput = signal('');
  protected readonly sheetError = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly todayInput = signal('');
  protected readonly todayError = signal<string | null>(null);
  protected readonly savingToday = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected signIn(e: Event): void {
    e.preventDefault();
    this.signinError.set(this.session.signIn(this.nameInput()) ? null : 'Enter your name.');
  }

  protected async load(e: Event): Promise<void> {
    e.preventDefault();
    this.sheetError.set(null);
    this.todayError.set(null);
    this.loading.set(true);
    try {
      this.sheetError.set(
        (await this.session.loadSheet(this.sheetInput())) ??
          (await Promise.all([this.people.load(), this.claims.load(), this.rates.load(), this.periods.load(), this.roles.load()])).find((err) => err !== null) ??
          null,
      );
    } finally {
      this.loading.set(false);
    }
  }

  protected async saveToday(e: Event): Promise<void> {
    e.preventDefault();
    if (this.savingToday()) return; // a second submit before the first has answered
    this.todayError.set(null);
    this.savingToday.set(true);
    try {
      this.todayError.set(await this.session.setToday(this.todayInput()));
    } finally {
      this.savingToday.set(false);
    }
  }
}
