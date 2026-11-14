package net.officefloor.hq.app.crew;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A person's membership of a crew, other than as its lead. */
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

    /** The sheet's today when the membership was added; null for one added before this was kept. */
    @Column(name = "made_on", updatable = false)
    private LocalDate madeOn;

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

    /** Make the membership another person's. */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    /** Keep the sheet's today the membership is added on. */
    public CrewMember madeOn(LocalDate day) {
        this.madeOn = day;
        return this;
    }

    /** Whether the membership stood at the end of a day: it was added while the sheet's today was on or
     *  before it (one added before that was kept always stood). */
    public boolean madeAsOf(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }
}
