import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Client, ClientService } from '../clients/client.service';
import { Tag, TagService } from '../tags/tag.service';
import { PROJECT_STATUSES, Project, ProjectService, ProjectStatus } from './project.service';

// Projects page: add a project for a client and list all projects with their client's name; each
// project opens its detail page and can be archived once it is no longer needed. Archived projects
// are kept but left off the list unless the archived toggle is on, where they can be restored. The
// list can be narrowed to the projects carrying a chosen tag. Each project is marked active, on hold
// or finished, chosen when it is added and changeable from its row, and the list can be narrowed to
// the projects at a chosen status. The tag and status filters combine, e.g. active projects with a
// given tag. Each project carries a short reference code, given when it is added, that no other
// project may share.
@Component({
  selector: 'app-projects',
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <h1>Jobs</h1>

    <form [formGroup]="form" (ngSubmit)="submit()" data-testid="project-form" novalidate>
      <div>
        <label for="project-name">Name</label>
        <input
          id="project-name"
          type="text"
          formControlName="name"
          data-testid="project-form-name"
          [attr.aria-invalid]="showError('name')"
          [attr.aria-describedby]="showError('name') ? 'project-name-error' : null"
        />
        @if (showError('name')) {
          <p id="project-name-error" role="alert" data-testid="project-form-name-error">
            Name is required.
          </p>
        }
      </div>
      <div>
        <label for="project-code">Code</label>
        <input
          id="project-code"
          type="text"
          formControlName="code"
          maxlength="20"
          autocomplete="off"
          data-testid="project-form-code"
          [attr.aria-invalid]="showError('code')"
          [attr.aria-describedby]="showError('code') ? 'project-code-error' : null"
        />
        @if (showError('code')) {
          <p id="project-code-error" role="alert" data-testid="project-form-code-error">
            @if (form.controls.code.hasError('taken')) {
              A job with this code already exists.
            } @else {
              Enter a code of up to 20 letters, digits or dashes.
            }
          </p>
        }
      </div>
      <div>
        <label for="project-client">Client</label>
        <select
          id="project-client"
          formControlName="clientId"
          data-testid="project-form-client"
          [attr.aria-invalid]="showError('clientId')"
          [attr.aria-describedby]="showError('clientId') ? 'project-client-error' : null"
        >
          <option value="">Select a client</option>
          @for (c of clients(); track c.id) {
            <option [value]="c.id">{{ c.name }}</option>
          }
        </select>
        @if (showError('clientId')) {
          <p id="project-client-error" role="alert" data-testid="project-form-client-error">
            Choose a client.
          </p>
        }
      </div>
      <div>
        <label for="project-status">Status</label>
        <select id="project-status" formControlName="status" data-testid="project-form-status">
          @for (s of statuses; track s.value) {
            <option [value]="s.value">{{ s.label }}</option>
          }
        </select>
      </div>
      <button type="submit" data-testid="project-form-submit" [disabled]="saving()">
        Add job
      </button>
      @if (saveError()) {
        <p role="alert" data-testid="project-form-error">{{ saveError() }}</p>
      }
    </form>

    @if (deleteError()) {
      <p role="alert" data-testid="project-delete-error">{{ deleteError() }}</p>
    }

    <div>
      <input
        id="projects-show-archived"
        type="checkbox"
        data-testid="projects-show-archived"
        [checked]="showArchived()"
        (change)="toggleArchived()"
      />
      <label for="projects-show-archived">Show archived jobs</label>
    </div>

    <div>
      <label for="project-tag-filter">Filter by tag</label>
      <select
        id="project-tag-filter"
        data-testid="project-tag-filter"
        #tagSelect
        [value]="tagFilter() ?? ''"
        (change)="filterByTag(tagSelect.value)"
      >
        <option value="">All tags</option>
        @for (t of tags(); track t.id) {
          <option [value]="t.id">{{ t.name }}</option>
        }
      </select>
    </div>

    <div>
      <label for="project-status-filter">Filter by status</label>
      <select
        id="project-status-filter"
        data-testid="project-status-filter"
        #statusFilterSelect
        [value]="statusFilter() ?? ''"
        (change)="filterByStatus(statusFilterSelect.value)"
      >
        <option value="">All statuses</option>
        @for (s of statuses; track s.value) {
          <option [value]="s.value">{{ s.label }}</option>
        }
      </select>
    </div>

    @if (projects().length === 0) {
      <p data-testid="projects-empty">
        @if (statusFilter() !== null && tagFilter() !== null) {
          No jobs at this status with this tag.
        } @else if (statusFilter() !== null) {
          No jobs at this status.
        } @else {
          {{ tagFilter() === null ? 'No jobs yet.' : 'No jobs with this tag.' }}
        }
      </p>
    } @else {
      <table data-testid="projects-table">
        <caption>All jobs</caption>
        <thead>
          <tr>
            <th scope="col">Code</th>
            <th scope="col">Name</th>
            <th scope="col">Client</th>
            <th scope="col">Status</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (p of projects(); track p.id) {
            <tr [attr.data-testid]="'project-row-' + p.id">
              <td data-testid="project-code">{{ p.code }}</td>
              <td data-testid="project-name">
                {{ p.name }}
                @if (p.archived) {
                  <span [attr.data-testid]="'project-archived-' + p.id">(archived)</span>
                }
              </td>
              <td data-testid="project-client">{{ p.clientName }}</td>
              <td>
                <span data-testid="project-status">{{ p.status }}</span>
                <label class="visually-hidden" [for]="'project-status-select-' + p.id"
                  >Change status of {{ p.name }}</label
                >
                <select
                  #statusSelect
                  [id]="'project-status-select-' + p.id"
                  [attr.data-testid]="'project-status-select-' + p.id"
                  [value]="p.status"
                  [disabled]="deleting() === p.id"
                  (change)="changeStatus(p, statusSelect.value)"
                >
                  @for (s of statuses; track s.value) {
                    <option [value]="s.value">{{ s.label }}</option>
                  }
                </select>
              </td>
              <td>
                <a
                  [routerLink]="['/projects', p.id]"
                  [attr.data-testid]="'project-open-' + p.id"
                  [attr.aria-label]="'Open ' + p.name"
                  >Open</a
                >
                @if (p.archived) {
                  <button
                    type="button"
                    [attr.data-testid]="'project-restore-' + p.id"
                    [attr.aria-label]="'Restore ' + p.name"
                    [disabled]="deleting() === p.id"
                    (click)="restore(p)"
                  >
                    Restore
                  </button>
                } @else {
                  <!-- Deleting a project now archives it; the delete anchor is kept on the same control. -->
                  <span [attr.data-testid]="'project-delete-' + p.id">
                    <button
                      type="button"
                      [attr.data-testid]="'project-archive-' + p.id"
                      [attr.aria-label]="'Archive ' + p.name"
                      [disabled]="deleting() === p.id"
                      (click)="archive(p)"
                    >
                      Archive
                    </button>
                  </span>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class Projects {
  private readonly service = inject(ProjectService);
  private readonly clientService = inject(ClientService);
  private readonly tagService = inject(TagService);

  protected readonly projects = signal<Project[]>([]);
  protected readonly clients = signal<Client[]>([]);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly deleting = signal<number | null>(null);
  protected readonly deleteError = signal<string | null>(null);
  protected readonly showArchived = signal(false);
  protected readonly tags = signal<Tag[]>([]);
  protected readonly tagFilter = signal<number | null>(null);
  protected readonly statusFilter = signal<ProjectStatus | null>(null);
  protected readonly statuses = PROJECT_STATUSES;

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(255)]],
    code: ['', [Validators.required, Validators.pattern(/^\s*[A-Za-z0-9-]{1,20}\s*$/)]],
    clientId: ['', Validators.required],
    status: ['ACTIVE' as ProjectStatus, Validators.required],
  });

  constructor() {
    this.load();
    this.clientService.list().subscribe((list) => this.clients.set(list));
    this.tagService.list().subscribe((list) => this.tags.set(list));
  }

  protected showError(field: 'name' | 'code' | 'clientId'): boolean {
    const control = this.form.controls[field];
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    if (this.form.invalid || !this.form.getRawValue().name.trim()) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, code, clientId, status } = this.form.getRawValue();
    this.saving.set(true);
    this.saveError.set(null);
    const project = { name: name.trim(), code: code.trim().toUpperCase(), clientId: Number(clientId), status };
    this.service.create(project).subscribe({
      next: (created) => {
        // A new project carries no tags, so it only belongs on a list not filtered by tag, and only
        // when its status matches any status filter.
        if (this.tagFilter() === null && this.matchesStatusFilter(created)) {
          this.projects.update((list) => [...list, created]);
        }
        this.form.reset();
        this.saving.set(false);
      },
      error: (err: HttpErrorResponse) => {
        if (err.status === 409) {
          // The server owns uniqueness; flag the code field until it is changed.
          const codeControl = this.form.controls.code;
          codeControl.setErrors({ taken: true });
          codeControl.markAsTouched();
        } else {
          this.saveError.set('Could not save the job. Please try again.');
        }
        this.saving.set(false);
      },
    });
  }

  protected toggleArchived(): void {
    this.showArchived.update((show) => !show);
    this.load();
  }

  protected filterByTag(value: string): void {
    this.tagFilter.set(value === '' ? null : Number(value));
    this.load();
  }

  protected filterByStatus(value: string): void {
    this.statusFilter.set(value === '' ? null : (value as ProjectStatus));
    this.load();
  }

  protected changeStatus(project: Project, status: string): void {
    this.deleting.set(project.id);
    this.deleteError.set(null);
    this.service.changeStatus(project.id, status as ProjectStatus).subscribe({
      next: (changed) => {
        // A project moved off the filtered status drops off the list.
        this.projects.update((list) =>
          this.matchesStatusFilter(changed)
            ? list.map((p) => (p.id === changed.id ? changed : p))
            : list.filter((p) => p.id !== changed.id),
        );
        this.deleting.set(null);
      },
      error: () => {
        this.deleteError.set(`Could not change the status of ${project.name}. Please try again.`);
        this.deleting.set(null);
      },
    });
  }

  protected archive(project: Project): void {
    this.deleting.set(project.id);
    this.deleteError.set(null);
    this.service.archive(project.id).subscribe({
      next: (archived) => {
        this.projects.update((list) =>
          this.showArchived()
            ? list.map((p) => (p.id === archived.id ? archived : p))
            : list.filter((p) => p.id !== archived.id),
        );
        this.deleting.set(null);
      },
      error: () => {
        this.deleteError.set(`Could not archive ${project.name}. Please try again.`);
        this.deleting.set(null);
      },
    });
  }

  protected restore(project: Project): void {
    this.deleting.set(project.id);
    this.deleteError.set(null);
    this.service.restore(project.id).subscribe({
      next: (restored) => {
        this.projects.update((list) => list.map((p) => (p.id === restored.id ? restored : p)));
        this.deleting.set(null);
      },
      error: () => {
        this.deleteError.set(`Could not restore ${project.name}. Please try again.`);
        this.deleting.set(null);
      },
    });
  }

  private matchesStatusFilter(project: Project): boolean {
    const status = this.statusFilter();
    return status === null || project.status === status;
  }

  private load(): void {
    this.service
      .list(this.showArchived(), this.tagFilter(), this.statusFilter())
      .subscribe((list) => this.projects.set(list));
  }
}
