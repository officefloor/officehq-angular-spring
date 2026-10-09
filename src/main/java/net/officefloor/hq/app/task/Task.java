package net.officefloor.hq.app.task;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import net.officefloor.hq.app.project.Project;

/** A to-do item on a project's task list, ticked off once done. */
@Entity
@Table(name = "task")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    private String title;

    private boolean done;

    /** When the task should be finished by; optional. */
    private LocalDate dueDate;

    protected Task() {
    }

    public Task(Project project, String title, LocalDate dueDate) {
        this.project = project;
        this.title = title;
        this.dueDate = dueDate;
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getTitle() {
        return title;
    }

    public boolean isDone() {
        return done;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    /** Flips the task between open and done. */
    public void toggle() {
        this.done = !this.done;
    }
}
