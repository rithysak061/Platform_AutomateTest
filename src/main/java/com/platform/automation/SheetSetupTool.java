package com.platform.automation;

import com.platform.utility.ConfigReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * One-off tool that wires up each tab listed in sheet.watchedTabs for Run-button automation:
 * finds that tab's real "TEST CASE ID" header row (which sits below a summary banner, not on
 * row 1), adds the RUN / AUTOMATION STATUS / LAST RUN / REPORT columns next to it if missing,
 * and turns the RUN column into checkboxes for every test case row. Safe to re-run - existing
 * columns and checkboxes are left as-is.
 *   mvn compile exec:java -Dexec.mainClass=com.platform.automation.SheetSetupTool
 */
public class SheetSetupTool {

    public static void main(String[] args) {
        String spreadsheetId = ConfigReader.get("sheet.spreadsheetId");
        String credentialsPath = ConfigReader.get("sheet.credentialsPath");
        SheetsClient client = new SheetsClient(spreadsheetId, credentialsPath);

        for (String tab : watchedTabs()) {
            setupTab(client, tab);
        }
    }

    static List<String> watchedTabs() {
        return Arrays.stream(ConfigReader.get("sheet.watchedTabs").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static void setupTab(SheetsClient client, String tab) {
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
        int dataEnd = dataStart;
        while (dataEnd < rows.size() && !TestCaseSheetLayout.cell(rows.get(dataEnd), layout.testCaseIdCol()).isEmpty()) {
            dataEnd++;
        }
        if (dataEnd > dataStart) {
            client.insertCheckboxes(tab, dataStart, dataEnd, runCol);
            // Without this, a =HYPERLINK(...) written into a cell that previously held a plain
            // filename can render as literal text instead of a clickable link - see
            // SheetsClient.clearNumberFormat.
            client.clearNumberFormat(tab, dataStart, dataEnd, reportCol);
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
                + ", " + (dataEnd - dataStart) + " test case row(s), suite-run checkbox on row "
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
}
