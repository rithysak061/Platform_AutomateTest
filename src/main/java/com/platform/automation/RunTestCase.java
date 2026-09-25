package com.platform.automation;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs a single automated test case given only its sheet TEST CASE ID, looking up the Java
 * class/method to run from TestData/TestCaseAutomationMap.csv (the same mapping SheetRunnerAgent
 * uses) instead of requiring the caller to already know that mapping.
 *
 *   mvn compile exec:java -Dexec.mainClass=com.platform.automation.RunTestCase -Dexec.args=TC_HKD_WITHDRA_0090
 *
 * or with the id as a system property instead of an exec arg:
 *   mvn compile exec:java -Dexec.mainClass=com.platform.automation.RunTestCase -DtestCaseId=TC_HKD_WITHDRA_0090
 *
 * Exits with the same exit code as the underlying `mvn test` run, so this is safe to use as a
 * step in a script that needs to know whether the test passed.
 */
public class RunTestCase {

    public static void main(String[] args) throws IOException, InterruptedException {
        String testCaseId = args.length > 0 ? args[0] : System.getProperty("testCaseId");
        if (testCaseId == null || testCaseId.isBlank()) {
            System.err.println("Usage: RunTestCase <TEST_CASE_ID>  (or -DtestCaseId=<TEST_CASE_ID>)");
            System.exit(2);
            return;
        }

        Map<String, TestCaseMapping> mappings = TestCaseMapping.loadAll();
        TestCaseMapping mapping = mappings.get(TestCaseMapping.normalize(testCaseId));
        if (mapping == null) {
            System.err.println("No mapping found for \"" + testCaseId + "\" in TestData/TestCaseAutomationMap.csv.");
            System.exit(2);
            return;
        }
        if (!mapping.enabled()) {
            System.err.println("\"" + testCaseId + "\" is mapped to " + mapping.testClass() + "#" + mapping.testMethod()
                    + ", but is marked disabled in TestData/TestCaseAutomationMap.csv.");
            System.exit(2);
            return;
        }

        String testFilter = mapping.testClass() + "#" + mapping.testMethod();
        System.out.println("Running " + testCaseId + " (" + testFilter + ")...");

        String mvnCommand = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        List<String> command = new ArrayList<>(List.of(mvnCommand, "test", "-Dtest=" + testFilter));
        if (mapping.usesTestCaseId()) {
            command.add("-DtestCaseId=" + testCaseId);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(System.getProperty("user.dir")));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        int exitCode = process.waitFor();
        System.exit(exitCode);
    }
}
