package net.officefloor.hq.app.project;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import net.officefloor.hq.app.client.Client;
import net.officefloor.hq.app.tag.Tag;

/** A project done for a client. */
@Entity
@Table(name = "project")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String code;

    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    private boolean archived;

    private boolean closed;

    @Enumerated(EnumType.STRING)
    private ProjectStatus status = ProjectStatus.ACTIVE;

    private BigDecimal budget;

    private boolean billable = true;

    private LocalDate startDate;

    private LocalDate endDate;

    private String fileRef;

    private String category;

    @ManyToMany
    @JoinTable(name = "project_tag", joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new HashSet<>();

    protected Project() {
    }

    public Project(String name, String code, Client client) {
        this.name = name;
        this.code = code;
        this.client = client;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** The job's short reference code, unique across jobs; null for jobs added before codes existed. */
    public String getCode() {
        return code;
    }

    /** A short description of the work, or null when none was given. */
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Client getClient() {
        return client;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    /** Whether the job is closed; no new invoice can be raised on a closed job. */
    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public void setStatus(ProjectStatus status) {
        this.status = status;
    }

    /** The amount this project is to be invoiced within, or null when no budget is set. */
    public BigDecimal getBudget() {
        return budget;
    }

    public void setBudget(BigDecimal budget) {
        this.budget = budget;
    }

    /** Whether the work on this project is charged to the client; internal work is non-billable. */
    public boolean isBillable() {
        return billable;
    }

    public void setBillable(boolean billable) {
        this.billable = billable;
    }

    /** The day work on the job starts, or null when not set. */
    public LocalDate getStartDate() {
        return startDate;
    }

    /** The day work on the job ends, or null when not set. */
    public LocalDate getEndDate() {
        return endDate;
    }

    public void setDates(LocalDate startDate, LocalDate endDate) {
        this.startDate = startDate;
        this.endDate = endDate;
    }

    /** A reference to a file kept elsewhere for the job (e.g. a document number), or null when none was noted. */
    public String getFileRef() {
        return fileRef;
    }

    public void setFileRef(String fileRef) {
        this.fileRef = fileRef;
    }

    /** The category the job is put into (e.g. "Web"), or null when it has none. */
    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    /** The tags labelling this project; add or remove to tag or untag it. */
    public Set<Tag> getTags() {
        return tags;
    }
}
