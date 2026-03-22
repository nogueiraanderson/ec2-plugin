/**
 * State machine simulation of all EC2 plugin bugs that kill ComputerRetentionWork.
 *
 * Models every failure scenario found during code review, demonstrates which
 * ones kill the CRW timer, and validates the proposed fixes.
 *
 * Usage:
 *   javac CrwStateMachine.java && java CrwStateMachine
 */
public class CrwStateMachine {

    // -----------------------------------------------------------------------
    // Simulated AWS SDK / Jenkins types
    // -----------------------------------------------------------------------

    static class SdkException extends Exception {
        SdkException(String msg) { super(msg); }
    }

    static class InstanceState {
        final String name;
        InstanceState(String name) { this.name = name; }

        static final InstanceState RUNNING = new InstanceState("running");
        static final InstanceState TERMINATED = new InstanceState("terminated");
        static final InstanceState PENDING = new InstanceState("pending");

        static InstanceState find(String name) {
            if (name == null) throw new IllegalArgumentException("null state name");
            return switch (name) {
                case "running" -> RUNNING;
                case "terminated" -> TERMINATED;
                case "pending" -> PENDING;
                default -> throw new IllegalArgumentException("Unknown state: " + name);
            };
        }

        @Override public String toString() { return name; }
    }

    static class Instance {
        final String id;
        final InstanceState stateField;
        final Long launchTimeEpoch; // null = not yet available

        Instance(String id, String stateName, Long launchTimeEpoch) {
            this.id = id;
            this.stateField = stateName != null ? new InstanceState(stateName) : null;
            this.launchTimeEpoch = launchTimeEpoch;
        }

        InstanceState state() { return stateField; }
    }

    static class EC2Cloud {
        final String name;
        EC2Cloud(String name) { this.name = name; }
        SlaveTemplate getTemplate() { return new SlaveTemplate("default", 0); }
    }

    static class SlaveTemplate {
        final String description;
        final int minInstances;
        SlaveTemplate(String desc, int min) { this.description = desc; this.minInstances = min; }
    }

    // -----------------------------------------------------------------------
    // Computer states (the state machine)
    // -----------------------------------------------------------------------

    enum ComputerState {
        HEALTHY_RUNNING,       // Normal: instance running, cloud attached, all fields present
        HEALTHY_IDLE,          // Normal: idle, should be terminated by retention
        TERMINATED_INSTANCE,   // AWS instance terminated, describeInstances returns empty
        DETACHED_NODE,         // Jenkins node removed, getNode() returns null
        NULL_CLOUD,            // Cloud config deleted, getCloud() returns null
        NULL_LAUNCH_TIME,      // Instance in PENDING state, launchTime not yet set
        UNKNOWN_AWS_STATE,     // AWS returns unexpected state string (e.g. "shutting-down")
        SPOT_NO_SIR,           // Spot instance without SIR ID yet
        NULL_INSTANCE_STATE,   // AWS returns instance with null state() field (degraded API)
    }

    // -----------------------------------------------------------------------
    // Simulated EC2Computer -- BUGGY (upstream v5.24)
    // -----------------------------------------------------------------------

    static class BuggyComputer {
        final String name;
        final ComputerState state;
        volatile Instance ec2InstanceDescription;

        BuggyComputer(String name, ComputerState state) {
            this.name = name;
            this.state = state;
        }

        String getInstanceId() {
            return switch (state) {
                case DETACHED_NODE -> null;
                default -> "i-" + name.hashCode();
            };
        }

        EC2Cloud getCloud() {
            return switch (state) {
                case NULL_CLOUD, DETACHED_NODE -> null;
                default -> new EC2Cloud("AWS-Dev");
            };
        }

        SlaveTemplate getSlaveTemplate() {
            // BUG 1: getCloud() can return null, NPE on .getTemplate()
            EC2Cloud cloud = getCloud();
            return cloud.getTemplate(); // NPE if cloud is null!
        }

        Instance getInstanceWithRetry() {
            return switch (state) {
                case TERMINATED_INSTANCE, DETACHED_NODE, NULL_CLOUD -> null;
                case NULL_LAUNCH_TIME -> new Instance("i-pending", "pending", null);
                case UNKNOWN_AWS_STATE -> new Instance("i-weird", "shutting-down", System.currentTimeMillis());
                case NULL_INSTANCE_STATE -> new Instance("i-degraded", null, System.currentTimeMillis());
                default -> new Instance("i-healthy", "running", System.currentTimeMillis() - 3600000);
            };
        }

