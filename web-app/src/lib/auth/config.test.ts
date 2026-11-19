import { describe, expect, it } from "vitest";
import { resolveControlApiUrl } from "./control-api-origin";

describe("Control API build-time origin", () => {
  it("normalizes an explicit HTTPS origin for endpoint concatenation", () => {
    expect(resolveControlApiUrl("https://control.example.com:443/", false))
      .toBe("https://control.example.com");
  });

  it.each([
    undefined, "", " ", "/", "control.example.com", "https:control.example.com",
    "https://control.example.com/api", "https://control.example.com/a/..",
    "https://user:secret@control.example.com", "https://@control.example.com",
    "https://control.example.com?", "https://control.example.com?key=value",
    "https://control.example.com#", "https://control.example.com#fragment",
    "https://control.example.com/ ", "https://control.example.com\\",
    "https://con\ntrol.example.com", "ftp://control.example.com",
    "http://control.example.com", "http://localhost:11077",
  ])("rejects invalid deployment origin %s", (origin) => {
    expect(() => resolveControlApiUrl(origin, false)).toThrow("VITE_MONOMER_CONTROL_API_URL");
  });

  it.each(["http://localhost:11077", "http://127.0.0.1:11077", "http://127.0.0.2:11077", "http://[::1]:11077"])(
    "permits loopback HTTP only for explicit local environment: %s", (origin) => {
      expect(resolveControlApiUrl(origin, true)).toBe(origin);
      expect(() => resolveControlApiUrl(origin, false)).toThrow();
    },
  );

  it.each(["http://control.example.com", "http://localhost.example.com", "http://192.168.1.1"])(
    "rejects non-loopback HTTP in local environment: %s", (origin) => {
      expect(() => resolveControlApiUrl(origin, true)).toThrow();
    },
  );
});
