import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, Observable, concat, last, switchMap } from 'rxjs';
import { GRADES, Grade } from '../../core/people';
import { ROLES, RoleName, Roles } from '../../core/roles';
import { SheetView, Sheets } from '../../core/sheet';

/** A clerk limit the form accepts: an amount of 0.00 or more with up to two decimals. */
const LIMIT_PATTERN = /^\d{1,12}(\.\d{1,2})?$/;
/** Terms the form accepts: a whole number of days, 0 or more. */
const TERMS_PATTERN = /^\d{1,5}$/;
/** A late percent the form accepts: 0.00 or more, up to 999.99, with up to two decimals. */
const LATE_PCT_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;
/** A policy date the form accepts: yyyy-mm-dd. */
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** A field of the settings form. */
type SettingField = 'clerkLimit' | 'floor' | 'terms' | 'termsA' | 'termsB' | 'termsC' | 'latePct' | 'policyDate' | 'periodNumberingFrom' | 'cap';

/** The form field of each grade's own terms. */
const GRADE_TERMS_FIELD: Record<Grade, 'termsA' | 'termsB' | 'termsC'> = { A: 'termsA', B: 'termsB', C: 'termsC' };

/** Whether a yyyy-mm-dd text is a real calendar day. */
function isDate(value: string): boolean {
  const [y, m, d] = value.split('-').map(Number);
  const day = new Date(Date.UTC(y, m - 1, d));
  return day.getUTCFullYear() === y && day.getUTCMonth() === m - 1 && day.getUTCDate() === d;
}

