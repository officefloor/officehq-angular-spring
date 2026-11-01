package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A person in a crew, at most once, never its lead. */
@Entity
@Table(name = "crew_member")
public class CrewMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "crew_id", nullable = false)
    private Long crewId;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    protected CrewMember() {}

    public CrewMember(Long crewId, String sheetId, Long personId) {
        this.crewId = crewId;
        this.sheetId = sheetId;
        this.personId = personId;
    }

    public Long getId() { return id; }

    public Long getCrewId() { return crewId; }

    public String getSheetId() { return sheetId; }

    public Long getPersonId() { return personId; }
}
