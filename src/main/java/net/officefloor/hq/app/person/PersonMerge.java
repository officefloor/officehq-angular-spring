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

/** A merge of a person into another on a sheet: who made it and on which sheet-today, the claims it
 *  moved to the other and the aliases it gave them, so the one who made it can undo it that day. */
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

    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_claim", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "claim_id", nullable = false)
    private Set<Long> claimIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_alias", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "alias", nullable = false)
    private Set<String> aliases = new HashSet<>();

    protected PersonMerge() {
    }

    public PersonMerge(String sheetId, Long fromId, Long intoId, String madeBy, LocalDate madeOn,
            Set<Long> claimIds, Set<String> aliases) {
        this.sheetId = sheetId;
        this.fromId = fromId;
        this.intoId = intoId;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
        this.claimIds.addAll(claimIds);
        this.aliases.addAll(aliases);
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

    public String getMadeBy() {
        return madeBy;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    public boolean isUndone() {
        return undone;
    }

    public void undo() {
        this.undone = true;
    }

    /** The claims the merge moved to the other. */
    public Set<Long> getClaimIds() {
        return claimIds;
    }

    /** The aliases the merge gave the other (those they did not go by already). */
    public Set<String> getAliases() {
        return aliases;
    }
}