// The sheet's settings: its clerk limit, its floor, its terms, each grade's own terms, its late percent, its policy date, its period numbering from, its cap, and the role assigned to each user name.
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <p>Clerk limit: <span data-testid="setting-clerk-limit-current">{{ sheets.current()?.clerkLimit ?? '' }}</span></p>
      <p>Floor: <span data-testid="setting-floor-current">{{ sheets.current()?.floor ?? '' }}</span></p>
      <p>Terms (days): <span data-testid="setting-terms-current">{{ sheets.current()?.terms ?? '' }}</span></p>
      @for (g of grades; track g) {
        <p>Terms for grade {{ g }} (days): <span [attr.data-testid]="'setting-terms-' + g + '-current'">{{ sheets.current()?.gradeTerms?.[g] ?? '' }}</span></p>
      }
      <p>Late percent: <span data-testid="setting-late-pct-current">{{ sheets.current()?.latePct ?? '' }}</span></p>
      <p>Policy date: <span data-testid="setting-policy-date-current">{{ sheets.current()?.policyDate ?? '' }}</span></p>
      <p>Period numbering from: <span data-testid="setting-period-numbering-from-current">{{ sheets.current()?.periodNumberingFrom ?? '' }}</span></p>

      <p>Cap: <span data-testid="setting-cap-current">{{ sheets.current()?.cap ?? '' }}</span></p>

      <form [formGroup]="settingsForm" (ngSubmit)="saveSettings()" aria-label="Change the sheet's settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input id="setting-clerk-limit" data-testid="setting-clerk-limit" formControlName="clerkLimit" (focus)="lastFocused = 'clerkLimit'"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-floor">Floor (a claim's due is at least this)</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" (focus)="lastFocused = 'floor'"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-terms">Terms (days a claim is due by after its date)</label>
        <input id="setting-terms" data-testid="setting-terms" formControlName="terms" (focus)="lastFocused = 'terms'"
               inputmode="numeric" autocomplete="off" />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (blank: the sheet's terms)</label>
          <input [id]="'setting-terms-' + g" [attr.data-testid]="'setting-terms-' + g" [formControlName]="gradeField[g]"
                 (focus)="lastFocused = gradeField[g]" inputmode="numeric" autocomplete="off" />
        }
        <label for="setting-late-pct">Late percent (added to an overdue claim's due for every full 30 days)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" formControlName="latePct" (focus)="lastFocused = 'latePct'"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-policy-date">Policy date (a claim dated before it rounds half up, on or after it half to even; blank: today)</label>
        <input id="setting-policy-date" data-testid="setting-policy-date" formControlName="policyDate" (focus)="lastFocused = 'policyDate'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        <label for="setting-period-numbering-from">Period numbering from (from this day, a claim opened is numbered within its period; blank: off)</label>
        <input id="setting-period-numbering-from" data-testid="setting-period-numbering-from" formControlName="periodNumberingFrom" (focus)="lastFocused = 'periodNumberingFrom'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        <label for="setting-cap">Cap (a person's due-total is at most this; blank: none)</label>
        <input id="setting-cap" data-testid="setting-cap" formControlName="cap" (focus)="lastFocused = 'cap'"
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

  protected readonly grades = GRADES;
  protected readonly gradeField = GRADE_TERMS_FIELD;
  protected readonly roleChoices = ROLES;
  protected readonly roleNames = ROLES.join(' or ');
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleError = signal<string | null>(null);
  protected readonly savingSettings = signal(false);
  protected readonly savingRole = signal(false);

  /** The setting field last focused: with no setting changed, it is the one saved as it is. */
  protected lastFocused: SettingField = 'clerkLimit';

  protected readonly settingsForm = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
    terms: new FormControl('', { nonNullable: true }),
    termsA: new FormControl('', { nonNullable: true }),
    termsB: new FormControl('', { nonNullable: true }),
    termsC: new FormControl('', { nonNullable: true }),
    latePct: new FormControl('', { nonNullable: true }),
    policyDate: new FormControl('', { nonNullable: true }),
    periodNumberingFrom: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
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
    const { clerkLimit, floor, terms, latePct, policyDate, periodNumberingFrom, cap } = this.settingsForm.controls;
    const limit = clerkLimit.value.trim();
    const floorValue = floor.value.trim();
    const termsValue = terms.value.trim();
    const latePctValue = latePct.value.trim();
    const policyDateValue = policyDate.value.trim();
    const periodNumberingFromValue = periodNumberingFrom.value.trim();
    const capValue = cap.value.trim();
    // Only the settings changed in the form are saved; with none changed, the field last focused (else the clerk
    // limit) is saved as it is.
    const unchanged = !this.settingsForm.dirty;
    const isSaved = (field: SettingField) => this.settingsForm.controls[field].dirty || (unchanged && this.lastFocused === field);
    const saveFloor = isSaved('floor');
    const saveTerms = isSaved('terms');
    const saveLatePct = isSaved('latePct');
    const savePolicyDate = isSaved('policyDate');
    const savePeriodNumberingFrom = isSaved('periodNumberingFrom');
    const saveLimit = isSaved('clerkLimit');
    const saveCap = isSaved('cap');
    if (saveLimit && limit !== '' && !LIMIT_PATTERN.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (saveFloor && !LIMIT_PATTERN.test(floorValue)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (saveTerms && !TERMS_PATTERN.test(termsValue)) {
      this.settingsError.set('Type terms of 0 or more whole days.');
      return;
    }
    const gradeTermsSaved = GRADES.filter((g) => isSaved(GRADE_TERMS_FIELD[g]))
      .map((g) => ({ grade: g, value: this.settingsForm.controls[GRADE_TERMS_FIELD[g]].value.trim() }));
    if (gradeTermsSaved.some((t) => t.value !== '' && !TERMS_PATTERN.test(t.value))) {
      this.settingsError.set('Type a grade\'s terms as 0 or more whole days, or leave them blank for the sheet\'s terms.');
      return;
    }
    if (saveLatePct && !LATE_PCT_PATTERN.test(latePctValue)) {
      this.settingsError.set('Type a late percent of 0.00 or more, up to 999.99, with up to two decimals.');
      return;
    }
    if (savePolicyDate && policyDateValue !== '' && !(DATE_PATTERN.test(policyDateValue) && isDate(policyDateValue))) {
      this.settingsError.set('Type a policy date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    if (savePeriodNumberingFrom && periodNumberingFromValue !== ''
        && !(DATE_PATTERN.test(periodNumberingFromValue) && isDate(periodNumberingFromValue))) {
      this.settingsError.set('Type a period numbering from date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    if (saveCap && capValue !== '' && !LIMIT_PATTERN.test(capValue)) {
      this.settingsError.set('Type a cap of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    const saves: Observable<SheetView>[] = [];
    if (saveLimit) saves.push(this.sheets.setSettings(sheet.id, limit === '' ? null : limit));
    if (saveFloor) saves.push(this.sheets.setFloor(sheet.id, floorValue));
    if (saveTerms) saves.push(this.sheets.setTerms(sheet.id, Number(termsValue)));
    for (const t of gradeTermsSaved) saves.push(this.sheets.setGradeTerms(sheet.id, t.grade, t.value === '' ? null : Number(t.value)));
    if (saveLatePct) saves.push(this.sheets.setLatePct(sheet.id, latePctValue));
    if (savePolicyDate) saves.push(this.sheets.setPolicyDate(sheet.id, policyDateValue === '' ? null : policyDateValue));
    if (savePeriodNumberingFrom) saves.push(this.sheets.setPeriodNumberingFrom(sheet.id, periodNumberingFromValue === '' ? null : periodNumberingFromValue));
    if (saveCap) saves.push(this.sheets.setCap(sheet.id, capValue === '' ? null : capValue));
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
    this.settingsForm.reset({ clerkLimit: sheet?.clerkLimit ?? '', floor: sheet?.floor ?? '', terms: String(sheet?.terms ?? ''), termsA: String(sheet?.gradeTerms?.A ?? ''), termsB: String(sheet?.gradeTerms?.B ?? ''), termsC: String(sheet?.gradeTerms?.C ?? ''), latePct: sheet?.latePct ?? '', policyDate: sheet?.policyDate ?? '', periodNumberingFrom: sheet?.periodNumberingFrom ?? '', cap: sheet?.cap ?? '' }, { emitEvent: false });
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
