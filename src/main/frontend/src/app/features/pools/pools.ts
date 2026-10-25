import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { cents, money } from '../claims/claims-api';
import { PersonView } from '../people/people-api';
import { SheetPot } from '../settings/sheet-pot';
import { Pools, PoolView } from './pools-api';

/** A share percent as typed: up to two decimals. */
const SHARE = /^\d{1,3}(\.\d{1,2})?$/;

// One pool: its members with their shares, its total share and whether it is balanced, and a form
// to add a member. A retired member's share counts as 0.00 toward the total.
// With a pot set, each member of a balanced pool shows a cut: the pot times their share divided by 100,
// rounded half to even to two decimals (a retired member cuts 0.00); the pot less the cuts added up goes
// to the member with the largest share, the earliest added on a tie, so the cuts total exactly the pot.
// An unbalanced pool, or no pot, shows no cut.
@Component({
  selector: 'app-pool-row',
  imports: [ReactiveFormsModule],
  host: { '[attr.data-testid]': "'pool-row-' + pool().name", role: 'listitem' },
  template: `
    <h3 data-testid="pool-name">{{ pool().name }}</h3>
    <p>
      Total share <span data-testid="pool-total-share">{{ total() }}</span>,
      balanced <span data-testid="pool-balanced">{{ balanced() ? 'yes' : 'no' }}</span>
    </p>
    <ul [attr.aria-label]="'Members of ' + pool().name">
      @for (m of members(); track m.personId) {
        <li [attr.data-testid]="'pool-member-' + pool().name + '-' + m.personId">
          <span data-testid="pool-member-name">{{ m.name }}</span>
          <span data-testid="pool-share">{{ m.share }}</span>
          <span data-testid="pool-cut" aria-label="Cut">{{ cuts()?.get(m.personId) ?? '' }}</span>
          @if (m.retired) {
            <span data-testid="pool-member-retired">(retired)</span>
          }
        </li>
      }
    </ul>
    <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="'Add a member to ' + pool().name">
      <label [for]="'pool-member-select-' + pool().id">Person</label>
      <select [id]="'pool-member-select-' + pool().id" data-testid="pool-member-select" formControlName="personId">
        <option value="">Choose a person</option>
        @for (p of people(); track p.id) {
          <option [value]="'' + p.id">{{ p.name }}</option>
        }
      </select>
      <label [for]="'pool-member-share-' + pool().id">Share %</label>
      <input [id]="'pool-member-share-' + pool().id" data-testid="pool-member-share" formControlName="share" inputmode="decimal" />
      <button type="submit" data-testid="pool-member-add" [disabled]="saving()">Add member</button>
      @if (error()) {
        <p role="alert" data-testid="pool-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class PoolRow {
  private readonly session = inject(Session);
  private readonly store = inject(Pools);
  private readonly sheetPot = inject(SheetPot);

  readonly pool = input.required<PoolView>();
  /** Everyone on the sheet, retired or not. */
  readonly people = input.required<PersonView[]>();

  private readonly peopleById = computed(() => new Map(this.people().map((p) => [p.id, p])));
  protected readonly members = computed(() => this.pool().members.map((m) => {
    const person = this.peopleById().get(m.personId);
    return { personId: m.personId, share: m.share, name: person?.name ?? '', retired: person?.retired ?? false };
  }));
  private readonly totalCents = computed(() => this.members().reduce((t, m) => (m.retired ? t : t + cents(m.share)), 0));
  protected readonly total = computed(() => money(this.totalCents()));
  protected readonly balanced = computed(() => this.totalCents() === 10000);
  /** Each member's cut of the pot, with two decimals, by person; null for an unbalanced pool or no pot. */
  protected readonly cuts = computed(() => {
    const pot = this.sheetPot.pot();
    if (pot === null || !this.balanced()) return null;
    return new Map(cutsOf(BigInt(cents(pot)), this.members()).map(([id, cut]) => [id, money(Number(cut))]));
  });

  protected readonly personId = new FormControl('', { nonNullable: true });
  protected readonly share = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ personId: this.personId, share: this.share });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const pool = this.pool();
    const { personId, share } = this.form.getRawValue();
    const typed = share.trim();
    if (personId === '') {
      this.error.set('Choose a person.');
      return;
    }
    if (this.pool().members.some((m) => String(m.personId) === personId)) {
      this.error.set('That person is already in the pool.');
      return;
    }
    if (!SHARE.test(typed) || cents(typed) <= 0 || cents(typed) > 10000) {
      this.error.set('Type a share of more than 0.00 and at most 100.00, with up to two decimals.');
      return;
    }
    this.saving.set(true);
    try {
      await this.store.addMember(sheet.id, pool.id, Number(personId), typed);
      if (this.session.sheet()?.id === sheet.id) this.form.reset({ personId: '', share: '' }, { emitEvent: false });
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(status === 403 ? 'Your role may not change a pool on this sheet.'
        : status === 409 ? 'That person is already in the pool.'
        : status === 422 ? 'Type a share of more than 0.00 and at most 100.00, with up to two decimals.'
        : 'The member could not be added.');
    } finally {
      this.saving.set(false);
    }
  }
}

/** A whole number of cents divided by 10000, rounded half to even. */
function divideHalfEven(value: bigint, by: bigint): bigint {
  const quotient = value / by;
  const twice = 2n * (value % by);
  return twice > by || (twice === by && quotient % 2n !== 0n) ? quotient + 1n : quotient;
}

/** The cuts of a pot (in cents) among members in the order they were added, by person: each the pot times
 *  their share over 100, half to even (a retired member's 0); the remainder to the largest share, the
 *  earliest added on a tie. */
function cutsOf(potCents: bigint, members: readonly { personId: number; share: string; retired: boolean }[]): [number, bigint][] {
  const shares = members.map((m) => (m.retired ? 0 : cents(m.share)));
  const cuts = shares.map((share) => divideHalfEven(potCents * BigInt(share), 10000n));
  let largest = 0;
  shares.forEach((share, i) => {
    if (share > shares[largest]) largest = i;
  });
  if (cuts.length > 0) cuts[largest] += potCents - cuts.reduce((t, c) => t + c, 0n);
  return members.map((m, i) => [m.personId, cuts[i]]);
}

// The sheet's pools, with a form to add one.
@Component({
  selector: 'app-pools',
  imports: [ReactiveFormsModule, PoolRow],
  template: `
    <section aria-labelledby="pools-heading">
      <h2 id="pools-heading">Pools</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Add a pool">
        <label for="pool-form-name">Name</label>
        <input id="pool-form-name" data-testid="pool-form-name" formControlName="name" />
        <button type="submit" data-testid="pool-form-submit" [disabled]="saving()">Add pool</button>
        @if (error() || store.loadError(); as message) {
          <p role="alert" data-testid="pool-form-error">{{ message }}</p>
        }
      </form>
      <div role="list" aria-label="Pools">
        @for (p of store.pools(); track p.id) {
          <app-pool-row [pool]="p" [people]="people()" />
        }
      </div>
    </section>
  `,
})
export class PoolsList {
  private readonly session = inject(Session);
  protected readonly store = inject(Pools);

  /** Everyone on the sheet, retired or not. */
  readonly people = input.required<PersonView[]>();

  protected readonly name = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ name: this.name });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const name = this.name.value.trim();
    if (name === '') {
      this.error.set('Type a pool name.');
      return;
    }
    if (this.store.pools().some((p) => p.name === name)) {
      this.error.set(`The sheet already has a pool "${name}".`);
      return;
    }
    this.saving.set(true);
    try {
      await this.store.add(sheet.id, name);
      if (this.session.sheet()?.id === sheet.id) this.form.reset({ name: '' }, { emitEvent: false });
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(status === 403 ? 'Your role may not add a pool on this sheet.'
        : status === 409 ? `The sheet already has a pool "${name}".`
        : status === 422 ? 'Type a pool name.'
        : 'The pool could not be added.');
    } finally {
      this.saving.set(false);
    }
  }
}
