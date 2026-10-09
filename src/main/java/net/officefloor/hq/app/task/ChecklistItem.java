package net.officefloor.hq.app.task;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A sub-item on a task's checklist, ticked off on its own. */
@Entity
@Table(name = "task_checklist_item")
public class ChecklistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    private String text;

    private boolean done;

    protected ChecklistItem() {
    }

    public ChecklistItem(Task task, String text) {
        this.task = task;
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public Task getTask() {
        return task;
    }

    public String getText() {
        return text;
    }

    public boolean isDone() {
        return done;
    }

    /** Flips the item between open and done. */
    public void toggle() {
        this.done = !this.done;
    }
}
