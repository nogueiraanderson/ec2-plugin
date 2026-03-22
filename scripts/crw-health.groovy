// ComputerRetentionWork health check
// Detects dead CRW timer, shows per-computer retention state, and optionally restarts.
// Usage: jenkins admin -i <inst> groovy -f scripts/crw-health.groovy

import groovy.json.JsonOutput
import java.util.logging.Level

def crw = Jenkins.instance.getExtensionList(hudson.slaves.ComputerRetentionWork.class)[0]
def f = crw.class.getDeclaredField("nextCheck")
f.accessible = true
def map = f.get(crw)
def now = System.currentTimeMillis()
def computers = Jenkins.instance.computers

// CRW timer state
def mapSize = map.size()
def compCount = computers.length
def maxTs = map.values().max() ?: 0
def minTs = map.values().min() ?: 0
def ageMin = maxTs > 0 ? ((now - maxTs) / 60000).toLong() : -1
def staleMin = minTs > 0 ? ((now - minTs) / 60000).toLong() : -1

// Classify timer health
def health = "HEALTHY"
if (mapSize == 0 && compCount > 1) {
    health = "DEAD"
} else if (mapSize == 1 && compCount > 5) {
    health = "DEAD"
} else if (ageMin > 10 && compCount > 1) {
    // nextCheck entries are all >10min in the past = timer stopped cycling
    health = "DEAD"
}

// Per-computer retention info
def retentionInfo = computers.collect { comp ->
    def node = comp.node
    def strategy = node?.retentionStrategy
    def nextCheck = map.get(comp)
    def nextCheckAge = nextCheck ? ((now - nextCheck) / 60000).toLong() : null
    [
        name: comp.name ?: "(controller)",
        online: !comp.offline,
        idle: comp.idle,
        type: strategy?.class?.simpleName ?: "none",
        nextCheckAge: nextCheckAge,
        tracked: map.containsKey(comp),
    ]
}

// EC2 computers with null instanceDescription (NPE risk)
def ec2NpeRisk = computers.findAll { comp ->
    comp.class.name.contains("EC2Computer")
}.collect { comp ->
    def descField = comp.class.getDeclaredField("ec2InstanceDescription")
    descField.accessible = true
    def desc = descField.get(comp)
    [name: comp.name, hasDescription: desc != null]
}.findAll { !it.hasDescription }

// Recent CRW exceptions from system log (ring buffer)
def crwErrors = []
try {
    Jenkins.instance.log.recorders.each { recorder ->
        recorder.handler?.getView()?.each { record ->
            if (record.level.intValue() >= Level.WARNING.intValue() &&
                (record.message?.contains("ComputerRetentionWork") ||
                 record.message?.contains("SafeTimerTask"))) {
                def sw = new StringWriter()
                if (record.thrown) record.thrown.printStackTrace(new PrintWriter(sw))
                crwErrors << [
                    time: new Date(record.millis).toString(),
                    level: record.level.toString(),
                    message: record.message,
                    exception: sw.toString() ?: null,
                ]
            }
        }
    }
} catch (e) { /* no log recorders */ }

return JsonOutput.toJson([
    health: health,
    nextCheckMap: mapSize,
    computers: compCount,
    maxCheckAge: ageMin,
    stalestCheckAge: staleMin,
    retention: retentionInfo,
    ec2NpeRisk: ec2NpeRisk,
    recentCrwErrors: crwErrors,
])
