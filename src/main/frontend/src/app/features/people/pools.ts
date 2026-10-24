import { Component, DOCUMENT, effect, inject, input, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Person } from './people.service';
import { Pool, PoolMember, PoolsService } from './pools.service';

/** A share as typed: a percent with at most two decimals. */
const SHARE = /^\d{1,3}(\.\d{1,2})?$/;

// The sheet's pools, one row each in the order they were added, with the form by which one is added
// (a blank name, or one already on the sheet, is refused). Each row shows its members with their
// shares, its total share (a retired member's counted as given) and whether it is balanced (exactly
// 100.00) and, while it is balanced and has a pot, each member's cut of the pot (none when every member is retired); and has the entry by which a person, or
// another pool (as pool:<name>), is added to it with a share (more than 0.00, at most 100.00, two decimals); a person already
// in the pool, a pool already in one, a pool that would contain itself or make a chain more than three deep, or a bad share,
// is refused. A pool that is a member of none has the sheet's pot; one that is a member of another has its cut there.
// A member may be added with a from-date and a to-date (each optional; a to-date before the from-date is refused);
// a member whose dates leave out the sheet's today counts for nothing in the pool and has no cut.
// A button downloads the pools, re-read from the store, as a CSV file: one line per member.
@Component({
  selector: 'app-pools',
  template: `
    <section aria-labelledby="pools-heading">
      <h2 id="pools-heading">Pools</h2>
      <form (submit)="submit($event)" aria-label="Pool" data-testid="pool-form">
        <label for="pool-form-name">Name</label>
        <input id="pool-form-name" data-testid="pool-form-name" [value]="name()" (input)="name.set(value($event)); error.set(null)" />
        <button type="submit" data-testid="pool-form-submit">Add pool</button>
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="pool-form-error">{{ message }}</p>
        }
      </form>
      <button type="button" data-testid="export-pools" (click)="exportPools()">Export pools (CSV)</button>
      @for (pool of service.pools(); track pool.id) {
        <div class="pool" [attr.data-testid]="'pool-row-' + pool.name">
          <h3>{{ pool.name }}</h3>
          <p>
            Total share <span data-testid="pool-total-share">{{ pool.totalShare }}</span>,
            balanced <span data-testid="pool-balanced">{{ pool.balanced ? 'yes' : 'no' }}</span>
          </p>
          @if (pool.members.length > 0) {
            <ul>
              @for (m of pool.members; track memberKey(m)) {
                <li [attr.data-testid]="'pool-member-' + pool.name + '-' + memberKey(m)">
                  {{ m.name }} <span data-testid="pool-share">{{ m.share }}</span>
                  cut <span data-testid="pool-cut">{{ m.cut ?? '' }}</span>
                </li>
              }
            </ul>
          }
          <form (submit)="addMember($event, pool)" [attr.aria-label]="'Member of ' + pool.name">
            <label [for]="'pool-member-select-' + pool.id">Member</label>
            <select
              [id]="'pool-member-select-' + pool.id"
              data-testid="pool-member-select"
              (change)="pick(pool.id, value($event))"
            >
              <option value="" [selected]="!picked()[pool.id]">Choose a person or pool</option>
              @for (p of people(); track p.id) {
                <option [value]="p.id" [selected]="picked()[pool.id] === String(p.id)">{{ p.name }}</option>
              }
              @for (other of service.pools(); track other.id) {
                @if (other.id !== pool.id) {
                  <option [value]="'pool:' + other.id" [selected]="picked()[pool.id] === 'pool:' + other.id">pool:{{ other.name }}</option>
                }
              }
            </select>
            <label [for]="'pool-member-share-' + pool.id">Share</label>
            <input
              [id]="'pool-member-share-' + pool.id"
              data-testid="pool-member-share"
              [value]="shares()[pool.id] ?? ''"
              (input)="setShare(pool.id, value($event))"
            />
            <label [for]="'pool-member-from-' + pool.id">From</label>
            <input
              type="date"
              [id]="'pool-member-from-' + pool.id"
              data-testid="pool-member-from"
              [value]="froms()[pool.id] ?? ''"
              (input)="setFrom(pool.id, value($event))"
            />
            <label [for]="'pool-member-to-' + pool.id">To</label>
            <input
              type="date"
              [id]="'pool-member-to-' + pool.id"
              data-testid="pool-member-to"
              [value]="tos()[pool.id] ?? ''"
              (input)="setTo(pool.id, value($event))"
            />
            <button type="submit" data-testid="pool-member-add">Add member</button>
            @if (memberErrors()[pool.id]; as message) {
              <p role="alert" class="error" data-testid="pool-error">{{ message }}</p>
            }
          </form>
        </div>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    h3 {
      margin: 0.5rem 0 0.25rem;
      font-size: 1rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class Pools {
  private readonly session = inject(Session);
  private readonly document = inject(DOCUMENT);
  protected readonly service = inject(PoolsService);

  /** The sheet's people, any of whom can be added to a pool. */
  readonly people = input<Person[]>([]);

  protected readonly String = String;
  protected readonly name = signal('');
  protected readonly error = signal<string | null>(null);
  /** By pool id: the person (its id) or pool (pool:<id>) chosen, the share and dates typed and the error shown for the pool. */
  protected readonly picked = signal<Record<number, string>>({});
  protected readonly shares = signal<Record<number, string>>({});
  protected readonly froms = signal<Record<number, string>>({});
  protected readonly tos = signal<Record<number, string>>({});
  protected readonly memberErrors = signal<Record<number, string | null>>({});
  private saving = false;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.name.set('');
      this.error.set(null);
      this.picked.set({});
      this.shares.set({});
      this.froms.set({});
      this.tos.set({});
      this.memberErrors.set({});
    });
  }

  /** A member's key in its pool: the person's id, or pool-<name> for a pool. */
  protected memberKey(m: PoolMember): string {
    return m.poolId !== null ? `pool-${m.name}` : String(m.personId);
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected pick(poolId: number, personId: string): void {
    this.picked.update((all) => ({ ...all, [poolId]: personId }));
    this.clearMemberError(poolId);
  }

  protected setShare(poolId: number, share: string): void {
    this.shares.update((all) => ({ ...all, [poolId]: share }));
    this.clearMemberError(poolId);
  }

  protected setFrom(poolId: number, from: string): void {
    this.froms.update((all) => ({ ...all, [poolId]: from }));
    this.clearMemberError(poolId);
  }

  protected setTo(poolId: number, to: string): void {
    this.tos.update((all) => ({ ...all, [poolId]: to }));
    this.clearMemberError(poolId);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.name().trim();
    if (name === '') {
      this.error.set('Type a name.');
      return;
    }
    if (this.service.pools().some((p) => p.name === name)) {
      this.error.set('There is already a pool by that name.');
      return;
    }
    this.saving = true;
    try {
      await this.service.add(sheet, name);
      if (this.session.sheetId() === sheet) this.name.set('');
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('There is already a pool by that name, or it could not be added.');
    } finally {
      this.saving = false;
    }
  }

  protected async addMember(event: Event, pool: Pool): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.clearMemberError(pool.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const picked = this.picked()[pool.id] ?? '';
    const memberPoolId = picked.startsWith('pool:') ? Number(picked.slice('pool:'.length)) : null;
    const personId = memberPoolId === null ? Number(picked) : null;
    const share = (this.shares()[pool.id] ?? '').trim();
    if (!memberPoolId && !personId) {
      this.setMemberError(pool.id, 'Choose a person or pool.');
      return;
    }
    if (personId && pool.members.some((m) => m.personId === personId)) {
      this.setMemberError(pool.id, 'That person is already in the pool.');
      return;
    }
    const amount = Number(share);
    if (!SHARE.test(share) || !(amount > 0) || amount > 100) {
      this.setMemberError(pool.id, 'Type a share more than 0.00 and at most 100.00, with at most two decimals.');
      return;
    }
    const from = (this.froms()[pool.id] ?? '').trim();
    const to = (this.tos()[pool.id] ?? '').trim();
    if (from !== '' && to !== '' && to < from) {
      this.setMemberError(pool.id, 'The to-date cannot be before the from-date.');
      return;
    }
    this.saving = true;
    try {
      await this.service.addMember(
        sheet,
        pool.id,
        memberPoolId ? { poolId: memberPoolId } : { personId: personId! },
        share,
        from,
        to,
      );
      if (this.session.sheetId() === sheet) {
        this.picked.update((all) => ({ ...all, [pool.id]: '' }));
        this.shares.update((all) => ({ ...all, [pool.id]: '' }));
        this.froms.update((all) => ({ ...all, [pool.id]: '' }));
        this.tos.update((all) => ({ ...all, [pool.id]: '' }));
      }
    } catch {
      if (this.session.sheetId() === sheet) {
        this.setMemberError(
          pool.id,
          memberPoolId
            ? 'That pool could not be added: it is already in a pool, contains this one, or would make a chain more than three deep.'
            : 'That member could not be added.',
        );
      }
    } finally {
      this.saving = false;
    }
  }

  /** Download the pools as a CSV file, re-read from the store first: pools in the order added, each
   *  member in the order added, a pool member as pool:<name>, and the cut as shown (empty when none). */
  protected async exportPools(): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) return;
    await this.service.refresh(sheet);
    if (this.session.sheetId() !== sheet) return;
    const lines = [['pool', 'member', 'share', 'cut']];
    for (const pool of this.service.pools()) {
      for (const m of pool.members) {
        lines.push([pool.name, m.poolId !== null ? `pool:${m.name}` : m.name, m.share, m.cut ?? '']);
      }
    }
    const text = lines.map((fields) => fields.map(csvField).join(',') + '\n').join('');
    const url = URL.createObjectURL(new Blob([text], { type: 'text/csv' }));
    const link = this.document.createElement('a');
    link.href = url;
    link.download = 'pools.csv';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url));
  }

  private setMemberError(poolId: number, message: string): void {
    this.memberErrors.update((all) => ({ ...all, [poolId]: message }));
  }

  private clearMemberError(poolId: number): void {
    if (this.memberErrors()[poolId]) this.memberErrors.update((all) => ({ ...all, [poolId]: null }));
  }
}

/** A CSV field, quoted when it needs to be. */
function csvField(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
}
