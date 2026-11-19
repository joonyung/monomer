import { useOptionalWorkspaceRouteContext } from "@/components/auth/TenantRouteContext";
import { useMemo, useRef, useState } from "react";
import { useRouter } from "@/lib/navigation";
import { FilePlus2, Replace } from "lucide-react";
import {
  pageContentAPI,
  pageContentLimits,
  type PageContentFormat,
  type PageContentPreparedUpload,
  type PageContentUploadProgress,
  type PageContentVersionResponse,
} from "@/api/page-content";
import { PagesApiError } from "@/api/pages";
import { useCanEditPages } from "@/components/pages/usePagesPermissions";
import { Button } from "@/components/ui/Button";
import { FIELD_LABEL_CLASS, Input, NativeSelect, TextArea } from "@/components/ui/Input";
import { PagesDialog } from "@/components/pages/PagesDialog";
import { usePagesDirectory } from "@/components/pages/PagesDirectoryProvider";
import { webPaths } from "@/lib/web-paths";

const FORMAT_OPTIONS: Array<{ value: PageContentFormat; label: string; accept: string }> = [
  { value: "MARKDOWN", label: "Markdown", accept: ".md,.markdown,text/markdown,text/plain" },
  { value: "NOTEBOOK", label: "Notebook", accept: ".ipynb,application/json" },
  { value: "HTML", label: "HTML", accept: ".html,.htm,text/html" },
];

function formatForFilename(filename: string): PageContentFormat | null {
  const extension = filename.split(".").pop()?.toLowerCase();
  if (extension === "md" || extension === "markdown") return "MARKDOWN";
  if (extension === "ipynb") return "NOTEBOOK";
  if (extension === "html" || extension === "htm") return "HTML";
  return null;
}

function titleForFilename(filename: string): string {
  const trimmed = filename.trim();
  const dot = trimmed.lastIndexOf(".");
  return (dot > 0 ? trimmed.slice(0, dot) : trimmed).trim();
}

