import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Person } from '../../core/people';
import { Pool, Pools, formatCents, shareCents } from '../../core/pools';

// A pool's row: its members with their shares, the total and whether it is balanced (exactly
// 100.00), and the entry a member is added with. A retired member's share counts as 0.00.
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
      @for (m of pool().members; track m.personId) {
        <li [attr.data-testid]="'pool-member-' + pool().name + '-' + m.personId">
          {{ nameOf(m.personId, m.name) }}: <span data-testid="pool-share">{{ m.share }}</span>
          @if (isRetired(m.personId, m.retired)) { (retired) }
        </li>
      }
    </ul>
    <form [formGroup]="form" (ngSubmit)="submit()">
      <label [for]="'pool-member-select-' + pool().id">Member</label>
      <select [id]="'pool-member-select-' + pool().id" data-testid="pool-member-select" formControlName="personId">
        <option value="">Choose a person</option>
        @for (p of people(); track p.id) {
          <option [value]="'' + p.id">{{ p.name }}</option>
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

  private readonly pools = inject(Pools);
  private readonly byId = computed(() => new Map(this.people().map((p) => [p.id, p])));

  /** The members' shares added up, a retired member's counting as 0.00. */
  private readonly totalCents = computed(() =>
    this.pool().members.reduce(
      (sum, m) => (this.isRetired(m.personId, m.retired) ? sum : sum + (shareCents(m.share) ?? 0)), 0),
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

  protected nameOf(personId: number, name: string): string {
    return this.byId().get(personId)?.name ?? name;
  }

  protected isRetired(personId: number, retired: boolean): boolean {
    return this.byId().get(personId)?.retired ?? retired;
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { personId, share } = this.form.getRawValue();
    const id = Number(personId);
    if (personId === '' || !this.byId().has(id)) {
      this.error.set('Choose a person.');
      return;
    }
    if (shareCents(share) === null) {
      this.error.set('A share is a percent with two decimals, more than 0.00 and at most 100.00.');
      return;
    }
    if (this.pool().members.some((m) => m.personId === id)) {
      this.error.set('That person is already in the pool.');
      return;
    }
    this.submitting = true;
    try {
      await this.pools.addMember(this.pool().id, id, share.trim());
      this.form.reset({ personId: '', share: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That person is already in the pool, or the sheet may not be changed.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'A viewer may not add a pool member.'
            : 'The member could not be added.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
