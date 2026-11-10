"""Turns Trivy JSON findings into GitHub annotations (first 10), so the gate's reason shows on the run page.

Usage: annotate-trivy.py <trivy-report.json>
"""
import json
import sys

MAX = 10


def escape(text: str) -> str:
    return " ".join(str(text).split())[:900].replace("%", "%25")


def findings(report):
    for result in report.get("Results") or []:
        target = result.get("Target", "?")
        for vuln in result.get("Vulnerabilities") or []:
            yield (f"{vuln.get('Severity')} {vuln.get('VulnerabilityID')} in {vuln.get('PkgName')}",
                   f"{target}: {vuln.get('InstalledVersion')} -> fixed in {vuln.get('FixedVersion')}. "
                   f"{vuln.get('Title', '')}")
        for misconfig in result.get("Misconfigurations") or []:
            yield (f"{misconfig.get('Severity')} {misconfig.get('ID')} {misconfig.get('Title', '')}",
                   f"{target}: {misconfig.get('Message', '')}")
        for secret in result.get("Secrets") or []:
            yield (f"{secret.get('Severity')} secret {secret.get('RuleID')}",
                   f"{target}:{secret.get('StartLine')} {secret.get('Title', '')}")


def main(path):
    try:
        with open(path, encoding="utf-8") as handle:
            report = json.load(handle)
    except (OSError, ValueError) as error:
        print(f"::error title=Trivy::report not readable: {escape(error)}")
        return
    for index, (title, detail) in enumerate(findings(report)):
        if index == MAX:
            break
        print(f"::error title={escape(title)}::{escape(detail)}")


if __name__ == "__main__":
    main(sys.argv[1])
