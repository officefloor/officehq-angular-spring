import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Person } from '../../core/people';
import { Pool, PoolMember, Pools, formatCents, shareCents } from '../../core/pools';

// A pool's row: its members (people, and pools shown as pool:<name>) with their shares, the total
// and whether it is balanced (exactly 100.00), each member's cut of the pool's pot (blank unless the
// pool is balanced and has a pot: the sheet's, or its cut in the pool above when it is a member of
// one), and the entry a member is added with. A retired member's share counts as 0.00.
@Component({
  selector: 'app-pool-row',
  imports: [ReactiveFormsModule],
  host: { '[attr.data-testid]': "'pool-row-' + pool().name", role: 'listitem' },
  template: `
    <h4>{{ pool().name }}</h4>
    <p>
      Total share <span data-testid="pool-total-share">{{ totalShare() }}</span>,
      balanced <span data-testid="pool-balanced">{{ balanced() ? 'yes' : 'no' }}</span>
    </p>
    <ul>
      @for (m of pool().members; track memberKey(m)) {
        <li [attr.data-testid]="'pool-member-' + pool().name + '-' + memberKey(m)">
          {{ nameOf(m) }}: <span data-testid="pool-share">{{ m.share }}</span>,
          cut <span data-testid="pool-cut">{{ m.cut ?? '' }}</span>
          @if (isRetired(m)) { (retired) }
        </li>
      }
    </ul>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <label [for]="'pool-member-select-' + pool().id">Member</label>
      <select [id]="'pool-member-select-' + pool().id" data-testid="pool-member-select" formControlName="personId">
        <option value="">Choose a person or a pool</option>
        @for (p of people(); track p.id) {
          <option [value]="'' + p.id">{{ p.name }}</option>
        }
        @for (p of pools.list(); track p.id) {
          <option [value]="'pool:' + p.id">pool:{{ p.name }}</option>
        }
      </select>
      <label [for]="'pool-member-share-' + pool().id">Share %</label>
      <input [id]="'pool-member-share-' + pool().id" data-testid="pool-member-share" formControlName="share"
        inputmode="decimal" />
      <button type="submit" data-testid="pool-member-add">Add member</button>
      @if (error()) {
        <p role="alert" data-testid="pool-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class PoolRow {
  readonly pool = input.required<Pool>();
  /** Everyone on the sheet, retired included, as the people list now shows them. */
  readonly people = input.required<Person[]>();

  protected readonly pools = inject(Pools);
  private readonly byId = computed(() => new Map(this.people().map((p) => [p.id, p])));

  /** The members' shares added up, a retired member's counting as 0.00. */
  private readonly totalCents = computed(() =>
    this.pool().members.reduce(
      (sum, m) => (this.isRetired(m) ? sum : sum + (shareCents(m.share) ?? 0)), 0),
  );
  protected readonly totalShare = computed(() => formatCents(this.totalCents()));
  protected readonly balanced = computed(() => this.totalCents() === 10000);

  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    personId: new FormControl('', { nonNullable: true }),
    share: new FormControl('', { nonNullable: true }),
  });
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  /** The member's key in its data-testid: the person's id, or pool-<name> for a pool. */
  protected memberKey(m: PoolMember): string {
    return m.personId === null ? `pool-${m.name}` : String(m.personId);
  }

  protected nameOf(m: PoolMember): string {
    return m.personId === null ? `pool:${m.name}` : (this.byId().get(m.personId)?.name ?? m.name);
  }

  protected isRetired(m: PoolMember): boolean {
    return m.personId !== null && (this.byId().get(m.personId)?.retired ?? m.retired);
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { personId, share } = this.form.getRawValue();
    const poolId = personId.startsWith('pool:') ? Number(personId.slice(5)) : null;
    const id = Number(personId);
    if (poolId === null ? personId === '' || !this.byId().has(id) : !this.pools.list().some((p) => p.id === poolId)) {
      this.error.set('Choose a person or a pool.');
      return;
    }
    if (shareCents(share) === null) {
      this.error.set('A share is a percent with two decimals, more than 0.00 and at most 100.00.');
      return;
    }
    if (poolId === null && this.pool().members.some((m) => m.personId === id)) {
      this.error.set('That person is already in the pool.');
      return;
    }
    this.submitting = true;
    try {
      await this.pools.addMember(this.pool().id, poolId === null ? { personId: id } : { poolId }, share.trim());
      this.form.reset({ personId: '', share: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? poolId === null
            ? 'That person is already in the pool, or the sheet may not be changed.'
            : 'That pool is already a member of a pool, would contain itself, or would make a chain of '
              + 'pools more than three deep; or the sheet may not be changed.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'A viewer may not add a pool member.'
            : 'The member could not be added.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
