package com.platform.listeners;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;
import com.microsoft.playwright.Page;
import com.platform.base.BaseTest;
import com.platform.pages.LoginPage;
import com.platform.utility.ExtentManager;
import com.platform.utility.ExtentTestManager;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ExtentTestNGListener implements ITestListener {

    private static ExtentReports extent;
    private static final Map<String, ExtentTest> testMap = new ConcurrentHashMap<>();

    private static ExtentReports getExtent() {
        if (extent == null) {
            extent = ExtentManager.getInstance();
        }
        return extent;
    }

    @Override
    public void onTestStart(ITestResult result) {
        ExtentTest test = getExtent().createTest(result.getMethod().getMethodName(), result.getMethod().getDescription());
        testMap.put(testKey(result), test);
        ExtentTestManager.setTest(test);
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        ExtentTest test = testMap.get(testKey(result));
        test.log(Status.PASS, "Test passed");
        attachScreenshot(test, result);
        ExtentTestManager.unload();
    }

    @Override
    public void onTestFailure(ITestResult result) {
        ExtentTest test = testMap.get(testKey(result));
        test.log(Status.FAIL, result.getThrowable());
        attachScreenshot(test, result);
        attachApiDetails(test);
        ExtentTestManager.unload();
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        ExtentTest test = getExtent().createTest(result.getMethod().getMethodName(), result.getMethod().getDescription());
        test.log(Status.SKIP, "Test skipped");
        testMap.put(testKey(result), test);
    }

    @Override
    public void onFinish(ITestContext context) {
        getExtent().flush();
    }

    private void attachScreenshot(ExtentTest test, ITestResult result) {
        Page page = BaseTest.getCurrentPage();
        if (page != null) {
            try {
                byte[] screenshot = page.screenshot();
                test.addScreenCaptureFromBase64String(Base64.getEncoder().encodeToString(screenshot),
                        result.getMethod().getMethodName());
            } catch (Exception e) {
                test.log(Status.WARNING, "Could not capture screenshot: " + e.getMessage());
            }
        }
    }

    /**
     * On failure, surfaces exactly what the login API was asked and what it answered,
     * so a failure can be diagnosed without re-running the test with network logging on.
     */
    private void attachApiDetails(ExtentTest test) {
        LoginPage loginPage = BaseTest.getCurrentLoginPage();
        if (loginPage == null || loginPage.getLastApiUrl() == null) {
            return;
        }
        test.log(Status.INFO, "<b>Login API URL:</b> " + escapeHtml(loginPage.getLastApiUrl()));
        test.log(Status.INFO, "<b>Request Payload:</b><pre>" + escapeHtml(loginPage.getLastApiPayload()) + "</pre>");
        test.log(Status.INFO, "<b>Response:</b><pre>" + escapeHtml(loginPage.getLastApiResponseBody()) + "</pre>");
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "(none)";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String testKey(ITestResult result) {
        return result.getMethod().getMethodName() + "_" + java.util.Arrays.toString(result.getParameters());
    }
}
