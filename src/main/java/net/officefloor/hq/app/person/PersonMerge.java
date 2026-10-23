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
import java.util.Collections;
import java.util.List;

/**
 * A merge of a person into another on a sheet: who made it, on the sheet's today then, and the claims
 * that moved with it, so the last merge into a person can be undone.
 */
@Entity
@Table(name = "person_merge")
public class PersonMerge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "into_id", nullable = false)
    private Long intoId;

    @Column(name = "merged_by", nullable = false)
    private String mergedBy;

    @Column(name = "merged_on")
    private LocalDate mergedOn;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_claim", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "claim_id", nullable = false)
    private List<Long> claimIds = new ArrayList<>();

    protected PersonMerge() {
    }

    public PersonMerge(String sheetId, Long personId, Long intoId, String mergedBy, LocalDate mergedOn,
            List<Long> claimIds) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.intoId = intoId;
        this.mergedBy = mergedBy;
        this.mergedOn = mergedOn;
        this.claimIds.addAll(claimIds);
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    /** The person merged. */
    public Long getPersonId() {
        return personId;
    }

    /** The person merged into. */
    public Long getIntoId() {
        return intoId;
    }

    public String getMergedBy() {
        return mergedBy;
    }

    /** The sheet's today when the merge was made. */
    public LocalDate getMergedOn() {
        return mergedOn;
    }

    public boolean isUndone() {
        return undone;
    }

    public void undo() {
        this.undone = true;
    }

    /** The claims that moved with the merged person. */
    public List<Long> getClaimIds() {
        return Collections.unmodifiableList(claimIds);
    }
}
