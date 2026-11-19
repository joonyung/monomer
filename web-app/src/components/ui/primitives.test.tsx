import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { X } from "lucide-react";
import { Button, buttonClassName } from "./Button";
import { IconButton } from "./IconButton";
import { Input, NativeSelect, TextArea } from "./Input";

function classes(markup: string): string[] {
  return /class="([^"]*)"/.exec(markup)?.[1].replaceAll("&amp;", "&").replaceAll("&gt;", ">").split(/\s+/) ?? [];
}

describe("Button primitive", () => {
  it("renders the 36px default and 32px compact sizes with 13px labels and 16px icons", () => {
    const defaultClasses = classes(renderToStaticMarkup(<Button>Save</Button>));
    const compactClasses = classes(renderToStaticMarkup(<Button size="sm">Save</Button>));
    expect(defaultClasses).toEqual(expect.arrayContaining(["h-9", "px-4", "text-[13px]", "font-normal", "rounded-[4px]", "[&_svg]:size-4", "gap-1.5"]));
    expect(compactClasses).toEqual(expect.arrayContaining(["h-8", "px-3", "text-[13px]"]));
  });

  it("removes color from disabled actions instead of fading them", () => {
    for (const variant of ["primary", "secondary", "danger"] as const) {
      const variantClasses = buttonClassName(variant, "md", "").split(/\s+/);
      expect(variantClasses).toEqual(expect.arrayContaining(["disabled:bg-surface-hover", "disabled:text-foreground-secondary"]));
    }
    expect(buttonClassName("ghost", "md", "").split(/\s+/)).toEqual(expect.arrayContaining(["disabled:bg-transparent", "disabled:text-foreground-secondary"]));
    for (const variant of ["primary", "secondary", "ghost", "danger"] as const) {
      expect(buttonClassName(variant, "md", "")).not.toMatch(/opacity/);
    }
  });

  it("keeps the variant fill, spinner, and busy state while pending", () => {
    const markup = renderToStaticMarkup(<Button pending>Save</Button>);
    const pendingClasses = classes(markup);
    expect(markup).toContain("disabled=\"\"");
    expect(markup).toContain("aria-busy=\"true\"");
    expect(markup).toContain("animate-spin");
    expect(pendingClasses).toContain("bg-accent");
    expect(pendingClasses).not.toContain("disabled:bg-surface-hover");
  });
});

describe("IconButton primitive", () => {
  it("renders a square 32px default and 36px md control with a 16px icon", () => {
    const compactClasses = classes(renderToStaticMarkup(<IconButton label="Close" icon={X} />));
    const defaultClasses = classes(renderToStaticMarkup(<IconButton label="Close" icon={X} size="md" />));
    expect(compactClasses).toEqual(expect.arrayContaining(["size-8", "rounded-[4px]", "[&>svg]:size-4", "bg-transparent", "text-foreground-secondary"]));
    expect(defaultClasses).toContain("size-9");
  });

  it("names the control from its label and keeps an explicit tooltip", () => {
    const markup = renderToStaticMarkup(<IconButton label="Close run detail" icon={X} />);
    expect(markup).toContain("aria-label=\"Close run detail\"");
    expect(markup).toContain("title=\"Close run detail\"");
    expect(markup).toContain("type=\"button\"");
    expect(markup).toContain("aria-hidden=\"true\"");
    expect(renderToStaticMarkup(<IconButton label="Cancel capture" icon={X} title="Cancel capture (Esc)" />)).toContain("title=\"Cancel capture (Esc)\"");
  });

  it("hovers only while enabled and removes color instead of fading when disabled", () => {
    const disabledClasses = classes(renderToStaticMarkup(<IconButton label="Close" icon={X} disabled />));
    expect(disabledClasses).toEqual(expect.arrayContaining(["enabled:hover:bg-surface", "enabled:hover:text-foreground", "disabled:cursor-not-allowed", "disabled:text-gray-400"]));
    expect(disabledClasses.some((name) => name.startsWith("hover:"))).toBe(false);
    expect(disabledClasses.join(" ")).not.toMatch(/opacity/);
  });

  it("keeps the resting color, spinner, and busy state while pending", () => {
    const markup = renderToStaticMarkup(<IconButton label="Refresh" icon={X} pending />);
    const pendingClasses = classes(markup);
    expect(markup).toContain("disabled=\"\"");
    expect(markup).toContain("aria-busy=\"true\"");
    expect(markup).toContain("animate-spin");
    expect(markup).not.toContain("lucide-x");
    expect(pendingClasses).toEqual(expect.arrayContaining(["text-foreground-secondary", "cursor-progress"]));
    expect(pendingClasses).not.toContain("disabled:text-gray-400");
  });
});

describe("field primitives", () => {
  it("share the 32px neutral field with a Focus Sandstone caret", () => {
    for (const markup of [renderToStaticMarkup(<Input aria-label="Name" />), renderToStaticMarkup(<NativeSelect aria-label="Grain"><option>Day</option></NativeSelect>)]) {
      const fieldClasses = classes(markup);
      expect(fieldClasses).toEqual(expect.arrayContaining(["h-8", "px-3", "text-[13px]", "rounded-[4px]", "border-border", "caret-focus-ring"]));
      expect(fieldClasses.join(" ")).not.toMatch(/focus:border|accent-marker/);
    }
    expect(classes(renderToStaticMarkup(<TextArea aria-label="Description" />))).toEqual(expect.arrayContaining(["py-2", "border-border"]));
  });

  it("links the visible label and error message to the control", () => {
    const markup = renderToStaticMarkup(<Input id="port" label="Port" error="Enter a port between 1 and 65535." />);
    expect(markup).toContain("<label for=\"port\" class=\"mb-1.5 block text-xs text-label\">Port</label>");
    expect(markup).toContain("aria-invalid=\"true\"");
    expect(markup).toContain("aria-describedby=\"port-message\"");
    expect(markup).toContain("<p id=\"port-message\" class=\"mt-1.5 text-xs text-error\">Enter a port between 1 and 65535.</p>");
    expect(markup).toContain("border-error");
  });

  it("renders a bare control when there is no label or message", () => {
    const markup = renderToStaticMarkup(<Input aria-label="Search" />);
    expect(markup.startsWith("<input")).toBe(true);
  });
});
