package com.platform.automation;

import java.util.List;
import java.util.Optional;

/**
 * Locates the real "TEST CASE ID / TEST SCENARIO / ..." header row within a tab, which sits
 * below a variable-height project/module summary banner (Project Name, Module Name, Version,
 * ...) rather than on row 1, and resolves the automation columns relative to it.
 */
record TestCaseSheetLayout(int headerRowIndex, int testCaseIdCol, int runCol, int statusCol, int lastRunCol,
                            int reportCol) {

    static final String COL_TEST_CASE_ID = "TEST CASE ID";
    static final String COL_RUN = "RUN";
    static final String COL_STATUS = "AUTOMATION STATUS";
    static final String COL_LAST_RUN = "LAST RUN";
    static final String COL_REPORT = "REPORT";
    static final String SUITE_RUN_LABEL = "RUN ENTIRE SUITE";

    /**
     * The blank banner row directly above the header, reused (same RUN/STATUS/LAST RUN/REPORT
     * columns) as a single whole-suite run control, separate from the per-test-case rows below
     * the header. -1 if the header is on row 1 and there's no banner row to use.
     */
    int suiteRowIndex() {
        return headerRowIndex - 1;
    }

    static Optional<TestCaseSheetLayout> locate(List<List<Object>> rows) {
        for (int r = 0; r < rows.size(); r++) {
            List<Object> row = rows.get(r);
            int testCaseIdCol = indexOf(row, COL_TEST_CASE_ID);
            if (testCaseIdCol >= 0) {
                return Optional.of(new TestCaseSheetLayout(
                        r,
                        testCaseIdCol,
                        indexOf(row, COL_RUN),
                        indexOf(row, COL_STATUS),
                        indexOf(row, COL_LAST_RUN),
                        indexOf(row, COL_REPORT)));
            }
        }
        return Optional.empty();
    }

    static int indexOf(List<Object> row, String columnName) {
        for (int i = 0; i < row.size(); i++) {
            if (columnName.equalsIgnoreCase(String.valueOf(row.get(i)).trim())) {
                return i;
            }
        }
        return -1;
    }

    static String cell(List<Object> row, int index) {
        if (index < 0 || index >= row.size() || row.get(index) == null) {
            return "";
        }
        return String.valueOf(row.get(index)).trim();
    }
}
