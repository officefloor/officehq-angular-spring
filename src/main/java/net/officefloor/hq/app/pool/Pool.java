package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A pool on a sheet: a name, trimmed and on the sheet at most once, never a crew's but its own crew's. A
 * crew's pool names the crew; its members are the crew's, not stored.
 */
@Entity
@Table(name = "pool")
public class Pool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(nullable = false)
    private String name;

    @Column(name = "crew_id")
    private Long crewId;

    protected Pool() {
    }

    public Pool(String sheetId, String name) {
        this.sheetId = sheetId;
        this.name = name;
    }

    /** A crew's pool, under the crew's name. */
    public static Pool ofCrew(String sheetId, String name, Long crewId) {
        Pool pool = new Pool(sheetId, name);
        pool.crewId = crewId;
        return pool;
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

    /** The crew this pool is; null for a pool of its own. */
    public Long getCrewId() {
        return crewId;
    }
}
