#!/usr/bin/env python3
"""Generate update-center.json for this plugin's private/self-hosted Update Center.

Ground truth for the JSON shape used here:
- hudson.model.UpdateSite.Entry / .Plugin field names and semantics
  (https://javadoc.jenkins.io/hudson/model/UpdateSite.Plugin.html), confirmed against
  jenkinsci/jenkins core source (hudson/model/UpdateSite.java): 'sha1' and 'sha256' are the
  **Base64-encoded** binary digest of the .hpi file, not hex.
- hudson.model.DownloadService#loadJSON (jenkinsci/jenkins core source,
  hudson/model/DownloadService.java) parses the fetched document by slicing between the first
  '{' and the last '}' -- a JSONP callback wrapper (e.g. the historical
  'updateCenter.post(...)') is optional, not required. This script emits plain JSON.
- Site layout reference: https://github.com/jenkins-infra/update-center2/blob/master/site/LAYOUT.md

Usage:
    python3 generate_update_center.py \
        --hpi ../target/config-template-sync.hpi \
        --base-url https://github.com/Retro-kharkov1/config-template-sync/releases/download/vVERSION \
        --out site/update-center.json

'VERSION' in --base-url is replaced with the plugin version read out of the .hpi's own
META-INF/MANIFEST.MF (Plugin-Version), so the URL always matches the artifact actually being
described, instead of being typed twice and risking drift.
"""
import argparse
import base64
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path


def read_manifest(hpi_path: Path) -> dict:
    with zipfile.ZipFile(hpi_path) as zf:
        raw = zf.read("META-INF/MANIFEST.MF").decode("utf-8")
    # Undo MANIFEST.MF's 72-column continuation-line wrapping (continuation lines start with a
    # single space) before splitting into key: value pairs.
    unwrapped = re.sub(r"\r?\n ", "", raw)
    fields = {}
    for line in unwrapped.splitlines():
        if not line.strip() or ":" not in line:
            continue
        key, _, value = line.partition(":")
        fields[key.strip()] = value.strip()
    return fields


def parse_plugin_dependencies(raw: str) -> list:
    """Plugin-Dependencies header, e.g. 'workflow-step-api:678.v...;resolution:=optional,credentials:1371...'.

    IMPORTANT (verified against jenkinsci/jenkins core source,
    hudson/model/UpdateSite.java, the UpdateSite.Plugin(JSONObject) constructor): each dependency
    object's 'optional' field is read as `get(depObj, "optional").equals("false")` with NO null
    check -- an update-center.json that simply omits 'optional' (rather than setting it to the
    string "false") crashes UpdateSite.getData() with a NullPointerException the moment Jenkins
    tries to parse the site. 'optional' MUST always be present as the literal string "true" or
    "false" on every dependency entry.
    """
    deps = []
    if not raw:
        return deps
    for entry in raw.split(","):
        entry = entry.strip()
        if not entry:
            continue
        optional = ";resolution:=optional" in entry
        entry = entry.replace(";resolution:=optional", "")
        name, _, version = entry.partition(":")
        deps.append({
            "name": name.strip(),
            "version": version.strip(),
            "optional": "true" if optional else "false",
        })
    return deps


def digest_b64(data: bytes, algo: str) -> str:
    h = hashlib.new(algo)
    h.update(data)
    return base64.b64encode(h.digest()).decode("ascii")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--hpi", required=True, type=Path, help="Path to the built .hpi file")
    parser.add_argument(
        "--base-url",
        required=True,
        help="Directory URL the .hpi will be served from; 'VERSION' is substituted with the "
        "plugin version. The .hpi filename is appended automatically.",
    )
    parser.add_argument(
        "--site-id",
        default="config-template-sync-update-center",
        help="Value for the top-level 'id' field (site source id).",
    )
    parser.add_argument(
        "--connection-check-url",
        default="https://www.google.com/",
        help="URL Jenkins pings first to distinguish 'no internet' from 'site unreachable'.",
    )
    parser.add_argument("--out", required=True, type=Path, help="Output path for update-center.json")
    args = parser.parse_args()

    if not args.hpi.is_file():
        print(f"error: hpi not found: {args.hpi}", file=sys.stderr)
        return 1

    manifest = read_manifest(args.hpi)
    name = manifest.get("Short-Name")
    version = manifest.get("Plugin-Version")
    long_name = manifest.get("Long-Name", name)
    required_core = manifest.get("Jenkins-Version") or manifest.get("Hudson-Version")
    url = manifest.get("Url", "")
    if not (name and version and required_core):
        print(f"error: MANIFEST.MF missing required fields, got: {manifest}", file=sys.stderr)
        return 1

    data = args.hpi.read_bytes()
    sha1_b64 = digest_b64(data, "sha1")
    sha256_b64 = digest_b64(data, "sha256")

    download_url = args.base_url.replace("VERSION", version).rstrip("/") + f"/{args.hpi.name}"

    plugin_entry = {
        "name": name,
        "title": long_name,
        "version": version,
        "url": download_url,
        "sha1": sha1_b64,
        "sha256": sha256_b64,
        "requiredCore": required_core,
        "dependencies": parse_plugin_dependencies(manifest.get("Plugin-Dependencies", "")),
        "excerpt": (
            "Keeps a nested structured-config token store (per common/env layer) structurally "
            "in sync with #{Dotted.Path}# placeholder tokens in a target application config file."
        ),
        "wiki": url,
    }

    update_center = {
        "updateCenterVersion": "1",
        "id": args.site_id,
        "connectionCheckUrl": args.connection_check_url,
        "plugins": {name: plugin_entry},
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(update_center, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {args.out} (plugin {name}@{version}, sha256={sha256_b64})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
