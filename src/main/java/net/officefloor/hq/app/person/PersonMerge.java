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
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A merge of one person into another on a sheet: who merged them and on which sheet's today, the
 *  merged person as they were just before (grades, holds, past retirements, dated terms and aliases
 *  kept as a {@link PersonRevision} keeps them) and the claims that came from them, so that the merge
 *  can be undone. */
@Entity
@Table(name = "person_merge")
public class PersonMerge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    /** The merged person: they keep this id when the merge is undone. */
    @Column(name = "person_id", nullable = false)
    private Long personId;

    /** The person they were merged into. */
    @Column(name = "into_id", nullable = false)
    private Long intoId;

    @Column(name = "merged_by", nullable = false)
    private String mergedBy;

    /** The sheet's today when they were merged. */
    @Column(name = "merged_on", nullable = false)
    private LocalDate mergedOn;

    /** Whether the merge has been undone. */
    @Column(name = "undone", nullable = false)
    private boolean undone;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "grades", nullable = false)
    private String grades;

    @Column(name = "holds", nullable = false)
    private String holds;

    @Column(name = "retirements", nullable = false)
    private String retirements;

    @Column(name = "terms")
    private Integer terms;

    @Column(name = "terms_from", nullable = false)
    private String termsFrom;

    @Column(name = "contact")
    private String contact;

    @Column(name = "tags")
    private String tags;

    @Column(name = "aliases", nullable = false)
    private String aliases;

    @Column(name = "origin")
    private String origin;

    @Column(name = "arrived_on")
    private LocalDate arrivedOn;

    @Column(name = "flag_reason")
    private String flagReason;

    @Column(name = "flag_until")
    private LocalDate flagUntil;

    @Column(name = "approver")
    private String approver;

    /** The claims that came from the merged person. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_claim", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "claim_id")
    private Set<Long> claimIds = new HashSet<>();

    protected PersonMerge() {
    }

    /** The merge, by the user on the day, of the person (as they are now, with the claims given) into
     *  the other. */
    public PersonMerge(Person from, Person into, String mergedBy, LocalDate mergedOn, List<Long> claimIds) {
        this.sheetId = from.getSheetId();
        this.personId = from.getId();
        this.intoId = into.getId();
        this.mergedBy = mergedBy;
        this.mergedOn = mergedOn;
        this.name = from.getName();
        this.grades = PersonRevision.grades(from);
        this.holds = PersonRevision.holds(from);
        this.retirements = PersonRevision.retirements(from);
        this.terms = from.getTerms();
        this.termsFrom = PersonRevision.termsFrom(from);
        this.contact = from.getContact();
        this.tags = from.getTags();
        this.aliases = PersonRevision.aliases(from);
        this.origin = from.getOrigin();
        this.arrivedOn = from.getArrivedOn();
        this.flagReason = from.getFlagReason();
        this.flagUntil = from.getFlagUntil();
        this.approver = from.getApprover();
        this.claimIds.addAll(claimIds);
    }

    /** Give the restored person (back with the merged person's id) their grades, holds, past
     *  retirements, dated terms and aliases as they were. */
    void fill(Person restored) {
        PersonRevision.fill(restored, grades, holds, retirements, termsFrom, aliases);
    }

    /** The merged person's name and own aliases. */
    public List<String> names() {
        List<String> names = new ArrayList<>(List.of(PersonRevision.split(aliases)));
        names.add(0, name);
        return names;
    }

    public void undo() {
        this.undone = true;
    }

    public Long getId() {
        return id;
    }

    public Long getPersonId() {
        return personId;
    }

    public Long getIntoId() {
        return intoId;
    }

    public String getMergedBy() {
        return mergedBy;
    }

    public LocalDate getMergedOn() {
        return mergedOn;
    }

    public boolean isUndone() {
        return undone;
    }

    public String getName() {
        return name;
    }

    public Integer getTerms() {
        return terms;
    }

    public String getContact() {
        return contact;
    }

    public String getTags() {
        return tags;
    }

    public String getOrigin() {
        return origin;
    }

    public LocalDate getArrivedOn() {
        return arrivedOn;
    }

    public String getFlagReason() {
        return flagReason;
    }

    public LocalDate getFlagUntil() {
        return flagUntil;
    }

    public String getApprover() {
        return approver;
    }

    public Set<Long> getClaimIds() {
        return Set.copyOf(claimIds);
    }
}
