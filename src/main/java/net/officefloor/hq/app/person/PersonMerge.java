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

/** A merge of one person of a sheet into another: who, by whom, on which sheet's today, what the merged person was
 *  and the claims that moved from them, so the merge can be undone. */
@Entity
@Table(name = "person_merge")
public class PersonMerge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    /** The merged person's id, given back to them when the merge is undone. */
    @Column(name = "from_id", nullable = false)
    private Long fromId;

    @Column(name = "into_id", nullable = false)
    private Long intoId;

    /** The user who made the merge: the only one who may undo it. */
    @Column(name = "user_name", nullable = false)
    private String userName;

    /** The sheet's today when the merge was made: the only day it may be undone. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "terms")
    private Integer terms;

    @Column(name = "tags", length = 1000)
    private String tags;

    @Column(name = "flag_reason", length = 200)
    private String flagReason;

    @Column(name = "flag_until")
    private LocalDate flagUntil;

    @Column(name = "approver", length = 100)
    private String approver;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_grade", joinColumns = @JoinColumn(name = "merge_id"))
    private List<GradeFrom> grades = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_terms", joinColumns = @JoinColumn(name = "merge_id"))
    private List<TermsFrom> datedTerms = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_past_retirement", joinColumns = @JoinColumn(name = "merge_id"))
    private List<PastRetirement> pastRetirements = new ArrayList<>();

    /** The merged person's own aliases. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_alias", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "alias", nullable = false)
    private Set<String> aliases = new HashSet<>();

    /** The claims that moved from the merged person. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_claim", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "claim_id", nullable = false)
    private Set<Long> claimIds = new HashSet<>();

    protected PersonMerge() {
    }

    /** The merge of {@code from} into {@code intoId} by {@code userName} on {@code madeOn}, moving {@code claimIds}. */
    public PersonMerge(Person from, Long intoId, String userName, LocalDate madeOn, List<Long> claimIds) {
        this.sheetId = from.getSheetId();
        this.fromId = from.getId();
        this.intoId = intoId;
        this.userName = userName;
        this.madeOn = madeOn;
        this.name = from.getName();
        this.terms = from.getTerms();
        this.tags = from.getTags();
        this.flagReason = from.getFlagReason();
        this.flagUntil = from.getFlagUntil();
        this.approver = from.getApprover();
        from.getGrades().forEach(g -> grades.add(new GradeFrom(g.getFromDate(), g.getGrade())));
        from.getDatedTerms().forEach(t -> datedTerms.add(new TermsFrom(t.getFromDate(), t.getTerms())));
        from.getPastRetirements().forEach(r -> pastRetirements.add(new PastRetirement(r.getFromDate(), r.getToDate())));
        this.aliases.addAll(from.getAliases());
        this.claimIds.addAll(claimIds);
    }

    public Long getId() {
        return id;
    }

    public Long getFromId() {
        return fromId;
    }

    public Long getIntoId() {
        return intoId;
    }

    public String getUserName() {
        return userName;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    public boolean isUndone() {
        return undone;
    }

    public void markUndone() {
        this.undone = true;
    }

    public String getName() {
        return name;
    }

    public Integer getTerms() {
        return terms;
    }

    public String getTags() {
        return tags;
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

    public List<GradeFrom> getGrades() {
        return grades;
    }

    public List<TermsFrom> getDatedTerms() {
        return datedTerms;
    }

    public List<PastRetirement> getPastRetirements() {
        return pastRetirements;
    }

    public Set<String> getAliases() {
        return aliases;
    }

    public Set<Long> getClaimIds() {
        return claimIds;
    }
}
