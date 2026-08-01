import { describe, expect, it } from "vitest";
import { shouldRespawnAfterCommandFailure, sidecarCrashMessage } from "../bridge";

describe("player bridge recovery", () => {
  it("restarts a missing sidecar only for an explicit retry", () => {
    expect(shouldRespawnAfterCommandFailure({ command: "retry" })).toBe(true);
    expect(shouldRespawnAfterCommandFailure({ command: "close" })).toBe(false);
    expect(shouldRespawnAfterCommandFailure({ command: "keep-open" })).toBe(false);
  });

  it("turns an unexpected EOF into a retryable visible error instead of closing the shell", () => {
    expect(sidecarCrashMessage("JVM exited with code 1")).toEqual({
      type: "error",
      title: "更新引擎意外退出",
      detail: "JVM exited with code 1",
      allowContinue: false,
    });
  });
});
