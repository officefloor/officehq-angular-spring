package net.officefloor.hq.app.task;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
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

    protected Task() {
    }

    public Task(Project project, String title) {
        this.project = project;
        this.title = title;
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

    /** Flips the task between open and done. */
    public void toggle() {
        this.done = !this.done;
    }
}
