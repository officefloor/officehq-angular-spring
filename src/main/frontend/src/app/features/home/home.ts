import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { AuditView } from '../audit-view/audit-view';
import { Holidays } from '../holidays/holidays';
import { People } from '../people/people';
import { Pools } from '../pools/pools';
import { Settings } from '../settings/settings';
import { Templates } from '../templates/templates';
import { VoidReasons } from '../void-reasons/void-reasons';

// The one screen: sign in by name, then load a sheet by id.
@Component({
  selector: 'app-home',
  imports: [ReactiveFormsModule, AuditView, Holidays, People, Pools, Settings, Templates, VoidReasons],
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
        <form [formGroup]="asOfForm" (ngSubmit)="setAsOf()" aria-label="As of">
          <label for="asof-date">View as of</label>
          <input id="asof-date" type="date" data-testid="asof-date" formControlName="date" />
          <button type="submit" data-testid="asof-set">Set as of</button>
          <button type="button" data-testid="asof-clear" (click)="clearAsOf()">Clear as of</button>
        </form>
        <p>As of: <strong data-testid="sheet-asof">{{ session.asOf() ?? '' }}</strong></p>
        <p>Parent sheet: <strong data-testid="sheet-parent">{{ session.parent() ?? '' }}</strong></p>
        <p>Archived: <strong data-testid="sheet-archived">{{ session.archived() ? 'yes' : 'no' }}</strong></p>
        <button type="button" data-testid="sheet-archive" (click)="archive()">Archive sheet</button>
        <app-settings />
        <app-holidays />
        <app-void-reasons />
        <app-templates />
        <app-people />
        <app-pools />
        <app-audit-view />
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
  protected readonly asOfForm = new FormGroup({ date: new FormControl('', { nonNullable: true }) });
  protected readonly signinError = signal('');
  protected readonly sheetError = signal('');
  /** The forms with a submit under way; another submit of one waits for its answer. */
  private readonly submitting = new Set<string>();

  constructor() {
    this.signinForm.valueChanges.subscribe(() => this.signinError.set(''));
    this.sheetForm.valueChanges.subscribe(() => this.sheetError.set(''));
    this.todayForm.valueChanges.subscribe(() => this.sheetError.set(''));
    this.asOfForm.valueChanges.subscribe(() => this.sheetError.set(''));
  }

  protected async signIn(): Promise<void> {
    if (this.submitting.has('signIn')) return;
    this.signinError.set('');
    const name = this.signinForm.controls.name.value.trim();
    if (!name) {
      this.signinError.set('Enter a name.');
      return;
    }
    this.submitting.add('signIn');
    try {
      await this.session.signIn(name);
    } catch {
      this.signinError.set('Could not sign in.');
    } finally {
      this.submitting.delete('signIn');
    }
  }

  protected async loadSheet(): Promise<void> {
    if (this.submitting.has('loadSheet')) return;
    this.sheetError.set('');
    const id = this.sheetForm.controls.id.value.trim();
    if (!id) {
      this.sheetError.set('Enter a sheet id.');
      return;
    }
    this.submitting.add('loadSheet');
    try {
      await this.session.loadSheet(id);
    } catch {
      this.sheetError.set('Could not load that sheet.');
    } finally {
      this.submitting.delete('loadSheet');
    }
  }

  protected async saveToday(): Promise<void> {
    if (this.submitting.has('saveToday')) return;
    this.sheetError.set('');
    const today = this.todayForm.controls.today.value.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(today)) {
      this.sheetError.set('Enter a date.');
      return;
    }
    this.submitting.add('saveToday');
    try {
      await this.session.setToday(today);
    } catch {
      this.sheetError.set('Could not change today.');
    } finally {
      this.submitting.delete('saveToday');
    }
  }

  protected async setAsOf(): Promise<void> {
    if (this.submitting.has('asOf')) return;
    this.sheetError.set('');
    const date = this.asOfForm.controls.date.value.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.sheetError.set('Enter a date.');
      return;
    }
    await this.changeAsOf(date);
  }

  protected async clearAsOf(): Promise<void> {
    if (this.submitting.has('asOf')) return;
    this.sheetError.set('');
    await this.changeAsOf(null);
  }

  private async changeAsOf(date: string | null): Promise<void> {
    this.submitting.add('asOf');
    try {
      await this.session.setAsOf(date);
    } catch {
      this.sheetError.set('Could not show the sheet as of that date.');
    } finally {
      this.submitting.delete('asOf');
    }
  }

  protected async archive(): Promise<void> {
    if (this.submitting.has('archive')) return;
    this.sheetError.set('');
    this.submitting.add('archive');
    try {
      await this.session.archive();
    } catch {
      this.sheetError.set('Could not archive the sheet.');
    } finally {
      this.submitting.delete('archive');
    }
  }
}
