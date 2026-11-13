import { Component, ElementRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { AuditLog } from '../../core/audit';
import { ClaimTemplates } from '../../core/claim-templates';
import { Claim, ClaimAction, Claims, PROOF_KINDS, ProofKind, bound } from '../../core/claims';
import { Periods } from '../../core/periods';
import { GradeFrom, People, gradeOn } from '../../core/people';
import { NO_RATE, Rates, hundredths, money } from '../../core/rates';
import { Session } from '../../core/session';
import { VoidReasons } from '../../core/void-reasons';

// A person's claims: one form adds a claim, or edits the one whose editor is open, and the list
// shows the person's claims newest first.
@Component({
  selector: 'app-person-claims',
  template: `
    <form (submit)="submit($event)" data-testid="claim-form">
      <label [for]="fid('amount')">Amount</label>
      <input
        [id]="fid('amount')"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); clearErrors()"
      />
      <label [for]="fid('date')">Date</label>
      <input
        [id]="fid('date')"
        data-testid="claim-form-date"
        placeholder="YYYY-MM-DD"
        [value]="date()"
        (input)="date.set(value($event)); clearErrors()"
      />
      <label [for]="fid('note')">Note</label>
      <input
        [id]="fid('note')"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); clearErrors()"
      />
      <label [for]="fid('corrects')">Corrects claim no. (for a negative amount)</label>
      <input
        [id]="fid('corrects')"
        data-testid="claim-form-corrects"
        inputmode="numeric"
        [value]="corrects()"
        (input)="corrects.set(value($event)); clearErrors()"
      />
      <label [for]="fid('ref')">External reference</label>
      <input
        [id]="fid('ref')"
        data-testid="claim-form-ref"
        [value]="ref()"
        (input)="ref.set(value($event)); clearErrors()"
      />
      <label [for]="fid('tags')">Tags (comma separated)</label>
      <input
        [id]="fid('tags')"
        data-testid="claim-form-tags"
        [value]="tags()"
        (input)="tags.set(value($event)); clearErrors()"
      />
      <button type="submit" data-testid="claim-form-submit" [disabled]="saving()">
        {{ editing() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>
    <label [for]="fid('template')">Claim template</label>
    <select
      [id]="fid('template')"
      data-testid="claim-template-select"
      (change)="template.set(value($event)); clearErrors()"
    >
      <option value="" [selected]="!template()">Choose a template</option>
      @for (t of templates.templates(); track t.id) {
        <option [value]="t.name" [selected]="template() === t.name">{{ t.name }}</option>
      }
    </select>
    <button type="button" data-testid="claim-template-add" [disabled]="saving()" (click)="addFromTemplate()">
      Add claim from template
    </button>
    <ul data-testid="claim-list">
      @for (c of list(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input
            type="checkbox"
            data-testid="claim-select"
            [attr.aria-label]="'Select claim ' + c.amount + ' on ' + c.date"
            [checked]="claims.selected().has(c.id)"
            (change)="claims.select(c.id, checked($event))"
          />
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Ref <span data-testid="claim-ref">{{ c.ref ?? '' }}</span></span>
          <span>Tags <span data-testid="claim-tags">{{ c.tags.join(', ') }}</span></span>
          <span>Due <span data-testid="claim-due">{{ figure(c) }}</span></span>
          <span>Due by <span data-testid="claim-due-by">{{ claims.dueBy(c) }}</span></span>
          <span>Overdue <span data-testid="claim-overdue">{{ claims.overdue(c) ? 'yes' : 'no' }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periods.of(c.dueBy)?.name ?? '' }}</span></span>
          <span>Waived <span data-testid="claim-waived">{{ c.waived ? 'yes' : 'no' }}</span></span>
          <span>Held <span data-testid="claim-held">{{ c.held ? 'yes' : 'no' }}</span></span>
          <span>Review assigned to <span data-testid="claim-review-assigned">{{ c.reviewAssignee ?? '' }}</span></span>
          <span>Shared with <span data-testid="claim-shares">{{ sharesShown(c) }}</span></span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span>Split from <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span></span>
          <span>Batch <span data-testid="claim-batch">{{ c.settleBatch ?? '' }}</span></span>
          <span>Late entry <span data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' }}</span></span>
          <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Status <span data-testid="claim-status">{{ c.status }}</span></span>
          <span>Review late <span data-testid="claim-review-late">{{ claims.reviewLate(c) ? 'yes' : 'no' }}</span></span>
          <span>Pending late <span data-testid="claim-pending-late">{{ claims.pendingLate(c) ? 'yes' : 'no' }}</span></span>
          <span>Proofs <span data-testid="claim-proofs">{{ c.proofs }}</span></span>
          <span>Receipts <span data-testid="claim-proofs-receipt">{{ c.proofReceipts }}</span></span>
          <span>Photos <span data-testid="claim-proofs-photo">{{ c.proofPhotos }}</span></span>
          <span>Deferred <span data-testid="claim-deferred">{{ claims.deferredShown(c) ? 'yes' : 'no' }}</span></span>
          <span>Deferred until <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span></span>
          <span>Reminded <span data-testid="claim-reminded">{{ c.remindedOn ?? '' }}</span></span>
          <ol data-testid="claim-log" aria-label="Status log">
            @for (l of c.statusLog; track $index) {
              <li data-testid="claim-log-line">{{ l.status }} by {{ l.by }} on {{ l.on }} due={{ l.due ?? noRate }}</li>
            }
          </ol>
          <ol data-testid="claim-comments" aria-label="Comments">
            @for (m of c.comments; track $index) {
              <li data-testid="claim-comments-line">{{ m.by }} on {{ m.on }}: {{ m.text }}</li>
            }
          </ol>
          <ol data-testid="claim-lines" aria-label="Lines">
            @for (l of c.lines ?? []; track $index) {
              <li data-testid="claim-lines-line">{{ l.label }} {{ l.qty }} x {{ l.unit }}</li>
            }
          </ol>
          <label [for]="'claim-line-label-' + c.id">Line label</label>
          <input
            [id]="'claim-line-label-' + c.id"
            data-testid="claim-line-label"
            [value]="lineFields()[c.id]?.label ?? ''"
            (input)="setLineField(c.id, 'label', value($event))"
          />
          <label [for]="'claim-line-qty-' + c.id">Quantity</label>
          <input
            [id]="'claim-line-qty-' + c.id"
            data-testid="claim-line-qty"
            inputmode="numeric"
            [value]="lineFields()[c.id]?.qty ?? ''"
            (input)="setLineField(c.id, 'qty', value($event))"
          />
          <label [for]="'claim-line-unit-' + c.id">Unit amount</label>
          <input
            [id]="'claim-line-unit-' + c.id"
            data-testid="claim-line-unit"
            inputmode="decimal"
            [value]="lineFields()[c.id]?.unit ?? ''"
            (input)="setLineField(c.id, 'unit', value($event))"
          />
          <button type="button" data-testid="claim-line-add" [disabled]="acting() === c.id" (click)="addLine(c)">
            Add line<span class="visually-hidden"> to claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <label [for]="'claim-comment-text-' + c.id">Comment</label>
          <input
            [id]="'claim-comment-text-' + c.id"
            data-testid="claim-comment-text"
            [value]="commentTexts()[c.id] ?? ''"
            (input)="setCommentText(c.id, value($event))"
          />
          <button type="button" data-testid="claim-comment-add" [disabled]="acting() === c.id" (click)="comment(c)">
            Add comment<span class="visually-hidden"> to claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          @if (editing() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
          } @else {
            <button type="button" data-testid="claim-edit" (click)="edit(c)">
              Edit<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
            </button>
          }
          <label [for]="'claim-inline-amount-' + c.id">New amount</label>
          <input
            [id]="'claim-inline-amount-' + c.id"
            data-testid="claim-inline-amount"
            inputmode="decimal"
            [value]="inlineAmounts()[c.id] ?? ''"
            (input)="setInlineAmount(c.id, value($event))"
          />
          <button type="button" data-testid="claim-inline-save" [disabled]="acting() === c.id" (click)="saveInline(c)">
            Save amount<span class="visually-hidden"> of claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          @if (voidReasons.reasons().length > 0) {
            <label [for]="'claim-void-reason-' + c.id">Void reason</label>
            <select
              [id]="'claim-void-reason-' + c.id"
              data-testid="claim-void-reason"
              (change)="setVoidReason(c.id, value($event))"
            >
              <option value="" [selected]="!voidReasonsChosen()[c.id]">Choose a reason</option>
              @for (r of voidReasons.reasons(); track r.id) {
                <option [value]="r.reason" [selected]="voidReasonsChosen()[c.id] === r.reason">{{ r.reason }}</option>
              }
            </select>
          }
          <label [for]="'claim-settle-date-' + c.id">Settle as of</label>
          <input
            [id]="'claim-settle-date-' + c.id"
            data-testid="claim-settle-date"
            placeholder="YYYY-MM-DD"
            [value]="settleDates()[c.id] ?? ''"
            (input)="setSettleDate(c.id, value($event))"
          />
          @for (a of actions; track a.action) {
            <button
              type="button"
              [attr.data-testid]="'claim-' + a.action"
              [disabled]="acting() === c.id"
              (click)="act(c, a.action)"
            >
              {{ a.label }}<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
            </button>
          }
          <label [for]="'claim-proof-kind-' + c.id">Proof kind</label>
          <select
            [id]="'claim-proof-kind-' + c.id"
            data-testid="claim-proof-kind"
            (change)="setProofKind(c.id, value($event))"
          >
            <option value="" [selected]="!proofKinds()[c.id]">Receipt (default)</option>
            @for (k of proofKindOptions; track k) {
              <option [value]="k" [selected]="proofKinds()[c.id] === k">{{ k }}</option>
            }
          </select>
          <button type="button" data-testid="claim-proof" [disabled]="acting() === c.id" (click)="addProof(c)">
            Add proof<span class="visually-hidden"> to claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <button type="button" data-testid="claim-remind" [disabled]="acting() === c.id" (click)="remind(c)">
            Remind<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <button type="button" data-testid="claim-waive" [disabled]="acting() === c.id" (click)="waive(c)">
            Waive late charge<span class="visually-hidden"> on claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <button type="button" data-testid="claim-hold" [disabled]="acting() === c.id" (click)="toggleHold(c)">
            {{ c.held ? 'Release' : 'Hold' }}<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <label [for]="'claim-review-user-' + c.id">Assign review to</label>
          <input
            [id]="'claim-review-user-' + c.id"
            data-testid="claim-review-user"
            [value]="reviewUsers()[c.id] ?? ''"
            (input)="setReviewUser(c.id, value($event))"
          />
          <button type="button" data-testid="claim-review-assign" [disabled]="acting() === c.id" (click)="assignReview(c)">
            Assign review<span class="visually-hidden"> of claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <label [for]="'claim-share-select-' + c.id">Share with</label>
          <select
            [id]="'claim-share-select-' + c.id"
            data-testid="claim-share-select"
            (change)="setShareField(c.id, 'personId', value($event))"
          >
            <option value="" [selected]="!shareFields()[c.id]?.personId">Choose a person</option>
            @for (p of everyone(); track p.id) {
              <option [value]="p.id" [selected]="shareFields()[c.id]?.personId === '' + p.id">{{ p.name }}</option>
            }
          </select>
          <label [for]="'claim-share-pct-' + c.id">Share percent</label>
          <input
            [id]="'claim-share-pct-' + c.id"
            data-testid="claim-share-pct"
            inputmode="decimal"
            [value]="shareFields()[c.id]?.share ?? ''"
            (input)="setShareField(c.id, 'share', value($event))"
          />
          <button type="button" data-testid="claim-share-add" [disabled]="acting() === c.id" (click)="share(c)">
            Share<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <label [for]="'claim-defer-date-' + c.id">Defer until</label>
          <input
            [id]="'claim-defer-date-' + c.id"
            data-testid="claim-defer-date"
            placeholder="YYYY-MM-DD"
            [value]="deferDates()[c.id] ?? ''"
            (input)="setDeferDate(c.id, value($event))"
          />
          <button type="button" data-testid="claim-defer" [disabled]="acting() === c.id" (click)="defer(c)">
            Defer<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <label [for]="'claim-date-new-' + c.id">New date</label>
          <input
            [id]="'claim-date-new-' + c.id"
            data-testid="claim-date-new"
            placeholder="YYYY-MM-DD"
            [value]="redates()[c.id] ?? ''"
            (input)="setRedate(c.id, value($event))"
          />
          <button type="button" data-testid="claim-redate" [disabled]="acting() === c.id" (click)="redate(c)">
            Re-date<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <label [for]="'claim-due-by-new-' + c.id">New due-by</label>
          <input
            [id]="'claim-due-by-new-' + c.id"
            data-testid="claim-due-by-new"
            placeholder="YYYY-MM-DD"
            [value]="dueByDates()[c.id] ?? ''"
            (input)="setDueByDate(c.id, value($event))"
          />
          <button type="button" data-testid="claim-set-due-by" [disabled]="acting() === c.id" (click)="setDueBy(c)">
            Set due-by<span class="visually-hidden"> of claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <label [for]="'claim-split-amount-' + c.id">Split off</label>
          <input
            [id]="'claim-split-amount-' + c.id"
            data-testid="claim-split-amount"
            inputmode="decimal"
            [value]="splitAmounts()[c.id] ?? ''"
            (input)="setSplitAmount(c.id, value($event))"
          />
          <button type="button" data-testid="claim-split" [disabled]="acting() === c.id" (click)="split(c)">
            Split<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <label [for]="'claim-settle-part-amount-' + c.id">Settle part</label>
          <input
            [id]="'claim-settle-part-amount-' + c.id"
            data-testid="claim-settle-part-amount"
            inputmode="decimal"
            [value]="settlePartAmounts()[c.id] ?? ''"
            (input)="setSettlePartAmount(c.id, value($event))"
          />
          <button type="button" data-testid="claim-settle-part" [disabled]="acting() === c.id" (click)="settlePart(c)">
            Part-settle<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <label [for]="'claim-reassign-to-' + c.id">Move to</label>
          <select
            [id]="'claim-reassign-to-' + c.id"
            data-testid="claim-reassign-to"
            (change)="setReassignTo(c.id, value($event))"
          >
            <option value="" [selected]="reassignTo()[c.id] === undefined">Choose a person</option>
            @for (p of others(); track p.id) {
              <option [value]="p.id" [selected]="reassignTo()[c.id] === p.id">{{ p.name }}</option>
            }
          </select>
          <button type="button" data-testid="claim-reassign" [disabled]="acting() === c.id" (click)="reassign(c)">
            Reassign<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input
            [id]="'claim-reject-reason-' + c.id"
            data-testid="claim-reject-reason"
            [value]="rejectReasons()[c.id] ?? ''"
            (input)="setRejectReason(c.id, value($event))"
          />
          @for (a of reviews; track a.action) {
            <button
              type="button"
              [attr.data-testid]="'claim-' + a.action"
              [disabled]="acting() === c.id"
              (click)="act(c, a.action)"
            >
              {{ a.label }}<span class="visually-hidden"> claim {{ c.amount }} on {{ c.date }}</span>
            </button>
          }
          <button type="button" data-testid="claim-timeline" (click)="showTimeline(c)">
            Timeline<span class="visually-hidden"> of claim {{ c.amount }} on {{ c.date }}</span>
          </button>
          @if (timeline()?.id === c.id) {
            <ol data-testid="claim-timeline-list" aria-label="Claim timeline">
              @for (line of timeline()?.lines; track $index) {
                <li data-testid="claim-timeline-line">{{ line }}</li>
              }
            </ol>
          }
          @if (actionError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ actionError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
  host: { '(document:click)': 'clickedElsewhere($event)' },
})
export class PersonClaims {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly claims = inject(Claims);
  private readonly rates = inject(Rates);
  protected readonly periods = inject(Periods);
  readonly personId = input.required<number>();
  /** The person's dated grades: the rate in force on each claim's date of the grade in force then
   *  gives its due, unless fixed when it was settled or bound by the person's last statement. */
  readonly grades = input.required<GradeFrom[]>();
  private readonly people = inject(People);
  private readonly session = inject(Session);
  protected readonly list = computed(() => this.claims.of(this.personId()));
  /** The other people on the sheet, whom a steward can move a claim to. */
  protected readonly others = computed(() => this.people.people().filter((p) => p.id !== this.personId()));
  /** Everyone on the sheet, whom a claim can be shared with (the server refuses the claim's own person). */
  protected readonly everyone = computed(() => this.people.people());
  /** The person chosen and the percent typed to share each claim with, by claim id. */
  protected readonly reviewUsers = signal<Record<number, string>>({});
  protected readonly shareFields = signal<Record<number, { personId?: string; share?: string }>>({});
  /** The person chosen to move each claim to, by claim id. */
  protected readonly reassignTo = signal<Record<number, number>>({});
  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly corrects = signal('');
  protected readonly ref = signal('');
  protected readonly tags = signal('');
  protected readonly templates = inject(ClaimTemplates);
  /** The template the person's next claim from a template is made from: none chosen yet. */
  protected readonly template = signal('');
  /** The edit whose edit button was refused while no editor was open: the form's next submit is its save,
   *  refused on the claim again, until the next action on that claim, another editor opens, or an action is
   *  taken anywhere else on the screen. */
  private refusedEdit: { id: number; message: string } | null = null;
  /** The lock asked for by the last edit button, until it answers: true when granted. */
  private locking: Promise<boolean> | null = null;
  /** The id of the claim whose editor is open, or null when the form adds. */
  protected readonly editing = signal<number | null>(null);
  private readonly audit = inject(AuditLog);
  /** The timeline last shown, of the claim it is about: the audit lines about it, newest first. */
  protected readonly timeline = signal<{ id: number; lines: string[] } | null>(null);
  private timelineShows = 0;
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
    { action: 'resettle', label: 'Re-settle' },
    { action: 'delete', label: 'Delete' },
    { action: 'restore', label: 'Restore' },
  ];
  /** A steward's review of a PENDING claim (approve, reject) or of a REVIEW claim (review), offered on
   *  every claim (the server refuses each unless the claim is in its status). */
  protected readonly reviews: { action: ClaimAction; label: string }[] = [
    { action: 'approve', label: 'Approve' },
    { action: 'reject', label: 'Reject' },
    { action: 'review', label: 'Review' },
  ];
  /** The date typed to defer each claim to, by claim id. */
  protected readonly deferDates = signal<Record<number, string>>({});
  /** The date typed to settle each claim as of, by claim id; blank: today. */
  protected readonly settleDates = signal<Record<number, string>>({});
  /** The new date typed to re-date each claim to, by claim id. */
  protected readonly redates = signal<Record<number, string>>({});
  protected readonly dueByDates = signal<Record<number, string>>({});
  /** The amount typed to split off each claim, by claim id. */
  protected readonly splitAmounts = signal<Record<number, string>>({});
  /** The amount typed to part-settle each claim, by claim id. */
  protected readonly settlePartAmounts = signal<Record<number, string>>({});
  /** The amount typed in each claim's row, by claim id. */
  protected readonly inlineAmounts = signal<Record<number, string>>({});
  /** The comment typed on each claim, by claim id. */
  protected readonly commentTexts = signal<Record<number, string>>({});
  /** The line typed on each claim, by claim id. */
  protected readonly lineFields = signal<Record<number, { label?: string; qty?: string; unit?: string }>>({});
  /** The reason typed to reject each claim, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});
  protected readonly voidReasons = inject(VoidReasons);
  /** The void reason chosen for each claim, by claim id. */
  protected readonly voidReasonsChosen = signal<Record<number, string>>({});
  /** The kinds a proof can be. */
  protected readonly proofKindOptions = PROOF_KINDS;
  /** The proof kind chosen for each claim, by claim id; none chosen: a receipt. */
  protected readonly proofKinds = signal<Record<number, ProofKind>>({});
  protected readonly noRate = NO_RATE;
  /** The id of the claim whose status change is under way. */
  protected readonly acting = signal<number | null>(null);
  /** The last refused status change, or edit of a claim that is no longer a DRAFT, shown on its claim
   *  until the next action on that claim. */
  protected readonly actionError = signal<{ id: number; message: string } | null>(null);

  constructor() {
    // The editor follows the user's lock: it opens on the claim the user has locked (as when the sheet is
    // re-loaded) and closes once that lock is gone (saved, or released by a new today).
    effect(() => {
      const user = this.session.user();
      const mine = this.list().find((c) => user !== null && c.lockedBy === user);
      untracked(() => {
        const id = this.editing();
        if (id !== null && this.list().find((c) => c.id === id)?.lockedBy !== user) this.reset();
        else if (id === null && mine) this.fill(mine);
      });
    });
  }

  /** The claim's due: fixed when settled, else as its person's last statement bound it (late charge
   *  included), else live with an overdue claim's late charge on top. */
  protected figure(c: Claim): string {
    if (c.settledDue !== null) return c.settledDue;
    const f = bound(c)
      ? (c.statedDue === null ? null : hundredths(c.statedDue))
      : this.rates.dueCents(hundredths(c.amount), gradeOn(this.grades(), c.date), c.date, this.claims.roundingDate(c), c.id);
    return f === null ? NO_RATE : money(this.claims.chargedCents(c, f));
  }

  protected fid(field: string): string {
    return `claim-form-${field}-${this.personId()}`;
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  /** Open the claim's editor once its lock is granted, keeping any field typed while it was asked for; when
   *  the lock is refused (the claim cannot be edited, or another user's editor is open on it) the refusal
   *  shows on the claim and the form stays as it is. */
  protected edit(c: Claim): Promise<boolean> {
    this.clearActionError(c.id);
    const typed = { amount: this.amount(), date: this.date(), note: this.note(), corrects: this.corrects(), ref: this.ref(), tags: this.tags() };
    const lock = (async () => {
      const err = await this.claims.lock(c.id);
      if (err) {
        this.actionError.set({ id: c.id, message: err });
        if (this.editing() === null) this.refusedEdit = { id: c.id, message: err };
        return false;
      }
      const was = this.editing();
      if (was !== null && was !== c.id) void this.claims.unlock(was);
      const now = { amount: this.amount(), date: this.date(), note: this.note(), corrects: this.corrects(), ref: this.ref(), tags: this.tags() };
      this.fill(this.list().find((o) => o.id === c.id) ?? c);
      if (now.amount !== typed.amount) this.amount.set(now.amount);
      if (now.date !== typed.date) this.date.set(now.date);
      if (now.note !== typed.note) this.note.set(now.note);
      if (now.corrects !== typed.corrects) this.corrects.set(now.corrects);
      if (now.ref !== typed.ref) this.ref.set(now.ref);
      if (now.tags !== typed.tags) this.tags.set(now.tags);
      return true;
    })();
    this.locking = lock;
    void lock.finally(() => {
      if (this.locking === lock) this.locking = null;
    });
    return lock;
  }

  /** Close the open editor without saving, releasing its lock. */
  protected cancel(): void {
    const id = this.editing();
    if (id !== null) void this.claims.unlock(id);
    this.reset();
  }

  private fill(c: Claim): void {
    this.refusedEdit = null;
    this.editing.set(c.id);
    this.amount.set(c.amount);
    this.date.set(c.date);
    this.note.set(c.note);
    this.corrects.set(c.corrects ?? '');
    this.ref.set(c.ref ?? '');
    this.tags.set(c.tags.join(', '));
    this.error.set(null);
  }

  protected async act(c: Claim, action: ClaimAction): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const reason =
        action === 'void'
          ? this.voidReasonsChosen()[c.id]
          : action === 'settle'
            ? this.settleDates()[c.id]
            : this.rejectReasons()[c.id];
      const err = await this.claims.act(c.id, action, reason ?? '');
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  protected async addProof(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.addProof(c.id, this.proofKinds()[c.id] ?? null);
      if (err) this.actionError.set({ id: c.id, message: err });
      else this.setProofKind(c.id, '');
    } finally {
      this.acting.set(null);
    }
  }

  protected setProofKind(id: number, kind: string): void {
    this.clearActionError(id);
    this.proofKinds.update((m) => {
      const { [id]: _, ...rest } = m;
      return (PROOF_KINDS as readonly string[]).includes(kind) ? { ...rest, [id]: kind as ProofKind } : rest;
    });
  }

  protected async showTimeline(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const show = ++this.timelineShows;
    this.timeline.set(null);
    const res = await this.audit.claimLines(c.id);
    if (show !== this.timelineShows) return; // a later press has taken over
    if ('error' in res) this.actionError.set({ id: c.id, message: res.error });
    else this.timeline.set({ id: c.id, lines: res.lines });
  }

  protected async remind(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.remind(c.id);
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  protected async waive(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.waive(c.id);
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  protected async toggleHold(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.toggleHold(c.id);
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  protected setReviewUser(id: number, text: string): void {
    this.clearActionError(id);
    this.reviewUsers.update((f) => ({ ...f, [id]: text }));
  }

  /** Assign the claim's review to the user typed. */
  protected async assignReview(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.assignReview(c.id, this.reviewUsers()[c.id] ?? '');
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  /** A claim's holders as its row shows them: <name> <share>, comma and space separated, in the order shared. */
  protected sharesShown(c: Claim): string {
    const names = new Map(this.people.people().map((p) => [p.id, p.name]));
    return (c.shares ?? []).map((s) => `${names.get(s.personId) ?? ''} ${s.share}`).join(', ');
  }

  protected setShareField(id: number, key: 'personId' | 'share', text: string): void {
    this.clearActionError(id);
    this.shareFields.update((f) => ({ ...f, [id]: { ...f[id], [key]: text } }));
  }

  /** Share the claim with the person chosen at the percent typed. */
  protected async share(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const f = this.shareFields()[c.id] ?? {};
      const err = await this.claims.share(c.id, f.personId ? Number(f.personId) : null, f.share ?? '');
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  protected setReassignTo(id: number, personId: string): void {
    this.clearActionError(id);
    this.reassignTo.update((r) => {
      const next = { ...r };
      if (personId) next[id] = Number(personId);
      else delete next[id];
      return next;
    });
  }

  protected async reassign(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.reassign(c.id, this.reassignTo()[c.id] ?? null);
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  protected setCommentText(id: number, text: string): void {
    this.clearActionError(id);
    this.commentTexts.update((t) => ({ ...t, [id]: text }));
  }

  /** Comment on the claim; once it goes through, its comment box is emptied. */
  protected async comment(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.comment(c.id, this.commentTexts()[c.id] ?? '');
      if (err) this.actionError.set({ id: c.id, message: err });
      else this.commentTexts.update((t) => ({ ...t, [c.id]: '' }));
    } finally {
      this.acting.set(null);
    }
  }

  protected setLineField(id: number, key: 'label' | 'qty' | 'unit', text: string): void {
    this.clearActionError(id);
    this.lineFields.update((f) => ({ ...f, [id]: { ...f[id], [key]: text } }));
  }

  /** Add a line to the claim; once it goes through, its line boxes are emptied. */
  protected async addLine(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const f = this.lineFields()[c.id] ?? {};
      const err = await this.claims.addLine(c.id, f.label ?? '', f.qty ?? '', f.unit ?? '');
      if (err) this.actionError.set({ id: c.id, message: err });
      else this.lineFields.update((l) => ({ ...l, [c.id]: {} }));
    } finally {
      this.acting.set(null);
    }
  }

  protected setDeferDate(id: number, date: string): void {
    this.clearActionError(id);
    this.deferDates.update((d) => ({ ...d, [id]: date }));
  }

  protected async defer(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.defer(c.id, this.deferDates()[c.id] ?? '');
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  protected setRedate(id: number, date: string): void {
    this.clearActionError(id);
    this.redates.update((d) => ({ ...d, [id]: date }));
  }

  protected async redate(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.redate(c.id, this.redates()[c.id] ?? '');
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  protected setDueByDate(id: number, date: string): void {
    this.clearActionError(id);
    this.dueByDates.update((d) => ({ ...d, [id]: date }));
  }

  protected async setDueBy(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.setDueBy(c.id, this.dueByDates()[c.id] ?? '');
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  protected setSplitAmount(id: number, amount: string): void {
    this.clearActionError(id);
    this.splitAmounts.update((a) => ({ ...a, [id]: amount }));
  }

  protected async split(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.split(c.id, this.splitAmounts()[c.id] ?? '');
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  protected setSettlePartAmount(id: number, amount: string): void {
    this.clearActionError(id);
    this.settlePartAmounts.update((a) => ({ ...a, [id]: amount }));
  }

  protected async settlePart(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const err = await this.claims.settlePart(c.id, this.settlePartAmounts()[c.id] ?? '');
      if (err) this.actionError.set({ id: c.id, message: err });
    } finally {
      this.acting.set(null);
    }
  }

  protected setInlineAmount(id: number, amount: string): void {
    this.clearActionError(id);
    this.inlineAmounts.update((a) => ({ ...a, [id]: amount }));
  }

  /** Save the amount typed in the claim's row, keeping its date, note, correction and reference: the claim form's
   *  save, refused on the claim for the same reasons. */
  protected async saveInline(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    this.acting.set(c.id);
    try {
      const f = {
        amount: this.inlineAmounts()[c.id] ?? '',
        date: c.date,
        note: c.note,
        corrects: c.corrects ?? '',
        ref: c.ref ?? '',
        tags: c.tags.join(','),
      };
      const refused = await this.claims.update(c.id, f);
      if (refused) this.actionError.set({ id: c.id, message: refused.message });
      else this.inlineAmounts.update((a) => ({ ...a, [c.id]: '' }));
    } finally {
      this.acting.set(null);
    }
  }

  protected setSettleDate(id: number, date: string): void {
    this.clearActionError(id);
    this.settleDates.update((d) => ({ ...d, [id]: date }));
  }

  protected setRejectReason(id: number, reason: string): void {
    this.clearActionError(id);
    this.rejectReasons.update((r) => ({ ...r, [id]: reason }));
  }

  protected setVoidReason(id: number, reason: string): void {
    this.clearActionError(id);
    this.voidReasonsChosen.update((r) => ({ ...r, [id]: reason }));
  }

  private clearActionError(id: number): void {
    if (this.refusedEdit?.id === id) this.refusedEdit = null;
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /** An action taken elsewhere on the screen: a refused edit button no longer makes the next submit its save. */
  protected clickedElsewhere(e: Event): void {
    if (e.target instanceof Node && !this.host.nativeElement.contains(e.target)) this.refusedEdit = null;
  }

  /** The form was changed: its error, and its claim's refused edit, no longer apply. */
  protected clearErrors(): void {
    this.error.set(null);
    const id = this.editing();
    if (id !== null) this.clearActionError(id);
  }

  protected reset(): void {
    const id = this.editing();
    if (id !== null) this.clearActionError(id);
    this.editing.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.ref.set('');
    this.tags.set('');
    this.error.set(null);
  }

  /** Add a DRAFT claim from the chosen template; a refusal shows on the form. */
  protected async addFromTemplate(): Promise<void> {
    if (this.saving()) return; // a second press before the first has answered
    this.saving.set(true);
    try {
      this.clearErrors();
      const err = await this.claims.addFromTemplate(this.personId(), this.template());
      if (err) this.error.set(err);
    } finally {
      this.saving.set(false);
    }
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.saving.set(true);
    try {
      // a submit while an edit button's lock is asked for saves that editor, or nothing when it is refused
      if (this.locking && !(await this.locking)) return;
      const refused = this.refusedEdit;
      if (this.editing() === null && refused) {
        this.actionError.set(refused);
        return;
      }
      this.clearErrors();
      const f = { amount: this.amount(), date: this.date(), note: this.note(), corrects: this.corrects(), ref: this.ref(), tags: this.tags() };
      const id = this.editing();
      if (id === null) {
        const err = await this.claims.add(this.personId(), f);
        if (err) this.error.set(err);
        else this.reset();
      } else {
        // a refused edit leaves the editor open; one the claim itself refuses (no longer a DRAFT, or its
        // amount taken from its lines) closes the editor, the refusal showing on the claim
        const refused = await this.claims.update(id, f);
        if (refused?.onClaim) {
          this.cancel();
          this.actionError.set({ id, message: refused.message });
        } else if (refused) this.error.set(refused.message);
        else this.reset();
      }
    } finally {
      this.saving.set(false);
    }
  }
}
