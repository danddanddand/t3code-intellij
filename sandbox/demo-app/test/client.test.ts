import { describe, expect, it, vi } from "vitest";
import { post } from "../src/api/client";

describe("post", () => {
  it("returns an error for a non-2xx response", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    const result = await post("/accounts", {});
    expect(result).toEqual({ ok: false, error: "Request failed with status 500" });
  });
});
