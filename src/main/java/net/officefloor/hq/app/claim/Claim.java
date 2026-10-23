package net.officefloor.hq.app.claim;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A claim a person on a sheet makes: an amount (two decimals), a date, a note and a status (DRAFT
 * when added). A claim with a negative amount is a correction, naming the number of the SETTLED claim
 * of the same person it corrects.
 */
@Entity
@Table(name = "claim")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    /** The place the claim is listed at, newest first: given by the store when added, and again when moved by a merge. */
    @Column(name = "listed", insertable = false, updatable = false)
    private Long listed;

    /**
     * The claim's place in the order claims are added: given by the store when added to a sheet, and
     * again when transferred to another; null until stored (newer than every rate). A forward-only rate
     * applies only to claims added after it was saved.
     */
    @Column(name = "added_seq", insertable = false, updatable = false)
    private Long added;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    /**
     * The date the claim is due by: the terms-th business day after its due-from date (see
     * {@link BusinessDays#dueBy}), worked out again when a holiday on or before it is added unless it is SETTLED or VOID.
     */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The terms, in business days, fixed when the claim was added: those in force on the sheet's today then, from its person's own terms, else their grade's. */
    @Column(name = "terms", nullable = false)
    private int terms;

    /** The date the claim's terms count from: its date when it was added (a later change of date keeps it). */
    @Column(name = "due_from", nullable = false)
    private LocalDate dueFrom;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** The sheet's number given to the claim when opened; null while DRAFT, and kept once VOID. */
    @Column(name = "claim_number")
    private String number;

    /** The due fixed when the claim was settled; null otherwise, or when the grade had no rate then. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    /** The user who opened the claim; null until it is opened. */
    @Column(name = "opened_by")
    private String openedBy;

    /** The user who last edited the claim; null when it was never edited. */
    @Column(name = "edited_by")
    private String editedBy;

    /** The number of the bulk settle batch that settled the claim; null when not settled by one. */
    @Column(name = "settle_batch")
    private Integer settleBatch;


    /** Whether the claim is bound by its person's last statement. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The due figure the claim was bound to by the statement; null when unbound, or when it was n/a. */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /**
     * Whether the bound figure is the claim's whole due as shown, late charge and floor included;
     * false for a figure bound before statements bound the late charge (the rate figure alone).
     */
    @Column(name = "stated_whole", nullable = false)
    private boolean statedWhole;

    /** The number of the claim this one corrects; null unless it is a correction. */
    @Column(name = "corrects")
    private String corrects;

    /** The claim's external reference; null when it has none. */
    @Column(name = "ref")
    private String ref;

    /** The reference as compared on the sheet (lower case); null when it has none. */
    @Column(name = "ref_key")
    private String refKey;

    /** The claim's tags, sorted and comma separated; null when it has none. */
    @Column(name = "tags")
    private String tags;

    /** Every tag the claim has ever carried, sorted and comma separated; null when none. */
    @Column(name = "tags_used")
    private String tagsUsed;

    /** The date the claim is deferred until; null when it was never deferred. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** Whether a steward waived the claim's late charge; false when added. */
    @Column(name = "waived", nullable = false)
    private boolean waived;

    /** Whether the claim is held: held, it still counts in every total, and only marks the claim; false when added. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** The number of the claim this one was split from; null unless it was split off another. */
    @Column(name = "split_from")
    private String splitFrom;

    /** How many proofs the claim has; 0 when added. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** How many of the claim's proofs are receipts. */
    @Column(name = "proofs_receipt", nullable = false)
    private int proofsReceipt;

    /** How many of the claim's proofs are photos. */
    @Column(name = "proofs_photo", nullable = false)
    private int proofsPhoto;

    /** The last day (the sheet's today) a reminder was written for the claim; null when never reminded. */
    @Column(name = "reminded_on")
    private LocalDate remindedOn;

    /** Whether a reminder ever escalated the claim; a claim escalates once at most. */
    @Column(name = "escalated", nullable = false)
    private boolean escalated;

    /** The user whose open editor locks the claim for everyone else; null when no editor holds it. */
    @Column(name = "locked_by")
    private String lockedBy;

    /** Whether the claim was added while a period its date or due-by is in was soft-closed. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /** The reason from the sheet's list the claim was voided with; null when not voided with one. */
    @Column(name = "void_reason")
    private String voidReason;

    /** The claim's status log, oldest first. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "claim_id", nullable = false)
    @OrderBy("id")
    private List<ClaimLogLine> log = new ArrayList<>();

    /** The claim's comments, oldest first. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "claim_id", nullable = false)
    @OrderBy("id")
    private List<ClaimComment> comments = new ArrayList<>();

    /** The claim's lines, in the order they were added. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "claim_id", nullable = false)
    @OrderBy("id")
    private List<ClaimLine> lines = new ArrayList<>();

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, int terms, LocalDate dueBy,
            String note, String corrects) {
        this.terms = terms;
        this.dueFrom = date;
        this.dueBy = dueBy;
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.note = note;
        this.corrects = corrects;
    }

    public Long getId() {
        return id;
    }

    /** The claim's place in the order claims are added; null until stored. */
    public Long getAdded() {
        return added;
    }

    /** Hold the place in the order claims are added kept for the claim, for a claim held in no store. */
    public void keepAdded(Long added) {
        this.added = added;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getPersonId() {
        return personId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getDate() {
        return date;
    }

    public LocalDate getDueBy() {
        return dueBy;
    }

    public int getTerms() {
        return terms;
    }

    public LocalDate getDueFrom() {
        return dueFrom;
    }

    /** Work the claim's due-by out again against the sheet's holidays now; its terms and due-from are kept. */
    public void workOutDueBy(Set<LocalDate> holidays) {
        this.dueBy = BusinessDays.dueBy(dueFrom, terms, holidays);
    }

    public String getNote() {
        return note;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public String getNumber() {
        return number;
    }

    public BigDecimal getSettledDue() {
        return settledDue;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    /** Record the user who opened the claim. */
    public void openedBy(String user) {
        this.openedBy = user;
    }

    public String getEditedBy() {
        return editedBy;
    }

    /** Record the user who last edited the claim. */
    public void editedBy(String user) {
        this.editedBy = user;
    }

    public Integer getSettleBatch() {
        return settleBatch;
    }

    /** Record the bulk settle batch that settled the claim. */
    public void settledInBatch(int batch) {
        this.settleBatch = batch;
    }

    /** Whether the user opened or last edited the claim, and so cannot settle or approve it. */
    public boolean isOpenedOrEditedBy(String user) {
        return user.equals(openedBy) || user.equals(editedBy);
    }

    /** Fix the claim's due as it is settled. */
    public void settleDue(BigDecimal due) {
        this.settledDue = due;
    }

    /** Move the claim to a new status; the caller checks the move is allowed. */
    public void become(ClaimStatus status) {
        this.status = status;
    }

    public String getVoidReason() {
        return voidReason;
    }

    /** Record the reason the claim is voided with; null for none. */
    public void voidedWith(String reason) {
        this.voidReason = reason;
    }

    /** Give the claim its number when it is opened; it keeps it from then on. */
    public void number(String number) {
        this.number = number;
    }

    public String getCorrects() {
        return corrects;
    }

    public String getRef() {
        return ref;
    }

    /** Give the claim an external reference; null for none. */
    public void refer(String ref) {
        this.ref = ref;
        this.refKey = keyOf(ref);
    }

    /** The claim's tags, sorted; empty when it has none. */
    public java.util.List<String> getTags() {
        return tags == null ? java.util.List.of() : java.util.List.of(tags.split(","));
    }

    /** Give the claim its tags (see {@link #tagsOf}); empty for none. */
    public void tag(java.util.List<String> tags) {
        this.tags = tags.isEmpty() ? null : String.join(",", tags);
        java.util.List<String> used = java.util.stream.Stream.concat(getTagsUsed().stream(), tags.stream())
                .distinct().sorted().toList();
        this.tagsUsed = used.isEmpty() ? null : String.join(",", used);
    }

    /** Every tag the claim has ever carried, sorted; empty when none. */
    public java.util.List<String> getTagsUsed() {
        return tagsUsed == null ? java.util.List.of() : java.util.List.of(tagsUsed.split(","));
    }

    /**
     * Tags as a comma separated text gives them: each trimmed and lower case, blanks and repeats
     * dropped, sorted.
     */
    public static java.util.List<String> tagsOf(String text) {
        if (text == null) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(text.split(","))
                .map((t) -> t.trim().toLowerCase(java.util.Locale.ROOT))
                .filter((t) -> !t.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /** A reference as compared on a sheet, without regard to case; null for none. */
    public static String keyOf(String ref) {
        return ref == null ? null : ref.toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether the claim is a correction: its amount is below 0.00. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    /** Change the claim's amount, date, note and the number it corrects. */
    public void change(BigDecimal amount, LocalDate date, String note, String corrects) {
        this.amount = amount;
        this.date = date;
        this.note = note;
        this.corrects = corrects;
    }

    public boolean isStated() {
        return stated;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    public boolean isStatedWhole() {
        return statedWhole;
    }

    /** Bind the claim to its due as shown, late charge and floor included (null when n/a), as a statement is taken. */
    public void bind(BigDecimal due) {
        bind(due, true);
    }

    /** Bind the claim to a figure (null when n/a): its whole due as shown, or its rate figure alone. */
    public void bind(BigDecimal due, boolean whole) {
        this.stated = true;
        this.statedDue = due;
        this.statedWhole = whole;
    }

    /** Move the claim to a new date; its due-by, number and any bound figure are kept. */
    public void redate(LocalDate date) {
        this.date = date;
    }

    /** Move the claim to another person on the sheet. */
    public void reassign(Long personId) {
        this.personId = personId;
    }

    /**
     * Move the claim with its person to another sheet: its status, dates, note, fixed figures, log
     * and any figure a statement bound are kept; it loses its reference, its settle batch and any
     * editor's lock. The caller gives it the other sheet's number.
     */
    public void transferTo(String sheetId) {
        this.sheetId = sheetId;
        refer(null);
        this.settleBatch = null;
        this.lockedBy = null;
    }

    /** Name other numbers for the claims this one corrects and was split from, as the map gives them. */
    public void renumberLinks(java.util.Map<String, String> numbers) {
        if (corrects != null) {
            this.corrects = numbers.getOrDefault(corrects, corrects);
        }
        if (splitFrom != null) {
            this.splitFrom = numbers.getOrDefault(splitFrom, splitFrom);
        }
    }

    /** The day the claim was first opened, as its status log records it; null when the log has none. */
    public LocalDate openedOn() {
        for (ClaimLogLine line : log) {
            if (line.getStatus() == ClaimStatus.OPEN) {
                return line.getOn();
            }
        }
        return null;
    }

    /** Leave the claim unbound, its due live again. */
    public void unbind() {
        this.stated = false;
        this.statedDue = null;
        this.statedWhole = false;
    }

    public LocalDate getDeferredUntil() {
        return deferredUntil;
    }

    /** Defer the claim until a date; the caller checks the deferral is allowed. */
    public void defer(LocalDate until) {
        this.deferredUntil = until;
    }

    /**
     * Whether the claim is deferred on a date (the sheet's today): it is OPEN and the date has not yet
     * reached the date it is deferred until.
     */
    public boolean isDeferredOn(LocalDate today) {
        return status == ClaimStatus.OPEN && deferredUntil != null && today != null && today.isBefore(deferredUntil);
    }

    public boolean isWaived() {
        return waived;
    }

    /** Waive the claim's late charge; the caller checks the waiver is allowed. */
    public void waive() {
        this.waived = true;
    }

    public boolean isHeld() {
        return held;
    }

    /** Hold or release the claim; the caller checks the claim may be. */
    public void hold(boolean held) {
        this.held = held;
    }

    public String getSplitFrom() {
        return splitFrom;
    }

    /**
     * Split an amount off the claim: it keeps the rest of its amount and loses any figure a statement
     * bound it to. The new claim, OPEN with the split-off amount, the same date and note, names this
     * claim's number as the one it was split from; the caller numbers it and records who opened it.
     */
    public Claim split(BigDecimal off, int newTerms, LocalDate newDueBy) {
        Claim split = new Claim(sheetId, personId, off, date, newTerms, newDueBy, note, null);
        split.status = ClaimStatus.OPEN;
        split.splitFrom = number;
        this.amount = amount.subtract(off);
        unbind();
        return split;
    }

    public int getProofs() {
        return proofs;
    }

    /** How many of the claim's proofs are of a kind (receipt or photo). */
    public int getProofs(String kind) {
        return switch (kind) {
            case "receipt" -> proofsReceipt;
            case "photo" -> proofsPhoto;
            default -> throw new IllegalArgumentException("No such proof kind: " + kind);
        };
    }

    /** Raise the claim's proofs count, and its count of the kind (receipt or photo), by one; the caller checks the claim can take a proof. */
    public void addProof(String kind) {
        switch (kind) {
            case "receipt" -> proofsReceipt++;
            case "photo" -> proofsPhoto++;
            default -> throw new IllegalArgumentException("No such proof kind: " + kind);
        }
        proofs++;
    }

    public LocalDate getRemindedOn() {
        return remindedOn;
    }

    /** Mark the claim reminded on a day; the caller checks the claim can be reminded. */
    public void remind(LocalDate on) {
        this.remindedOn = on;
    }

    public boolean isEscalated() {
        return escalated;
    }

    /** Mark the claim escalated; the caller checks it was not already. */
    public void escalate() {
        this.escalated = true;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    /** Whether the claim was added while a period its date or due-by is in was soft-closed. */
    public boolean isLateEntry() {
        return lateEntry;
    }

    /** Mark the claim as added while its period was soft-closed. */
    public void markLateEntry() {
        this.lateEntry = true;
    }

    /** Whether an editor of a user other than this one holds the claim. */
    public boolean isLockedAgainst(String user) {
        return lockedBy != null && !lockedBy.equals(user);
    }

    /** Lock the claim for the user's editor (null: release it); the caller checks the lock is theirs to take. */
    public void lockFor(String user) {
        this.lockedBy = user;
    }

    public List<ClaimComment> getComments() {
        return List.copyOf(comments);
    }

    public List<ClaimLine> getLines() {
        return List.copyOf(lines);
    }

    /** Add a line to the claim: its amount becomes the totals of its lines added up. */
    public void addLine(String label, int qty, BigDecimal unit) {
        lines.add(new ClaimLine(label, qty, unit));
        this.amount = lines.stream().map(ClaimLine::total).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
    }

    /** Add a comment to the claim, by the user on the sheet's today. */
    public void comment(String by, LocalDate on, String text) {
        comments.add(new ClaimComment(by, on, text));
    }

    public List<ClaimLogLine> getLog() {
        return List.copyOf(log);
    }

    /**
     * The status the claim had at the end of a day, as its status log records it: the last line dated
     * on or before it, or null when the claim was added after it. A claim with no log (added before
     * the log) has its current status.
     */
    public ClaimStatus statusAt(LocalDate day) {
        if (log.isEmpty()) {
            return status;
        }
        ClaimStatus then = null;
        for (ClaimLogLine line : log) {
            if (!line.getOn().isAfter(day)) {
                then = line.getStatus();
            }
        }
        return then;
    }

    /**
     * The day the claim was last settled, as its status log records it: the date of its last SETTLED
     * line, or null when the log has none.
     */
    public LocalDate settledOn() {
        LocalDate on = null;
        for (ClaimLogLine line : log) {
            if (line.getStatus() == ClaimStatus.SETTLED) {
                on = line.getOn();
            }
        }
        return on;
    }

    /**
     * The day the claim was last deleted, as its status log records it: the date of its last DELETED
     * line, or null when the log has none.
     */
    public LocalDate deletedOn() {
        LocalDate on = null;
        for (ClaimLogLine line : log) {
            if (line.getStatus() == ClaimStatus.DELETED) {
                on = line.getOn();
            }
        }
        return on;
    }

    /** Add a line to the claim's status log for the status it now has. */
    public void logged(String by, LocalDate on, BigDecimal due) {
        log.add(new ClaimLogLine(status, by, on, due));
    }
}
