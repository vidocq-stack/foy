"""Checks the alerts of the nightly ZAP scan against the accepted ones.

Usage: check.py <zap.json> <accepted.tsv>

Prints every alert ZAP raised (risk, name, plugin id, URLs) and whether it is accepted; exits 1 when an
alert of risk Low or above is not accepted, so that the nightly turns red on a new finding only.
Informational alerts are printed and never fail.

Each line of accepted.tsv is a plugin id, a tab, the evidence accepted (exactly as ZAP reports it, or * for
any), a tab, then why. An alert is accepted when every one of its instances is: an entry tied to an evidence
leaves the other findings of the same rule, for instance a stack trace where a generic page was accepted,
failing the night. Chappe's check.py accepts by plugin id only; Foy needs the evidence because the target
answers some errors on purpose.
"""
import json
import sys

report_path, accepted_path = sys.argv[1:3]
accepted = {}
with open(accepted_path, encoding="utf-8") as fh:
    for line in fh:
        line = line.rstrip("\n")
        if line and not line.startswith("#"):
            plugin, evidence, reason = (line.split("\t", 2) + ["", ""])[:3]
            accepted.setdefault(plugin.strip(), {})[evidence.strip() or "*"] = reason.strip()


def reason_for(plugin, instance):
    entries = accepted.get(plugin, {})
    return entries.get(instance.get("evidence", ""), entries.get("*"))


with open(report_path, encoding="utf-8") as fh:
    report = json.load(fh)

new = 0
alerts = [a for site in report.get("site", []) for a in site.get("alerts", [])]
for alert in sorted(alerts, key=lambda a: -int(a.get("riskcode", 0))):
    plugin, risk = str(alert.get("pluginid")), int(alert.get("riskcode", 0))
    instances = alert.get("instances", [])
    uris = sorted({i.get("uri", "") for i in instances})
    reasons = [reason_for(plugin, i) for i in instances]
    if reasons and all(reasons):
        verdict = "accepted: " + "; ".join(sorted(set(reasons)))
    elif risk == 0:
        verdict = "informational"
    else:
        verdict = "NEW"
        new += 1
    print("%-14s %s (plugin %s, %d URL%s, e.g. %s): %s" % (
        alert.get("riskdesc", "?"), alert.get("alert", "?"), plugin, len(uris), "" if len(uris) == 1 else "s",
        uris[0] if uris else "-", verdict))
    if verdict == "NEW":
        for i in instances:
            print("    %s %s evidence=%r" % (i.get("method", "?"), i.get("uri", "-"), i.get("evidence", "")))

print("ZAP: %d alert(s), %d new" % (len(alerts), new))
sys.exit(1 if new else 0)
