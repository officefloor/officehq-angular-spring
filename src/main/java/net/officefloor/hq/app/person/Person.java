package net.officefloor.hq.app.person;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.annotations.SQLRestriction;

/**
 * A person on a sheet, with a name and a grade (A, B or C). The name is kept without surrounding
 * spaces and is unique on the sheet without regard to case (by its {@link #keyOf key}). A person
 * given a retirement date is retired at once, whatever the date; none of their claims may be dated
 * after it, and from the day the sheet's today reaches it their open claims, unless bound by a
 * statement, leave the live totals. A retired person can be unretired: the retirement is
 * kept as a past one, from its date to the sheet's today it ended on, and no claim of theirs may be
 * dated after its from-date and before its to-date. A person may have several holds over time, each from a from-date to an optional to-date. The grade is dated: the first one
 * given is in force from the beginning of time, and each later one from its from-date. A person may have
 * their own terms in days, which their claims' due-by takes ahead of their grade's and the sheet's. A person
 * may have aliases, which share the names' uniqueness on the sheet. A person merged into another is kept,
 * naming them, but leaves the sheet: no query reads them.
 */
@Entity
@SQLRestriction("merged_into is null")
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    /** The name key while not merged away, null once merged: names are unique on the sheet by it. */
    @Column(name = "live_name_key")
    private String liveNameKey;

    @Column(name = "grade", nullable = false)
    private String grade;

    /** The first grade as they were added with, before a grade merge gave them another; null: still it. */
    @Column(name = "first_grade_was")
    private String firstGradeWas;

    /** The from-date a grade log line giving the first grade carries: the beginning of time. */
    private static final LocalDate FIRST = LocalDate.of(1, 1, 1);

    /** The grades recorded after the first, by the date each is in force from. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    @MapKeyColumn(name = "from_date")
    @Column(name = "grade", nullable = false)
    private Map<LocalDate, String> datedGrades = new TreeMap<>();

    /** Each grade recorded after the first, oldest first, with the sheet's today it was recorded on. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade_log", joinColumns = @JoinColumn(name = "person_id"))
    @OrderColumn(name = "seq")
    private List<PersonGradeLine> gradeLog = new ArrayList<>();

    /** The sheet's today when they were added; null when added before this was kept. */
    @Column(name = "added_on", updatable = false)
    private LocalDate addedOn;

    /** The sheet's today when their retirement date was last set; null while not retired, or set before this was kept. */
    @Column(name = "retired_on")
    private LocalDate retiredOn;

    /** Their own terms in days from the beginning of time, ahead of their grade's and the sheet's; null: none. */
    @Column(name = "terms")
    private Integer terms;

    /** Their own terms recorded from a from-date, by that date; {@link #NO_TERMS}: none from it. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_terms", joinColumns = @JoinColumn(name = "person_id"))
    @MapKeyColumn(name = "from_date")
    @Column(name = "terms", nullable = false)
    private Map<LocalDate, Integer> datedTerms = new TreeMap<>();

    /** The dated terms value stored for none from its from-date. */
    private static final int NO_TERMS = -1;

    /** Their contact, without surrounding spaces and lower-cased; null: none. */
    @Column(name = "contact")
    private String contact;

    /** Their tags, sorted and comma separated (see {@link Claim#tagsOf}); empty for none. */
    @Column(name = "tags", nullable = false, length = 2000)
    private String tags = "";

    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** Their past retirements, earliest first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_retirement", joinColumns = @JoinColumn(name = "person_id"))
    @OrderBy("fromDate ASC, toDate ASC")
    private List<PersonRetirement> retirements = new ArrayList<>();

    /** The SNAPSHOT lines written for them, oldest first; a new one does not move their version. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_snapshot", joinColumns = @JoinColumn(name = "person_id"))
    @OrderColumn(name = "seq")
    @OptimisticLock(excluded = true)
    private List<PersonSnapshot> snapshots = new ArrayList<>();

    /** Their holds, earliest from-date first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    @OrderBy("fromDate ASC, toDate ASC")
    private List<PersonHold> holds = new ArrayList<>();

    /** Their aliases, in the order they were added. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_alias", joinColumns = @JoinColumn(name = "person_id"))
    private List<PersonAlias> aliases = new ArrayList<>();

    /** Their position in the sheet's list, smallest first. */
    @Column(name = "list_position", nullable = false)
    private long position;

    /** The person they were merged into; null while not merged. */
    @Column(name = "merged_into")
    private Long mergedInto;

    /** The sheet they were last transferred from; null when never transferred. */
    @Column(name = "transferred_from")
    private String transferredFrom;

    /** The sheet-today, on the sheet they arrived on, of their last transfer; null when never transferred. */
    @Column(name = "transferred_on")
    private LocalDate transferredOn;

    /** The reason a steward flagged them; null when never flagged. Flagging does not move their version. */
    @Column(name = "flag_reason")
    @OptimisticLock(excluded = true)
    private String flagReason;

    /** The date their flag is in force until, inclusive; null when never flagged. */
    @Column(name = "flag_until")
    @OptimisticLock(excluded = true)
    private LocalDate flagUntil;

    /** The user who may approve or reject their PENDING claims as a steward may; null when none. Setting it does not move their version. */
    @Column(name = "approver")
    @OptimisticLock(excluded = true)
    private String approver;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {}

    public Person(String sheetId, String name, String grade, LocalDate addedOn) {
        this.sheetId = sheetId;
        setName(name);
        this.grade = grade;
        this.addedOn = addedOn;
    }

    /** The key a name is compared by for uniqueness: without surrounding spaces, lower-cased. */
    public static String keyOf(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public String getName() { return name; }

    public void setName(String name) {
        this.name = name.strip();
        this.nameKey = keyOf(name);
        if (mergedInto == null) this.liveNameKey = nameKey;
    }

    /** The first grade they were given, in force from the beginning of time. */
    public String getGrade() { return grade; }

    /** The grade in force on the date: the one with the latest from-date on or before it. */
    public String gradeOn(LocalDate date) {
        Map.Entry<LocalDate, String> on = new TreeMap<>(datedGrades).floorEntry(date);
        return on == null ? grade : on.getValue();
    }

    /** Record the grade from the date on the sheet's today, in place of any recorded from the same date. */
    public void setGradeFrom(LocalDate from, String grade, LocalDate on) {
        datedGrades.put(from, grade);
        gradeLog.add(new PersonGradeLine(from, grade, on));
    }

    /**
     * Give them the other grade in place of the grade wherever it is recorded, on the sheet's today: their
     * first grade and each recorded from a date, so every date that had it now has the other.
     */
    public void regrade(String from, String into, LocalDate on) {
        if (from.equals(grade)) {
            if (firstGradeWas == null) firstGradeWas = grade;
            grade = into;
            gradeLog.add(new PersonGradeLine(FIRST, into, on));
        }
        for (Map.Entry<LocalDate, String> dated : new TreeMap<>(datedGrades).entrySet()) {
            if (!from.equals(dated.getValue())) continue;
            datedGrades.put(dated.getKey(), into);
            gradeLog.add(new PersonGradeLine(dated.getKey(), into, on));
        }
    }

    /**
     * The grade in force on the date as recorded by the end of the as-of date: of the grades recorded while
     * the sheet's today was on or before it, the one with the latest from-date on or before the date.
     */
    public String gradeOn(LocalDate date, LocalDate asOf) {
        String first = firstGradeWas != null ? firstGradeWas : grade;
        TreeMap<LocalDate, String> then = new TreeMap<>();
        for (PersonGradeLine line : gradeLog) {
            if (!line.recordedBy(asOf)) continue;
            if (line.getFromDate().equals(FIRST)) first = line.getGrade();
            else then.put(line.getFromDate(), line.getGrade());
        }
        Map.Entry<LocalDate, String> on = then.floorEntry(date);
        return on == null ? first : on.getValue();
    }

    /** The sheet's today when they were added; null when added before this was kept. */
    public LocalDate getAddedOn() { return addedOn; }

    /** Whether they were on the sheet at the end of the date: added while its today was on or before it. */
    public boolean existedAt(LocalDate date) { return addedOn == null || !addedOn.isAfter(date); }

    /** The grades recorded after the first, by the date each is in force from, earliest first. */
    public Map<LocalDate, String> getDatedGrades() { return new TreeMap<>(datedGrades); }

    /** Their own terms in days from the beginning of time, or null when they have none then. */
    public Integer getTerms() { return terms; }

    /** Record their own terms (null: none) from the beginning of time, keeping those recorded from a date. */
    public void setTerms(Integer terms) { this.terms = terms; }

    /** Their own terms in force on the date: those with the latest from-date on or before it; null: none. */
    public Integer termsOn(LocalDate date) {
        Map.Entry<LocalDate, Integer> on = date == null ? null : new TreeMap<>(datedTerms).floorEntry(date);
        if (on == null) return terms;
        return on.getValue() == NO_TERMS ? null : on.getValue();
    }

    /** Record their own terms (null: none) from the date, in place of any recorded from the same date. */
    public void setTermsFrom(LocalDate from, Integer terms) {
        datedTerms.put(from, terms == null ? NO_TERMS : terms);
    }

    /** Their own terms recorded from a from-date, by that date, earliest first; null: none from it. */
    public Map<LocalDate, Integer> getDatedTerms() {
        TreeMap<LocalDate, Integer> all = new TreeMap<>();
        datedTerms.forEach((from, t) -> all.put(from, t == NO_TERMS ? null : t));
        return all;
    }

    /** Their contact, or null when they have none. */
    public String getContact() { return contact; }

    public void setContact(String contact) { this.contact = contactOf(contact); }

    /** A contact as it is stored: without surrounding spaces, lower-cased; blank or null: none. */
    public static String contactOf(String contact) {
        if (contact == null || contact.isBlank()) return null;
        return contact.strip().toLowerCase(Locale.ROOT);
    }

    /** Their tags, sorted; empty for none. */
    public List<String> getTags() {
        return tags.isEmpty() ? List.of() : List.of(tags.split(","));
    }

    /** Give them tags, as {@link Claim#tagsOf} reads them. */
    public void setTags(List<String> tags) {
        this.tags = String.join(",", Claim.tagsOf(tags));
    }

    /** Their aliases, sorted as names are compared (by key, then as given). */
    public List<String> getAliases() {
        return aliases.stream()
                .sorted(Comparator.comparing(PersonAlias::getAliasKey).thenComparing(PersonAlias::getAlias))
                .map(PersonAlias::getAlias).toList();
    }

    /** Whether they have the name, as names are compared, as an alias. */
    public boolean hasAlias(String name) {
        String key = keyOf(name);
        return aliases.stream().anyMatch(a -> a.getAliasKey().equals(key));
    }

    public void addAlias(String alias) { aliases.add(new PersonAlias(sheetId, alias)); }

    /** Drop the alias, as names are compared, if they have it. */
    public void removeAlias(String alias) {
        String key = keyOf(alias);
        aliases.removeIf(a -> a.getAliasKey().equals(key));
    }

    /**
     * Merge them into the other person: they name the other as merged into, and lose their aliases, holds
     * and contact. Returns their aliases, as given, in the order they were added.
     */
    public List<String> mergeInto(Long otherId) {
        List<String> theirs = aliases.stream().map(PersonAlias::getAlias).toList();
        this.mergedInto = otherId;
        this.liveNameKey = null;
        aliases.clear();
        holds.clear();
        this.contact = null;
        return theirs;
    }

    /**
     * Move them to another sheet, noting the one they came from and that sheet's today they arrive on, with
     * their grades, terms, contact, aliases and holds.
     */
    public void transferTo(String sheetId, LocalDate arrivedOn) {
        this.transferredFrom = this.sheetId;
        this.transferredOn = arrivedOn;
        this.sheetId = sheetId;
        List<PersonAlias> theirs = new ArrayList<>(aliases);
        aliases.clear();
        for (PersonAlias a : theirs) aliases.add(new PersonAlias(sheetId, a.getAlias()));
    }

    /** The sheet they were last transferred from, or null when never transferred. */
    public String getTransferredFrom() { return transferredFrom; }

    /** The sheet-today they arrived on by their last transfer, or null when never transferred. */
    public LocalDate getTransferredOn() { return transferredOn; }

    /** Their name and aliases, as given. */
    public List<String> namesAndAliases() {
        List<String> all = new ArrayList<>();
        all.add(name);
        for (PersonAlias a : aliases) all.add(a.getAlias());
        return all;
    }

    /** The date they are retired from, or null while not retired. */
    public LocalDate getRetiredFrom() { return retiredFrom; }

    /** Retire them from the date, on the sheet's today. */
    public void retireFrom(LocalDate retiredFrom, LocalDate on) {
        this.retiredFrom = retiredFrom;
        this.retiredOn = on;
    }

    public boolean isRetired() { return retiredFrom != null; }

    /** Unretire them on the sheet's today, keeping the retirement as a past one from its date to today. */
    public void unretire(LocalDate on) {
        retirements.add(new PersonRetirement(retiredFrom, on, retiredOn));
        this.retiredFrom = null;
        this.retiredOn = null;
    }

    /** Their past retirements, earliest first. */
    public List<PersonRetirement> getRetirements() { return List.copyOf(retirements); }

    /** Keep a SNAPSHOT line written for them: the day being left and their due-total on it (null: n/a). */
    public void addSnapshot(LocalDate day, BigDecimal dueTotal) { snapshots.add(new PersonSnapshot(day, dueTotal)); }

    /** The SNAPSHOT lines written for them, oldest first. */
    public List<PersonSnapshot> getSnapshots() { return List.copyOf(snapshots); }

    /**
     * Whether a claim of theirs may be dated on the date: not after their retirement date, nor after the
     * from-date and before the to-date of a past retirement.
     */
    public boolean mayClaimOn(LocalDate date) {
        if (retiredFrom != null && date.isAfter(retiredFrom)) return false;
        return retirements.stream().noneMatch(r -> r.bars(date));
    }

    /** Flag them with a reason until a date, inclusive, replacing any flag they had. */
    public void flag(String reason, LocalDate until) {
        this.flagReason = reason;
        this.flagUntil = until;
    }

    /** The user who may approve or reject their PENDING claims; null when none. */
    public String getApprover() { return approver; }

    /** Name the user who may approve or reject their PENDING claims; null for none. */
    public void setApprover(String approver) { this.approver = approver; }

    /** The reason of their flag while it is in force on the date (on or before its until date); null otherwise. */
    public String flagOn(LocalDate date) {
        return flagReason != null && flagUntil != null && date != null && !date.isAfter(flagUntil) ? flagReason : null;
    }

    /** Whether they have any hold, whatever its dates. */
    public boolean isHeld() { return !holds.isEmpty(); }

    /** Their holds, earliest from-date first. */
    public List<PersonHold> getHolds() { return List.copyOf(holds); }

    public void addHold(PersonHold hold) { holds.add(hold); }

    /** Whether the date falls within any of their holds. */
    public boolean heldOn(LocalDate date) { return holds.stream().anyMatch(h -> h.contains(date)); }

    /**
     * Whether the date falls within any of their holds put on while the sheet's today was on or before the
     * as-of date.
     */
    public boolean heldOn(LocalDate date, LocalDate asOf) {
        return holds.stream().anyMatch(h -> h.putOnBy(asOf) && h.contains(date));
    }

    /** Whether, as it stood at the end of the date, they were retired by it: retired then, from on or before it. */
    public boolean retiredAt(LocalDate date) {
        if (retirements.stream().anyMatch(r -> r.retiredAt(date))) return true;
        return retiredOn(date) && (retiredOn == null || !retiredOn.isAfter(date));
    }

    /** Whether their retirement date is reached on the date (the date on or after it). */
    public boolean retiredOn(LocalDate date) { return retiredFrom != null && date != null && !date.isBefore(retiredFrom); }

    /** Whether an open claim of theirs is kept out of the live totals: theirs once retired by the sheet's today (a hold keeps nothing out). */
    private boolean keptOut(Claim claim, LocalDate today) { return retiredOn(today); }

    /**
     * Whether a claim of theirs counts in the live totals: it counts by its status, unless it is open
     * (OPEN or PENDING), theirs once their retirement date is reached on the sheet's today, and not bound
     * by a statement, or deferred on the sheet's today. A hold (theirs or the claim's) takes nothing out.
     */
    public boolean counts(Claim claim, LocalDate today) {
        if (!claim.getStatus().counts() || claim.isDeferredOn(today)) return false;
        return !claim.getStatus().isOpen() || claim.isBound() || !keptOut(claim, today);
    }

    /**
     * Whether a claim of theirs counts toward what they have claimed: as {@link #counts}.
     */
    public boolean countsClaimed(Claim claim, LocalDate today) {
        if (!claim.getStatus().counts() || claim.isDeferredOn(today)) return false;
        return !claim.getStatus().isOpen() || claim.isBound() || !retiredOn(today);
    }

    /**
     * Whether a claim of theirs counts for a period ending on the date: by the status it had at the end
     * of that date, less a deferred one and one still open now (OPEN or PENDING) of theirs once retired on
     * the sheet's today, and not bound, as the live rules say.
     */
    public boolean countsAt(Claim claim, LocalDate end, LocalDate today) {
        ClaimStatus at = claim.statusAt(end);
        if (at == null || !at.counts() || claim.isDeferredOn(today)) return false;
        return !claim.getStatus().isOpen() || claim.isBound() || !keptOut(claim, today);
    }

    /**
     * Whether they are active on the date: they have an open claim (OPEN or PENDING) that counts, dated
     * within the 30 days up to and including it.
     */
    public boolean activeOn(Iterable<Claim> claims, LocalDate today) {
        if (today == null) return false;
        LocalDate since = today.minusDays(29);
        for (Claim claim : claims) {
            if (!getId().equals(claim.getPersonId()) || !claim.getStatus().isOpen() || !counts(claim, today)) continue;
            if (!claim.getDate().isBefore(since) && !claim.getDate().isAfter(today)) return true;
        }
        return false;
    }

    /** Moved on by each save; an edit is refused unless made from the current version. */
    public long getVersion() { return version; }

    public long getPosition() { return position; }

    public void setPosition(long position) { this.position = position; }
}
