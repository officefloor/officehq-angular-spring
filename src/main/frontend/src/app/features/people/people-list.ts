import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Person, PeopleService } from './people.service';

// Every person on the sheet, one row each.
@Component({
  selector: 'app-people-list',
  template: `
    @if (people(); as list) {
      @if (list.length === 0) {
        <p data-testid="home-empty">Nothing here yet.</p>
      } @else {
        <table data-testid="people-table">
          <caption>People</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Alpha</th>
              <th scope="col">Beta</th>
              <th scope="col">Gamma</th>
              <th scope="col">On hold</th>
              <th scope="col">Patrons</th>
              <th scope="col">Thrust</th>
              <th scope="col">Momentum</th>
              <th scope="col">Scaled</th>
              <th scope="col">Third</th>
              <th scope="col">Norm</th>
              <th scope="col">Backing</th>
              <th scope="col">Quarter</th>
              <th scope="col">Tier</th>
              <th scope="col">Keel</th>
              <th scope="col">Fathom</th>
              <th scope="col">Draft</th>
              <th scope="col">Cascade</th>
              <th scope="col">Trim</th>
              <th scope="col">Ripple</th>
              <th scope="col">Heading</th>
              <th scope="col">Ballast</th>
              <th scope="col">Keystone</th>
              <th scope="col">Bearing</th>
              <th scope="col">Pitch</th>
              <th scope="col">Tackle</th>
              <th scope="col">Yaw</th>
              <th scope="col">Heave</th>
              <th scope="col">Camber</th>
              <th scope="col">Sheer</th>
              <th scope="col">Beam</th>
              <th scope="col">Freeboard</th>
              <th scope="col">Buoyancy</th>
              <th scope="col">Knot</th>
              <th scope="col">Cable</th>
              <th scope="col">Hull</th>
              <th scope="col">Bow</th>
              <th scope="col">Stern</th>
              <th scope="col">Helm</th>
              <th scope="col">Rudder</th>
              <th scope="col">Mast</th>
              <th scope="col">Rigging</th>
              <th scope="col">Moor</th>
              <th scope="col">Berth</th>
              <th scope="col">Wharf</th>
              <th scope="col">Jetty</th>
              <th scope="col">Pier</th>
              <th scope="col">Ebb</th>
              <th scope="col">Flood</th>
              <th scope="col">Swell</th>
            </tr>
          </thead>
          <tbody>
            @for (p of list; track p.id) {
              <tr [attr.data-testid]="'person-row-' + p.id">
                <th scope="row" [attr.data-testid]="'person-name-' + p.id">{{ p.name }}</th>
                <td [attr.data-testid]="'person-alpha-' + p.id">{{ p.alpha ?? '' }}</td>
                <td [attr.data-testid]="'person-beta-' + p.id">{{ p.beta ?? '' }}</td>
                <td [attr.data-testid]="'person-gamma-' + p.id">{{ p.gamma ?? '' }}</td>
                <td [attr.data-testid]="'person-hold-' + p.id">{{ p.hold ? 'Yes' : 'No' }}</td>
                <td [attr.data-testid]="'person-patrons-' + p.id">{{ patronNames(p.patronIds) }}</td>
                <td data-testid="concept-thrust">{{ p.thrust ?? 'n/a' }}</td>
                <td data-testid="concept-momentum">{{ p.momentum ?? 'n/a' }}</td>
                <td data-testid="concept-scaled">{{ p.scaled ?? 'n/a' }}</td>
                <td data-testid="concept-third">{{ p.third ?? 'n/a' }}</td>
                <td data-testid="concept-norm">{{ p.norm ?? 'n/a' }}</td>
                <td data-testid="concept-backing">{{ p.backing ?? 'n/a' }}</td>
                <td data-testid="concept-quarter">{{ p.quarter ?? 'n/a' }}</td>
                <td data-testid="concept-tier">{{ p.tier ?? 'n/a' }}</td>
                <td data-testid="concept-keel">{{ p.keel ?? 'n/a' }}</td>
                <td data-testid="concept-fathom">{{ p.fathom ?? 'n/a' }}</td>
                <td data-testid="concept-draft">{{ p.draft ?? 'n/a' }}</td>
                <td data-testid="concept-cascade">{{ p.cascade ?? 'n/a' }}</td>
                <td data-testid="concept-trim">{{ p.trim ?? 'n/a' }}</td>
                <td data-testid="concept-ripple">{{ p.ripple ?? 'n/a' }}</td>
                <td data-testid="concept-heading">{{ p.heading ?? 'n/a' }}</td>
                <td data-testid="concept-ballast">{{ p.ballast ?? 'n/a' }}</td>
                <td data-testid="concept-keystone">{{ p.keystone ?? 'n/a' }}</td>
                <td data-testid="concept-bearing">{{ p.bearing ?? 'n/a' }}</td>
                <td data-testid="concept-pitch">{{ p.pitch ?? 'n/a' }}</td>
                <td data-testid="concept-tackle">{{ p.tackle ?? 'n/a' }}</td>
                <td data-testid="concept-yaw">{{ p.yaw ?? 'n/a' }}</td>
                <td data-testid="concept-heave">{{ p.heave ?? 'n/a' }}</td>
                <td data-testid="concept-camber">{{ p.camber ?? 'n/a' }}</td>
                <td data-testid="concept-sheer">{{ p.sheer ?? 'n/a' }}</td>
                <td data-testid="concept-beam">{{ p.beam ?? 'n/a' }}</td>
                <td data-testid="concept-freeboard">{{ p.freeboard ?? 'n/a' }}</td>
                <td data-testid="concept-buoyancy">{{ p.buoyancy ?? 'n/a' }}</td>
                <td data-testid="concept-knot">{{ p.knot ?? 'n/a' }}</td>
                <td data-testid="concept-cable">{{ p.cable ?? 'n/a' }}</td>
                <td data-testid="concept-hull">{{ p.hull ?? 'n/a' }}</td>
                <td data-testid="concept-bow">{{ p.bow ?? 'n/a' }}</td>
                <td data-testid="concept-stern">{{ p.stern ?? 'n/a' }}</td>
                <td data-testid="concept-helm">{{ p.helm ?? 'n/a' }}</td>
                <td data-testid="concept-rudder">{{ p.rudder ?? 'n/a' }}</td>
                <td data-testid="concept-mast">{{ p.mast ?? 'n/a' }}</td>
                <td data-testid="concept-rigging">{{ p.rigging ?? 'n/a' }}</td>
                <td data-testid="concept-moor">{{ p.moor ?? 'n/a' }}</td>
                <td data-testid="concept-berth">{{ p.berth ?? 'n/a' }}</td>
                <td data-testid="concept-wharf">{{ p.wharf ?? 'n/a' }}</td>
                <td data-testid="concept-jetty">{{ p.jetty ?? 'n/a' }}</td>
                <td data-testid="concept-pier">{{ p.pier ?? 'n/a' }}</td>
                <td data-testid="concept-ebb">{{ p.ebb ?? 'n/a' }}</td>
                <td data-testid="concept-flood">{{ p.flood ?? 'n/a' }}</td>
                <td data-testid="concept-swell">{{ p.swell ?? 'n/a' }}</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="6">Thrust total</th>
              <td data-testid="rollup-thrust-total">{{ thrustTotal() }}</td>
              <td colspan="43"></td>
            </tr>
            <tr>
              <th scope="row" colspan="14">Keel total</th>
              <td data-testid="rollup-keel-total">{{ keelTotal() }}</td>
              <td colspan="35"></td>
            </tr>
            <tr>
              <th scope="row" colspan="14">Keel count</th>
              <td data-testid="rollup-keel-count">{{ keelCount() }}</td>
              <td colspan="35"></td>
            </tr>
            <tr>
              <th scope="row" colspan="15">Fathom total</th>
              <td data-testid="rollup-fathom-total">{{ fathomTotal() }}</td>
              <td colspan="34"></td>
            </tr>
            <tr>
              <th scope="row" colspan="16">Draft total</th>
              <td data-testid="rollup-draft-total">{{ draftTotal() }}</td>
              <td colspan="33"></td>
            </tr>
            <tr>
              <th scope="row" colspan="17">Cascade total</th>
              <td data-testid="rollup-cascade-total">{{ cascadeTotal() }}</td>
              <td colspan="32"></td>
            </tr>
          </tfoot>
        </table>
      }
    }
  `,
})
export class PeopleList {
  protected readonly people = toSignal(inject(PeopleService).list());

