import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { money } from '../claims/claims';

/** A person in a crew: their id and name. */
interface MemberView {
  personId: number;
  name: string;
}

/** A crew on the sheet, with its lead (null: no longer on the sheet) and its members in the order added. */
interface CrewView {
  id: number;
  name: string;
  lead: MemberView | null;
  members: MemberView[];
}

/** A crew as shown, with its due-total (two decimals). */
interface CrewLine extends CrewView {
  dueTotal: string;
  cents: number;
}

// The sheet's crews, each name once (trimmed, not blank), each led by a person of the sheet, and their
// members: people of the sheet other than the lead, each at most once in a crew. Each crew shows its lead
// and its due-total: the lead's and the members' due-totals as the people section shows them, added up,
// an n/a one skipped. A crew is also a pool (the pools section shows it), so a pool's name is refused too.
@Component({
  selector: 'app-crews',
  imports: [FigurePipe, ReactiveFormsModule],
  template: `
    <section aria-labelledby="crews-heading">
      <h2 id="crews-heading">Crews</h2>
      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a crew">
        <label for="crew-form-name">Crew name</label>
        <input id="crew-form-name" data-testid="crew-form-name" formControlName="name" />
        <label for="crew-form-lead">Lead</label>
        <select id="crew-form-lead" data-testid="crew-form-lead" formControlName="leadId">
          <option value="">Choose a lead</option>
          @for (person of session.people(); track person.id) {
            <option [value]="person.id">{{ person.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit">Add crew</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="crew-form-error">{{ error() }}</p>
        }
      </form>
      <ul aria-label="Crews">
        @for (c of lines(); track c.id) {
          <li [attr.data-testid]="'crew-row-' + c.name">
            <strong>{{ c.name }}</strong>
            — lead <span data-testid="crew-lead">{{ leadShown(c.lead) }}</span>,
            due total <span data-testid="crew-due-total">{{ c.dueTotal | figure }}</span>
            <ul [attr.aria-label]="'Members of ' + c.name">
              @for (m of c.members; track m.personId) {
                <li data-testid="crew-member-line">{{ m.name }}</li>
              }
            </ul>
            <form [formGroup]="memberForm(c.id)" (ngSubmit)="addMember(c)" [attr.aria-label]="'Add a member to ' + c.name">
              <label [for]="'crew-member-select-' + c.id">Person</label>
              <select [id]="'crew-member-select-' + c.id" data-testid="crew-member-select" formControlName="personId">
                <option value="">Choose a person</option>
                @for (person of session.people(); track person.id) {
                  <option [value]="person.id">{{ person.name }}</option>
                }
              </select>
              <button type="submit" data-testid="crew-member-add">Add member</button>
              @if (memberError()?.id === c.id) {
                <p role="alert" class="error" data-testid="crew-error">{{ memberError()?.message }}</p>
              }
            </form>
          </li>
        }
      </ul>
    </section>
  `,
})
export class Crews {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly crews = signal<CrewView[]>([]);
  protected readonly error = signal('');
  /** The error of the last member add refused, on the crew it was asked of. */
  protected readonly memberError = signal<{ id: number; message: string } | null>(null);
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    leadId: new FormControl('', { nonNullable: true }),
  });
  private readonly memberForms = new Map<number, FormGroup<{ personId: FormControl<string> }>>();
  private submitting = false;

  /** Each crew with its due-total: its lead's and members' due-totals as the people show them, n/a skipped. */
  protected readonly lines = computed<CrewLine[]>(() => {
    const dues = this.session.dueTotals();
    return this.crews().map((c) => {
      const ids = [...(c.lead ? [c.lead.personId] : []), ...c.members.map((m) => m.personId)];
      const total = ids.reduce((sum, id) => sum + (dues.get(id) ?? 0), 0);
      return { ...c, dueTotal: money(total), cents: total };
    });
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => this.session.crewTotals.set(this.lines().map((c) => ({ leadId: c.lead?.personId ?? null, cents: c.cents }))));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.crews.set([]);
      this.memberForms.clear();
      this.error.set('');
      this.memberError.set(null);
      if (id) void this.load(id);
    });
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/crews`;
  }

  /** The lead as the screen shows a name, with their grade in force today; blank when no longer on the sheet. */
  protected leadShown(lead: MemberView | null): string {
    if (!lead) return '';
    return this.session.people().find((p) => p.id === lead.personId)?.shown ?? lead.name;
  }

  /** The crew's add-member form, made the first time it is shown. */
  protected memberForm(crewId: number): FormGroup<{ personId: FormControl<string> }> {
    let f = this.memberForms.get(crewId);
    if (!f) {
      f = new FormGroup({ personId: new FormControl('', { nonNullable: true }) });
      f.valueChanges.subscribe(() => {
        if (this.memberError()?.id === crewId) this.memberError.set(null);
      });
      this.memberForms.set(crewId, f);
    }
    return f;
  }

  private async load(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.get<CrewView[]>(this.base(sheet), { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.crews.set(list);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the crews of this sheet.');
    }
  }

  /** Add a crew, its name trimmed, with the chosen lead; a blank name or one already a crew is refused. */
  protected async add(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.form.controls.name.value.trim();
    const leadId = Number(this.form.controls.leadId.value);
    if (name === '' || name.length > 200) {
      this.error.set('Enter a crew name (at most 200 characters).');
      return;
    }
    if (!leadId) {
      this.error.set('Choose a lead.');
      return;
    }
    if (this.crews().some((c) => c.name === name)) {
      this.error.set('That crew is already on the sheet.');
      return;
    }
    this.submitting = true;
    try {
      const list = await firstValueFrom(this.http.post<CrewView[]>(this.base(sheet), { name, leadId }, { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.crews.set(list);
      this.session.crewChanges.update((n) => n + 1);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.error.set(e instanceof HttpErrorResponse && e.status === 409
        ? 'That name is already a crew or a pool on the sheet.'
        : 'Could not add that crew.');
    } finally {
      this.submitting = false;
    }
  }

  /** Add the chosen person to the crew; its lead, or a person already in it, is refused. */
  protected async addMember(crew: CrewView): Promise<void> {
    if (this.submitting) return;
    this.memberError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const personId = Number(this.memberForm(crew.id).controls.personId.value);
    const refuse = (message: string) => this.memberError.set({ id: crew.id, message });
    const already = 'That person is the lead of the crew or already in it.';
    if (!personId) return refuse('Choose a person.');
    if (crew.lead?.personId === personId || crew.members.some((m) => m.personId === personId)) return refuse(already);
    this.submitting = true;
    try {
      const list = await firstValueFrom(
        this.http.post<CrewView[]>(`${this.base(sheet)}/${crew.id}/members`, { personId }, { headers: this.headers() }),
      );
      if (this.session.sheetId() === sheet) this.crews.set(list);
      this.session.crewChanges.update((n) => n + 1);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      refuse(e instanceof HttpErrorResponse && e.status === 409 ? already : 'Could not add that member.');
    } finally {
      this.submitting = false;
    }
  }
}
