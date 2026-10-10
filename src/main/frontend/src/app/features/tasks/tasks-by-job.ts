import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TaskGroup, TaskService } from './task.service';

// Tasks by job: every task, listed under the job it belongs to. Jobs without tasks, and archived
// jobs, are left off. Each job heading opens the job's detail page.
@Component({
  selector: 'app-tasks-by-job',
  imports: [RouterLink],
  template: `
    <h1>Tasks by job</h1>
    <p><a routerLink="/projects" data-testid="tasks-by-job-back">Back to jobs</a></p>

    @if (error()) {
      <p role="alert" data-testid="tasks-by-job-error">{{ error() }}</p>
    } @else if (loaded() && groups().length === 0) {
      <p data-testid="tasks-by-job-empty">No tasks yet.</p>
    } @else {
      @for (g of groups(); track g.projectId) {
        <section
          [attr.data-testid]="'task-group-' + g.projectId"
          [attr.aria-labelledby]="'task-group-heading-' + g.projectId"
        >
          <h2 [id]="'task-group-heading-' + g.projectId">
            <a
              [routerLink]="['/projects', g.projectId]"
              [attr.data-testid]="'task-group-name-' + g.projectId"
              >{{ g.projectCode }} — {{ g.projectName }}</a
            >
            <span [attr.data-testid]="'task-group-count-' + g.projectId">
              ({{ g.tasks.length }} {{ g.tasks.length === 1 ? 'task' : 'tasks' }})</span
            >
          </h2>
          <ul>
            @for (t of g.tasks; track t.id) {
              <li [attr.data-testid]="'task-row-' + t.id">
                <span data-testid="task-title">{{ t.title }}</span>
                @if (t.done) {
                  <span data-testid="task-done"> (done)</span>
                }
              </li>
            }
          </ul>
        </section>
      }
    }
  `,
})
export class TasksByJob {
  private readonly service = inject(TaskService);

  protected readonly groups = signal<TaskGroup[]>([]);
  protected readonly loaded = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.service.listByJob().subscribe({
      next: (list) => {
        this.groups.set(list);
        this.loaded.set(true);
      },
      error: () => this.error.set('Could not load tasks. Please try again.'),
    });
  }
}
