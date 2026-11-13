import { Component, inject, input, signal } from '@angular/core';
import { download } from '../../core/csv';
import { People } from '../../core/people';
import { PoolMemberChoice, PoolView, Pools } from '../../core/pools';
import { Session } from '../../core/session';

// One pool's row: its members with their shares, its total share and whether it is balanced (exactly
// 100.00; a retired member counts 0.00), each member's cut of the pool's pot while balanced (the sheet's pot, or
// its cut in the pool it is a member of), and a form adding a person or another pool (pool:<name>) with a share,
// from and to a date when given. A member whose dates do not include the sheet's today is left out entirely.
@Component({
  selector: 'app-pool-row',
  template: `
    @let p = pool();
    <h3 data-testid="pool-name">{{ p.name }}</h3>
    <p>
      Total share: <strong data-testid="pool-total-share">{{ p.totalShare }}</strong>
      Balanced: <strong data-testid="pool-balanced">{{ p.balanced ? 'yes' : 'no' }}</strong>
    </p>
    <ul [attr.aria-label]="'Members of ' + p.name">
      @for (m of p.members; track memberKey(m)) {
        <li [attr.data-testid]="'pool-member-' + p.name + '-' + memberKey(m)">
          <span data-testid="pool-member-name">{{ m.name }}</span>
          <span data-testid="pool-share">{{ m.share }}</span>
          <span data-testid="pool-cut">{{ m.cut ?? '' }}</span>
          @if (m.from || m.to) {
            <span data-testid="pool-member-dates">{{ m.from ?? '' }}–{{ m.to ?? '' }}</span>
          }
          @if (!m.active) {
            <span data-testid="pool-member-inactive">not current</span>
          }
          @if (m.retired) {
            <span data-testid="pool-member-retired">retired</span>
          }
        </li>
      }
    </ul>
    <form (submit)="add($event)">
      <label [for]="'pool-member-select-' + p.id">Member</label>
      <select
        [id]="'pool-member-select-' + p.id"
        data-testid="pool-member-select"
        (change)="member.set(selected($event)); error.set(null)"
      >
        <option value="" [selected]="member() === ''">Choose a person or a pool</option>
        @for (person of people.people(); track person.id) {
          <option [value]="'person:' + person.id" [selected]="member() === 'person:' + person.id">{{ person.name }}</option>
        }
        @for (other of pools.pools(); track other.id) {
          <option [value]="'pool:' + other.id" [selected]="member() === 'pool:' + other.id">pool:{{ other.name }}</option>
        }
      </select>
      <label [for]="'pool-member-share-' + p.id">Share %</label>
      <input
        [id]="'pool-member-share-' + p.id"
        data-testid="pool-member-share"
        inputmode="decimal"
        [value]="share()"
        (input)="share.set(value($event)); error.set(null)"
      />
      <label [for]="'pool-member-from-' + p.id">From</label>
      <input
        [id]="'pool-member-from-' + p.id"
        data-testid="pool-member-from"
        placeholder="YYYY-MM-DD"
        [value]="from()"
        (input)="from.set(value($event)); error.set(null)"
      />
      <label [for]="'pool-member-to-' + p.id">To</label>
      <input
        [id]="'pool-member-to-' + p.id"
        data-testid="pool-member-to"
        placeholder="YYYY-MM-DD"
        [value]="to()"
        (input)="to.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="pool-member-add" [disabled]="saving()">Add member</button>
      @if (error(); as err) {
        <p role="alert" data-testid="pool-error">{{ err }}</p>
      }
    </form>
  `,
})
export class PoolRow {
  readonly pool = input.required<PoolView>();
  protected readonly people = inject(People);
  protected readonly pools = inject(Pools);
  /** The chosen member: person:<id>, pool:<id>, or blank for none. */
  protected readonly member = signal('');
  protected readonly share = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected selected(e: Event): string {
    return (e.target as HTMLSelectElement).value;
  }

  /** A person member by their id; a pool member as pool-<name>. */
  protected memberKey(m: PoolView['members'][number]): string {
    return m.poolId !== null ? 'pool-' + m.name : String(m.personId);
  }

  private choice(): PoolMemberChoice | null {
    const [kind, id] = this.member().split(':');
    if (!id) return null;
    return kind === 'pool' ? { poolId: Number(id) } : { personId: Number(id) };
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.pools.addMember(this.pool().id, this.choice(), this.share(), this.from(), this.to());
      if (err) this.error.set(err);
      else {
        this.member.set('');
        this.share.set('');
        this.from.set('');
        this.to.set('');
      }
    } finally {
      this.saving.set(false);
    }
  }
}

// The sheet's pools: a form adds one (a blank name, or one already taken, is refused), and each pool's row
// shows its members and adds more. A button downloads the pools as CSV.
@Component({
  selector: 'app-pools',
  imports: [PoolRow],
  template: `
    <section aria-label="Pools" data-testid="pools">
      <h2>Pools</h2>
      <form (submit)="add($event)" data-testid="pool-form">
        <label for="pool-form-name">Name</label>
        <input
          id="pool-form-name"
          data-testid="pool-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="pool-form-submit" [disabled]="saving()">Add pool</button>
        @if (error(); as err) {
          <p role="alert" data-testid="pool-form-error">{{ err }}</p>
        }
      </form>
      <button type="button" data-testid="export-pools" (click)="exportCsv()">Export pools (CSV)</button>
      @for (p of pools.pools(); track p.id) {
        <app-pool-row [pool]="p" [attr.data-testid]="'pool-row-' + p.name" />
      }
    </section>
  `,
})
export class PoolsPanel {
  protected readonly pools = inject(Pools);
  private readonly session = inject(Session);
  protected readonly name = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  /** Download the pools as CSV, re-read first: pools and their members in the order added, a pool member
   *  as pool:<name>, the cut as shown (empty when none). */
  protected async exportCsv(): Promise<void> {
    await this.session.loaded();
    await this.pools.reread();
    const lines = [['pool', 'member', 'share', 'cut']];
    for (const p of this.pools.pools()) {
      for (const m of p.members) lines.push([p.name, m.poolId !== null ? 'pool:' + m.name : m.name, m.share, m.cut ?? '']);
    }
    download(lines, 'pools.csv');
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.pools.add(this.name());
      if (err) this.error.set(err);
      else this.name.set('');
    } finally {
      this.saving.set(false);
    }
  }
}
