package net.officefloor.hq.app.pool;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.officefloor.hq.app.history.History;

/** A sheet's pool: a name, and its members, each a person or another pool with a share percent. */
@Entity
@EntityListeners(History.class)
@Table(name = "pool")
public class Pool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "pool_id", nullable = false)
    private List<PoolMember> members = new ArrayList<>();

    /** Moved on by each save, a change to its members too, so the history keeps each change. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Pool() {
    }

    public Pool(String sheetId, String name) {
        this.sheetId = sheetId;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getName() {
        return name;
    }

    /** Its members, in the order they were added. */
    public List<PoolMember> getMembers() {
        return members.stream()
                .sorted(Comparator.comparing(PoolMember::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public boolean hasMember(long personId) {
        return members.stream().anyMatch(m -> Objects.equals(m.getPersonId(), personId));
    }

    public boolean hasMemberPool(long poolId) {
        return members.stream().anyMatch(m -> Objects.equals(m.getMemberPoolId(), poolId));
    }

    /** Add a person with a share, from and to a date when given. */
    public void addMember(long personId, BigDecimal share, LocalDate from, LocalDate to) {
        members.add(new PoolMember(personId, null, share, from, to));
    }

    /** Add another pool with a share, from and to a date when given. */
    public void addMemberPool(long poolId, BigDecimal share, LocalDate from, LocalDate to) {
        members.add(new PoolMember(null, poolId, share, from, to));
    }

    /** Drop a person's membership. */
    public void removeMember(long personId) {
        members.removeIf(m -> Objects.equals(m.getPersonId(), personId));
    }

    /** Move a person's membership to another: their share is added to the other's (at most 100.00) when the
     *  other is already a member. */
    public void moveMember(long fromId, long intoId) {
        PoolMember moving = members.stream().filter(m -> Objects.equals(m.getPersonId(), fromId)).findFirst().orElse(null);
        if (moving == null) {
            return;
        }
        PoolMember kept = members.stream().filter(m -> Objects.equals(m.getPersonId(), intoId)).findFirst().orElse(null);
        if (kept == null) {
            moving.setPersonId(intoId);
            return;
        }
        members.remove(moving);
        kept.setShare(kept.getShare().add(moving.getShare()).min(new BigDecimal("100.00")));
    }
}
