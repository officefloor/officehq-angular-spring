import {
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
} from "@angular/core";
import {
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from "@angular/forms";
import { ChecklistItem, TaskService } from "./task.service";

// A task's checklist: sub-items that are added and ticked off one by one.
@Component({
  selector: "app-task-checklist",
  imports: [ReactiveFormsModule],
  template: `
    <div [attr.data-testid]="'task-checklist-' + taskId()">
      @if (checklist().length > 0) {
        <p
          [id]="'checklist-progress-' + taskId()"
          data-testid="checklist-progress"
        >
          {{ doneCount() }} of {{ checklist().length }} done
        </p>
        <ul [attr.aria-label]="'Checklist for ' + taskTitle()">
          @for (item of checklist(); track item.id) {
            <li [attr.data-testid]="'checklist-item-row-' + item.id">
              <input
                type="checkbox"
                [id]="'checklist-item-' + item.id"
                [attr.data-testid]="'checklist-item-toggle-' + item.id"
                [checked]="item.done"
                [disabled]="toggling() === item.id"
                (change)="toggle(item, $event)"
              />
              <label
                [for]="'checklist-item-' + item.id"
                data-testid="checklist-item-text"
                >{{ item.text }}</label
              >
            </li>
          }
        </ul>
      }
      @if (toggleError()) {
        <p role="alert" data-testid="checklist-toggle-error">
          {{ toggleError() }}
        </p>
      }
      <form
        [formGroup]="form"
        (ngSubmit)="add()"
        data-testid="checklist-form"
        novalidate
      >
        <label [for]="'checklist-new-' + taskId()">New checklist item</label>
        <input
          [id]="'checklist-new-' + taskId()"
          type="text"
          formControlName="text"
          maxlength="255"
          data-testid="checklist-form-text"
        />
        <button
          type="submit"
          data-testid="checklist-form-submit"
          [disabled]="saving()"
          [attr.aria-label]="'Add checklist item to ' + taskTitle()"
        >
          Add
        </button>
        @if (saveError()) {
          <p role="alert" data-testid="checklist-form-error">
            {{ saveError() }}
          </p>
        }
      </form>
    </div>
  `,
})
export class TaskChecklist {
  private readonly service = inject(TaskService);

  readonly projectId = input.required<number>();
  readonly taskId = input.required<number>();
  readonly taskTitle = input.required<string>();
  readonly items = input<ChecklistItem[]>([]);

  protected readonly checklist = linkedSignal(() => this.items());
  protected readonly doneCount = computed(
    () => this.checklist().filter((i) => i.done).length,
  );
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly toggling = signal<number | null>(null);
  protected readonly toggleError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    text: ["", [Validators.required, Validators.maxLength(255)]],
  });

  protected add(): void {
    const text = this.form.getRawValue().text.trim();
    if (this.form.invalid || !text) {
      this.form.reset();
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    this.service
      .addChecklistItem(this.projectId(), this.taskId(), text)
      .subscribe({
        next: (created) => {
          this.checklist.update((list) => [...list, created]);
          this.form.reset();
          this.saving.set(false);
        },
        error: () => {
          this.saveError.set(
            "Could not add the checklist item. Please try again.",
          );
          this.saving.set(false);
        },
      });
  }

  protected toggle(item: ChecklistItem, event: Event): void {
    const checkbox = event.target as HTMLInputElement;
    this.toggling.set(item.id);
    this.toggleError.set(null);
    this.service
      .toggleChecklistItem(this.projectId(), this.taskId(), item.id)
      .subscribe({
        next: (updated) => {
          this.checklist.update((list) =>
            list.map((i) => (i.id === updated.id ? updated : i)),
          );
          this.toggling.set(null);
        },
        error: () => {
          this.toggleError.set(
            "Could not update the checklist item. Please try again.",
          );
          checkbox.checked = item.done;
          this.toggling.set(null);
        },
      });
  }
}
