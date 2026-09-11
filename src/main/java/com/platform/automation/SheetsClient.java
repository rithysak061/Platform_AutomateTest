package com.platform.automation;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
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
import com.google.api.services.sheets.v4.model.SetDataValidationRequest;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
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
        Request request = new Request().setSetDataValidation(new SetDataValidationRequest().setRange(range).setRule(rule));
        try {
            service.spreadsheets()
                    .batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(List.of(request)))
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Unable to insert checkboxes on tab: " + sheetTitle, e);
        }
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
        GridRange range = new GridRange()
                .setSheetId(sheetId)
                .setStartRowIndex(startRowIndex)
                .setEndRowIndex(endRowIndexExclusive)
                .setStartColumnIndex(columnIndex)
                .setEndColumnIndex(columnIndex + 1);
        Request request = new Request().setRepeatCell(new RepeatCellRequest()
                .setRange(range)
                .setCell(new CellData().setUserEnteredFormat(new CellFormat()))
                .setFields("userEnteredFormat.numberFormat"));
        try {
            service.spreadsheets()
                    .batchUpdate(spreadsheetId, new BatchUpdateSpreadsheetRequest().setRequests(List.of(request)))
                    .execute();
        } catch (IOException e) {
            throw new RuntimeException("Unable to clear number format on tab: " + sheetTitle, e);
        }
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
