package com.platform.automation;

import com.platform.utility.ConfigReader;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Polls:
 *  - the tabs listed in sheet.watchedTabs, for test-case rows whose RUN checkbox is checked
 *    (runs that one Maven/TestNG test) and for each tab's whole-suite RUN checkbox (one row
 *    above the header, runs the tab's entire test class named by sheet.suiteClass.<Tab>).
 *  - the "Test Suite Automate" dashboard tab (see MasterSheetLayout), independently of
 *    sheet.watchedTabs, for the same kind of whole-suite RUN checkbox, one row per test-case tab
 *    found anywhere in the spreadsheet.
 * Either way the result is written back as AUTOMATION STATUS / LAST RUN / a clickable REPORT
 * link. Run SheetSetupTool once first to add those columns and checkboxes. Start with:
 *   mvn compile exec:java -Dexec.mainClass=com.platform.automation.SheetRunnerAgent
 */
public class SheetRunnerAgent {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_PASSED = "PASSED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_NOT_AUTOMATED = "NOT AUTOMATED";
    private static final String STATUS_NOT_CONFIGURED = "NOT CONFIGURED";

    private static ReportFileServer reportServer;

    public static void main(String[] args) throws InterruptedException {
        String spreadsheetId = ConfigReader.get("sheet.spreadsheetId");
        String credentialsPath = ConfigReader.get("sheet.credentialsPath");
        int pollSeconds = ConfigReader.getInt("sheet.pollIntervalSeconds");
        int reportServerPort = ConfigReader.getInt("sheet.reportServerPort");
        List<String> watchedTabs = watchedTabs();

        SheetsClient client = new SheetsClient(spreadsheetId, credentialsPath);

        reportServer = new ReportFileServer(new File("reports"), reportServerPort);
        reportServer.start();

        System.out.println("Sheet runner agent started, watching tab(s) " + watchedTabs
                + ". Polling every " + pollSeconds + "s. Press Ctrl+C to stop.");

        while (true) {
            try {
                // Reloaded every cycle (not once at startup) so editing TestCaseAutomationMap.csv
                // or renaming/adding a test method takes effect on the next poll, without having
                // to remember to restart this long-running agent.
                Map<String, TestCaseMapping> mappings = TestCaseMapping.loadAll();
                for (String tab : watchedTabs) {
                    processTab(client, tab, mappings);
                }
                processMasterSheet(client);
            } catch (Exception e) {
                System.err.println("Poll cycle failed: " + e.getMessage());
            }
            TimeUnit.SECONDS.sleep(pollSeconds);
        }
    }

