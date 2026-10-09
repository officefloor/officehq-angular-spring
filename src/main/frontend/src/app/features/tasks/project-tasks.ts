import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Task, TaskService } from './task.service';

export type TaskFilter = 'ALL' | 'OPEN' | 'DONE';

// A project's task list: add tasks and tick them off (OPEN <-> DONE) as they are finished.
@Component({
  selector: 'app-project-tasks',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="project-tasks-heading" data-testid="project-tasks">
      <h2 id="project-tasks-heading">Tasks</h2>

      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="task-form" novalidate>
        <div>
          <label for="task-title">Task</label>
          <input
            id="task-title"
            type="text"
            formControlName="title"
            data-testid="task-form-title"
            [attr.aria-invalid]="showTitleError()"
            [attr.aria-describedby]="showTitleError() ? 'task-title-error' : null"
          />
          @if (showTitleError()) {
            <p id="task-title-error" role="alert" data-testid="task-form-title-error">
              Task is required.
            </p>
          }
        </div>
        <button type="submit" data-testid="task-form-submit" [disabled]="saving()">Add task</button>
        @if (saveError()) {
          <p role="alert" data-testid="task-form-error">{{ saveError() }}</p>
        }
      </form>

      @if (toggleError()) {
        <p role="alert" data-testid="task-toggle-error">{{ toggleError() }}</p>
      }

      @if (tasks.error()) {
        <p role="alert" data-testid="project-tasks-error">Could not load the tasks.</p>
      } @else if (tasks.hasValue()) {
        @if (tasks.value().length === 0) {
          <p data-testid="project-tasks-empty">No tasks for this project yet.</p>
        } @else {
          <div>
            <label for="task-filter">Show</label>
            <select
              id="task-filter"
              data-testid="task-filter"
              [value]="filter()"
              (change)="setFilter($event)"
            >
              <option value="ALL">All tasks</option>
              <option value="OPEN">Open tasks</option>
              <option value="DONE">Done tasks</option>
            </select>
          </div>
          @if (visibleTasks().length === 0) {
            <p data-testid="project-tasks-filter-empty">
              {{ filter() === 'OPEN' ? 'No open tasks.' : 'No done tasks.' }}
            </p>
          } @else {
            <table data-testid="project-tasks-table">
              <caption>Tasks for this project</caption>
              <thead>
                <tr>
                  <th scope="col">Task</th>
                  <th scope="col">Status</th>
                  <th scope="col">Action</th>
                </tr>
              </thead>
              <tbody>
                @for (t of visibleTasks(); track t.id) {
                  <tr [attr.data-testid]="'task-row-' + t.id">
                    <td data-testid="task-title">{{ t.title }}</td>
                    <td data-testid="task-status">{{ t.done ? 'DONE' : 'OPEN' }}</td>
                    <td>
                      <button
                        type="button"
                        [attr.data-testid]="'task-toggle-' + t.id"
                        [attr.aria-pressed]="t.done"
                        [attr.aria-label]="(t.done ? 'Reopen ' : 'Tick off ') + t.title"
                        [disabled]="toggling() === t.id"
                        (click)="toggle(t)"
                      >
                        {{ t.done ? 'Reopen' : 'Tick off' }}
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          }
        }
      }
    </section>
  `,
})
export class ProjectTasks {
  private readonly service = inject(TaskService);

  readonly projectId = input.required<number>();

  protected readonly tasks = rxResource({
    params: () => this.projectId(),
    stream: ({ params }) => this.service.listForProject(params),
  });
  protected readonly filter = signal<TaskFilter>('ALL');
  protected readonly visibleTasks = computed(() => {
    const list = this.tasks.value() ?? [];
    switch (this.filter()) {
      case 'OPEN':
        return list.filter((t) => !t.done);
      case 'DONE':
        return list.filter((t) => t.done);
      default:
        return list;
    }
  });
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly toggling = signal<number | null>(null);
  protected readonly toggleError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    title: ['', [Validators.required, Validators.maxLength(255)]],
  });

  protected setFilter(event: Event): void {
    this.filter.set((event.target as HTMLSelectElement).value as TaskFilter);
  }

  protected showTitleError(): boolean {
    const control = this.form.controls.title;
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    const title = this.form.getRawValue().title.trim();
    if (this.form.invalid || !title) {
      this.form.controls.title.setValue('');
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    this.service.create(this.projectId(), title).subscribe({
      next: (created) => {
        this.tasks.update((list) => [...(list ?? []), created]);
        this.form.reset();
        this.saving.set(false);
      },
      error: () => {
        this.saveError.set('Could not save the task. Please try again.');
        this.saving.set(false);
      },
    });
  }

  protected toggle(task: Task): void {
    this.toggling.set(task.id);
    this.toggleError.set(null);
    this.service.toggle(this.projectId(), task.id).subscribe({
      next: (updated) => {
        this.tasks.update((list) => (list ?? []).map((t) => (t.id === updated.id ? updated : t)));
        this.toggling.set(null);
      },
      error: () => {
        this.toggleError.set('Could not update the task. Please try again.');
        this.toggling.set(null);
      },
    });
  }
}
