import { afterEach, describe, expect, it, vi } from "vitest";
import { loadInlineRasterImage, parseInlineRasterDataUri } from "./inline-raster";

afterEach(() => vi.unstubAllGlobals());

describe("ChartWidget inline raster boundary", () => {
  it("accepts the supported image:// data URI form", () => {
    const raster = parseInlineRasterDataUri("image://data:image/png;base64,iVBORw0KGgo=");
    expect(raster.mimeType).toBe("image/png");
    expect(raster.bytes.byteLength).toBe(8);
  });

  it("rejects URL, SVG, malformed, and empty image sources", () => {
    expect(() => parseInlineRasterDataUri("https://example.test/logo.png")).toThrow();
    expect(() => parseInlineRasterDataUri("data:image/svg+xml;base64,PHN2Zz4=")).toThrow();
    expect(() => parseInlineRasterDataUri("data:image/png;base64,not base64")).toThrow();
    expect(() => parseInlineRasterDataUri("data:image/png;base64,")).toThrow();
  });

  it("decodes a validated raster into a Worker canvas before notifying zrender", async () => {
    const drawImage = vi.fn();
    class FakeCanvas {
      width = 1;
      height = 1;
      getContext() { return { drawImage } as unknown as OffscreenCanvasRenderingContext2D; }
    }
    const bitmap = { width: 11, height: 7, close: vi.fn() };
    vi.stubGlobal("OffscreenCanvas", FakeCanvas);
    vi.stubGlobal("createImageBitmap", vi.fn().mockResolvedValue(bitmap));
    const onload = vi.fn();
    const onerror = vi.fn();
    const image = loadInlineRasterImage("image://data:image/png;base64,iVBORw0KGgo=", onload, onerror);
    await Promise.resolve();
    await Promise.resolve();
    expect(image).toBeInstanceOf(FakeCanvas);
    expect((image as unknown as FakeCanvas).width).toBe(11);
    expect((image as unknown as FakeCanvas).height).toBe(7);
    expect(drawImage).toHaveBeenCalledWith(bitmap, 0, 0);
    expect(bitmap.close).toHaveBeenCalledOnce();
    expect(onload).toHaveBeenCalledOnce();
    expect(onerror).not.toHaveBeenCalled();
  });
});

describe("inline file byte capacity", () => {
  it("accepts exactly 2 MiB and rejects the next byte before atob allocation", () => {
    const limit = 2 * 1024 * 1024;
    const exact = `data:image/png;base64,${Buffer.alloc(limit).toString("base64")}`;
    expect(parseInlineRasterDataUri(exact).bytes.byteLength).toBe(limit);
    const atobSpy = vi.fn(atob);
    vi.stubGlobal("atob", atobSpy);
    expect(() => parseInlineRasterDataUri(`data:image/png;base64,${Buffer.alloc(limit + 1).toString("base64")}`)).toThrow(/byte budget/);
    expect(atobSpy).not.toHaveBeenCalled();
  });
});
