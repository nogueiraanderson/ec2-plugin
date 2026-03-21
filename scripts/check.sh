#!/usr/bin/env bash
# Check EC2 plugin version and CRW health across instances.
# Usage: ./scripts/check.sh "pmm ps80 psmdb pxb pxc pg ps57 rel cloud ps3"
set -euo pipefail

instances="${1:?Usage: check.sh \"inst1 inst2 ...\"}"

echo "INSTANCE    VERSION                     CRW-HEALTH"
for inst in $instances; do
    result=$(jenkins admin -i "$inst" groovy -e '
def plugin = Jenkins.instance.pluginManager.getPlugin("ec2")
def ver = plugin ? plugin.version : "not-installed"
def crw = Jenkins.instance.getExtensionList(hudson.slaves.ComputerRetentionWork.class)[0]
def f = crw.class.getDeclaredField("nextCheck"); f.accessible = true
def map = f.get(crw)
def computers = Jenkins.instance.computers.length
def staleMs = map.values().max() ?: 0
def ageMin = staleMs > 0 ? ((System.currentTimeMillis() - staleMs) / 60000).toLong() : -1
println "${ver}|${map.size()}/${computers}|${ageMin}min"
' --json 2>&1 | python3 -c "import json,sys; print(json.load(sys.stdin).get('message','FAIL'))" 2>/dev/null || echo "FAIL")
    IFS='|' read -r ver crw age <<< "$result"
    printf "%-11s %-27s %s (age: %s)\n" "$inst" "$ver" "$crw" "$age"
done
