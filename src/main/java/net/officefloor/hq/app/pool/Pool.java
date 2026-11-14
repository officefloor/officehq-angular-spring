package net.officefloor.hq.app.pool;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A pool on a sheet: a name, unique on the sheet, whose members each hold a share percent. */
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

    /** The sheet's today when the pool was added; null for one added before this was kept. */
    @Column(name = "made_on", updatable = false)
    private LocalDate madeOn;

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

    /** Keep the sheet's today the pool is added on. */
    public Pool madeOn(LocalDate day) {
        this.madeOn = day;
        return this;
    }

    /** Whether the pool stood at the end of a day: it was added while the sheet's today was on or
     *  before it (one added before that was kept always stood). */
    public boolean madeAsOf(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }
}
