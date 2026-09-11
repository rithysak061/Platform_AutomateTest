# Running the Tests

All commands are run from the project root (`d:\Platform_AutomateTest`) and require `JAVA_HOME`
pointed at JDK 21.

## 1. Run all tests

Runs the full suite defined in `src/test/resources/testng.xml`.

```
mvn test
```

## 2. Run a specific test file (class)

```
mvn test -Dtest=LoginTest
```

Runs every `@Test` method in `LoginTest` - each one is its own self-contained test case with its
data hardcoded directly in the method (`verifyValidLogin`, `verifyInvalidUsername`,
`verifyInvalidPassword`, `verifyAccountBlockedAfter3InvalidPasswords`,
`verifyInvalidVerificationCode`, `verifyUsernameWithSpecialCharacters`,
`verifyLoginWithUnactivatedAccount`, etc.).

## 3. Run a specific test case

Every test case is its own dedicated method, so run it directly by method name:

```
mvn test -Dtest=LoginTest#verifyValidLogin                    # TC_LOGIN_001
mvn test -Dtest=LoginTest#verifyInvalidUsername                # TC_LOGIN_002
mvn test -Dtest=LoginTest#verifyInvalidPassword                # TC_LOGIN_003
mvn test -Dtest=LoginTest#verifyAccountBlockedAfter3InvalidPasswords  # TC_LOGIN_004
mvn test -Dtest=LoginTest#verifyInvalidVerificationCode        # TC_LOGIN_005
mvn test -Dtest=LoginTest#verifyUsernameWithSpecialCharacters   # TC_LOGIN_007
mvn test -Dtest=LoginTest#verifyLoginWithUnactivatedAccount     # TC_LOGIN_009
```

## Headless vs. headed mode

The browser mode is controlled by `headless` in `Config/config.properties` (currently `false` =
headed). Override it for a single run without editing the file:

```
mvn test -Dheadless=true
```

```
mvn test -Dheadless=false
```

This can be combined with any of the commands above, e.g. run one test case headless:

```
mvn test -Dtest=LoginTest#verifyValidLogin -Dheadless=true
```

## Report

Every run generates an ExtentReports HTML report at `reports/ExtentReport_<timestamp>.html`,
listing each test with its logged steps, a screenshot of the final state (pass or fail), and -
for a failure - the login API URL, request payload and response body that were captured at the
moment of failure.

## Notes

- `-Dheadless` is an optional system-property override; any config key in
  `Config/config.properties` can be overridden the same way (e.g. `-Dbrowser=firefox`).
- TC_LOGIN_006 and TC_LOGIN_008 are disabled (`enabled = false`) in `LoginTest.java` by design -
  see the Javadoc on those methods for why - and won't run even when targeted directly unless you
  remove `enabled = false` first.

## Running a test case from the spreadsheet ("Run" button)

