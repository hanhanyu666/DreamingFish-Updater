import { reactive } from "vue";
import type { UpdateResultDto } from "./types";

export type UpdateStatus = "working" | "done" | "error" | "offline";

interface StatusSource {
  error: unknown;
  working: boolean;
  launchPermitted: boolean;
  result: Pick<UpdateResultDto, "outcome" | "displayVersion"> | null;
  unverifiedOffline: boolean;
  localContentOverride: unknown;
}

/** Where the update stands, as the capsule on the home page and the title-bar pill elsewhere show it. */
export function updateStatusOf(state: StatusSource): UpdateStatus {
  if (state.error != null) return "error";
  if (state.working || (!state.launchPermitted && state.result == null)) return "working";
  if (state.unverifiedOffline || state.localContentOverride || state.result?.outcome === "OFFLINE_ALLOWED") {
    return "offline";
  }
  return "done";
}

/** The capsule heading once the update is over, as in the design: "已更新到 r13". */
export function finishedHeading(result: Pick<UpdateResultDto, "outcome" | "displayVersion"> | null): string {
  if (result == null) return "已是最新版本";
  if (result.outcome === "OFFLINE_ALLOWED") return "离线启动";
  return result.outcome === "UPDATED" ? "已更新到 " + result.displayVersion : "已是最新 " + result.displayVersion;
}

/** Window chrome state the content pages report, such as the title bar turning solid on scroll. */
export const chrome = reactive({ contentScrolled: false });
