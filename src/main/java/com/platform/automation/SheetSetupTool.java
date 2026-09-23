package com.platform.automation;

import com.platform.utility.ConfigReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One-off tool that wires up each tab listed in sheet.watchedTabs for Run-button automation:
 * finds that tab's real "TEST CASE ID" header row (which sits below a summary banner, not on
 * row 1), adds the RUN / AUTOMATION STATUS / LAST RUN / REPORT columns next to it if missing,
 * and turns the RUN column into checkboxes only for the test case rows that are actually
 * automated in Java (per TestData/TestCaseAutomationMap.csv) - a checkbox on a row with no
 * automation behind it would just be dead UI. Safe to re-run: re-running after adding a new
 * automated test case adds that row's checkbox; re-running after removing one clears it. Also
 * (re)builds the "Test Suite Automate" dashboard tab listing every test-case tab in the whole
 * spreadsheet, regardless of sheet.watchedTabs.
 *   mvn compile exec:java -Dexec.mainClass=com.platform.automation.SheetSetupTool
 */
public class SheetSetupTool {

    public static void main(String[] args) {
        String spreadsheetId = ConfigReader.get("sheet.spreadsheetId");
        String credentialsPath = ConfigReader.get("sheet.credentialsPath");
        SheetsClient client = new SheetsClient(spreadsheetId, credentialsPath);
        Map<String, TestCaseMapping> mappings = TestCaseMapping.loadAll();

        for (String tab : watchedTabs()) {
            setupTab(client, tab, mappings);
        }

        setupMasterSheet(client);
    }

