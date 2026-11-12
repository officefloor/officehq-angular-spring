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

/** A crew on a sheet: a lead and the members added to it. */
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

    /** The lead; null once the lead was transferred to another sheet. */
    @Column(name = "lead_person_id")
    private Long leadId;

    /** The crew's members besides its lead, in the order they were added. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "crew_id", nullable = false)
    @OrderBy("id")
    private List<CrewMember> members = new ArrayList<>();

    protected Crew() {
    }

    public Crew(String sheetId, String name, Long leadId) {
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

    /** The lead, or null when the crew is without one. */
    public Long getLeadId() {
        return leadId;
    }

    /** Make another person the lead (a merge). */
    public void setLeadId(Long leadId) {
        this.leadId = leadId;
    }

    public List<CrewMember> getMembers() {
        return members;
    }

    /** Whether the person is the lead or one of the members. */
    public boolean includes(Long personId) {
        return personId.equals(leadId) || members.stream().anyMatch(m -> personId.equals(m.getPersonId()));
    }
}
