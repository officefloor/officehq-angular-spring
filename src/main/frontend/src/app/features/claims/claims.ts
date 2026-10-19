import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Rate } from '../rates/rates-api';
import { GradeFrom, gradeOn } from '../people/people-api';
import { Period, periodOf } from '../periods/periods-api';
import { SheetFloor } from '../settings/sheet-floor';
import { SheetLatePct } from '../settings/sheet-late-pct';
import { SheetPolicyDate } from '../settings/sheet-policy-date';
import { Claim, ClaimAction, ClaimsApi, Lateness, claimDue, isOverdue, roundsBy } from './claims-api';

const AMOUNT = /^-?\d{1,10}(\.\d{1,2})?$/;
/** A claim's number: the sheet-wide one, or a period's, <period name>-<n> (X-<n> in no period). */
const NUMBER = /^\S{1,255}$/;

// One person's claims, newest first, and the one form that adds a claim for them or, once a
// claim's editor is opened, saves changes to it.
@Component({
  selector: 'app-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" data-testid="claim-form">
      <label [for]="'claim-form-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-form-amount-' + personId()"
        data-testid="claim-form-amount"
        inputmode="decimal"
        formControlName="amount"
      />
      <label [for]="'claim-form-date-' + personId()">Date</label>
      <input [id]="'claim-form-date-' + personId()" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="'claim-form-note-' + personId()">Note</label>
      <input [id]="'claim-form-note-' + personId()" data-testid="claim-form-note" formControlName="note" />
      <label [for]="'claim-form-corrects-' + personId()">Corrects claim no.</label>
      <input
        [id]="'claim-form-corrects-' + personId()"
        data-testid="claim-form-corrects"
        inputmode="numeric"
        formControlName="corrects"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (editingId() !== null) {
        <button type="button" data-testid="claim-form-cancel" (click)="reset()">Cancel</button>
      }
    </form>
    @if (formError(); as e) {
      <p role="alert" data-testid="claim-form-error">{{ e }}</p>
    }
    @if (claims().length > 0) {
      <table data-testid="claim-list">
        <caption class="visually-hidden">Claims by {{ personName() }}</caption>
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th>
            <th scope="col">No.</th>
            <th scope="col">Amount</th>
            <th scope="col">Date</th>
            <th scope="col">Note</th>
            <th scope="col">Period</th>
            <th scope="col">Due</th>
            <th scope="col">Due by</th>
            <th scope="col">Overdue</th>
            <th scope="col">Stated</th>
            <th scope="col">Status</th>
            <th scope="col">Opened by</th>
            <th scope="col">Corrects</th>
            <th scope="col">Status log</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (c of claims(); track c.id) {
            <tr [attr.data-testid]="'claim-row-' + c.id">
              <td>
                <input
                  type="checkbox"
                  data-testid="claim-select"
                  [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date"
                  [checked]="selected().has(c.id)"
                  (change)="selectedChange.emit({ id: c.id, selected: $any($event.target).checked })"
                />
              </td>
              <td data-testid="claim-number">{{ c.number ?? '' }}</td>
              <td data-testid="claim-amount">{{ c.amount }}</td>
              <td data-testid="claim-date">{{ c.date }}</td>
              <td data-testid="claim-note">{{ c.note }}</td>
              <td data-testid="claim-period">{{ periodName(c) }}</td>
              <td data-testid="claim-due">{{ due(c) }}</td>
              <td data-testid="claim-due-by">{{ c.dueBy }}</td>
              <td data-testid="claim-overdue">{{ overdue(c) ? 'yes' : 'no' }}</td>
              <td data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</td>
              <td data-testid="claim-status">{{ c.status }}</td>
              <td data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</td>
              <td data-testid="claim-corrects">{{ c.corrects ?? '' }}</td>
              <td>
                <ol data-testid="claim-log">
                  @for (l of c.statusLog; track $index) {
                    <li data-testid="claim-log-line">{{ l.status }} by {{ l.by }} on {{ l.on }} due={{ l.due }}</li>
                  }
                </ol>
              </td>
              <td>
                <button
                  type="button"
                  data-testid="claim-edit"
                  [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
                  (click)="edit(c)"
                >
                  Edit
                </button>
                @for (a of actions; track a.action) {
                  <button
                    type="button"
                    [attr.data-testid]="'claim-' + a.action"
                    [attr.aria-label]="a.label + ' claim of ' + c.amount + ' on ' + c.date"
                    (click)="changeStatus(c, a.action)"
                  >
                    {{ a.label }}
                  </button>
                }
                <label class="visually-hidden" [for]="'claim-reject-reason-' + c.id">Reason to reject</label>
                <input
                  [id]="'claim-reject-reason-' + c.id"
                  data-testid="claim-reject-reason"
                  placeholder="Reason"
                  [value]="rejectReasons().get(c.id) ?? ''"
                  (input)="reasonChange(c.id, $any($event.target).value)"
                />
                <button
                  type="button"
                  data-testid="claim-reject"
                  [attr.aria-label]="'Reject claim of ' + c.amount + ' on ' + c.date"
                  (click)="reject(c)"
                >
                  Reject
                </button>
                <label class="visually-hidden" [for]="'claim-reassign-to-' + c.id">Move to</label>
                <select
                  [id]="'claim-reassign-to-' + c.id"
                  data-testid="claim-reassign-to"
                  (change)="reassignChange(c.id, $any($event.target).value)"
                >
                  <option value="" [selected]="!reassignTo().has(c.id)">Move to…</option>
                  @for (o of others(); track o.id) {
                    <option [value]="o.id" [selected]="reassignTo().get(c.id) === o.id">{{ o.name }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="claim-reassign"
                  [attr.aria-label]="'Move claim of ' + c.amount + ' on ' + c.date"
                  (click)="reassign(c)"
                >
                  Move
                </button>
                @if (claimError()?.id === c.id) {
                  <p role="alert" data-testid="claim-error">{{ claimError()?.message }}</p>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class Claims {
  private readonly session = inject(Session);
  private readonly api = inject(ClaimsApi);
  private readonly floor = inject(SheetFloor);
  private readonly latePct = inject(SheetLatePct);
  private readonly policyDate = inject(SheetPolicyDate);
  /** What a late charge goes by: the sheet's today and late percent; and the policy date rounding goes by. */
  private readonly late = computed<Lateness>(() => ({
    today: this.session.today(),
    latePct: this.latePct.value(),
    policyDate: this.policyDate.value() ?? this.session.today(),
  }));

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** This person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** This person's dated grades, and the sheet's rates. */
  readonly grades = input.required<readonly GradeFrom[]>();
  readonly rates = input<readonly Rate[]>([]);
  /** The sheet's periods. */
  readonly periods = input<readonly Period[]>([]);
  /** The people on the sheet a claim can be moved to, by id and name. */
  readonly people = input<readonly { id: number; name: string }[]>([]);
  /** The ids of the claims selected to act on together. */
  readonly selected = input<ReadonlySet<number>>(new Set());
  /** A claim was added or saved. */
  readonly saved = output<Claim>();
  /** A claim's select box was ticked or cleared. */
  readonly selectedChange = output<{ id: number; selected: boolean }>();

  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'approve', label: 'Approve' },
    { action: 'void', label: 'Void' },
  ];
  protected readonly editingId = signal<number | null>(null);
  /** The error of the last action taken on a claim, shown on that claim's row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  protected readonly formError = signal<string | null>(null);
  /** The person picked to move each claim to, by claim id. */
  protected readonly reassignTo = signal<ReadonlyMap<number, number>>(new Map());
  /** The people a claim of this person's can be moved to: everyone else on the sheet. */
  protected readonly others = computed(() => this.people().filter((p) => p.id !== this.personId()));
  /** The reason typed to reject each PENDING claim, by id. */
  protected readonly rejectReasons = signal<ReadonlyMap<number, string>>(new Map());
  /** Whether a submit is waiting on the server; another submit is ignored until it answers. */
  private submitting = false;
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
    corrects: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.formError.set(null));
    // A new claim is dated the sheet's today unless another date is picked.
    effect(() => {
      const today = this.session.today() ?? '';
      untracked(() => {
        if (this.editingId() === null && !this.form.controls.date.dirty) {
          this.form.controls.date.setValue(today, { emitEvent: false });
        }
      });
    });
  }

  /** A settled claim shows the due it was settled at; any other is due at the rate the grade its person has on
   * the claim's date has now in force on that date, and at least the sheet's floor, plus its late charge when
   * overdue. */
  protected due(claim: Claim): string {
    return claimDue(claim, this.rates(), gradeOn(this.grades(), claim.date), this.floor.value(), this.late(),
      roundsBy(claim, this.claims()));
  }

  /** Whether the claim is OPEN or PENDING and its due-by is before the sheet's today. */
  protected overdue(claim: Claim): boolean {
    return isOverdue(claim, this.session.today());
  }

  /** The name of the period the claim's date falls in; empty when it falls in none. */
  protected periodName(claim: Claim): string {
    return periodOf(this.periods(), claim.date)?.name ?? '';
  }

  protected changeStatus(claim: Claim, action: ClaimAction): void {
    this.claimError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.api.changeStatus(sheetId, claim.id, action).subscribe({
      next: (c) => {
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 403
              ? `You opened that claim, so someone else must ${action === 'approve' ? 'approve' : 'settle'} it.`
              : e.status === 422
              ? 'Only a steward can approve a pending claim.'
              : e.status === 409 && action === 'approve'
              ? 'Only a pending claim can be approved.'
              : e.status === 423
              ? 'That claim is dated in a closed period.'
              : e.status === 409
              ? 'That claim\'s status does not allow that change.'
              : e.status === 404
                ? 'That claim is no longer on this sheet.'
                : 'Could not change that claim\'s status.',
        }),
    });
  }

  protected reassignChange(id: number, personId: string): void {
    if (this.claimError()?.id === id) this.claimError.set(null);
    this.reassignTo.update((m) => {
      const next = new Map(m);
      if (personId === '') next.delete(id);
      else next.set(id, Number(personId));
      return next;
    });
  }

  /** Move a claim to the person picked on its row; it keeps its number and date but is live again. */
  protected reassign(claim: Claim): void {
    this.claimError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const personId = this.reassignTo().get(claim.id);
    if (personId === undefined) {
      this.claimError.set({ id: claim.id, message: 'Pick the person to move that claim to.' });
      return;
    }
    this.api.reassign(sheetId, claim.id, personId).subscribe({
      next: (c) => {
        this.reassignTo.update((m) => {
          const next = new Map(m);
          next.delete(claim.id);
          return next;
        });
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 422
              ? 'Only a steward can move a claim.'
              : e.status === 423
              ? 'That claim is dated in a closed period.'
              : e.status === 409
              ? 'Only a draft, open or pending claim can be moved, and never a correction.'
              : e.status === 410
              ? 'That person is retired before the claim\'s date.'
              : e.status === 400
              ? 'Pick another person to move that claim to.'
              : e.status === 404
                ? 'That claim or person is no longer on this sheet.'
                : 'Could not move that claim.',
        }),
    });
  }

  protected reasonChange(id: number, reason: string): void {
    if (this.claimError()?.id === id) this.claimError.set(null);
    this.rejectReasons.update((m) => new Map(m).set(id, reason));
  }

  /** Reject a PENDING claim with the reason typed on its row: it is OPEN again. */
  protected reject(claim: Claim): void {
    this.claimError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const reason = (this.rejectReasons().get(claim.id) ?? '').trim();
    if (reason === '') {
      this.claimError.set({ id: claim.id, message: 'Type a reason to reject that claim.' });
      return;
    }
    this.api.reject(sheetId, claim.id, reason).subscribe({
      next: (c) => {
        this.rejectReasons.update((m) => {
          const next = new Map(m);
          next.delete(claim.id);
          return next;
        });
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 422
              ? 'Only a steward can reject a pending claim.'
              : e.status === 423
              ? 'That claim is dated in a closed period.'
              : e.status === 409
              ? 'Only a pending claim can be rejected.'
              : e.status === 404
                ? 'That claim is no longer on this sheet.'
                : e.status === 400
                  ? 'Type a reason on one line to reject that claim.'
                  : 'Could not reject that claim.',
        }),
    });
  }

  protected edit(claim: Claim): void {
    this.claimError.set(null);
    if (claim.status !== 'DRAFT') {
      this.claimError.set({
        id: claim.id,
        message: 'Only a draft claim can be edited. To change it, void it and add a new one.',
      });
      return;
    }
    if (periodOf(this.periods(), claim.date)?.closed) {
      this.claimError.set({ id: claim.id, message: 'That claim is dated in a closed period.' });
      return;
    }
    this.editingId.set(claim.id);
    this.form.setValue(
      { amount: claim.amount, date: claim.date, note: claim.note, corrects: claim.corrects ?? '' },
      { emitEvent: false },
    );
    this.formError.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset(
      { amount: '', date: this.session.today() ?? '', note: '', corrects: '' },
      { emitEvent: false },
    );
    this.formError.set(null);
  }

  protected submit(): void {
    if (this.submitting) return;
    this.formError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.formError.set('Load a sheet first.');
      return;
    }
    const value = this.form.getRawValue();
    const amount = value.amount.trim();
    if (!AMOUNT.test(amount)) {
      this.formError.set('Type an amount with up to two decimals; a negative one is a correction.');
      return;
    }
    const corrects = value.corrects.trim();
    if (corrects !== '' && !NUMBER.test(corrects)) {
      this.formError.set('Type the number of the settled claim this corrects.');
      return;
    }
    const form = {
      amount,
      date: value.date || null,
      note: value.note.trim(),
      corrects: corrects === '' ? null : corrects,
    };
    const id = this.editingId();
    const request = id === null ? this.api.add(sheetId, this.personId(), form) : this.api.update(sheetId, id, form);
    this.submitting = true;
    request.subscribe({
      next: (c) => {
        this.submitting = false;
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
        this.reset();
      },
      error: (e: HttpErrorResponse) => {
        this.submitting = false;
        this.formError.set(
          e.status === 400
            ? 'A negative amount is a correction: name the number of a settled claim of this person it corrects.'
            : e.status === 404
            ? 'That person or claim is no longer on this sheet.'
            : e.status === 422
              ? 'That claim is dated after its person is retired.'
              : e.status === 423
              ? 'That claim is dated in a closed period.'
              : e.status === 409
                ? 'Only a draft claim can be edited. To change it, void it and add a new one.'
                : 'Could not save that claim.',
        );
      },
    });
  }
}
