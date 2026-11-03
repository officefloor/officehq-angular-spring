package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A pool on a sheet: a (trimmed) name, unique on the sheet, that people are members of. A crew's
 *  pool carries its crew: its members are then the crew's people, not stored ones. */
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

    /** The crew the pool is; null for a pool added as one. */
    @Column(name = "crew_id")
    private Long crewId;

    /** The sheet's today when it was added; null: before this was kept (always there). */
    @Column(name = "added_on")
    private LocalDate addedOn;

    protected Pool() {
    }

    public Pool(String sheetId, String name) {
        this.sheetId = sheetId;
        this.name = name;
    }

    /** A crew's pool, under the crew's name. */
    public static Pool ofCrew(String sheetId, String name, Long crewId) {
        Pool p = new Pool(sheetId, name);
        p.crewId = crewId;
        return p;
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

    public Long getCrewId() {
        return crewId;
    }

    /** Whether the pool is a crew's. */
    public boolean isCrew() {
        return crewId != null;
    }

    public LocalDate getAddedOn() {
        return addedOn;
    }

    /** Stamp the day it was added (the sheet's today then). */
    public void setAddedOn(LocalDate addedOn) {
        this.addedOn = addedOn;
    }

    /** Whether it was there at the end of a day. */
    public boolean isAddedBy(LocalDate day) {
        return addedOn == null || !addedOn.isAfter(day);
    }
}
