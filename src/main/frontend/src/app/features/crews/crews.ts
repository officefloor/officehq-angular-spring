import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { FigurePipe } from '../../core/figure';
import { money } from '../claims/claims';

// The sheet's crews: each a name (trimmed, not blank, at most once on the sheet) and a lead chosen
// from the sheet's people, and its members, each a person on the sheet at most once per crew and
// never its lead (the store refuses each). Each crew shows its lead and its due-total: the lead's
// and the members' due-totals as the people list shows them, added up, n/a skipped. Re-read from
// the store on every sheet load and whenever the people change.
export interface CrewMember {
  personId: number;
  name: string;
}

export interface Crew {
  id: number;
  name: string;
  leadId: number;
  lead: string;
  members: CrewMember[];
}

/** A person who may be chosen as a lead or member, with their due-total in whole cents (null for n/a). */
export interface CrewPerson {
  id: number;
  name: string;
  due: number | null;
}

function refusal(e: unknown, what: string): string {
  if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string' && e.status !== 403 && e.status < 500) {
    return e.error.message;
  }
  return e instanceof HttpErrorResponse && e.status === 403 ? 'Your role may not change crews.' : what;
}

@Component({
  selector: 'app-crew-row',
  imports: [FigurePipe],
  template: `
    <article [attr.data-testid]="'crew-row-' + crew().name">
      <h3>{{ crew().name }}</h3>
      <p>
        Lead <strong data-testid="crew-lead">{{ crew().lead }}</strong>
        Due in total <strong data-testid="crew-due-total">{{ dueTotal() | figure }}</strong>
      </p>
      <ul [attr.aria-label]="'Members of ' + crew().name">
        @for (m of crew().members; track m.personId) {
          <li data-testid="crew-member-line">{{ m.name }}</li>
        }
      </ul>
      <form [attr.aria-label]="'Add a member to ' + crew().name" (submit)="add($event)">
        <label [for]="'crew-member-select-' + crew().id">Member</label>
        <select
          [id]="'crew-member-select-' + crew().id"
          data-testid="crew-member-select"
          [value]="personInput()"
          (change)="personInput.set($any($event.target).value); error.set(null)"
        >
          <option value="">Choose a member</option>
          @for (p of people(); track p.id) {
            <option [value]="'' + p.id" [selected]="personInput() === '' + p.id">{{ p.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-member-add">Add member</button>
        @if (error(); as err) {
          <p role="alert" data-testid="crew-error">{{ err }}</p>
        }
      </form>
    </article>
  `,
})
export class CrewRow {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly crew = input.required<Crew>();
  readonly people = input.required<CrewPerson[]>();
  /** The sheet's crews as the store holds them once a member was added. */
  readonly changed = output<Crew[]>();

  protected readonly personInput = signal('');
  protected readonly error = signal<string | null>(null);
  private submitting = false;

  /** The lead's and the members' due-totals added up, n/a skipped. */
  protected readonly dueTotal = computed(() => {
    const ids = [this.crew().leadId, ...this.crew().members.map((m) => m.personId)];
    const dues = new Map(this.people().map((p) => [p.id, p.due]));
    return money(ids.reduce((sum, id) => sum + (dues.get(id) ?? 0), 0));
  });

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const personId = Number(this.personInput());
    if (!personId) {
      this.error.set('Choose a member.');
      return;
    }
    if (personId === this.crew().leadId) {
      this.error.set('That person leads the crew.');
      return;
    }
    if (this.crew().members.some((m) => m.personId === personId)) {
      this.error.set('That person is already in the crew.');
      return;
    }
    const sheet = this.session.sheet();
    this.submitting = true;
    try {
      const crews = await firstValueFrom(
        this.http.post<Crew[]>('/api/crews/members/add', {
          sheet,
          user: this.session.user(),
          crew: this.crew().id,
          person: personId,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.personInput.set('');
      this.changed.emit(crews);
    } catch (e) {
      if (this.session.sheet() === sheet) this.error.set(refusal(e, 'Could not add this member.'));
    } finally {
      this.submitting = false;
    }
  }
}

@Component({
  selector: 'app-crews',
  imports: [CrewRow],
  template: `
    <section aria-labelledby="crews-heading">
      <h2 id="crews-heading">Crews</h2>
      <form aria-label="Crew" (submit)="add($event)">
        <label for="crew-form-name">Name</label>
        <input
          id="crew-form-name"
          data-testid="crew-form-name"
          [value]="nameInput()"
          (input)="nameInput.set($any($event.target).value); error.set(null)"
        />
        <label for="crew-form-lead">Lead</label>
        <select
          id="crew-form-lead"
          data-testid="crew-form-lead"
          [value]="leadInput()"
          (change)="leadInput.set($any($event.target).value); error.set(null)"
        >
          <option value="">Choose a lead</option>
          @for (p of people(); track p.id) {
            <option [value]="'' + p.id" [selected]="leadInput() === '' + p.id">{{ p.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit">Add crew</button>
        @if (error() ?? loadError(); as err) {
          <p role="alert" data-testid="crew-form-error">{{ err }}</p>
        }
      </form>
      @for (c of crews(); track c.id) {
        <app-crew-row [crew]="c" [people]="people()" (changed)="changed($event)" />
      }
    </section>
  `,
})
export class Crews {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** Everyone on the sheet with their due-total: the people a lead or member is chosen from. */
  readonly people = input.required<CrewPerson[]>();

  protected readonly crews = signal<Crew[]>([]);
  protected readonly nameInput = signal('');
  protected readonly leadInput = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly loadError = signal<string | null>(null);
  private submitting = false;
  /** Bumped by every read, so only the latest one's answer is shown. */
  private reads = 0;
  /** The people's names and ids, so a change of figures alone does not re-read the crews. */
  private readonly roster = computed(() => this.people().map((p) => p.id + ':' + p.name).join(','));

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.crews.set([]);
      this.nameInput.set('');
      this.leadInput.set('');
      this.error.set(null);
    });
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.roster(); // a person renamed, added or gone: re-read the names
      if (sheet) void this.fetch(sheet);
    });
  }

  private async fetch(sheet: string): Promise<void> {
    const read = ++this.reads;
    try {
      const crews = await firstValueFrom(this.http.post<Crew[]>('/api/crews/list', { sheet }));
      if (read === this.reads && this.session.sheet() === sheet) {
        this.crews.set(crews);
        this.loadError.set(null);
      }
    } catch {
      if (read === this.reads) this.loadError.set('Could not read the crews on this sheet.');
    }
  }

  protected changed(crews: Crew[]): void {
    ++this.reads;
    this.crews.set(crews);
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const name = this.nameInput().trim();
    if (!name) {
      this.error.set('Enter a crew name.');
      return;
    }
    const lead = Number(this.leadInput());
    if (!lead) {
      this.error.set('Choose a lead.');
      return;
    }
    if (this.crews().some((c) => c.name === name)) {
      this.error.set('That crew is already on the sheet.');
      return;
    }
    const sheet = this.session.sheet();
    this.submitting = true;
    try {
      const crews = await firstValueFrom(
        this.http.post<Crew[]>('/api/crews/add', { sheet, user: this.session.user(), name, lead }),
      );
      if (this.session.sheet() !== sheet) return;
      ++this.reads;
      this.crews.set(crews);
      this.nameInput.set('');
      this.leadInput.set('');
    } catch (e) {
      if (this.session.sheet() === sheet) this.error.set(refusal(e, 'Could not add this crew.'));
    } finally {
      this.submitting = false;
    }
  }
}
