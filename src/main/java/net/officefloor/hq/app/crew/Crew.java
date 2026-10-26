package net.officefloor.hq.app.crew;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/** A crew of the sheet: a name, a lead and its members, each a person of the sheet. */
@Entity
@Table(name = "crew")
public class Crew {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    /** The lead; none once they have been transferred to another sheet. */
    @Column(name = "lead_id")
    private Long leadId;

    /** The members in the order they were added. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "crew_id", nullable = false)
    @OrderBy("id ASC")
    private List<CrewMember> members = new ArrayList<>();

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

    public Long getLeadId() {
        return leadId;
    }

    /** The members' person ids in the order they were added. */
    public List<Long> getMemberIds() {
        return members.stream().map(CrewMember::getPersonId).toList();
    }

    /** Whether the person is the lead or a member of the crew. */
    public boolean includes(long personId) {
        return isLead(personId) || hasMember(personId);
    }

    /** Whether the person is the crew's lead. */
    public boolean isLead(long personId) {
        return leadId != null && leadId == personId;
    }

    public boolean hasMember(long personId) {
        return members.stream().anyMatch((m) -> m.getPersonId() == personId);
    }

    public void addMember(long personId) {
        members.add(new CrewMember(personId));
    }

    /** Give a person's place in the crew, as lead or member, to another person; a member place is dropped when
     *  that person is already in the crew, and on taking the lead they leave the members. */
    public void movePerson(long fromPersonId, long intoPersonId) {
        if (isLead(fromPersonId)) {
            leadId = intoPersonId;
            members.removeIf((m) -> m.getPersonId() == intoPersonId);
        }
        for (CrewMember m : List.copyOf(members)) {
            if (m.getPersonId() != fromPersonId) continue;
            if (includes(intoPersonId)) members.remove(m);
            else m.setPersonId(intoPersonId);
        }
    }

    /** Take the person out of the crew's members; nothing when they are not one. */
    public void removeMember(long personId) {
        members.removeIf((m) -> m.getPersonId() == personId);
    }

    /** Take the person out of the crew: as lead, leaving it with none, or as a member. */
    public void removePerson(long personId) {
        if (isLead(personId)) leadId = null;
        removeMember(personId);
    }
}
