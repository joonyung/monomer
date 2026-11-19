import { describe, expect, it } from "vitest";
import { activeElementBeforeDialogOpens, dialogFocusReturnTarget } from "./dialogFocus";

interface FakeElement {
  isConnected: boolean;
  focus: () => void;
  attributes: Map<string, string>;
  classes: Set<string>;
  hasAttribute: (name: string) => boolean;
  setAttribute: (name: string, value: string) => void;
  classList: { add: (name: string) => void };
}

function fakeElement(isConnected = true): FakeElement {
  const attributes = new Map<string, string>();
  const classes = new Set<string>();
  return {
    isConnected,
    focus: () => undefined,
    attributes,
    classes,
    hasAttribute: (name) => attributes.has(name),
    setAttribute: (name, value) => { attributes.set(name, value); },
    classList: { add: (name) => { classes.add(name); } },
  };
}

function fakeDocument(elements: Record<string, FakeElement | null>, active: FakeElement | null = null) {
  const body = fakeElement();
  return {
    body,
    activeElement: active ?? body,
    querySelector: (selector: string) => elements[selector] ?? null,
  } as unknown as Document;
}

describe("dialog focus return", () => {
  it("captures the opener before an autoFocus child moves focus", () => {
    const opener = fakeElement();
    const doc = fakeDocument({}, opener);
    const captured = activeElementBeforeDialogOpens(doc);
    // An Input with autoFocus mounts after the opening render and takes focus.
    (doc as unknown as { activeElement: FakeElement }).activeElement = fakeElement();
    expect(captured).toBe(opener);
    expect(dialogFocusReturnTarget(captured, doc)).toBe(opener);
  });

  it("does not treat the document body as an opener", () => {
    expect(activeElementBeforeDialogOpens(fakeDocument({}))).toBeNull();
    expect(activeElementBeforeDialogOpens(undefined)).toBeNull();
  });

  it("falls back to the page heading when the opener was removed", () => {
    const heading = fakeElement();
    const doc = fakeDocument({ "main h1": heading, main: fakeElement() });
    const target = dialogFocusReturnTarget(fakeElement(false) as unknown as HTMLElement, doc);
    expect(target).toBe(heading);
    expect(heading.attributes.get("tabindex")).toBe("-1");
    expect(heading.classes.has("focus-visible:!outline-none")).toBe(true);
  });

  it("falls back to the main region when the page has no heading and keeps existing tabindex", () => {
    const main = fakeElement();
    main.setAttribute("tabindex", "0");
    const doc = fakeDocument({ main });
    expect(dialogFocusReturnTarget(null, doc)).toBe(main);
    expect(main.attributes.get("tabindex")).toBe("0");
  });

  it("returns nothing when neither the opener nor a page region exists", () => {
    expect(dialogFocusReturnTarget(null, fakeDocument({}))).toBeNull();
  });
});
