package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A crew of the sheet, each name once, led by one of its people. */
@Entity
@Table(name = "crew")
public class Crew {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "lead_person_id", nullable = false)
    private Long leadPersonId;

    protected Crew() {}

    public Crew(String sheetId, String name, Long leadPersonId) {
        this.sheetId = sheetId;
        this.name = name;
        this.leadPersonId = leadPersonId;
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public String getName() { return name; }

    public Long getLeadPersonId() { return leadPersonId; }
}
