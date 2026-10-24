import { Component, effect, inject, output, signal } from '@angular/core';
import { GRADES, Grade } from './people.service';
import { cents } from './claims.service';
import { Session } from '../../core/session';
import { ROLES, Role, RoleGiven, SettingsService, SheetSettings } from './settings.service';

const LIMIT = /^\d{1,10}(\.\d{1,2})?$/;
const TERMS = /^\d{1,4}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's settings: its clerk limit (the most a clerk may settle a claim for; blank for no
// limit), its floor (the least a claim's live due can be), its cap (the most a person's due-total
// can be; blank for no cap), its terms (how many days after its date
// a claim is due by), each grade's own terms (used in place of the sheet's for a person of that
// grade; blank for the sheet's), its late percent (the percent of its figure an overdue claim's due gains for
// every full 30 days it is overdue), its policy date (a claim dated before it rounds half up, one on
// or after it half to even; blank for the sheet's today), its period numbering from (the date from which
// a claim opened is numbered within its period; blank for off) and the role each user name has been given (any other name is a clerk). Read from the
// store every time a sheet is loaded. Saving the settings saves only the fields changed since
// they were last saved.
@Component({
  selector: 'app-settings',
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form (submit)="saveSettings($event)" aria-label="Settings" data-testid="settings-form">
        <label for="setting-clerk-limit">Clerk limit</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="limit()"
          (input)="limit.set(value($event)); limitChanged = true; settingsError.set(null)"
          (keydown)="limitChanged = true"
        />
        <span>
          In force: <span data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</span>
          @if (clerkLimit() === null) {
            (no limit)
          }
        </span>
        <label for="setting-floor">Floor</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floorInput()"
          (input)="floorInput.set(value($event)); floorChanged = true; settingsError.set(null)"
          (keydown)="floorChanged = true"
        />
        <span>In force: <span data-testid="setting-floor-current">{{ floor() }}</span></span>
        <label for="setting-cap">Cap</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="capInput()"
          (input)="capInput.set(value($event)); capChanged = true; settingsError.set(null)"
          (keydown)="capChanged = true"
        />
        <span>
          In force: <span data-testid="setting-cap-current">{{ cap() ?? '' }}</span>
          @if (cap() === null) {
            (no cap)
          }
        </span>
        <label for="setting-terms">Terms (days)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="termsInput()"
          (input)="termsInput.set(value($event)); termsChanged = true; settingsError.set(null)"
          (keydown)="termsChanged = true"
        />
        <span>In force: <span data-testid="setting-terms-current">{{ terms() }}</span></span>
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (days)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [value]="gradeTermsInput()[g]"
            (input)="setGradeTermsInput(g, value($event)); gradeTermsChanged.add(g); settingsError.set(null)"
            (keydown)="gradeTermsChanged.add(g)"
          />
          <span>
            In force: <span [attr.data-testid]="'setting-terms-' + g + '-current'">{{ gradeTermsShown(g) }}</span>
            @if (gradeTerms()[g] === undefined) {
              (the sheet's terms)
            }
          </span>
        }
        <label for="setting-late-pct">Late percent</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePctInput()"
          (input)="latePctInput.set(value($event)); latePctChanged = true; settingsError.set(null)"
          (keydown)="latePctChanged = true"
        />
        <span>In force: <span data-testid="setting-late-pct-current">{{ latePct() }}</span></span>
        <label for="setting-policy-date">Policy date</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          type="date"
          [value]="policyDateInput()"
          (input)="policyDateInput.set(value($event)); policyDateChanged = true; settingsError.set(null)"
          (keydown)="policyDateChanged = true"
        />
        <span>
          In force: <span data-testid="setting-policy-date-current">{{ policyDate() ?? '' }}</span>
          @if (policyDate() === null) {
            (the sheet's today)
          }
        </span>
        <label for="setting-period-numbering-from">Period numbering from</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          type="date"
          [value]="periodNumberingFromInput()"
          (input)="periodNumberingFromInput.set(value($event)); periodNumberingFromChanged = true; settingsError.set(null)"
          (keydown)="periodNumberingFromChanged = true"
        />
        <span>
          In force: <span data-testid="setting-period-numbering-from-current">{{ periodNumberingFrom() ?? '' }}</span>
          @if (periodNumberingFrom() === null) {
            (off)
          }
        </span>
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as message) {
          <p role="alert" class="error" data-testid="settings-error">{{ message }}</p>
        }
      </form>

      <form (submit)="saveRole($event)" aria-label="Role" data-testid="role-form">
        <label for="role-form-user">User</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="roleUser()"
          (input)="roleUser.set(value($event)); roleError.set(null)"
        />
        <label for="role-form-role">Role</label>
        <input
          id="role-form-role"
          data-testid="role-form-role"
          list="role-form-roles"
          [value]="role()"
          (input)="role.set(value($event)); roleError.set(null)"
        />
        <datalist id="role-form-roles">
          @for (r of roles; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError(); as message) {
          <p role="alert" class="error" data-testid="role-form-error">{{ message }}</p>
        }
      </form>
      @if (given().length > 0) {
        <table data-testid="role-list">
          <caption>Roles (anyone else is a clerk)</caption>
          <thead>
            <tr>
              <th scope="col">User</th>
              <th scope="col">Role</th>
            </tr>
          </thead>
          <tbody>
            @for (r of given(); track r.user) {
              <tr [attr.data-testid]="'role-row-' + r.user">
                <td data-testid="role-user">{{ r.user }}</td>
                <td data-testid="role-value">{{ r.role }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
    table {
      border-collapse: collapse;
      margin-bottom: 1rem;
    }
    caption {
      text-align: left;
      font-weight: bold;
    }
    th,
    td {
      text-align: left;
      padding: 0.25rem 0.75rem 0.25rem 0;
    }
  `,
})
export class Settings {
  private readonly session = inject(Session);
  private readonly service = inject(SettingsService);

  protected readonly roles = ROLES;
  /** The clerk limit in force; null when there is none. */
  protected readonly clerkLimit = signal<string | null>(null);
  /** The floor in force, with two decimals; empty until read. */
  protected readonly floor = signal('');
  /** The cap in force, with two decimals; null when there is none. */
  protected readonly cap = signal<string | null>(null);
  /** The cap saved, in cents; null when there is none. */
  readonly capSaved = output<number | null>();
  protected readonly given = signal<RoleGiven[]>([]);
  /** The terms in force, in days, as text; empty until read. */
  protected readonly terms = signal('');
  protected readonly grades = GRADES;
  /** The grades' own terms in force, in days, by grade; a grade not in it uses the sheet's terms. */
  protected readonly gradeTerms = signal<Partial<Record<Grade, number>>>({});
  /** The late percent in force, with two decimals; empty until read. */
  protected readonly latePct = signal('');
  /** The floor saved, in cents. */
  readonly floorSaved = output<number>();
  /** The late percent saved, with two decimals. */
  readonly latePctSaved = output<string>();
  /** The policy date as set; null when it is the sheet's today. */
  protected readonly policyDate = signal<string | null>(null);
  /** The policy date saved; null when it is the sheet's today. */
  readonly policyDateSaved = output<string | null>();

  /** The period numbering from date as set; null when it is off. */
  protected readonly periodNumberingFrom = signal<string | null>(null);

  protected readonly limit = signal('');
  protected readonly floorInput = signal('');
  protected readonly capInput = signal('');
  protected readonly termsInput = signal('');
  protected readonly latePctInput = signal('');
  protected readonly gradeTermsInput = signal<Record<Grade, string>>(blankGrades());
  protected readonly policyDateInput = signal('');
  protected readonly periodNumberingFromInput = signal('');
  /** Whether each field was typed in (a key pressed in it, even one that left its value as it was)
   *  since the settings were last loaded or saved; a field is saved when it was, or when it differs
   *  from the value in force. */
  protected limitChanged = false;
  protected floorChanged = false;
  protected capChanged = false;
  protected termsChanged = false;
  protected latePctChanged = false;
  protected readonly gradeTermsChanged = new Set<Grade>();
  protected policyDateChanged = false;
  protected periodNumberingFromChanged = false;
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleUser = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal<string | null>(null);
  /** A submit is waiting for its answer; another submit of the same form meanwhile is ignored. */
  private savingSettings = false;
  /** A settings submit came while one was waiting; it is made once that one is answered. */
  private settingsResubmit = false;
  private savingRole = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      this.session.loads();
      this.clerkLimit.set(null);
      this.floor.set('');
      this.given.set([]);
      this.limit.set('');
      this.floorInput.set('');
      this.cap.set(null);
      this.capInput.set('');
      this.capChanged = false;
      this.terms.set('');
      this.termsInput.set('');
      this.limitChanged = false;
      this.floorChanged = false;
      this.termsChanged = false;
      this.gradeTerms.set({});
      this.gradeTermsInput.set(blankGrades());
      this.gradeTermsChanged.clear();
      this.latePct.set('');
      this.latePctInput.set('');
      this.latePctChanged = false;
      this.policyDate.set(null);
      this.policyDateInput.set('');
      this.policyDateChanged = false;
      this.periodNumberingFrom.set(null);
      this.periodNumberingFromInput.set('');
      this.periodNumberingFromChanged = false;
      this.settingsError.set(null);
      this.roleUser.set('');
      this.role.set('');
      this.roleError.set(null);
      if (sheet) void this.read(sheet);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected setGradeTermsInput(grade: Grade, text: string): void {
    this.gradeTermsInput.update((inputs) => ({ ...inputs, [grade]: text }));
  }

  /** The grade's own terms in force, as text; empty when it uses the sheet's terms. */
  protected gradeTermsShown(grade: Grade): string {
    const own = this.gradeTerms()[grade];
    return own === undefined ? '' : String(own);
  }

  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingSettings) {
      this.settingsResubmit = true;
      return;
    }
    this.settingsError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const limit = this.limit().trim();
    const floor = this.floorInput().trim();
    const saveLimit = this.limitChanged || limit !== (this.clerkLimit() ?? '');
    const saveFloor = this.floorChanged || floor !== this.floor();
    const cap = this.capInput().trim();
    const saveCap = this.capChanged || cap !== (this.cap() ?? '');
    const terms = this.termsInput().trim();
    const saveTerms = this.termsChanged || terms !== this.terms();
    const gradeTerms = GRADES.map((grade) => ({ grade, text: this.gradeTermsInput()[grade].trim() })).filter(
      ({ grade, text }) => this.gradeTermsChanged.has(grade) || text !== this.gradeTermsShown(grade),
    );
    const latePct = this.latePctInput().trim();
    const saveLatePct = this.latePctChanged || latePct !== this.latePct();
    const policyDate = this.policyDateInput().trim();
    const savePolicyDate = this.policyDateChanged || policyDate !== (this.policyDate() ?? '');
    const numberingFrom = this.periodNumberingFromInput().trim();
    const saveNumberingFrom =
      this.periodNumberingFromChanged || numberingFrom !== (this.periodNumberingFrom() ?? '');
    if (saveLimit && limit !== '' && !LIMIT.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (saveFloor && !LIMIT.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (saveCap && cap !== '' && !LIMIT.test(cap)) {
      this.settingsError.set('Type a cap of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (saveTerms && !TERMS.test(terms)) {
      this.settingsError.set('Type terms as a whole number of days, 0 or more.');
      return;
    }
    const badGrade = gradeTerms.find(({ text }) => text !== '' && !TERMS.test(text));
    if (badGrade) {
      this.settingsError.set(
        `Type terms for grade ${badGrade.grade} as a whole number of days, 0 or more, or leave them blank for the sheet's terms.`,
      );
      return;
    }
    if (saveLatePct && !LIMIT.test(latePct)) {
      this.settingsError.set('Type a late percent of 0.00 or more, with up to two decimals.');
      return;
    }
    if (savePolicyDate && policyDate !== '' && !DATE.test(policyDate)) {
      this.settingsError.set('Type a policy date as YYYY-MM-DD, or leave it blank for the sheet\'s today.');
      return;
    }
    if (saveNumberingFrom && numberingFrom !== '' && !DATE.test(numberingFrom)) {
      this.settingsError.set('Type a period numbering from date as YYYY-MM-DD, or leave it blank to turn it off.');
      return;
    }
    this.savingSettings = true;
    try {
      if (saveLimit) {
        const settings = await this.service.setClerkLimit(sheet, limit || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.limit().trim() === limit) this.limitChanged = false;
        this.show(settings);
      }
      if (saveFloor) {
        const settings = await this.service.setFloor(sheet, floor);
        if (this.session.sheetId() !== sheet) return;
        if (this.floorInput().trim() === floor) this.floorChanged = false;
        this.show(settings);
        this.floorSaved.emit(cents(settings.floor));
      }
      if (saveCap) {
        const settings = await this.service.setCap(sheet, cap || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.capInput().trim() === cap) this.capChanged = false;
        this.show(settings);
        this.capSaved.emit(settings.cap === null ? null : cents(settings.cap));
      }
      if (saveTerms) {
        const settings = await this.service.setTerms(sheet, terms);
        if (this.session.sheetId() !== sheet) return;
        if (this.termsInput().trim() === terms) this.termsChanged = false;
        this.show(settings);
      }
      for (const { grade, text } of gradeTerms) {
        const settings = await this.service.setGradeTerms(sheet, grade, text || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.gradeTermsInput()[grade].trim() === text) this.gradeTermsChanged.delete(grade);
        this.show(settings);
      }
      if (saveLatePct) {
        const settings = await this.service.setLatePct(sheet, latePct);
        if (this.session.sheetId() !== sheet) return;
        if (this.latePctInput().trim() === latePct) this.latePctChanged = false;
        this.show(settings);
        this.latePctSaved.emit(settings.latePct);
      }
      if (savePolicyDate) {
        const settings = await this.service.setPolicyDate(sheet, policyDate || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.policyDateInput().trim() === policyDate) this.policyDateChanged = false;
        this.show(settings);
        this.policyDateSaved.emit(settings.policyDate);
      }
      if (saveNumberingFrom) {
        const settings = await this.service.setPeriodNumberingFrom(sheet, numberingFrom || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.periodNumberingFromInput().trim() === numberingFrom) this.periodNumberingFromChanged = false;
        this.show(settings);
      }
      // Show what is in force in each field, but keep what was typed in one while the save was waiting.
      if (!this.limitChanged) this.limit.set(this.clerkLimit() ?? '');
      if (!this.floorChanged) this.floorInput.set(this.floor());
      if (!this.capChanged) this.capInput.set(this.cap() ?? '');
      if (!this.termsChanged) this.termsInput.set(this.terms());
      if (!this.latePctChanged) this.latePctInput.set(this.latePct());
      this.showGradeTermsInputs();
      if (!this.policyDateChanged) this.policyDateInput.set(this.policyDate() ?? '');
      if (!this.periodNumberingFromChanged) this.periodNumberingFromInput.set(this.periodNumberingFrom() ?? '');
    } catch {
      this.settingsError.set('Those settings could not be saved.');
    } finally {
      this.savingSettings = false;
      if (this.settingsResubmit) {
        this.settingsResubmit = false;
        void this.saveSettings(event);
      }
    }
  }

  protected async saveRole(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingRole) return;
    this.roleError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const user = this.roleUser().trim();
    const role = this.role().trim();
    if (user === '') {
      this.roleError.set('Type a user name.');
      return;
    }
    if (!ROLES.includes(role as Role)) {
      this.roleError.set(`Choose a role: ${ROLES.join(' or ')}.`);
      return;
    }
    this.savingRole = true;
    try {
      const saved = await this.service.setRole(sheet, user, role as Role);
      if (this.session.sheetId() !== sheet) return;
      this.given.update((list) =>
        [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => a.user.localeCompare(b.user)),
      );
      this.roleUser.set('');
      this.role.set('');
    } catch {
      this.roleError.set('That role could not be set.');
    } finally {
      this.savingRole = false;
    }
  }

  private async read(sheet: string): Promise<void> {
    try {
      const settings = await this.service.get(sheet);
      if (this.session.sheetId() === sheet) {
        this.show(settings);
        if (!this.limitChanged) this.limit.set(settings.clerkLimit ?? '');
        if (!this.floorChanged) this.floorInput.set(settings.floor);
        if (!this.capChanged) this.capInput.set(settings.cap ?? '');
        if (!this.termsChanged) this.termsInput.set(String(settings.terms));
        if (!this.latePctChanged) this.latePctInput.set(settings.latePct);
        this.showGradeTermsInputs();
        if (!this.policyDateChanged) this.policyDateInput.set(settings.policyDate ?? '');
        if (!this.periodNumberingFromChanged) this.periodNumberingFromInput.set(settings.periodNumberingFrom ?? '');
      }
    } catch {
      // The settings could not be read; they show as none.
    }
  }

  /** Show each grade's own terms in force in its field, but keep what was typed in one since. */
  private showGradeTermsInputs(): void {
    this.gradeTermsInput.update((inputs) => {
      const next = { ...inputs };
      for (const grade of GRADES) if (!this.gradeTermsChanged.has(grade)) next[grade] = this.gradeTermsShown(grade);
      return next;
    });
  }

  private show(settings: SheetSettings): void {
    this.clerkLimit.set(settings.clerkLimit);
    this.floor.set(settings.floor);
    this.cap.set(settings.cap);
    this.terms.set(String(settings.terms));
    this.gradeTerms.set(settings.gradeTerms ?? {});
    this.latePct.set(settings.latePct);
    this.policyDate.set(settings.policyDate);
    this.periodNumberingFrom.set(settings.periodNumberingFrom);
    this.given.set(settings.roles);
  }
}

function blankGrades(): Record<Grade, string> {
  return { A: '', B: '', C: '' };
}
