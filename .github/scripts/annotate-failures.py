"""Turns test and build failures into GitHub annotations, so the cause shows on the run page.

Usage: annotate-failures.py <maven-log> [report-dir ...]
Reports failing JUnit test cases (Surefire/Failsafe XML); if there are none, the Maven [ERROR] lines.
GitHub shows at most 10 error annotations per step, so only the first failures are reported.
"""
import glob
import sys
import xml.etree.ElementTree as ET

MAX = 10


def escape(text: str) -> str:
    text = " ".join(text.split())[:900]
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def failing_tests(directories):
    for directory in directories:
        for path in sorted(glob.glob(f"{directory}/**/TEST-*.xml", recursive=True)):
            for case in ET.parse(path).getroot().iter("testcase"):
                for kind in ("failure", "error"):
                    node = case.find(kind)
                    if node is not None:
                        first_lines = " | ".join((node.text or "").strip().splitlines()[:3])
                        yield f"{case.get('classname')}.{case.get('name')}", f"{node.get('message') or ''} {first_lines}"


def main(argv):
    log_path, directories = (argv[0] if argv else None), (argv[1:] or ["."])
    emitted = 0
    for title, detail in failing_tests(directories):
        if emitted == MAX:
            break
        print(f"::error title={escape(title)}::{escape(detail)}")
        emitted += 1
    if emitted == 0 and log_path:
        try:
            with open(log_path, encoding="utf-8", errors="replace") as log:
                errors = [line.strip() for line in log if line.startswith("[ERROR]") and len(line.strip()) > 8]
        except FileNotFoundError:
            errors = []
        for line in errors[:MAX]:
            print(f"::error title=Maven::{escape(line)}")


if __name__ == "__main__":
    main(sys.argv[1:])
