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

Runs every `@Test` method in `LoginTest`: `verifyValidLogin`, `verifyInvalidLogin`, the dedicated
methods (`verifyAccountBlockedAfter3InvalidPasswords`, etc.) and every data-driven row of both.

To run one specific method in a class (e.g. just the account-lockout test):

```
mvn test -Dtest=LoginTest#verifyAccountBlockedAfter3InvalidPasswords
```

## 3. Run a specific test case ID

`verifyValidLogin` (TC_LOGIN_001, the only SUCCESS row) and `verifyInvalidLogin` (TC_LOGIN_002,
003, 005, 007, 009) are both data-driven from `TestData/Login_TestData.csv`. Pass
`-DtestCaseId=<ID>` to run just one row:

```
mvn test -Dtest=LoginTest#verifyValidLogin -DtestCaseId=TC_LOGIN_001
```

```
mvn test -Dtest=LoginTest#verifyInvalidLogin -DtestCaseId=TC_LOGIN_007
```

Omitting `-DtestCaseId` runs all rows for that method, as usual.

The other test-case IDs (TC_LOGIN_004, 006, 008) are implemented as their own methods rather than
CSV rows, so use the method-filter form from section 2 instead, e.g.:

```
mvn test -Dtest=LoginTest#verifyAccountBlockedAfter3InvalidPasswords
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
mvn test -Dtest=LoginTest#verifyValidLogin -DtestCaseId=TC_LOGIN_001 -Dheadless=true
```

## Report

Every run generates an ExtentReports HTML report at `reports/ExtentReport_<timestamp>.html`,
listing each test with its logged steps, a screenshot of the final state (pass or fail), and -
for a failure - the login API URL, request payload and response body that were captured at the
moment of failure.

## Notes

- `-DtestCaseId` and `-Dheadless` are optional system-property overrides; any config key in
  `Config/config.properties` can be overridden the same way (e.g. `-Dbrowser=firefox`).
- TC_LOGIN_006 and TC_LOGIN_008 are disabled (`enabled = false`) in `LoginTest.java` by design -
  see the Javadoc on those methods for why - and won't run even when targeted directly unless you
  remove `enabled = false` first.