The [test case tracking spreadsheet](https://docs.google.com/spreadsheets/d/1amCnHIC-9VY44ClOR4hT4AEV3tSjl7dvp_mgnCC2_L0)
has one tab per feature (`Login`, `Register`, `Deposit`, `Home page`, ...). Each tab starts with a
project/module summary banner, then a `TEST CASE ID / TEST SCENARIO / ... / Automation` header
row, then one row per test case (e.g. `TC_LOGIN_001`-`TC_LOGIN_009` on the `Login` tab). Each test
case row gets a `RUN` checkbox that runs that one test case locally and writes the result back.
It works via a small local agent that polls the sheet directly through the Google Sheets API -
nothing is pasted into the spreadsheet itself (no Apps Script); the agent both detects the
checkbox and writes the result back on its own.

Only tabs listed in `sheet.watchedTabs` (`Config/config.properties`) are touched - currently just
`Login`, since that's the only tab with an automated TestNG class behind it. The spreadsheet has
30+ other tabs; polling all of them would blow through the Sheets API's 60-reads/minute quota, so
add a tab there only once it actually has automated tests.

### One-time setup

1. **Google Cloud service account** (the one step that needs your Google account - nobody else
   can do this part for you):
   - In [Google Cloud Console](https://console.cloud.google.com/), create or pick a project and
     enable the **Google Sheets API**.
   - Create a **Service Account**, then create a JSON key for it and download it.
   - Save that key as `Config/service-account.json` (already gitignored - never commit it).
   - Open the JSON key and copy its `client_email` value, then share the spreadsheet with that
     email address as an **Editor** (Share button, top-right of the sheet).
2. **Config**: `Config/config.properties` already has `sheet.spreadsheetId`,
   `sheet.credentialsPath`, `sheet.pollIntervalSeconds`, `sheet.watchedTabs`,
   `sheet.suiteClass.Login` and `sheet.reportServerPort` pointing at this spreadsheet,
   `Config/service-account.json`, the `Login` tab, `LoginTest` and port `8787` - adjust only if
   you used a different key file path, want a different poll interval, are wiring up another tab
   (add its own `sheet.suiteClass.<TabName>` too), or `8787` is already taken on your machine.
3. **Wire up the tab(s)**: once the key exists and the sheet is shared, run:
   ```
   mvn compile exec:java -Dexec.mainClass=com.platform.automation.SheetSetupTool
   ```
   For each tab in `sheet.watchedTabs`, this finds its real `TEST CASE ID` header row (it's below
   the summary banner, not row 1), adds `RUN` (a checkbox), `AUTOMATION STATUS`, `LAST RUN` and
   `REPORT` columns next to it, turns `RUN` into a checkbox for every test case row, and adds one
   more `RUN` checkbox labeled `RUN ENTIRE SUITE` on the blank banner row directly above the
   header - none of this touches existing manual columns. It also grows the tab's grid if needed,
   since Sheets tabs are trimmed to their used size and won't auto-expand on a plain write. Safe
   to re-run any time (e.g. after adding new test-case rows); it only fills in what's missing.

### Running the agent

Start the agent from the project root and leave it running while you use the sheet:

```
mvn compile exec:java -Dexec.mainClass=com.platform.automation.SheetRunnerAgent
```

Checking a `RUN` box is picked up within one poll interval: the agent unchecks the box
immediately (so it behaves like a button, not a toggle) and sets `AUTOMATION STATUS` to
`RUNNING`.

- On a **test-case row**, it runs the matching `mvn test` command (the same ones documented
  above) for just that one test case. A test case with no automation behind it yet - or one
  that's `enabled = false` - comes back as `NOT AUTOMATED` instead of running anything.
- On the **`RUN ENTIRE SUITE`** row, it runs `mvn test -Dtest=<TestClass>` for the whole class
  named by that tab's `sheet.suiteClass.<TabName>` - every enabled test case in one go. If that
  config key is missing, it comes back as `NOT CONFIGURED`.

Either way it writes back `PASSED`/`FAILED`, the timestamp, and a `REPORT` cell you can click to
open the generated ExtentReport. The agent also starts a tiny local HTTP server (see
`ReportFileServer`) on `sheet.reportServerPort` that serves the `reports/` folder, and the
`REPORT` cell is a `=HYPERLINK(...)` formula pointing at `http://localhost:<port>/<report>` - not
a `file://` path, since most browsers block navigating from an `https:` page (like Google Sheets)
to a local `file:` URL when a link is clicked. This does mean the link only works while the agent
(and its report server) is running on that machine.

### Adding new automated test cases

`SheetRunnerAgent` decides what to run purely from
[`TestData/TestCaseAutomationMap.csv`](TestData/TestCaseAutomationMap.csv): each row maps a
`testCaseId` to a `testClass`/`testMethod`, whether that method takes `-DtestCaseId` (every
current `LoginTest` method is self-contained and doesn't; the column exists for a future
data-driven method), and whether it's `enabled`. Automating a new test case is adding a row there
once the TestNG method exists, and adding its tab (plus a `sheet.suiteClass.<TabName>` entry) to
`sheet.watchedTabs` if it isn't already there - no Java changes needed in the agent itself.
