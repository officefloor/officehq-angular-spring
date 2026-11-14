package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A crew on a sheet: a name, unique on the sheet, a lead from the sheet's people, and its members. */
@Entity
@Table(name = "crew")
public class Crew {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    /** The sheet's today when the crew was added; null for one added before this was kept. */
    @Column(name = "made_on", updatable = false)
    private LocalDate madeOn;

    protected Crew() {
    }

    public Crew(String sheetId, String name, Long leadId) {
        this.sheetId = sheetId;
        this.name = name;
        this.leadId = leadId;
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

    public Long getLeadId() {
        return leadId;
    }

    /** Make the crew led by another person. */
    public void setLeadId(Long leadId) {
        this.leadId = leadId;
    }

    /** Keep the sheet's today the crew is added on. */
    public Crew madeOn(LocalDate day) {
        this.madeOn = day;
        return this;
    }

    /** Whether the crew stood at the end of a day: it was added while the sheet's today was on or
     *  before it (one added before that was kept always stood). */
    public boolean madeAsOf(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }
}
