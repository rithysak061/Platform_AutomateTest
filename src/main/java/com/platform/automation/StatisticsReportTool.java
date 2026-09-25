package com.platform.automation;

import com.platform.utility.ConfigReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Scans every test-case tab in the spreadsheet (detected the same way SheetSetupTool's master
 * dashboard does - any tab with a "TEST CASE ID" header) and tallies, per tab: total test cases,
 * the sheet's own STATUS(PASS/FAIL) breakdown, and its Automation column breakdown (where that
 * column exists - some tabs, like Transaction Record, don't have one). Writes the result into
 * the "Statistics" tab as one row per suite plus a grand-total row.
 *   mvn compile exec:java -Dexec.mainClass=com.platform.automation.StatisticsReportTool
 */
public class StatisticsReportTool {

    private static final Pattern TEST_CASE_ID_PATTERN = Pattern.compile("(?i)^TC[_-].+");

    record SuiteStats(String tab, int total, int pass, int fail, int pending, int other,
                       boolean hasAutomationColumn, int automatedYes, int automatedNo) {
    }

    public static void main(String[] args) {
        SheetsClient client = new SheetsClient(ConfigReader.get("sheet.spreadsheetId"), ConfigReader.get("sheet.credentialsPath"));

        List<String> candidateTabs = client.listSheetTitles().stream()
                .filter(t -> !t.equals("Statistics") && !t.equals("Test Suite Automate"))
                .toList();
        List<String> ranges = candidateTabs.stream().map(t -> "'" + t + "'!A1:R500").toList();
        List<List<List<Object>>> allRows = client.batchReadRanges(ranges);

        List<SuiteStats> statsList = new ArrayList<>();
        for (int i = 0; i < candidateTabs.size(); i++) {
            computeStats(candidateTabs.get(i), allRows.get(i)).ifPresent(statsList::add);
        }

        int grandTotal = 0, grandPass = 0, grandFail = 0, grandPending = 0, grandOther = 0,
                grandAutomatedYes = 0, grandAutomatedNo = 0;
        System.out.printf("%-35s %8s %6s %6s %8s %8s %10s %10s%n",
                "Tab", "Total", "Pass", "Fail", "Pending", "Other", "AutoYes", "AutoNo");
        for (SuiteStats s : statsList) {
            System.out.printf("%-35s %8d %6d %6d %8d %8d %10s %10s%n",
                    s.tab(), s.total(), s.pass(), s.fail(), s.pending(), s.other(),
                    s.hasAutomationColumn() ? String.valueOf(s.automatedYes()) : "N/A",
                    s.hasAutomationColumn() ? String.valueOf(s.automatedNo()) : "N/A");
            grandTotal += s.total();
            grandPass += s.pass();
            grandFail += s.fail();
            grandPending += s.pending();
            grandOther += s.other();
            grandAutomatedYes += s.automatedYes();
            grandAutomatedNo += s.automatedNo();
        }
        System.out.println("=== GRAND TOTAL ===");
        System.out.println("Total test cases: " + grandTotal);
        System.out.println("Pass: " + grandPass + " Fail: " + grandFail + " Pending: " + grandPending + " Other: " + grandOther);
        System.out.println("Automation Yes: " + grandAutomatedYes + " Automation No: " + grandAutomatedNo);
        System.out.println("Tabs counted: " + statsList.size() + " of " + candidateTabs.size() + " scanned");

        writeReport(client, statsList, grandTotal, grandPass, grandFail, grandPending, grandOther, grandAutomatedYes, grandAutomatedNo);
    }

