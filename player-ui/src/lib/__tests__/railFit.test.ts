import { describe, expect, it } from "vitest";
import { RAIL_FOLDS, railFolds } from "../railFit";

describe("home rail folding", () => {
  it("folds nothing while the cards fit", () => {
    expect(railFolds(() => true)).toEqual([]);
  });

  it("tightens the server card first so the event can stay, and drops the whole card last", () => {
    const tried: string[] = [];
    const folds = railFolds((candidate) => {
      tried.push(candidate.join(" "));
      return candidate.length >= 2;
    });
    expect(folds).toEqual(["status", "address"]);
    expect(tried).toEqual(["", "status", "status address"]);
    expect(RAIL_FOLDS.indexOf("event")).toBeGreaterThan(RAIL_FOLDS.indexOf("address"));
    expect(RAIL_FOLDS.at(-1)).toBe("server");
  });

  it("folds everything when even that is not enough", () => {
    expect(railFolds(() => false)).toEqual(RAIL_FOLDS);
  });
});
