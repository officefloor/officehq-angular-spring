import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { cents, money } from '../claims/claims-api';
import type { Person } from '../people/people-api';
import { dueCents } from '../rates/rates-api';
import { Pool, PoolMember, PoolsApi, SHARE_PATTERN, memberError, memberKey, poolError } from './pools-api';

/** A pool's members' shares in cents, a retired person's (any with a retirement date) counting 0. */
function memberShares(pool: Pool, retired: (personId: number) => boolean): number[] {
  return pool.members.map((m) => (m.personId !== null && retired(m.personId) ? 0 : cents(m.share)));
}

/** Each member's cut of the pool's pot, by member key: the pot times their share divided by 100,
 *  rounded half to even (a retired member's 0.00), the remainder going to the member with the
 *  largest share (the earliest added on a tie) so the cuts total the pot; none unless the pool
 *  is balanced and has a pot. */
function cutsOf(pool: Pool, pot: string | null, retired: (personId: number) => boolean): Map<string, string> {
  const cutsByKey = new Map<string, string>();
  const shares = memberShares(pool, retired);
  if (pot === null || shares.reduce((sum, c) => sum + c, 0) !== 10000) return cutsByKey;
  const potCents = cents(pot);
  const raw = shares.map((share) => (share === 0 ? 0 : dueCents(potCents, share)));
  let largest = 0;
  shares.forEach((share, i) => { if (share > shares[largest]) largest = i; });
  raw[largest] += potCents - raw.reduce((sum, c) => sum + c, 0);
  pool.members.forEach((m, i) => cutsByKey.set(memberKey(m), money(raw[i])));
  return cutsByKey;
}

// One pool's row: its total share and whether it is balanced, its members each with their share,
// and the form that adds a member to it.
@Component({
  selector: 'li[appPoolRow]',
  imports: [ReactiveFormsModule],
  host: { '[attr.data-testid]': "'pool-row-' + pool().name" },
  template: `
      <h3 data-testid="pool-name">{{ pool().name }}</h3>
      <p>Total share <strong data-testid="pool-total-share">{{ totalShare() }}</strong>
        Balanced <strong data-testid="pool-balanced">{{ balanced() ? 'yes' : 'no' }}</strong></p>
      <ul [attr.aria-label]="'Members of pool ' + pool().name">
        @for (m of pool().members; track key(m)) {
          <li [attr.data-testid]="'pool-member-' + pool().name + '-' + key(m)">
            <span data-testid="pool-member-name">{{ nameOf(m) }}</span>
            <span data-testid="pool-share">{{ m.share }}</span>
            <span data-testid="pool-cut">{{ cuts().get(key(m)) ?? '' }}</span>
            @if (m.personId !== null && retired(m.personId)) {
              <span>(retired)</span>
            }
          </li>
        }
      </ul>
      <form [formGroup]="form" (ngSubmit)="add()" [attr.aria-label]="'Add a member to pool ' + pool().name">
        <label [for]="'pool-member-select-' + pool().name">Person or pool</label>
        <select [id]="'pool-member-select-' + pool().name" data-testid="pool-member-select" formControlName="member">
          <option value="">Choose a person or pool</option>
          @for (p of people(); track p.id) {
            <option [value]="p.id">{{ p.name }}</option>
          }
          @for (p of pools(); track p.name) {
            <option [value]="'pool:' + p.name">pool:{{ p.name }}</option>
          }
        </select>
        <label [for]="'pool-member-share-' + pool().name">Share %</label>
        <input [id]="'pool-member-share-' + pool().name" data-testid="pool-member-share" formControlName="share"
          inputmode="decimal" autocomplete="off" />
        <button type="submit" data-testid="pool-member-add">Add member</button>
        @if (error(); as err) {
          <p role="alert" data-testid="pool-error">{{ err }}</p>
        }
      </form>
  `,
})
export class PoolRow {
  private readonly session = inject(Session);
  private readonly api = inject(PoolsApi);

  readonly pool = input.required<Pool>();
  /** The people on the sheet (retired ones too). */
  readonly people = input.required<Person[]>();
  /** The sheet's pools, any of which may be chosen as a member. */
  readonly pools = input.required<Pool[]>();
  /** Each member's cut of this pool's pot, by member key. */
  readonly cuts = input.required<Map<string, string>>();
  /** The sheet's pools after a member was added. */
  readonly saved = output<Pool[]>();

  protected readonly error = signal<string | null>(null);
  private saving = false;

  protected readonly form = new FormGroup({
    member: new FormControl('', { nonNullable: true }),
    share: new FormControl('', { nonNullable: true }),
  });

