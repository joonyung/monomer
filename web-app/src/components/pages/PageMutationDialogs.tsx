import { useState } from "react";
import {
  ArrowDown,
  ArrowUp,
  FolderPlus,
  MoreHorizontal,
  Move,
  Pencil,
  Plus,
  Trash2,
} from "lucide-react";
import { PagesApiError, type PageResponse } from "@/api/pages";
import { useCanEditPages } from "@/components/pages/usePagesPermissions";
import { useWorkspaceAccess } from "@/components/auth/WorkspaceAccessProvider";
import { Button } from "@/components/ui/Button";
import { Input, NativeSelect, TextArea } from "@/components/ui/Input";
import { PagesDialog } from "@/components/pages/PagesDialog";
import { usePagesDirectory } from "@/components/pages/PagesDirectoryProvider";
import {
  appendIndexForParent,
  buildPageDestinationOptions,
  currentParentPath,
} from "@/components/pages/pageDialogSelectors";
import { findPlacementLocation } from "@/components/pages/pagesTree";

const ROOT_DESTINATION = "__pages_root__";
const FOLDER_NOT_EMPTY = "Only empty folders can be deleted. Move or remove its pages first.";

type PageActionScreen = "menu" | "new-folder" | "rename" | "move" | "remove";

interface NewFolderButtonProps {
  parentPageId: string | null;
  triggerLabel: string;
  /** "button" for page toolbars; "row" for the last row of the Pages sidebar list. */
  appearance: "button" | "row";
}

export function mutationErrorMessage(error: unknown): string {
  if (error instanceof PagesApiError && error.problem?.code === "REVISION_CONFLICT") {
    return "Pages changed in another session. The latest tree is loaded; review your choices and try again.";
  }
  if (error instanceof PagesApiError) return error.message;
  return error instanceof Error ? error.message : "The Pages change could not be completed.";
}

export function NewFolderButton({
  parentPageId,
  triggerLabel,
  appearance,
}: NewFolderButtonProps) {
  const canEdit = useCanEditPages();
  const { createFolder } = usePagesDirectory();
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!canEdit) return null;

  const onOpenChange = (nextOpen: boolean) => {
    if (submitting) return;
    setOpen(nextOpen);
    if (nextOpen) {
      setTitle("");
      setDescription("");
      setError(null);
      setSubmitting(false);
    }
  };

  const submit = async () => {
    const normalizedTitle = title.trim();
    if (!normalizedTitle || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await createFolder({
        title: normalizedTitle,
        description: description.trim() || null,
        parentPageId,
        index: null,
      });
      setOpen(false);
    } catch (caught) {
      setError(mutationErrorMessage(caught));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <PagesDialog
      open={open}
      pending={submitting}
      onOpenChange={onOpenChange}
      title="New folder"
      description={parentPageId ? "Add a folder inside this Page." : "Add a top-level folder to Pages."}
      testId="new-pages-folder-dialog"
      trigger={
        appearance === "row" ? (
          <button
            type="button"
            aria-label={triggerLabel}
            className="flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left type-control text-foreground-secondary transition-colors hover:bg-surface/60 hover:text-foreground focus-visible:outline focus-visible:outline-2 focus-visible:outline-focus-ring"
          >
            <Plus className="h-3.5 w-3.5 flex-shrink-0" aria-hidden="true" />
            <span>New folder</span>
          </button>
        ) : (
          <Button type="button" variant="secondary" size="sm" aria-label={triggerLabel} title={triggerLabel}>
            <Plus aria-hidden="true" />
            <span>New folder</span>
          </Button>
        )
      }
      footer={
        <>
          <Button variant="ghost" size="sm" onClick={() => setOpen(false)} disabled={submitting}>
            Cancel
          </Button>
          <Button
            pending={submitting}
            variant="primary"
            size="sm"
            onClick={() => void submit()}
            disabled={!title.trim()}
          >
            Create folder
          </Button>
        </>
      }
    >
      <FolderFields
        title={title}
        description={description}
        onTitleChange={setTitle}
        onDescriptionChange={setDescription}
        disabled={submitting}
      />
      <DialogError message={error} />
    </PagesDialog>
  );
}

interface PageActionsButtonProps {
  page: Pick<PageResponse, "pageId" | "kind" | "title" | "displayTitle" | "description">;
  parentPageId: string | null;
  siblingIndex: number;
  siblingCount: number;
}