    private static Optional<SuiteStats> computeStats(String tab, List<List<Object>> rows) {
        int headerRow = -1;
        for (int r = 0; r < rows.size(); r++) {
            if (indexOf(rows.get(r), "TEST CASE ID") >= 0) {
                headerRow = r;
                break;
            }
        }
        if (headerRow < 0) {
            return Optional.empty();
        }
        List<Object> header = rows.get(headerRow);
        int idCol = indexOf(header, "TEST CASE ID");
        int statusCol = indexOfContains(header, "STATUS");
        int automationCol = indexOfContains(header, "AUTOMAT");

        int total = 0, pass = 0, fail = 0, pending = 0, other = 0, automatedYes = 0, automatedNo = 0;
        for (int r = headerRow + 1; r < rows.size(); r++) {
            String id = cell(rows.get(r), idCol);
            if (!TEST_CASE_ID_PATTERN.matcher(id).matches()) {
                continue;
            }
            total++;

            String status = statusCol >= 0 ? cell(rows.get(r), statusCol).toUpperCase() : "";
            if (status.startsWith("PASS")) {
                pass++;
            } else if (status.startsWith("FAIL")) {
                fail++;
            } else if (status.startsWith("PEND") || status.isEmpty()) {
                pending++;
            } else {
                other++;
            }

            if (automationCol >= 0) {
                String automation = cell(rows.get(r), automationCol).trim().toUpperCase();
                if (automation.equals("TRUE") || automation.equals("YES")) {
                    automatedYes++;
                } else {
                    automatedNo++;
                }
            }
        }
        if (total == 0) {
            return Optional.empty();
        }
        return Optional.of(new SuiteStats(tab, total, pass, fail, pending, other, automationCol >= 0, automatedYes, automatedNo));
    }

    private static void writeReport(SheetsClient client, List<SuiteStats> statsList, int grandTotal, int grandPass,
                                     int grandFail, int grandPending, int grandOther, int grandAutomatedYes, int grandAutomatedNo) {
        client.createSheetIfMissing("Statistics");
        client.ensureMinimumColumns("Statistics", 9);
        // Clears any leftover merged-cell regions from an earlier version of this tab - writing
        // into a non-anchor cell of a merge is silently ignored, which otherwise leaves gaps in
        // the report despite every write call reporting success.
        client.unmergeCells("Statistics", 0, statsList.size() + 2, 0, 9);

        List<List<Object>> values = new ArrayList<>();
        values.add(List.of("Test Suite", "Total Test Cases", "Pass", "Fail", "Pending", "Other",
                "Automation: Yes", "Automation: No", "Automation %"));

        for (SuiteStats s : statsList) {
            String pct = s.hasAutomationColumn() && s.total() > 0
                    ? String.format("%.1f%%", 100.0 * s.automatedYes() / s.total())
                    : "N/A";
            values.add(List.of(
                    s.tab(),
                    s.total(),
                    s.pass(),
                    s.fail(),
                    s.pending(),
                    s.other(),
                    s.hasAutomationColumn() ? String.valueOf(s.automatedYes()) : "N/A",
                    s.hasAutomationColumn() ? String.valueOf(s.automatedNo()) : "N/A",
                    pct));
        }

        values.add(List.of("TOTAL", grandTotal, grandPass, grandFail, grandPending, grandOther,
                grandAutomatedYes, grandAutomatedNo, String.format("%.1f%%", 100.0 * grandAutomatedYes / grandTotal)));

        // A single call for the whole block, instead of one updateCell per cell - the latter
        // blows through the Sheets API's 60-writes/minute quota well before finishing a report
        // this size (over 300 cells).
        client.writeRange("Statistics", 0, 0, values);

        System.out.println("Wrote " + statsList.size() + " suite row(s) + 1 total row to \"Statistics\".");
    }

    private static int indexOf(List<Object> row, String name) {
        for (int i = 0; i < row.size(); i++) {
            if (name.equalsIgnoreCase(String.valueOf(row.get(i)).trim())) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfContains(List<Object> row, String needle) {
        for (int i = 0; i < row.size(); i++) {
            if (row.get(i) != null && String.valueOf(row.get(i)).toUpperCase().contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static String cell(List<Object> row, int index) {
        if (index < 0 || index >= row.size() || row.get(index) == null) {
            return "";
        }
        return String.valueOf(row.get(index)).trim();
    }
}
