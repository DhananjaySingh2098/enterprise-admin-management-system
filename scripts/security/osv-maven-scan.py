#!/usr/bin/env python3
"""Checks every resolved Maven dependency of the backend against the OSV.dev vulnerability database.

Usage (from the repository root, Java 21 on JAVA_HOME):
    python3 scripts/security/osv-maven-scan.py
Exit code 1 when any dependency has a known vulnerability. Needs network access to api.osv.dev.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BACKEND = os.path.join(ROOT, "backend")


def resolve_dependencies():
    with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as out:
        path = out.name
    subprocess.run(["./mvnw", "-q", "dependency:list", "-DoutputFile=" + path, "-DoutputAbsoluteArtifactFilename=false"],
                   cwd=BACKEND, check=True)
    deps = set()
    for line in open(path):
        m = re.match(r"\s*([\w.\-]+):([\w.\-]+):jar(?::[\w.\-]+)?:([\w.\-]+):(compile|runtime|test|provided)", line)
        if m:
            deps.add((m.group(1), m.group(2), m.group(3), m.group(4)))
    os.unlink(path)
    return sorted(deps)


def query(deps):
    body = {"queries": [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for g, a, v, _ in deps]}
    request = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)["results"]


def details(vuln_id):
    with urllib.request.urlopen(f"https://api.osv.dev/v1/vulns/{vuln_id}", timeout=60) as response:
        return json.load(response)


def main():
    deps = resolve_dependencies()
    results = query(deps)
    findings = []
    for (g, a, v, scope), result in zip(deps, results):
        for vuln in result.get("vulns", []):
            info = details(vuln["id"])
            severity = (info.get("database_specific") or {}).get("severity", "UNKNOWN")
            findings.append((severity, f"{g}:{a}:{v}", scope, vuln["id"], info.get("summary", "")[:100]))
    print(f"Scanned {len(deps)} Maven artifacts ({sum(1 for d in deps if d[3] != 'test')} runtime/compile, "
          f"{sum(1 for d in deps if d[3] == 'test')} test).")
    counts = {}
    for severity, *_ in findings:
        counts[severity] = counts.get(severity, 0) + 1
    print("Findings by severity:", counts or "none")
    for finding in sorted(findings):
        print(" -", " | ".join(finding))
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
