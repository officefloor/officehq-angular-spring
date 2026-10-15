import { Component, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { GRADES, Grade, Group, KINDS, Kind, PeopleService, Person } from './people.service';

// The people list: every person with their name, alpha, beta, gamma, thrust (alpha × beta), drift
// (gamma − alpha) and momentum (thrust + 3 × drift) and its tier, plus a form to add one. Alpha, beta and
// gamma are optional on add and can be filled in (or changed) on the row later; while any is missing the
// derived values show "n/a". Each row's Edit button loads that person into the form, which then saves
// their alpha, beta and gamma instead of adding. Each person also has whole-number delta and epsilon
// (a second raw axis, blank until given), set on add, on the row or through Edit; their force is delta × epsilon
// ("n/a" while delta, epsilon or zeta is missing). Each person also has a whole-number zeta (blank until given), set the same
// way; their sway is zeta − delta and their torque is force + 3 × sway ("n/a" while any part is missing),
// and their phase classifies torque: EBB below zero, RUN up to 199, PEAK from 200. Their blend is
// momentum + torque ("n/a" while either is missing). Their flux is the average of their patrons' force, rounded down (0 without
// patrons, "n/a" while any patron's force is missing). Their crest is blend + flux ("n/a" while either is missing).
// The sheet's top crest is the name of the person with the highest crest, ties going to the name that
// comes first alphabetically ("n/a" when nobody has a crest). Their swing is the sum of the positive torque of the people
// they follow (0 when they follow no one, "n/a" while any followed person's torque is missing). Their haul is
// crest + swing ("n/a" while either is missing). Their vector is the average of their patrons' blend, rounded down (0 without patrons,
// "n/a" while any patron's blend is missing). The sheet's total haul is everyone's haul added together,
// leaving out anyone whose haul is "n/a".
// Each person may have any number of patrons (other
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
// Each person may have a kind (A, B or C), chosen on add, in the edit form or on the row; their verge
// comes from it (A 2, B 3, C 5, "n/a" without a kind). Their pivot is torque × verge ("n/a" while either is missing).
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
// Their brace is their influence plus two times their force ("n/a" while either is "n/a").
// Their tide is the brace of the people they follow added together, each followed brace taken as momentum plus
// two times force, counting only positive braces (0 when they follow no one, "n/a" while any followed person's momentum or force is missing).
// Their strain is their crest minus their brace ("n/a" while either is "n/a").
// Their pulse classifies their strain against a tolerance band: NEG below -10, FLAT within 10 of zero, POS above 10 ("n/a" while strain is "n/a").
// The sheet's top influence is the name of the person with the highest influence, ties going to the
// name that comes first alphabetically ("n/a" when nobody has an influence).
// Each person may belong to any number of regions: short codes typed in (like N1 or S2), separated by
// commas, given on add, in the edit form or on the row; clearing them removes them. They are listed
// sorted ascending.
// Their reach is the sum, over their regions, of momentum times the region's multiplier, from its first
// letter: N 2, S 3, any other 1 ("n/a" while momentum is missing or they have no region).
// Their gust is their reach plus two times their torque ("n/a" while either is "n/a").
// Their coil is their gust minus their momentum ("n/a" while either is "n/a").
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
// Their thrum is their scaled plus two times their torque ("n/a" while either is "n/a").
// The sheet's max thrum is the name of the person with the highest thrum, ties going to the name that
// comes first alphabetically ("n/a" when nobody has a thrum).
// Their spool is their thrum minus their blend ("n/a" while either is "n/a").
// Their drag classifies blend: L0 below zero, L1 up to 49, L2 up to 149, L3 from 150 ("n/a" while blend is).
// Their lift is their blend times their drag's multiplier: L0 0, L1 1, L2 2, L3 3 ("n/a" while blend is).
// Their yield is their pivot plus two times their lift ("n/a" while either is "n/a").
// Their grip is their yield minus their coil ("n/a" while either is "n/a").
// Their charge is their vector plus their yield ("n/a" while either is "n/a").
// Their churn is their tide minus their vector ("n/a" while either is "n/a").
// Their flow is their yield plus their charge ("n/a" while either is "n/a").
// The sheet's top charge is the name of the person with the highest charge, ties going to the name
// that comes first alphabetically ("n/a" when nobody has a charge).
// The sheet's total yield is everyone's yield added together, leaving out anyone whose yield is "n/a".
// The sheet's grand flow is everyone's flow added together, leaving out anyone whose flow is "n/a".
// The sheet's top flow is the name of the person with the highest flow, ties going to the name
// that comes first alphabetically ("n/a" when nobody has a flow).
// Their norm is their scaled minus the median scaled of everyone on the sheet whose scaled can be
// worked out; for an even count, the lower of the two middle values ("n/a" while their scaled is "n/a").
// They are an outlier ("yes") when their norm is more than 100 away from zero in either direction, and
// otherwise "no" (also "no" while their norm is "n/a").
// The sheet's outlier count is how many of its people are outliers.
// Their ebb is their blend minus the mean blend of everyone on the sheet whose blend can be worked
// out, rounded down ("n/a" while their blend is "n/a").
// They are a stray ("yes") when their ebb is more than 100 away from zero in either direction, and
// otherwise "no" (also "no" while their ebb is "n/a").
// The sheet's stray count is how many of its people are strays.
// The sheet's final total is the sum of the scaled of its people whose state is FINAL, counting
// "n/a" as zero.
// The sheet's band counts show how many of its people are in each band, A through E (zero when none
// are); people whose band is "n/a" are not counted.
// The sheet's drag counts show how many of its people are in each drag level, L0 through L3 (zero when
// none are); people whose drag is "n/a" are not counted.
// The sheet's pulse counts show how many of its people have each pulse, NEG, FLAT and POS (zero when
// none do); people whose pulse is "n/a" are not counted.
// The sheet's groups are listed by name, with a form to add one. Each person is in one group, chosen on
// add, through Edit or on the row, and the row shows its name.
// Each group shows its strength: the sum of the effective of the people in it, counting "n/a" as zero.
// The sheet's biggest group is the name of the group with the highest strength, ties going to the name
// that comes first alphabetically ("n/a" when there are no groups).
// The sheet's top group size is the size of its biggest group ("n/a" when there are no groups).
// The sheet's total strength is the sum of the strengths of all its groups (0 when there are none).
// The sheet's group count is how many groups it has (0 when there are none).
// The sheet's median strength is the middle of its groups' strengths; for an even count, the lower of
// the two middle values ("n/a" when there are no groups).
// The sheet's strength range is the strongest group's strength minus the weakest's ("n/a" when there
// are no groups).
// Each group shows its rank: its place among the sheet's groups by strength, highest first (1), with
// equal strength placed by the group name earlier in the alphabet.
// Each group shows its cohesion: its strength divided by its size, rounded down ("n/a" when empty).
// Each group shows its vigor: its strength plus its size (0 when empty).
// Each group shows its spread: the highest effective of the people in it minus the lowest, ignoring "n/a"
// ("n/a" when none of its people have an effective).
// Each group shows its apex: the name of the person in it with the highest scaled, ties going to the name
// earlier in the alphabet ("n/a" when none of its people have a scaled).
// Each group shows its apex scaled: the scaled of its apex ("n/a" when it has no apex).
// Each group shows its density: how many of its people have an effective at or above its cohesion, not
// counting anyone whose effective is "n/a" (0 when empty).
// Each group shows its min: the lowest effective of the people in it, ignoring "n/a" ("n/a" when none of its
// people have an effective).
// Each group shows its max: the highest scaled of the people in it, ignoring "n/a" ("n/a" when none of its
// people have a scaled).
// Each group shows its headroom: the highest scaled of the people in it minus the lowest, ignoring "n/a"
// ("n/a" when none of its people have a scaled).
// Each person shows their share: their scaled as a percentage of their group's strength, rounded to the nearest whole percent
// ("n/a" when their group's strength is zero, they have no group or their scaled is "n/a").
// Each person shows their standing: their place within their group by scaled, highest first (1), with
// equal scaled placed by higher alpha first, then by name ("n/a" when they have no group or no scaled).
// Each person shows their belonging: their group's strength minus their scaled ("n/a" when they have no
// group or their scaled is "n/a").
// Each person shows their tether: their scaled minus their group's cohesion ("n/a" when they have no
// group or their scaled is "n/a").
// Each person shows their slack: their group's cohesion minus their scaled ("n/a" when they have no
// group or their scaled is "n/a").
// Each person shows their weave: their scaled plus their group's cohesion ("n/a" when they have no
// group or their scaled is "n/a").
// Each person shows their uplift: their weave minus their tether ("n/a" when either is "n/a").
// Each person shows their mesh: their scaled multiplied by their group's size ("n/a" when they have no
// group or their scaled is "n/a").
// Each person shows their rung: (their group's size less their standing in it) over the group's size, as a
// percentage, rounded; a group of one is 100 ("n/a" without a standing).
// Each person shows their depth: their standing in their group less one ("n/a" without a standing).
// Each person shows their anchorage: how many groups they belong to (0 without one).
// Each person shows their home group: the name of their group ("n/a" without one).
// Each person shows their anchor strength: the strength of their home group ("n/a" without one).
// Each person shows their group rank: the rank of their home group by strength ("n/a" without one).
// The sheet's strongest member is the name of the person with the highest scaled anywhere on the sheet,
// ties going to the name earlier in the alphabet ("n/a" when nobody has a scaled).
// A person may be in several groups; a group's figures count every person in it. Where a person's own
// figure depends on "their group", it is the group, of those they are in, whose name comes first
// alphabetically.
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
      <label for="person-zeta">Zeta</label>
      <input
        id="person-zeta"
        type="number"
        step="1"
        formControlName="zeta"
        data-testid="person-form-zeta"
      />
      <label for="person-grade">Grade</label>
      <select id="person-grade" formControlName="grade" data-testid="person-form-grade">
        <option [ngValue]="null">none</option>
        @for (g of grades; track g) {
          <option [ngValue]="g">{{ g }}</option>
        }
      </select>
      <label for="person-kind">Kind</label>
      <select id="person-kind" formControlName="kind" data-testid="person-form-kind">
        <option [ngValue]="null">none</option>
        @for (k of kinds; track k) {
          <option [ngValue]="k">{{ k }}</option>
        }
      </select>
      <label for="person-group">Group</label>
      <select id="person-group" formControlName="groupId" data-testid="person-form-group">
        <option [ngValue]="null">none</option>
        @for (g of groups(); track g.id) {
          <option [ngValue]="g.id">{{ g.name }}</option>
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

    <section aria-labelledby="groups-heading">
      <h2 id="groups-heading">Groups</h2>
      <form [formGroup]="groupForm" (ngSubmit)="addGroup()" data-testid="group-form">
        <label for="group-name">Group name</label>
        <input id="group-name" formControlName="name" data-testid="group-form-name" />
        <button type="submit" [disabled]="groupForm.invalid" data-testid="group-form-submit">Add group</button>
      </form>
      @if (groups().length === 0) {
        <p data-testid="groups-empty">No groups yet.</p>
      } @else {
        <ul data-testid="groups-list">
          @for (g of groups(); track g.id) {
            <li [attr.data-testid]="'group-row-' + g.id">
              <span data-testid="group-name">{{ g.name }}</span>
              — strength <span data-testid="group-concept-strength">{{ groupStrengths().get(g.id) ?? 0 }}</span>
              — rank <span data-testid="group-concept-rank">{{ groupRanks().get(g.id) }}</span>
              — size <span data-testid="group-concept-size">{{ groupSizes().get(g.id) ?? 0 }}</span>
              — cohesion <span data-testid="group-concept-cohesion">{{ groupCohesions().get(g.id) ?? 'n/a' }}</span>
              — vigor <span data-testid="group-concept-vigor">{{ groupVigors().get(g.id) ?? 0 }}</span>
              — spread <span data-testid="group-concept-spread">{{ groupSpreads().get(g.id) ?? 'n/a' }}</span>
              — apex <span data-testid="group-concept-apex">{{ groupApexes().get(g.id)?.name ?? 'n/a' }}</span>
              — apex scaled <span data-testid="group-concept-apex-scaled">{{ groupApexes().get(g.id)?.scaled ?? 'n/a' }}</span>
              — density <span data-testid="group-concept-density">{{ groupDensities().get(g.id) ?? 0 }}</span>
              — min <span data-testid="group-concept-min">{{ groupMins().get(g.id) ?? 'n/a' }}</span>
              — max <span data-testid="group-concept-max">{{ groupMaxes().get(g.id) ?? 'n/a' }}</span>
              — headroom <span data-testid="group-concept-headroom">{{ groupHeadrooms().get(g.id) ?? 'n/a' }}</span>
              — even <span data-testid="group-concept-even">{{ (groupSizes().get(g.id) ?? 0) % 2 === 0 ? 'yes' : 'no' }}</span>
            </li>
          }
        </ul>
      }
    </section>
    <p>Biggest group: <strong data-testid="rollup-biggest-group">{{ biggestGroup() ?? 'n/a' }}</strong></p>
    <p>Top group size: <strong data-testid="rollup-top-group-size">{{ topGroupSize() ?? 'n/a' }}</strong></p>
    <p>Total strength: <strong data-testid="rollup-total-strength">{{ totalStrength() }}</strong></p>
    <p>Groups: <strong data-testid="rollup-group-count">{{ groupCount() }}</strong></p>
    <p>Median strength: <strong data-testid="rollup-median-strength">{{ medianStrength() ?? 'n/a' }}</strong></p>
    <p>Strength range: <strong data-testid="rollup-strength-range">{{ strengthRange() ?? 'n/a' }}</strong></p>
    <p>Leader: <strong data-testid="rollup-leader">{{ leader() ?? 'n/a' }}</strong></p>
    <p>Total load: <strong data-testid="rollup-total-load">{{ totalLoad() }}</strong></p>
    <p>Active: <strong data-testid="rollup-active-count">{{ activeCount() }}</strong></p>
    <p>Total effective: <strong data-testid="rollup-total-effective">{{ totalEffective() }}</strong></p>
    <p>Top influence: <strong data-testid="rollup-top-influence">{{ topInfluence() ?? 'n/a' }}</strong></p>
    <p>Top crest: <strong data-testid="rollup-top-crest">{{ topCrest() ?? 'n/a' }}</strong></p>
    <p>Top brace: <strong data-testid="rollup-top-brace">{{ topBrace() ?? 'n/a' }}</strong></p>
    <p>Max thrum: <strong data-testid="rollup-max-thrum">{{ maxThrum() ?? 'n/a' }}</strong></p>
    <p>Top charge: <strong data-testid="rollup-top-charge">{{ topCharge() ?? 'n/a' }}</strong></p>
    <p>Total haul: <strong data-testid="rollup-total-haul">{{ totalHaul() }}</strong></p>
    <p>Total pivot: <strong data-testid="rollup-total-pivot">{{ totalPivot() }}</strong></p>
    <p>Total gust: <strong data-testid="rollup-total-gust">{{ totalGust() }}</strong></p>
    <p>Total brace: <strong data-testid="rollup-total-brace">{{ totalBrace() }}</strong></p>
    <p>Total yield: <strong data-testid="rollup-total-yield">{{ totalYield() }}</strong></p>
    <p>Total tide: <strong data-testid="rollup-total-tide">{{ totalTide() }}</strong></p>
    <p>Grand flow: <strong data-testid="rollup-grand-flow">{{ grandFlow() }}</strong></p>
    <p>Top flow: <strong data-testid="rollup-top-flow">{{ topFlow() ?? 'n/a' }}</strong></p>
    <p>Outliers: <strong data-testid="rollup-outlier-count">{{ outlierCount() }}</strong></p>
    <p>Strays: <strong data-testid="rollup-stray-count">{{ strayCount() }}</strong></p>
    <p>Final total: <strong data-testid="rollup-final-total">{{ finalTotal() }}</strong></p>
    <p>Strongest member: <strong data-testid="rollup-strongest-member">{{ strongestMember() ?? 'n/a' }}</strong></p>
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
    <section aria-labelledby="drag-counts-heading">
      <h2 id="drag-counts-heading">People per drag level</h2>
      <ul>
        @for (d of dragCounts(); track d.drag) {
          <li>
            {{ d.drag }}: <strong [attr.data-testid]="'drag-count-' + d.drag">{{ d.count }}</strong>
          </li>
        }
      </ul>
    </section>
    <section aria-labelledby="pulse-counts-heading">
      <h2 id="pulse-counts-heading">People per pulse</h2>
      <ul>
        @for (c of pulseCounts(); track c.pulse) {
          <li>
            {{ c.pulse }}: <strong [attr.data-testid]="'pulse-count-' + c.pulse">{{ c.count }}</strong>
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
          <th scope="col">Zeta</th>
          <th scope="col">Grade</th>
          <th scope="col">Kind</th>
          <th scope="col">Group</th>
          <th scope="col">Regions</th>
          <th scope="col">Active</th>
          <th scope="col">State</th>
          <th scope="col">Onset</th>
          <th scope="col">Age</th>
          <th scope="col">Decay</th>
          <th scope="col">Weight</th>
          <th scope="col">Verge</th>
          <th scope="col">Thrust</th>
          <th scope="col">Drift</th>
          <th scope="col">Momentum</th>
          <th scope="col">Force</th>
          <th scope="col">Sway</th>
          <th scope="col">Torque</th>
          <th scope="col">Pivot</th>
          <th scope="col">Blend</th>
          <th scope="col">Flux</th>
          <th scope="col">Crest</th>
          <th scope="col">Swing</th>
          <th scope="col">Haul</th>
          <th scope="col">Vector</th>
          <th scope="col">Phase</th>
          <th scope="col">Reach</th>
          <th scope="col">Gust</th>
          <th scope="col">Coil</th>
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
          <th scope="col">Brace</th>
          <th scope="col">Tide</th>
          <th scope="col">Strain</th>
          <th scope="col">Pulse</th>
          <th scope="col">Combined</th>
          <th scope="col">Score</th>
          <th scope="col">Note sum</th>
          <th scope="col">Quality</th>
          <th scope="col">Adjusted</th>
          <th scope="col">Band</th>
          <th scope="col">Scaled</th>
          <th scope="col">Thrum</th>
          <th scope="col">Spool</th>
          <th scope="col">Drag</th>
          <th scope="col">Lift</th>
          <th scope="col">Yield</th>
          <th scope="col">Grip</th>
          <th scope="col">Charge</th>
          <th scope="col">Churn</th>
          <th scope="col">Flow</th>
          <th scope="col">Norm</th>
          <th scope="col">Outlier</th>
          <th scope="col">Ebb</th>
          <th scope="col">Stray</th>
          <th scope="col">Share</th>
          <th scope="col">Standing</th>
          <th scope="col">Belonging</th>
          <th scope="col">Tether</th>
          <th scope="col">Slack</th>
          <th scope="col">Weave</th>
          <th scope="col">Uplift</th>
          <th scope="col">Mesh</th>
          <th scope="col">Anchorage</th>
          <th scope="col">Breadth</th>
          <th scope="col">Home group</th>
          <th scope="col">Anchor strength</th>
          <th scope="col">Group rank</th>
          <th scope="col">Rung</th>
          <th scope="col">Depth</th>
          <th scope="col">Rank</th>
          <th scope="col">Percentile</th>
          <th scope="col">Set alpha</th>
          <th scope="col">Set beta</th>
          <th scope="col">Set gamma</th>
          <th scope="col">Set delta</th>
          <th scope="col">Set epsilon</th>
          <th scope="col">Set zeta</th>
          <th scope="col">Set grade</th>
          <th scope="col">Set kind</th>
          <th scope="col">Set group</th>
          <th scope="col">Groups</th>
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
            <td data-testid="person-zeta">{{ p.zeta }}</td>
            <td data-testid="person-grade">{{ p.grade }}</td>
            <td data-testid="person-kind">{{ p.kind }}</td>
            <td data-testid="person-group">{{ groupNamesOf(p) }}</td>
            <td data-testid="person-region">{{ p.regions.join(', ') }}</td>
            <td data-testid="person-active">{{ p.active ? 'yes' : 'no' }}</td>
            <td data-testid="person-state">{{ p.state }}</td>
            <td data-testid="person-onset">{{ p.onset }}</td>
            <td data-testid="concept-age">{{ p.age ?? 'n/a' }}</td>
            <td data-testid="concept-decay">{{ p.decay ?? 'n/a' }}</td>
            <td data-testid="concept-weight">{{ p.weight ?? 'n/a' }}</td>
            <td data-testid="concept-verge">{{ p.verge ?? 'n/a' }}</td>
            <td data-testid="concept-thrust">{{ p.thrust ?? 'n/a' }}</td>
            <td data-testid="concept-drift">{{ p.drift ?? 'n/a' }}</td>
            <td data-testid="concept-momentum">{{ p.momentum ?? 'n/a' }}</td>
            <td data-testid="concept-force">{{ p.force ?? 'n/a' }}</td>
            <td data-testid="concept-sway">{{ p.sway ?? 'n/a' }}</td>
            <td data-testid="concept-torque">{{ p.torque ?? 'n/a' }}</td>
            <td data-testid="concept-pivot">{{ p.pivot ?? 'n/a' }}</td>
            <td data-testid="concept-blend">{{ p.blend ?? 'n/a' }}</td>
            <td data-testid="concept-flux">{{ p.flux ?? 'n/a' }}</td>
            <td data-testid="concept-crest">{{ p.crest ?? 'n/a' }}</td>
            <td data-testid="concept-swing">{{ p.swing ?? 'n/a' }}</td>
            <td data-testid="concept-haul">{{ p.haul ?? 'n/a' }}</td>
            <td data-testid="concept-vector">{{ p.vector ?? 'n/a' }}</td>
            <td data-testid="concept-phase">{{ p.phase ?? 'n/a' }}</td>
            <td data-testid="concept-reach">{{ p.reach ?? 'n/a' }}</td>
            <td data-testid="concept-gust">{{ p.gust ?? 'n/a' }}</td>
            <td data-testid="concept-coil">{{ p.coil ?? 'n/a' }}</td>
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
            <td data-testid="concept-brace">{{ p.brace ?? 'n/a' }}</td>
            <td data-testid="concept-tide">{{ p.tide ?? 'n/a' }}</td>
            <td data-testid="concept-strain">{{ p.strain ?? 'n/a' }}</td>
            <td data-testid="concept-pulse">{{ p.pulse ?? 'n/a' }}</td>
            <td data-testid="concept-combined">{{ p.combined ?? 'n/a' }}</td>
            <td data-testid="person-score">{{ p.score }}</td>
            <td data-testid="concept-note-sum">{{ p.noteSum }}</td>
            <td data-testid="concept-quality">{{ p.quality ?? 'n/a' }}</td>
            <td data-testid="concept-adjusted">{{ p.adjusted ?? 'n/a' }}</td>
            <td data-testid="concept-band">{{ p.band ?? 'n/a' }}</td>
            <td data-testid="concept-scaled">{{ p.scaled ?? 'n/a' }}</td>
            <td data-testid="concept-thrum">{{ p.thrum ?? 'n/a' }}</td>
            <td data-testid="concept-spool">{{ p.spool ?? 'n/a' }}</td>
            <td data-testid="concept-drag">{{ p.drag ?? 'n/a' }}</td>
            <td data-testid="concept-lift">{{ p.lift ?? 'n/a' }}</td>
            <td data-testid="concept-yield">{{ p.yield ?? 'n/a' }}</td>
            <td data-testid="concept-grip">{{ p.grip ?? 'n/a' }}</td>
            <td data-testid="concept-charge">{{ p.charge ?? 'n/a' }}</td>
            <td data-testid="concept-churn">{{ p.churn ?? 'n/a' }}</td>
            <td data-testid="concept-flow">{{ p.flow ?? 'n/a' }}</td>
            <td data-testid="concept-norm">{{ p.norm ?? 'n/a' }}</td>
            <td data-testid="concept-outlier">{{ p.outlier ? 'yes' : 'no' }}</td>
            <td data-testid="concept-ebb">{{ p.ebb ?? 'n/a' }}</td>
            <td data-testid="concept-stray">{{ p.stray ? 'yes' : 'no' }}</td>
            <td data-testid="concept-share">{{ shareOf(p) ?? 'n/a' }}</td>
            <td data-testid="concept-standing">{{ standings().get(p.id) ?? 'n/a' }}</td>
            <td data-testid="concept-belonging">{{ belongingOf(p) ?? 'n/a' }}</td>
            <td data-testid="concept-tether">{{ tetherOf(p) ?? 'n/a' }}</td>
            <td data-testid="concept-slack">{{ slackOf(p) ?? 'n/a' }}</td>
            <td data-testid="concept-weave">{{ weaveOf(p) ?? 'n/a' }}</td>
            <td data-testid="concept-uplift">{{ upliftOf(p) ?? 'n/a' }}</td>
            <td data-testid="concept-mesh">{{ meshOf(p) ?? 'n/a' }}</td>
            <td data-testid="concept-anchorage">{{ anchorageOf(p) }}</td>
            <td data-testid="concept-breadth">{{ breadthOf(p) }}</td>
            <td data-testid="concept-home-group">{{ p.groupName ?? 'n/a' }}</td>
            <td data-testid="concept-anchor-strength">{{ anchorStrengthOf(p) ?? 'n/a' }}</td>
            <td data-testid="concept-group-rank">{{ groupRankOf(p) ?? 'n/a' }}</td>
            <td data-testid="concept-rung">{{ rungs().get(p.id) ?? 'n/a' }}</td>
            <td data-testid="concept-depth">{{ depthOf(p) ?? 'n/a' }}</td>
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
              <form (submit)="$event.preventDefault(); setZeta(p, zetaInput.value)">
                <label class="visually-hidden" [for]="'person-zeta-edit-' + p.id">
                  Zeta for {{ p.name }}
                </label>
                <input
                  #zetaInput
                  [id]="'person-zeta-edit-' + p.id"
                  type="number"
                  step="1"
                  [value]="p.zeta ?? ''"
                  data-testid="person-zeta-input"
                />
                <button type="submit" data-testid="person-zeta-save">Save</button>
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
              <label class="visually-hidden" [for]="'person-kind-edit-' + p.id">
                Kind for {{ p.name }}
              </label>
              <select
                [id]="'person-kind-edit-' + p.id"
                (change)="setKind(p, kindSelect.value)"
                #kindSelect
                data-testid="person-kind-select"
              >
                <option value="" [selected]="p.kind === null">none</option>
                @for (k of kinds; track k) {
                  <option [value]="k" [selected]="p.kind === k">{{ k }}</option>
                }
              </select>
            </td>
            <td>
              <label class="visually-hidden" [for]="'person-group-edit-' + p.id">
                Group for {{ p.name }}
              </label>
              <select
                [id]="'person-group-edit-' + p.id"
                (change)="setGroup(p, groupSelect.value)"
                #groupSelect
                data-testid="person-group-select"
              >
                <option value="" [selected]="p.groupId === null">none</option>
                @for (g of groups(); track g.id) {
                  <option [value]="g.id" [selected]="p.groupId === g.id">{{ g.name }}</option>
                }
              </select>
            </td>
            <td>
              <fieldset [attr.data-testid]="'person-groups-' + p.id">
                <legend class="visually-hidden">Groups for {{ p.name }}</legend>
                @for (g of groups(); track g.id) {
                  <label>
                    <input
                      type="checkbox"
                      [checked]="isInGroup(p, g.id)"
                      (change)="toggleGroup(p, g.id, groupBox.checked)"
                      #groupBox
                      [attr.data-testid]="'person-group-member-' + g.id"
                    />
                    {{ g.name }}
                  </label>
                }
              </fieldset>
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
  protected readonly kinds = KINDS;
  protected readonly people = signal<Person[]>([]);
  protected readonly groups = signal<Group[]>([]);
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
  protected readonly strayCount = computed(() => this.people().filter((p) => p.stray).length);
  protected readonly totalEffective = computed(() =>
    this.people().reduce((total, p) => total + (p.effective ?? 0), 0),
  );
  protected readonly totalHaul = computed(() =>
    this.people().reduce((total, p) => total + (p.haul ?? 0), 0),
  );
  protected readonly totalPivot = computed(() =>
    this.people().reduce((total, p) => total + (p.pivot ?? 0), 0),
  );
  protected readonly totalGust = computed(() =>
    this.people().reduce((total, p) => total + (p.gust ?? 0), 0),
  );
  protected readonly totalBrace = computed(() =>
    this.people().reduce((total, p) => total + (p.brace ?? 0), 0),
  );
  protected readonly totalYield = computed(() =>
    this.people().reduce((total, p) => total + (p.yield ?? 0), 0),
  );
  protected readonly totalTide = computed(() =>
    this.people().reduce((total, p) => total + (p.tide ?? 0), 0),
  );
  protected readonly grandFlow = computed(() =>
    this.people().reduce((total, p) => total + (p.flow ?? 0), 0),
  );
  protected readonly finalTotal = computed(() =>
    this.people()
      .filter((p) => p.state === 'FINAL')
      .reduce((total, p) => total + (p.scaled ?? 0), 0),
  );
  protected readonly groupStrengths = computed(() => {
    const strengths = new Map<number, number>();
    for (const p of this.people()) {
      for (const g of p.groups) strengths.set(g.id, (strengths.get(g.id) ?? 0) + (p.effective ?? 0));
    }
    return strengths;
  });
  protected readonly totalStrength = computed(() => {
    const strengths = this.groupStrengths();
    return this.groups().reduce((total, g) => total + (strengths.get(g.id) ?? 0), 0);
  });
  protected readonly groupCount = computed(() => this.groups().length);
  protected readonly medianStrength = computed(() => {
    const strengths = this.groupStrengths();
    const sorted = this.groups()
      .map((g) => strengths.get(g.id) ?? 0)
      .sort((a, b) => a - b);
    return sorted.length === 0 ? null : sorted[Math.floor((sorted.length - 1) / 2)];
  });
  protected readonly strengthRange = computed(() => {
    const strengths = this.groupStrengths();
    const values = this.groups().map((g) => strengths.get(g.id) ?? 0);
    return values.length === 0 ? null : Math.max(...values) - Math.min(...values);
  });
  protected readonly groupRanks = computed(() => {
    const strengths = this.groupStrengths();
    const ranked = [...this.groups()].sort(
      (a, b) => (strengths.get(b.id) ?? 0) - (strengths.get(a.id) ?? 0) || a.name.localeCompare(b.name),
    );
    return new Map(ranked.map((g, i) => [g.id, i + 1]));
  });
  private readonly topGroup = computed(() => {
    const strengths = this.groupStrengths();
    let top: { group: Group; strength: number } | null = null;
    for (const g of this.groups()) {
      const strength = strengths.get(g.id) ?? 0;
      if (top === null || strength > top.strength || (strength === top.strength && g.name.localeCompare(top.group.name) < 0)) {
        top = { group: g, strength };
      }
    }
    return top?.group ?? null;
  });
  protected readonly biggestGroup = computed(() => this.topGroup()?.name ?? null);
  protected readonly topGroupSize = computed(() => {
    const top = this.topGroup();
    return top === null ? null : (this.groupSizes().get(top.id) ?? 0);
  });
  protected shareOf(p: Person): number | null {
    const strength = p.groupId === null ? 0 : (this.groupStrengths().get(p.groupId) ?? 0);
    return p.scaled === null || strength === 0 ? null : Math.round((p.scaled * 100) / strength);
  }
  protected anchorStrengthOf(p: Person): number | null {
    return p.groupId === null ? null : (this.groupStrengths().get(p.groupId) ?? 0);
  }
  protected groupRankOf(p: Person): number | null {
    return p.groupId === null ? null : (this.groupRanks().get(p.groupId) ?? null);
  }
  protected belongingOf(p: Person): number | null {
    if (p.groupId === null || p.scaled === null) return null;
    return (this.groupStrengths().get(p.groupId) ?? 0) - p.scaled;
  }
  protected tetherOf(p: Person): number | null {
    if (p.groupId === null || p.scaled === null) return null;
    const cohesion = this.groupCohesions().get(p.groupId);
    return cohesion === undefined ? null : p.scaled - cohesion;
  }
  protected slackOf(p: Person): number | null {
    if (p.groupId === null || p.scaled === null) return null;
    const cohesion = this.groupCohesions().get(p.groupId);
    return cohesion === undefined ? null : cohesion - p.scaled;
  }
  protected weaveOf(p: Person): number | null {
    if (p.groupId === null || p.scaled === null) return null;
    const cohesion = this.groupCohesions().get(p.groupId);
    return cohesion === undefined ? null : p.scaled + cohesion;
  }
  protected upliftOf(p: Person): number | null {
    const weave = this.weaveOf(p);
    const tether = this.tetherOf(p);
    return weave === null || tether === null ? null : weave - tether;
  }
  protected meshOf(p: Person): number | null {
    if (p.groupId === null || p.scaled === null) return null;
    return p.scaled * (this.groupSizes().get(p.groupId) ?? 0);
  }
  protected depthOf(p: Person): number | null {
    const standing = this.standings().get(p.id);
    if (standing === undefined || p.groupId === null) return null;
    return standing - 1;
  }
  protected anchorageOf(p: Person): number {
    return p.groups.length;
  }
  protected breadthOf(p: Person): number {
    return p.groups.length;
  }
  protected groupNamesOf(p: Person): string {
    return p.groups.map((g) => g.name).join(', ');
  }
  protected isInGroup(p: Person, groupId: number): boolean {
    return p.groups.some((g) => g.id === groupId);
  }
  protected readonly groupSizes = computed(() => {
    const sizes = new Map<number, number>();
    for (const p of this.people()) {
      for (const g of p.groups) sizes.set(g.id, (sizes.get(g.id) ?? 0) + 1);
    }
    return sizes;
  });
  protected readonly groupVigors = computed(() => {
    const strengths = this.groupStrengths();
    const vigors = new Map<number, number>();
    for (const [groupId, size] of this.groupSizes()) {
      vigors.set(groupId, (strengths.get(groupId) ?? 0) + size);
    }
    return vigors;
  });
  protected readonly groupCohesions = computed(() => {
    const strengths = this.groupStrengths();
    const cohesions = new Map<number, number>();
    for (const [groupId, size] of this.groupSizes()) {
      cohesions.set(groupId, Math.floor((strengths.get(groupId) ?? 0) / size));
    }
    return cohesions;
  });
  protected readonly groupDensities = computed(() => {
    const cohesions = this.groupCohesions();
    const densities = new Map<number, number>();
    for (const p of this.people()) {
      if (p.effective === null) continue;
      for (const g of p.groups) {
        const cohesion = cohesions.get(g.id);
        if (cohesion !== undefined && p.effective >= cohesion) densities.set(g.id, (densities.get(g.id) ?? 0) + 1);
      }
    }
    return densities;
  });
  protected readonly groupSpreads = computed(() => {
    const ranges = new Map<number, { min: number; max: number }>();
    for (const p of this.people()) {
      if (p.effective === null) continue;
      for (const g of p.groups) {
        const range = ranges.get(g.id);
        ranges.set(g.id, {
          min: Math.min(range?.min ?? p.effective, p.effective),
          max: Math.max(range?.max ?? p.effective, p.effective),
        });
      }
    }
    const spreads = new Map<number, number>();
    for (const [groupId, { min, max }] of ranges) spreads.set(groupId, max - min);
    return spreads;
  });
  protected readonly groupApexes = computed(() => {
    const apexes = new Map<number, { name: string; scaled: number }>();
    for (const p of this.people()) {
      if (p.scaled === null) continue;
      for (const g of p.groups) {
        const apex = apexes.get(g.id);
        if (!apex || p.scaled > apex.scaled || (p.scaled === apex.scaled && p.name.localeCompare(apex.name) < 0)) {
          apexes.set(g.id, { name: p.name, scaled: p.scaled });
        }
      }
    }
    return apexes;
  });
  protected readonly groupMins = computed(() => {
    const mins = new Map<number, number>();
    for (const p of this.people()) {
      if (p.effective === null) continue;
      for (const g of p.groups) mins.set(g.id, Math.min(mins.get(g.id) ?? p.effective, p.effective));
    }
    return mins;
  });
  protected readonly groupMaxes = computed(() => {
    const maxes = new Map<number, number>();
    for (const p of this.people()) {
      if (p.scaled === null) continue;
      for (const g of p.groups) maxes.set(g.id, Math.max(maxes.get(g.id) ?? p.scaled, p.scaled));
    }
    return maxes;
  });
  protected readonly groupHeadrooms = computed(() => {
    const lows = new Map<number, number>();
    for (const p of this.people()) {
      if (p.scaled === null) continue;
      for (const g of p.groups) lows.set(g.id, Math.min(lows.get(g.id) ?? p.scaled, p.scaled));
    }
    const headrooms = new Map<number, number>();
    for (const [groupId, max] of this.groupMaxes()) headrooms.set(groupId, max - lows.get(groupId)!);
    return headrooms;
  });
  protected readonly standings = computed(() => {
    const ranked = this.people()
      .filter((p) => p.scaled !== null)
      .sort(
        (a, b) =>
          b.scaled! - a.scaled! ||
          (b.alpha ?? -Infinity) - (a.alpha ?? -Infinity) ||
          a.name.localeCompare(b.name),
      );
    const places = new Map<number, number>();
    const standings = new Map<number, number>();
    // Places are counted among everyone in a group; a person's standing is their place in their group.
    for (const p of ranked) {
      for (const g of p.groups) {
        const place = (places.get(g.id) ?? 0) + 1;
        places.set(g.id, place);
        if (g.id === p.groupId) standings.set(p.id, place);
      }
    }
    return standings;
  });
  protected readonly rungs = computed(() => {
    const standings = this.standings();
    const sizes = new Map<number, number>();
    for (const p of this.people()) {
      if (p.scaled === null) continue;
      for (const g of p.groups) sizes.set(g.id, (sizes.get(g.id) ?? 0) + 1);
    }
    const rungs = new Map<number, number>();
    for (const p of this.people()) {
      const standing = standings.get(p.id);
      if (standing === undefined || p.groupId === null) continue;
      const size = sizes.get(p.groupId) ?? 1;
      rungs.set(p.id, size <= 1 ? 100 : Math.round(((size - standing) * 100) / size));
    }
    return rungs;
  });
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
  protected readonly dragCounts = computed(() =>
    DRAGS.map((drag) => ({ drag, count: this.people().filter((p) => p.drag === drag).length })),
  );
  protected readonly pulseCounts = computed(() =>
    PULSES.map((pulse) => ({ pulse, count: this.people().filter((p) => p.pulse === pulse).length })),
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
  protected readonly topCrest = computed(() => {
    let top: Person | null = null;
    for (const p of this.people()) {
      if (p.crest === null) continue;
      if (
        top === null ||
        p.crest > top.crest! ||
        (p.crest === top.crest && p.name.localeCompare(top.name) < 0)
      ) {
        top = p;
      }
    }
    return top?.name ?? null;
  });
  protected readonly strongestMember = computed(() => {
    let top: Person | null = null;
    for (const p of this.people()) {
      if (p.scaled === null) continue;
      if (
        top === null ||
        p.scaled > top.scaled! ||
        (p.scaled === top.scaled && p.name.localeCompare(top.name) < 0)
      ) {
        top = p;
      }
    }
    return top?.name ?? null;
  });
  protected readonly topBrace = computed(() => {
    let top: Person | null = null;
    for (const p of this.people()) {
      if (p.brace === null) continue;
      if (
        top === null ||
        p.brace > top.brace! ||
        (p.brace === top.brace && p.name.localeCompare(top.name) < 0)
      ) {
        top = p;
      }
    }
    return top?.name ?? null;
  });
  protected readonly topFlow = computed(() => {
    let top: Person | null = null;
    for (const p of this.people()) {
      if (p.flow === null) continue;
      if (
        top === null ||
        p.flow > top.flow! ||
        (p.flow === top.flow && p.name.localeCompare(top.name) < 0)
      ) {
        top = p;
      }
    }
    return top?.name ?? null;
  });

  protected readonly topCharge = computed(() => {
    let top: Person | null = null;
    for (const p of this.people()) {
      if (p.charge === null) continue;
      if (
        top === null ||
        p.charge > top.charge! ||
        (p.charge === top.charge && p.name.localeCompare(top.name) < 0)
      ) {
        top = p;
      }
    }
    return top?.name ?? null;
  });
  protected readonly maxThrum = computed(() => {
    let top: Person | null = null;
    for (const p of this.people()) {
      if (p.thrum === null) continue;
      if (
        top === null ||
        p.thrum > top.thrum! ||
        (p.thrum === top.thrum && p.name.localeCompare(top.name) < 0)
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
    zeta: [null as number | null, [Validators.pattern(/^-?\d+$/)]],
    grade: [null as Grade | null],
    kind: [null as Kind | null],
    region: [''],
    groupId: [null as number | null],
  });

  protected readonly groupForm = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.pattern(/\S/)]],
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
    this.form.reset({ name: person.name, alpha: person.alpha, beta: person.beta, gamma: person.gamma, delta: person.delta, epsilon: person.epsilon, zeta: person.zeta, grade: person.grade, kind: person.kind, region: person.regions.join(', '), groupId: person.groupId });
    this.form.controls.name.disable();
  }

  protected cancelEdit(): void {
    this.editing.set(null);
    this.form.controls.name.enable();
    this.form.reset();
  }

  protected submit(): void {
    const { name, alpha, beta, gamma, delta, epsilon, zeta, grade, kind, region: regionText, groupId } = this.form.getRawValue();
    const regions = toRegions(regionText);
    if (this.form.invalid) return;
    const editing = this.editing();
    const request = editing
      ? this.service.update(this.sheet(), editing.id, { alpha: toNumber(alpha), beta: toNumber(beta), gamma: toNumber(gamma), delta: toNumber(delta), epsilon: toNumber(epsilon), zeta: toNumber(zeta), grade, kind, regions,
          // Only a changed group replaces the person's groups, so saving keeps every group they are in.
          ...(groupId === editing.groupId ? {} : { groupId }) })
      : this.service.add(this.sheet(), { name, alpha, beta, gamma, delta, epsilon, zeta, grade, kind, regions, groupId });
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

  protected setZeta(person: Person, value: string): void {
    const text = value.trim();
    if (text !== '' && !/^-?\d+$/.test(text)) return;
    const zeta = text === '' ? null : Number(text);
    this.service.setZeta(this.sheet(), person.id, zeta).subscribe(() => this.load());
  }

  protected setGrade(person: Person, value: string): void {
    const grade = GRADES.find((g) => g === value) ?? null;
    this.service.setGrade(this.sheet(), person.id, grade).subscribe(() => this.load());
  }

  protected setKind(person: Person, value: string): void {
    const kind = KINDS.find((k) => k === value) ?? null;
    this.service.setKind(this.sheet(), person.id, kind).subscribe(() => this.load());
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

  protected addGroup(): void {
    if (this.groupForm.invalid) return;
    const name = this.groupForm.getRawValue().name.trim();
    this.service.addGroup(this.sheet(), name).subscribe(() => {
      this.groupForm.reset();
      this.load();
    });
  }

  protected setGroup(person: Person, value: string): void {
    const groupId = value === '' ? null : Number(value);
    this.service.setGroup(this.sheet(), person.id, groupId).subscribe(() => this.load());
  }

  protected toggleGroup(person: Person, groupId: number, member: boolean): void {
    const others = person.groups.map((g) => g.id).filter((id) => id !== groupId);
    const groupIds = member ? [...others, groupId] : others;
    this.service.setGroups(this.sheet(), person.id, groupIds).subscribe(() => this.load());
  }

  private load(): void {
    const sheet = this.sheet();
    this.service.sheet(sheet).subscribe((s) => {
      if (this.sheet() === sheet) this.anchor.set(s.anchor);
    });
    this.service.groups(sheet).subscribe((groups) => {
      if (this.sheet() === sheet) this.groups.set(groups);
    });
    this.service.list(sheet).subscribe((people) => {
      if (this.sheet() === sheet) this.people.set(people);
    });
  }
}

// The bands, best first.
const BANDS = ['A', 'B', 'C', 'D', 'E'] as const;

// The drag levels, lowest first.
const DRAGS = ['L0', 'L1', 'L2', 'L3'] as const;
const PULSES = ['NEG', 'FLAT', 'POS'] as const;

// A number input's value as a whole number, or null when it is empty.
function toNumber(value: number | string | null): number | null {
  return value === null || value === '' ? null : Number(value);
}

// Typed regions, separated by commas, each trimmed, leaving out blanks and repeats.
function toRegions(value: string): string[] {
  return [...new Set(value.split(',').map((r) => r.trim()).filter((r) => r !== ''))];
}
