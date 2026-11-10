package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A member of a crew: a person on the sheet, in a crew at most once and never its lead. */
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

    public Long getCrewId() {
        return crewId;
    }

    public Long getPersonId() {
        return personId;
    }

    /** Move the membership to another person (a merge). */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }
}
