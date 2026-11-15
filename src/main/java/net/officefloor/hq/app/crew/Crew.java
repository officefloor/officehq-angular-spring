package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import net.officefloor.hq.app.history.HistoryListener;

/** A crew on the sheet: a name, unique on the sheet, its lead (a person of the sheet; none once
 *  they have left it) and its members ({@link CrewMember}). */
@Entity
@EntityListeners(HistoryListener.class)
@Table(name = "crew")
public class Crew {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
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

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getName() {
        return name;
    }

    /** The lead's person id; null when the lead has left the sheet. */
    public Long getLeadId() {
        return leadId;
    }

    /** Move the lead to another person. */
    public void setLeadId(Long leadId) {
        this.leadId = leadId;
    }
}
