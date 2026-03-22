/**
 * Simulates the EC2 plugin NPE that kills ComputerRetentionWork.
 *
 * The bug: EC2Computer.getState() dereferences ec2InstanceDescription without
 * null check. When CloudHelper.getInstanceWithRetry() returns null (terminated
 * instance), the NPE propagates through ComputerRetentionWork and kills the
 * periodic timer thread permanently. All idle worker cleanup stops.
 *
 * Usage:
 *   javac CrwNpeDemo.java && java CrwNpeDemo
 */
public class CrwNpeDemo {

    // --- Simulated AWS SDK types ---

    static class InstanceState {
        final String name;
        InstanceState(String name) { this.name = name; }
        static InstanceState find(String name) { return new InstanceState(name); }
        @Override public String toString() { return name; }
    }

    static class Instance {
        final String id;
        final InstanceState state;
        Instance(String id, String stateName) {
            this.id = id;
            this.state = new InstanceState(stateName);
        }
        InstanceState state() { return state; }
    }

    static class SdkException extends Exception {
        SdkException(String msg) { super(msg); }
    }

    // --- Simulated CloudHelper ---

    static Instance getInstanceWithRetry(String instanceId) {
        if (instanceId == null || instanceId.startsWith("i-terminated")) {
            return null; // instance not found (terminated or detached node)
        }
        return new Instance(instanceId, "running");
    }

    // --- Simulated EC2Computer (BUGGY - upstream code) ---

    static class BuggyEC2Computer {
        final String name;
        final String instanceId;
        Instance ec2InstanceDescription;

        BuggyEC2Computer(String name, String instanceId) {
            this.name = name;
            this.instanceId = instanceId;
        }

        // Upstream code (EC2Computer.java:199-201) - NO null check
        InstanceState getState() throws SdkException {
            ec2InstanceDescription = getInstanceWithRetry(instanceId);
            return InstanceState.find(ec2InstanceDescription.state().name); // NPE here!
        }
    }

    // --- Simulated EC2Computer (FIXED - our patch) ---

    static class FixedEC2Computer {
        final String name;
        final String instanceId;
        Instance ec2InstanceDescription;

        FixedEC2Computer(String name, String instanceId) {
            this.name = name;
            this.instanceId = instanceId;
        }

        // Patched code - null guard converts NPE to SdkException
        InstanceState getState() throws SdkException {
            ec2InstanceDescription = getInstanceWithRetry(instanceId);
            if (ec2InstanceDescription == null) {
                throw new SdkException(
                    "Instance " + instanceId + " not found (may be terminated)");
            }
            return InstanceState.find(ec2InstanceDescription.state().name);
        }
    }

    // --- Simulated retention strategy ---

    interface Computer {
        String getName();
        void checkRetention() throws SdkException;
    }

    static class EC2Worker implements Computer {
        final String name;
        final String instanceId;
        final boolean useFix;

        EC2Worker(String name, String instanceId, boolean useFix) {
            this.name = name;
            this.instanceId = instanceId;
            this.useFix = useFix;
        }

        @Override public String getName() { return name; }

        @Override
        public void checkRetention() throws SdkException {
            // EC2RetentionStrategy.check() -> attemptReconnectIfOffline() -> getState()
            if (useFix) {
                new FixedEC2Computer(name, instanceId).getState();
            } else {
                new BuggyEC2Computer(name, instanceId).getState();
            }
        }
    }

    static class HetznerWorker implements Computer {
        final String name;
        final boolean idle;
        final int idleMinutes;
        final int threshold;

        HetznerWorker(String name, boolean idle, int idleMinutes, int threshold) {
            this.name = name;
            this.idle = idle;
            this.idleMinutes = idleMinutes;
            this.threshold = threshold;
        }

        @Override public String getName() { return name; }

        @Override
        public void checkRetention() {
            if (idle && idleMinutes > threshold) {
                System.out.printf("    -> %s: idle %dm > %dm, would terminate%n",
                    name, idleMinutes, threshold);
            } else {
                System.out.printf("    -> %s: ok (idle=%b, %dm)%n",
                    name, idle, idleMinutes);
            }
        }
    }

    // --- Simulated ComputerRetentionWork.doRun() ---

    static boolean simulateCrwCycle(Computer[] computers, String label) {
        System.out.printf("%n=== CRW cycle: %s ===%n", label);
        int checked = 0;
        for (Computer c : computers) {
            try {
                c.checkRetention();
                checked++;
            } catch (NullPointerException e) {
                // BUG: NPE is uncaught RuntimeException, kills the timer thread
                System.out.printf("    !! %s: NPE -> %s%n", c.getName(), e.getMessage());
                System.out.printf("    !! Timer thread DEAD. Remaining %d computers SKIPPED.%n",
                    computers.length - checked - 1);
                return false; // timer dies
            } catch (SdkException e) {
                // FIX: SdkException is caught, computer skipped, loop continues
                System.out.printf("    ~~ %s: skipped (%s)%n", c.getName(), e.getMessage());
                checked++;
            }
        }
        System.out.printf("    Checked %d/%d computers. Timer alive.%n", checked, computers.length);
        return true; // timer survives
    }

    // --- Main ---

    public static void main(String[] args) {
        System.out.println("EC2 Plugin NPE Demo: ComputerRetentionWork Timer Death");
        System.out.println("======================================================");

        // Fleet: mix of EC2 and Hetzner workers, one EC2 node has terminated instance
        Computer[] buggyFleet = {
            new EC2Worker("EC2 (us-west-1) - docker-1", "i-abc123", false),
            new EC2Worker("EC2 (us-west-1) - docker-2", "i-terminated-xyz", false), // <- NPE bomb
            new HetznerWorker("hcloud-rogue1", true, 120, 10),  // rogue, should be cleaned
            new HetznerWorker("hcloud-rogue2", true, 90, 10),   // rogue, should be cleaned
            new HetznerWorker("hcloud-active", false, 0, 10),   // active, keep
        };

        Computer[] fixedFleet = {
            new EC2Worker("EC2 (us-west-1) - docker-1", "i-abc123", true),
            new EC2Worker("EC2 (us-west-1) - docker-2", "i-terminated-xyz", true), // <- handled
            new HetznerWorker("hcloud-rogue1", true, 120, 10),
            new HetznerWorker("hcloud-rogue2", true, 90, 10),
            new HetznerWorker("hcloud-active", false, 0, 10),
        };

        // --- BUGGY behavior ---
        System.out.println("\n1. BUGGY (upstream EC2 plugin):");
        System.out.println("   One terminated EC2 instance kills cleanup for ALL workers.\n");

        boolean alive = simulateCrwCycle(buggyFleet, "BUGGY");

        if (!alive) {
            System.out.println("\n   Result: 2 Hetzner rogue workers NEVER cleaned up.");
            System.out.println("   They accumulate indefinitely until JVM restart.");
            System.out.println("   On pxc.cd: this left 24 rogue workers for 47 hours.");
        }

        // --- FIXED behavior ---
        System.out.println("\n\n2. FIXED (our patch - null -> SdkException):");
        System.out.println("   Terminated EC2 instance is skipped, loop continues.\n");

        alive = simulateCrwCycle(fixedFleet, "FIXED");

        if (alive) {
            System.out.println("\n   Result: EC2 ghost skipped gracefully.");
            System.out.println("   Hetzner rogue workers detected and would be terminated.");
            System.out.println("   Timer stays alive for future cycles.");
        }
    }
}
