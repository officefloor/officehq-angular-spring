import { Component, inject, signal } from '@angular/core';
import { ClaimTemplates } from '../../core/claim-templates';
import { Claims } from '../../core/claims';
import { Holidays } from '../../core/holidays';
import { People } from '../../core/people';
import { Periods } from '../../core/periods';
import { Plans } from '../../core/plans';
import { Pools } from '../../core/pools';
import { Rates } from '../../core/rates';
import { Roles } from '../../core/roles';
import { Session, isDate } from '../../core/session';
import { VoidReasons } from '../../core/void-reasons';
import { AuditPanel } from '../audit/audit';
import { ClaimTemplatesPanel } from '../claim-templates/claim-templates';
import { BulkOpen } from '../claims/bulk-open';
import { BulkReassign } from '../claims/bulk-reassign';
import { BulkRedate } from '../claims/bulk-redate';
import { BulkSettle } from '../claims/bulk-settle';
import { PasteClaims } from '../claims/paste-claims';
import { HeaderCounters } from '../header/header';
import { HolidaysPanel } from '../holidays/holidays';
import { ImportPeople } from '../people/import-people';
import { PeopleList } from '../people/people';
import { PeriodsPanel } from '../periods/periods';
import { GeneratePlans } from '../plans/generate-plans';
import { PoolsPanel } from '../pools/pools';
import { ImportRates } from '../rates/import-rates';
import { RatesPanel } from '../rates/rates';
import { SearchPanel } from '../search/search';
import { SettingsPanel } from '../settings/settings';
import { SummaryPanel } from '../summary/summary';
import { VoidReasonsPanel } from '../void-reasons/void-reasons';

