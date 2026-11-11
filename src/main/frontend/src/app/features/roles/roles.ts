import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Delegations } from './delegations';

// The roles the sheet assigns to user names: clerk (the default for any name), steward, viewer or
// restricted (a clerk who sees on the people list only their crews' people and themselves),
// and the form that assigns one. A steward settles any claim, and approves or rejects a PENDING
// one; a clerk settling a claim above the clerk limit makes it PENDING; a viewer sees everything
// and changes nothing (the server refuses each change; a bulk or generate action does nothing).
interface Role {
  user: string;
  role: string;
}

const ROLES = ['clerk', 'steward', 'viewer', 'restricted'];

@Component({
  selector: 'app-roles',
  imports: [Delegations],
  template: `
    <section aria-labelledby="roles-heading">
      <h2 id="roles-heading">Roles</h2>
      <form aria-label="Role" (submit)="save($event)">
        <label for="role-form-user">User name</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="userInput()"
          (input)="userInput.set($any($event.target).value); error.set(null)"
        />
        <label for="role-form-role">Role (clerk, steward, viewer or restricted)</label>
        <input
          id="role-form-role"
          data-testid="role-form-role"
          list="role-form-roles"
          [value]="roleInput()"
          (input)="roleInput.set($any($event.target).value); error.set(null)"
        />
        <datalist id="role-form-roles">
          @for (r of roles; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (error(); as err) {
          <p role="alert" data-testid="role-form-error">{{ err }}</p>
        }
      </form>
      <p>Any name not listed is a clerk.</p>
      <table data-testid="roles-list" aria-label="Assigned roles">
        <thead>
          <tr>
            <th scope="col">User</th>
            <th scope="col">Role</th>
          </tr>
        </thead>
        <tbody>
          @for (r of list(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <th scope="row" data-testid="role-user">{{ r.user }}</th>
              <td data-testid="role-value">{{ r.role }}</td>
            </tr>
          }
        </tbody>
      </table>
      <app-delegations />
    </section>
  `,
})
export class Roles {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly roles = ROLES;
  protected readonly list = signal<Role[]>([]);
  protected readonly userInput = signal('');
  protected readonly roleInput = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's roles from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.list.set([]);
      this.userInput.set('');
      this.roleInput.set('');
      this.error.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.post<Role[]>('/api/roles/list', { sheet }));
      if (this.session.sheet() === sheet) this.list.set(list);
    } catch {
      this.error.set('Could not read the roles on this sheet.');
    }
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const name = this.userInput().trim();
    const role = this.roleInput().trim();
    if (!name) {
      this.error.set('Enter a user name.');
      return;
    }
    if (!ROLES.includes(role)) {
      this.error.set('Choose a role of clerk, steward, viewer or restricted.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Role>('/api/roles/set', { sheet, user: this.session.user(), name, role }),
      );
      if (this.session.sheet() !== sheet) return;
      this.list.update((list) =>
        [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => a.user.localeCompare(b.user)),
      );
      this.userInput.set('');
      this.roleInput.set('');
    } catch {
      this.error.set('Could not set this role.');
    } finally {
      this.submitting = false;
    }
  }
}
