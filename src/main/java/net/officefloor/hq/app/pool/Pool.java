package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A pool on a sheet: a name its members share in, each with a share percent. */
@Entity
@Table(name = "pool")
public class Pool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    protected Pool() {
    }

    public Pool(String sheetId, String name) {
        this.sheetId = sheetId;
        this.name = name;
    }

    /** The pool a crew is, under the crew's name: never stored, its id the crew's id negated so it is told apart from
     *  the stored pools. */
    public static Pool ofCrew(String sheetId, long crewId, String name) {
        Pool pool = new Pool(sheetId, name);
        pool.id = -crewId;
        return pool;
    }

    /** Whether this is the pool a crew is. */
    public boolean isCrew() {
        return id != null && id < 0;
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
}
