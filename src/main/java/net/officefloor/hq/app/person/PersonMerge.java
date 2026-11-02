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
 * A merge of a person into another on a sheet, kept so the last one into a person can be undone: by
 * whom and on which sheet-today, the aliases the merged person had, and the claims that moved with them.
 */
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

    @Column(name = "merged_by", nullable = false)
    private String mergedBy;

    @Column(name = "merged_on", nullable = false)
    private LocalDate mergedOn;

    /** Their aliases, as given, in the order they were added, one per line. */
    @Column(name = "aliases", nullable = false)
    private String aliases;

    /** The ids of the claims that moved with them, comma separated. */
    @Column(name = "claim_ids", nullable = false)
    private String claimIds;

    protected PersonMerge() {}

    public PersonMerge(String sheetId, Long fromId, Long intoId, String mergedBy, LocalDate mergedOn,
            List<String> aliases, List<Long> claimIds) {
        this.sheetId = sheetId;
        this.fromId = fromId;
        this.intoId = intoId;
        this.mergedBy = mergedBy;
        this.mergedOn = mergedOn;
        this.aliases = String.join("\n", aliases);
        this.claimIds = claimIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public Long getId() { return id; }

    public Long getFromId() { return fromId; }

    public Long getIntoId() { return intoId; }

    public String getMergedBy() { return mergedBy; }

    public LocalDate getMergedOn() { return mergedOn; }

    public List<String> getAliases() {
        return aliases.isEmpty() ? List.of() : List.of(aliases.split("\n"));
    }

    public List<Long> getClaimIds() {
        return claimIds.isEmpty() ? List.of() : Arrays.stream(claimIds.split(",")).map(Long::valueOf).toList();
    }
}
