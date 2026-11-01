package net.officefloor.hq.app.claim;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A claim by a person on a sheet: an amount (two decimals), a date, a note and a status (DRAFT when
 * added), and a number and who opened it once opened. A negative amount makes it a correction, which
 * names the number of the SETTLED claim of its person that it corrects.
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

    /**
     * Its place in its sheet's list, newest (highest) first: the next of claim_listed_seq, given by the
     * store when added and again when moved to another person.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "listed", insertable = false)
    private Long listed;

    /**
     * Its place in the order claims were added to their sheets: the next of claim_added_seq, given by the
     * store when added and again when it arrives on another sheet. A forward-only rate applies only to
     * claims added after it was saved.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "added", insertable = false)
    private Long added;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    @Column(name = "note", nullable = false)
    private String note;

    /**
     * The date it is due by, fixed when added: the terms-th business day after its date, with its person's own
     * terms, else those of their grade then. Fixed again, with the same terms, when a holiday is added.
     */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The terms, in business days, its due-by was fixed with. */
    @Column(name = "terms", nullable = false, updatable = false)
    private int terms;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /**
     * Given when the claim is opened: the sheet-wide number, or <period name>-<n> (X-<n> in no
     * period) while the sheet numbers by period; none while DRAFT, kept when VOID.
     */
    @Column(name = "claim_number")
    private String number;

    /** The period the claim was numbered within (empty: in no period); none unless numbered by period. */
    @Column(name = "number_period")
    private String numberPeriod;

    /** The claim's place in its period's numbering; none unless numbered by period. */
    @Column(name = "number_seq")
    private Integer numberSeq;

    /** The user who opened the claim; none while DRAFT. */
    @Column(name = "opened_by", length = 100)
    private String openedBy;

    /** The user who last edited the claim; none while never edited. */
    @Column(name = "edited_by", length = 100)
    private String editedBy;

    /** The due fixed when the claim was settled; none while not settled, or settled with no rate. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    /** Bound by its person's last statement, which it counted toward; its due is then {@link #statedDue}. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The due it had at its person's last statement; none while not stated, or stated with no due. */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /** The number of the claim this correction corrects; none unless a correction. */
    @Column(name = "corrects")
    private String corrects;

    /** Its external reference, trimmed; none unless given. Unique on the sheet by its {@link #refKeyOf key}. */
    @Column(name = "ref", length = 200)
    private String ref;

    /** Its reference lower-cased, the key its uniqueness on the sheet is kept on; none without a reference. */
    @Column(name = "ref_key", length = 200)
    private String refKey;

    /** Its tags, sorted and comma separated (see {@link #tagsOf}); empty for none. */
    @Column(name = "tags", nullable = false, length = 2000)
    private String tags = "";

    /** The number of the claim it was split from; none unless split off one. */
    @Column(name = "split_from")
    private String splitFrom;

    /** How many proofs it has; raised one at a time while not SETTLED or VOID. */
    @Column(name = "proofs", nullable = false)
    private int proofs;

    /** The date it is deferred until; none unless deferred. Deferred only while OPEN and before that date. */
    @Column(name = "deferred_until")
    private LocalDate deferredUntil;

    /** The reason it was voided with, from the sheet's list; none unless voided with one. */
    @Column(name = "void_reason", length = 200)
    private String voidReason;

    /** The sheet's today when it was deferred; none unless deferred (or deferred before this was kept). */
    @Column(name = "deferred_on")
    private LocalDate deferredOn;

    /** Whether it was added while the period its due-by falls in was soft-closed. */
    @Column(name = "late_entry", nullable = false)
    private boolean lateEntry;

    /** The user whose open editor locks it for everyone else; none while no editor holds it. */
    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    /** The last sheet's today a reminder was written for it; none until reminded. */
    @Column(name = "reminded_on")
    private LocalDate remindedOn;

    /** Whether a reminder has escalated it; a claim is escalated at most once. */
    @Column(name = "escalated", nullable = false)
    private boolean escalated;

    /** Whether a steward waived its late charge; a claim is waived at most once. */
    @Column(name = "waived", nullable = false)
    private boolean waived;

    /** The sheet's today when its late charge was waived; none unless waived. */
    @Column(name = "waived_on")
    private LocalDate waivedOn;

    /** The sheet's today when it last went to REVIEW; none unless it has. */
    @Column(name = "review_on")
    private LocalDate reviewOn;

    /** The number of the bulk settle batch that settled it (or sent it to PENDING); none when settled one at a time. */
    @Column(name = "settle_batch")
    private Integer settleBatch;

    /** Its status log, oldest first: a line for each status it has had, DRAFT when added. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_log", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<ClaimLogEntry> log = new ArrayList<>();

    /** Its binding log, oldest first: a line each time a statement bound it or it was left unbound again. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_binding", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<ClaimBinding> bindings = new ArrayList<>();

    /** Its comments, oldest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_comment", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<ClaimComment> comments = new ArrayList<>();

    /** Its lines, in the order they were added; with any, its amount is what they add up to. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "claim_line", joinColumns = @JoinColumn(name = "claim_id"))
    @OrderColumn(name = "seq")
    private List<ClaimLine> lines = new ArrayList<>();

    protected Claim() {}

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note, LocalDate dueBy,
            int terms) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.note = note;
        this.dueBy = dueBy;
        this.terms = terms;
    }

    public Long getId() { return id; }

    /** A correction: a negative amount. Its due has no floor and no late charge. */
    public boolean isCorrection() { return amount.signum() < 0; }

    public String getCorrects() { return corrects; }

    public void setCorrects(String corrects) { this.corrects = corrects; }

    public String getRef() { return ref; }

    /** Give it an external reference (null: none). */
    public void setRef(String ref) {
        this.ref = ref;
        this.refKey = ref == null ? null : refKeyOf(ref);
    }

    /** The key a reference is compared on: trimmed, without regard to case. */
    public static String refKeyOf(String ref) {
        return ref.strip().toLowerCase(Locale.ROOT);
    }

    /** Its tags, sorted; empty for none. */
    public List<String> getTags() {
        return tags.isEmpty() ? List.of() : List.of(tags.split(","));
    }

    /** Give it tags, as {@link #tagsOf} reads them. */
    public void setTags(List<String> tags) {
        this.tags = String.join(",", tagsOf(tags));
    }

    /** Tags each trimmed and lower-cased, blanks and repeats dropped, sorted. */
    public static List<String> tagsOf(List<String> tags) {
        return tags.stream().map(t -> t.strip().toLowerCase(Locale.ROOT)).filter(t -> !t.isEmpty())
                .distinct().sorted().toList();
    }

    /** The tags in comma separated text, as {@link #tagsOf} reads them; none for null. */
    public static List<String> tagsOf(String text) {
        return text == null ? List.of() : tagsOf(List.of(text.split(",")));
    }

    public String getSplitFrom() { return splitFrom; }

    public String getVoidReason() { return voidReason; }

    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }

    public void setSplitFrom(String splitFrom) { this.splitFrom = splitFrom; }

    public String getSheetId() { return sheetId; }

    public Long getPersonId() { return personId; }

    public Long getListed() { return listed; }

    /** Its place in the order claims were added; null until the store has added it (then it is the newest). */
    public Long getAdded() { return added; }

    /** Move the claim to another person, listed as their newest at the given place in the list. */
    public void moveTo(Long personId, long listed) {
        this.personId = personId;
        this.listed = listed;
    }

    /**
     * Move the claim, with its person, to another sheet: it keeps its status, dates, note, tags, frozen
     * figure, logs and any figure a statement bound it to, but loses its reference, its number (to be
     * given the other sheet's next), its settle batch and any editor's lock; it is added to the other sheet
     * at the given place in the order claims were added.
     */
    public void transferTo(String sheetId, long added) {
        this.sheetId = sheetId;
        this.added = added;
        setRef(null);
        this.number = null;
        this.numberPeriod = null;
        this.numberSeq = null;
        this.settleBatch = null;
        this.lockedBy = null;
    }

    /** The sheet's today it was first opened on, as its status log records it; null when it records none. */
    public LocalDate openedOn() {
        for (ClaimLogEntry line : log) if (line.getStatus() == ClaimStatus.OPEN) return line.getDate();
        return null;
    }

    public BigDecimal getAmount() { return amount; }

    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDate getDate() { return date; }

    public void setDate(LocalDate date) { this.date = date; }

    public String getNote() { return note; }

    /** The date it is due by, fixed when added; it does not move with its date or the terms, only when a holiday is added. */
    public LocalDate getDueBy() { return dueBy; }

    /** Fix the due-by again, as worked out over the sheet's holidays now. */
    public void setDueBy(LocalDate dueBy) { this.dueBy = dueBy; }

    /** The terms, in business days, its due-by was fixed with. */
    public int getTerms() { return terms; }

    public void setNote(String note) { this.note = note; }

    public ClaimStatus getStatus() { return status; }

    public void setStatus(ClaimStatus status) { this.status = status; }

    public List<ClaimComment> getComments() { return Collections.unmodifiableList(comments); }

    /** Add a comment written by the user on the date. */
    public void comment(String by, LocalDate on, String text) {
        comments.add(new ClaimComment(by, on, text));
    }

    public List<ClaimLine> getLines() { return Collections.unmodifiableList(lines); }

    /** Add a line; its amount becomes the quantities times the unit amounts added up. */
    public void addLine(String label, int qty, BigDecimal unit) {
        lines.add(new ClaimLine(label, qty, unit));
        amount = lines.stream().map(ClaimLine::total).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }

    public List<ClaimLogEntry> getLog() { return Collections.unmodifiableList(log); }

    /** Log the status it now has, given by the user on the date, with its due at that moment. */
    public void logStatus(String by, LocalDate on, BigDecimal due) {
        log.add(new ClaimLogEntry(status, by, on, due));
    }

    /**
     * The status it had at the end of the date, as its log records it: the last line dated on or before
     * it; null when it had none then (added after it). With no log lines (added before the log was
     * kept) it is taken to have had its status now.
     */
    public ClaimStatus statusAt(LocalDate date) {
        if (log.isEmpty()) return status;
        ClaimStatus at = null;
        for (ClaimLogEntry line : log) if (!line.getDate().isAfter(date)) at = line.getStatus();
        return at;
    }

    /**
     * The due it was settled at, as its status log records it, when SETTLED at the end of the date: the
     * due of its last SETTLED line dated on or before it; with no log lines, its settled due now.
     */
    public BigDecimal settledDueAt(LocalDate date) {
        if (log.isEmpty()) return settledDue;
        BigDecimal due = null;
        for (ClaimLogEntry line : log) {
            if (!line.getDate().isAfter(date) && line.getStatus() == ClaimStatus.SETTLED) due = line.getDue();
        }
        return due;
    }

    /** The date it was last settled, as its status log records it; null when the log has no SETTLED line. */
    public LocalDate settledOn() {
        LocalDate on = null;
        for (ClaimLogEntry line : log) if (line.getStatus() == ClaimStatus.SETTLED) on = line.getDate();
        return on;
    }

    /** The sheet's today it was last deleted on, as its status log records it; null when the log has no DELETED line. */
    public LocalDate deletedOn() {
        LocalDate on = null;
        for (ClaimLogEntry line : log) if (line.getStatus() == ClaimStatus.DELETED) on = line.getDate();
        return on;
    }

    public boolean isLateEntry() { return lateEntry; }
    public void setLateEntry(boolean lateEntry) { this.lateEntry = lateEntry; }

    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }
    /** Locked by an open editor of a user other than this one. */
    public boolean isLockedAgainst(String user) { return lockedBy != null && !lockedBy.equals(user.strip()); }
    public LocalDate getRemindedOn() { return remindedOn; }
    /** Record a reminder written on the sheet's today. */
    public void remind(LocalDate on) { this.remindedOn = on; }
    public boolean isEscalated() { return escalated; }
    public LocalDate getReviewOn() { return reviewOn; }
    public void setReviewOn(LocalDate reviewOn) { this.reviewOn = reviewOn; }
    /** Record that a reminder escalated it. */
    public void escalate() { this.escalated = true; }
    public boolean isWaived() { return waived; }
    public LocalDate getWaivedOn() { return waivedOn; }
    /** Waive its late charge on the sheet's today. */
    public void waive(LocalDate on) {
        this.waived = true;
        this.waivedOn = on;
    }
    /** Whether its late charge was waived on or before the date: it bears none from then on. */
    public boolean isWaivedAt(LocalDate date) {
        return waived && (waivedOn == null || date == null || !waivedOn.isAfter(date));
    }
    public Integer getSettleBatch() { return settleBatch; }
    public void setSettleBatch(Integer settleBatch) { this.settleBatch = settleBatch; }
    public String getNumber() { return number; }

    /** Number the claim sheet-wide. */
    public void setNumber(long number) { this.number = String.valueOf(number); }

    /** Number the claim within a fiscal year: FY&lt;year&gt;-&lt;n&gt;. */
    public void setFiscalNumber(int year, long n) { this.number = "FY" + year + "-" + n; }

    /** Number the claim within a period (empty name: in no period): <period name>-<n>, or X-<n>. */
    public void setNumberWithin(String period, int seq) {
        this.numberPeriod = period;
        this.numberSeq = seq;
        this.number = (period.isEmpty() ? "X" : period) + "-" + seq;
    }

    /** The period the claim was numbered within (empty: in no period); null unless numbered by period. */
    public String getNumberPeriod() { return numberPeriod; }

    public Integer getNumberSeq() { return numberSeq; }

    public String getOpenedBy() { return openedBy; }

    public void setOpenedBy(String openedBy) { this.openedBy = openedBy; }

    public String getEditedBy() { return editedBy; }

    public void setEditedBy(String editedBy) { this.editedBy = editedBy; }

    /** Whether the user opened or last edited the claim, so may neither settle nor approve it. */
    public boolean isOpenedOrEditedBy(String user) {
        String name = user.strip();
        return name.equals(openedBy) || name.equals(editedBy);
    }

    public BigDecimal getSettledDue() { return settledDue; }

    public void setSettledDue(BigDecimal settledDue) { this.settledDue = settledDue; }

    public boolean isStated() { return stated; }

    public BigDecimal getStatedDue() { return statedDue; }

    /**
     * Whether its due is now bound by its person's last statement: stated, a PENDING claim like any other.
     */
    public boolean isBound() { return stated; }

    /** Bind the claim to a due (null: it has none) on the sheet's today, as its person's statement does. */
    public void state(BigDecimal due, LocalDate on) {
        if (!stated || !Objects.equals(statedDue, due)) bindings.add(new ClaimBinding(on, true, due));
        this.stated = true;
        this.statedDue = due;
    }

    /**
     * The binding it had at the end of the date, as its binding log records it: the last line dated on or
     * before it, null when unbound then. With no log lines it is taken to have had its binding now.
     */
    public ClaimBinding bindingAt(LocalDate date) {
        if (bindings.isEmpty()) return stated ? new ClaimBinding(date, true, statedDue) : null;
        ClaimBinding at = null;
        for (ClaimBinding line : bindings) if (!line.getDate().isAfter(date)) at = line;
        return at != null && at.isStated() ? at : null;
    }

    public int getProofs() { return proofs; }

    /** Raise its proofs count by one. */
    public void addProof() { proofs++; }

    public LocalDate getDeferredUntil() { return deferredUntil; }

    /** Defer it until the date, on the sheet's today. */
    public void deferUntil(LocalDate deferredUntil, LocalDate on) {
        this.deferredUntil = deferredUntil;
        this.deferredOn = on;
    }

    /**
     * Whether it was deferred at the end of the date, with the status it had then: OPEN, deferred on or
     * before the date until a date after it.
     */
    public boolean isDeferredAt(ClaimStatus statusThen, LocalDate date) {
        return statusThen == ClaimStatus.OPEN && deferredUntil != null && date.isBefore(deferredUntil)
                && (deferredOn == null || !deferredOn.isAfter(date));
    }

    /**
     * Whether it is deferred on the date: OPEN, with a deferred-until date after it. A deferred claim counts
     * in no total, is not overdue, cannot be settled and is left unbound by a statement.
     */
    public boolean isDeferredOn(LocalDate today) {
        return status == ClaimStatus.OPEN && deferredUntil != null && today != null && today.isBefore(deferredUntil);
    }

    /** Leave the claim unbound on the sheet's today, its due live again. */
    public void unstate(LocalDate on) {
        if (stated) bindings.add(new ClaimBinding(on, false, null));
        this.stated = false;
        this.statedDue = null;
    }
}
