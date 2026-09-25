package com.platform.automation;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.AddSheetRequest;
import com.google.api.services.sheets.v4.model.AppendDimensionRequest;
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest;
import com.google.api.services.sheets.v4.model.BooleanCondition;
import com.google.api.services.sheets.v4.model.CellData;
import com.google.api.services.sheets.v4.model.CellFormat;
import com.google.api.services.sheets.v4.model.DataValidationRule;
import com.google.api.services.sheets.v4.model.GridRange;
import com.google.api.services.sheets.v4.model.RepeatCellRequest;
import com.google.api.services.sheets.v4.model.Request;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.SheetProperties;
import com.google.api.services.sheets.v4.model.SetDataValidationRequest;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Google Sheets API for reading test-case rows and writing run
 * status/results back into the spreadsheet, authenticated as a service account.
 */
public class SheetsClient {

    private final Sheets service;
    private final String spreadsheetId;

    public SheetsClient(String spreadsheetId, String credentialsPath) {
        this.spreadsheetId = spreadsheetId;
        try {
            GoogleCredentials credentials;
            try (FileInputStream in = new FileInputStream(credentialsPath)) {
                credentials = GoogleCredentials.fromStream(in)
                        .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));
            }
            this.service = new Sheets.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials))
                    .setApplicationName("Platform_AutomateTest Sheet Runner")
                    .build();
        } catch (IOException | GeneralSecurityException e) {
            throw new RuntimeException("Unable to initialize Google Sheets client using credentials at "
                    + credentialsPath, e);
        }
    }

    public List<String> listSheetTitles() {
        try {
            Spreadsheet spreadsheet = service.spreadsheets().get(spreadsheetId).execute();
            return spreadsheet.getSheets().stream()
                    .map(sheet -> sheet.getProperties().getTitle())
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Unable to list tabs of spreadsheet " + spreadsheetId, e);
        }
    }

    public List<List<Object>> readSheet(String sheetTitle) {
        return readRange("'" + sheetTitle + "'");
    }

    public List<List<Object>> readRange(String a1Range) {
        try {
            ValueRange response = service.spreadsheets().values()
                    .get(spreadsheetId, a1Range)
                    .execute();
            List<List<Object>> values = response.getValues();
            return values == null ? Collections.emptyList() : values;
        } catch (IOException e) {
            throw new RuntimeException("Unable to read range: " + a1Range, e);
        }
    }

    /** Like readRange, but returns the underlying formula text (e.g. "=HYPERLINK(...)") instead of its rendered display value. */
    public List<List<Object>> readRangeAsFormula(String a1Range) {
        try {
            ValueRange response = service.spreadsheets().values()
                    .get(spreadsheetId, a1Range)
                    .setValueRenderOption("FORMULA")
                    .execute();
            List<List<Object>> values = response.getValues();
            return values == null ? Collections.emptyList() : values;
        } catch (IOException e) {
            throw new RuntimeException("Unable to read range: " + a1Range, e);
        }
    }

    /**
     * Reads several ranges (each possibly on a different tab) in one API call, counting as a
     * single request against the Sheets read quota regardless of how many ranges are included -
     * essential for scanning every tab in a large spreadsheet without hitting the
     * 60-reads/minute-per-user quota that a per-tab loop would blow through.
     */
    public List<List<List<Object>>> batchReadRanges(List<String> a1Ranges) {
        if (a1Ranges.isEmpty()) {
            return List.of();
        }
        try {
            var response = service.spreadsheets().values()
                    .batchGet(spreadsheetId)
                    .setRanges(a1Ranges)
                    .execute();
            return response.getValueRanges().stream()
                    .map(vr -> vr.getValues() == null ? List.<List<Object>>of() : vr.getValues())
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Unable to batch-read ranges", e);
        }
    }

    public void createSheetIfMissing(String title) {
        if (sheetIdsByTitle().containsKey(title)) {
            return;
        }
        Request request = new Request().setAddSheet(new AddSheetRequest()
                .setProperties(new SheetProperties().setTitle(title)));
        try {
            service.spreadsheets()
                    .batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(List.of(request)))
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Unable to create sheet tab: " + title, e);
        }
    }

    public Map<String, Integer> sheetIdsByTitle() {
        try {
            Spreadsheet spreadsheet = service.spreadsheets().get(spreadsheetId).execute();
            Map<String, Integer> ids = new LinkedHashMap<>();
            spreadsheet.getSheets().forEach(sheet -> ids.put(sheet.getProperties().getTitle(), sheet.getProperties().getSheetId()));
            return ids;
        } catch (IOException e) {
            throw new RuntimeException("Unable to read tab ids of spreadsheet " + spreadsheetId, e);
        }
    }

    /**
     * Grows a tab's grid to at least the given number of columns if it's currently smaller.
     * Sheets tabs are trimmed to their used size, so writing a value.update beyond the current
     * grid bounds fails with a 400 instead of auto-expanding - call this first.
     */
    public void ensureMinimumColumns(String sheetTitle, int minColumnCount) {
        try {
            Spreadsheet spreadsheet = service.spreadsheets().get(spreadsheetId).execute();
            for (Sheet sheet : spreadsheet.getSheets()) {
                if (!sheet.getProperties().getTitle().equals(sheetTitle)) {
                    continue;
                }
                int currentColumns = sheet.getProperties().getGridProperties().getColumnCount();
                if (currentColumns >= minColumnCount) {
                    return;
                }
                Request request = new Request().setAppendDimension(new AppendDimensionRequest()
                        .setSheetId(sheet.getProperties().getSheetId())
                        .setDimension("COLUMNS")
                        .setLength(minColumnCount - currentColumns));
                service.spreadsheets()
                        .batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(List.of(request)))
                        .execute();
                return;
            }
            throw new RuntimeException("No such sheet tab: " + sheetTitle);
        } catch (IOException e) {
            throw new RuntimeException("Unable to grow tab: " + sheetTitle, e);
        }
    }

    /**
     * Turns every cell in the given row range (0-based, end exclusive) of one column into a
     * checkbox, overwriting any existing data validation there.
     */
    public void insertCheckboxes(String sheetTitle, int startRowIndex, int endRowIndexExclusive, int columnIndex) {
        Integer sheetId = sheetIdsByTitle().get(sheetTitle);
        if (sheetId == null) {
            throw new RuntimeException("No such sheet tab: " + sheetTitle);
        }
        Request request = checkboxRequest(sheetId, startRowIndex, endRowIndexExclusive, columnIndex);
        try {
            service.spreadsheets()
                    .batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(List.of(request)))
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Unable to insert checkboxes on tab: " + sheetTitle, e);
        }
    }

    /**
     * Same as {@link #insertCheckboxes}, but only on the given (not necessarily contiguous) row
     * indices - e.g. the subset of a tab's test-case rows that are actually automated in Java, so
     * a RUN checkbox only appears where checking it would do something. Contiguous runs of rows
     * are coalesced into a single range each, and all of them are sent in one batchUpdate call to
     * stay within the Sheets API's write-request budget regardless of how many rows match.
     */
    public void insertCheckboxesForRows(String sheetTitle, List<Integer> rowIndices, int columnIndex) {
        if (rowIndices.isEmpty()) {
            return;
        }
        Integer sheetId = sheetIdsByTitle().get(sheetTitle);
        if (sheetId == null) {
            throw new RuntimeException("No such sheet tab: " + sheetTitle);
        }
        List<Request> requests = new ArrayList<>();
        for (int[] range : toContiguousRanges(rowIndices)) {
            requests.add(checkboxRequest(sheetId, range[0], range[1], columnIndex));
        }
        try {
            service.spreadsheets()
                    .batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(requests))
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Unable to insert checkboxes on tab: " + sheetTitle, e);
        }
    }

    /**
     * Removes any data validation (in particular, a RUN checkbox) from every cell in the given
     * row range of one column, leaving the cells as plain empty cells. Used to walk back a
     * checkbox that was previously added to a row which has since turned out to have no Java
     * automation behind it, so RUN checkboxes only ever appear where checking one would do
     * something.
     */
    public void clearCheckboxes(String sheetTitle, int startRowIndex, int endRowIndexExclusive, int columnIndex) {
        Integer sheetId = sheetIdsByTitle().get(sheetTitle);
        if (sheetId == null) {
            throw new RuntimeException("No such sheet tab: " + sheetTitle);
        }
        GridRange range = new GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(startRowIndex)
                .setEndRowIndex(endRowIndexExclusive)
                .setStartColumnIndex(columnIndex)
                .setEndColumnIndex(columnIndex + 1);
        Request request = new Request().setSetDataValidation(new SetDataValidationRequest().setRange(range));
        try {
            service.spreadsheets()
                    .batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(List.of(request)))
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Unable to clear checkboxes on tab: " + sheetTitle, e);
        }
    }

    private Request checkboxRequest(int sheetId, int startRowIndex, int endRowIndexExclusive, int columnIndex) {
        GridRange range = new GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(startRowIndex)
                .setEndRowIndex(endRowIndexExclusive)
                .setStartColumnIndex(columnIndex)
                .setEndColumnIndex(columnIndex + 1);
        DataValidationRule rule = new DataValidationRule()
                .setCondition(new BooleanCondition().setType("BOOLEAN"))
                .setStrict(true)
                .setShowCustomUi(true);
        return new Request().setSetDataValidation(new SetDataValidationRequest().setRange(range).setRule(rule));
    }

    /**
     * Groups sorted-or-not row indices into [start, endExclusive) ranges of consecutive rows, so
     * a scattered set of matching rows can be expressed as a handful of GridRanges instead of one
     * API request per row.
     */
    private static List<int[]> toContiguousRanges(List<Integer> rowIndices) {
        List<Integer> sorted = new ArrayList<>(rowIndices);
        Collections.sort(sorted);
        List<int[]> ranges = new ArrayList<>();
        int start = -1;
        int prev = -1;
        for (int row : sorted) {
            if (start == -1) {
                start = row;
            } else if (row != prev + 1) {
                ranges.add(new int[]{start, prev + 1});
                start = row;
            }
            prev = row;
        }
        if (start != -1) {
            ranges.add(new int[]{start, prev + 1});
        }
        return ranges;
    }

    /**
     * Clears any explicit number format (in particular "Plain text") on the given cell range so
     * a =HYPERLINK(...) written there afterward renders as a real clickable link instead of
     * literal text. Once a cell/column has been set to Plain text - which Sheets can do on its
     * own after repeatedly receiving text-looking values - it keeps displaying even a
     * subsequently entered formula as its literal source text rather than evaluating it.
     */
    public void clearNumberFormat(String sheetTitle, int startRowIndex, int endRowIndexExclusive, int columnIndex) {
        Integer sheetId = sheetIdsByTitle().get(sheetTitle);
        if (sheetId == null) {
            throw new RuntimeException("No such sheet tab: " + sheetTitle);
        }
        Request request = clearNumberFormatRequest(sheetId, startRowIndex, endRowIndexExclusive, columnIndex);
        try {
            service.spreadsheets()
                    .batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(List.of(request)))
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Unable to clear number format on tab: " + sheetTitle, e);
        }
    }

    /** Same as {@link #clearNumberFormat}, but only on the given (not necessarily contiguous) row
     *  indices - see {@link #insertCheckboxesForRows} for why. */
    public void clearNumberFormatForRows(String sheetTitle, List<Integer> rowIndices, int columnIndex) {
        if (rowIndices.isEmpty()) {
            return;
        }
        Integer sheetId = sheetIdsByTitle().get(sheetTitle);
        if (sheetId == null) {
            throw new RuntimeException("No such sheet tab: " + sheetTitle);
        }
        List<Request> requests = new ArrayList<>();
        for (int[] range : toContiguousRanges(rowIndices)) {
            requests.add(clearNumberFormatRequest(sheetId, range[0], range[1], columnIndex));
        }
        try {
            service.spreadsheets()
                    .batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(requests))
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Unable to clear number format on tab: " + sheetTitle, e);
        }
    }

    private Request clearNumberFormatRequest(int sheetId, int startRowIndex, int endRowIndexExclusive, int columnIndex) {
        GridRange range = new GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(startRowIndex)
                .setEndRowIndex(endRowIndexExclusive)
                .setStartColumnIndex(columnIndex)
                .setEndColumnIndex(columnIndex + 1);
        return new Request().setRepeatCell(new RepeatCellRequest()
                .setRange(range)
                .setCell(new CellData().setUserEnteredFormat(new CellFormat()))
                .setFields("userEnteredFormat.numberFormat"));
    }

    public void updateCell(String sheetTitle, int rowIndex, int columnIndex, String value) {
        String range = "'" + sheetTitle + "'!" + toColumnLetter(columnIndex) + (rowIndex + 1);
        ValueRange body = new ValueRange().setValues(List.of(List.of(value)));
        try {
            service.spreadsheets().values()
                    .update(spreadsheetId, range, body)
                    .setValueInputOption("USER_ENTERED")
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Unable to update cell " + range, e);
        }
    }

    /**
     * Removes any cell merges within the given range. Writing to a non-anchor cell of a merged
     * region is silently ignored by the Sheets API (the write succeeds but the value never shows
     * up), so this is worth calling before writing into a range that might carry leftover merge
     * formatting from an earlier version of the tab - a values.get read reports 0 rows for a
     * sheet with no cell values, but doesn't reveal that kind of leftover cell formatting.
     */
    public void unmergeCells(String sheetTitle, int startRowIndex, int endRowIndexExclusive,
                              int startColumnIndex, int endColumnIndexExclusive) {
        Integer sheetId = sheetIdsByTitle().get(sheetTitle);
        if (sheetId == null) {
            throw new RuntimeException("No such sheet tab: " + sheetTitle);
        }
        GridRange range = new GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(startRowIndex)
                .setEndRowIndex(endRowIndexExclusive)
                .setStartColumnIndex(startColumnIndex)
                .setEndColumnIndex(endColumnIndexExclusive);
        Request request = new Request().setUnmergeCells(new com.google.api.services.sheets.v4.model.UnmergeCellsRequest().setRange(range));
        try {
            service.spreadsheets()
                    .batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(List.of(request)))
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Unable to unmerge cells on tab: " + sheetTitle, e);
        }
    }

    /**
     * Writes a whole 2D block of values starting at (startRowIndex, startColumnIndex) in a single
     * API call, instead of one updateCell call per cell - the latter blows through the Sheets
     * API's 60-writes/minute quota for anything beyond a couple dozen cells.
     */
    public void writeRange(String sheetTitle, int startRowIndex, int startColumnIndex, List<List<Object>> values) {
        if (values.isEmpty()) {
            return;
        }
        String startCell = toColumnLetter(startColumnIndex) + (startRowIndex + 1);
        String range = "'" + sheetTitle + "'!" + startCell;
        ValueRange body = new ValueRange().setValues(values);
        try {
            service.spreadsheets().values()
                    .update(spreadsheetId, range, body)
                    .setValueInputOption("USER_ENTERED")
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Unable to write range starting at " + range, e);
        }
    }

    private static String toColumnLetter(int columnIndex) {
        StringBuilder column = new StringBuilder();
        int n = columnIndex + 1;
        while (n > 0) {
            int remainder = (n - 1) % 26;
            column.insert(0, (char) ('A' + remainder));
            n = (n - 1) / 26;
        }
        return column.toString();
    }
}
