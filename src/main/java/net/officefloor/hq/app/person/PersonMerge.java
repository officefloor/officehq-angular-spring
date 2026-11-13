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
import java.util.HashSet;
import java.util.Set;

/** A merge of one person into another made on the sheet: by whom and on which sheet-today, the merged person
 *  as they stood before it and the claims that came from them, so it can be undone. */
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

    /** The merged person as they stood before it; null when the merge cannot be undone. */
    @Lob
    @Column(name = "state")
    private String state;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_claim", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "claim_id", nullable = false)
    private Set<Long> claimIds = new HashSet<>();

    protected PersonMerge() {
    }

    public PersonMerge(String sheetId, Long fromId, Long intoId, String madeBy, LocalDate madeOn, String state) {
        this.sheetId = sheetId;
        this.fromId = fromId;
        this.intoId = intoId;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
        this.state = state;
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

    public String getState() {
        return state;
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
}