        // BUG (original): no null check
        InstanceState getState() throws SdkException {
            ec2InstanceDescription = getInstanceWithRetry();
            // NPE if ec2InstanceDescription is null (Bug 0)
            // NPE if .state() is null (Bug 4 - null instance state)
            // IllegalArgumentException if state name is unknown (Bug 3)
            return InstanceState.find(ec2InstanceDescription.state().name);
        }

        long getUptime() throws SdkException {
            Instance desc = ec2InstanceDescription;
            if (desc == null) desc = getInstanceWithRetry();
            // BUG 2: launchTime can be null
            return System.currentTimeMillis() - desc.launchTimeEpoch; // NPE if null
        }

        long getLaunchTimeEpoch() throws SdkException {
            Instance desc = ec2InstanceDescription;
            if (desc == null) desc = getInstanceWithRetry();
            return desc.launchTimeEpoch; // NPE if null
        }
    }

    // -----------------------------------------------------------------------
    // Simulated EC2Computer -- FIXED (v5.24.percona.2)
    // -----------------------------------------------------------------------

    static class FixedComputer {
        final String name;
        final ComputerState state;
        volatile Instance ec2InstanceDescription;

        FixedComputer(String name, ComputerState state) {
            this.name = name;
            this.state = state;
        }

        String getInstanceId() {
            return switch (state) {
                case DETACHED_NODE -> null;
                default -> "i-" + name.hashCode();
            };
        }

        EC2Cloud getCloud() {
            return switch (state) {
                case NULL_CLOUD, DETACHED_NODE -> null;
                default -> new EC2Cloud("AWS-Dev");
            };
        }

        // FIX 1: null check getCloud()
        SlaveTemplate getSlaveTemplate() {
            EC2Cloud cloud = getCloud();
            if (cloud == null) return null;
            return cloud.getTemplate();
        }

        Instance getInstanceWithRetry() {
            return switch (state) {
                case TERMINATED_INSTANCE, DETACHED_NODE, NULL_CLOUD -> null;
                case NULL_LAUNCH_TIME -> new Instance("i-pending", "pending", null);
                case UNKNOWN_AWS_STATE -> new Instance("i-weird", "shutting-down", System.currentTimeMillis());
                case NULL_INSTANCE_STATE -> new Instance("i-degraded", null, System.currentTimeMillis());
                default -> new Instance("i-healthy", "running", System.currentTimeMillis() - 3600000);
            };
        }

        // FIX 0: null guard + FIX 3: unknown state guard + FIX 4: null state guard
        InstanceState getState() throws SdkException {
            ec2InstanceDescription = getInstanceWithRetry();
            if (ec2InstanceDescription == null) {
                throw new SdkException("Instance " + getInstanceId() + " not found (node or cloud detached)");
            }
            if (ec2InstanceDescription.state() == null) {
                throw new SdkException("Instance " + getInstanceId() + " has null state (degraded API response)");
            }
            try {
                return InstanceState.find(ec2InstanceDescription.state().name);
            } catch (IllegalArgumentException e) {
                throw new SdkException("Instance " + getInstanceId() + " has unknown state: " + ec2InstanceDescription.state().name);
            }
        }

        // FIX 2: null launchTime guard
        long getUptime() throws SdkException {
            Instance desc = ec2InstanceDescription;
            if (desc == null) desc = getInstanceWithRetry();
            if (desc == null || desc.launchTimeEpoch == null) {
                throw new SdkException("Cannot determine uptime: instance or launchTime is null");
            }
            return System.currentTimeMillis() - desc.launchTimeEpoch;
        }

        long getLaunchTimeEpoch() throws SdkException {
            Instance desc = ec2InstanceDescription;
            if (desc == null) desc = getInstanceWithRetry();
            if (desc == null || desc.launchTimeEpoch == null) {
                throw new SdkException("Cannot determine launchTime: instance or launchTime is null");
            }
            return desc.launchTimeEpoch;
        }
    }


