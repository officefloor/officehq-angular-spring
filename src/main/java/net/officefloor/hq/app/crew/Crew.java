package net.officefloor.hq.app.crew;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.officefloor.hq.app.history.History;

/** A sheet's crew: a name, a lead (a person of the sheet), and its members, each another person of the sheet. */
@Entity
@EntityListeners(History.class)
@Table(name = "crew")
public class Crew {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "lead_person_id")
    private Long leadId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "crew_id", nullable = false)
    private List<CrewMember> members = new ArrayList<>();

    /** Moved on by each save, a change to its members too, so the history keeps each change. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Crew() {
    }

    public Crew(String sheetId, String name, long leadId) {
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

    /** Its lead's id; null once its lead has left the sheet. */
    public Long getLeadId() {
        return leadId;
    }

    /** Leave it with no lead (its lead has left the sheet); its members stay. */
    public void dropLead() {
        leadId = null;
    }

    /** Its members, in the order they were added. */
    public List<CrewMember> getMembers() {
        return members.stream()
                .sorted(Comparator.comparing(CrewMember::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Whether a person is its lead or one of its members. */
    public boolean includes(long personId) {
        return Objects.equals(leadId, personId)
                || members.stream().anyMatch(m -> Objects.equals(m.getPersonId(), personId));
    }

    public void addMember(long personId) {
        members.add(new CrewMember(personId));
    }

    /** Hand a person's place to another (a merge): the other leads it when they did; a member's place moves to
     *  the other, dropped when the other is in it already. */
    public void movePerson(long fromId, long intoId) {
        if (Objects.equals(leadId, fromId)) {
            leadId = intoId;
            members.removeIf(m -> Objects.equals(m.getPersonId(), intoId));
            return;
        }
        CrewMember moving = members.stream().filter(m -> Objects.equals(m.getPersonId(), fromId)).findFirst().orElse(null);
        if (moving == null) {
            return;
        }
        if (includes(intoId)) {
            members.remove(moving);
        } else {
            moving.setPersonId(intoId);
        }
    }

    /** Drop a person's membership. */
    public void removeMember(long personId) {
        members.removeIf(m -> Objects.equals(m.getPersonId(), personId));
    }
}
