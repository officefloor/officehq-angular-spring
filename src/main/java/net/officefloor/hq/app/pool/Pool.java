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
import java.util.ArrayList;
import java.util.List;

/** A pool on a sheet: people and other pools, each with a share percent. */
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

    /** The pool's members, in the order they were added. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "pool_id", nullable = false)
    @OrderBy("id")
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
        return members;
    }

    public boolean hasMember(Long personId) {
        return members.stream().anyMatch(m -> personId.equals(m.getPersonId()));
    }

    public boolean hasMemberPool(Long poolId) {
        return members.stream().anyMatch(m -> poolId.equals(m.getMemberPoolId()));
    }
}
