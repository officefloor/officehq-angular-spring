package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A crew on a sheet: a name, a lead chosen from the sheet's people, and its members. */
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

    @Column(name = "lead_id")
    private Long leadId;

    protected Crew() {
    }

    public Crew(String sheetId, String name, Long leadId) {
        this.sheetId = sheetId;
        this.name = name;
        this.leadId = leadId;
    }

    /** The crew as it stood in the sheet's history, unsaved, with the id it had. */
    public static Crew rebuilt(String sheetId, long id, String name, Long leadId) {
        Crew crew = new Crew(sheetId, name, leadId);
        crew.id = id;
        return crew;
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

    /** The lead; null once the lead has left the sheet. */
    public Long getLeadId() {
        return leadId;
    }

    public void setLeadId(Long leadId) {
        this.leadId = leadId;
    }
}
