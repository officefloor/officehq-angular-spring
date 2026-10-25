package net.officefloor.hq.app.pool;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** A pool of the sheet: a name and its members, each a person with a share percent. */
@Entity
@Table(name = "pool")
public class Pool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    /** The members in the order they were added. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "pool_id", nullable = false)
    @OrderBy("id ASC")
    private List<PoolMember> members = new ArrayList<>();

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

    public List<PoolMember> getMembers() {
        return List.copyOf(members);
    }

    /** Whether the person is a member of the pool. */
    public boolean hasMember(long personId) {
        return members.stream().anyMatch((m) -> m.isPerson(personId));
    }

    /** Give a member's place in the pool to another person: added to that person's share when they are
     *  already a member (held to 100.00), else taken over by them; nothing when the member is not in it. */
    public void moveMember(long fromPersonId, long intoPersonId) {
        PoolMember from = memberOf(fromPersonId);
        if (from == null) return;
        PoolMember into = memberOf(intoPersonId);
        if (into == null) {
            from.setPersonId(intoPersonId);
        } else {
            into.addShare(from.getShare());
            members.remove(from);
        }
    }

    /** Take the person out of the pool; nothing when they are not in it. */
    public void removeMember(long personId) {
        members.removeIf((m) -> m.isPerson(personId));
    }

    private PoolMember memberOf(long personId) {
        return members.stream().filter((m) -> m.isPerson(personId)).findFirst().orElse(null);
    }

    /** Whether the pool is a member of this one. */
    public boolean hasMemberPool(long poolId) {
        return members.stream().anyMatch((m) -> m.getMemberPoolId() != null && m.getMemberPoolId() == poolId);
    }

    /** Add the person with a share (kept to two decimals). */
    public void addMember(long personId, BigDecimal share) {
        members.add(new PoolMember(personId, null, share));
    }

    /** Add another pool as a member with a share (kept to two decimals). */
    public void addMemberPool(long poolId, BigDecimal share) {
        members.add(new PoolMember(null, poolId, share));
    }
}
