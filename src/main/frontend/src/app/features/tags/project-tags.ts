import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable } from 'rxjs';
import { Tag, TagService } from './tag.service';

// A project's tags: labels shown as chips that group projects. Put an existing tag on the project,
// take one off, or create a new tag.
@Component({
  selector: 'app-project-tags',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="project-tags-heading" data-testid="project-tags">
      <h2 id="project-tags-heading">Tags</h2>

      @if (projectTags.error()) {
        <p role="alert" data-testid="project-tags-error">Could not load the tags.</p>
      } @else if (projectTags.hasValue()) {
        @if (projectTags.value().length === 0) {
          <p data-testid="project-tags-empty">No tags on this project yet.</p>
        } @else {
          <ul class="chips" aria-label="Project tags" data-testid="project-tag-list">
            @for (t of projectTags.value(); track t.id) {
              <li class="chip">
                <span [attr.data-testid]="'project-tag-' + t.id">{{ t.name }}</span>
                <button
                  type="button"
                  [attr.data-testid]="'project-tag-remove-' + t.id"
                  [attr.aria-label]="'Remove tag ' + t.name"
                  [disabled]="busy()"
                  (click)="remove(t)"
                >
                  <span aria-hidden="true">×</span>
                </button>
              </li>
            }
          </ul>
        }
      }

      <form [formGroup]="addForm" (ngSubmit)="add()" data-testid="project-tag-add-form" novalidate>
        <label for="project-tag-add">Add tag</label>
        <select id="project-tag-add" formControlName="tagId" data-testid="project-tag-add">
          <option value="">Choose a tag…</option>
          @for (t of availableTags(); track t.id) {
            <option [value]="t.id">{{ t.name }}</option>
          }
        </select>
        <button type="submit" data-testid="project-tag-add-submit" [disabled]="busy()">Add</button>
      </form>

      <form [formGroup]="newForm" (ngSubmit)="createTag()" data-testid="tag-new-form" novalidate>
        <label for="tag-new-name">New tag</label>
        <input
          id="tag-new-name"
          type="text"
          formControlName="name"
          maxlength="50"
          data-testid="tag-new-name"
        />
        <button type="submit" data-testid="tag-new-submit" [disabled]="busy()">
          Create and add
        </button>
      </form>

      @if (error()) {
        <p role="alert" data-testid="project-tag-error">{{ error() }}</p>
      }
    </section>
  `,
  styles: `
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      list-style: none;
      padding: 0;
    }
    .chip {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      padding: 0.125rem 0.5rem;
      border: 1px solid #555;
      border-radius: 1rem;
      background: #eef;
      color: #111;
    }
  `,
})
export class ProjectTags {
  private readonly service = inject(TagService);
  private readonly fb = inject(NonNullableFormBuilder);

  readonly projectId = input.required<number>();

  protected readonly projectTags = rxResource({
    params: () => this.projectId(),
    stream: ({ params }) => this.service.listForProject(params),
  });
  protected readonly allTags = rxResource({ stream: () => this.service.list() });

  /** Tags that can still be put on the project. */
  protected readonly availableTags = computed(() => {
    const applied = new Set((this.projectTags.value() ?? []).map((t) => t.id));
    return (this.allTags.value() ?? []).filter((t) => !applied.has(t.id));
  });

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly addForm = this.fb.group({ tagId: ['', Validators.required] });
  protected readonly newForm = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(50)]],
  });

  protected add(): void {
    const tagId = Number(this.addForm.getRawValue().tagId);
    if (!tagId) {
      this.error.set('Choose a tag to add.');
      return;
    }
    this.apply(this.service.addToProject(this.projectId(), tagId), 'Could not add the tag.', () =>
      this.addForm.reset(),
    );
  }

  protected remove(tag: Tag): void {
    this.apply(
      this.service.removeFromProject(this.projectId(), tag.id),
      'Could not remove the tag.',
    );
  }

  protected createTag(): void {
    const name = this.newForm.getRawValue().name.trim();
    if (!name) {
      this.error.set('Enter a name for the new tag.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.service.create(name).subscribe({
      next: (created) => {
        this.allTags.update((list) => [...(list ?? []), created]);
        this.newForm.reset();
        this.busy.set(false);
        this.apply(
          this.service.addToProject(this.projectId(), created.id),
          'Could not add the tag.',
        );
      },
      error: () => {
        this.error.set('Could not create the tag. It may already exist.');
        this.busy.set(false);
      },
    });
  }

  private apply(request: Observable<Tag[]>, failure: string, done?: () => void): void {
    this.busy.set(true);
    this.error.set(null);
    request.subscribe({
      next: (tags) => {
        this.projectTags.set(tags);
        done?.();
        this.busy.set(false);
      },
      error: () => {
        this.error.set(failure);
        this.busy.set(false);
      },
    });
  }
}