// The one screen: sign in by name, then choose a sheet by id. Everything else is kept per sheet.
@Component({
  selector: 'app-home',
  imports: [AuditPanel, BulkOpen, BulkReassign, BulkRedate, BulkSettle, ClaimTemplatesPanel, GeneratePlans, HeaderCounters, HolidaysPanel, ImportPeople, ImportRates, PasteClaims, PeopleList, PeriodsPanel, PoolsPanel, RatesPanel, SearchPanel, SettingsPanel, SummaryPanel, VoidReasonsPanel],
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
          <p>Archived: <strong data-testid="sheet-archived">{{ session.archived() ? 'yes' : 'no' }}</strong></p>
          <button type="button" data-testid="sheet-archive" [disabled]="archiving()" (click)="archive()">Archive sheet</button>
          <form (submit)="cloneSheet($event)">
            <label for="sheet-clone-to">Clone to sheet id</label>
            <input
              id="sheet-clone-to"
              data-testid="sheet-clone-to"
              [value]="cloneInput()"
              (input)="cloneInput.set(value($event)); todayError.set(null)"
            />
            <button type="submit" data-testid="sheet-clone" [disabled]="cloning()">Clone sheet</button>
          </form>
          <p>Cloned to: <strong data-testid="sheet-cloned-to">{{ clonedTo() ?? '' }}</strong></p>
          @if (todayError(); as err) {
            <p role="alert" data-testid="sheet-error">{{ err }}</p>
          }
          <p>As of: <strong data-testid="sheet-asof">{{ session.asOf() ?? '' }}</strong></p>
          <p>Parent sheet: <strong data-testid="sheet-parent">{{ session.parent() ?? '' }}</strong></p>
          <form (submit)="setAsOf($event)">
            <label for="asof-date">Show the sheet as of</label>
            <input
              id="asof-date"
              data-testid="asof-date"
              placeholder="YYYY-MM-DD"
              [value]="asOfInput()"
              (input)="asOfInput.set(value($event)); asOfError.set(null)"
            />
            <button type="submit" data-testid="asof-set" [disabled]="loading()">Set as of</button>
            <button type="button" data-testid="asof-clear" [disabled]="loading()" (click)="clearAsOf()">Clear as of</button>
          </form>
          @if (asOfError(); as err) {
            <p role="alert" data-testid="asof-error">{{ err }}</p>
          }
        }
      </section>
      @if (session.sheetId()) {
        <app-settings />
        <app-rates />
        <app-import-rates />
        <app-periods />
        <app-holidays />
        <app-void-reasons />
        <app-claim-templates />
        <app-people />
        <app-import-people />
        <app-pools />
        <app-paste-claims />
        <app-generate-plans />
        <app-bulk-open />
        <app-bulk-settle />
        <app-bulk-reassign />
        <app-bulk-redate />
        <app-summary />
        <app-search />
        <app-audit />
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
  private readonly holidays = inject(Holidays);
  private readonly voidReasons = inject(VoidReasons);
  private readonly claimTemplates = inject(ClaimTemplates);
  private readonly pools = inject(Pools);
  private readonly plans = inject(Plans);
  protected readonly nameInput = signal('');
  protected readonly signinError = signal<string | null>(null);
  protected readonly sheetInput = signal('');
  protected readonly sheetError = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly todayInput = signal('');
  protected readonly todayError = signal<string | null>(null);
  protected readonly savingToday = signal(false);
  protected readonly archiving = signal(false);
  protected readonly cloneInput = signal('');
  protected readonly cloning = signal(false);
  /** The id of the sheet the last clone made; cleared while a clone runs and when it is refused. */
  protected readonly clonedTo = signal<string | null>(null);
  protected readonly asOfInput = signal('');
  protected readonly asOfError = signal<string | null>(null);

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
      this.sheetError.set(await this.loadSheet(this.sheetInput()));
    } finally {
      this.loading.set(false);
    }
  }

  /** Load a sheet and everything on it, as of the screen's as-of date while one is set. */
  private async loadSheet(id: string): Promise<string | null> {
    return (
      (await this.session.loadSheet(id)) ??
      (await Promise.all([this.people.load(), this.claims.load(), this.rates.load(), this.periods.load(), this.roles.load(), this.holidays.load(), this.voidReasons.load(), this.claimTemplates.load(), this.pools.load(), this.plans.load()])).find((err) => err !== null) ??
      null
    );
  }

  /** Show the sheet as of a date on this screen only: everything is re-read as it stood at the end of it. */
  protected async setAsOf(e: Event): Promise<void> {
    e.preventDefault();
    const d = this.asOfInput().trim();
    this.asOfError.set(null);
    if (!isDate(d)) {
      this.asOfError.set('Enter an as-of date as YYYY-MM-DD.');
      return;
    }
    await this.showAsOf(d);
  }

  /** Show the sheet as it is again. */
  protected async clearAsOf(): Promise<void> {
    this.asOfError.set(null);
    await this.showAsOf(null);
  }

  private async showAsOf(date: string | null): Promise<void> {
    const sid = this.session.sheetId();
    if (sid === null || this.loading()) return;
    this.todayError.set(null);
    this.loading.set(true);
    try {
      this.session.asOf.set(date);
      this.asOfError.set(await this.loadSheet(sid));
    } finally {
      this.loading.set(false);
    }
  }

  protected async archive(): Promise<void> {
    if (this.archiving()) return; // a second press before the first has answered
    this.todayError.set(null);
    this.archiving.set(true);
    try {
      this.todayError.set(await this.session.archive());
    } finally {
      this.archiving.set(false);
    }
  }

  protected async cloneSheet(e: Event): Promise<void> {
    e.preventDefault();
    if (this.cloning()) return; // a second submit before the first has answered
    this.todayError.set(null);
    this.clonedTo.set(null);
    this.cloning.set(true);
    try {
      const to = this.cloneInput().trim();
      const err = await this.session.cloneSheet(to);
      if (err === null) this.clonedTo.set(to);
      this.todayError.set(err);
    } finally {
      this.cloning.set(false);
    }
  }

  protected async saveToday(e: Event): Promise<void> {
    e.preventDefault();
    if (this.savingToday()) return; // a second submit before the first has answered
    this.todayError.set(null);
    this.savingToday.set(true);
    try {
      // A today change may close periods past their grace days, and moves the plans' next dates, so the
      // periods and plans are re-read; it also releases every claim's lock, closing their editors.
      const was = this.session.today();
      const err = await this.session.setToday(this.todayInput());
      if (err === null && this.session.today() !== was) this.claims.unlockAll();
      this.todayError.set(
        err ?? (await Promise.all([this.periods.load(), this.plans.load()])).find((e) => e !== null) ?? null,
      );
    } finally {
      this.savingToday.set(false);
    }
  }
}
