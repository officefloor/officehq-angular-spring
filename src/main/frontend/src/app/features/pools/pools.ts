import { Component, inject, input, signal } from '@angular/core';
import { People } from '../../core/people';
import { PoolView, Pools } from '../../core/pools';

// One pool's row: its members with their shares, its total share and whether it is balanced (exactly
// 100.00; a retired member counts 0.00), each member's cut of the sheet's pot while balanced, and a form adding a person with a share.
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
      @for (m of p.members; track m.personId) {
        <li [attr.data-testid]="'pool-member-' + p.name + '-' + m.personId">
          <span data-testid="pool-member-name">{{ m.name }}</span>
          <span data-testid="pool-share">{{ m.share }}</span>
          <span data-testid="pool-cut">{{ m.cut ?? '' }}</span>
          @if (m.retired) {
            <span data-testid="pool-member-retired">retired</span>
          }
        </li>
      }
    </ul>
    <form (submit)="add($event)">
      <label [for]="'pool-member-select-' + p.id">Person</label>
      <select
        [id]="'pool-member-select-' + p.id"
        data-testid="pool-member-select"
        (change)="personId.set(selected($event)); error.set(null)"
      >
        <option value="" [selected]="personId() === null">Choose a person</option>
        @for (person of people.people(); track person.id) {
          <option [value]="person.id" [selected]="personId() === person.id">{{ person.name }}</option>
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
  private readonly pools = inject(Pools);
  protected readonly personId = signal<number | null>(null);
  protected readonly share = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected selected(e: Event): number | null {
    const v = (e.target as HTMLSelectElement).value;
    return v === '' ? null : Number(v);
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.pools.addMember(this.pool().id, this.personId(), this.share());
      if (err) this.error.set(err);
      else {
        this.personId.set(null);
        this.share.set('');
      }
    } finally {
      this.saving.set(false);
    }
  }
}

// The sheet's pools: a form adds one (a blank name, or one already taken, is refused), and each pool's row
// shows its members and adds more.
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
      @for (p of pools.pools(); track p.id) {
        <app-pool-row [pool]="p" [attr.data-testid]="'pool-row-' + p.name" />
      }
    </section>
  `,
})
export class PoolsPanel {
  protected readonly pools = inject(Pools);
  protected readonly name = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
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
