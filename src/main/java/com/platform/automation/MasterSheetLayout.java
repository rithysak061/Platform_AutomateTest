package com.platform.automation;

import java.util.List;
import java.util.Optional;

/**
 * Layout of the "Test Suite Automate" dashboard tab: one row per test-case tab found in the
 * spreadsheet, each with its own RUN checkbox that runs that tab's whole suite (looked up via
 * sheet.suiteClass.<TabName>, the same config used by the per-tab "RUN ENTIRE SUITE" row).
 * Unlike TestCaseSheetLayout, there's no summary banner here - the header is always row 1, since
 * this tab is entirely managed by SheetSetupTool/SheetRunnerAgent rather than hand-authored.
 */
record MasterSheetLayout(int tabNameCol, int runCol, int statusCol, int lastRunCol, int reportCol) {

    static final String SHEET_NAME = "Test Suite Automate";
    static final String COL_TAB_NAME = "TAB NAME";

    static Optional<MasterSheetLayout> locate(List<List<Object>> rows) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<Object> header = rows.get(0);
        int tabNameCol = TestCaseSheetLayout.indexOf(header, COL_TAB_NAME);
        if (tabNameCol < 0) {
            return Optional.empty();
        }
        return Optional.of(new MasterSheetLayout(
                tabNameCol,
                TestCaseSheetLayout.indexOf(header, TestCaseSheetLayout.COL_RUN),
                TestCaseSheetLayout.indexOf(header, TestCaseSheetLayout.COL_STATUS),
                TestCaseSheetLayout.indexOf(header, TestCaseSheetLayout.COL_LAST_RUN),
                TestCaseSheetLayout.indexOf(header, TestCaseSheetLayout.COL_REPORT)));
    }
}
