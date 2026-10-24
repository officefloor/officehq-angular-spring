package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A crew on a sheet: a name, unique on the sheet, led by a person on the sheet. */
@Entity
@Table(name = "sheet_crew")
public class SheetCrew {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "lead_person_id")
    private Long leadPersonId;

    protected SheetCrew() {
    }

    public SheetCrew(String sheetId, String name, Long leadPersonId) {
        this.sheetId = sheetId;
        this.name = name;
        this.leadPersonId = leadPersonId;
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

    public Long getLeadPersonId() {
        return leadPersonId;
    }

    public void setLeadPersonId(Long leadPersonId) {
        this.leadPersonId = leadPersonId;
    }
}