    private static List<String> watchedTabs() {
        return Arrays.stream(ConfigReader.get("sheet.watchedTabs").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static void processTab(SheetsClient client, String tab, Map<String, TestCaseMapping> mappings) {
        List<List<Object>> rows = client.readRange("'" + tab + "'!A1:Z300");
        Optional<TestCaseSheetLayout> layoutOpt = TestCaseSheetLayout.locate(rows);
        if (layoutOpt.isEmpty()) {
            return;
        }
        TestCaseSheetLayout layout = layoutOpt.get();
        if (layout.runCol() < 0 || layout.statusCol() < 0) {
            return; // tab hasn't been wired up yet - run SheetSetupTool first
        }

        int suiteRow = layout.suiteRowIndex();
        if (suiteRow >= 0 && suiteRow < rows.size()
                && "TRUE".equalsIgnoreCase(TestCaseSheetLayout.cell(rows.get(suiteRow), layout.runCol()))) {
            client.updateCell(tab, suiteRow, layout.runCol(), "FALSE");
            runSuite(client, tab, tab, suiteRow, layout.statusCol(), layout.lastRunCol(), layout.reportCol());
        }

        int dataStart = layout.headerRowIndex() + 1;
        for (int rowIndex = dataStart; rowIndex < rows.size(); rowIndex++) {
            List<Object> row = rows.get(rowIndex);
            String testCaseId = TestCaseSheetLayout.cell(row, layout.testCaseIdCol());
            if (testCaseId.isEmpty()) {
                break; // end of this tab's test case block
            }
            if (!"TRUE".equalsIgnoreCase(TestCaseSheetLayout.cell(row, layout.runCol()))) {
                continue;
            }
            client.updateCell(tab, rowIndex, layout.runCol(), "FALSE"); // reset immediately, acts like a button press
            runTestCase(client, tab, rowIndex, testCaseId, mappings, layout);
        }
    }

    private static void runTestCase(SheetsClient client, String tab, int rowIndex, String testCaseId,
                                     Map<String, TestCaseMapping> mappings, TestCaseSheetLayout layout) {
        TestCaseMapping mapping = mappings.get(TestCaseMapping.normalize(testCaseId));
        if (mapping == null || !mapping.enabled()) {
            client.updateCell(tab, rowIndex, layout.statusCol(), STATUS_NOT_AUTOMATED);
            return;
        }

        client.updateCell(tab, rowIndex, layout.statusCol(), STATUS_RUNNING);
        System.out.println("Running " + testCaseId + " (" + mapping.testClass() + "#" + mapping.testMethod() + ")...");

        String testFilter = mapping.testClass() + "#" + mapping.testMethod();
        String testCaseIdArg = mapping.usesTestCaseId() ? testCaseId : null;
        runAndRecord(client, tab, rowIndex, layout.statusCol(), layout.lastRunCol(), layout.reportCol(),
                testFilter, testCaseIdArg);
    }

    private static void processMasterSheet(SheetsClient client) {
        List<List<Object>> rows = client.readRange("'" + MasterSheetLayout.SHEET_NAME + "'!A1:E500");
        Optional<MasterSheetLayout> layoutOpt = MasterSheetLayout.locate(rows);
        if (layoutOpt.isEmpty()) {
            return; // dashboard hasn't been wired up yet - run SheetSetupTool first
        }
        MasterSheetLayout layout = layoutOpt.get();
        if (layout.runCol() < 0 || layout.statusCol() < 0) {
            return;
        }

        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<Object> row = rows.get(rowIndex);
            String tabName = TestCaseSheetLayout.cell(row, layout.tabNameCol());
            if (tabName.isEmpty() || !"TRUE".equalsIgnoreCase(TestCaseSheetLayout.cell(row, layout.runCol()))) {
                continue;
            }
            client.updateCell(MasterSheetLayout.SHEET_NAME, rowIndex, layout.runCol(), "FALSE");
            runSuite(client, tabName, MasterSheetLayout.SHEET_NAME, rowIndex,
                    layout.statusCol(), layout.lastRunCol(), layout.reportCol());
        }
    }

    /**
     * Runs the whole test class configured for suiteLookupTab (sheet.suiteClass.<suiteLookupTab>)
     * and writes the result into targetSheet/targetRow - which may be suiteLookupTab's own tab
     * (the per-tab "RUN ENTIRE SUITE" row) or the "Test Suite Automate" dashboard (one row per
     * tab, results recorded there instead of on the tab itself).
     */
    private static void runSuite(SheetsClient client, String suiteLookupTab, String targetSheet, int targetRow,
                                  int statusCol, int lastRunCol, int reportCol) {
        String suiteClass;
        try {
            suiteClass = ConfigReader.get("sheet.suiteClass." + suiteLookupTab);
        } catch (RuntimeException e) {
            client.updateCell(targetSheet, targetRow, statusCol, STATUS_NOT_CONFIGURED);
            return;
        }

        client.updateCell(targetSheet, targetRow, statusCol, STATUS_RUNNING);
        System.out.println("Running entire suite for tab \"" + suiteLookupTab + "\" (" + suiteClass + ")...");

        runAndRecord(client, targetSheet, targetRow, statusCol, lastRunCol, reportCol, suiteClass, null);
    }

    private static void runAndRecord(SheetsClient client, String targetSheet, int targetRow, int statusCol,
                                      int lastRunCol, int reportCol, String testFilter, String testCaseId) {
        long startTime = System.currentTimeMillis();
        boolean passed;
        try {
            passed = runMaven(testFilter, testCaseId);
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to run " + testFilter + ": " + e.getMessage());
            passed = false;
        }

        String reportFileName = findLatestReport(new File("reports"), startTime).orElse(null);

        if (lastRunCol >= 0) {
            client.updateCell(targetSheet, targetRow, lastRunCol, LocalDateTime.now().format(TIMESTAMP));
        }
        if (reportCol >= 0) {
            client.updateCell(targetSheet, targetRow, reportCol, reportLinkFormula(reportFileName));
        }
        client.updateCell(targetSheet, targetRow, statusCol, passed ? STATUS_PASSED : STATUS_FAILED);
    }

    private static boolean runMaven(String testFilter, String testCaseId) throws IOException, InterruptedException {
        String mvnCommand = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        List<String> command = new ArrayList<>(List.of(mvnCommand, "test", "-Dtest=" + testFilter));
        if (testCaseId != null) {
            command.add("-DtestCaseId=" + testCaseId);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(new File(System.getProperty("user.dir")));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process process = builder.start();
        return process.waitFor() == 0;
    }

    private static Optional<String> findLatestReport(File reportsDir, long afterTimeMillis) {
        File[] files = reportsDir.listFiles((dir, name) -> name.startsWith("ExtentReport_") && name.endsWith(".html"));
        if (files == null) {
            return Optional.empty();
        }
        File latest = null;
        for (File file : files) {
            if (file.lastModified() >= afterTimeMillis - 2000 && (latest == null || file.lastModified() > latest.lastModified())) {
                latest = file;
            }
        }
        return Optional.ofNullable(latest).map(File::getName);
    }

    /**
     * A Sheets HYPERLINK() formula opening the report via the local ReportFileServer (started in
     * main()) rather than a file:// path - browsers commonly block navigating from an https:
     * page (like Google Sheets) to a file: URL when a link is clicked, but allow plain http
     * navigation freely.
     */
    private static String reportLinkFormula(String reportFileName) {
        if (reportFileName == null) {
            return "(no report found)";
        }
        return "=HYPERLINK(\"" + reportServer.urlFor(reportFileName) + "\", \"Open Report\")";
    }
}
