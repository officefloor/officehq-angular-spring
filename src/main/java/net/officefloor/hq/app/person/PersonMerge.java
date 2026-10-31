package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A merge of a person into another on a sheet: who made it, the sheet's today then, the merged person as they
 * were (JSON, see {@link PersonMergeService.Merged}), the ids of the claims that moved and whether it was undone.
 */
@Entity
@Table(name = "person_merge")
public class PersonMerge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "into_id", nullable = false)
    private Long intoId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "merged_by", nullable = false)
    private String mergedBy;

    @Column(name = "merged_on", nullable = false)
    private LocalDate mergedOn;

    @Column(name = "person", nullable = false)
    private String person;

    @Column(name = "claim_ids", nullable = false)
    private String claimIds;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    protected PersonMerge() {
    }

    public PersonMerge(String sheetId, Long intoId, Long personId, String mergedBy, LocalDate mergedOn, String person,
            List<Long> claimIds) {
        this.sheetId = sheetId;
        this.intoId = intoId;
        this.personId = personId;
        this.mergedBy = mergedBy;
        this.mergedOn = mergedOn;
        this.person = person;
        this.claimIds = claimIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getIntoId() {
        return intoId;
    }

    public Long getPersonId() {
        return personId;
    }

    public String getMergedBy() {
        return mergedBy;
    }

    public LocalDate getMergedOn() {
        return mergedOn;
    }

    /** The merged person as they were, as JSON. */
    public String getPerson() {
        return person;
    }

    /** The ids of the claims that moved, in the order they were added. */
    public List<Long> getClaimIds() {
        return claimIds.isEmpty() ? List.of() : Arrays.stream(claimIds.split(",")).map(Long::valueOf).toList();
    }

    public boolean isUndone() {
        return undone;
    }

    public void undo() {
        this.undone = true;
    }
}
