package com.platform.listeners;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;
import com.microsoft.playwright.Page;
import com.platform.base.BaseTest;
import com.platform.utility.ExtentManager;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

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
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        testMap.get(testKey(result)).log(Status.PASS, "Test passed");
    }

    @Override
    public void onTestFailure(ITestResult result) {
        ExtentTest test = testMap.get(testKey(result));
        test.log(Status.FAIL, result.getThrowable());

        Page page = BaseTest.getCurrentPage();
        if (page != null) {
            try {
                byte[] screenshot = page.screenshot();
                test.addScreenCaptureFromBase64String(java.util.Base64.getEncoder().encodeToString(screenshot),
                        result.getMethod().getMethodName());
            } catch (Exception e) {
                test.log(Status.WARNING, "Could not capture screenshot: " + e.getMessage());
            }
        }
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

    private String testKey(ITestResult result) {
        return result.getMethod().getMethodName() + "_" + java.util.Arrays.toString(result.getParameters());
    }
}
