import { Component, Injectable, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';

/** The columns of the people list a user can switch off on their screen. */
export const PERSON_COLUMNS = [
  'claimed', 'due-total', 'statement-count', 'stated-total', 'statement-date', 'overdue-count',
  'overdue-total', 'patrons', 'backing', 'hold', 'retired', 'terms', 'contact', 'aliases', 'excess',
] as const;
export type PersonColumn = (typeof PERSON_COLUMNS)[number];

// Which columns of the people list the signed-in user's screen shows. It belongs to this screen
// only and to the user signed in on it: every column is shown again when someone signs in.
@Injectable({ providedIn: 'root' })
export class PeopleColumns {
  private readonly session = inject(Session);
  private readonly hiddenState = signal<ReadonlySet<PersonColumn>>(new Set());
  readonly hidden = this.hiddenState.asReadonly();

  constructor() {
    effect(() => {
      this.session.user();
      this.hiddenState.set(new Set());
    });
  }

  shown(column: PersonColumn): boolean {
    return !this.hiddenState().has(column);
  }

  set(column: PersonColumn, on: boolean): void {
    this.hiddenState.update((was) => {
      const next = new Set(was);
      if (on) next.delete(column);
      else next.add(column);
      return next;
    });
  }
}

// A checkbox per column of the people list: the columns this user's screen shows.
@Component({
  selector: 'app-people-columns',
  template: `
    <fieldset>
      <legend>Columns shown</legend>
      @for (c of columns; track c) {
        <label>
          <input type="checkbox" [attr.data-testid]="'column-' + c" [checked]="cols.shown(c)" #box
            (change)="cols.set(c, box.checked)" />
          {{ c }}
        </label>
      }
    </fieldset>
  `,
})
export class PeopleColumnsChoice {
  protected readonly cols = inject(PeopleColumns);
  protected readonly columns = PERSON_COLUMNS;
}
