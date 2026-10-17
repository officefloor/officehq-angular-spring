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
              </tr>
            }
          </tbody>
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

  protected patronNames(ids: number[]): string {
    const names = this.names();
    return ids.map((id) => names.get(id) ?? String(id)).join(', ');
  }
}
