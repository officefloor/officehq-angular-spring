import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface RoleRow {
  user: string;
  role: string;
}

interface Settings {
  /** The most due a clerk may settle, with two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** The least live due a claim has, with two decimals. */
  floor: string;
  /** The days a claim has to be paid by, from its date. */
  terms: string;
  /** The days a claim of grade A, B or C has to be paid by, from its date; null when the grade uses the sheet's terms. */
  termsA: string | null;
  termsB: string | null;
  termsC: string | null;
  /** The percent of its figure an overdue claim's due gains for every full 30 days overdue, with two decimals. */
  latePct: string;
  /** The date a claim's rounding turns on (half up before it, half to even on or after); null when blank (the sheet's today). */
  policyDate: string | null;
  /** From the sheet's today on this date, a claim opened is numbered within its period; null when blank (off). */
  periodNumberingFrom: string | null;
  /** The most a person's due-total comes to, with two decimals; null when there is no cap. */
  cap: string | null;
}

interface AccessView {
  roles: RoleRow[];
  settings: Settings;
}

const ROLES = ['clerk', 'steward'];
const GRADES = ['A', 'B', 'C'] as const;
type Grade = (typeof GRADES)[number];
const GRADE_TERMS = { A: 'termsA', B: 'termsB', C: 'termsC' } as const;
const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;
const DAYS = /^\d{1,5}$/;
const PERCENT = /^\d{1,10}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's roles (any name without one is a clerk) with the form that sets one, and the
// sheet's settings: the clerk limit, the most due a clerk may settle (blank: no limit), and the floor,
// the least live due a claim has (blank: 1.00), and the terms, the days a claim has to be paid by
// from its date (blank: 14), and each grade's own terms (blank: the sheet's terms), and the late percent, what an overdue claim's due gains of its figure for
// every full 30 days overdue (blank: 0.00), and the policy date, the date a claim's rounding turns on
// (blank: the sheet's today), and the period numbering from, the date from which a claim opened is
// numbered within its period (blank: off), and the cap, the most a person's due-total comes to (blank: no
// cap). Each input shows the value saved; a save sends only the
// settings whose input was changed (a Delete or Backspace in a blank input asks to save it blank).
@Component({
  selector: 'app-access',
  template: `
    <section aria-label="Roles">
      <h2>Roles</h2>
      <p>Anyone without a role is a clerk.</p>
      <ul aria-label="Roles set">
        @for (r of roles(); track r.user) {
          <li [attr.data-testid]="'role-row-' + r.user">
            <span data-testid="role-user">{{ r.user }}</span>:
            <span data-testid="role-value">{{ r.role }}</span>
          </li>
        }
      </ul>
      <form (submit)="saveRole($event)" aria-label="Set role">
        <label for="role-form-user">User name</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="roleUser()"
          (input)="roleUser.set(value($event)); roleError.set('')"
        />
        <label for="role-form-role">Role (clerk or steward)</label>
        <input
          id="role-form-role"
          data-testid="role-form-role"
          list="role-form-roles"
          [value]="role()"
          (input)="role.set(value($event)); roleError.set('')"
        />
        <datalist id="role-form-roles">
          @for (r of roleNames; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError()) {
          <p role="alert" data-testid="role-form-error">{{ roleError() }}</p>
        }
      </form>
    </section>
    <section aria-label="Settings">
      <h2>Settings</h2>
      <p>Clerk limit <strong data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</strong></p>
      <p>Floor <strong data-testid="setting-floor-current">{{ floor() }}</strong></p>
      <p>Terms (days) <strong data-testid="setting-terms-current">{{ terms() }}</strong></p>
      @for (g of grades; track g) {
        <p>Terms for grade {{ g }} (days) <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ gradeTerms()[g] ?? '' }}</strong></p>
      }
      <p>Late percent <strong data-testid="setting-late-pct-current">{{ latePct() }}</strong></p>
      <p>Policy date <strong data-testid="setting-policy-date-current">{{ policyDate() ?? '' }}</strong></p>
      <p>Period numbering from <strong data-testid="setting-period-numbering-from-current">{{ periodNumberingFrom() ?? '' }}</strong></p>
      <p>Cap <strong data-testid="setting-cap-current">{{ cap() ?? '' }}</strong></p>
      <form (submit)="saveSettings($event)" aria-label="Save settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input
          id="setting-clerk-limit"
          inputmode="decimal"
          data-testid="setting-clerk-limit"
          [value]="limitInput()"
          (input)="limitInput.set(value($event)); limitTouched = true; settingsError.set('')"
          (keydown)="limitTouched = limitTouched || clears($event)"
        />
        <label for="setting-floor">Floor (blank: 1.00)</label>
        <input
          id="setting-floor"
          inputmode="decimal"
          data-testid="setting-floor"
          [value]="floorInput()"
          (input)="floorInput.set(value($event)); floorTouched = true; settingsError.set('')"
          (keydown)="floorTouched = floorTouched || clears($event)"
        />
        <label for="setting-terms">Terms in days (blank: 14)</label>
        <input
          id="setting-terms"
          inputmode="numeric"
          data-testid="setting-terms"
          [value]="termsInput()"
          (input)="termsInput.set(value($event)); termsTouched = true; settingsError.set('')"
          (keydown)="termsTouched = termsTouched || clears($event)"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} in days (blank: the sheet's terms)</label>
          <input
            [id]="'setting-terms-' + g"
            inputmode="numeric"
            [attr.data-testid]="'setting-terms-' + g"
            [value]="gradeTermsInput()[g]"
            (input)="setGradeTermsInput(g, value($event)); gradeTermsTouched[g] = true; settingsError.set('')"
            (keydown)="gradeTermsTouched[g] = gradeTermsTouched[g] || clears($event)"
          />
        }
        <label for="setting-late-pct">Late percent per full 30 days overdue (blank: 0.00)</label>
        <input
          id="setting-late-pct"
          inputmode="decimal"
          data-testid="setting-late-pct"
          [value]="latePctInput()"
          (input)="latePctInput.set(value($event)); latePctTouched = true; settingsError.set('')"
          (keydown)="latePctTouched = latePctTouched || clears($event)"
        />
        <label for="setting-policy-date">Policy date: claims before it round half up, on or after it half to even (blank: the sheet's today)</label>
        <input
          id="setting-policy-date"
          type="date"
          data-testid="setting-policy-date"
          [value]="policyDateInput()"
          (input)="policyDateInput.set(value($event)); policyDateTouched = true; settingsError.set('')"
          (keydown)="policyDateTouched = policyDateTouched || clears($event)"
        />
        <label for="setting-period-numbering-from">Period numbering from: claims opened from this date are numbered within their period (blank: off)</label>
        <input
          id="setting-period-numbering-from"
          type="date"
          data-testid="setting-period-numbering-from"
          [value]="numberingFromInput()"
          (input)="numberingFromInput.set(value($event)); numberingFromTouched = true; settingsError.set('')"
          (keydown)="numberingFromTouched = numberingFromTouched || clears($event)"
        />
        <label for="setting-cap">Cap on a person's due-total (blank: no cap)</label>
        <input
          id="setting-cap"
          inputmode="decimal"
          data-testid="setting-cap"
          [value]="capInput()"
          (input)="capInput.set(value($event)); capTouched = true; settingsError.set('')"
          (keydown)="capTouched = capTouched || clears($event)"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError()) {
          <p role="alert" data-testid="settings-error">{{ settingsError() }}</p>
        }
      </form>
    </section>
  `,
})
export class Access {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly roleNames = ROLES;
  protected readonly grades = GRADES;
  protected readonly roles = signal<RoleRow[]>([]);
  protected readonly clerkLimit = signal<string | null>(null);
  protected readonly roleUser = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal('');
  protected readonly limitInput = signal('');
  protected readonly floor = signal('');
  protected readonly floorInput = signal('');
  protected readonly terms = signal('');
  protected readonly termsInput = signal('');
  protected readonly gradeTerms = signal<Record<Grade, string | null>>({ A: null, B: null, C: null });
  protected readonly gradeTermsInput = signal<Record<Grade, string>>({ A: '', B: '', C: '' });
  protected readonly latePct = signal('');
  protected readonly latePctInput = signal('');
  protected readonly policyDate = signal<string | null>(null);
  protected readonly policyDateInput = signal('');
  protected readonly periodNumberingFrom = signal<string | null>(null);
  protected readonly numberingFromInput = signal('');
  protected readonly cap = signal<string | null>(null);
  protected readonly capInput = signal('');
  /** Whether each settings input was changed since the settings were last shown. */
  protected limitTouched = false;
  protected floorTouched = false;
  protected termsTouched = false;
  protected gradeTermsTouched: Record<Grade, boolean> = { A: false, B: false, C: false };
  protected latePctTouched = false;
  protected policyDateTouched = false;
  protected numberingFromTouched = false;
  protected capTouched = false;
  protected readonly settingsError = signal('');
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.roles.set([]);
      this.roleUser.set('');
      this.role.set('');
      this.roleError.set('');
      this.showSettings({ clerkLimit: null, floor: '', terms: '', termsA: null, termsB: null, termsC: null, latePct: '', policyDate: null, periodNumberingFrom: null, cap: null });
      this.settingsError.set('');
      if (sheet) void this.reload(sheet);
    });
  }

  /** Whether a key asks to clear the input: Delete or Backspace, even on an input already blank. */
  protected clears(e: KeyboardEvent): boolean {
    return e.key === 'Delete' || e.key === 'Backspace';
  }

  protected setGradeTermsInput(grade: Grade, v: string): void {
    this.gradeTermsInput.update((t) => ({ ...t, [grade]: v }));
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async saveRole(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.roleError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = this.roleUser().trim();
    const role = this.role().trim();
    if (!user) {
      this.roleError.set('Enter a user name.');
      return;
    }
    if (!ROLES.includes(role)) {
      this.roleError.set('Enter a role: clerk or steward.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<RoleRow>(`/api/sheets/${encodeURIComponent(sheet)}/roles`, { user, role }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.roles.update((rs) => [...rs.filter((r) => r.user !== saved.user), saved].sort((a, b) => a.user.localeCompare(b.user)));
      this.roleUser.set('');
      this.role.set('');
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.roleError.set('Could not set the role.');
    } finally {
      this.busy = false;
    }
  }

  protected async saveSettings(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.settingsError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const clerkLimit = this.limitInput().trim();
    if (this.limitTouched && clerkLimit !== '' && !LIMIT.test(clerkLimit)) {
      this.settingsError.set('Enter a clerk limit of 0.00 or more, with up to two decimals, or leave it blank for no limit.');
      return;
    }
    const floor = this.floorInput().trim();
    if (this.floorTouched && floor !== '' && !LIMIT.test(floor)) {
      this.settingsError.set('Enter a floor of 0.00 or more, with up to two decimals, or leave it blank for 1.00.');
      return;
    }
    const terms = this.termsInput().trim();
    if (this.termsTouched && terms !== '' && !DAYS.test(terms)) {
      this.settingsError.set('Enter terms of 0 or more whole days, or leave them blank for 14.');
      return;
    }
    const gradeTerms = this.gradeTermsInput();
    for (const g of GRADES) {
      const days = gradeTerms[g].trim();
      if (this.gradeTermsTouched[g] && days !== '' && !DAYS.test(days)) {
        this.settingsError.set(`Enter terms for grade ${g} of 0 or more whole days, or leave them blank for the sheet's terms.`);
        return;
      }
    }
    const latePct = this.latePctInput().trim();
    if (this.latePctTouched && latePct !== '' && !PERCENT.test(latePct)) {
      this.settingsError.set('Enter a late percent of 0.00 or more, with up to two decimals, or leave it blank for 0.00.');
      return;
    }
    const policyDate = this.policyDateInput().trim();
    if (this.policyDateTouched && policyDate !== '' && !DATE.test(policyDate)) {
      this.settingsError.set('Enter a policy date, or leave it blank for the sheet\'s today.');
      return;
    }
    const numberingFrom = this.numberingFromInput().trim();
    if (this.numberingFromTouched && numberingFrom !== '' && !DATE.test(numberingFrom)) {
      this.settingsError.set('Enter a period numbering from date, or leave it blank for off.');
      return;
    }
    const cap = this.capInput().trim();
    if (this.capTouched && cap !== '' && !LIMIT.test(cap)) {
      this.settingsError.set('Enter a cap of 0.00 or more, with up to two decimals, or leave it blank for no cap.');
      return;
    }
    const body: Partial<Settings> = {};
    if (this.limitTouched) body.clerkLimit = clerkLimit;
    if (this.floorTouched) body.floor = floor;
    if (this.termsTouched) body.terms = terms;
    for (const g of GRADES) {
      if (this.gradeTermsTouched[g]) body[GRADE_TERMS[g]] = gradeTerms[g].trim();
    }
    if (this.latePctTouched) body.latePct = latePct;
    if (this.policyDateTouched) body.policyDate = policyDate;
    if (this.numberingFromTouched) body.periodNumberingFrom = numberingFrom;
    if (this.capTouched) body.cap = cap;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<Settings>(`/api/sheets/${encodeURIComponent(sheet)}/settings`, body, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.showSettings(saved);
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.settingsError.set('Could not save the settings.');
    } finally {
      this.busy = false;
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const view = await firstValueFrom(this.http.get<AccessView>(`/api/sheets/${encodeURIComponent(sheet)}/access`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) {
      this.roles.set(view.roles);
      this.showSettings(view.settings);
    }
  }

  /** Show the settings saved, each input holding its value, none of them changed yet. */
  private showSettings(settings: Settings): void {
    this.clerkLimit.set(settings.clerkLimit);
    this.floor.set(settings.floor);
    this.limitInput.set(settings.clerkLimit ?? '');
    this.floorInput.set(settings.floor);
    this.terms.set(settings.terms);
    this.termsInput.set(settings.terms);
    this.latePct.set(settings.latePct);
    this.latePctInput.set(settings.latePct);
    this.limitTouched = false;
    this.floorTouched = false;
    this.termsTouched = false;
    this.gradeTerms.set({ A: settings.termsA, B: settings.termsB, C: settings.termsC });
    this.gradeTermsInput.set({ A: settings.termsA ?? '', B: settings.termsB ?? '', C: settings.termsC ?? '' });
    this.gradeTermsTouched = { A: false, B: false, C: false };
    this.policyDate.set(settings.policyDate);
    this.policyDateInput.set(settings.policyDate ?? '');
    this.latePctTouched = false;
    this.policyDateTouched = false;
    this.periodNumberingFrom.set(settings.periodNumberingFrom);
    this.numberingFromInput.set(settings.periodNumberingFrom ?? '');
    this.numberingFromTouched = false;
    this.cap.set(settings.cap);
    this.capInput.set(settings.cap ?? '');
    this.capTouched = false;
  }
}
