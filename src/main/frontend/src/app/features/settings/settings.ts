import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, Observable, concat, last, switchMap } from 'rxjs';
import { ROLES, RoleName, Roles } from '../../core/roles';
import { SheetView, Sheets } from '../../core/sheet';

/** A clerk limit the form accepts: an amount of 0.00 or more with up to two decimals. */
const LIMIT_PATTERN = /^\d{1,12}(\.\d{1,2})?$/;

// The sheet's settings: its clerk limit, its floor, and the role assigned to each user name.
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <p>Clerk limit: <span data-testid="setting-clerk-limit-current">{{ sheets.current()?.clerkLimit ?? '' }}</span></p>
      <p>Floor: <span data-testid="setting-floor-current">{{ sheets.current()?.floor ?? '' }}</span></p>

      <form [formGroup]="settingsForm" (ngSubmit)="saveSettings()" aria-label="Change the sheet's settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input id="setting-clerk-limit" data-testid="setting-clerk-limit" formControlName="clerkLimit"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-floor">Floor (a claim's due is at least this)</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor"
               inputmode="decimal" autocomplete="off" />
        <button type="submit" data-testid="settings-save" [disabled]="savingSettings()">Save settings</button>
        @if (settingsError(); as err) {
          <p role="alert" data-testid="settings-error">{{ err }}</p>
        }
      </form>

      <h3>Roles</h3>
      <p>A name with no role assigned is a clerk.</p>
      <ul aria-label="Roles assigned">
        @for (r of roles.all(); track r.user) {
          <li [attr.data-testid]="'role-row-' + r.user">
            {{ r.user }}: <span data-testid="role-value">{{ r.role }}</span>
          </li>
        }
      </ul>

      <form [formGroup]="roleForm" (ngSubmit)="saveRole()" aria-label="Assign a role">
        <label for="role-form-user">User name</label>
        <input id="role-form-user" data-testid="role-form-user" formControlName="user" autocomplete="off" />
        <label for="role-form-role">Role ({{ roleNames }})</label>
        <input id="role-form-role" data-testid="role-form-role" formControlName="role" autocomplete="off" list="role-names" />
        <datalist id="role-names">
          @for (r of roleChoices; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit" [disabled]="savingRole()">Set role</button>
        @if (roleError(); as err) {
          <p role="alert" data-testid="role-form-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class SettingsPanel {
  protected readonly sheets = inject(Sheets);
  protected readonly roles = inject(Roles);

  protected readonly roleChoices = ROLES;
  protected readonly roleNames = ROLES.join(' or ');
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleError = signal<string | null>(null);
  protected readonly savingSettings = signal(false);
  protected readonly savingRole = signal(false);

  protected readonly settingsForm = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
  });
  protected readonly roleForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    role: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its roles and settings from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.settingsError.set(null);
          this.roleError.set(null);
          this.resetSettings(sheet);
          return this.roles.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.roleError.set('Could not read the roles.') });
    // An error is removed as soon as its form is changed.
    this.settingsForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.settingsError.set(null));
    this.roleForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.roleError.set(null));
  }

  protected saveSettings(): void {
    // A second submit before the first has answered is ignored, so it stores one change.
    if (this.savingSettings()) return;
    this.settingsError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const { clerkLimit, floor } = this.settingsForm.controls;
    const limit = clerkLimit.value.trim();
    const floorValue = floor.value.trim();
    // Only the settings changed in the form are saved; with none changed, the clerk limit is saved as it is.
    const saveFloor = floor.dirty;
    const saveLimit = clerkLimit.dirty || !saveFloor;
    if (saveLimit && limit !== '' && !LIMIT_PATTERN.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (saveFloor && !LIMIT_PATTERN.test(floorValue)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    const saves: Observable<SheetView>[] = [];
    if (saveLimit) saves.push(this.sheets.setSettings(sheet.id, limit === '' ? null : limit));
    if (saveFloor) saves.push(this.sheets.setFloor(sheet.id, floorValue));
    this.savingSettings.set(true);
    concat(...saves).pipe(last()).subscribe({
      next: (saved) => {
        this.savingSettings.set(false);
        this.resetSettings(saved);
      },
      error: () => {
        this.savingSettings.set(false);
        this.settingsError.set('Could not save the settings.');
      },
    });
  }

  /** Show the sheet's settings in the form, as not yet changed. */
  private resetSettings(sheet: SheetView | null): void {
    this.settingsForm.reset({ clerkLimit: sheet?.clerkLimit ?? '', floor: sheet?.floor ?? '' }, { emitEvent: false });
  }

  protected saveRole(): void {
    // A second submit before the first has answered is ignored, so it stores one record.
    if (this.savingRole()) return;
    this.roleError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const user = this.roleForm.controls.user.value.trim();
    const role = this.roleForm.controls.role.value.trim();
    if (!user) {
      this.roleError.set('Type the user name to assign a role to.');
      return;
    }
    if (!(ROLES as readonly string[]).includes(role)) {
      this.roleError.set(`Choose a role: ${this.roleNames}.`);
      return;
    }
    this.savingRole.set(true);
    this.roles.set(sheet.id, user, role as RoleName).subscribe({
      next: () => {
        this.savingRole.set(false);
        this.roleForm.reset({ user: '', role: '' }, { emitEvent: false });
      },
      error: () => {
        this.savingRole.set(false);
        this.roleError.set('Could not assign that role.');
      },
    });
  }
}
