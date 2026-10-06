"""Check the three generic release ZIPs for accidentally bundled project data.

Run after build-distributions.ps1, before uploading Release assets. Project-bound
deployment ZIPs are intentionally outside this allowlist.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

PRIVATE_NAMES = {"management-settings.json", "management-web-auth.json", "management.db", "project-binding.json"}

def inspect(archive: zipfile.ZipFile, prefix: str, issues: list[dict], marker_bytes: list, depth: int = 0) -> int:
    count = 0
    for entry in archive.infolist():
        if entry.is_dir(): continue
        count += 1
        name = entry.filename.replace("\\", "/")
        location = prefix + "!" + name
        if depth == 0 and (Path(name).name in PRIVATE_NAMES or name.startswith("data/")):
            issues.append({"entry": location, "reason": "project-specific state in generic archive"})
        if depth == 0 and ("private-key" in name.lower() or name.lower().endswith(".key")):
            issues.append({"entry": location, "reason": "possible project private key"})
        payload = archive.read(entry)
        if name.endswith((".jar", ".zip")) and depth < 3 and zipfile.is_zipfile(io.BytesIO(payload)):
            with zipfile.ZipFile(io.BytesIO(payload)) as nested:
                count += inspect(nested, location, issues, marker_bytes, depth + 1)
            continue
        for marker, needle in marker_bytes:
            if needle in payload:
                issues.append({"entry": location, "reason": "private server marker", "marker": marker})
    return count

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="0.2.0")
    parser.add_argument("--dist", type=Path, default=Path(__file__).resolve().parent.parent / "dist")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--markers", type=Path, help="Local JSON array of private strings; keep this file outside public source control")
    args = parser.parse_args()
    markers = json.loads(args.markers.read_text(encoding="utf-8")) if args.markers else []
    if not isinstance(markers, list) or any(not isinstance(m, str) or not m for m in markers):
        parser.error("--markers must contain a JSON array of nonempty strings")
    marker_bytes = [(marker, marker.encode(encoding)) for marker in markers for encoding in ("utf-8", "utf-16le")]
    names = [f"dfs-admin-windows-x64-{args.version}.zip", f"dfs-admin-linux-x64-{args.version}.zip",
             f"dreamingfish-player-windows-x64-{args.version}.zip"]
    report = {"version": args.version, "archives": [], "issues": []}
    for name in names:
        path = args.dist / name
        if not path.is_file():
            report["issues"].append({"entry": name, "reason": "required release asset missing"})
            continue
        with zipfile.ZipFile(path) as archive:
            entries = inspect(archive, name, report["issues"], marker_bytes)
            if name.startswith("dreamingfish-player"):
                binding = next((n for n in archive.namelist() if n.endswith("project-binding.example.json")), None)
                if not binding:
                    report["issues"].append({"entry": name, "reason": "binding format example missing"})
                else:
                    example = json.loads(archive.read(binding))
                    if example.get("projectId") != "replace-with-project-id" or example.get("publicKey") != "replace-with-the-generated-project-public-key":
                        report["issues"].append({"entry": name+"!"+binding, "reason": "example contains an actual project binding"})
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        report["archives"].append({"name": name, "bytes": path.stat().st_size,
                                   "sha256": digest,
                                   "entriesInspected": entries})
    report["passed"] = not report["issues"]
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["passed"] else 1

if __name__ == "__main__":
    raise SystemExit(main())