  private readonly names = computed(
    () => new Map((this.people() ?? []).map((p) => [p.id, p.name] as const)),
  );

  // Everyone's thrust added up, skipping the people for whom it is n/a.
  protected readonly thrustTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.thrust, hold: p.hold }))),
  );

  // Everyone's keel added up, trimmed like every sheet total.
  protected readonly keelTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.keel, hold: p.hold }))),
  );

  // Everyone's fathom added up, trimmed like every sheet total.
  protected readonly fathomTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.fathom, hold: p.hold }))),
  );

  // Everyone's draft added up, trimmed like every sheet total.
  protected readonly draftTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.draft, hold: p.hold }))),
  );

  // Everyone's cascade added up, trimmed like every sheet total.
  protected readonly cascadeTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.cascade, hold: p.hold }))),
  );

  // How many counted people have an even keel (n/a keels are not counted).
  protected readonly keelCount = computed(
    () => countable(this.people() ?? []).filter((p) => p.keel !== null && p.keel % 2 === 0).length,
  );

  protected patronNames(ids: number[]): string {
    const names = this.names();
    return ids.map((id) => names.get(id) ?? String(id)).join(', ');
  }
}

// The people every sheet count is taken over: everyone except the people on hold and anyone whose
// keel is below zero. Every sheet count goes through this.
export function countable(people: Person[]): Person[] {
  return people.filter((p) => !p.hold && !(p.keel !== null && p.keel < 0));
}

// A sheet-wide total: the contributions (n/a ones skipped) added up, leaving out one highest and
// one lowest whenever at least two people contribute. A person on hold contributes half their
// value (rounded like every divide), and that half takes part in leaving out the highest and
// lowest. Every sheet total goes through this.
export function trimmedTotal(values: { value: number | null; hold: boolean }[]): number {
  const contributions = values
    .filter((v): v is { value: number; hold: boolean } => v.value !== null)
    .map((v) => (v.hold ? divide(v.value, 2) : v.value));
  const sum = contributions.reduce((total, v) => total + v, 0);
  if (contributions.length < 2) {
    return sum;
  }
  return sum - Math.max(...contributions) - Math.min(...contributions);
}

// Every divide on the sheet: rounded to the nearest whole number, a half rounding up to the larger
// number (5 / 2 is 3, -5 / 2 is -2), as the server's SheetMath.divide.
export function divide(dividend: number, divisor: number): number {
  if (divisor < 0) {
    dividend = -dividend;
    divisor = -divisor;
  }
  return Math.floor((2 * dividend + divisor) / (2 * divisor));
}