export function PageActionsButton({
  page,
  parentPageId,
  siblingIndex,
  siblingCount,
}: PageActionsButtonProps) {
  const canEdit = useCanEditPages();
  const workspaceAccess = useWorkspaceAccess();
  const {
    snapshot,
    createFolder,
    updatePage,
    deletePage,
    movePage,
    reorderPage,
  } = usePagesDirectory();
  const [open, setOpen] = useState(false);
  const [screen, setScreen] = useState<PageActionScreen>("menu");
  const [title, setTitle] = useState(page.title ?? "");
  const [description, setDescription] = useState(page.description ?? "");
  const [destination, setDestination] = useState(parentPageId ?? ROOT_DESTINATION);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!canEdit) return null;

  const resetForOpen = () => {
    setScreen("menu");
    setTitle(page.title ?? "");
    setDescription(page.description ?? "");
    setDestination(parentPageId ?? ROOT_DESTINATION);
    setSubmitting(false);
    setError(null);
  };

  const onOpenChange = (nextOpen: boolean) => {
    if (submitting) return;
    setOpen(nextOpen);
    if (nextOpen) resetForOpen();
  };

  const runMutation = async (mutation: () => Promise<unknown>) => {
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await mutation();
      setOpen(false);
    } catch (caught) {
      setError(mutationErrorMessage(caught));
    } finally {
      setSubmitting(false);
    }
  };

  const normalizedTitle = title.trim();
  const selectedParentPageId = destination === ROOT_DESTINATION ? null : destination;
  const pageIsFolder = page.kind === "FOLDER";
  const folderHasPages = pageIsFolder && snapshot !== null
    && (findPlacementLocation(snapshot.placements, page.pageId)?.placement.children.length ?? 0) > 0;
  const deleteLabel = page.kind === "DASHBOARD" ? "Remove from Pages" : page.kind === "DOCUMENT" ? "Delete document" : "Delete folder";
  const destinationOptions = snapshot
    ? buildPageDestinationOptions(snapshot, page.pageId).map((option) => ({
        ...option,
        disabled: option.disabled || !workspaceAccess.hasRole("EXPLORER"),
      }))
    : [];
  const moveChangesParent = selectedParentPageId !== parentPageId;
  const selectedDestination = destinationOptions.find(
    (option) => option.pageId === selectedParentPageId,
  );
  const canEditDestination = Boolean(selectedDestination && !selectedDestination.disabled);

  const dialogTitle = screen === "menu"
    ? `${page.displayTitle} actions`
    : screen === "new-folder"
      ? "New folder"
      : screen === "rename"
        ? "Rename Page"
        : screen === "move"
          ? "Move in Pages"
          : deleteLabel;

  const dialogDescription = screen === "menu"
    ? "Manage this Page's navigation label and placement."
    : screen === "new-folder"
      ? `Add a folder inside ${page.displayTitle}.`
      : screen === "rename"
        ? "This changes the name shown throughout Pages."
        : screen === "move"
          ? "Choose a Folder or move the Page to the top level."
          : page.kind === "DASHBOARD"
            ? "Remove this link. The dashboard and its results will remain available."
            : page.kind === "DOCUMENT"
              ? "This deletes the Page and every immutable document version it owns."
              : folderHasPages
                ? FOLDER_NOT_EMPTY
                : "This deletes the empty folder.";

  const footer = screen === "menu" ? (
    <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>Close</Button>
  ) : (
    <>
      <Button
        variant="ghost"
        size="sm"
        onClick={() => {
          setScreen("menu");
          setError(null);
          setSubmitting(false);
        }}
        disabled={submitting}
      >
        Back
      </Button>
      {screen === "new-folder" && (
        <Button
          pending={submitting}
          variant="primary"
          size="sm"
          disabled={!normalizedTitle}
          onClick={() => void runMutation(() => createFolder({
            title: normalizedTitle,
            description: description.trim() || null,
            parentPageId: page.pageId,
            index: null,
          }))}
        >
          Create folder
        </Button>
      )}
      {screen === "rename" && (
        <Button
          pending={submitting}
          variant="primary"
          size="sm"
          disabled={!normalizedTitle}
          onClick={() => void runMutation(() => updatePage(page.pageId, {
            title: normalizedTitle,
            description: description.trim() || null,
          }))}
        >
          Save
        </Button>
      )}
      {screen === "move" && snapshot && (
        <Button
          pending={submitting}
          variant="primary"
          size="sm"
          disabled={!moveChangesParent || !canEditDestination}
          onClick={() => void runMutation(() => movePage(
            page.pageId,
            selectedParentPageId,
            appendIndexForParent(snapshot, selectedParentPageId, page.pageId),
          ))}
        >
          Move Page
        </Button>
      )}
      {screen === "remove" && (
        <Button
          pending={submitting}
          variant="danger"
          size="sm"
          disabled={folderHasPages || (pageIsFolder && snapshot === null)}
          onClick={() => void runMutation(() => deletePage(page.pageId))}
        >
          {deleteLabel}
        </Button>
      )}
    </>
  );

  return (
    <PagesDialog
      open={open}
      pending={submitting}
      onOpenChange={onOpenChange}
      title={dialogTitle}
      description={dialogDescription}
      testId={`pages-actions-dialog-${page.pageId}`}
      trigger={
        <button
          type="button"
          aria-label={`Actions for ${page.displayTitle}`}
          title={`Actions for ${page.displayTitle}`}
          className="mr-0.5 flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-[4px] text-foreground-secondary opacity-70 transition-colors hover:bg-surface-hover hover:text-foreground focus:opacity-100 focus:outline-none group-hover:opacity-100"
        >
          <MoreHorizontal className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      }
      footer={footer}
    >
      {screen === "menu" && (
        <div className="space-y-1" aria-label={`Actions for ${page.displayTitle}`}>
          {pageIsFolder && (
            <ActionChoice
              icon={FolderPlus}
              label="New folder inside"
              detail="Create a child Folder."
              onClick={() => {
                setTitle("");
                setDescription("");
                setScreen("new-folder");
              }}
            />
          )}
          <ActionChoice
            icon={Pencil}
            label="Rename"
            detail="Change the Pages name."
            onClick={() => setScreen("rename")}
          />
          <ActionChoice
            icon={Move}
            label="Move in Pages"
            detail="Choose a different parent Folder."
            onClick={() => setScreen("move")}
          />
          <ActionChoice
            icon={ArrowUp}
            label="Move up"
            detail="Move one position earlier among siblings."
            disabled={siblingIndex === 0 || submitting}
            onClick={() => void runMutation(() => reorderPage(page.pageId, siblingIndex - 1))}
          />
          <ActionChoice
            icon={ArrowDown}
            label="Move down"
            detail="Move one position later among siblings."
            disabled={siblingIndex >= siblingCount - 1 || submitting}
            onClick={() => void runMutation(() => reorderPage(page.pageId, siblingIndex + 1))}
          />
          <ActionChoice
            icon={Trash2}
            label={deleteLabel}
            detail={page.kind === "DASHBOARD" ? "Keep the dashboard and its results." : page.kind === "DOCUMENT"
                ? "Delete the Page and its versions."
                : folderHasPages
                  ? FOLDER_NOT_EMPTY
                  : "Delete this empty folder."}
            danger
            disabled={folderHasPages || submitting}
            onClick={() => setScreen("remove")}
          />
        </div>
      )}

      {screen === "new-folder" && (
        <FolderFields
          title={title}
          description={description}
          onTitleChange={setTitle}
          onDescriptionChange={setDescription}
          disabled={submitting}
        />
      )}

      {screen === "rename" && (
        <NavigationMetadataFields
          title={title}
          description={description}
          onTitleChange={setTitle}
          onDescriptionChange={setDescription}
          disabled={submitting}
        />
      )}

      {screen === "move" && snapshot && (
        <DestinationField
          options={destinationOptions}
          value={destination}
          currentPath={currentParentPath(snapshot, page.pageId)}
          onChange={setDestination}
          disabled={submitting}
          helperText={!canEditDestination
            ? "Choose a destination covered by your Edit Pages permission."
            : page.kind === "FOLDER"
              ? "A Folder cannot be moved into itself or one of its descendants."
              : "Choose the Folder where this Page should appear."}
        />
      )}

      {screen === "remove" && (
        <div className="rounded-[4px] border border-error/20 bg-error/10 p-4 type-body text-foreground">
          {page.kind === "DASHBOARD" ? <p>{page.displayTitle} will be removed from Pages. The dashboard and its results will remain available.</p> : page.kind === "DOCUMENT" ? (
            <p>
              <span className="text-heading">{page.displayTitle}</span> and every immutable document
              version it owns will be deleted.
            </p>
          ) : folderHasPages ? (
            <p>{FOLDER_NOT_EMPTY}</p>
          ) : (
            <p>
              <span className="text-heading">{page.displayTitle}</span> will be deleted.
            </p>
          )}
        </div>
      )}

      <DialogError message={error} />
    </PagesDialog>
  );
}

