package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A person's membership of a crew (other than as its lead). */
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

    /** The sheet's today when it was added; null: before this was kept (always there). */
    @Column(name = "added_on")
    private LocalDate addedOn;

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

    /** Make the membership another person's (the member was merged into them). */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public LocalDate getAddedOn() {
        return addedOn;
    }

    /** Stamp the day it was added (the sheet's today then). */
    public void setAddedOn(LocalDate addedOn) {
        this.addedOn = addedOn;
    }

    /** Whether it was there at the end of a day. */
    public boolean isAddedBy(LocalDate day) {
        return addedOn == null || !addedOn.isAfter(day);
    }
}