    // -----------------------------------------------------------------------
    // Simulated retention check (mirrors EC2RetentionStrategy.check())
    // -----------------------------------------------------------------------

    static String checkRetentionBuggy(BuggyComputer c) {
        // Mirrors internalCheck() structure:
        // 1. getSlaveTemplate() -- OUTSIDE try-catch (Bug 1)
        // 2. try { getState(); getUptime(); getLaunchTime(); } catch (SdkException) {}
        try {
            // Line 130 equivalent -- NOT in try-catch for SdkException
            SlaveTemplate tpl = c.getSlaveTemplate();
        } catch (NullPointerException e) {
            // BUG 1: This NPE escapes internalCheck() and kills CRW
            return "KILLED:getSlaveTemplate-NPE:" + e.getMessage();
        }

        try {
            InstanceState state = c.getState();
            long uptime = c.getUptime();
            long launched = c.getLaunchTimeEpoch();
            return "OK:state=" + state + ",uptime=" + uptime;
        } catch (SdkException e) {
            return "SKIP:" + e.getMessage();
        } catch (NullPointerException e) {
            return "KILLED:NPE:" + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "KILLED:IllegalArg:" + e.getMessage();
        }
    }

    static String checkRetentionFixed(FixedComputer c) {
        // Fixed: getSlaveTemplate() has null guard
        SlaveTemplate tpl = c.getSlaveTemplate();
        // tpl can be null now, handled gracefully

        try {
            InstanceState state = c.getState();
            long uptime = c.getUptime();
            long launched = c.getLaunchTimeEpoch();
            return "OK:state=" + state + ",uptime=" + uptime;
        } catch (SdkException e) {
            return "SKIP:" + e.getMessage();
        }
        // No NPE or IllegalArgumentException can escape -- all converted to SdkException
    }

    // -----------------------------------------------------------------------
    // CRW timer simulation
    // -----------------------------------------------------------------------

    interface RetentionCheck {
        String check();
    }

    static boolean simulateCrw(String label, RetentionCheck[] checks) {
        System.out.printf("%n=== CRW Cycle: %s ===%n", label);
        int checked = 0;
        boolean timerAlive = true;

        for (var check : checks) {
            String result = check.check();
            String name = result;
            checked++;

            if (result.startsWith("KILLED")) {
                System.out.printf("  [%d] \u001b[31mKILLED\u001b[0m %s%n", checked, result);
                System.out.printf("       Timer thread DEAD. %d remaining computers SKIPPED.%n",
                    checks.length - checked);
                timerAlive = false;
                break;
            } else if (result.startsWith("SKIP")) {
                System.out.printf("  [%d] \u001b[33mSKIP\u001b[0m   %s%n", checked, result);
            } else {
                System.out.printf("  [%d] \u001b[32mOK\u001b[0m     %s%n", checked, result);
            }
        }

        if (timerAlive) {
            System.out.printf("  Checked %d/%d computers. Timer alive.%n", checked, checks.length);
        }
        return timerAlive;
    }

    // -----------------------------------------------------------------------
    // Test scenarios
    // -----------------------------------------------------------------------

    static void testScenario(String name, ComputerState[] states) {
        System.out.printf("%n%n============================================================%n");
        System.out.printf("SCENARIO: %s%n", name);
        System.out.printf("States: ");
        for (var s : states) System.out.printf("%s ", s);
        System.out.println();

        // BUGGY
        RetentionCheck[] buggyChecks = new RetentionCheck[states.length];
        for (int i = 0; i < states.length; i++) {
            var c = new BuggyComputer("node-" + i, states[i]);
            buggyChecks[i] = () -> checkRetentionBuggy(c);
        }
        boolean buggyAlive = simulateCrw("BUGGY (upstream)", buggyChecks);

        // FIXED
        RetentionCheck[] fixedChecks = new RetentionCheck[states.length];
        for (int i = 0; i < states.length; i++) {
            var c = new FixedComputer("node-" + i, states[i]);
            fixedChecks[i] = () -> checkRetentionFixed(c);
        }
        boolean fixedAlive = simulateCrw("FIXED (percona.2)", fixedChecks);

        System.out.printf("%n  Result: BUGGY=%s  FIXED=%s%n",
            buggyAlive ? "survived" : "TIMER DEAD",
            fixedAlive ? "survived" : "TIMER DEAD");
    }

