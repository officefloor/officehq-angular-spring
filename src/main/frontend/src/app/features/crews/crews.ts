import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { sumSkippingNa } from '../rates/rates';

export interface CrewMemberView {
  personId: number;
  name: string;
}

export interface CrewView {
  id: number;
  name: string;
  leadId: number;
  leadName: string;
  members: CrewMemberView[];
}

// The people of the active sheet as the people list holds them now: who may be chosen as a lead or member, and each
// person's due-total as the list shows it (null: n/a), so a crew's due-total follows theirs.
@Injectable({ providedIn: 'root' })
export class CrewRoster {
  readonly people = signal<readonly { id: number; name: string }[]>([]);
  readonly dueBy = signal<ReadonlyMap<number, string | null>>(new Map());
}

// The active sheet's crews: each with its lead and members, and its due-total: the lead's and the members' due-totals
// added up, n/a skipped. A blank crew name, one already a crew's, or no lead, is refused; so is adding a crew's lead,
// or a person already in it, as a member.
@Component({
  selector: 'app-crews',
  imports: [FigPipe],
  template: `
    <section aria-label="Crews">
      <h2>Crews</h2>
      <form (submit)="add($event)" aria-label="Add a crew">
        <label for="crew-form-name">Crew name</label>
        <input
          id="crew-form-name"
          data-testid="crew-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="crew-form-lead">Lead</label>
        <select id="crew-form-lead" data-testid="crew-form-lead" (change)="lead.set(value($event)); error.set(null)">
          <option value="" [selected]="!lead()">Choose a lead</option>
          @for (person of roster.people(); track person.id) {
            <option [value]="person.id" [selected]="lead() === '' + person.id">{{ person.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit">Add crew</button>
        @if (error(); as err) {
          <p role="alert" data-testid="crew-form-error">{{ err | fig }}</p>
        }
      </form>

      <table aria-label="Crews on the sheet">
        <thead>
          <tr><th scope="col">Crew</th><th scope="col">Lead</th><th scope="col">Members</th><th scope="col">Due-total</th><th scope="col">Add member</th></tr>
        </thead>
        <tbody>
          @for (c of shown(); track c.id) {
            <tr [attr.data-testid]="'crew-row-' + c.name">
              <th scope="row">{{ c.name }}</th>
              <td data-testid="crew-lead">{{ c.leadName | fig }}</td>
              <td>
                <ul [attr.aria-label]="'Members of ' + c.name">
                  @for (m of c.members; track m.personId) {
                    <li data-testid="crew-member-line">{{ m.name | fig }}</li>
                  }
                </ul>
              </td>
              <td data-testid="crew-due-total">{{ c.dueTotal | fig }}</td>
              <td>
                <form (submit)="addMember($event, c)" [attr.aria-label]="'Add a member to ' + c.name">
                  <label [for]="'crew-member-select-' + c.id">Member<span class="visually-hidden"> for {{ c.name }}</span></label>
                  <select
                    [id]="'crew-member-select-' + c.id"
                    data-testid="crew-member-select"
                    (change)="setChoice(c.id, value($event))"
                  >
                    <option value="" [selected]="!choices()[c.id]">Choose a person</option>
                    @for (person of roster.people(); track person.id) {
                      <option [value]="person.id" [selected]="choices()[c.id] === '' + person.id">{{ person.name }}</option>
                    }
                  </select>
                  <button type="submit" data-testid="crew-member-add">Add member<span class="visually-hidden"> to {{ c.name }}</span></button>
                  @if (rowErrors()[c.id]; as err) {
                    <p role="alert" data-testid="crew-error">{{ err | fig }}</p>
                  }
                </form>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Crews {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  protected readonly roster = inject(CrewRoster);

  protected readonly crews = signal<readonly CrewView[]>([]);
  // Each crew with its lead's and members' names as the people list has them now, and its due-total.
  protected readonly shown = computed(() => {
    const names = new Map(this.roster.people().map((p) => [p.id, p.name]));
    const due = this.roster.dueBy();
    return this.crews().map((c) => ({
      ...c,
      leadName: names.get(c.leadId) ?? c.leadName,
      members: c.members.map((m) => ({ ...m, name: names.get(m.personId) ?? m.name })),
      dueTotal: sumSkippingNa([c.leadId, ...c.members.map((m) => m.personId)].map((id) => due.get(id) ?? null)),
    }));
  });

  protected readonly name = signal('');
  protected readonly lead = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly choices = signal<Record<number, string>>({});
  protected readonly rowErrors = signal<Record<number, string>>({});
  // A submit in flight: a second one before it answers is ignored.
  private submitting = false;

  constructor() {
    // Re-read the crews from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.error.set(null);
        this.crews.set([]);
        this.choices.set({});
        this.rowErrors.set({});
        if (id) this.load(id).catch(() => this.error.set('Could not load the crews.'));
      });
    });
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/crews`;
  }

  private async load(sheet: string): Promise<void> {
    const l = await firstValueFrom(this.http.get<CrewView[]>(this.url(sheet)));
    if (this.session.sheetId() === sheet) this.crews.set(l);
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected setChoice(crew: number, v: string): void {
    this.choices.update((c) => ({ ...c, [crew]: v }));
    this.clearRowError(crew);
  }

  private clearRowError(crew: number): void {
    this.rowErrors.update((e) => {
      const { [crew]: _, ...rest } = e;
      return rest;
    });
  }

  private setRowError(crew: number, err: string): void {
    this.rowErrors.update((e) => ({ ...e, [crew]: err }));
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.name().trim();
    if (!name) {
      this.error.set('Enter a crew name.');
      return;
    }
    if (this.crews().some((c) => c.name === name)) {
      this.error.set(`${name} is already a crew.`);
      return;
    }
    const leadId = Number(this.lead());
    if (!leadId) {
      this.error.set('Choose a lead.');
      return;
    }
    this.submitting = true;
    try {
      const added = await firstValueFrom(this.http.post<CrewView>(this.url(sheet), { name, leadId }));
      if (this.session.sheetId() === sheet) this.crews.update((l) => [...l, added]);
    } catch (err) {
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 409 ? `${name} is already a crew.` : 'Could not add the crew.',
      );
    } finally {
      this.submitting = false;
    }
  }

  protected async addMember(e: Event, crew: CrewView): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.clearRowError(crew.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const personId = Number(this.choices()[crew.id] ?? '');
    if (!personId) {
      this.setRowError(crew.id, 'Choose a person.');
      return;
    }
    const current = this.crews().find((c) => c.id === crew.id);
    if (current?.leadId === personId) {
      this.setRowError(crew.id, 'That person leads the crew.');
      return;
    }
    if (current?.members.some((m) => m.personId === personId)) {
      this.setRowError(crew.id, 'That person is already in the crew.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<CrewView>(`${this.url(sheet)}/${crew.id}/members`, { personId }),
      );
      if (this.session.sheetId() === sheet) this.crews.update((l) => l.map((c) => (c.id === saved.id ? saved : c)));
    } catch (err) {
      this.setRowError(
        crew.id,
        err instanceof HttpErrorResponse && err.status === 409
          ? 'That person leads the crew or is already in it.'
          : 'Could not add the member.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
