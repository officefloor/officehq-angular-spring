import { HttpClient } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { money } from '../claims/claims';

export interface Crew {
  id: number;
  /** The crew's name, trimmed; unique on the sheet, it names the crew's row. */
  name: string;
  /** The person who leads the crew. */
  leadId: number;
  /** The members' person ids, the lead not among them, in the order they were added. */
  memberIds: number[];
}

/** A person as a crew shows them: their name and their due-total in cents (null: n/a). */
export interface CrewPerson {
  id: number;
  name: string;
  due: number | null;
}

// The active sheet's crews, in the order they were added, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class CrewStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly crews = signal<Crew[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.crews.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Add a crew led by a person; the server refuses a blank name or one already a crew's. */
  async add(name: string, leadId: number): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(this.http.post<Crew>(this.base(sheet), { name, leadId }));
    this.keep(sheet, saved);
  }

  /** Add a person to a crew; the server refuses the crew's lead or a person in it already. */
  async addMember(crewId: number, personId: number): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(this.http.post<Crew>(`${this.base(sheet)}/${crewId}/members`, { personId }));
    this.keep(sheet, saved);
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/crews`;
  }

  /** Show a crew as saved, in its place, or last when it is new. */
  private keep(sheet: string, saved: Crew): void {
    if (this.session.sheetId() !== sheet) return;
    this.crews.update((list) =>
      list.some((c) => c.id === saved.id) ? list.map((c) => (c.id === saved.id ? saved : c)) : [...list, saved],
    );
  }

  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(this.http.get<Crew[]>(this.base(sheet)));
    if (this.session.loads() !== load) return;
    this.crews.set(list);
  }
}

/** A crew as its row shows it: its lead's name, its members' names and its due-total. */
interface CrewRow {
  crew: Crew;
  lead: string;
  members: { id: number; name: string }[];
  /** The lead's and the members' due-totals added up, n/a skipped (two decimals). */
  dueTotal: string;
}

// The sheet's crews, the form that adds one, and on each crew's row the entry that adds a member.
@Component({
  selector: 'app-crews',
  template: `
    <section aria-labelledby="crews-heading">
      <h2 id="crews-heading">Crews</h2>
      <form (submit)="submit($event)">
        <label for="crew-form-name">Crew name</label>
        <input
          id="crew-form-name"
          data-testid="crew-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="crew-form-lead">Lead</label>
        <select id="crew-form-lead" data-testid="crew-form-lead" (change)="lead.set(value($event)); error.set(null)">
          <option value="" [selected]="lead() === ''">Choose a person</option>
          @for (p of people(); track p.id) {
            <option [value]="p.id" [selected]="lead() === '' + p.id">{{ p.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit">Add crew</button>
        @if (error(); as message) {
          <p role="alert" data-testid="crew-form-error">{{ message }}</p>
        }
      </form>
      <ul>
        @for (row of rows(); track row.crew.id) {
          <li [attr.data-testid]="'crew-row-' + row.crew.name">
            <h3>{{ row.crew.name }}</h3>
            <p>
              Lead <strong data-testid="crew-lead">{{ row.lead }}</strong>,
              due in total <strong data-testid="crew-due-total">{{ row.dueTotal }}</strong>
            </p>
            <ul>
              @for (m of row.members; track m.id) {
                <li data-testid="crew-member-line">{{ m.name }}</li>
              }
            </ul>
            <label [for]="'crew-member-select-' + row.crew.id">Member</label>
            <select
              [id]="'crew-member-select-' + row.crew.id"
              data-testid="crew-member-select"
              (change)="setMember(row.crew.id, value($event))"
            >
              <option value="" [selected]="!entries()[row.crew.id]">Choose a person</option>
              @for (p of people(); track p.id) {
                <option [value]="p.id" [selected]="entries()[row.crew.id] === '' + p.id">{{ p.name }}</option>
              }
            </select>
            <button type="button" data-testid="crew-member-add" (click)="addMember(row.crew)">Add member</button>
            @if (memberErrors()[row.crew.id]; as message) {
              <p role="alert" data-testid="crew-error">{{ message }}</p>
            }
          </li>
        }
      </ul>
    </section>
  `,
})
export class Crews {
  /** The sheet's people with their due-totals, a lead's and member's name and figure read from here. */
  readonly people = input.required<CrewPerson[]>();

  protected readonly store = inject(CrewStore);
  protected readonly name = signal('');
  /** The lead chosen in the form (the select's value). */
  protected readonly lead = signal('');
  protected readonly error = signal<string | null>(null);
  /** The member chosen on each crew's row (the select's value), by crew id. */
  protected readonly entries = signal<Record<number, string>>({});
  /** The error shown on each crew's row, by crew id. */
  protected readonly memberErrors = signal<Record<number, string | null>>({});
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);

  protected readonly rows = computed<CrewRow[]>(() => {
    const byId = new Map(this.people().map((p) => [p.id, p]));
    return this.store.crews().map((crew) => {
      const due = [crew.leadId, ...crew.memberIds]
        .map((id) => byId.get(id)?.due ?? null)
        .reduce<number>((sum, d) => sum + (d ?? 0), 0);
      return {
        crew,
        lead: byId.get(crew.leadId)?.name ?? '',
        members: crew.memberIds.map((id) => ({ id, name: byId.get(id)?.name ?? '' })),
        dueTotal: money(due),
      };
    });
  });

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected setMember(crewId: number, member: string): void {
    this.entries.update((e) => ({ ...e, [crewId]: member }));
    this.memberErrors.update((e) => ({ ...e, [crewId]: null }));
  }

  /** Add the typed crew, its name trimmed, led by the chosen person; a blank name, no lead or a name
   *  already a crew's is refused beside the form. */
  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const name = this.name().trim();
    const lead = Number(this.lead());
    if (name === '') {
      this.error.set('Type a name for the crew.');
      return;
    }
    if (!Number.isInteger(lead) || lead <= 0) {
      this.error.set('Choose a lead for the crew.');
      return;
    }
    if (this.store.crews().some((c) => c.name === name)) {
      this.error.set(`The crew ${name} is on the sheet already.`);
      return;
    }
    this.busy.set(true);
    try {
      await this.store.add(name, lead);
    } catch {
      this.error.set(`The crew ${name} could not be added: the name may be a crew's already.`);
    } finally {
      this.busy.set(false);
    }
  }

  /** Add the chosen person to the crew; its lead or a person in it already is refused on the crew's row. */
  protected async addMember(crew: Crew): Promise<void> {
    if (this.busy()) return;
    const refuse = (message: string) => this.memberErrors.update((e) => ({ ...e, [crew.id]: message }));
    this.memberErrors.update((e) => ({ ...e, [crew.id]: null }));
    const person = Number(this.entries()[crew.id] ?? '');
    if (!Number.isInteger(person) || person <= 0) {
      refuse('Choose a person to add.');
      return;
    }
    if (person === crew.leadId) {
      refuse('That person leads the crew.');
      return;
    }
    if (crew.memberIds.includes(person)) {
      refuse('That person is in the crew already.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.addMember(crew.id, person);
    } catch {
      refuse('The member could not be added: they may lead the crew or be in it already.');
    } finally {
      this.busy.set(false);
    }
  }
}
