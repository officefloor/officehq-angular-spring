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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A merge of one person into another, kept so the last into a person can be undone (see
 * {@link PersonMergeService#undo}): the sheet's today it was made on and by whom, the merged person as
 * they stood (their state, to restore them under the same id), the claims that moved from them and their
 * own aliases. */
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

    @Column(name = "from_id", nullable = false)
    private Long fromId;

    @Column(name = "from_name", nullable = false)
    private String fromName;

    @Column(name = "merged_by", nullable = false)
    private String mergedBy;

    @Column(name = "merged_on", nullable = false)
    private LocalDate mergedOn;

    /** The merged person as they stood before the merge, as JSON of their fields. */
    @Lob
    @Column(name = "person_state", nullable = false)
    private String personState;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_claim", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "claim_id", nullable = false)
    private List<Long> claimIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_alias", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "alias", nullable = false)
    private List<String> aliases = new ArrayList<>();

    @Column(name = "undone", nullable = false)
    private boolean undone;

    protected PersonMerge() {
    }

    public PersonMerge(String sheetId, Long intoId, Long fromId, String fromName, String mergedBy, LocalDate mergedOn,
            String personState, List<Long> claimIds, List<String> aliases) {
        this.sheetId = sheetId;
        this.intoId = intoId;
        this.fromId = fromId;
        this.fromName = fromName;
        this.mergedBy = mergedBy;
        this.mergedOn = mergedOn;
        this.personState = personState;
        this.claimIds.addAll(claimIds);
        this.aliases.addAll(aliases);
    }

    public Long getId() {
        return id;
    }

    public Long getIntoId() {
        return intoId;
    }

    public Long getFromId() {
        return fromId;
    }

    public String getFromName() {
        return fromName;
    }

    public String getMergedBy() {
        return mergedBy;
    }

    public LocalDate getMergedOn() {
        return mergedOn;
    }

    public String getPersonState() {
        return personState;
    }

    public List<Long> getClaimIds() {
        return claimIds;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public boolean isUndone() {
        return undone;
    }

    public void undo() {
        this.undone = true;
    }
}
