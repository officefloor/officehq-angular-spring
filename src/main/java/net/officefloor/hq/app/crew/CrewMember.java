package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A member of a crew: a person of the sheet. */
@Entity
@Table(name = "crew_member")
public class CrewMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    protected CrewMember() {
    }

    CrewMember(long personId) {
        this.personId = personId;
    }

    public Long getPersonId() {
        return personId;
    }

    void setPersonId(long personId) {
        this.personId = personId;
    }
}