function requestId(): string {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `page-content-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

async function sha256File(file: File): Promise<string> {
  if (!globalThis.crypto?.subtle) {
    throw new PagesApiError("This browser cannot verify document content.", 0, null, null);
  }
  const digest = await globalThis.crypto.subtle.digest("SHA-256", await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function errorMessage(error: unknown): string {
  if (error instanceof PagesApiError) {
    if (error.problem?.code === "REVISION_CONFLICT") {
      return "Pages changed in another session. Your uploaded file is retained; review and try again.";
    }
    return error.message;
  }
  return error instanceof Error ? error.message : "The document could not be saved.";
}

function validateFile(file: File | null): string | null {
  if (!file) return "Choose a file.";
  if (file.size <= 0) return "Choose a file that is not empty.";
  if (file.size > pageContentLimits.maximumSourceBytes) return "Files can be up to 20 MiB.";
  return null;
}

function DocumentFields({
  title,
  description,
  format,
  file,
  disabled,
  titleRequired,
  onTitleChange,
  onDescriptionChange,
  onFormatChange,
  onFileChange,
}: {
  title: string;
  description: string;
  format: PageContentFormat;
  file: File | null;
  disabled: boolean;
  titleRequired: boolean;
  onTitleChange: (value: string) => void;
  onDescriptionChange: (value: string) => void;
  onFormatChange: (value: PageContentFormat) => void;
  onFileChange: (file: File | null) => void;
}) {
  const workspaceContext = useOptionalWorkspaceRouteContext();
  const selectedFormat = FORMAT_OPTIONS.find((option) => option.value === format) ?? FORMAT_OPTIONS[0];
  return (
    <div className="space-y-4">
      {workspaceContext?.workspace.publicPages && <p role="status" className="border border-border bg-surface p-3 type-body text-foreground-secondary">This workspace has Public Pages enabled. This document’s source content and this replacement will be available to anyone with a link.</p>}
      {titleRequired && (
        <Input
          id="document-title"
          label="Title"
          value={title}
          maxLength={200}
          required
          disabled={disabled}
          onChange={(event) => onTitleChange(event.target.value)}
        />
      )}
      {titleRequired && (
        <div>
          <label htmlFor="document-description" className={FIELD_LABEL_CLASS}>
            Description <span className="text-foreground-secondary">(optional)</span>
          </label>
          <TextArea
            id="document-description"
            value={description}
            maxLength={1_000}
            rows={3}
            disabled={disabled}
            onChange={(event) => onDescriptionChange(event.target.value)}
          />
        </div>
      )}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-[180px_1fr]">
        <NativeSelect
          id="document-format"
          label="Format"
          value={format}
          disabled={disabled || file !== null}
          onChange={(event) => onFormatChange(event.target.value as PageContentFormat)}
        >
          {FORMAT_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </NativeSelect>
        <div>
          <label htmlFor="document-file" className={FIELD_LABEL_CLASS}>File</label>
          <input
            id="document-file"
            type="file"
            accept={selectedFormat.accept}
            disabled={disabled}
            onChange={(event) => onFileChange(event.target.files?.[0] ?? null)}
            className="block w-full rounded-[4px] border border-border bg-background type-body text-foreground file:mr-3 file:border-0 file:border-r file:border-border file:bg-surface file:px-3 file:py-2.5 file:type-body file:text-foreground hover:file:bg-surface focus:outline-none disabled:cursor-not-allowed disabled:bg-surface disabled:text-foreground-secondary disabled:file:text-foreground-secondary"
          />
          <p className="mt-1.5 type-meta text-foreground-secondary">
            One file, up to 20 MiB. Files are rendered without executing notebook code or scripts.
          </p>
          {file && <p className="mt-1 type-meta tabular-nums text-foreground-secondary">{file.size.toLocaleString("en-US")} bytes</p>}
        </div>
      </div>
    </div>
  );
}

function UploadProgress({ progress }: { progress: PageContentUploadProgress | null }) {
  if (!progress) return null;
  const percentage = Math.min(100, Math.round((progress.uploadedBytes / progress.totalBytes) * 100));
  return (
    <div aria-live="polite">
      <div className="mb-1 flex items-center justify-between type-meta text-foreground-secondary">
        <span>Uploading file</span>
        <span className="tabular-nums">{percentage}%</span>
      </div>
      <div
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percentage}
        className="h-1.5 overflow-hidden rounded-[4px] bg-surface"
      >
        <div className="h-full bg-accent transition-[width]" style={{ width: `${percentage}%` }} />
      </div>
    </div>
  );
}

export function NewDocumentButton({
  parentPageId,
  compact,
}: {
  parentPageId: string | null;
  compact: boolean;
}) {
  const router = useRouter();
  const canEdit = useCanEditPages();
  const { workspaceId, createDocument, isFixture } = usePagesDirectory();
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [format, setFormat] = useState<PageContentFormat>("MARKDOWN");
  const [file, setFile] = useState<File | null>(null);
  const [preparedUpload, setPreparedUpload] = useState<PageContentPreparedUpload | null>(null);
  const [completedUploadRef, setCompletedUploadRef] = useState<string | null>(null);
  const [progress, setProgress] = useState<PageContentUploadProgress | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const titleWasEdited = useRef(false);

  if (!canEdit) return null;

  const resetUploadedFile = () => {
    void preparedUpload?.abort();
    setPreparedUpload(null);
    setCompletedUploadRef(null);
    setProgress(null);
    setError(null);
  };
  const reset = (abortPreparedUpload: boolean) => {
    if (abortPreparedUpload) void preparedUpload?.abort();
    setTitle("");
    setDescription("");
    setFormat("MARKDOWN");
    setFile(null);
    setPreparedUpload(null);
    setCompletedUploadRef(null);
    setProgress(null);
    setError(null);
    setSubmitting(false);
    titleWasEdited.current = false;
  };
  const onOpenChange = (nextOpen: boolean) => {
    if (submitting) return;
    setOpen(nextOpen);
    if (!nextOpen) reset(true);
  };
  const submit = async () => {
    const normalizedTitle = title.trim();
    const fileError = validateFile(file);
    if (!normalizedTitle || fileError || !file || !workspaceId) {
      setError(fileError ?? (!normalizedTitle ? "Enter a title." : "Select a workspace."));
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      let nextUploadRef = completedUploadRef;
      if (!nextUploadRef) {
        if (isFixture) {
          nextUploadRef = `fixture-upload-${requestId()}`;
        } else {
          const nextPreparedUpload = preparedUpload
            ?? await pageContentAPI.prepareUpload(workspaceId, format, file, undefined, setProgress);
          setPreparedUpload(nextPreparedUpload);
          nextUploadRef = await nextPreparedUpload.complete();
        }
        setCompletedUploadRef(nextUploadRef);
      }
      const response = await createDocument({
        title: normalizedTitle,
        description: description.trim() || null,
        uploadRef: nextUploadRef,
        parentPageId,
        index: null,
      });
      const pageId = response.change?.pageId;
      setOpen(false);
      reset(false);
      if (pageId && !isFixture) router.push(webPaths.page(workspaceId, pageId));
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <PagesDialog
      pending={submitting}
      open={open}
      onOpenChange={onOpenChange}
      title="Upload document"
      description="Upload one immutable source file and place the Document Page here."
      testId="new-pages-document-dialog"
      trigger={(
        <Button variant={compact ? "ghost" : "secondary"} size="sm">
          <FilePlus2 aria-hidden="true" />
          Upload document
        </Button>
      )}
      footer={(
        <>
          <Button variant="ghost" size="sm" disabled={submitting} onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button
            pending={submitting}
            variant="primary"
            size="sm"
            disabled={!title.trim() || !file}
            onClick={() => void submit()}
          >
            {completedUploadRef
              ? "Retry publishing"
              : preparedUpload
                ? "Retry completion"
                : "Create document"}
          </Button>
        </>
      )}
    >
      <DocumentFields
        title={title}
        description={description}
        format={format}
        file={file}
        disabled={submitting || preparedUpload !== null || completedUploadRef !== null}
        titleRequired
        onTitleChange={(value) => {
          titleWasEdited.current = true;
          setTitle(value);
        }}
        onDescriptionChange={setDescription}
        onFormatChange={(value) => {
          resetUploadedFile();
          setFormat(value);
        }}
        onFileChange={(nextFile) => {
          resetUploadedFile();
          setFile(nextFile);
          const inferredFormat = nextFile ? formatForFilename(nextFile.name) : null;
          if (inferredFormat) setFormat(inferredFormat);
          if (nextFile && !titleWasEdited.current) setTitle(titleForFilename(nextFile.name));
        }}
      />
      <div className="mt-4"><UploadProgress progress={progress} /></div>
      {completedUploadRef && error && (
        <p className="mt-3 rounded-[4px] border border-label/20 bg-surface px-3 py-2 type-meta text-foreground">
          The file is verified. Retry publishing without selecting it again.
        </p>
      )}
      {preparedUpload && !completedUploadRef && error && (
        <p className="mt-3 rounded-[4px] border border-label/20 bg-surface px-3 py-2 type-meta text-foreground">
          The file bytes are uploaded. Retry completion without selecting the file again, or cancel to choose another file.
        </p>
      )}
      {error && <p role="alert" className="mt-3 type-body text-error">{error}</p>}
    </PagesDialog>
  );
}

export function ReplaceDocumentContentButton({
  pageId,
  currentFormat,
  nextFixtureVersionNo,
  onReplaced,
}: {
  pageId: string;
  currentFormat: PageContentFormat;
  nextFixtureVersionNo: number;
  onReplaced: (version: PageContentVersionResponse, sourceFile: File | null) => void;
}) {
  const canEdit = useCanEditPages();
  const { workspaceId, isFixture } = usePagesDirectory();
  const [open, setOpen] = useState(false);
  const [format, setFormat] = useState<PageContentFormat>(currentFormat);
  const [file, setFile] = useState<File | null>(null);
  const [preparedUpload, setPreparedUpload] = useState<PageContentPreparedUpload | null>(null);
  const [completedUploadRef, setCompletedUploadRef] = useState<string | null>(null);
  const [mutationRequestId, setMutationRequestId] = useState<string | null>(null);
  const [progress, setProgress] = useState<PageContentUploadProgress | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const formatLabel = useMemo(
    () => FORMAT_OPTIONS.find((option) => option.value === format)?.label ?? format,
    [format],
  );

  if (!canEdit) return null;

  const resetUploadedFile = () => {
    void preparedUpload?.abort();
    setPreparedUpload(null);
    setCompletedUploadRef(null);
    setMutationRequestId(null);
    setProgress(null);
    setError(null);
  };
  const reset = (abortPreparedUpload: boolean) => {
    if (abortPreparedUpload) void preparedUpload?.abort();
    setFormat(currentFormat);
    setFile(null);
    setPreparedUpload(null);
    setCompletedUploadRef(null);
    setMutationRequestId(null);
    setProgress(null);
    setError(null);
    setSubmitting(false);
  };
  const onOpenChange = (nextOpen: boolean) => {
    if (submitting) return;
    setOpen(nextOpen);
    if (nextOpen) setFormat(currentFormat);
    else reset(true);
  };
  const submit = async () => {
    const fileError = validateFile(file);
    if (fileError || !file || !workspaceId) {
      setError(fileError ?? "Select a workspace.");
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      let nextUploadRef = completedUploadRef;
      if (!nextUploadRef) {
        if (isFixture) {
          nextUploadRef = `fixture-replacement-${requestId()}`;
        } else {
          const nextPreparedUpload = preparedUpload
            ?? await pageContentAPI.prepareUpload(workspaceId, format, file, undefined, setProgress);
          setPreparedUpload(nextPreparedUpload);
          nextUploadRef = await nextPreparedUpload.complete();
        }
        setCompletedUploadRef(nextUploadRef);
      }
      const nextRequestId = mutationRequestId ?? requestId();
      setMutationRequestId(nextRequestId);
      if (isFixture) {
        onReplaced({
          pageId,
          versionNo: nextFixtureVersionNo,
          format,
          contentHash: await sha256File(file),
          contentSizeBytes: file.size,
          finalizedBy: { kind: "USER", id: "pages-fixture-user", displayName: "Pages fixture user", agentApp: null },
          finalizedAt: new Date().toISOString(),
        }, file);
      } else {
        const response = await pageContentAPI.replaceContent(
          workspaceId,
          pageId,
          nextUploadRef,
          nextRequestId,
        );
        onReplaced(response.version, null);
      }
      setOpen(false);
      reset(false);
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <PagesDialog
      pending={submitting}
      open={open}
      onOpenChange={onOpenChange}
      title="Replace content"
      description={`Add a new immutable ${formatLabel} version. Earlier versions remain available.`}
      testId={`replace-document-content-dialog-${pageId}`}
      trigger={(
        <Button variant="secondary" size="sm">
          <Replace aria-hidden="true" />
          Replace content
        </Button>
      )}
      footer={(
        <>
          <Button variant="ghost" size="sm" disabled={submitting} onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button variant="primary" size="sm" pending={submitting} disabled={!file} onClick={() => void submit()}>
            {completedUploadRef
              ? "Retry replacing"
              : preparedUpload
                ? "Retry completion"
                : "Add version"}
          </Button>
        </>
      )}
    >
      <DocumentFields
        title=""
        description=""
        format={format}
        file={file}
        disabled={submitting || preparedUpload !== null || completedUploadRef !== null}
        titleRequired={false}
        onTitleChange={() => undefined}
        onDescriptionChange={() => undefined}
        onFormatChange={(value) => {
          resetUploadedFile();
          setFormat(value);
        }}
        onFileChange={(nextFile) => {
          resetUploadedFile();
          setFile(nextFile);
          const inferredFormat = nextFile ? formatForFilename(nextFile.name) : null;
          if (inferredFormat) setFormat(inferredFormat);
        }}
      />
      <div className="mt-4"><UploadProgress progress={progress} /></div>
      {completedUploadRef && error && (
        <p className="mt-3 rounded-[4px] border border-label/20 bg-surface px-3 py-2 type-meta text-foreground">
          The file is verified. Retry adding the version without selecting it again.
        </p>
      )}
      {preparedUpload && !completedUploadRef && error && (
        <p className="mt-3 rounded-[4px] border border-label/20 bg-surface px-3 py-2 type-meta text-foreground">
          The file bytes are uploaded. Retry completion without selecting the file again, or cancel to choose another file.
        </p>
      )}
      {error && <p role="alert" className="mt-3 type-body text-error">{error}</p>}
    </PagesDialog>
  );
}
