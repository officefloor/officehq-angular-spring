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
              <th scope="row" colspan="15">Fathom low</th>
              <td data-testid="rollup-fathom-low">{{ fathomLow() ?? 'n/a' }}</td>
              <td colspan="34"></td>
            </tr>
            <tr>
              <th scope="row" colspan="15">Fathom top</th>
              <td data-testid="rollup-fathom-top">{{ fathomTop() ?? 'n/a' }}</td>
              <td colspan="34"></td>
            </tr>
            <tr>
              <th scope="row" colspan="15">Fathom spread</th>
              <td data-testid="rollup-fathom-spread">{{ fathomSpread() ?? 'n/a' }}</td>
              <td colspan="34"></td>
            </tr>
            <tr>
              <th scope="row" colspan="15">Fathom even</th>
              <td data-testid="rollup-fathom-even">{{ fathomEven() }}</td>
              <td colspan="34"></td>
            </tr>
            <tr>
              <th scope="row" colspan="15">Fathom mean</th>
              <td data-testid="rollup-fathom-mean">{{ fathomMean() ?? 'n/a' }}</td>
              <td colspan="34"></td>
            </tr>
            <tr>
              <th scope="row" colspan="16">Draft total</th>
              <td data-testid="rollup-draft-total">{{ draftTotal() }}</td>
              <td colspan="33"></td>
            </tr>
            <tr>
              <th scope="row" colspan="16">Draft low</th>
              <td data-testid="rollup-draft-low">{{ draftLow() ?? 'n/a' }}</td>
              <td colspan="33"></td>
            </tr>
            <tr>
              <th scope="row" colspan="16">Draft top</th>
              <td data-testid="rollup-draft-top">{{ draftTop() ?? 'n/a' }}</td>
              <td colspan="33"></td>
            </tr>
            <tr>
              <th scope="row" colspan="16">Draft spread</th>
              <td data-testid="rollup-draft-spread">{{ draftSpread() ?? 'n/a' }}</td>
              <td colspan="33"></td>
            </tr>
            <tr>
              <th scope="row" colspan="16">Draft even</th>
              <td data-testid="rollup-draft-even">{{ draftEven() }}</td>
              <td colspan="33"></td>
            </tr>
            <tr>
              <th scope="row" colspan="16">Draft mean</th>
              <td data-testid="rollup-draft-mean">{{ draftMean() ?? 'n/a' }}</td>
              <td colspan="33"></td>
            </tr>
            <tr>
              <th scope="row" colspan="17">Cascade total</th>
              <td data-testid="rollup-cascade-total">{{ cascadeTotal() }}</td>
              <td colspan="32"></td>
            </tr>
            <tr>
              <th scope="row" colspan="17">Cascade low</th>
              <td data-testid="rollup-cascade-low">{{ cascadeLow() ?? 'n/a' }}</td>
              <td colspan="32"></td>
            </tr>
            <tr>
              <th scope="row" colspan="17">Cascade top</th>
              <td data-testid="rollup-cascade-top">{{ cascadeTop() ?? 'n/a' }}</td>
              <td colspan="32"></td>
            </tr>
            <tr>
              <th scope="row" colspan="17">Cascade spread</th>
              <td data-testid="rollup-cascade-spread">{{ cascadeSpread() ?? 'n/a' }}</td>
              <td colspan="32"></td>
            </tr>
            <tr>
              <th scope="row" colspan="17">Cascade even</th>
              <td data-testid="rollup-cascade-even">{{ cascadeEven() }}</td>
              <td colspan="32"></td>
            </tr>
            <tr>
              <th scope="row" colspan="17">Cascade mean</th>
              <td data-testid="rollup-cascade-mean">{{ cascadeMean() ?? 'n/a' }}</td>
              <td colspan="32"></td>
            </tr>
            <tr>
              <th scope="row" colspan="18">Trim total</th>
              <td data-testid="rollup-trim-total">{{ trimTotal() }}</td>
              <td colspan="31"></td>
            </tr>
            <tr>
              <th scope="row" colspan="18">Trim top</th>
              <td data-testid="rollup-trim-top">{{ trimTop() ?? 'n/a' }}</td>
              <td colspan="31"></td>
            </tr>
            <tr>
              <th scope="row" colspan="18">Trim spread</th>
              <td data-testid="rollup-trim-spread">{{ trimSpread() ?? 'n/a' }}</td>
              <td colspan="31"></td>
            </tr>
            <tr>
              <th scope="row" colspan="18">Trim even</th>
              <td data-testid="rollup-trim-even">{{ trimEven() }}</td>
              <td colspan="31"></td>
            </tr>
            <tr>
              <th scope="row" colspan="18">Trim mean</th>
              <td data-testid="rollup-trim-mean">{{ trimMean() ?? 'n/a' }}</td>
              <td colspan="31"></td>
            </tr>
            <tr>
              <th scope="row" colspan="19">Ripple total</th>
              <td data-testid="rollup-ripple-total">{{ rippleTotal() }}</td>
              <td colspan="30"></td>
            </tr>
            <tr>
              <th scope="row" colspan="19">Ripple low</th>
              <td data-testid="rollup-ripple-low">{{ rippleLow() ?? 'n/a' }}</td>
              <td colspan="30"></td>
            </tr>
            <tr>
              <th scope="row" colspan="19">Ripple top</th>
              <td data-testid="rollup-ripple-top">{{ rippleTop() ?? 'n/a' }}</td>
              <td colspan="30"></td>
            </tr>
            <tr>
              <th scope="row" colspan="19">Ripple spread</th>
              <td data-testid="rollup-ripple-spread">{{ rippleSpread() ?? 'n/a' }}</td>
              <td colspan="30"></td>
            </tr>
            <tr>
              <th scope="row" colspan="19">Ripple even</th>
              <td data-testid="rollup-ripple-even">{{ rippleEven() }}</td>
              <td colspan="30"></td>
            </tr>
            <tr>
              <th scope="row" colspan="19">Ripple mean</th>
              <td data-testid="rollup-ripple-mean">{{ rippleMean() ?? 'n/a' }}</td>
              <td colspan="30"></td>
            </tr>
            <tr>
              <th scope="row" colspan="20">Heading total</th>
              <td data-testid="rollup-heading-total">{{ headingTotal() }}</td>
              <td colspan="29"></td>
            </tr>
            <tr>
              <th scope="row" colspan="20">Heading top</th>
              <td data-testid="rollup-heading-top">{{ headingTop() ?? 'n/a' }}</td>
              <td colspan="29"></td>
            </tr>
            <tr>
              <th scope="row" colspan="20">Heading spread</th>
              <td data-testid="rollup-heading-spread">{{ headingSpread() ?? 'n/a' }}</td>
              <td colspan="29"></td>
            </tr>
            <tr>
              <th scope="row" colspan="20">Heading even</th>
              <td data-testid="rollup-heading-even">{{ headingEven() }}</td>
              <td colspan="29"></td>
            </tr>
            <tr>
              <th scope="row" colspan="20">Heading mean</th>
              <td data-testid="rollup-heading-mean">{{ headingMean() ?? 'n/a' }}</td>
              <td colspan="29"></td>
            </tr>
            <tr>
              <th scope="row" colspan="21">Ballast total</th>
              <td data-testid="rollup-ballast-total">{{ ballastTotal() }}</td>
              <td colspan="28"></td>
            </tr>
            <tr>
              <th scope="row" colspan="21">Ballast low</th>
              <td data-testid="rollup-ballast-low">{{ ballastLow() ?? 'n/a' }}</td>
              <td colspan="28"></td>
            </tr>
            <tr>
              <th scope="row" colspan="21">Ballast top</th>
              <td data-testid="rollup-ballast-top">{{ ballastTop() ?? 'n/a' }}</td>
              <td colspan="28"></td>
            </tr>
            <tr>
              <th scope="row" colspan="21">Ballast spread</th>
              <td data-testid="rollup-ballast-spread">{{ ballastSpread() ?? 'n/a' }}</td>
              <td colspan="28"></td>
            </tr>
            <tr>
              <th scope="row" colspan="21">Ballast even</th>
              <td data-testid="rollup-ballast-even">{{ ballastEven() }}</td>
              <td colspan="28"></td>
            </tr>
            <tr>
              <th scope="row" colspan="21">Ballast mean</th>
              <td data-testid="rollup-ballast-mean">{{ ballastMean() ?? 'n/a' }}</td>
              <td colspan="28"></td>
            </tr>
            <tr>
              <th scope="row" colspan="22">Keystone total</th>
              <td data-testid="rollup-keystone-total">{{ keystoneTotal() }}</td>
              <td colspan="27"></td>
            </tr>
            <tr>
              <th scope="row" colspan="22">Keystone low</th>
              <td data-testid="rollup-keystone-low">{{ keystoneLow() ?? 'n/a' }}</td>
              <td colspan="27"></td>
            </tr>
            <tr>
              <th scope="row" colspan="22">Keystone top</th>
              <td data-testid="rollup-keystone-top">{{ keystoneTop() ?? 'n/a' }}</td>
              <td colspan="27"></td>
            </tr>
            <tr>
              <th scope="row" colspan="22">Keystone spread</th>
              <td data-testid="rollup-keystone-spread">{{ keystoneSpread() ?? 'n/a' }}</td>
              <td colspan="27"></td>
            </tr>
            <tr>
              <th scope="row" colspan="22">Keystone even</th>
              <td data-testid="rollup-keystone-even">{{ keystoneEven() }}</td>
              <td colspan="27"></td>
            </tr>
            <tr>
              <th scope="row" colspan="22">Keystone mean</th>
              <td data-testid="rollup-keystone-mean">{{ keystoneMean() ?? 'n/a' }}</td>
              <td colspan="27"></td>
            </tr>
            <tr>
              <th scope="row" colspan="23">Bearing total</th>
              <td data-testid="rollup-bearing-total">{{ bearingTotal() }}</td>
              <td colspan="26"></td>
            </tr>
            <tr>
              <th scope="row" colspan="23">Bearing low</th>
              <td data-testid="rollup-bearing-low">{{ bearingLow() ?? 'n/a' }}</td>
              <td colspan="26"></td>
            </tr>
            <tr>
              <th scope="row" colspan="23">Bearing top</th>
              <td data-testid="rollup-bearing-top">{{ bearingTop() ?? 'n/a' }}</td>
              <td colspan="26"></td>
            </tr>
            <tr>
              <th scope="row" colspan="23">Bearing spread</th>
              <td data-testid="rollup-bearing-spread">{{ bearingSpread() ?? 'n/a' }}</td>
              <td colspan="26"></td>
            </tr>
            <tr>
              <th scope="row" colspan="23">Bearing even</th>
              <td data-testid="rollup-bearing-even">{{ bearingEven() }}</td>
              <td colspan="26"></td>
            </tr>
            <tr>
              <th scope="row" colspan="23">Bearing mean</th>
              <td data-testid="rollup-bearing-mean">{{ bearingMean() ?? 'n/a' }}</td>
              <td colspan="26"></td>
            </tr>
            <tr>
              <th scope="row" colspan="24">Pitch total</th>
              <td data-testid="rollup-pitch-total">{{ pitchTotal() }}</td>
              <td colspan="25"></td>
            </tr>
            <tr>
              <th scope="row" colspan="24">Pitch top</th>
              <td data-testid="rollup-pitch-top">{{ pitchTop() ?? 'n/a' }}</td>
              <td colspan="25"></td>
            </tr>
            <tr>
              <th scope="row" colspan="24">Pitch spread</th>
              <td data-testid="rollup-pitch-spread">{{ pitchSpread() ?? 'n/a' }}</td>
              <td colspan="25"></td>
            </tr>
            <tr>
              <th scope="row" colspan="24">Pitch even</th>
              <td data-testid="rollup-pitch-even">{{ pitchEven() }}</td>
              <td colspan="25"></td>
            </tr>
            <tr>
              <th scope="row" colspan="24">Pitch mean</th>
              <td data-testid="rollup-pitch-mean">{{ pitchMean() ?? 'n/a' }}</td>
              <td colspan="25"></td>
            </tr>
            <tr>
              <th scope="row" colspan="25">Tackle total</th>
              <td data-testid="rollup-tackle-total">{{ tackleTotal() }}</td>
              <td colspan="24"></td>
            </tr>
            <tr>
              <th scope="row" colspan="25">Tackle top</th>
              <td data-testid="rollup-tackle-top">{{ tackleTop() ?? 'n/a' }}</td>
              <td colspan="24"></td>
            </tr>
            <tr>
              <th scope="row" colspan="25">Tackle spread</th>
              <td data-testid="rollup-tackle-spread">{{ tackleSpread() ?? 'n/a' }}</td>
              <td colspan="24"></td>
            </tr>
            <tr>
              <th scope="row" colspan="25">Tackle even</th>
              <td data-testid="rollup-tackle-even">{{ tackleEven() }}</td>
              <td colspan="24"></td>
            </tr>
            <tr>
              <th scope="row" colspan="25">Tackle mean</th>
              <td data-testid="rollup-tackle-mean">{{ tackleMean() ?? 'n/a' }}</td>
              <td colspan="24"></td>
            </tr>
            <tr>
              <th scope="row" colspan="26">Yaw total</th>
              <td data-testid="rollup-yaw-total">{{ yawTotal() }}</td>
              <td colspan="23"></td>
            </tr>
            <tr>
              <th scope="row" colspan="26">Yaw top</th>
              <td data-testid="rollup-yaw-top">{{ yawTop() ?? 'n/a' }}</td>
              <td colspan="23"></td>
            </tr>
            <tr>
              <th scope="row" colspan="26">Yaw spread</th>
              <td data-testid="rollup-yaw-spread">{{ yawSpread() ?? 'n/a' }}</td>
              <td colspan="23"></td>
            </tr>
            <tr>
              <th scope="row" colspan="26">Yaw even</th>
              <td data-testid="rollup-yaw-even">{{ yawEven() }}</td>
              <td colspan="23"></td>
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

  // The lowest fathom among the people not on hold (n/a fathoms skipped); null (n/a) when nobody has one.
  protected readonly fathomLow = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.fathom)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.min(...values);
  });

  // The highest fathom among the people not on hold (n/a fathoms skipped); null (n/a) when nobody has one.
  protected readonly fathomTop = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.fathom)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values);
  });

  // The highest fathom minus the lowest among the people not on hold; null (n/a) when nobody has one.
  protected readonly fathomSpread = computed(() => {
    const top = this.fathomTop();
    const low = this.fathomLow();
    return top === null || low === null ? null : top - low;
  });

  // The average fathom among the people not on hold (n/a fathoms skipped), rounded like every
  // divide; null (n/a) when nobody has one.
  protected readonly fathomMean = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.fathom)
      .filter((v): v is number => v !== null);
    return values.length === 0
      ? null
      : divide(values.reduce((total, v) => total + v, 0), values.length);
  });

  // Everyone's draft added up, trimmed like every sheet total.
  protected readonly draftTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.draft, hold: p.hold }))),
  );

  // The lowest draft among the people not on hold (n/a drafts skipped); null (n/a) when nobody has one.
  protected readonly draftLow = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.draft)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.min(...values);
  });

  // The highest draft among the people not on hold (n/a drafts skipped); null (n/a) when nobody has one.
  protected readonly draftTop = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.draft)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values);
  });

  // The highest draft minus the lowest among the people not on hold; null (n/a) when nobody has one.
  protected readonly draftSpread = computed(() => {
    const top = this.draftTop();
    const low = this.draftLow();
    return top === null || low === null ? null : top - low;
  });

  // The average draft among the people not on hold (n/a drafts skipped), rounded like every
  // divide; null (n/a) when nobody has one.
  protected readonly draftMean = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.draft)
      .filter((v): v is number => v !== null);
    return values.length === 0
      ? null
      : divide(values.reduce((total, v) => total + v, 0), values.length);
  });

  // Everyone's cascade added up, trimmed like every sheet total.
  protected readonly cascadeTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.cascade, hold: p.hold }))),
  );

  // The lowest cascade among the people not on hold (n/a cascades skipped); null (n/a) when nobody has one.
  protected readonly cascadeLow = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.cascade)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.min(...values);
  });

  // The highest cascade among the people not on hold (n/a cascades skipped); null (n/a) when nobody has one.
  protected readonly cascadeTop = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.cascade)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values);
  });

  // The highest cascade minus the lowest among the people not on hold; null (n/a) when nobody has one.
  protected readonly cascadeSpread = computed(() => {
    const top = this.cascadeTop();
    const low = this.cascadeLow();
    return top === null || low === null ? null : top - low;
  });

  // The average cascade among the people not on hold (n/a cascades skipped), rounded like every
  // divide; null (n/a) when nobody has one.
  protected readonly cascadeMean = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.cascade)
      .filter((v): v is number => v !== null);
    return values.length === 0
      ? null
      : divide(values.reduce((total, v) => total + v, 0), values.length);
  });

  // Everyone's trim added up, trimmed like every sheet total.
  protected readonly trimTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.trim, hold: p.hold }))),
  );

  // The highest trim among the people not on hold (n/a trims skipped); null (n/a) when nobody has one.
  protected readonly trimTop = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.trim)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values);
  });

  // The highest trim minus the lowest among the people not on hold (n/a trims skipped); null (n/a)
  // when nobody has one.
  protected readonly trimSpread = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.trim)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values) - Math.min(...values);
  });

  // The average trim among the people not on hold (n/a trims skipped), rounded like every divide;
  // null (n/a) when nobody has one.
  protected readonly trimMean = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.trim)
      .filter((v): v is number => v !== null);
    return values.length === 0
      ? null
      : divide(values.reduce((total, v) => total + v, 0), values.length);
  });

  // Everyone's ripple added up, trimmed like every sheet total.
  protected readonly rippleTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.ripple, hold: p.hold }))),
  );

  // The lowest ripple among the people not on hold (n/a ripples skipped); null (n/a) when nobody has one.
  protected readonly rippleLow = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.ripple)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.min(...values);
  });

  // The highest ripple among the people not on hold (n/a ripples skipped); null (n/a) when nobody has one.
  protected readonly rippleTop = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.ripple)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values);
  });

  // The highest ripple minus the lowest among the people not on hold; null (n/a) when nobody has one.
  protected readonly rippleSpread = computed(() => {
    const top = this.rippleTop();
    const low = this.rippleLow();
    return top === null || low === null ? null : top - low;
  });

  // The average ripple among the people not on hold (n/a ripples skipped), rounded like every
  // divide; null (n/a) when nobody has one.
  protected readonly rippleMean = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.ripple)
      .filter((v): v is number => v !== null);
    return values.length === 0
      ? null
      : divide(values.reduce((total, v) => total + v, 0), values.length);
  });

  // Everyone's heading added up, trimmed like every sheet total.
  protected readonly headingTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.heading, hold: p.hold }))),
  );

  // The highest heading among the people not on hold (n/a headings skipped); null (n/a) when nobody has one.
  protected readonly headingTop = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.heading)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values);
  });

  // The lowest heading among the people not on hold (n/a headings skipped); null (n/a) when nobody has one.
  protected readonly headingLow = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.heading)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.min(...values);
  });

  // The highest heading minus the lowest among the people not on hold; null (n/a) when nobody has one.
  protected readonly headingSpread = computed(() => {
    const top = this.headingTop();
    const low = this.headingLow();
    return top === null || low === null ? null : top - low;
  });

  // The average heading among the people not on hold (n/a headings skipped), rounded like every
  // divide; null (n/a) when nobody has one.
  protected readonly headingMean = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.heading)
      .filter((v): v is number => v !== null);
    return values.length === 0
      ? null
      : divide(values.reduce((total, v) => total + v, 0), values.length);
  });

  // Everyone's ballast added up, trimmed like every sheet total.
  protected readonly ballastTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.ballast, hold: p.hold }))),
  );

  // The lowest ballast among the people not on hold (n/a ballasts skipped); null (n/a) when nobody has one.
  protected readonly ballastLow = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.ballast)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.min(...values);
  });

  // The highest ballast among the people not on hold (n/a ballasts skipped); null (n/a) when nobody has one.
  protected readonly ballastTop = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.ballast)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values);
  });

  // The highest ballast minus the lowest among the people not on hold; null (n/a) when nobody has one.
  protected readonly ballastSpread = computed(() => {
    const top = this.ballastTop();
    const low = this.ballastLow();
    return top === null || low === null ? null : top - low;
  });

  // The average ballast among the people not on hold (n/a ballasts skipped), rounded like every
  // divide; null (n/a) when nobody has one.
  protected readonly ballastMean = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.ballast)
      .filter((v): v is number => v !== null);
    return values.length === 0
      ? null
      : divide(values.reduce((total, v) => total + v, 0), values.length);
  });

  // Everyone's keystone added up, trimmed like every sheet total.
  protected readonly keystoneTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.keystone, hold: p.hold }))),
  );

  // The lowest keystone among the people not on hold (n/a keystones skipped); null (n/a) when nobody has one.
  protected readonly keystoneLow = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.keystone)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.min(...values);
  });

  // The highest keystone among the people not on hold (n/a keystones skipped); null (n/a) when nobody has one.
  protected readonly keystoneTop = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.keystone)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values);
  });

  // The highest keystone minus the lowest among the people not on hold; null (n/a) when nobody has one.
  protected readonly keystoneSpread = computed(() => {
    const top = this.keystoneTop();
    const low = this.keystoneLow();
    return top === null || low === null ? null : top - low;
  });

  // The average keystone among the people not on hold (n/a keystones skipped), rounded like every
  // divide; null (n/a) when nobody has one.
  protected readonly keystoneMean = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.keystone)
      .filter((v): v is number => v !== null);
    return values.length === 0
      ? null
      : divide(values.reduce((total, v) => total + v, 0), values.length);
  });

  // Everyone's bearing added up, trimmed like every sheet total.
  protected readonly bearingTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.bearing, hold: p.hold }))),
  );

  // The lowest bearing among the people not on hold (n/a bearings skipped); null (n/a) when nobody has one.
  protected readonly bearingLow = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.bearing)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.min(...values);
  });

  // The highest bearing among the people not on hold (n/a bearings skipped); null (n/a) when nobody has one.
  protected readonly bearingTop = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.bearing)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values);
  });

  // The highest bearing minus the lowest among the people not on hold; null (n/a) when nobody has one.
  protected readonly bearingSpread = computed(() => {
    const top = this.bearingTop();
    const low = this.bearingLow();
    return top === null || low === null ? null : top - low;
  });

  // The average bearing among the people not on hold (n/a bearings skipped), rounded like every
  // divide; null (n/a) when nobody has one.
  protected readonly bearingMean = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.bearing)
      .filter((v): v is number => v !== null);
    return values.length === 0
      ? null
      : divide(values.reduce((total, v) => total + v, 0), values.length);
  });

  // Everyone's pitch added up, trimmed like every sheet total.
  protected readonly pitchTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.pitch, hold: p.hold }))),
  );

  // The highest pitch among the people not on hold (n/a pitches skipped); null (n/a) when nobody has one.
  protected readonly pitchTop = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.pitch)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values);
  });

  // The highest pitch minus the lowest among the people not on hold (n/a pitches skipped); null
  // (n/a) when nobody has one.
  protected readonly pitchSpread = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.pitch)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values) - Math.min(...values);
  });

  // The average pitch among the people not on hold (n/a pitches skipped), rounded like every
  // divide; null (n/a) when nobody has one.
  protected readonly pitchMean = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.pitch)
      .filter((v): v is number => v !== null);
    return values.length === 0
      ? null
      : divide(values.reduce((total, v) => total + v, 0), values.length);
  });

  // Everyone's tackle added up, trimmed like every sheet total.
  protected readonly tackleTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.tackle, hold: p.hold }))),
  );

  // The highest tackle among the people not on hold (n/a tackles skipped); null (n/a) when nobody has one.
  protected readonly tackleTop = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.tackle)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values);
  });

  // The highest tackle minus the lowest among the people not on hold (n/a tackles skipped); null
  // (n/a) when nobody has one.
  protected readonly tackleSpread = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.tackle)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values) - Math.min(...values);
  });

  // The average tackle among the people not on hold (n/a tackles skipped), rounded like every
  // divide; null (n/a) when nobody has one.
  protected readonly tackleMean = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.tackle)
      .filter((v): v is number => v !== null);
    return values.length === 0
      ? null
      : divide(values.reduce((total, v) => total + v, 0), values.length);
  });

  // Everyone's yaw added up, trimmed like every sheet total.
  protected readonly yawTotal = computed(() =>
    trimmedTotal((this.people() ?? []).map((p) => ({ value: p.yaw, hold: p.hold }))),
  );

  // The highest yaw among the people not on hold (n/a yaws skipped); null (n/a) when nobody has one.
  protected readonly yawTop = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.yaw)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values);
  });

  // The highest yaw minus the lowest among the people not on hold (n/a yaws skipped); null
  // (n/a) when nobody has one.
  protected readonly yawSpread = computed(() => {
    const values = (this.people() ?? [])
      .filter((p) => !p.hold)
      .map((p) => p.yaw)
      .filter((v): v is number => v !== null);
    return values.length === 0 ? null : Math.max(...values) - Math.min(...values);
  });

  // How many counted people have an even keel (n/a keels are not counted).
  protected readonly keelCount = computed(
    () => countable(this.people() ?? []).filter((p) => p.keel !== null && p.keel % 2 === 0).length,
  );

  // How many counted people have an even fathom (n/a fathoms are not counted).
  protected readonly fathomEven = computed(
    () => countable(this.people() ?? []).filter((p) => p.fathom !== null && p.fathom % 2 === 0).length,
  );

  // How many counted people have an even draft (n/a drafts are not counted).
  protected readonly draftEven = computed(
    () => countable(this.people() ?? []).filter((p) => p.draft !== null && p.draft % 2 === 0).length,
  );

  // How many counted people have an even cascade (n/a cascades are not counted).
  protected readonly cascadeEven = computed(
    () =>
      countable(this.people() ?? []).filter((p) => p.cascade !== null && p.cascade % 2 === 0).length,
  );

  // How many counted people have an even trim (n/a trims are not counted).
  protected readonly trimEven = computed(
    () => countable(this.people() ?? []).filter((p) => p.trim !== null && p.trim % 2 === 0).length,
  );

  // How many counted people have an even ripple (n/a ripples are not counted).
  protected readonly rippleEven = computed(
    () =>
      countable(this.people() ?? []).filter((p) => p.ripple !== null && p.ripple % 2 === 0).length,
  );

  // How many counted people have an even heading (n/a headings are not counted).
  protected readonly headingEven = computed(
    () =>
      countable(this.people() ?? []).filter((p) => p.heading !== null && p.heading % 2 === 0).length,
  );

  // How many counted people have an even ballast (n/a ballasts are not counted).
  protected readonly ballastEven = computed(
    () =>
      countable(this.people() ?? []).filter((p) => p.ballast !== null && p.ballast % 2 === 0).length,
  );

  // How many counted people have an even keystone (n/a keystones are not counted).
  protected readonly keystoneEven = computed(
    () =>
      countable(this.people() ?? []).filter((p) => p.keystone !== null && p.keystone % 2 === 0).length,
  );

  // How many counted people have an even bearing (n/a bearings are not counted).
  protected readonly bearingEven = computed(
    () =>
      countable(this.people() ?? []).filter((p) => p.bearing !== null && p.bearing % 2 === 0).length,
  );

  // How many counted people have an even pitch (n/a pitches are not counted).
  protected readonly pitchEven = computed(
    () => countable(this.people() ?? []).filter((p) => p.pitch !== null && p.pitch % 2 === 0).length,
  );

  // How many counted people have an even tackle (n/a tackles are not counted).
  protected readonly tackleEven = computed(
    () =>
      countable(this.people() ?? []).filter((p) => p.tackle !== null && p.tackle % 2 === 0).length,
  );

  // How many counted people have an even yaw (n/a yaws are not counted).
  protected readonly yawEven = computed(
    () => countable(this.people() ?? []).filter((p) => p.yaw !== null && p.yaw % 2 === 0).length,
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
