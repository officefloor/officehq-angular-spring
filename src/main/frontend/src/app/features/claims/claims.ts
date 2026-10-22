import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { AMOUNT, CLAIM_NUMBER, ClaimAction, ClaimEditors, ClaimView, ClaimsApi, cents, tagsText, deferred, dueBy, money, overdue, roundsHalfUp } from '../../core/claims';
import { GradeView, gradeOn } from '../../core/people';
import { PeriodView, periodOn } from '../../core/periods';
import { NO_RATE, RateView, claimDueCents } from '../../core/rates';
import { Session, graceOn, valueOn } from '../../core/session';
import { Templates, TemplatesApi } from '../../core/templates';
import { VoidReasons } from '../../core/void-reasons';

// A person's claims: listed newest first, added and edited through one form on the person's row (an
// open editor locks its claim for other users until it is saved or cancelled, and stays open across
// loads of the sheet),
// moved through their statuses (open, settle, approve, reject with a reason, void, re-settle) from each
// claim's row, deferred there until a date, split there into two, re-dated there, and reassigned there to another person.
const PAST: Record<ClaimAction, string> = {
  open: 'opened',
  settle: 'settled',
  approve: 'approved',
  reject: 'rejected',
  void: 'voided',
  resettle: 're-settled',
  delete: 'deleted',
  restore: 'restored',
};

