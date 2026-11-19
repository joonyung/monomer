import { useState } from "react";
import { PagesDialog } from "@/components/pages/PagesDialog";
import { Database, Download, LayoutDashboard, Plus, Search, Settings2, Table2, Trash2 } from "lucide-react";
import { Dialog, type DialogSize } from "@/components/ui/Dialog";
import { StateBlock } from "@/components/ui/StateBlock";
import { AgentAvatar, AgentAvatarCaption } from "@/components/ui/AgentMarker";
import { MonomerLogo } from "@/components/brand/MonomerLogo";
import { Button } from "@/components/ui/Button";
import { FIELD_LABEL_CLASS, Input, NativeSelect, TextArea } from "@/components/ui/Input";
import { Select } from "@/components/ui/Select";
import { notFound } from "@/lib/navigation";
import { CATEGORICAL_CHART_TOKENS } from "@/lib/chart-color-scale";

const SOURCES = [
  { name: "Northstar Warehouse", meta: "BigQuery · updated 4m ago", status: "Connected", dot: "bg-success", selected: true },
  { name: "Product events", meta: "PostgreSQL · updated 28m ago", status: "Connected", dot: "bg-success", selected: false },
  { name: "Finance mart", meta: "Snowflake · paused 1h ago", status: "Paused", dot: "bg-foreground-secondary", selected: false },
];

const BUTTON_VARIANTS = ["primary", "secondary", "ghost", "danger"] as const;

const TYPE_ROLES = [
  ["type-display", "Display · 32 / 300", "Revenue overview"],
  ["type-section", "Section · 22 / 300", "Connected sources"],
  ["type-item", "Item · 15 / 400", "Northstar Warehouse"],
  ["type-body", "Body · 14 / 400", "Choose the schemas this connector reads."],
  ["type-control", "Control · 13 / 400", "Refresh schedule"],
  ["type-meta", "Metadata · 12 / 400", "Updated 4m ago"],
  ["type-status", "Status · 11 / 400", "Running"],
  ["type-group", "Group · 10 / 500", "Sources"],
  ["type-mono", "Mono · 12 / 400", "orders.created_at"],
] as const;

const DIALOG_SIZES: { size: DialogSize; label: string }[] = [
  { size: "sm", label: "Small · 480" },
  { size: "md", label: "Medium · 640" },
  { size: "lg", label: "Large · 840" },
  { size: "xl", label: "Wide · 1040" },
];

function SectionHeading({ title }: { title: string }) {
  return <h2 className="text-[15px] font-normal text-heading">{title}</h2>;
}

