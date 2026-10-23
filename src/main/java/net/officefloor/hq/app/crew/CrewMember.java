package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A crew's member: a person on the sheet, in the crew at most once and never its lead. */
@Entity
@Table(name = "crew_member")
public class CrewMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "crew_id", nullable = false)
    private Long crewId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    protected CrewMember() {
    }

    public CrewMember(Long crewId, Long personId) {
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

    /** Move the membership to another person on the sheet. */
    public void moveTo(Long personId) {
        this.personId = personId;
    }
}
