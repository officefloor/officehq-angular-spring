package net.officefloor.hq.app.person;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A merge of one person into another, kept so it can be undone: who made it, on what today, and what it moved. */
@Entity
@Table(name = "person_merge")
public class PersonMerge {

    /** A claim the merge moved, with its place in its person's list before. */
    @Embeddable
    public record MovedClaim(@Column(name = "claim_id", nullable = false) Long claimId,
            @Column(name = "listed", nullable = false) long listed) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "from_id", nullable = false)
    private Long fromId;

    @Column(name = "into_id", nullable = false)
    private Long intoId;

    @Column(name = "merged_by", nullable = false)
    private String mergedBy;

    /** The sheet's today when the merge was made. */
    @Column(name = "merged_on", nullable = false)
    private LocalDate mergedOn;

    /** The alias the merged person's name became. */
    @Column(name = "name_alias_id")
    private Long nameAliasId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_claim", joinColumns = @JoinColumn(name = "merge_id"))
    private List<MovedClaim> claims = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_alias", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "alias_id", nullable = false)
    private List<Long> aliasIds = new ArrayList<>();

    protected PersonMerge() {
    }

    public PersonMerge(String sheetId, Long fromId, Long intoId, String mergedBy, LocalDate mergedOn) {
        this.sheetId = sheetId;
        this.fromId = fromId;
        this.intoId = intoId;
        this.mergedBy = mergedBy;
        this.mergedOn = mergedOn;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getFromId() {
        return fromId;
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

    public Long getNameAliasId() {
        return nameAliasId;
    }

    public void setNameAliasId(Long nameAliasId) {
        this.nameAliasId = nameAliasId;
    }

    public List<MovedClaim> getClaims() {
        return claims;
    }

    public List<Long> getAliasIds() {
        return aliasIds;
    }
}
