import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { PeopleService } from './people.service';

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
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="6">Thrust total</th>
              <td data-testid="rollup-thrust-total">{{ thrustTotal() }}</td>
              <td colspan="7"></td>
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
    trimmedTotal((this.people() ?? []).map((p) => p.thrust)),
  );

  protected patronNames(ids: number[]): string {
    const names = this.names();
    return ids.map((id) => names.get(id) ?? String(id)).join(', ');
  }
}

// A sheet-wide total: the contributions (n/a ones skipped) added up, leaving out one highest and
// one lowest whenever at least two people contribute. Every sheet total goes through this.
export function trimmedTotal(values: (number | null)[]): number {
  const contributions = values.filter((v): v is number => v !== null);
  const sum = contributions.reduce((total, v) => total + v, 0);
  if (contributions.length < 2) {
    return sum;
  }
  return sum - Math.max(...contributions) - Math.min(...contributions);
}