@Component({
  selector: 'app-claims',
  template: `
    <form (submit)="save($event)" [attr.aria-label]="(editing() === null ? 'Add a claim for ' : 'Edit a claim of ') + personName()">
      <label [for]="'claim-form-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-form-amount-' + personId()"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); typed()"
      />
      <label [for]="'claim-form-date-' + personId()">Date</label>
      <input
        [id]="'claim-form-date-' + personId()"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set(value($event)); typed()"
      />
      <label [for]="'claim-form-note-' + personId()">Note</label>
      <input
        [id]="'claim-form-note-' + personId()"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); typed()"
      />
      <label [for]="'claim-form-corrects-' + personId()">Corrects claim no.</label>
      <input
        [id]="'claim-form-corrects-' + personId()"
        data-testid="claim-form-corrects"
        inputmode="numeric"
        [value]="corrects()"
        (input)="corrects.set(value($event)); typed()"
      />
      <label [for]="'claim-form-ref-' + personId()">Reference</label>
      <input
        [id]="'claim-form-ref-' + personId()"
        data-testid="claim-form-ref"
        [value]="ref()"
        (input)="ref.set(value($event)); typed()"
      />
      <label [for]="'claim-form-tags-' + personId()">Tags (comma separated)</label>
      <input
        [id]="'claim-form-tags-' + personId()"
        data-testid="claim-form-tags"
        [value]="tags()"
        (input)="tags.set(value($event)); typed()"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editing() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (editing() !== null) {
        <button type="button" data-testid="claim-form-cancel" (click)="cancelEditor()">Cancel</button>
      }
      <label [for]="'claim-template-select-' + personId()">Template</label>
      <select
        [id]="'claim-template-select-' + personId()"
        data-testid="claim-template-select"
        (change)="template.set(value($event)); error.set(null)"
      >
        <option value="" [selected]="template() === ''">Choose a template</option>
        @for (t of templates.list(); track t.id) {
          <option [value]="t.name" [selected]="template() === t.name">{{ t.name }}</option>
        }
      </select>
      <button type="button" data-testid="claim-template-add" (click)="addFromTemplate()">Add claim from template</button>
      @if (error(); as message) {
        <p role="alert" data-testid="claim-form-error">{{ message }}</p>
      }
    </form>
    <ul [attr.aria-label]="'Claims of ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input
            type="checkbox"
            data-testid="claim-select"
            [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date"
            [checked]="selected().has(c.id)"
            (change)="selectedChange.emit({ id: c.id, on: checked($event) })"
          />
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Ref <span data-testid="claim-ref">{{ c.ref ?? '' }}</span></span>
          <span>Tags <span data-testid="claim-tags">{{ tagsOf(c) }}</span></span>
          <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
          <span>Due by <span data-testid="claim-due-by">{{ dueByOf(c) }}</span></span>
          <span>Overdue <span data-testid="claim-overdue">{{ overdueOf(c) ? 'yes' : 'no' }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periodOf(c) }}</span></span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span>Batch <span data-testid="claim-batch">{{ c.settleBatch ?? '' }}</span></span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Deferred <span data-testid="claim-deferred">{{ deferredOf(c) ? 'yes' : 'no' }}</span></span>
          <span>Deferred until <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span></span>
          <span>Proofs <span data-testid="claim-proofs">{{ c.proofs }}</span></span>
          <span>Split from <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span></span>
          <span>Late entry <span data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' }}</span></span>
          <span>Reminded <span data-testid="claim-reminded">{{ c.remindedOn ?? '' }}</span></span>
          <ol [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
            @for (line of c.log; track $index) {
              <li data-testid="claim-log-line">{{ line.status }} by {{ line.by }} on {{ line.on }} due={{ line.due }}</li>
            }
          </ol>
          <ol [attr.aria-label]="'Comments on claim of ' + c.amount + ' on ' + c.date">
            @for (comment of c.comments ?? []; track $index) {
              <li data-testid="claim-comments-line">{{ comment.by }} on {{ comment.on }}: {{ comment.text }}</li>
            }
          </ol>
          <label [for]="'claim-comment-text-' + c.id">Comment</label>
          <input
            [id]="'claim-comment-text-' + c.id"
            data-testid="claim-comment-text"
            [value]="commentTexts()[c.id] ?? ''"
            (input)="setCommentText(c.id, value($event))"
          />
          <button
            type="button"
            data-testid="claim-comment-add"
            [attr.aria-label]="'Add a comment to claim of ' + c.amount + ' on ' + c.date"
            (click)="addComment(c)"
          >
            Add comment
          </button>
          <button
            type="button"
            data-testid="claim-edit"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
            (click)="open(c)"
          >
            Edit
          </button>
          <label [for]="'claim-inline-amount-' + c.id">New amount</label>
          <input
            [id]="'claim-inline-amount-' + c.id"
            data-testid="claim-inline-amount"
            inputmode="decimal"
            [value]="inlineAmounts()[c.id] ?? ''"
            (input)="setInlineAmount(c.id, value($event))"
          />
          <button
            type="button"
            data-testid="claim-inline-save"
            [attr.aria-label]="'Save the new amount of claim of ' + c.amount + ' on ' + c.date"
            (click)="saveInline(c)"
          >
            Save amount
          </button>
          <button
            type="button"
            data-testid="claim-cancel"
            [attr.aria-label]="'Cancel editing claim of ' + c.amount + ' on ' + c.date"
            (click)="cancel(c)"
          >
            Cancel edit
          </button>
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input
            [id]="'claim-reject-reason-' + c.id"
            data-testid="claim-reject-reason"
            [value]="rejectReasons()[c.id] ?? ''"
            (input)="setRejectReason(c.id, value($event))"
          />
          <label [for]="'claim-void-reason-' + c.id">Void reason</label>
          <select
            [id]="'claim-void-reason-' + c.id"
            data-testid="claim-void-reason"
            (change)="setVoidReason(c.id, value($event))"
          >
            <option value="" [selected]="!voidReasonsChosen()[c.id]">No reason</option>
            @for (r of voidReasons.list(); track r.id) {
              <option [value]="r.reason" [selected]="voidReasonsChosen()[c.id] === r.reason">{{ r.reason }}</option>
            }
          </select>
          @for (a of actions; track a.action) {
            <button
              type="button"
              [attr.data-testid]="'claim-' + a.action"
              [attr.aria-label]="a.label + ' claim of ' + c.amount + ' on ' + c.date"
              (click)="act(c, a.action)"
            >
              {{ a.label }}
            </button>
          }
          <button
            type="button"
            data-testid="claim-proof"
            [attr.aria-label]="'Add a proof to claim of ' + c.amount + ' on ' + c.date"
            (click)="addProof(c)"
          >
            Add proof
          </button>
          <button
            type="button"
            data-testid="claim-remind"
            [attr.aria-label]="'Remind claim of ' + c.amount + ' on ' + c.date"
            (click)="remind(c)"
          >
            Remind
          </button>
          <label [for]="'claim-defer-date-' + c.id">Defer until</label>
          <input
            [id]="'claim-defer-date-' + c.id"
            type="date"
            data-testid="claim-defer-date"
            [value]="deferDates()[c.id] ?? ''"
            (input)="setDeferDate(c.id, value($event))"
          />
          <button
            type="button"
            data-testid="claim-defer"
            [attr.aria-label]="'Defer claim of ' + c.amount + ' on ' + c.date"
            (click)="defer(c)"
          >
            Defer
          </button>
          <label [for]="'claim-date-new-' + c.id">New date</label>
          <input
            [id]="'claim-date-new-' + c.id"
            type="date"
            data-testid="claim-date-new"
            [value]="newDates()[c.id] ?? ''"
            (input)="setNewDate(c.id, value($event))"
          />
          <button
            type="button"
            data-testid="claim-redate"
            [attr.aria-label]="'Re-date claim of ' + c.amount + ' on ' + c.date"
            (click)="redate(c)"
          >
            Re-date
          </button>
          <label [for]="'claim-split-amount-' + c.id">Split off amount</label>
          <input
            [id]="'claim-split-amount-' + c.id"
            data-testid="claim-split-amount"
            inputmode="decimal"
            [value]="splitAmounts()[c.id] ?? ''"
            (input)="setSplitAmount(c.id, value($event))"
          />
          <button
            type="button"
            data-testid="claim-split"
            [attr.aria-label]="'Split claim of ' + c.amount + ' on ' + c.date"
            (click)="split(c)"
          >
            Split
          </button>
          <label [for]="'claim-reassign-to-' + c.id">Reassign to</label>
          <select
            [id]="'claim-reassign-to-' + c.id"
            data-testid="claim-reassign-to"
            (change)="setReassignTo(c.id, value($event))"
          >
            <option value="">Choose a person</option>
            @for (other of others(); track other.id) {
              <option [value]="other.id" [selected]="reassignTo()[c.id] === other.id">{{ other.name }}</option>
            }
          </select>
          <button
            type="button"
            data-testid="claim-reassign"
            [attr.aria-label]="'Reassign claim of ' + c.amount + ' on ' + c.date"
            (click)="reassign(c)"
          >
            Reassign
          </button>
          @if (claimError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ claimError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class Claims {
  private readonly session = inject(Session);
  private readonly api = inject(ClaimsApi);
  private readonly editors = inject(ClaimEditors);
  protected readonly voidReasons = inject(VoidReasons);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's claims, newest first. */
  readonly claims = input.required<ClaimView[]>();
  /** The person's grades by the date each is in force from; each claim uses the one on its date. */
  readonly grades = input.required<GradeView[]>();
  /** The sheet's rates; each claim uses the one its person's grade has in force on its date. */
  readonly rates = input.required<RateView[]>();
  /** The rates of the sheet's ancestors, nearest first: a grade with no rate in force falls back to them. */
  readonly inherited = input<RateView[][]>([]);
  /** The sheet's periods; each claim belongs to the one whose dates contain its due-by, or to none. */
  readonly periods = input.required<PeriodView[]>();
  /** The people on the sheet, any of whom (but this one) a claim may be reassigned to. */
  readonly people = input.required<{ id: number; name: string }[]>();
  /** The date the person is retired from; null when they are not retired. */
  readonly retiredFrom = input<string | null>(null);
  /** The ids of the claims selected for a bulk action. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** A claim's checkbox ticked (on) or cleared. */
  readonly selectedChange = output<{ id: number; on: boolean }>();
  /** A claim added or saved through the form. */
  readonly saved = output<ClaimView>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  /** The number of the SETTLED claim a correction corrects; empty on any other claim. */
  protected readonly corrects = signal('');
  /** The claim's external reference; empty for none. */
  protected readonly ref = signal('');
  /** The claim's tags, comma separated; empty for none. */
  protected readonly tags = signal('');
  /** Whose form this is: the signed-in user's, on the sheet, for the person. */
  private readonly editorKey = computed(() => `${this.session.user()}/${this.session.sheet()}/${this.personId()}`);
  /** The id of the claim open in the form, or null when the form adds a new claim. */
  protected readonly editing = computed(() => this.editors.of(this.editorKey()));
  /** The form was typed in since the edit button was last pressed. */
  private typedSinceOpen = false;
  /** The editor left open has been put back in the form once this person's claims arrived. */
  private restored = false;
  protected readonly error = signal<string | null>(null);
  protected readonly templates = inject(Templates);
  private readonly templatesApi = inject(TemplatesApi);
  /** The name of the template chosen to add a claim from; empty when none is chosen. */
  protected readonly template = signal('');
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;
  /** The refusal of the last action taken on a claim's row, shown on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'approve', label: 'Approve' },
    { action: 'reject', label: 'Reject' },
    { action: 'void', label: 'Void' },
    { action: 'resettle', label: 'Re-settle' },
    { action: 'delete', label: 'Delete' },
    { action: 'restore', label: 'Restore' },
  ];
  /** The people a claim of this person can be reassigned to. */
  protected readonly others = computed(() => this.people().filter((p) => p.id !== this.personId()));
  /** The person chosen on each claim's row to reassign it to, by claim id. */
  protected readonly reassignTo = signal<Record<number, number>>({});
  /** The date entered on each claim's row to defer it until, by claim id. */
  protected readonly deferDates = signal<Record<number, string>>({});
  /** The new date entered on each claim's row to re-date it to, by claim id. */
  protected readonly newDates = signal<Record<number, string>>({});
  /** The amount typed on each claim's row to split off it, by claim id. */
  protected readonly splitAmounts = signal<Record<number, string>>({});
  /** The comment typed on each claim's row, by claim id. */
  protected readonly commentTexts = signal<Record<number, string>>({});
  /** The amount typed on each DRAFT claim's row to change it to, by claim id. */
  protected readonly inlineAmounts = signal<Record<number, string>>({});
  /** The reason typed on each claim's row for rejecting it, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});
  /** The reason chosen from the sheet's list on each claim's row for voiding it, by claim id. */
  protected readonly voidReasonsChosen = signal<Record<number, string>>({});

  constructor() {
    // Put back the editor left open on this person's form (or, failing that, the one whose lock this
    // user holds) once their claims are shown.
    effect(() => {
      const claims = this.claims();
      if (this.restored || claims.length === 0) return;
      untracked(() => {
        this.restored = true;
        const me = this.session.user()?.trim();
        const id = this.editing() ?? claims.find((c) => c.status === 'DRAFT' && c.lockedBy === me)?.id ?? null;
        const claim = claims.find((c) => c.id === id && c.status === 'DRAFT');
        this.setEditing(claim?.id ?? null);
        if (claim) this.fill(claim);
      });
    });
    // An editor whose claim has left this person's DRAFT claims (reassigned, opened or gone) is closed:
    // the form goes back to adding a claim.
    effect(() => {
      const claims = this.claims();
      untracked(() => {
        const id = this.editing();
        if (this.restored && id !== null && !claims.some((c) => c.id === id && c.status === 'DRAFT')) this.reset();
      });
    });
  }

  /**
   * What is due on a claim: the due fixed when it was settled, or else the greater of the floor in
   * force on the claim's date and its figure — the rate figure its person's last statement bound it to,
   * or else its amount at the rate the person's grade has in force on the claim's date (n/a when it is
   * dated before every rate) — with its late charge (at the late percent in force on each 30-day
   * boundary) on top while it is overdue.
   */
  protected dueOf(claim: ClaimView): string {
    const late = { history: this.session.latePctHistory(), today: this.session.today() ?? '', grace: this.graceOf(claim) };
    const floor = cents(valueOn(this.session.floorHistory(), claim.date, '1.00'));
    const halfUp = roundsHalfUp(claim, this.claims(), this.session.policyDate(), late.today);
    const figure = claimDueCents(claim, this.rates(), gradeOn(this.grades(), claim.date), floor, late, halfUp, this.inherited());
    return figure === null ? NO_RATE : money(figure);
  }

  /** A claim's due-by, fixed when it was added. */
  protected dueByOf(claim: ClaimView): string {
    return dueBy(claim);
  }

  /** Whether a claim is OPEN and due by a date (plus its grace days) before the sheet's today. */
  protected overdueOf(claim: ClaimView): boolean {
    return overdue(claim, this.session.today() ?? '', this.graceOf(claim));
  }

  /** A claim's grace days: those the person's grade in force on its date has in force then. */
  private graceOf(claim: ClaimView): number {
    return graceOn(this.session.graceHistory(), gradeOn(this.grades(), claim.date), claim.date);
  }

  /** Whether a claim is deferred on the sheet's today. */
  protected deferredOf(claim: ClaimView): boolean {
    return deferred(claim, this.session.today() ?? '');
  }

  /** The name of the period a claim's due-by falls in; empty when it falls in none. */
  protected periodOf(claim: ClaimView): string {
    return periodOn(this.periods(), claim.dueBy)?.name ?? '';
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /**
   * Open a claim's editor: the claim is locked for other users and the form takes its values. Only a
   * DRAFT claim can be edited; any other is refused with an error on its row (to change an OPEN claim,
   * void it and add a new one), as is one due by in a closed period or dated after its person's
   * retirement, all taking no lock. A claim
   * another user's editor holds is refused with an error on its row, the form left on it to save once
   * the lock is released; a viewer's edit is refused like any of their actions.
   */
  protected async open(claim: ClaimView): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    if (claim.status !== 'DRAFT') {
      this.reset();
      this.claimError.set({
        id: claim.id,
        message: claim.status === 'DELETED'
          ? 'A DELETED claim cannot be edited; restore it first.'
          : `A ${claim.status} claim cannot be edited; void it and add a new one.`,
      });
      return;
    }
    const period = periodOn(this.periods(), claim.dueBy);
    if (period?.closed) {
      this.reset();
      this.claimError.set({ id: claim.id, message: `Period ${period.name} is closed, so its claims cannot be edited.` });
      return;
    }
    const retired = this.retiredFrom();
    if (retired !== null && claim.date > retired) {
      this.reset();
      this.claimError.set({
        id: claim.id,
        message: `${this.personName()} is retired from ${retired}, so a claim dated after it cannot be edited.`,
      });
      return;
    }
    const me = this.session.user()?.trim();
    if (claim.lockedBy !== null && claim.lockedBy !== me) {
      this.setEditing(claim.id);
      this.claimError.set({ id: claim.id, message: this.lockedMessage(claim.lockedBy) });
      return;
    }
    const previous = this.editing();
    // the form is the claim's from the press on, so a save typed before the lock answers edits it
    this.setEditing(claim.id);
    this.typedSinceOpen = false;
    // the form shows the claim as listed at once, so a field typed before the lock answers keeps the rest
    this.fill(claim);
    try {
      const locked = await this.api.lock(sheet, claim.id);
      this.saved.emit(locked);
      if (previous !== null && previous !== claim.id) void this.release(sheet, previous);
      if (this.editing() === claim.id && !this.typedSinceOpen) this.fill(locked);
    } catch (e) {
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      if (status !== 423 && this.editing() === claim.id) this.setEditing(previous);
      this.claimError.set({
        id: claim.id,
        message: status === 423
          ? this.lockedMessage(null)
          : status === 403
            ? 'Your role is not allowed to edit a claim.'
            : 'The claim cannot be edited.',
      });
    }
  }

  /**
   * Cancel the editor of a claim: the form goes back to adding a claim and this user's lock on the
   * claim is released.
   */
  protected async cancel(claim: ClaimView): Promise<void> {
    this.claimError.set(null);
    if (this.editing() === claim.id) this.reset();
    const sheet = this.session.sheet();
    if (sheet) await this.release(sheet, claim.id);
  }

  /** The form's own cancel: the editor open in it is cancelled. */
  protected cancelEditor(): void {
    const id = this.editing();
    const claim = this.claims().find((c) => c.id === id);
    if (claim) void this.cancel(claim);
    else this.reset();
  }

  /** Release this user's lock on a claim; one that cannot be released is left to the sheet's next today. */
  private async release(sheet: string, id: number): Promise<void> {
    try {
      this.saved.emit(await this.api.unlock(sheet, id));
    } catch {
      // nothing of this user's to release
    }
  }

  private lockedMessage(by: string | null): string {
    return `The claim is being edited by ${by ?? 'another user'}, so it cannot be edited until they save or cancel.`;
  }

  private setEditing(id: number | null): void {
    this.editors.set(this.editorKey(), id);
  }

  /** The form takes a claim's values. */
  private fill(claim: ClaimView): void {
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.corrects.set(claim.corrects ?? '');
    this.ref.set(claim.ref ?? '');
    this.tags.set(tagsText(claim));
    this.error.set(null);
  }

  /** A claim's tags as it shows them (see tagsText). */
  protected tagsOf(claim: ClaimView): string {
    return tagsText(claim);
  }

  /** A field of the form was typed in: it clears the form's error. */
  protected typed(): void {
    this.typedSinceOpen = true;
    this.error.set(null);
  }

  /** Type the reason for rejecting a claim; it clears any error left on the claim's row. */
  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((reasons) => ({ ...reasons, [id]: reason }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** Enter the date to defer a claim until; it clears any error left on the claim's row. */
  protected setVoidReason(id: number, reason: string): void {
    this.voidReasonsChosen.update((reasons) => ({ ...reasons, [id]: reason }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  protected setDeferDate(id: number, date: string): void {
    this.deferDates.update((dates) => ({ ...dates, [id]: date }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /**
   * Defer a claim until the date entered on its row; refused with an error on its row when no date is
   * entered, when the claim is not OPEN, when the date is not after the sheet's today, or when the
   * claim is due by in a closed period.
   */
  protected async defer(claim: ClaimView): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const until = (this.deferDates()[claim.id] ?? '').trim();
    if (until === '') {
      this.claimError.set({ id: claim.id, message: 'Enter a date to defer the claim until.' });
      return;
    }
    try {
      this.saved.emit(await this.api.defer(sheet, claim.id, until));
    } catch {
      const today = this.session.today() ?? '';
      const period = periodOn(this.periods(), claim.dueBy);
      this.claimError.set({
        id: claim.id,
        message: claim.status !== 'OPEN'
          ? `A ${claim.status} claim cannot be deferred; only an OPEN one can.`
          : until <= today
            ? `A claim can only be deferred to a date after ${today}.`
            : period?.closed
              ? `Period ${period.name} is closed, so its claims cannot be deferred.`
              : 'The claim could not be deferred.',
      });
    }
  }

  /** Enter the new date to re-date a claim to; it clears any error left on the claim's row. */
  protected setNewDate(id: number, date: string): void {
    this.newDates.update((dates) => ({ ...dates, [id]: date }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /**
   * Re-date a claim to the date entered on its row; refused with an error on its row when no date is
   * entered, when the user is not a steward, when the claim is not OPEN or PENDING, when its date or
   * its due-by is in a closed period, or when the new date is after its person's retirement.
   */
  protected async redate(claim: ClaimView): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const date = (this.newDates()[claim.id] ?? '').trim();
    if (date === '') {
      this.claimError.set({ id: claim.id, message: 'Enter a new date for the claim.' });
      return;
    }
    try {
      this.saved.emit(await this.api.redate(sheet, claim.id, date));
      this.newDates.update((dates) => ({ ...dates, [claim.id]: '' }));
    } catch (e) {
      const closed = periodOn(this.periods(), claim.dueBy);
      this.claimError.set({
        id: claim.id,
        message: e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can re-date a claim.'
          : claim.status !== 'OPEN' && claim.status !== 'PENDING'
            ? `A ${claim.status} claim cannot be re-dated; only an OPEN or PENDING one can.`
            : closed?.closed
              ? `Period ${closed.name} is closed, so its claims cannot be re-dated.`
              : 'The claim could not be re-dated to that date.',
      });
    }
  }

  /** Type the new amount of a claim on its row; it clears any error left on the claim's row. */
  protected setInlineAmount(id: number, amount: string): void {
    this.inlineAmounts.update((amounts) => ({ ...amounts, [id]: amount }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /**
   * Change a claim's amount to the one typed on its row, keeping its date, note and the claim it
   * corrects: an edit by the claim form's rules, refused with an error on its row when the amount is not
   * one the form takes, when the claim is not a DRAFT, is due by in a closed period or is locked by
   * another user's editor, or for a viewer.
   */
  protected async saveInline(claim: ClaimView): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = (this.inlineAmounts()[claim.id] ?? '').trim();
    if (!AMOUNT.test(amount)) {
      this.claimError.set({ id: claim.id, message: 'Type an amount with up to two decimals; below 0.00 only for a correction.' });
      return;
    }
    const me = this.session.user()?.trim();
    const period = periodOn(this.periods(), claim.dueBy);
    try {
      const saved = await this.api.edit(sheet, claim.id, { amount, date: claim.date, note: claim.note, corrects: claim.corrects ?? null, ref: claim.ref ?? '' });
      this.saved.emit(saved);
      this.inlineAmounts.update((amounts) => ({ ...amounts, [claim.id]: '' }));
      // the save released this user's lock, so an editor left open on the claim is closed
      if (this.editing() === claim.id) this.reset();
    } catch (e) {
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.claimError.set({
        id: claim.id,
        message: status === 403
          ? 'Your role is not allowed to edit a claim.'
          : claim.status !== 'DRAFT'
            ? claim.status === 'DELETED'
              ? 'A DELETED claim cannot be edited; restore it first.'
              : `A ${claim.status} claim cannot be edited; void it and add a new one.`
            : period?.closed
              ? `Period ${period.name} is closed, so its claims cannot be edited.`
              : status === 423 || (claim.lockedBy !== null && claim.lockedBy !== me)
                ? this.lockedMessage(claim.lockedBy)
                : amount.startsWith('-')
                  ? `The claim could not be saved: a correction (an amount below 0.00) must name a SETTLED claim of ${this.personName()}.`
                  : 'The claim could not be saved.',
      });
    }
  }

  /** Type the amount to split off a claim; it clears any error left on the claim's row. */
  protected setSplitAmount(id: number, amount: string): void {
    this.splitAmounts.update((amounts) => ({ ...amounts, [id]: amount }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /**
   * Split the amount typed on a claim's row off it into a new OPEN claim; refused with an error on its
   * row when the amount is not above 0.00 and below the claim's, when the claim is not OPEN or is
   * deferred, or when it is due by in a closed period.
   */
  protected async split(claim: ClaimView): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = (this.splitAmounts()[claim.id] ?? '').trim();
    const period = periodOn(this.periods(), claim.dueBy);
    const refuse = (): string =>
      claim.status !== 'OPEN'
        ? `A ${claim.status} claim cannot be split; only an OPEN one can.`
        : deferred(claim, this.session.today() ?? '')
          ? `The claim is deferred until ${claim.deferredUntil}, so it cannot be split.`
          : period?.closed
            ? `Period ${period.name} is closed, so its claims cannot be split.`
            : `Type an amount above 0.00 and below ${claim.amount} to split off the claim.`;
    if (!AMOUNT.test(amount) || amount.startsWith('-') || cents(amount) <= 0 || cents(amount) >= cents(claim.amount)) {
      this.claimError.set({ id: claim.id, message: refuse() });
      return;
    }
    try {
      const result = await this.api.split(sheet, claim.id, amount);
      this.saved.emit(result.original);
      this.saved.emit(result.split);
      this.splitAmounts.update((amounts) => ({ ...amounts, [claim.id]: '' }));
    } catch {
      this.claimError.set({ id: claim.id, message: refuse() });
    }
  }

  /** Type a comment on a claim; it clears any error left on the claim's row. */
  protected setCommentText(id: number, text: string): void {
    this.commentTexts.update((texts) => ({ ...texts, [id]: text }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** Comment on a claim; refused with an error on its row when the comment is empty or the user may not change the sheet. */
  protected async addComment(claim: ClaimView): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = (this.commentTexts()[claim.id] ?? '').trim();
    if (!text) {
      this.claimError.set({ id: claim.id, message: 'Type a comment to add it to the claim.' });
      return;
    }
    try {
      this.saved.emit(await this.api.comment(sheet, claim.id, text));
      this.commentTexts.update((texts) => ({ ...texts, [claim.id]: '' }));
    } catch (e) {
      this.claimError.set({
        id: claim.id,
        message: e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role is not allowed to comment on a claim.'
          : 'The comment could not be added.',
      });
    }
  }

  /** Raise a claim's proofs count by one; refused with an error on its row when the claim is SETTLED or VOID. */
  protected async addProof(claim: ClaimView): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      this.saved.emit(await this.api.addProof(sheet, claim.id));
    } catch {
      this.claimError.set({
        id: claim.id,
        message: claim.status === 'SETTLED' || claim.status === 'VOID'
          ? `A ${claim.status} claim cannot take a proof.`
          : 'The proof could not be added.',
      });
    }
  }

  /** Remind a claim; refused with an error on its row unless it is OPEN or PENDING and not yet reminded today. */
  protected async remind(claim: ClaimView): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      this.saved.emit(await this.api.remind(sheet, claim.id));
    } catch {
      this.claimError.set({
        id: claim.id,
        message: claim.status === 'OPEN' || claim.status === 'PENDING'
          ? 'The claim could not be reminded; it may already have been reminded today.'
          : `A ${claim.status} claim cannot be reminded; only an OPEN or PENDING one can.`,
      });
    }
  }

  /** Choose the person to reassign a claim to; it clears any error left on the claim's row. */
  protected setReassignTo(id: number, personId: string): void {
    this.reassignTo.update((chosen) => ({ ...chosen, [id]: Number(personId) }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /**
   * Move a claim to the person chosen on its row; refused with an error on its row when none is
   * chosen, when the user's role may not reassign, or when the claim is SETTLED or VOID.
   */
  protected async reassign(claim: ClaimView): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const to = this.reassignTo()[claim.id];
    if (!to) {
      this.claimError.set({ id: claim.id, message: 'Choose a person to reassign the claim to.' });
      return;
    }
    try {
      this.saved.emit(await this.api.reassign(sheet, claim.id, to));
    } catch (e) {
      this.claimError.set({
        id: claim.id,
        message: e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role is not allowed to reassign a claim.'
          : claim.status === 'SETTLED' || claim.status === 'VOID'
            ? `A ${claim.status} claim cannot be reassigned.`
            : 'The claim could not be reassigned to that person.',
      });
    }
  }

  /**
   * Move a claim's status; a move its status does not allow is refused with an error on its row, as
   * is a reject without a reason. A clerk settling a claim above the clerk limit sends it PENDING.
   */
  protected async act(claim: ClaimView, action: ClaimAction): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const reason = action === 'void'
      ? (this.voidReasonsChosen()[claim.id] ?? '')
      : (this.rejectReasons()[claim.id] ?? '').trim();
    if (action === 'reject' && reason === '') {
      this.claimError.set({ id: claim.id, message: 'Type a reason to reject the claim.' });
      return;
    }
    if (action === 'void' && reason === '' && this.voidReasons.list().length > 0) {
      this.claimError.set({ id: claim.id, message: 'Choose a reason from the list to void the claim.' });
      return;
    }
    try {
      this.saved.emit(await this.api.act(sheet, claim.id, action, reason));
      if (action === 'reject') this.rejectReasons.update((reasons) => ({ ...reasons, [claim.id]: '' }));
    } catch (e) {
      const me = this.session.user()?.trim();
      const mine = claim.openedBy === me;
      const edited = !mine && (claim.editedBy ?? null) === me;
      const own = ((action === 'settle' && claim.status === 'OPEN') || (action === 'approve' && claim.status === 'PENDING'))
        && (mine || edited);
      const unproved = e instanceof HttpErrorResponse && e.status === 409 && claim.proofs === 0
        && ((action === 'settle' && claim.status === 'OPEN') || (action === 'approve' && claim.status === 'PENDING'));
      const unnoted = e instanceof HttpErrorResponse && e.status === 409 && action === 'open'
        && claim.status === 'DRAFT' && (claim.note ?? '').trim() === '';
      const steward = e instanceof HttpErrorResponse && e.status === 403
        && (((action === 'approve' || action === 'reject') && claim.status === 'PENDING')
          || ((action === 'resettle' || action === 'void') && claim.status === 'SETTLED'));
      const forbidden = e instanceof HttpErrorResponse && e.status === 403 && !steward
        && (action === 'open' || action === 'settle' || action === 'void');
      const lateVoid = e instanceof HttpErrorResponse && e.status === 409
        && action === 'void' && claim.status === 'SETTLED';
      this.claimError.set({
        id: claim.id,
        message: forbidden
          ? `Your role is not allowed to ${action} a claim.`
          : action === 'settle' && deferred(claim, this.session.today() ?? '')
          ? `The claim is deferred until ${claim.deferredUntil}, so it cannot be settled before then.`
          : own
            ? `You ${edited ? 'last edited' : 'opened'} this claim, so someone else must ${action} it.`
            : unproved
              ? `The claim's due is above the proof threshold, so it needs a proof before it can be ${PAST[action]}.`
            : unnoted
              ? "The claim's due is above the note threshold, so it needs a note before it can be opened."
            : steward
              ? `Only a steward can ${action === 'resettle' ? 're-settle' : action} a ${claim.status} claim.`
            : lateVoid
              ? 'A SETTLED claim can only be voided within 7 days of its settlement, outside a closed period.'
            : action === 'delete' && claim.status === 'DRAFT'
              ? 'The claim is due by inside a closed period, so it cannot be deleted.'
            : action === 'restore' && claim.status === 'DELETED'
              ? 'A deleted claim can only be restored on the day it was deleted, outside a closed period.'
              : `A ${claim.status} claim cannot be ${PAST[action]}.`,
      });
    }
  }

  /** The form goes back to adding a claim; a lock its editor held is not released here. */
  protected reset(): void {
    this.setEditing(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.ref.set('');
    this.tags.set('');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    // submitting the form is the next action on its claims, so an error left on a claim's row goes
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Type an amount with up to two decimals; below 0.00 only for a correction.');
      return;
    }
    const corrects = this.corrects().trim();
    if (corrects !== '' && !CLAIM_NUMBER.test(corrects)) {
      this.error.set('Type the number of the claim it corrects.');
      return;
    }
    const form = { amount, date: this.date().trim() || null, note: this.note(), corrects: corrects === '' ? null : corrects, ref: this.ref().trim(), tags: this.tags() };
    // a claim no longer this person's is not edited from their form: the form adds a claim
    const editing = this.editing();
    const id = this.claims().some((c) => c.id === editing) ? editing : null;
    this.busy = true;
    try {
      const claim = id === null
        ? await this.api.add(sheet, this.personId(), form)
        : await this.api.edit(sheet, id, form);
      this.saved.emit(claim);
      this.reset();
    } catch (e) {
      const verb = id === null ? 'added' : 'saved';
      this.error.set(e instanceof HttpErrorResponse && e.status === 423
        ? this.lockedMessage(null)
        : e instanceof HttpErrorResponse && e.status === 409 && form.ref !== ''
        ? `The claim could not be ${verb}: the reference ${form.ref} may already be used on this sheet.`
        : amount.startsWith('-') || corrects !== ''
        ? `The claim could not be ${verb}: a correction (an amount below 0.00) must name a SETTLED claim of ${this.personName()}, and only a correction names one.`
        : `The claim could not be ${verb}.`);
      if (id === null) {
        // a refused add leaves nothing typed behind for the next one; its refusal is still shown
        this.amount.set('');
        this.date.set('');
        this.note.set('');
        this.corrects.set('');
        this.ref.set('');
        this.tags.set('');
      }
    } finally {
      this.busy = false;
    }
  }

  /**
   * Add a DRAFT claim from the chosen template, with its amount and note as they are now and no date,
   * by every rule of the claim form; refused with an error on the form.
   */
  protected async addFromTemplate(): Promise<void> {
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.template();
    if (!this.templates.list().some((t) => t.name === name)) {
      this.error.set('Choose a template to add a claim from.');
      return;
    }
    this.busy = true;
    try {
      this.saved.emit(await this.templatesApi.claimFrom(sheet, this.personId(), name));
    } catch (e) {
      this.error.set(e instanceof HttpErrorResponse && e.status === 403
        ? 'Your role is not allowed to add a claim.'
        : `The claim could not be added from the template ${name}.`);
    } finally {
      this.busy = false;
    }
  }
}