export default function DesignSystemFixturePage() {
  const [dialogOpen, setDialogOpen] = useState(false);
  const [refresh, setRefresh] = useState("hourly");
  const [shellSize, setShellSize] = useState<DialogSize | null>(null);
  const [saving, setSaving] = useState(false);
  if (!import.meta.env.DEV) notFound();

  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground md:flex-row">
      <aside className="w-full flex-none border-b border-border bg-background px-3 py-4 md:w-60 md:border-b-0 md:border-r">
        <MonomerLogo className="h-6 w-auto px-2" />
        <p className="mt-5 px-2 text-[10px] font-medium uppercase tracking-[0.08em] text-label md:mt-7">Workspace</p>
        <nav className="mt-2 grid grid-cols-3 gap-1 md:block md:space-y-1" aria-label="Fixture navigation">
          <a className="flex items-center gap-2 rounded-[4px] px-2 py-2 text-[13px] text-foreground-secondary" href="#overview">
            <LayoutDashboard className="h-4 w-4" aria-hidden="true" />
            Overview
          </a>
          <a
            aria-current="page"
            className="relative flex items-center gap-2 rounded-[4px] bg-surface px-2 py-2 text-[13px] text-foreground"
            href="#sources"
          >
            <span className="absolute inset-y-2 left-0 w-[2px] bg-accent-marker" aria-hidden="true" />
            <Database className="h-4 w-4" aria-hidden="true" />
            Data sources
          </a>
          <a className="flex items-center gap-2 rounded-[4px] px-2 py-2 text-[13px] text-foreground-secondary" href="#settings">
            <Settings2 className="h-4 w-4" aria-hidden="true" />
            Settings
          </a>
        </nav>
      </aside>

      <main className="min-w-0 flex-1 px-4 py-6 sm:px-6 md:px-8 md:py-8">
        <div className="mx-auto max-w-[1080px]">
          <div className="flex flex-col items-start justify-between gap-5 sm:flex-row sm:gap-6">
            <div>
              <p className="text-[10px] font-medium uppercase tracking-[0.08em] text-label">Design system fixture</p>
              <h1 className="mt-2 text-[32px] font-light leading-[1.1] tracking-[-0.64px] text-heading">Ink actions, sandstone markers</h1>
              <p className="mt-3 max-w-2xl text-sm font-normal leading-6 text-foreground-secondary">
                Repeated controls remain neutral. Sandstone appears only where position or keyboard focus carries a single active state.
              </p>
            </div>
            <div className="flex gap-2">
              <Button variant="secondary">
                <Download aria-hidden="true" />
                Export
              </Button>
              <PagesDialog
                open={dialogOpen}
                onOpenChange={setDialogOpen}
                title="New document"
                description="Check pointer and keyboard focus."
                testId="focus-dialog"
                trigger={<Button variant="primary">New document</Button>}
              >
                <Button variant="secondary">Choose file</Button>
              </PagesDialog>
            </div>
          </div>

          <section className="mt-8 grid gap-6 lg:grid-cols-[minmax(0,1fr)_300px]">
            <div className="overflow-hidden rounded-[6px] border border-border bg-background">
              <div className="flex items-center justify-between border-b border-border-light px-4 py-3">
                <div>
                  <SectionHeading title="Available data sources" />
                  <p className="mt-1 text-xs text-foreground-secondary">One selected row, expressed by both surface and marker.</p>
                </div>
                <span className="text-xs tabular-nums text-foreground-secondary">3 sources</span>
              </div>
              <div className="divide-y divide-border-light">
                {SOURCES.map((source) => (
                  <div
                    className={`relative flex flex-wrap items-center justify-between gap-3 px-4 py-3.5 ${source.selected ? "bg-surface" : "bg-background"}`}
                    key={source.name}
                  >
                    {source.selected && <span className="absolute inset-y-0 left-0 w-[2px] bg-accent-marker" aria-hidden="true" />}
                    <div>
                      <p className="text-[15px] font-normal text-foreground">{source.name}</p>
                      <p className="mt-1 text-xs tabular-nums text-foreground-secondary">{source.meta}</p>
                    </div>
                    <span className="inline-flex items-center gap-1.5 text-xs text-foreground">
                      <span className={`h-1.5 w-1.5 rounded-full ${source.dot}`} aria-hidden="true" />
                      {source.status}
                    </span>
                  </div>
                ))}
              </div>
            </div>

            <div className="space-y-6">
              <section className="rounded-[6px] border border-border bg-background p-4">
                <SectionHeading title="Focus marker" />
                <label className={`mt-4 ${FIELD_LABEL_CLASS}`} htmlFor="fixture-search">Search sources</label>
                <div className="relative">
                  <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-secondary" aria-hidden="true" />
                  <Input autoFocus className="pl-8" id="fixture-search" placeholder="northstar" />
                </div>
              </section>

              <section className="rounded-[6px] border border-border bg-background p-4">
                <SectionHeading title="Data owns color" />
                <div className="mt-4 grid grid-cols-4 gap-2" aria-label="Chart palette">
                  {CATEGORICAL_CHART_TOKENS.map((token) => (
                    <div key={token}>
                      <div className="h-8 rounded-[4px]" style={{ background: `var(${token})` }} />
                      <p className="mt-1 text-center font-mono text-[9px] text-foreground-secondary">{token.replace("--chart-", "")}</p>
                    </div>
                  ))}
                </div>
              </section>
            </div>
          </section>

          <section className="mt-6 rounded-[6px] border border-border bg-background p-4 sm:p-6" aria-labelledby="fixture-actions">
            <h2 id="fixture-actions" className="text-[15px] font-normal text-heading">Actions</h2>
            <p className="mt-1 text-xs text-foreground-secondary">36px default, 32px compact. 13px labels, 16px icons, 4px radius.</p>
            <div className="mt-5 space-y-4">
              {(["md", "sm"] as const).map((size) => (
                <div key={size} className="grid gap-3 sm:grid-cols-[88px_minmax(0,1fr)] sm:items-center">
                  <p className="text-xs text-label">{size === "md" ? "Default · 36" : "Compact · 32"}</p>
                  <div className="flex flex-wrap items-center gap-2">
                    {BUTTON_VARIANTS.map((variant) => (
                      <Button key={variant} variant={variant} size={size}>
                        {variant === "danger" ? <Trash2 aria-hidden="true" /> : variant === "primary" ? <Plus aria-hidden="true" /> : null}
                        {variant[0].toUpperCase() + variant.slice(1)}
                      </Button>
                    ))}
                  </div>
                </div>
              ))}
              <div className="grid gap-3 sm:grid-cols-[88px_minmax(0,1fr)] sm:items-center">
                <p className="text-xs text-label">Disabled</p>
                <div className="flex flex-wrap items-center gap-2">
                  {BUTTON_VARIANTS.map((variant) => (
                    <Button key={variant} variant={variant} disabled>
                      {variant[0].toUpperCase() + variant.slice(1)}
                    </Button>
                  ))}
                </div>
              </div>
            </div>
          </section>

          <section className="mt-6 rounded-[6px] border border-border bg-background p-4 sm:p-6" aria-labelledby="fixture-fields">
            <h2 id="fixture-fields" className="text-[15px] font-normal text-heading">Fields</h2>
            <p className="mt-1 text-xs text-foreground-secondary">32px fields keep a neutral border; focus shows the sandstone caret.</p>
            <div className="mt-5 grid gap-x-6 gap-y-5 md:grid-cols-2">
              <Input label="Connector name" placeholder="Northstar warehouse" required />
              <Input label="Host" defaultValue="warehouse.internal" helperText="Hostname only, without a scheme." />
              <Input label="Port" defaultValue="54321x" error="Enter a port between 1 and 65535." />
              <Input label="Account" defaultValue="acme-analytics" disabled />
              <NativeSelect label="Refresh schedule" value={refresh} onChange={(event) => setRefresh(event.target.value)}>
                <option value="hourly">Every hour</option>
                <option value="daily">Every day</option>
                <option value="manual">Manual only</option>
              </NativeSelect>
              <div>
                <span className={FIELD_LABEL_CLASS} aria-hidden="true">Time grain</span>
                <Select
                  label="Time grain"
                  value="week"
                  options={[{ value: "day", label: "Day" }, { value: "week", label: "Week" }, { value: "month", label: "Month" }]}
                  disabled={false}
                  invalid={false}
                  onChange={() => undefined}
                />
              </div>
              <div className="md:col-span-2">
                <TextArea label="Description" placeholder="What this connector is used for" />
              </div>
            </div>
          </section>

          <section className="mt-6 rounded-[6px] border border-border bg-background p-4 sm:p-6" aria-labelledby="fixture-type">
            <h2 id="fixture-type" className="type-item text-heading">Typography roles</h2>
            <div className="mt-4 divide-y divide-border-light">
              {TYPE_ROLES.map(([role, spec, sample]) => (
                <div key={role} className="grid gap-1 py-3 sm:grid-cols-[180px_minmax(0,1fr)] sm:items-baseline">
                  <p className="type-meta text-foreground-secondary"><code className="type-mono text-label">{role}</code><br />{spec}</p>
                  <p className={`${role} text-heading`}>{sample}</p>
                </div>
              ))}
            </div>
          </section>

          <section className="mt-6 rounded-[6px] border border-border bg-background p-4 sm:p-6" aria-labelledby="fixture-dialogs">
            <h2 id="fixture-dialogs" className="type-item text-heading">Dialogs</h2>
            <p className="type-meta mt-1 text-foreground-secondary">Fixed header and footer, scrolling body, full screen below 768px, locked while saving.</p>
            <div className="mt-4 flex flex-wrap gap-2">
              {DIALOG_SIZES.map(({ size, label }) => (
                <Button key={size} variant="secondary" onClick={() => setShellSize(size)}>{label}</Button>
              ))}
            </div>
            {shellSize ? (
              <Dialog
                open
                onOpenChange={(open) => { if (!open) { setShellSize(null); setSaving(false); } }}
                size={shellSize}
                busy={saving}
                title="Edit connector"
                description="Changes apply to the next refresh."
                testId="fixture-shell-dialog"
                footer={<>
                  <Button variant="ghost" disabled={saving} onClick={() => setShellSize(null)}>Cancel</Button>
                  <Button pending={saving} onClick={() => setSaving(true)}>{saving ? "Saving…" : "Save"}</Button>
                </>}
              >
                <div className="grid gap-5 md:grid-cols-2">
                  <Input label="Connector name" defaultValue="Northstar warehouse" />
                  <Input label="Host" defaultValue="warehouse.internal" />
                  <div className="md:col-span-2"><TextArea label="Description" defaultValue="Primary analytics warehouse." /></div>
                </div>
                {saving ? <p className="type-meta mt-4 text-foreground-secondary">Saving locks Close, Escape, and outside dismissal. Reload to reset.</p> : null}
              </Dialog>
            ) : null}
          </section>

          <section className="mt-6 rounded-[6px] border border-border bg-background p-4 sm:p-6" aria-labelledby="fixture-activity">
            <h2 id="fixture-activity" className="type-item text-heading">Activity attribution</h2>
            <div className="mt-4 divide-y divide-border-light">
              {[
                { name: "Maya Chen", agent: true, agentApp: { clientId: "https://claude.ai/oauth/mcp-oauth-client-metadata", name: "Claude", verifiedHost: "claude.ai" }, action: "published Weekly revenue", when: "2m ago" },
                { name: "Sam Kim", agent: true, agentApp: { clientId: "https://agents.acme.dev/client.json", name: "Acme Agent", verifiedHost: "agents.acme.dev" }, action: "saved Orders model", when: "9m ago" },
                { name: "Ana Ortiz", agent: true, agentApp: null, action: "ran Daily orders", when: "12m ago" },
                { name: "Jordan Lee", agent: false, agentApp: null, action: "edited Net revenue", when: "18m ago" },
              ].map((row) => {
                const avatar = <span className="grid h-7 w-7 place-items-center rounded-full bg-accent type-status text-accent-foreground" aria-hidden="true">{row.name.split(" ").map((part) => part[0]).join("")}</span>;
                return (
                <div key={row.name} className="flex flex-wrap items-center justify-between gap-3 py-3">
                  <div className="flex items-center gap-3">
                    {row.agent ? <AgentAvatar app={row.agentApp}>{avatar}</AgentAvatar> : avatar}
                    <div>
                      <p className="type-control flex flex-col text-foreground">{row.name}{row.agent ? <AgentAvatarCaption app={row.agentApp} className="type-meta text-foreground-secondary" /> : null}</p>
                      <p className="type-meta mt-0.5 text-foreground-secondary">{row.action}</p>
                    </div>
                  </div>
                  <span className="type-meta tabular-nums text-foreground-secondary">{row.when}</span>
                </div>
                );
              })}
            </div>
          </section>

          <section className="mt-6 rounded-[6px] border border-border bg-background p-4 sm:p-6" aria-labelledby="fixture-states">
            <h2 id="fixture-states" className="type-item text-heading">State blocks</h2>
            <div className="mt-4 grid gap-4 lg:grid-cols-3">
              <div className="rounded-[6px] border border-border"><StateBlock kind="empty" layout="inline" icon={Table2} title="No relations yet" description="Refresh the catalog to list tables and views." action={<Button variant="secondary" size="sm">Refresh catalog</Button>} /></div>
              <div className="rounded-[6px] border border-border"><StateBlock kind="loading" layout="inline" title="Loading relations…" /></div>
              <div className="rounded-[6px] border border-border"><StateBlock kind="error" layout="inline" title="Relations could not be loaded" description="The connector did not respond in time." action={<Button variant="secondary" size="sm">Retry</Button>} /></div>
            </div>
            <div className="mt-4 rounded-[6px] border border-border"><StateBlock kind="empty" layout="page" icon={LayoutDashboard} title="No dashboards" description="Create a dashboard to share metrics with your team." action={<Button><Plus aria-hidden="true" />New dashboard</Button>} /></div>
          </section>
        </div>
      </main>
    </div>
  );
}