    static List<String> watchedTabs() {
        return Arrays.stream(ConfigReader.get("sheet.watchedTabs").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static void setupTab(SheetsClient client, String tab, Map<String, TestCaseMapping> mappings) {
        List<List<Object>> rows = client.readRange("'" + tab + "'!A1:Z300");
        Optional<TestCaseSheetLayout> layoutOpt = TestCaseSheetLayout.locate(rows);
        if (layoutOpt.isEmpty()) {
            System.out.println("Skipping \"" + tab + "\" - no \"" + TestCaseSheetLayout.COL_TEST_CASE_ID
                    + "\" header found.");
            return;
        }
        TestCaseSheetLayout layout = layoutOpt.get();
        List<Object> header = new ArrayList<>(rows.get(layout.headerRowIndex()));

        long missingColumns = List.of(TestCaseSheetLayout.COL_RUN, TestCaseSheetLayout.COL_STATUS,
                        TestCaseSheetLayout.COL_LAST_RUN, TestCaseSheetLayout.COL_REPORT).stream()
                .filter(name -> TestCaseSheetLayout.indexOf(header, name) < 0)
                .count();
        if (missingColumns > 0) {
            client.ensureMinimumColumns(tab, header.size() + (int) missingColumns);
        }

        int runCol = ensureColumn(client, tab, layout.headerRowIndex(), header, TestCaseSheetLayout.COL_RUN);
        ensureColumn(client, tab, layout.headerRowIndex(), header, TestCaseSheetLayout.COL_STATUS);
        ensureColumn(client, tab, layout.headerRowIndex(), header, TestCaseSheetLayout.COL_LAST_RUN);
        int reportCol = ensureColumn(client, tab, layout.headerRowIndex(), header, TestCaseSheetLayout.COL_REPORT);

        int dataStart = layout.headerRowIndex() + 1;
        // rows.size() already reflects the last row with any content in A1:Z (the Sheets API
        // trims trailing fully-empty rows from a range read), so it's a reliable end-of-block
        // marker even though a handful of individual rows in between - a currency-divider row,
        // a data row someone forgot to fill in a TEST CASE ID for - can have a blank ID cell.
        int dataEnd = Math.max(dataStart, rows.size());

        List<Integer> automatedRows = new ArrayList<>();
        for (int r = dataStart; r < dataEnd; r++) {
            String testCaseId = TestCaseSheetLayout.cell(rows.get(r), layout.testCaseIdCol());
            if (!testCaseId.isEmpty() && mappings.containsKey(TestCaseMapping.normalize(testCaseId))) {
                automatedRows.add(r);
            }
        }

        if (dataEnd > dataStart) {
            // Clear first so a row that used to be automated but no longer is (mapping removed,
            // or the tab re-scanned after a code change) loses its checkbox instead of keeping a
            // stale one, then re-add checkboxes only where there's a Java test behind them.
            client.clearCheckboxes(tab, dataStart, dataEnd, runCol);
            client.insertCheckboxesForRows(tab, automatedRows, runCol);
            // Without this, a =HYPERLINK(...) written into a cell that previously held a plain
            // filename can render as literal text instead of a clickable link - see
            // SheetsClient.clearNumberFormat.
            client.clearNumberFormatForRows(tab, automatedRows, reportCol);
        }

        int suiteRow = layout.suiteRowIndex();
        if (suiteRow >= 0) {
            String existingLabel = suiteRow < rows.size()
                    ? TestCaseSheetLayout.cell(rows.get(suiteRow), layout.testCaseIdCol())
                    : "";
            if (existingLabel.isEmpty()) {
                client.updateCell(tab, suiteRow, layout.testCaseIdCol(), TestCaseSheetLayout.SUITE_RUN_LABEL);
            }
            client.insertCheckboxes(tab, suiteRow, suiteRow + 1, runCol);
            client.clearNumberFormat(tab, suiteRow, suiteRow + 1, reportCol);
        }

        System.out.println("Wired up \"" + tab + "\": header on row " + (layout.headerRowIndex() + 1)
                + ", " + automatedRows.size() + " of " + (dataEnd - dataStart)
                + " test case row(s) automated, suite-run checkbox on row "
                + (suiteRow + 1) + ".");
    }

    private static int ensureColumn(SheetsClient client, String tab, int headerRowIndex, List<Object> header,
                                     String name) {
        int idx = TestCaseSheetLayout.indexOf(header, name);
        if (idx >= 0) {
            return idx;
        }
        idx = header.size();
        client.updateCell(tab, headerRowIndex, idx, name);
        header.add(name);
        return idx;
    }

    /**
     * Rebuilds the "Test Suite Automate" dashboard: one row per test-case tab found anywhere in
     * the spreadsheet (discovered by the same "TEST CASE ID" header detection used per-tab
     * above), each with a RUN checkbox that runs that tab's whole suite via
     * sheet.suiteClass.<TabName>. Discovery scans every tab in a single batched API call so it
     * stays well under the Sheets API's read quota even with 30+ tabs. Safe to re-run: existing
     * rows and their AUTOMATION STATUS/LAST RUN/REPORT history are left alone, only tabs not
     * already listed get added - this is what keeps the list "dynamic" without needing a script
     * pasted into the spreadsheet.
     */
    private static void setupMasterSheet(SheetsClient client) {
        client.createSheetIfMissing(MasterSheetLayout.SHEET_NAME);

        List<List<Object>> masterRows = client.readRange("'" + MasterSheetLayout.SHEET_NAME + "'!A1:E500");
        List<Object> header = masterRows.isEmpty() ? new ArrayList<>() : new ArrayList<>(masterRows.get(0));

        long missingColumns = List.of(MasterSheetLayout.COL_TAB_NAME, TestCaseSheetLayout.COL_RUN,
                        TestCaseSheetLayout.COL_STATUS, TestCaseSheetLayout.COL_LAST_RUN, TestCaseSheetLayout.COL_REPORT)
                .stream()
                .filter(name -> TestCaseSheetLayout.indexOf(header, name) < 0)
                .count();
        if (missingColumns > 0) {
            client.ensureMinimumColumns(MasterSheetLayout.SHEET_NAME, header.size() + (int) missingColumns);
        }

        int tabNameCol = ensureColumn(client, MasterSheetLayout.SHEET_NAME, 0, header, MasterSheetLayout.COL_TAB_NAME);
        int runCol = ensureColumn(client, MasterSheetLayout.SHEET_NAME, 0, header, TestCaseSheetLayout.COL_RUN);
        ensureColumn(client, MasterSheetLayout.SHEET_NAME, 0, header, TestCaseSheetLayout.COL_STATUS);
        ensureColumn(client, MasterSheetLayout.SHEET_NAME, 0, header, TestCaseSheetLayout.COL_LAST_RUN);
        int reportCol = ensureColumn(client, MasterSheetLayout.SHEET_NAME, 0, header, TestCaseSheetLayout.COL_REPORT);

        List<String> candidateTabs = client.listSheetTitles().stream()
                .filter(t -> !t.equals(MasterSheetLayout.SHEET_NAME))
                .toList();
        List<String> scanRanges = candidateTabs.stream().map(t -> "'" + t + "'!A1:Z15").toList();
        List<List<List<Object>>> scans = client.batchReadRanges(scanRanges);

        List<String> testCaseTabs = new ArrayList<>();
        for (int i = 0; i < candidateTabs.size(); i++) {
            if (TestCaseSheetLayout.locate(scans.get(i)).isPresent()) {
                testCaseTabs.add(candidateTabs.get(i));
            }
        }

        Set<String> alreadyListed = new HashSet<>();
        for (int r = 1; r < masterRows.size(); r++) {
            String name = TestCaseSheetLayout.cell(masterRows.get(r), tabNameCol);
            if (!name.isBlank()) {
                alreadyListed.add(name);
            }
        }

        int nextRow = Math.max(masterRows.size(), 1);
        int added = 0;
        for (String tab : testCaseTabs) {
            if (alreadyListed.contains(tab)) {
                continue;
            }
            client.updateCell(MasterSheetLayout.SHEET_NAME, nextRow, tabNameCol, tab);
            nextRow++;
            added++;
        }

        if (nextRow > 1) {
            client.insertCheckboxes(MasterSheetLayout.SHEET_NAME, 1, nextRow, runCol);
            client.clearNumberFormat(MasterSheetLayout.SHEET_NAME, 1, nextRow, reportCol);
        }

        System.out.println("Master sheet \"" + MasterSheetLayout.SHEET_NAME + "\": " + testCaseTabs.size()
                + " test-case tab(s) found, " + added + " newly listed.");
    }
}
