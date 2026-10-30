package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A person in a crew, at most once, and never the crew's lead. */
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

    public void setPersonId(Long personId) {
        this.personId = personId;
    }
}