export interface DestinationFieldProps {
  options: ReturnType<typeof buildPageDestinationOptions>;
  value: string;
  currentPath: string;
  onChange: (value: string) => void;
  disabled: boolean;
  helperText: string;
}

export function DestinationField({
  options,
  value,
  currentPath,
  onChange,
  disabled,
  helperText,
}: DestinationFieldProps) {
  return (
    <div>
      <p className="mb-4 rounded-[4px] border border-border bg-surface px-3 py-2 type-meta text-foreground-secondary">
        Current path: <span className="text-foreground">{currentPath}</span>
      </p>
      <NativeSelect
        id="pages-destination"
        label="Destination"
        helperText={helperText}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
      >
        {options.map((option) => (
          <option
            key={option.pageId ?? ROOT_DESTINATION}
            value={option.pageId ?? ROOT_DESTINATION}
            disabled={option.disabled}
          >
            {option.label}{option.disabled ? " (unavailable)" : ""}
          </option>
        ))}
      </NativeSelect>
    </div>
  );
}

function FolderFields({
  title,
  description,
  onTitleChange,
  onDescriptionChange,
  disabled,
}: {
  title: string;
  description: string;
  onTitleChange: (value: string) => void;
  onDescriptionChange: (value: string) => void;
  disabled: boolean;
}) {
  return (
    <NavigationFields
      titleLabel="Name"
      title={title}
      description={description}
      onTitleChange={onTitleChange}
      onDescriptionChange={onDescriptionChange}
      disabled={disabled}
      titleRequired
      helperText="Folder names organize navigation only; they do not change resource permissions."
    />
  );
}

