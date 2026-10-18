import { Component, effect, inject, signal, untracked } from '@angular/core';
import { finalize } from 'rxjs';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { ROLES, SettingsChange, SettingsService } from '../../core/settings.service';
import { SheetService } from '../../core/sheet.service';

// The sheet's settings (its clerk limit: blank for none; its floor, the least a claim's live due is; its terms,
// the days after its date a claim is due by; its late percent, what an overdue claim gains per full 30 days; its
// policy date, blank for the sheet's today: a claim dated before it rounds half up, else half to even) and the roles it gives user names (clerk,
// the default for any name, or steward), each with a form that changes it.
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form [formGroup]="settingsForm" (ngSubmit)="save()" aria-label="Settings">
        <label for="setting-clerk-limit">Clerk limit (empty: none)</label>
        <input id="setting-clerk-limit" data-testid="setting-clerk-limit" formControlName="clerkLimit"
          inputmode="decimal" />
        <label for="setting-floor">Floor</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" inputmode="decimal" />
        <label for="setting-terms">Terms (days)</label>
        <input id="setting-terms" data-testid="setting-terms" formControlName="terms" inputmode="numeric" />
        <label for="setting-late-pct">Late percent (per full 30 days overdue)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" formControlName="latePct" inputmode="decimal" />
        <label for="setting-policy-date">Policy date (yyyy-mm-dd; empty: the sheet's today)</label>
        <input id="setting-policy-date" data-testid="setting-policy-date" formControlName="policyDate" />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError()) {
          <p role="alert" data-testid="settings-error">{{ settingsError() }}</p>
        }
      </form>
      <p>Clerk limit now: <span data-testid="setting-clerk-limit-current">{{ settings.clerkLimit() ?? '' }}</span></p>
      <p>Floor now: <span data-testid="setting-floor-current">{{ settings.floor() }}</span></p>
      <p>Terms now: <span data-testid="setting-terms-current">{{ settings.terms() }}</span> days</p>
      <p>Late percent now: <span data-testid="setting-late-pct-current">{{ settings.latePct() }}</span></p>
      <p>Policy date now: <span data-testid="setting-policy-date-current">{{ settings.policyDate() ?? '' }}</span></p>
      <form [formGroup]="roleForm" (ngSubmit)="setRole()" aria-label="Set role">
        <label for="role-form-user">User</label>
        <input id="role-form-user" data-testid="role-form-user" formControlName="user" />
        <label for="role-form-role">Role ({{ roles.join(' or ') }})</label>
        <input id="role-form-role" data-testid="role-form-role" formControlName="role" />
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError()) {
          <p role="alert" data-testid="role-form-error">{{ roleError() }}</p>
        }
      </form>
      <table>
        <caption>Roles (anyone else is a clerk)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of settings.roles(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <th scope="row">{{ r.user }}</th>
              <td data-testid="role-value">{{ r.role }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Settings {
  protected readonly settings = inject(SettingsService);
  private readonly sheets = inject(SheetService);
  protected readonly roles = ROLES;
  protected readonly settingsForm = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
    terms: new FormControl('', { nonNullable: true }),
    latePct: new FormControl('', { nonNullable: true }),
    policyDate: new FormControl('', { nonNullable: true }),
  });
  protected readonly roleForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    role: new FormControl('', { nonNullable: true }),
  });
  protected readonly settingsError = signal('');
  protected readonly roleError = signal('');
  /** A submit is waiting for its answer; another submit of the same form meanwhile is ignored. */
  private savingSettings = false;
  private savingRole = false;

  constructor() {
    this.settingsForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.roleForm.valueChanges.subscribe(() => this.roleError.set(''));
    effect(() => {
      this.sheets.loads();
      untracked(() => {
        this.settingsError.set('');
        this.roleError.set('');
        this.resetSettings();
        this.roleForm.reset({ user: '', role: '' }, { emitEvent: false });
      });
    });
  }

  protected save(): void {
    if (this.savingSettings) return;
    this.settingsError.set('');
    const { clerkLimit, floor, terms, latePct, policyDate } = this.settingsForm.controls;
    const limit = clerkLimit.value.trim();
    const least = floor.value.trim();
    const days = terms.value.trim();
    const late = latePct.value.trim();
    const policy = policyDate.value.trim();
    if (limit !== '' && !/^\d{1,13}(\.\d{1,2})?$/.test(limit)) {
      this.settingsError.set('Enter a clerk limit of 0.00 or more with up to two decimals, or leave it empty.');
      return;
    }
    if (!/^\d{1,13}(\.\d{1,2})?$/.test(least)) {
      this.settingsError.set('Enter a floor of 0.00 or more with up to two decimals.');
      return;
    }
    if (!/^\d{1,4}$/.test(days)) {
      this.settingsError.set('Enter terms of 0 or more whole days.');
      return;
    }
    if (!/^\d{1,3}(\.\d{1,2})?$/.test(late)) {
      this.settingsError.set('Enter a late percent of 0.00 or more with up to two decimals.');
      return;
    }
    if (policy !== '' && !validDate(policy)) {
      this.settingsError.set('Enter a policy date as yyyy-mm-dd, or leave it empty.');
      return;
    }
    // Only the settings the user changed or filled in are saved (and audited).
    const change: SettingsChange = {};
    if (clerkLimit.dirty || clerkLimit.touched) change.clerkLimit = limit;
    if (floor.dirty || floor.touched) change.floor = least;
    if (terms.dirty || terms.touched) change.terms = days;
    if (latePct.dirty || latePct.touched) change.latePct = late;
    if (policyDate.dirty || policyDate.touched) change.policyDate = policy;
    this.savingSettings = true;
    this.settings.save(change).pipe(finalize(() => (this.savingSettings = false))).subscribe({
      next: () => this.resetSettings(),
      error: () => this.settingsError.set('Could not save the settings.'),
    });
  }

  private resetSettings(): void {
    this.settingsForm.reset(
      { clerkLimit: this.settings.clerkLimit() ?? '', floor: this.settings.floor(),
        terms: String(this.settings.terms()), latePct: this.settings.latePct(),
        policyDate: this.settings.policyDate() ?? '' },
      { emitEvent: false },
    );
  }

  protected setRole(): void {
    if (this.savingRole) return;
    this.roleError.set('');
    const { user, role } = this.roleForm.getRawValue();
    const u = user.trim();
    const r = role.trim();
    if (!u) {
      this.roleError.set('Enter a user name.');
      return;
    }
    if (!(ROLES as readonly string[]).includes(r)) {
      this.roleError.set(`Enter a role: ${ROLES.join(' or ')}.`);
      return;
    }
    this.savingRole = true;
    this.settings.setRole(u, r).pipe(finalize(() => (this.savingRole = false))).subscribe({
      next: () => this.roleForm.reset({ user: '', role: '' }, { emitEvent: false }),
      error: () => this.roleError.set('Could not set that role.'),
    });
  }
}

/** A real calendar date written yyyy-mm-dd. */
function validDate(text: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return false;
  const [y, m, d] = text.split('-').map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}