    // -----------------------------------------------------------------------
    // Main
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        System.out.println("EC2 Plugin State Machine: CRW Timer Death Scenarios");
        System.out.println("====================================================");
        System.out.println();
        System.out.println("Bug inventory:");
        System.out.println("  Bug 0: getState() NPE when ec2InstanceDescription is null (ORIGINAL)");
        System.out.println("  Bug 1: getSlaveTemplate() NPE when getCloud() returns null");
        System.out.println("  Bug 2: getUptime()/getLaunchTime() NPE when launchTime is null");
        System.out.println("  Bug 3: InstanceState.find() IllegalArgumentException for unknown state");
        System.out.println("  Bug 4: getState() NPE when instance.state() is null (degraded API)");

        // Scenario 1: Original bug (terminated instance)
        testScenario("Bug 0 -- Terminated EC2 instance (the pxc incident)",
            new ComputerState[] {
                ComputerState.HEALTHY_RUNNING,
                ComputerState.TERMINATED_INSTANCE,  // <- kills CRW
                ComputerState.HEALTHY_IDLE,          // <- never checked
                ComputerState.HEALTHY_IDLE,          // <- never checked
            });

        // Scenario 2: Cloud config deleted
        testScenario("Bug 1 -- Cloud config removed from Jenkins",
            new ComputerState[] {
                ComputerState.HEALTHY_RUNNING,
                ComputerState.NULL_CLOUD,            // <- kills CRW via getSlaveTemplate
                ComputerState.HEALTHY_IDLE,
            });

        // Scenario 3: Pending instance with null launchTime
        testScenario("Bug 2 -- Instance in PENDING state (null launchTime)",
            new ComputerState[] {
                ComputerState.HEALTHY_RUNNING,
                ComputerState.NULL_LAUNCH_TIME,      // <- kills CRW via getUptime NPE
                ComputerState.HEALTHY_IDLE,
            });

        // Scenario 4: Unknown AWS state
        testScenario("Bug 3 -- AWS returns unknown state 'shutting-down'",
            new ComputerState[] {
                ComputerState.HEALTHY_RUNNING,
                ComputerState.UNKNOWN_AWS_STATE,     // <- kills CRW via IllegalArgumentException
                ComputerState.HEALTHY_IDLE,
            });

        // Scenario 5: Degraded API -- null state field
        testScenario("Bug 4 -- AWS returns instance with null state (degraded API)",
            new ComputerState[] {
                ComputerState.HEALTHY_RUNNING,
                ComputerState.NULL_INSTANCE_STATE,   // <- kills CRW via NPE on state()
                ComputerState.HEALTHY_IDLE,
            });

        // Scenario 6: Detached node
        testScenario("Bug 0+1 -- Node removed from Jenkins (detached)",
            new ComputerState[] {
                ComputerState.HEALTHY_RUNNING,
                ComputerState.DETACHED_NODE,
                ComputerState.HEALTHY_IDLE,
            });

        // Scenario 7: Multiple failures in one fleet
        testScenario("All bugs at once -- worst case fleet",
            new ComputerState[] {
                ComputerState.HEALTHY_RUNNING,
                ComputerState.TERMINATED_INSTANCE,
                ComputerState.NULL_CLOUD,
                ComputerState.NULL_LAUNCH_TIME,
                ComputerState.UNKNOWN_AWS_STATE,
                ComputerState.NULL_INSTANCE_STATE,
                ComputerState.HEALTHY_IDLE,
                ComputerState.HEALTHY_IDLE,
            });

        // Summary
        System.out.println("\n\n====================================================");
        System.out.println("SUMMARY OF FIXES (percona.2)");
        System.out.println("====================================================");
        System.out.println("Fix 0: getState()             -> null instance  -> SdkException (v1, deployed)");
        System.out.println("Fix 1: getSlaveTemplate()     -> null cloud     -> return null");
        System.out.println("Fix 2: getUptime/LaunchTime() -> null time      -> SdkException");
        System.out.println("Fix 3: InstanceState.find()   -> unknown state  -> SdkException");
        System.out.println("Fix 4: getState()             -> null state()   -> SdkException");
        System.out.println("Fix 5: check() RuntimeException safety net (belt-and-suspenders)");
    }
}
