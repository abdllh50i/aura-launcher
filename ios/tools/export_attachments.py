"""Gives the UI tests' screenshots their names. xcresulttool exports them under random file names, with a manifest beside them.

    python3 tools/export_attachments.py evidence/ui-attachments evidence/flow
"""
import json
import re
import shutil
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
target.mkdir(parents=True, exist_ok=True)
manifest = source / "manifest.json"
if not manifest.exists():
    print("no manifest in", source)
    sys.exit(0)
count = 0
for test in json.loads(manifest.read_text()):
    test_name = test.get("testIdentifier", "test").split("/")[-1].replace("()", "")
    for item in test.get("attachments", []):
        exported = source / item["exportedFileName"]
        readable = item.get("suggestedHumanReadableName") or exported.name
        name = re.sub(r'[/\\"\s:<>|*?`]+', "_", f"{test_name}--{readable}")
        if exported.exists():
            shutil.copy(exported, target / name)
            count += 1
print("named", count, "attachments in", target)