function NavigationMetadataFields({
  title,
  description,
  onTitleChange,
  onDescriptionChange,
  disabled,
}: {
  title: string;
  description: string;
  onTitleChange: (value: string) => void;
  onDescriptionChange: (value: string) => void;
  disabled: boolean;
}) {
  return (
    <NavigationFields
      titleLabel="Name"
      title={title}
      description={description}
      onTitleChange={onTitleChange}
      onDescriptionChange={onDescriptionChange}
      disabled={disabled}
      titleRequired
      helperText="This is the shared navigation name for the Page."
    />
  );
}

function NavigationFields({
  titleLabel,
  title,
  description,
  onTitleChange,
  onDescriptionChange,
  disabled,
  titleRequired,
  helperText,
}: {
  titleLabel: string;
  title: string;
  description: string;
  onTitleChange: (value: string) => void;
  onDescriptionChange: (value: string) => void;
  disabled: boolean;
  titleRequired: boolean;
  helperText: string;
}) {
  return (
    <div className="space-y-4">
      <Input
        id="pages-navigation-title"
        label={titleLabel}
        helperText={helperText}
        value={title}
        onChange={(event) => onTitleChange(event.target.value)}
        disabled={disabled}
        required={titleRequired}
        autoFocus
      />
      <TextArea
        id="pages-navigation-description"
        label="Description (optional)"
        value={description}
        onChange={(event) => onDescriptionChange(event.target.value)}
        disabled={disabled}
        rows={3}
      />
    </div>
  );
}

function ActionChoice({
  icon: Icon,
  label,
  detail,
  onClick,
  disabled = false,
  danger = false,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  detail: string;
  onClick: () => void;
  disabled?: boolean;
  danger?: boolean;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`flex w-full items-start gap-3 rounded-[4px] px-3 py-2.5 text-left transition-colors focus:outline-none disabled:cursor-not-allowed disabled:text-foreground-secondary ${
        danger ? "text-error enabled:hover:bg-error/10" : "text-foreground enabled:hover:bg-surface-hover"
      }`}
    >
      <Icon className="mt-0.5 h-4 w-4 flex-shrink-0" aria-hidden="true" />
      <span className="min-w-0 flex-1">
        <span className="block type-body">{label}</span>
        <span className="mt-0.5 block type-meta text-foreground-secondary">{detail}</span>
      </span>
    </button>
  );
}

function DialogError({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <p className="mt-4 rounded-[4px] border border-error/20 bg-error/10 px-3 py-2 type-body text-error" role="alert">
      {message}
    </p>
  );
}
