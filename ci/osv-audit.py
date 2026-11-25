"""Query OSV for exact resolved Maven versions; fail closed on findings or API errors."""
import json
from pathlib import Path
from urllib.request import Request, urlopen

root = Path("audit")
packages = json.loads((root / "dependencies.json").read_text())
if not packages:
    raise SystemExit("Empty dependency inventory")
results = []
for start in range(0, len(packages), 100):
    batch = packages[start:start + 100]
    queries = [{"package": {"ecosystem": "Maven", "name": p["name"]},
                "version": p["version"]} for p in batch]
    pending = list(range(len(batch)))
    found = [[] for _ in batch]
    while pending:
        req = Request("https://api.osv.dev/v1/querybatch",
                      data=json.dumps({"queries": [queries[i] for i in pending]}).encode(),
                      headers={"Content-Type": "application/json"})
        with urlopen(req, timeout=60) as response:
            answers = json.load(response)["results"]
        if len(answers) != len(pending):
            raise RuntimeError("Unexpected OSV result count")
        again = []
        for i, answer in zip(pending, answers):
            found[i].extend(answer.get("vulns", []))
            token = answer.get("next_page_token")
            if token:
                queries[i]["page_token"] = token
                again.append(i)
        pending = again
    results.extend({**p, "vulnerabilities": v} for p, v in zip(batch, found))
(root / "osv-results.json").write_text(json.dumps(results, indent=2))
issues = [p for p in results if p["vulnerabilities"]]
for p in issues:
    print(p["name"], p["version"], ", ".join(v["id"] for v in p["vulnerabilities"]))
print(f"Checked {len(results)} resolved coordinates; {len(issues)} with known advisories")
raise SystemExit(1 if issues else 0)