  private readonly byId = computed(() => new Map(this.people().map((p) => [p.id, p])));
  /** The members' shares added up, a retired member's counting 0.00. */
  private readonly totalCents = computed(() =>
    memberShares(this.pool(), (id) => this.retired(id)).reduce((sum, c) => sum + c, 0));
  protected readonly totalShare = computed(() => money(this.totalCents()));
  /** Balanced when the shares total exactly 100.00. */
  protected readonly balanced = computed(() => this.totalCents() === 10000);

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
  }

  protected key(m: PoolMember): string {
    return memberKey(m);
  }

  protected nameOf(m: PoolMember): string {
    return m.pool !== null ? `pool:${m.pool}` : (this.byId().get(m.personId ?? -1)?.name ?? '');
  }

  protected retired(personId: number): boolean {
    return (this.byId().get(personId)?.retiredFrom ?? null) !== null;
  }

  protected async add(): Promise<void> {
    if (this.saving) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.form.getRawValue();
    const share = value.share.trim();
    let member: { personId: number } | { memberPool: string };
    if (value.member.startsWith('pool:')) {
      const memberPool = value.member.slice('pool:'.length);
      if (!this.pools().some((p) => p.name === memberPool)) {
        this.error.set('Choose a person or pool to add to the pool.');
        return;
      }
      member = { memberPool };
    } else {
      const personId = Number(value.member);
      if (value.member === '' || !this.byId().has(personId)) {
        this.error.set('Choose a person or pool to add to the pool.');
        return;
      }
      if (this.pool().members.some((m) => m.personId === personId)) {
        this.error.set('That person is in the pool already.');
        return;
      }
      member = { personId };
    }
    if (!SHARE_PATTERN.test(share) || cents(share) <= 0 || cents(share) > 10000) {
      this.error.set('Enter a share more than 0.00 and at most 100.00, with up to two decimals.');
      return;
    }
    this.saving = true;
    try {
      const pools = await this.api.addMember(sheetId, this.pool().name, member, share);
      if (this.session.sheetId() === sheetId) this.saved.emit(pools);
      this.form.reset({ member: '', share: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(memberError(e));
    } finally {
      this.saving = false;
    }
  }
}

// The sheet's pools, in the order they were added, and the form that adds one.
@Component({
  selector: 'app-pools',
  imports: [ReactiveFormsModule, PoolRow],
  template: `
    <section aria-labelledby="pools-heading">
      <h2 id="pools-heading">Pools</h2>
      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a pool">
        <label for="pool-form-name">Name</label>
        <input id="pool-form-name" data-testid="pool-form-name" formControlName="name" autocomplete="off" />
        <button type="submit" data-testid="pool-form-submit">Add pool</button>
        @if (error(); as err) {
          <p role="alert" data-testid="pool-form-error">{{ err }}</p>
        }
      </form>
      <ul aria-label="Pools" data-testid="pools-list">
        @for (p of pools(); track p.name) {
          <li appPoolRow [pool]="p" [people]="people()" [pools]="pools()" [cuts]="cuts().get(p.name) ?? noCuts"
            (saved)="taken($event)"></li>
        }
      </ul>
    </section>
  `,
})
export class Pools {
  private readonly session = inject(Session);
  private readonly api = inject(PoolsApi);

  /** The people on the sheet (retired ones too). */
  readonly people = input.required<Person[]>();

  protected readonly pools = signal<Pool[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly noCuts = new Map<string, string>();
  /** Each pool's members' cuts, by pool name: a pool that is a member of none cuts the sheet's
   *  pot; a pool that is a member of another has no pot of its own but its cut in the pool above
   *  (none when that pool has no cuts). */
  protected readonly cuts = computed(() => {
    const pools = this.pools();
    const retiredIds = new Set(this.people().filter((p) => (p.retiredFrom ?? null) !== null).map((p) => p.id));
    const retired = (id: number) => retiredIds.has(id);
    const parentOf = new Map<string, string>();
    for (const p of pools) for (const m of p.members) if (m.pool !== null) parentOf.set(m.pool, p.name);
    const byName = new Map(pools.map((p) => [p.name, p]));
    const all = new Map<string, Map<string, string>>();
    const cutsFor = (pool: Pool, seen: Set<string>): Map<string, string> => {
      const known = all.get(pool.name);
      if (known) return known;
      const parent = parentOf.get(pool.name);
      let pot: string | null = this.session.pot();
      if (parent !== undefined) {
        const above = byName.get(parent);
        pot = above && !seen.has(parent)
          ? (cutsFor(above, new Set([...seen, pool.name])).get(`pool-${pool.name}`) ?? null)
          : null;
      }
      const cuts = cutsOf(pool, pot, retired);
      all.set(pool.name, cuts);
      return cuts;
    };
    for (const p of pools) cutsFor(p, new Set([p.name]));
    return all;
  });
  private saving = false;
  /** Guards against a slower, older read landing over a newer one. */
  private readSeq = 0;

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.pools.set([]);
        this.error.set(null);
        this.form.reset({ name: '' }, { emitEvent: false });
        if (id) void this.read(id);
      });
    });
  }

  protected taken(pools: Pool[]): void {
    this.readSeq++;
    this.pools.set(pools);
  }

  protected async add(): Promise<void> {
    if (this.saving) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const name = this.form.getRawValue().name.trim();
    if (name === '') {
      this.error.set('Enter a name for the pool.');
      return;
    }
    if (this.pools().some((p) => p.name.toLowerCase() === name.toLowerCase())) {
      this.error.set('The sheet has a pool of that name already.');
      return;
    }
    this.saving = true;
    try {
      const pools = await this.api.add(sheetId, name);
      if (this.session.sheetId() === sheetId) this.taken(pools);
      this.form.reset({ name: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(poolError(e));
    } finally {
      this.saving = false;
    }
  }

  private async read(sheetId: string): Promise<void> {
    const seq = ++this.readSeq;
    try {
      const pools = await this.api.list(sheetId);
      if (seq === this.readSeq && this.session.sheetId() === sheetId) this.pools.set(pools);
    } catch {
      if (seq === this.readSeq) this.error.set('Something went wrong loading pools; try again.');
    }
  }
}
