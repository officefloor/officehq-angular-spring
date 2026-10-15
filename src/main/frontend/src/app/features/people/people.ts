import { Component, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { GRADES, Grade, PeopleService, Person } from './people.service';

// The people list: every person with their name, alpha, beta, gamma, thrust (alpha × beta), drift
// (gamma − alpha) and momentum (thrust + 3 × drift) and its tier, plus a form to add one. Alpha, beta and
// gamma are optional on add and can be filled in (or changed) on the row later; while any is missing the
// derived values show "n/a". Each row's Edit button loads that person into the form, which then saves
// their alpha, beta and gamma instead of adding. Each person also has whole-number delta and epsilon
// (a second raw axis, blank until given), set on add, on the row or through Edit; their force is delta × epsilon
// ("n/a" while either is missing). Each person may have any number of patrons (other
// people), listed by name and chosen on the row; their backing is the sum of their patrons' thrust
// ("n/a" without patrons), and their net is momentum plus backing (counted as zero without patrons).
// Each person's rank orders the sheet by momentum, highest first (rank 1), with equal momentum ranked
// by higher alpha first, then by name; without a momentum the rank shows "n/a". The sheet's leader
// is the name of the rank-1 person ("n/a" when nobody is ranked). Each ranked person's percentile spreads the ranks evenly from
// 100 (the best) to 0 (the worst), rounded; a lone ranked person is 100.
// Each person may have a grade (LOW, MID or HIGH), chosen on add, in the edit form or on the row; their
// weight comes from it (LOW 1, MID 3, HIGH 5, "n/a" without a grade). Their load is momentum × weight
// ("n/a" while either is missing). The sheet's total load is everyone's load added together, leaving
// out anyone whose load is "n/a".
// The list shows one sheet: typing a sheet id and loading it opens that sheet, whose id is then
// shown, and people added belong to it. Until one is opened the unnamed sheet is shown. Opening an
// id no sheet has yet starts a new empty sheet with that id. Sheets are kept apart: each numbers
// its own people, and a sheet only ever shows (and edits) its own.
// The sheet shows its anchor date (today when none is set), and each person their onset date and
// their age: complete weeks from onset to the anchor, negative (toward zero) when onset is after it ("n/a" without onset).
// Their decay is momentum minus age ("n/a" while either is missing).
// Each person has a state, starting at DRAFT; the row's Advance button moves it forward to REVIEW and
// then FINAL, after which there is no Advance button. The row's Back button moves a REVIEW person back
// to DRAFT; a FINAL person cannot go back, and trying shows that it is not allowed. The row's Rework
// button reopens a FINAL person to REWORK, and Advance moves a REWORK person forward to FINAL again
// (a REWORK person cannot go back either). Every change is recorded in the audit.
// Each person is active or not ("yes" or "no"; a new person is active), marked with the row's checkbox.
// Their effective is their net while active, and half their net rounded down while not active
// ("n/a" while net is).
// The sheet's active count is how many of its people are active, and its total effective is everyone's
// effective added together, leaving out anyone whose effective is "n/a".
// Their clout is the average of their patrons' effective, rounded down (zero without patrons; "n/a" while any
// patron's effective is "n/a").
// Their influence is their effective plus their clout ("n/a" while either is "n/a").
// The sheet's top influence is the name of the person with the highest influence, ties going to the
// name that comes first alphabetically ("n/a" when nobody has an influence).
// Each person may belong to any number of regions: short codes typed in (like N1 or S2), separated by
// commas, given on add, in the edit form or on the row; clearing them removes them. They are listed
// sorted ascending.
// Their reach is the sum, over their regions, of momentum times the region's multiplier, from its first
// letter: N 2, S 3, any other 1 ("n/a" while momentum is missing or they have no region).
// The sheet's region counts show, for each region its people have, how many of them are in it
// (a person counts once in every region they belong to), ordered by region code.
// Each person may follow any number of other people, chosen on the row; following is one-directional,
// so A following B does not make B follow A. A person's allies are the people they follow, listed by name, with how many there are,
// and their synergy: the allies' thrust added together (zero without allies).
// Their combined is their influence plus their synergy ("n/a" while either is "n/a").
// Each person may have a single whole-number score, set on the row. Their note sum is their score (zero
// without one) and their quality is their score ("n/a" without one).
// Their adjusted is their combined plus their quality, counting quality as zero without a score ("n/a" while
// combined is "n/a").
// Their band classifies their adjusted: E below 0, D from 0 to 99, C from 100 to 249, B from 250 to 499 and
// A from 500 ("n/a" while adjusted is "n/a").
// Their scaled is their adjusted times their band's multiplier: A 4, B 3, C 2, D 1, E 0 ("n/a" while
// adjusted is "n/a").
// Their norm is their scaled minus the median scaled of everyone on the sheet whose scaled can be
// worked out; for an even count, the lower of the two middle values ("n/a" while their scaled is "n/a").
// They are an outlier ("yes") when their norm is more than 100 away from zero in either direction, and
// otherwise "no" (also "no" while their norm is "n/a").
// The sheet's outlier count is how many of its people are outliers.
// The sheet's final total is the sum of the scaled of its people whose state is FINAL, counting
// "n/a" as zero.
// The sheet's band counts show how many of its people are in each band, A through E (zero when none
// are); people whose band is "n/a" are not counted.
@Component({
  selector: 'app-people',
  imports: [ReactiveFormsModule],
  template: `
    <h1>People</h1>

    <form (submit)="$event.preventDefault(); openSheet(sheetInput.value)" data-testid="sheet-form">
      <label for="sheet-id">Sheet</label>
      <input #sheetInput id="sheet-id" data-testid="sheet-id-input" />
      <button type="submit" data-testid="sheet-load">Open sheet</button>
    </form>
    <p>
      Open sheet:
      @if (sheet() !== null) {
        <strong data-testid="sheet-current-id">{{ sheet() }}</strong>
      } @else {
        <span data-testid="sheet-none">none</span>
      }
    </p>
    <p>Anchor: <strong data-testid="sheet-anchor">{{ anchor() }}</strong></p>

    <form [formGroup]="form" (ngSubmit)="submit()" data-testid="person-form">
      <label for="person-name">Name</label>
      <input id="person-name" formControlName="name" data-testid="person-form-name" />
      <label for="person-alpha">Alpha</label>
      <input
        id="person-alpha"
        type="number"
        step="1"
        formControlName="alpha"
        data-testid="person-form-alpha"
      />
      <label for="person-beta">Beta</label>
      <input
        id="person-beta"
        type="number"
        step="1"
        formControlName="beta"
        data-testid="person-form-beta"
      />
      <label for="person-gamma">Gamma</label>
      <input
        id="person-gamma"
        type="number"
        step="1"
        formControlName="gamma"
        data-testid="person-form-gamma"
      />
      <label for="person-delta">Delta</label>
      <input
        id="person-delta"
        type="number"
        step="1"
        formControlName="delta"
        data-testid="person-form-delta"
      />
      <label for="person-epsilon">Epsilon</label>
      <input
        id="person-epsilon"
        type="number"
        step="1"
        formControlName="epsilon"
        data-testid="person-form-epsilon"
      />
      <label for="person-grade">Grade</label>
      <select id="person-grade" formControlName="grade" data-testid="person-form-grade">
        <option [ngValue]="null">none</option>
        @for (g of grades; track g) {
          <option [ngValue]="g">{{ g }}</option>
        }
      </select>
      <label for="person-region">Regions (comma-separated)</label>
      <input
        id="person-region"
        formControlName="region"
        data-testid="person-form-region"
      />
      <button type="submit" [disabled]="form.invalid || saving()" data-testid="person-form-submit">
        {{ editing() ? 'Save ' + editing()!.name : 'Add person' }}
      </button>
      @if (editing()) {
        <button type="button" (click)="cancelEdit()" data-testid="person-form-cancel">Cancel</button>
      }
    </form>

    <p>Leader: <strong data-testid="rollup-leader">{{ leader() ?? 'n/a' }}</strong></p>
    <p>Total load: <strong data-testid="rollup-total-load">{{ totalLoad() }}</strong></p>
    <p>Active: <strong data-testid="rollup-active-count">{{ activeCount() }}</strong></p>
    <p>Total effective: <strong data-testid="rollup-total-effective">{{ totalEffective() }}</strong></p>
    <p>Top influence: <strong data-testid="rollup-top-influence">{{ topInfluence() ?? 'n/a' }}</strong></p>
    <p>Outliers: <strong data-testid="rollup-outlier-count">{{ outlierCount() }}</strong></p>
    <p>Final total: <strong data-testid="rollup-final-total">{{ finalTotal() }}</strong></p>
    <section aria-labelledby="region-counts-heading">
      <h2 id="region-counts-heading">People per region</h2>
      @if (regionCounts().length === 0) {
        <p>No regions yet.</p>
      } @else {
        <ul>
          @for (r of regionCounts(); track r.region) {
            <li>
              {{ r.region }}: <strong [attr.data-testid]="'region-count-' + r.region">{{ r.count }}</strong>
            </li>
          }
        </ul>
      }
    </section>
    <section aria-labelledby="band-counts-heading">
      <h2 id="band-counts-heading">People per band</h2>
      <ul>
        @for (b of bandCounts(); track b.band) {
          <li>
            {{ b.band }}: <strong [attr.data-testid]="'band-count-' + b.band">{{ b.count }}</strong>
          </li>
        }
      </ul>
    </section>

    @if (people().length === 0) {
      <p data-testid="people-empty">No people yet.</p>
    }
    @if (stateError(); as error) {
      <p role="alert" data-testid="person-state-error">{{ error }}</p>
    }
    <table data-testid="people-table">
      <thead>
        <tr>
          <th scope="col">Name</th>
          <th scope="col">Alpha</th>
          <th scope="col">Beta</th>
          <th scope="col">Gamma</th>
          <th scope="col">Delta</th>
          <th scope="col">Epsilon</th>
          <th scope="col">Grade</th>
          <th scope="col">Regions</th>
          <th scope="col">Active</th>
          <th scope="col">State</th>
          <th scope="col">Onset</th>
          <th scope="col">Age</th>
          <th scope="col">Decay</th>
          <th scope="col">Weight</th>
          <th scope="col">Thrust</th>
          <th scope="col">Drift</th>
          <th scope="col">Momentum</th>
          <th scope="col">Force</th>
          <th scope="col">Reach</th>
          <th scope="col">Load</th>
          <th scope="col">Tier</th>
          <th scope="col">Patrons</th>
          <th scope="col">Allies</th>
          <th scope="col">Ally count</th>
          <th scope="col">Synergy</th>
          <th scope="col">Backing</th>
          <th scope="col">Net</th>
          <th scope="col">Effective</th>
          <th scope="col">Clout</th>
          <th scope="col">Influence</th>
          <th scope="col">Combined</th>
          <th scope="col">Score</th>
          <th scope="col">Note sum</th>
          <th scope="col">Quality</th>
          <th scope="col">Adjusted</th>
          <th scope="col">Band</th>
          <th scope="col">Scaled</th>
          <th scope="col">Norm</th>
          <th scope="col">Outlier</th>
          <th scope="col">Rank</th>
          <th scope="col">Percentile</th>
          <th scope="col">Set alpha</th>
          <th scope="col">Set beta</th>
          <th scope="col">Set gamma</th>
          <th scope="col">Set delta</th>
          <th scope="col">Set epsilon</th>
          <th scope="col">Set grade</th>
          <th scope="col">Set regions</th>
          <th scope="col">Set active</th>
          <th scope="col">Advance</th>
          <th scope="col">Back</th>
          <th scope="col">Rework</th>
          <th scope="col">Set patrons</th>
          <th scope="col">Set allies</th>
          <th scope="col">Set score</th>
          <th scope="col">Edit</th>
        </tr>
      </thead>
      <tbody>
        @for (p of people(); track p.id) {
          <tr [attr.data-testid]="'person-row-' + p.id">
            <td data-testid="person-name">{{ p.name }}</td>
            <td data-testid="person-alpha">{{ p.alpha }}</td>
            <td data-testid="person-beta">{{ p.beta }}</td>
            <td data-testid="person-gamma">{{ p.gamma }}</td>
            <td data-testid="person-delta">{{ p.delta }}</td>
            <td data-testid="person-epsilon">{{ p.epsilon }}</td>
            <td data-testid="person-grade">{{ p.grade }}</td>
            <td data-testid="person-region">{{ p.regions.join(', ') }}</td>
            <td data-testid="person-active">{{ p.active ? 'yes' : 'no' }}</td>
            <td data-testid="person-state">{{ p.state }}</td>
            <td data-testid="person-onset">{{ p.onset }}</td>
            <td data-testid="concept-age">{{ p.age ?? 'n/a' }}</td>
            <td data-testid="concept-decay">{{ p.decay ?? 'n/a' }}</td>
            <td data-testid="concept-weight">{{ p.weight ?? 'n/a' }}</td>
            <td data-testid="concept-thrust">{{ p.thrust ?? 'n/a' }}</td>
            <td data-testid="concept-drift">{{ p.drift ?? 'n/a' }}</td>
            <td data-testid="concept-momentum">{{ p.momentum ?? 'n/a' }}</td>
            <td data-testid="concept-force">{{ p.force ?? 'n/a' }}</td>
            <td data-testid="concept-reach">{{ p.reach ?? 'n/a' }}</td>
            <td data-testid="concept-load">{{ p.load ?? 'n/a' }}</td>
            <td data-testid="concept-tier">{{ p.tier ?? 'n/a' }}</td>
            <td data-testid="person-patrons">{{ patronNames(p) }}</td>
            <td data-testid="person-allies">{{ allyNames(p) }}</td>
            <td data-testid="person-ally-count">{{ p.allyCount }}</td>
            <td data-testid="concept-synergy">{{ p.synergy ?? 'n/a' }}</td>
            <td data-testid="concept-backing">{{ p.backing ?? 'n/a' }}</td>
            <td data-testid="concept-net">{{ p.net ?? 'n/a' }}</td>
            <td data-testid="concept-effective">{{ p.effective ?? 'n/a' }}</td>
            <td data-testid="concept-clout">{{ p.clout ?? 'n/a' }}</td>
            <td data-testid="concept-influence">{{ p.influence ?? 'n/a' }}</td>
            <td data-testid="concept-combined">{{ p.combined ?? 'n/a' }}</td>
            <td data-testid="person-score">{{ p.score }}</td>
            <td data-testid="concept-note-sum">{{ p.noteSum }}</td>
            <td data-testid="concept-quality">{{ p.quality ?? 'n/a' }}</td>
            <td data-testid="concept-adjusted">{{ p.adjusted ?? 'n/a' }}</td>
            <td data-testid="concept-band">{{ p.band ?? 'n/a' }}</td>
            <td data-testid="concept-scaled">{{ p.scaled ?? 'n/a' }}</td>
            <td data-testid="concept-norm">{{ p.norm ?? 'n/a' }}</td>
            <td data-testid="concept-outlier">{{ p.outlier ? 'yes' : 'no' }}</td>
            <td data-testid="concept-rank">{{ p.rank ?? 'n/a' }}</td>
            <td data-testid="concept-percentile">{{ p.percentile ?? 'n/a' }}</td>
            <td>
              <form (submit)="$event.preventDefault(); setAlpha(p, alphaInput.value)">
                <label class="visually-hidden" [for]="'person-alpha-edit-' + p.id">
                  Alpha for {{ p.name }}
                </label>
                <input
                  #alphaInput
                  [id]="'person-alpha-edit-' + p.id"
                  type="number"
                  step="1"
                  [value]="p.alpha ?? ''"
                  data-testid="person-alpha-input"
                />
                <button type="submit" data-testid="person-alpha-save">Save</button>
              </form>
            </td>
            <td>
              <form (submit)="$event.preventDefault(); setBeta(p, betaInput.value)">
                <label class="visually-hidden" [for]="'person-beta-edit-' + p.id">
                  Beta for {{ p.name }}
                </label>
                <input
                  #betaInput
                  [id]="'person-beta-edit-' + p.id"
                  type="number"
                  step="1"
                  [value]="p.beta ?? ''"
                  data-testid="person-beta-input"
                />
                <button type="submit" data-testid="person-beta-save">Save</button>
              </form>
            </td>
            <td>
              <form (submit)="$event.preventDefault(); setGamma(p, gammaInput.value)">
                <label class="visually-hidden" [for]="'person-gamma-edit-' + p.id">
                  Gamma for {{ p.name }}
                </label>
                <input
                  #gammaInput
                  [id]="'person-gamma-edit-' + p.id"
                  type="number"
                  step="1"
                  [value]="p.gamma ?? ''"
                  data-testid="person-gamma-input"
                />
                <button type="submit" data-testid="person-gamma-save">Save</button>
              </form>
            </td>
            <td>
              <form (submit)="$event.preventDefault(); setDelta(p, deltaInput.value)">
                <label class="visually-hidden" [for]="'person-delta-edit-' + p.id">
                  Delta for {{ p.name }}
                </label>
                <input
                  #deltaInput
                  [id]="'person-delta-edit-' + p.id"
                  type="number"
                  step="1"
                  [value]="p.delta ?? ''"
                  data-testid="person-delta-input"
                />
                <button type="submit" data-testid="person-delta-save">Save</button>
              </form>
            </td>
            <td>
              <form (submit)="$event.preventDefault(); setEpsilon(p, epsilonInput.value)">
                <label class="visually-hidden" [for]="'person-epsilon-edit-' + p.id">
                  Epsilon for {{ p.name }}
                </label>
                <input
                  #epsilonInput
                  [id]="'person-epsilon-edit-' + p.id"
                  type="number"
                  step="1"
                  [value]="p.epsilon ?? ''"
                  data-testid="person-epsilon-input"
                />
                <button type="submit" data-testid="person-epsilon-save">Save</button>
              </form>
            </td>
            <td>
              <label class="visually-hidden" [for]="'person-grade-edit-' + p.id">
                Grade for {{ p.name }}
              </label>
              <select
                [id]="'person-grade-edit-' + p.id"
                (change)="setGrade(p, gradeSelect.value)"
                #gradeSelect
                data-testid="person-grade-select"
              >
                <option value="" [selected]="p.grade === null">none</option>
                @for (g of grades; track g) {
                  <option [value]="g" [selected]="p.grade === g">{{ g }}</option>
                }
              </select>
            </td>
            <td>
              <form (submit)="$event.preventDefault(); setRegions(p, regionInput.value)">
                <label class="visually-hidden" [for]="'person-region-edit-' + p.id">
                  Regions for {{ p.name }}
                </label>
                <input
                  #regionInput
                  [id]="'person-region-edit-' + p.id"
                  [value]="p.regions.join(', ')"
                  data-testid="person-region-input"
                />
                <button type="submit" data-testid="person-region-save">Save</button>
              </form>
            </td>
            <td>
              <label class="visually-hidden" [for]="'person-active-edit-' + p.id">
                {{ p.name }} is active
              </label>
              <input
                type="checkbox"
                [id]="'person-active-edit-' + p.id"
                [checked]="p.active"
                (change)="setActive(p, activeCheckbox.checked)"
                #activeCheckbox
                data-testid="person-active-toggle"
              />
            </td>
            <td>
              @if (p.state !== 'FINAL') {
                <button
                  type="button"
                  (click)="advance(p)"
                  [attr.aria-label]="'Advance ' + p.name"
                  [attr.data-testid]="'person-advance-' + p.id"
                >
                  Advance
                </button>
              }
            </td>
            <td>
              @if (p.state !== 'DRAFT') {
                <button
                  type="button"
                  (click)="demote(p)"
                  [attr.aria-label]="'Move ' + p.name + ' back'"
                  [attr.data-testid]="'person-demote-' + p.id"
                >
                  Back
                </button>
              }
            </td>
            <td>
              @if (p.state === 'FINAL') {
                <button
                  type="button"
                  (click)="rework(p)"
                  [attr.aria-label]="'Rework ' + p.name"
                  [attr.data-testid]="'person-rework-' + p.id"
                >
                  Rework
                </button>
              }
            </td>
            <td>
              <label class="visually-hidden" [for]="'person-patron-edit-' + p.id">
                Patrons for {{ p.name }}
              </label>
              <select
                multiple
                [id]="'person-patron-edit-' + p.id"
                (change)="setPatrons(p, patronSelect)"
                #patronSelect
                data-testid="person-patron-select"
              >
                @for (other of people(); track other.id) {
                  @if (other.id !== p.id) {
                    <option [value]="other.id" [selected]="hasPatron(p, other)">{{ other.name }}</option>
                  }
                }
              </select>
            </td>
            <td>
              <label class="visually-hidden" [for]="'person-ally-edit-' + p.id">
                People {{ p.name }} follows
              </label>
              <select
                multiple
                [id]="'person-ally-edit-' + p.id"
                (change)="setAllies(p, allySelect)"
                #allySelect
                data-testid="person-ally-select"
              >
                @for (other of people(); track other.id) {
                  @if (other.id !== p.id) {
                    <option [value]="other.id" [selected]="hasAlly(p, other)">{{ other.name }}</option>
                  }
                }
              </select>
            </td>
            <td>
              <form (submit)="$event.preventDefault(); setScore(p, scoreInput.value)">
                <label class="visually-hidden" [for]="'person-score-edit-' + p.id">
                  Score for {{ p.name }}
                </label>
                <input
                  #scoreInput
                  [id]="'person-score-edit-' + p.id"
                  type="number"
                  step="1"
                  [value]="p.score ?? ''"
                  data-testid="person-score-input"
                />
                <button type="submit" data-testid="person-score-save">Save</button>
              </form>
            </td>
            <td>
              <button
                type="button"
                (click)="edit(p)"
                [attr.aria-label]="'Edit ' + p.name"
                [attr.data-testid]="'person-edit-' + p.id"
              >
                Edit
              </button>
            </td>
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class People {
  private readonly service = inject(PeopleService);

  protected readonly grades = GRADES;
  protected readonly people = signal<Person[]>([]);
  protected readonly sheet = signal<string | null>(null);
  protected readonly anchor = signal('');
  protected readonly saving = signal(false);
  protected readonly stateError = signal<string | null>(null);
  protected readonly editing = signal<Person | null>(null);
  protected readonly leader = computed(() => this.people().find((p) => p.rank === 1)?.name ?? null);
  protected readonly totalLoad = computed(() =>
    this.people().reduce((total, p) => total + (p.load ?? 0), 0),
  );
  protected readonly activeCount = computed(() => this.people().filter((p) => p.active).length);
  protected readonly outlierCount = computed(() => this.people().filter((p) => p.outlier).length);
  protected readonly totalEffective = computed(() =>
    this.people().reduce((total, p) => total + (p.effective ?? 0), 0),
  );
  protected readonly finalTotal = computed(() =>
    this.people()
      .filter((p) => p.state === 'FINAL')
      .reduce((total, p) => total + (p.scaled ?? 0), 0),
  );
  protected readonly regionCounts = computed(() => {
    const counts = new Map<string, number>();
    for (const p of this.people()) {
      for (const region of p.regions) counts.set(region, (counts.get(region) ?? 0) + 1);
    }
    return [...counts.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([region, count]) => ({ region, count }));
  });
  protected readonly bandCounts = computed(() =>
    BANDS.map((band) => ({ band, count: this.people().filter((p) => p.band === band).length })),
  );
  protected readonly topInfluence = computed(() => {
    let top: Person | null = null;
    for (const p of this.people()) {
      if (p.influence === null) continue;
      if (
        top === null ||
        p.influence > top.influence! ||
        (p.influence === top.influence && p.name.localeCompare(top.name) < 0)
      ) {
        top = p;
      }
    }
    return top?.name ?? null;
  });

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.pattern(/\S/)]],
    alpha: [null as number | null, [Validators.pattern(/^-?\d+$/)]],
    beta: [null as number | null, [Validators.pattern(/^-?\d+$/)]],
    gamma: [null as number | null, [Validators.pattern(/^-?\d+$/)]],
    delta: [null as number | null, [Validators.pattern(/^-?\d+$/)]],
    epsilon: [null as number | null, [Validators.pattern(/^-?\d+$/)]],
    grade: [null as Grade | null],
    region: [''],
  });

  constructor() {
    this.load();
  }

  protected openSheet(value: string): void {
    const id = value.trim();
    const sheet = id === '' ? null : id;
    this.sheet.set(sheet);
    this.cancelEdit();
    if (sheet === null) {
      this.load();
    } else {
      this.service.openSheet(sheet).subscribe(() => {
        if (this.sheet() === sheet) this.load();
      });
    }
  }

  protected edit(person: Person): void {
    this.editing.set(person);
    this.form.reset({ name: person.name, alpha: person.alpha, beta: person.beta, gamma: person.gamma, delta: person.delta, epsilon: person.epsilon, grade: person.grade, region: person.regions.join(', ') });
    this.form.controls.name.disable();
  }

  protected cancelEdit(): void {
    this.editing.set(null);
    this.form.controls.name.enable();
    this.form.reset();
  }

  protected submit(): void {
    const { name, alpha, beta, gamma, delta, epsilon, grade, region: regionText } = this.form.getRawValue();
    const regions = toRegions(regionText);
    if (this.form.invalid) return;
    const editing = this.editing();
    const request = editing
      ? this.service.update(this.sheet(), editing.id, { alpha: toNumber(alpha), beta: toNumber(beta), gamma: toNumber(gamma), delta: toNumber(delta), epsilon: toNumber(epsilon), grade, regions })
      : this.service.add(this.sheet(), { name, alpha, beta, gamma, delta, epsilon, grade, regions });
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.saving.set(false);
        this.cancelEdit();
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  protected setAlpha(person: Person, value: string): void {
    const text = value.trim();
    if (text !== '' && !/^-?\d+$/.test(text)) return;
    const alpha = text === '' ? null : Number(text);
    this.service.setAlpha(this.sheet(), person.id, alpha).subscribe(() => this.load());
  }

  protected setBeta(person: Person, value: string): void {
    const text = value.trim();
    if (text !== '' && !/^-?\d+$/.test(text)) return;
    const beta = text === '' ? null : Number(text);
    this.service.setBeta(this.sheet(), person.id, beta).subscribe(() => this.load());
  }

  protected setGamma(person: Person, value: string): void {
    const text = value.trim();
    if (text !== '' && !/^-?\d+$/.test(text)) return;
    const gamma = text === '' ? null : Number(text);
    this.service.setGamma(this.sheet(), person.id, gamma).subscribe(() => this.load());
  }

  protected setDelta(person: Person, value: string): void {
    const text = value.trim();
    if (text !== '' && !/^-?\d+$/.test(text)) return;
    const delta = text === '' ? null : Number(text);
    this.service.setDelta(this.sheet(), person.id, delta).subscribe(() => this.load());
  }

  protected setEpsilon(person: Person, value: string): void {
    const text = value.trim();
    if (text !== '' && !/^-?\d+$/.test(text)) return;
    const epsilon = text === '' ? null : Number(text);
    this.service.setEpsilon(this.sheet(), person.id, epsilon).subscribe(() => this.load());
  }

  protected setGrade(person: Person, value: string): void {
    const grade = GRADES.find((g) => g === value) ?? null;
    this.service.setGrade(this.sheet(), person.id, grade).subscribe(() => this.load());
  }

  protected setRegions(person: Person, value: string): void {
    this.service.setRegions(this.sheet(), person.id, toRegions(value)).subscribe(() => this.load());
  }

  protected setActive(person: Person, active: boolean): void {
    this.service.setActive(this.sheet(), person.id, active).subscribe(() => this.load());
  }

  protected advance(person: Person): void {
    this.stateError.set(null);
    this.service.advance(this.sheet(), person.id).subscribe(() => this.load());
  }

  protected rework(person: Person): void {
    this.stateError.set(null);
    this.service.rework(this.sheet(), person.id).subscribe(() => this.load());
  }

  protected demote(person: Person): void {
    this.stateError.set(null);
    this.service.demote(this.sheet(), person.id).subscribe({
      next: () => this.load(),
      error: () => this.stateError.set(`${person.name} is ${person.state} and cannot move back.`),
    });
  }

  protected patronNames(person: Person): string {
    return person.patrons.map((patron) => patron.name).join(', ');
  }

  protected hasPatron(person: Person, other: Person): boolean {
    return person.patrons.some((patron) => patron.id === other.id);
  }

  protected setPatrons(person: Person, select: HTMLSelectElement): void {
    const patronIds = Array.from(select.selectedOptions, (option) => Number(option.value));
    this.service.setPatrons(this.sheet(), person.id, patronIds).subscribe(() => this.load());
  }

  protected allyNames(person: Person): string {
    return person.allies.map((ally) => ally.name).join(', ');
  }

  protected hasAlly(person: Person, other: Person): boolean {
    return person.allies.some((ally) => ally.id === other.id);
  }

  protected setAllies(person: Person, select: HTMLSelectElement): void {
    const allyIds = Array.from(select.selectedOptions, (option) => Number(option.value));
    this.service.setAllies(this.sheet(), person.id, allyIds).subscribe(() => this.load());
  }

  protected setScore(person: Person, value: string): void {
    const text = value.trim();
    if (text !== '' && !/^-?\d+$/.test(text)) return;
    const score = text === '' ? null : Number(text);
    this.service.setScore(this.sheet(), person.id, score).subscribe(() => this.load());
  }

  private load(): void {
    const sheet = this.sheet();
    this.service.sheet(sheet).subscribe((s) => {
      if (this.sheet() === sheet) this.anchor.set(s.anchor);
    });
    this.service.list(sheet).subscribe((people) => {
      if (this.sheet() === sheet) this.people.set(people);
    });
  }
}

// The bands, best first.
const BANDS = ['A', 'B', 'C', 'D', 'E'] as const;

// A number input's value as a whole number, or null when it is empty.
function toNumber(value: number | string | null): number | null {
  return value === null || value === '' ? null : Number(value);
}

// Typed regions, separated by commas, each trimmed, leaving out blanks and repeats.
function toRegions(value: string): string[] {
  return [...new Set(value.split(',').map((r) => r.trim()).filter((r) => r !== ''))];
}
