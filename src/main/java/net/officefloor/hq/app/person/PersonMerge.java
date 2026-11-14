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
import java.util.HashSet;
import java.util.Set;

/** A steward's merge of a person into another: the claims that moved and the aliases the merged person
 *  brought, so the last merge into a person can be undone. */
@Entity
@Table(name = "person_merge")
public class PersonMerge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "from_id", nullable = false)
    private Long fromId;

    @Column(name = "into_id", nullable = false)
    private Long intoId;

    @Column(name = "made_by", nullable = false)
    private String madeBy;

    /** The sheet's today when it was made. */
    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    /** The alias made of the merged person's name. */
    @Column(name = "name_alias_id")
    private Long nameAliasId;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_claim", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "claim_id", nullable = false)
    private Set<Long> claimIds = new HashSet<>();

    /** The merged person's own aliases, moved to the other. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_alias", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "alias_id", nullable = false)
    private Set<Long> aliasIds = new HashSet<>();

    protected PersonMerge() {
    }

    public PersonMerge(String sheetId, Long fromId, Long intoId, String madeBy, LocalDate madeOn, Long nameAliasId,
            Set<Long> claimIds, Set<Long> aliasIds) {
        this.sheetId = sheetId;
        this.fromId = fromId;
        this.intoId = intoId;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
        this.nameAliasId = nameAliasId;
        this.claimIds = new HashSet<>(claimIds);
        this.aliasIds = new HashSet<>(aliasIds);
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

    public String getMadeBy() {
        return madeBy;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    public Long getNameAliasId() {
        return nameAliasId;
    }

    public boolean isUndone() {
        return undone;
    }

    public void undo() {
        this.undone = true;
    }

    public Set<Long> getClaimIds() {
        return claimIds;
    }

    public Set<Long> getAliasIds() {
        return aliasIds;
    }
}
