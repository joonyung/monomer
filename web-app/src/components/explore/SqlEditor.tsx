import type { ConnectorType } from "@/lib/types/connector";
import { useEffect, useLayoutEffect, useRef, useState } from "react";

interface SqlEditorProps {
  value: string;
  onChange: (value: string) => void;
  onRun: () => void;
  runDisabled?: boolean;
  placeholder?: string;
  readOnly?: boolean;
  ariaLabel?: string;
}

const SQL_EDITOR_THEME = "monomer-sql-light";
export function sqlPlaceholder(type: ConnectorType | undefined): string {
  if (type === "BIGQUERY") return "SELECT * FROM `project.dataset.table` LIMIT 100";
  if (type === "DATABRICKS") return "SELECT * FROM `catalog`.`schema`.`table` LIMIT 100";
  if (type === "SNOWFLAKE") return 'SELECT * FROM "database"."schema"."table" LIMIT 100';
  if (type === "TRINO") return 'SELECT * FROM "catalog"."schema"."table" LIMIT 100';
  if (type === "POSTGRESQL" || type === "REDSHIFT") return 'SELECT * FROM "schema"."table" LIMIT 100';
  return "Select a connector, then write SQL";
}

export function SqlEditor({
  value,
  onChange,
  onRun,
  runDisabled = false,
  placeholder = sqlPlaceholder(undefined),
  readOnly = false,
  ariaLabel = "SQL",
}: SqlEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const editorRef = useRef<import("monaco-editor").editor.IStandaloneCodeEditor | null>(null);
  const valueRef = useRef(value);
  const onChangeRef = useRef(onChange);
  const onRunRef = useRef(onRun);
  const runDisabledRef = useRef(runDisabled);
  const applyingExternalValueRef = useRef(false);
  const [loadFailed, setLoadFailed] = useState(false);

  valueRef.current = value;
  onChangeRef.current = onChange;
  onRunRef.current = onRun;
  runDisabledRef.current = runDisabled;

  useEffect(() => {
    let cancelled = false;

    void import("monaco-editor/esm/vs/editor/editor.api")
      .then(async (monaco) => {
        await import("monaco-editor/esm/vs/basic-languages/sql/sql.contribution");
        // editor.api ships without the contribution that renders the `placeholder` option.
        await import("monaco-editor/esm/vs/editor/contrib/placeholderText/browser/placeholderText.contribution");
        if (cancelled || !containerRef.current) return;

        monaco.editor.defineTheme(SQL_EDITOR_THEME, {
          base: "vs",
          inherit: true,
          rules: [
            { token: "keyword", foreground: "2466C3" },
            { token: "keyword.sql", foreground: "2466C3" },
            { token: "predefined", foreground: "007A5F" },
            { token: "predefined.sql", foreground: "007A5F" },
            { token: "string", foreground: "007044" },
            { token: "string.sql", foreground: "007044" },
            { token: "number", foreground: "806A1F" },
            { token: "number.sql", foreground: "806A1F" },
            { token: "comment", foreground: "6C7074", fontStyle: "italic" },
            { token: "comment.sql", foreground: "6C7074", fontStyle: "italic" },
            { token: "operator", foreground: "42464A" },
            { token: "operator.sql", foreground: "42464A" },
            { token: "delimiter", foreground: "6C7074" },
            { token: "delimiter.sql", foreground: "6C7074" },
            { token: "identifier.quote", foreground: "4D4691" },
            { token: "identifier.quote.sql", foreground: "4D4691" },
          ],
          colors: {
            "editor.background": "#FDFDFE",
            "editor.foreground": "#1E2226",
            "editor.placeholder.foreground": "#6C7074",
            "editorCursor.foreground": "#C1742D",
            "editor.selectionBackground": "#ECEFF3",
            "editor.inactiveSelectionBackground": "#F4F6F9",
            "editorLineNumber.foreground": "#6C7074AA",
            "editorLineNumber.activeForeground": "#42464A",
            "editorGutter.background": "#FDFDFE",
            "editor.lineHighlightBackground": "#FDFDFE",
            "editorIndentGuide.background1": "#DFE3E7",
            "scrollbarSlider.background": "#6C707433",
            "scrollbarSlider.hoverBackground": "#6C707466",
            "scrollbarSlider.activeBackground": "#6C707499",
          },
        });

        const editor = monaco.editor.create(containerRef.current, {
          value: valueRef.current,
          language: "sql",
          theme: SQL_EDITOR_THEME,
          ariaLabel,
          placeholder,
          readOnly,
          automaticLayout: true,
          accessibilitySupport: "on",
          fontFamily: "var(--font-mono), SFMono-Regular, monospace",
          fontSize: 13,
          lineHeight: 24,
          fontLigatures: false,
          cursorWidth: 2,
          minimap: { enabled: false },
          glyphMargin: false,
          folding: false,
          lineDecorationsWidth: 12,
          lineNumbersMinChars: 3,
          overviewRulerLanes: 0,
          hideCursorInOverviewRuler: true,
          renderLineHighlight: "none",
          renderWhitespace: "none",
          renderValidationDecorations: "off",
          scrollBeyondLastLine: false,
          smoothScrolling: false,
          wordWrap: "on",
          wrappingIndent: "indent",
          tabSize: 2,
          insertSpaces: true,
          detectIndentation: false,
          quickSuggestions: false,
          suggestOnTriggerCharacters: false,
          selectionHighlight: false,
          occurrencesHighlight: "off",
          bracketPairColorization: { enabled: false },
          guides: { indentation: false, bracketPairs: false },
          stickyScroll: { enabled: false },
          links: false,
          padding: { top: 20, bottom: 20 },
          scrollbar: {
            verticalScrollbarSize: 8,
            horizontalScrollbarSize: 8,
            useShadows: false,
            alwaysConsumeMouseWheel: false,
          },
        });

        editorRef.current = editor;
        editor.onDidChangeModelContent(() => {
          if (applyingExternalValueRef.current) return;
          const nextValue = editor.getValue();
          valueRef.current = nextValue;
          onChangeRef.current(nextValue);
        });
        if (!readOnly) {
          editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => {
            if (!runDisabledRef.current) onRunRef.current();
          });
        }
      })
      .catch(() => {
        if (!cancelled) setLoadFailed(true);
      });

    return () => {
      cancelled = true;
      editorRef.current?.dispose();
      editorRef.current = null;
    };
  }, [ariaLabel, placeholder, readOnly]);

  useLayoutEffect(() => {
    const editor = editorRef.current;
    if (!editor || editor.getValue() === value) return;
    applyingExternalValueRef.current = true;
    editor.setValue(value);
    applyingExternalValueRef.current = false;
  }, [value]);

  return (
    <div className="relative h-full min-h-[246px] w-full overflow-hidden bg-background" data-testid="sql-editor">
      <div ref={containerRef} className={loadFailed ? "hidden" : "h-full w-full"} />
      {loadFailed ? (
        <label className="block h-full w-full">
          <span className="sr-only">{ariaLabel}</span>
          <textarea
            value={value}
            onChange={(event) => onChange(event.target.value)}
            readOnly={readOnly}
            onKeyDown={(event) => {
              if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
                event.preventDefault();
                if (!runDisabled) onRun();
              }
            }}
            spellCheck={false}
            placeholder={placeholder}
            className="h-full w-full resize-none bg-background px-5 py-5 font-mono text-[13px] leading-6 text-foreground caret-[var(--focus-ring)] placeholder:text-foreground-secondary focus:outline-none focus-visible:!outline-none"
          />
        </label>
      ) : null}
    </div>
  );
}
