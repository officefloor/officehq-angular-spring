package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A person in a crew, besides its lead. */
@Entity
@Table(name = "crew_member")
public class CrewMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "crew_id", nullable = false)
    private Long crewId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    protected CrewMember() {
    }

    public CrewMember(String sheetId, Long crewId, Long personId) {
        this.sheetId = sheetId;
        this.crewId = crewId;
        this.personId = personId;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getCrewId() {
        return crewId;
    }

    public Long getPersonId() {
        return personId;
    }

    /** Moves the membership to another person, keeping its place in the order members were added. */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }
}
