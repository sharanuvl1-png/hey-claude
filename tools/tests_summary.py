"""Turns JUnit XML reports into a short text summary (first line: pass count)."""
import glob
import sys
import xml.etree.ElementTree as ET

results_dir, log = sys.argv[1], sys.argv[2]
lines, passed, failed = [], 0, 0
for f in sorted(glob.glob(f"{results_dir}/*.xml")):
    for case in ET.parse(f).getroot().iter("testcase"):
        name = f"{case.get('classname', '').split('.')[-1]}.{case.get('name')}"
        bad = case.find("failure") if case.find("failure") is not None else case.find("error")
        if bad is None:
            passed += 1
            lines.append(f"PASS {name}")
        else:
            failed += 1
            detail = "\n      ".join((bad.text or bad.get("message") or "").strip().splitlines()[:12])
            lines.append(f"FAIL {name}\n      {detail}")
print(f"{passed} passed, {failed} failed")
print("\n".join(lines))
if passed + failed == 0:
    print("No test results. End of the build log:")
    print("".join(open(log, errors="replace").readlines()[-80:]))
