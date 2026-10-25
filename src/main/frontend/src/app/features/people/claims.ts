import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal, untracked, type WritableSignal } from '@angular/core';
import { Session } from '../../core/session';
import { Claim, ClaimAction, ClaimsService, LineFields, PROOF_KINDS, ProofKind, cents, deferred, money, overdue } from './claims.service';
import { PersonGrade } from './people.service';
import { Period, periodOn } from './periods.service';
import { Graces, Rate, claimShownCents, overdueFrom } from './rates.service';
import { Dated } from './settings.service';
import { AuditService } from './audit.service';
import { TemplatesService } from './templates.service';
import { VoidReasonsService } from './void-reasons.service';
import { FigurePipe } from '../../core/figure.pipe';

/** The fields of the claim form. */
type FormField = 'amount' | 'date' | 'note' | 'corrects' | 'ref' | 'tags';

const AMOUNT = /^-?\d{1,10}(\.\d{1,2})?$/;
const NUMBER = /^\S.{0,119}$/;
const PAST: Record<ClaimAction, string> = {
  open: 'opened',
  review: 'reviewed',
  settle: 'settled',
  approve: 'approved',
  reject: 'rejected',
  void: 'voided',
  delete: 'deleted',
  restore: 'restored',
};

// A person's claims, newest first, each with its status and the buttons that change it, and the form that adds a claim to them or, once a claim's
// editor is opened, saves changes to it.
@Component({
  selector: 'app-claims',
  imports: [FigurePipe],
  template: `
    <form (submit)="submit($event)" [attr.aria-label]="'Claim for ' + personName()" data-testid="claim-form">
      <label [for]="fieldId('amount')">Amount</label>
      <input
        [id]="fieldId('amount')"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('date')">Date</label>
      <input
        [id]="fieldId('date')"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('note')">Note</label>
      <input
        [id]="fieldId('note')"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('corrects')">Corrects claim no.</label>
      <input
        [id]="fieldId('corrects')"
        data-testid="claim-form-corrects"
        [value]="corrects()"
        (input)="corrects.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('ref')">Reference</label>
      <input
        [id]="fieldId('ref')"
        data-testid="claim-form-ref"
        [value]="ref()"
        (input)="ref.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('tags')">Tags (comma separated)</label>
      <input
        [id]="fieldId('tags')"
        data-testid="claim-form-tags"
        [value]="tags()"
        (input)="tags.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ (editingId() === null ? 'Add claim' : 'Save claim') | figure }}
      </button>
      @if (error(); as message) {
        <p role="alert" class="error" data-testid="claim-form-error">{{ (message) | figure }}</p>
      }
    </form>
    <div class="from-template">
      <label [for]="fieldId('template')">Template</label>
      <select
        [id]="fieldId('template')"
        data-testid="claim-template-select"
        (change)="template.set(value($event)); error.set(null)"
      >
        <option value="" [selected]="template() === ''">Choose a template</option>
        @for (t of templateList(); track t.id) {
          <option [value]="t.name" [selected]="template() === t.name">{{ (t.name) | figure }}</option>
        }
      </select>
      <button type="button" data-testid="claim-template-add" (click)="addFromTemplate()">Add claim from template</button>
    </div>
    @if (claims().length > 0) {
      <ul class="claims" [attr.aria-label]="'Claims of ' + personName()">
        @for (c of claims(); track c.id) {
          <li [attr.data-testid]="'claim-row-' + c.id">
            <input
              type="checkbox"
              data-testid="claim-select"
              [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date"
              [checked]="selected().has(c.id)"
              (change)="selectToggled.emit({ id: c.id, selected: checked($event) })"
            />
            <span>No. <span data-testid="claim-number">{{ (c.number ?? '') | figure }}</span></span>
            <span data-testid="claim-amount">{{ (c.amount) | figure }}</span>
            <span data-testid="claim-date">{{ (c.date) | figure }}</span>
            <span data-testid="claim-note">{{ (c.note) | figure }}</span>
            <span>Ref <span data-testid="claim-ref">{{ (c.ref ?? '') | figure }}</span></span>
            <span>Tags <span data-testid="claim-tags">{{ (c.tags.join(', ')) | figure }}</span></span>
            <span>Due <span data-testid="claim-due">{{ (dueOf(c)) | figure }}</span></span>
            <span>Due by <span data-testid="claim-due-by">{{ (dueBy(c)) | figure }}</span></span>
            <span>Overdue <span data-testid="claim-overdue">{{ (isOverdue(c) ? 'yes' : 'no') | figure }}</span></span>
            <span data-testid="claim-status">{{ (c.status) | figure }}</span>
            <span>Batch <span data-testid="claim-batch">{{ (c.settleBatch ?? '') | figure }}</span></span>
            <span>Opened by <span data-testid="claim-opened-by">{{ (c.openedBy ?? '') | figure }}</span></span>
            <span>Stated <span data-testid="claim-stated">{{ (c.stated ? 'yes' : 'no') | figure }}</span></span>
            <span>Period <span data-testid="claim-period">{{ (periodOf(c)) | figure }}</span></span>
            <span>Corrects <span data-testid="claim-corrects">{{ (c.corrects ?? '') | figure }}</span></span>
            <span>Deferred <span data-testid="claim-deferred">{{ (isDeferred(c) ? 'yes' : 'no') | figure }}</span></span>
            <span>Until <span data-testid="claim-deferred-until">{{ (c.deferredUntil ?? '') | figure }}</span></span>
            <span>Proofs <span data-testid="claim-proofs">{{ (c.proofs) | figure }}</span></span>
            @for (k of proofKinds; track k) {
              <span>{{ (k) | figure }} <span [attr.data-testid]="'claim-proofs-' + k">{{ (c.proofsByKind[k]) | figure }}</span></span>
            }
            <span>Split from <span data-testid="claim-split-from">{{ (c.splitFrom ?? '') | figure }}</span></span>
            <span>Late entry <span data-testid="claim-late-entry">{{ (c.lateEntry ? 'yes' : 'no') | figure }}</span></span>
            <span>Review late <span data-testid="claim-review-late">{{ (c.reviewLate ? 'yes' : 'no') | figure }}</span></span>
            <span>Held <span data-testid="claim-held">{{ (c.held ? 'yes' : 'no') | figure }}</span></span>
            <span>Waived <span data-testid="claim-waived">{{ (c.waived ? 'yes' : 'no') | figure }}</span></span>
            <span>Reminded <span data-testid="claim-reminded">{{ (c.remindedOn ?? '') | figure }}</span></span>
            <ol class="claim-log" [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
              @for (line of c.log; track $index) {
                <li data-testid="claim-log-line">{{ (line) | figure }}</li>
              }
            </ol>
            <button
              type="button"
              data-testid="claim-timeline"
              [attr.aria-label]="'Show timeline of claim of ' + c.amount + ' on ' + c.date"
              (click)="showTimeline(c)"
            >
              Timeline
            </button>
            @if (timelines()[c.id]; as timeline) {
              <ol class="claim-timeline" [attr.aria-label]="'Timeline of claim of ' + c.amount + ' on ' + c.date">
                @for (line of timeline; track $index) {
                  <li data-testid="claim-timeline-line">{{ (line) | figure }}</li>
                }
              </ol>
            }
            <ol class="claim-comments" [attr.aria-label]="'Comments on claim of ' + c.amount + ' on ' + c.date">
              @for (line of c.comments; track $index) {
                <li data-testid="claim-comments-line">{{ (line) | figure }}</li>
              }
            </ol>
            <ol class="claim-lines" [attr.aria-label]="'Lines of claim of ' + c.amount + ' on ' + c.date">
              @for (line of c.lines; track $index) {
                <li data-testid="claim-lines-line">{{ (line) | figure }}</li>
              }
            </ol>
            <label [for]="fieldId('line-label-' + c.id)">Line label</label>
            <input
              [id]="fieldId('line-label-' + c.id)"
              data-testid="claim-line-label"
              [value]="lineFields()[c.id]?.label ?? ''"
              (input)="setLineField(c.id, 'label', value($event))"
            />
            <label [for]="fieldId('line-qty-' + c.id)">Quantity</label>
            <input
              [id]="fieldId('line-qty-' + c.id)"
              data-testid="claim-line-qty"
              inputmode="numeric"
              [value]="lineFields()[c.id]?.qty ?? ''"
              (input)="setLineField(c.id, 'qty', value($event))"
            />
            <label [for]="fieldId('line-unit-' + c.id)">Unit amount</label>
            <input
              [id]="fieldId('line-unit-' + c.id)"
              data-testid="claim-line-unit"
              inputmode="decimal"
              [value]="lineFields()[c.id]?.unit ?? ''"
              (input)="setLineField(c.id, 'unit', value($event))"
            />
            <button
              type="button"
              data-testid="claim-line-add"
              [attr.aria-label]="'Add a line to claim of ' + c.amount + ' on ' + c.date"
              (click)="addLine(c)"
            >
              Add line
            </button>
            <label [for]="fieldId('comment-' + c.id)">Comment</label>
            <input
              [id]="fieldId('comment-' + c.id)"
              data-testid="claim-comment-text"
              [value]="commentTexts()[c.id] ?? ''"
              (input)="setCommentText(c.id, value($event))"
            />
            <button
              type="button"
              data-testid="claim-comment-add"
              [attr.aria-label]="'Comment on claim of ' + c.amount + ' on ' + c.date"
              (click)="comment(c)"
            >
              Comment
            </button>
            <label [for]="fieldId('reject-reason-' + c.id)">Reject reason</label>
            <input
              [id]="fieldId('reject-reason-' + c.id)"
              data-testid="claim-reject-reason"
              [value]="rejectReasons()[c.id] ?? ''"
              (input)="setRejectReason(c.id, value($event))"
            />
            @if (voidReasonList().length > 0) {
              <label [for]="fieldId('void-reason-' + c.id)">Void reason</label>
              <select
                [id]="fieldId('void-reason-' + c.id)"
                data-testid="claim-void-reason"
                (change)="setVoidReason(c.id, value($event))"
              >
                <option value="" [selected]="!voidReasons()[c.id]">Choose a reason</option>
                @for (r of voidReasonList(); track r.id) {
                  <option [value]="r.reason" [selected]="voidReasons()[c.id] === r.reason">{{ (r.reason) | figure }}</option>
                }
              </select>
            }
            <label [for]="fieldId('settle-date-' + c.id)">Settle as of</label>
            <input
              [id]="fieldId('settle-date-' + c.id)"
              type="date"
              data-testid="claim-settle-date"
              [value]="settleDates()[c.id] ?? ''"
              (input)="setSettleDate(c.id, value($event))"
            />
            @for (a of actions; track a.action) {
              <button
                type="button"
                [attr.data-testid]="'claim-' + a.action"
                [attr.aria-label]="a.label + ' claim of ' + c.amount + ' on ' + c.date"
                (click)="act(c, a.action)"
              >
                {{ (a.label) | figure }}
              </button>
            }
            <select
              data-testid="claim-proof-kind"
              [attr.aria-label]="'Kind of proof for claim of ' + c.amount + ' on ' + c.date"
              [value]="proofKindOf(c)"
              (change)="chooseProofKind(c, $event)"
            >
              <option value="">Kind (receipt)</option>
              @for (k of proofKinds; track k) {
                <option [value]="k">{{ (k) | figure }}</option>
              }
            </select>
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
            <button
              type="button"
              data-testid="claim-hold"
              [attr.aria-label]="(c.held ? 'Release' : 'Hold') + ' claim of ' + c.amount + ' on ' + c.date"
              (click)="hold(c)"
            >
              {{ c.held ? 'Release' : 'Hold' }}
            </button>
            <button
              type="button"
              data-testid="claim-waive"
              [attr.aria-label]="'Waive the late charge of claim of ' + c.amount + ' on ' + c.date"
              (click)="waive(c)"
            >
              Waive late charge
            </button>
            <button
              type="button"
              data-testid="claim-resettle"
              [attr.aria-label]="'Re-settle claim of ' + c.amount + ' on ' + c.date"
              (click)="resettle(c)"
            >
              Re-settle
            </button>
            <label [for]="fieldId('defer-date-' + c.id)">Defer until</label>
            <input
              [id]="fieldId('defer-date-' + c.id)"
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
            <label [for]="fieldId('redate-date-' + c.id)">Re-date to</label>
            <input
              [id]="fieldId('redate-date-' + c.id)"
              type="date"
              data-testid="claim-date-new"
              [value]="redates()[c.id] ?? ''"
              (input)="setRedate(c.id, value($event))"
            />
            <button
              type="button"
              data-testid="claim-redate"
              [attr.aria-label]="'Re-date claim of ' + c.amount + ' on ' + c.date"
              (click)="redate(c)"
            >
              Re-date
            </button>
            <label [for]="fieldId('split-amount-' + c.id)">Split off</label>
            <input
              [id]="fieldId('split-amount-' + c.id)"
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
            <label [for]="fieldId('settle-part-amount-' + c.id)">Settle part</label>
            <input
              [id]="fieldId('settle-part-amount-' + c.id)"
              data-testid="claim-settle-part-amount"
              inputmode="decimal"
              [value]="settlePartAmounts()[c.id] ?? ''"
              (input)="setSettlePartAmount(c.id, value($event))"
            />
            <button
              type="button"
              data-testid="claim-settle-part"
              [attr.aria-label]="'Part-settle claim of ' + c.amount + ' on ' + c.date"
              (click)="settlePart(c)"
            >
              Settle part
            </button>
            <label [for]="fieldId('reassign-to-' + c.id)">Move to</label>
            <select
              [id]="fieldId('reassign-to-' + c.id)"
              data-testid="claim-reassign-to"
              (change)="setReassignTo(c.id, value($event))"
            >
              <option value="" [selected]="!reassignTos()[c.id]">Choose a person</option>
              @for (o of others(); track o.id) {
                <option [value]="o.id" [selected]="reassignTos()[c.id] === '' + o.id">{{ (o.name) | figure }}</option>
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
            <label [for]="fieldId('inline-amount-' + c.id)">New amount</label>
            <input
              [id]="fieldId('inline-amount-' + c.id)"
              data-testid="claim-inline-amount"
              inputmode="decimal"
              [value]="inlineAmounts()[c.id] ?? ''"
              (input)="setInlineAmount(c.id, value($event))"
            />
            <button
              type="button"
              data-testid="claim-inline-save"
              [attr.aria-label]="'Save amount of claim of ' + c.amount + ' on ' + c.date"
              (click)="saveInline(c)"
            >
              Save amount
            </button>
            <button
              type="button"
              data-testid="claim-edit"
              [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
              (click)="edit(c)"
            >
              Edit
            </button>
            @if (editingId() === c.id) {
              <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
            }
            @if (rowErrors()[c.id]; as message) {
              <p role="alert" class="error" data-testid="claim-error">{{ (message) | figure }}</p>
            }
          </li>
        }
      </ul>
    }
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem;
      align-items: center;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
    .from-template {
      display: flex;
      gap: 0.25rem;
      align-items: center;
      margin-top: 0.25rem;
    }
    .claims {
      margin: 0.25rem 0 0;
      padding-left: 1rem;
    }
    .claims li span {
      margin-right: 0.75rem;
    }
  `,
})
export class Claims {
  private readonly session = inject(Session);
  private readonly service = inject(ClaimsService);
  private readonly audit = inject(AuditService);
  /** The sheet's void reasons: while there are any, a void names one of them. */
  protected readonly voidReasonList = inject(VoidReasonsService).reasons;
  private readonly templates = inject(TemplatesService);
  /** The sheet's claim templates, any of which a DRAFT claim can be added from. */
  protected readonly templateList = this.templates.templates;

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** The grades the person was given, each from its date. */
  readonly grades = input.required<PersonGrade[]>();
  /** The sheet's rates. */
  readonly rates = input.required<Rate[]>();
  /** Every floor recorded on the sheet, each from its date, oldest first: the least a claim's live
   *  due can be is the one in force on its date. */
  readonly floors = input.required<Dated[]>();
  /** The sheet's periods. */
  readonly periods = input.required<Period[]>();
  /** Every late percent recorded on the sheet, each from its date, oldest first, with two decimals. */
  readonly latePcts = input.required<Dated[]>();
  /** Every grace recorded on the sheet for each grade, each from its date, oldest first. */
  readonly graces = input.required<Graces>();
  /** The sheet's policy date in force (YYYY-MM-DD): a claim dated before it rounds half up, one on
   *  or after it half to even; null until read. */
  readonly policyDate = input.required<string | null>();
  /** Every rounding mode recorded, each from its date, oldest first; null records none from then. */
  readonly roundings = input.required<Dated<string | null>[]>();
  /** The other people on the sheet, whom a claim can be moved to. */
  readonly others = input.required<{ id: number; name: string }[]>();
  /** The ids of the claims selected for a bulk action. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** A claim was selected or unselected. */
  readonly selectToggled = output<{ id: number; selected: boolean }>();
  /** A claim was added or saved. */
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly corrects = signal('');
  protected readonly ref = signal('');
  protected readonly tags = signal('');
  protected readonly editingId = signal<number | null>(null);
  /** The name of the template chosen to add a claim from; '' for none. */
  protected readonly template = signal('');
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private submitting = false;
  /** The error of the last refused action on each claim, by claim id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** The kinds a proof may be, as offered on each row. */
  protected readonly proofKinds = PROOF_KINDS;
  /** The proof kind chosen on each claim's row, by claim id; a row not in it has none chosen. */
  protected readonly chosenProofKinds = signal<Record<number, ProofKind>>({});
  /** The timeline shown for each claim, by claim id: its audit lines, newest first. */
  protected readonly timelines = signal<Record<number, string[]>>({});
  /** The reason typed for rejecting each PENDING claim, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});
  /** The reason chosen from the sheet's list for voiding each claim, by claim id. */
  protected readonly voidReasons = signal<Record<number, string>>({});
  /** The comment typed on each claim, by claim id. */
  protected readonly commentTexts = signal<Record<number, string>>({});
  /** The line typed on each draft claim, by claim id. */
  protected readonly lineFields = signal<Record<number, LineFields>>({});
  /** The date typed to settle each claim as of, by claim id; blank settles it as of today. */
  protected readonly settleDates = signal<Record<number, string>>({});
  /** The date typed to defer each claim until, by claim id. */
  protected readonly deferDates = signal<Record<number, string>>({});
  /** The date typed to re-date each claim to, by claim id. */
  protected readonly redates = signal<Record<number, string>>({});
  /** The amount typed to split off each claim, by claim id. */
  protected readonly splitAmounts = signal<Record<number, string>>({});
  /** The amount typed to part-settle on each claim, by claim id. */
  protected readonly settlePartAmounts = signal<Record<number, string>>({});
  /** The amount typed to change each draft claim to in its row, by claim id. */
  protected readonly inlineAmounts = signal<Record<number, string>>({});
  /** The person chosen to move each claim to, by claim id: their id as text. */
  protected readonly reassignTos = signal<Record<number, string>>({});
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'review', label: 'Review' },
    { action: 'settle', label: 'Settle' },
    { action: 'approve', label: 'Approve' },
    { action: 'reject', label: 'Reject' },
    { action: 'void', label: 'Void' },
    { action: 'delete', label: 'Delete' },
    { action: 'restore', label: 'Restore' },
  ];

  protected fieldId(field: string): string {
    return `claim-${this.personId()}-${field}`;
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  /** What is due on the claim at the rate in force on the claim's date of the person's grade in force
   *  then (or the figure a statement bound it to), with its late charge added while it is overdue, and
   *  then never below the sheet's floor in force on its date; n/a when no rate of that grade applies then. A claim settled at a rate keeps what was due on it then; one settled without a rate
   *  fixed nothing. */
  protected dueOf(claim: Claim): string {
    const late = { today: this.session.today(), latePcts: this.latePcts(), graces: this.graces() };
    const rounding = { modes: this.roundings(), policyDate: this.policyDate(), claims: this.claims() };
    const due = claimShownCents(claim, this.rates(), this.grades(), this.floors(), late, rounding);
    return due === null ? 'n/a' : money(due);
  }

  protected dueBy(claim: Claim): string {
    return claim.dueBy;
  }

  protected isOverdue(claim: Claim): boolean {
    return overdue(claim, this.session.today(), overdueFrom(claim, this.grades(), this.graces()));
  }

  /** Whether the claim shows as deferred: deferred now, or voided while deferred until a date still
   *  after the sheet's today (a voided claim keeps the deferral it was voided with). */
  protected isDeferred(claim: Claim): boolean {
    const today = this.session.today();
    if (deferred(claim, today)) return true;
    return claim.status === 'VOID' && claim.deferredUntil !== null && today !== null && today < claim.deferredUntil;
  }

  /** The name of the period whose dates contain the claim's due-by; empty when none does. */
  protected periodOf(claim: Claim): string {
    return periodOn(this.periods(), claim.dueBy)?.name ?? '';
  }

  constructor() {
    // An editor left open on this person's form is open again when the sheet is loaded again, while
    // its claim is still a draft; one that is no longer is forgotten, so the form is back to adding.
    effect(() => {
      const claims = this.claims();
      untracked(() => {
        const sheet = this.session.sheetId();
        const id = sheet ? this.service.editing(sheet, this.personId()) : null;
        if (!sheet || id === null || this.editingId() !== null) return;
        const claim = claims.find((c) => c.id === id);
        if (claim && claim.status !== 'DRAFT') this.service.setEditing(sheet, this.personId(), null);
        else if (claim) this.fill(claim);
      });
    });
  }

  /** Open the claim's editor, locking the claim for other users until it is saved or cancelled, or the
   *  sheet's today changes. Only a DRAFT not dated in a closed period can be edited, and not by a
   *  viewer, so any other edit is refused with an error on the claim, taking no lock (an open claim is
   *  changed by voiding it and adding a new one). A claim another user holds the lock of, one a
   *  viewer opens, or a draft dated in a closed period is refused with an error too, though its editor
   *  shows it: saving it is refused while that lock holds or its period is closed, and always for a viewer. */
  protected async edit(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const refuse = (message: string) => {
      if (this.editingId() === claim.id) this.reset();
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: message }));
    };
    if (claim.status !== 'DRAFT') {
      refuse(
        claim.status === 'DELETED'
          ? 'A deleted claim cannot be edited; restore it first.'
          : 'Only a draft claim can be edited; void it and add a new one instead.',
      );
      return;
    }
    const period = periodOn(this.periods(), claim.dueBy);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (period?.closed && !period.soft) {
      this.refuseClosed(sheet, claim);
      return;
    }
    const was = this.editingId();
    // The form shows the claim once the lock is answered, so it never shows as open before a refusal.
    this.release(sheet, was, claim.id);
    const before = this.formFields();
    try {
      await this.service.lock(sheet, claim.id);
      if (this.session.sheetId() === sheet) this.fill(claim, before);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      if (status === 412 || status === 403) {
        this.rowErrors.update((errors) => ({
          ...errors,
          [claim.id]:
            status === 403
              ? 'Your role may not edit a claim on this sheet.'
              : 'Another user is editing that claim; try again once they save or cancel it.',
        }));
        this.fill(claim);
        return;
      }
      if (status === 423) this.refuseClosed(sheet, claim);
      else refuse('Only a draft claim can be edited; void it and add a new one instead.');
      return;
    }
  }

  /** Refuse to edit the draft dated in a closed period with an error on the claim, its editor showing it
   *  without a lock: saving it is refused while its period is closed. */
  private refuseClosed(sheet: string, claim: Claim): void {
    this.release(sheet, this.editingId(), claim.id);
    this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'That claim is dated in a closed period.' }));
    this.fill(claim);
  }

  /** Release the lock of the editor that was open before another claim's editor replaced it. */
  private release(sheet: string, was: number | null, now: number): void {
    if (was !== null && was !== now) this.service.unlock(sheet, was).catch(() => undefined);
  }

  /** The form's fields as they are now. */
  private formFields(): Record<FormField, string> {
    return {
      amount: this.amount(),
      date: this.date(),
      note: this.note(),
      corrects: this.corrects(),
      ref: this.ref(),
      tags: this.tags(),
    };
  }

  /** Show the claim in the form, its editor open. Given the fields as they were when the editor was
   *  asked for, a field typed into while its lock was being answered keeps what was typed. */
  private fill(claim: Claim, before?: Record<FormField, string>): void {
    const sheet = this.session.sheetId();
    if (sheet) this.service.setEditing(sheet, this.personId(), claim.id);
    this.editingId.set(claim.id);
    const now = this.formFields();
    const set = (field: FormField, value: string, into: WritableSignal<string>) => {
      if (!before || now[field] === before[field]) into.set(value);
    };
    set('amount', claim.amount, this.amount);
    set('date', claim.date, this.date);
    set('note', claim.note, this.note);
    set('corrects', claim.corrects ?? '', this.corrects);
    set('ref', claim.ref ?? '', this.ref);
    set('tags', claim.tags.join(', '), this.tags);
    this.error.set(null);
  }

  /** Close the claim's editor without saving, releasing the lock on it. */
  protected cancel(): void {
    const sheet = this.session.sheetId();
    const id = this.editingId();
    this.reset();
    if (sheet && id !== null) this.service.unlock(sheet, id).catch(() => undefined);
  }

  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((reasons) => ({ ...reasons, [id]: reason }));
    this.clearRowError(id);
  }

  protected setVoidReason(id: number, reason: string): void {
    this.voidReasons.update((reasons) => ({ ...reasons, [id]: reason }));
    this.clearRowError(id);
  }

  /** Change the claim's status; an open, settle or void the sheet's permission matrix does not allow
   *  the user's role, a change its status does not allow, a settle by the user who opened
   *  the claim, an approve by a user who is not a steward or who opened the claim, a reject by a user
   *  who is not a steward or without a one-word reason, is refused with an error. A clerk's settle of
   *  a claim whose due is above the clerk limit leaves it PENDING for a steward. A settle or approve of a
   *  claim without proofs whose due is above the proof threshold in force on its date is refused, and so
   *  is an open of a claim with an empty note whose due is above the note threshold in force then. An
   *  open of a claim whose due is above the review threshold in force then leaves it in REVIEW, and only
   *  a steward's review makes it OPEN. */
  protected async act(claim: Claim, action: ClaimAction): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const reason = (this.rejectReasons()[claim.id] ?? '').trim();
    if (action === 'reject' && !/^\S{1,200}$/.test(reason)) {
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'Type a one-word reason to reject that claim.' }));
      return;
    }
    const voidReason = this.voidReasons()[claim.id] ?? '';
    if (action === 'void' && this.voidReasonList().length > 0 && voidReason === '') {
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'Choose a reason to void that claim.' }));
      return;
    }
    const settleDate = (this.settleDates()[claim.id] ?? '').trim();
    try {
      const given = action === 'reject' ? reason : action === 'void' && voidReason !== '' ? voidReason
        : action === 'settle' && settleDate !== '' ? settleDate : undefined;
      const changed = await this.service.act(sheet, claim.id, action, given);
      if (this.session.sheetId() !== sheet) return;
      if (action === 'reject') this.rejectReasons.update(({ [claim.id]: _, ...rest }) => rest);
      if (action === 'void') this.voidReasons.update(({ [claim.id]: _, ...rest }) => rest);
      if (action === 'settle') this.settleDates.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 403
          ? action === 'settle' && claim.openedBy === this.session.user()
            ? 'You opened that claim, so another user must settle it.'
            : action === 'settle' && claim.editedBy === this.session.user()
            ? 'You last edited that claim, so another user must settle it.'
            : action === 'void' && claim.status === 'SETTLED'
            ? 'Only a steward can void a settled claim.'
            : action === 'review'
            ? 'Only a steward can review that claim.'
            : action === 'approve' || action === 'reject'
            ? `Only a steward or the person's approver${action === 'approve' ? ' who did not open or last edit that claim' : ''} can ${action} it.`
            : `Your role may not ${action} a claim on this sheet.`
          : e instanceof HttpErrorResponse && e.status === 400 && action === 'settle'
          ? "A claim can only be settled as of a date on or before the sheet's today, and not before it was added."
          : e instanceof HttpErrorResponse && e.status === 400
          ? 'Type a one-word reason to reject that claim.'
          : e instanceof HttpErrorResponse && e.status === 410 && action === 'restore'
          ? 'A deleted claim can only be restored on the day it was deleted.'
          : e instanceof HttpErrorResponse && e.status === 410
          ? 'A settled claim can only be voided within 7 days of its settlement.'
          : e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : e instanceof HttpErrorResponse && e.status === 409 && action === 'open' && claim.status === 'DRAFT'
          ? 'Its person already has as many open claims in that claim\'s period as the period allows.'
          : e instanceof HttpErrorResponse && e.status === 422 && action === 'void'
          ? 'Choose a reason from the sheet\'s list to void that claim.'
          : e instanceof HttpErrorResponse && e.status === 422 && action === 'open'
          ? 'That claim is due above the note threshold, so it needs a note before it can be opened.'
          : e instanceof HttpErrorResponse && e.status === 422
          ? `That claim is due above the proof threshold, so it needs a proof (one of each listed proof kind) before it can be ${PAST[action]}.`
          : action === 'settle' && this.isDeferred(claim)
          ? `That claim is deferred until ${claim.deferredUntil}.`
          : `That claim cannot be ${PAST[action]} in its status.`,
      }));
    }
  }

  /** The proof kind chosen on the claim's row; blank when none is (a receipt). */
  protected proofKindOf(claim: Claim): ProofKind | '' {
    return this.chosenProofKinds()[claim.id] ?? '';
  }

  /** Choose the kind of the next proof added to the claim. */
  protected chooseProofKind(claim: Claim, event: Event): void {
    this.clearRowError(claim.id);
    const kind = (event.target as HTMLSelectElement).value;
    this.chosenProofKinds.update(({ [claim.id]: _, ...rest }) =>
      PROOF_KINDS.includes(kind as ProofKind) ? { ...rest, [claim.id]: kind as ProofKind } : rest);
  }

  /** Raise the claim's proofs count by one, of the kind chosen on its row (none chosen is a receipt), which
   *  is unchosen once it is added; a SETTLED, VOID or DELETED claim, or one dated in a closed period,
   *  is refused with an error. */
  protected async addProof(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const kind = this.chosenProofKinds()[claim.id] ?? null;
    try {
      const changed = await this.service.addProof(sheet, claim.id, kind);
      if (this.session.sheetId() !== sheet) return;
      this.chosenProofKinds.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : 'A settled or void claim cannot be given a proof.',
      }));
    }
  }

  /** Write a reminder of the OPEN or PENDING claim, once per sheet's today; a viewer's, one on a claim
   *  in any other status or dated in a closed period, and a second one the same day are refused with an
   *  error. */
  /** Read the claim's timeline afresh; the one shown before is gone while it is read. */
  protected async showTimeline(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    this.timelines.update(({ [claim.id]: _, ...rest }) => rest);
    try {
      const lines = await this.audit.claimLines(sheet, claim.id);
      if (this.session.sheetId() !== sheet) return;
      this.timelines.update((all) => ({ ...all, [claim.id]: lines }));
    } catch {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'The timeline could not be read.' }));
    }
  }

  protected async remind(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const changed = await this.service.remind(sheet, claim.id);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: status === 403
          ? 'You may not remind a claim.'
          : status === 422
          ? "That claim was already reminded on the sheet's today."
          : status === 423
          ? 'That claim is dated in a closed period.'
          : 'Only an open or pending claim can be reminded.',
      }));
    }
  }

  /** Hold the OPEN or PENDING claim, or release it when it is held; a claim in any other status is
   *  refused with an error. */
  protected async hold(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const changed = await this.service.hold(sheet, claim.id);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(changed);
    } catch {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: 'Only an open or pending claim can be held or released.',
      }));
    }
  }

  /** Waive the late charge of the OPEN or PENDING claim; anyone but a steward, a claim in any other
   *  status and one already waived are refused with an error. */
  protected async waive(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const changed = await this.service.waive(sheet, claim.id);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: status === 403
          ? 'Only a steward can waive a late charge.'
          : status === 422
          ? "That claim's late charge is already waived."
          : 'Only an open or pending claim can have its late charge waived.',
      }));
    }
  }

  /** Settle the SETTLED claim again at what would be due on it today were it open and bound by no
   *  statement; only a steward can, and not on a claim dated in a closed period, so anything else is
   *  refused with an error. */
  protected async resettle(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const changed = await this.service.resettle(sheet, claim.id);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can re-settle a claim.'
          : e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : 'Only a settled claim can be re-settled.',
      }));
    }
  }

  protected setLineField(id: number, field: keyof LineFields, text: string): void {
    this.lineFields.update((lines) => ({
      ...lines,
      [id]: { ...{ label: '', qty: '', unit: '' }, ...lines[id], [field]: text },
    }));
    this.clearRowError(id);
  }

  /** Add the line typed on its row to the claim: a label, a whole quantity of at least 1 and a unit
   *  amount with up to two decimals, on a DRAFT not dated in a closed period nor held by another user's
   *  editor; anything else is refused with an error on the claim. */
  protected async addLine(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const refuse = (message: string) => this.rowErrors.update((errors) => ({ ...errors, [claim.id]: message }));
    const typed = this.lineFields()[claim.id] ?? { label: '', qty: '', unit: '' };
    const line = { label: typed.label.trim(), qty: typed.qty.trim(), unit: typed.unit.trim() };
    if (line.label === '') {
      refuse('Type a label for the line.');
      return;
    }
    if (!/^0*[1-9]\d{0,5}$/.test(line.qty)) {
      refuse('Type a whole quantity of at least 1.');
      return;
    }
    if (!/^\d{1,10}(\.\d{1,2})?$/.test(line.unit)) {
      refuse('Type a unit amount with up to two decimals.');
      return;
    }
    try {
      const changed = await this.service.addLine(sheet, claim.id, line);
      if (this.session.sheetId() !== sheet) return;
      this.lineFields.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      refuse(
        status === 403
          ? 'Your role may not edit a claim on this sheet.'
          : status === 423
          ? 'That claim is dated in a closed period.'
          : status === 412
          ? 'Another user is editing that claim; a line cannot be added until they save or cancel it.'
          : status === 409
          ? 'Only a draft claim can have lines added.'
          : 'That line could not be added.',
      );
    }
  }

  protected setCommentText(id: number, text: string): void {
    this.commentTexts.update((texts) => ({ ...texts, [id]: text }));
    this.clearRowError(id);
  }

  /** Comment on the claim with the text typed on its row; an empty comment, and a viewer's or one on an
   *  archived sheet or its as-of view, is refused with an error. */
  protected async comment(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = (this.commentTexts()[claim.id] ?? '').trim();
    if (text === '') {
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'Type a comment to add to that claim.' }));
      return;
    }
    try {
      const changed = await this.service.comment(sheet, claim.id, text);
      if (this.session.sheetId() !== sheet) return;
      this.commentTexts.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 403
          ? 'You may not comment on that claim.'
          : 'That comment could not be added.',
      }));
    }
  }

  protected setSettleDate(id: number, on: string): void {
    this.settleDates.update((dates) => ({ ...dates, [id]: on }));
    this.clearRowError(id);
  }

  protected setDeferDate(id: number, until: string): void {
    this.deferDates.update((dates) => ({ ...dates, [id]: until }));
    this.clearRowError(id);
  }

  /** Defer the claim until the date typed on its row; only an OPEN claim not dated in a closed period
   *  can be deferred, and only to a date after the sheet's today, so anything else is refused with an
   *  error. */
  protected async defer(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const until = (this.deferDates()[claim.id] ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(until)) {
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'Choose the date to defer that claim until.' }));
      return;
    }
    try {
      const changed = await this.service.defer(sheet, claim.id, until);
      if (this.session.sheetId() !== sheet) return;
      this.deferDates.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : e instanceof HttpErrorResponse && e.status === 422
          ? "A claim can only be deferred to a date after the sheet's today."
          : 'Only an open claim can be deferred.',
      }));
    }
  }

  protected setRedate(id: number, date: string): void {
    this.redates.update((dates) => ({ ...dates, [id]: date }));
    this.clearRowError(id);
  }

  /** Re-date the claim to the date typed on its row; only a steward can re-date an OPEN or PENDING
   *  claim, neither it nor the new date in a closed period and the new date not after its person's
   *  retirement, so anything else is refused with an error. */
  protected async redate(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const date = (this.redates()[claim.id] ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'Choose the date to re-date that claim to.' }));
      return;
    }
    try {
      const changed = await this.service.redate(sheet, claim.id, date);
      if (this.session.sheetId() !== sheet) return;
      this.redates.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: status === 403
          ? 'Only a steward can re-date a claim.'
          : status === 423
          ? 'Neither the claim nor its new date can be in a closed period.'
          : status === 409
          ? "A claim cannot be dated after its person's retirement."
          : 'Only an open or pending claim can be re-dated.',
      }));
    }
  }

  protected setInlineAmount(id: number, amount: string): void {
    this.inlineAmounts.update((amounts) => ({ ...amounts, [id]: amount }));
    this.clearRowError(id);
  }

  /** Change the claim's amount to the one typed on its row, keeping its date, note and correction, by the
   *  claim form's rules: an amount with up to two decimals (a negative one only on a correction), a
   *  DRAFT not dated in a closed period nor held by another user's editor, and not by a viewer; anything
   *  else is refused with an error on the claim. */
  protected async saveInline(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const refuse = (message: string) => this.rowErrors.update((errors) => ({ ...errors, [claim.id]: message }));
    const amount = (this.inlineAmounts()[claim.id] ?? '').trim();
    if (!AMOUNT.test(amount)) {
      refuse('Type an amount with up to two decimals.');
      return;
    }
    try {
      const changed = await this.service.update(sheet, claim.id, {
        amount,
        date: claim.date,
        note: claim.note,
        corrects: claim.corrects,
        ref: claim.ref,
        tags: claim.tags.join(','),
      });
      if (this.session.sheetId() !== sheet) return;
      this.inlineAmounts.update(({ [claim.id]: _, ...rest }) => rest);
      // Saving releases the claim's lock, so an editor of it left open on the form is closed.
      if (this.editingId() === claim.id) this.reset();
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      refuse(
        status === 403
          ? 'Your role may not edit a claim on this sheet.'
          : status === 423
          ? 'That claim is dated in a closed period.'
          : status === 412
          ? 'Another user is editing that claim; it cannot be saved until they save or cancel it.'
          : status === 409
          ? 'Only a draft claim can be edited; void it and add a new one instead.'
          : status === 400
          ? 'A negative amount is a correction: name the number of a settled claim of this person it corrects, for no more than what remains of it.'
          : status === 422
          ? 'A claim cannot be dated after the person retired.'
          : status === 417
          ? 'A claim with lines has the amount its lines add up to; add a line to change it.'
          : 'That claim could not be saved.',
      );
    }
  }

  protected setSplitAmount(id: number, amount: string): void {
    this.splitAmounts.update((amounts) => ({ ...amounts, [id]: amount }));
    this.clearRowError(id);
  }

  /** Split the amount typed on its row off the claim into a new OPEN claim; only an OPEN claim that is
   *  not deferred nor dated in a closed period can be split, and only by an amount more than 0.00 and
   *  less than its own, so anything else is refused with an error. */
  protected async split(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = (this.splitAmounts()[claim.id] ?? '').trim();
    if (!/^\d{1,10}(\.\d{1,2})?$/.test(amount) || cents(amount) <= 0 || cents(amount) >= cents(claim.amount)) {
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: 'Type an amount more than 0.00 and less than the claim\'s amount to split off.',
      }));
      return;
    }
    try {
      const [kept, made] = await this.service.split(sheet, claim.id, amount);
      if (this.session.sheetId() !== sheet) return;
      this.splitAmounts.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(kept);
      this.saved.emit(made);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : e instanceof HttpErrorResponse && (e.status === 422 || e.status === 400)
          ? 'Type an amount more than 0.00 and less than the claim\'s amount to split off.'
          : 'Only an open claim that is not deferred can be split.',
      }));
    }
  }

  protected setSettlePartAmount(id: number, amount: string): void {
    this.settlePartAmounts.update((amounts) => ({ ...amounts, [id]: amount }));
    this.clearRowError(id);
  }

  /** Split the amount typed on its row off the claim, as a split does, and settle the new claim at once,
   *  as a settle does (left PENDING over the clerk limit); a split or settle that would be refused
   *  refuses the whole action with an error. */
  protected async settlePart(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = (this.settlePartAmounts()[claim.id] ?? '').trim();
    if (!/^\d{1,10}(\.\d{1,2})?$/.test(amount) || cents(amount) <= 0 || cents(amount) >= cents(claim.amount)) {
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: 'Type an amount more than 0.00 and less than the claim\'s amount to settle.',
      }));
      return;
    }
    try {
      const [kept, made] = await this.service.settlePart(sheet, claim.id, amount);
      if (this.session.sheetId() !== sheet) return;
      this.settlePartAmounts.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(kept);
      this.saved.emit(made);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : e instanceof HttpErrorResponse && (e.status === 422 || e.status === 400)
          ? 'Type an amount more than 0.00 and less than the claim\'s amount to settle.'
          : 'That claim cannot be part-settled by you now.',
      }));
    }
  }

  protected setReassignTo(id: number, personId: string): void {
    this.reassignTos.update((chosen) => ({ ...chosen, [id]: personId }));
    this.clearRowError(id);
  }

  /** Move the claim to the person chosen on its row; only a steward can, and only a DRAFT, OPEN or
   *  PENDING claim that is not a correction moves, so any other move is refused with an error. */
  protected async reassign(claim: Claim): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const to = Number(this.reassignTos()[claim.id] ?? '');
    if (!to) {
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'Choose the person to move that claim to.' }));
      return;
    }
    try {
      const moved = await this.service.reassign(sheet, claim.id, to);
      if (this.session.sheetId() !== sheet) return;
      this.reassignTos.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(moved);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role may not move a claim on this sheet.'
          : e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : e instanceof HttpErrorResponse && e.status === 422
          ? 'That claim is dated after that person retired.'
          : 'Only a draft, open or pending claim that is not a correction can be moved to another person.',
      }));
    }
  }

  private clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected reset(): void {
    const sheet = this.session.sheetId();
    if (sheet) this.service.setEditing(sheet, this.personId(), null);
    this.editingId.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.ref.set('');
    this.tags.set('');
    this.error.set(null);
  }

  /** Add a DRAFT claim from the chosen template: its amount and note as they are now, dated like a
   *  claim with no date, refused as the claim form refuses it. The claim form and any editor open on it
   *  are left as they are. */
  protected async addFromTemplate(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.template();
    if (name === '') {
      this.error.set('Choose a template.');
      return;
    }
    this.submitting = true;
    try {
      const claim = await this.templates.claim(sheet, this.personId(), name);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? 'A claim dated in a closed period cannot be added.'
          : e instanceof HttpErrorResponse && e.status === 422
            ? 'A claim cannot be dated after the person retired.'
          : e instanceof HttpErrorResponse && e.status === 400
            ? 'A negative amount is a correction: add it with the claim form, naming the claim it corrects.'
          : e instanceof HttpErrorResponse && e.status === 404
            ? 'That template is no longer on the sheet.'
          : 'That claim could not be added.',
      );
    } finally {
      this.submitting = false;
    }
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Type an amount with up to two decimals.');
      return;
    }
    const corrects = this.corrects().trim();
    if (corrects !== '' && !NUMBER.test(corrects)) {
      this.error.set('Type the number of the claim it corrects.');
      return;
    }
    const fields = {
      amount,
      date: this.date().trim() || null,
      note: this.note(),
      corrects: corrects === '' ? null : corrects,
      ref: this.ref().trim() || null,
      tags: this.tags().trim() || null,
    };
    const id = this.editingId();
    // A save is the next action on the claim being edited: an error left on its row goes.
    if (id !== null) this.clearRowError(id);
    this.submitting = true;
    try {
      const claim =
        id === null
          ? await this.service.add(sheet, this.personId(), fields)
          : await this.service.update(sheet, id, fields);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
      this.reset();
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? 'A claim dated in a closed period cannot be changed.'
          : e instanceof HttpErrorResponse && e.status === 417
            ? 'A claim with lines has the amount its lines add up to; add a line to change it.'
          : e instanceof HttpErrorResponse && e.status === 422
            ? 'A claim cannot be dated after the person retired.'
          : e instanceof HttpErrorResponse && e.status === 400
            ? 'A negative amount is a correction: name the number of a settled claim of this person it corrects, for no more than what remains of it (and only a correction names one).'
          : fields.ref !== null && e instanceof HttpErrorResponse && e.status === 409
            ? id === null
              ? 'That reference is already used on this sheet.'
              : 'That reference is already used on this sheet, or the claim is no longer a draft.'
          : id !== null && e instanceof HttpErrorResponse && e.status === 409
            ? 'Only a draft claim can be edited; void it and add a new one instead.'
          : id !== null && e instanceof HttpErrorResponse && e.status === 412
            ? 'Another user is editing that claim; it cannot be saved until they save or cancel it.'
          : id === null
            ? 'That claim could not be added.'
            : 'That claim could not be saved.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
