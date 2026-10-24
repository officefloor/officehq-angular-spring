import { Component, effect, inject, input, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Person } from './people.service';
import { Pool, PoolsService } from './pools.service';

/** A share as typed: a percent with at most two decimals. */
const SHARE = /^\d{1,3}(\.\d{1,2})?$/;

// The sheet's pools, one row each in the order they were added, with the form by which one is added
// (a blank name, or one already on the sheet, is refused). Each row shows its members with their
// shares, its total share (a retired member's counting as 0.00) and whether it is balanced (exactly
// 100.00) and, while it is balanced and the sheet has a pot, each member's cut of the pot; and has the entry by which a person is added to it with a share (more than 0.00, at most
// 100.00, two decimals); a person already in the pool, or a bad share, is refused.
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
      @for (pool of service.pools(); track pool.id) {
        <div class="pool" [attr.data-testid]="'pool-row-' + pool.name">
          <h3>{{ pool.name }}</h3>
          <p>
            Total share <span data-testid="pool-total-share">{{ pool.totalShare }}</span>,
            balanced <span data-testid="pool-balanced">{{ pool.balanced ? 'yes' : 'no' }}</span>
          </p>
          @if (pool.members.length > 0) {
            <ul>
              @for (m of pool.members; track m.personId) {
                <li [attr.data-testid]="'pool-member-' + pool.name + '-' + m.personId">
                  {{ m.name }} <span data-testid="pool-share">{{ m.share }}</span>
                  cut <span data-testid="pool-cut">{{ m.cut ?? '' }}</span>
                </li>
              }
            </ul>
          }
          <form (submit)="addMember($event, pool)" [attr.aria-label]="'Member of ' + pool.name">
            <label [for]="'pool-member-select-' + pool.id">Person</label>
            <select
              [id]="'pool-member-select-' + pool.id"
              data-testid="pool-member-select"
              (change)="pick(pool.id, value($event))"
            >
              <option value="" [selected]="!picked()[pool.id]">Choose a person</option>
              @for (p of people(); track p.id) {
                <option [value]="p.id" [selected]="picked()[pool.id] === String(p.id)">{{ p.name }}</option>
              }
            </select>
            <label [for]="'pool-member-share-' + pool.id">Share</label>
            <input
              [id]="'pool-member-share-' + pool.id"
              data-testid="pool-member-share"
              [value]="shares()[pool.id] ?? ''"
              (input)="setShare(pool.id, value($event))"
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
  protected readonly service = inject(PoolsService);

  /** The sheet's people, any of whom can be added to a pool. */
  readonly people = input<Person[]>([]);

  protected readonly String = String;
  protected readonly name = signal('');
  protected readonly error = signal<string | null>(null);
  /** By pool id: the person chosen, the share typed and the error shown for the pool. */
  protected readonly picked = signal<Record<number, string>>({});
  protected readonly shares = signal<Record<number, string>>({});
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
      this.memberErrors.set({});
    });
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
    const personId = Number(this.picked()[pool.id] ?? '');
    const share = (this.shares()[pool.id] ?? '').trim();
    if (!personId) {
      this.setMemberError(pool.id, 'Choose a person.');
      return;
    }
    if (pool.members.some((m) => m.personId === personId)) {
      this.setMemberError(pool.id, 'That person is already in the pool.');
      return;
    }
    const amount = Number(share);
    if (!SHARE.test(share) || !(amount > 0) || amount > 100) {
      this.setMemberError(pool.id, 'Type a share more than 0.00 and at most 100.00, with at most two decimals.');
      return;
    }
    this.saving = true;
    try {
      await this.service.addMember(sheet, pool.id, personId, share);
      if (this.session.sheetId() === sheet) {
        this.picked.update((all) => ({ ...all, [pool.id]: '' }));
        this.shares.update((all) => ({ ...all, [pool.id]: '' }));
      }
    } catch {
      if (this.session.sheetId() === sheet) this.setMemberError(pool.id, 'That member could not be added.');
    } finally {
      this.saving = false;
    }
  }

  private setMemberError(poolId: number, message: string): void {
    this.memberErrors.update((all) => ({ ...all, [poolId]: message }));
  }

  private clearMemberError(poolId: number): void {
    if (this.memberErrors()[poolId]) this.memberErrors.update((all) => ({ ...all, [poolId]: null }));
  }
}
