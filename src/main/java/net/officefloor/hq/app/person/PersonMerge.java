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

/** One merge of a person into another: who made it on which sheet's today, the claims that moved and the
 *  names the other was given as aliases, so it can be undone. */
@Entity
@Table(name = "person_merge")
public class PersonMerge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    /** The person merged, kept with no sheet while the merge stands. */
    @Column(name = "person_id", nullable = false)
    private Long personId;

    /** The person merged into. */
    @Column(name = "into_id", nullable = false)
    private Long intoId;

    @Column(name = "made_by", nullable = false)
    private String madeBy;

    /** The sheet's today when the merge was made. */
    @Column(name = "made_on", nullable = false)
    private LocalDate madeOn;

    @Column(name = "undone", nullable = false)
    private boolean undone;

    /** The ids of the claims that moved from the person merged. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_claim", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "claim_id", nullable = false)
    private Set<Long> claimIds = new HashSet<>();

    /** The names the person merged into was given as aliases: the merged name, then the merged aliases. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_merge_alias", joinColumns = @JoinColumn(name = "merge_id"))
    @Column(name = "alias", nullable = false)
    private List<String> aliases = new ArrayList<>();

    protected PersonMerge() {
    }

    public PersonMerge(String sheetId, Long personId, Long intoId, String madeBy, LocalDate madeOn,
            Set<Long> claimIds, List<String> aliases) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.intoId = intoId;
        this.madeBy = madeBy;
        this.madeOn = madeOn;
        this.claimIds.addAll(claimIds);
        this.aliases.addAll(aliases);
    }

    public Long getPersonId() {
        return personId;
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

    public Set<Long> getClaimIds() {
        return Set.copyOf(claimIds);
    }

    public List<String> getAliases() {
        return List.copyOf(aliases);
    }
}
