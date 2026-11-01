import { Component, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, switchMap } from 'rxjs';
import { money } from '../../core/claims';
import { CrewView, Crews, crewDueCents } from '../../core/crews';
import { PersonView, nameWithGrade } from '../../core/people';
import { Pools } from '../../core/pools';
import { Sheets } from '../../core/sheet';
import { FigurePipe } from '../../core/figure';

type MemberForm = FormGroup<{ personId: FormControl<string> }>;

// The sheet's crews: each has a lead and members chosen from the sheet's people, and shows their due-totals added up.
@Component({
  selector: 'app-crews',
  imports: [ReactiveFormsModule, FigurePipe],
  template: `
    <section aria-labelledby="crews-heading">
      <h3 id="crews-heading">Crews</h3>
      <ul aria-label="Crews">
        @for (crew of crews.all(); track crew.id) {
          <li [attr.data-testid]="'crew-row-' + crew.name">
            <span data-testid="crew-name">{{ crew.name }}</span>:
            lead <span data-testid="crew-lead">{{ leadShown(crew) }}</span>,
            due <span data-testid="crew-due-total">{{ (dueOf(crew)) | figure }}</span>
            <ul [attr.aria-label]="'Members of ' + crew.name">
              @for (m of crew.members; track m.id) {
                <li data-testid="crew-member-line">{{ m.name }}</li>
              }
            </ul>
            <form [formGroup]="memberForm(crew.id)" (ngSubmit)="addMember(crew.id)"
                  [attr.aria-label]="'Add a member to ' + crew.name">
              <label [attr.for]="'crew-member-select-' + crew.id">Member</label>
              <select [id]="'crew-member-select-' + crew.id" data-testid="crew-member-select" formControlName="personId">
                <option value="">Choose a person</option>
                @for (p of people(); track p.id) {
                  <option [value]="p.id">{{ p.name }}</option>
                }
              </select>
              <button type="submit" data-testid="crew-member-add" [disabled]="busy().has(crew.id)">Add member</button>
              @if (errors().get(crew.id); as err) {
                <p role="alert" data-testid="crew-error">{{ err }}</p>
              }
            </form>
          </li>
        }
      </ul>

      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a crew">
        <label for="crew-form-name">Name</label>
        <input id="crew-form-name" data-testid="crew-form-name" formControlName="name" autocomplete="off" />
        <label for="crew-form-lead">Lead</label>
        <select id="crew-form-lead" data-testid="crew-form-lead" formControlName="leadId">
          <option value="">Choose a person</option>
          @for (p of people(); track p.id) {
            <option [value]="p.id">{{ p.name }}</option>
          }
        </select>
        <button type="submit" data-testid="crew-form-submit" [disabled]="saving()">Add crew</button>
        @if (error(); as err) {
          <p role="alert" data-testid="crew-form-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class CrewsPanel {
  /** The sheet's people, the lead and members are chosen from. */
  readonly people = input.required<PersonView[]>();
  /** Each person's due-total in whole cents, by person id; null when they have none (n/a). */
  readonly dueByPerson = input.required<ReadonlyMap<number, number | null>>();

  protected readonly crews = inject(Crews);
  private readonly sheets = inject(Sheets);
  private readonly pools = inject(Pools);

  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  /** Each crew's error, by crew id. */
  protected readonly errors = signal<ReadonlyMap<number, string>>(new Map());
  /** The crews a member is being added to. */
  protected readonly busy = signal<ReadonlySet<number>>(new Set());
  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
    leadId: new FormControl('', { nonNullable: true }),
  });
  private readonly memberForms = new Map<number, MemberForm>();

  constructor() {
    // Each (re)load of a sheet re-reads its crews from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.error.set(null);
          this.errors.set(new Map());
          this.memberForms.clear();
          return this.crews.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.error.set('Could not read the crews.') });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  /** The crew's due-total: its lead's and members' due-totals added up, skipping those with none. */
  /** The crew's lead as the screen shows them: with their grade in force on the sheet's today; blank with none. */
  protected leadShown(crew: CrewView): string {
    if (!crew.lead) return '';
    const lead = this.people().find((p) => p.id === crew.lead?.id);
    return lead ? nameWithGrade(lead, this.sheets.current()?.today ?? '') : crew.lead.name;
  }

  protected dueOf(crew: CrewView): string {
    return money(crewDueCents(crew, this.dueByPerson()));
  }

  /** The crew row's form to add a member, made the first time the row shows. */
  protected memberForm(crewId: number): MemberForm {
    let form = this.memberForms.get(crewId);
    if (!form) {
      const made: MemberForm = new FormGroup({ personId: new FormControl('', { nonNullable: true }) });
      made.valueChanges.subscribe(() => this.setError(crewId, null));
      this.memberForms.set(crewId, made);
      form = made;
    }
    return form;
  }

  protected add(): void {
    // A second submit before the first has answered is ignored, so it records one crew.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const { name: typed, leadId } = this.form.getRawValue();
    const name = typed.trim();
    if (name === '') {
      this.error.set('Type the crew\'s name.');
      return;
    }
    if (leadId === '') {
      this.error.set('Choose the crew\'s lead.');
      return;
    }
    this.saving.set(true);
    this.crews.add(sheet.id, name, Number(leadId)).subscribe({
      next: () => {
        this.saving.set(false);
        this.form.reset({ name: '', leadId: '' }, { emitEvent: false });
        this.refreshPools(sheet.id);
      },
      error: () => {
        this.saving.set(false);
        this.error.set('Could not add that crew: is it already a crew or a pool on the sheet?');
      },
    });
  }

  protected addMember(crewId: number): void {
    if (this.busy().has(crewId)) return;
    this.setError(crewId, null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const personId = this.memberForm(crewId).getRawValue().personId;
    if (personId === '') {
      this.setError(crewId, 'Choose a person to add.');
      return;
    }
    this.busy.update((s) => new Set(s).add(crewId));
    const done = () => this.busy.update((s) => {
      const next = new Set(s);
      next.delete(crewId);
      return next;
    });
    this.crews.addMember(sheet.id, crewId, Number(personId)).subscribe({
      next: () => {
        done();
        this.memberForm(crewId).reset({ personId: '' }, { emitEvent: false });
        this.refreshPools(sheet.id);
      },
      error: () => {
        done();
        this.setError(crewId, 'Could not add that member: do they lead the crew, or are they already in it?');
      },
    });
  }

  /** A crew is also a pool: re-read the pools so its pool shows the crew as it now stands. */
  private refreshPools(sheetId: string): void {
    this.pools.refresh(sheetId).subscribe({ error: () => undefined });
  }

  private setError(crewId: number, message: string | null): void {
    this.errors.update((m) => {
      const next = new Map(m);
      if (message === null) next.delete(crewId);
      else next.set(crewId, message);
      return next;
    });
  }
}
