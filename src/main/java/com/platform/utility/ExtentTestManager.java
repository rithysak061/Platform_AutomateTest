package com.platform.utility;

import com.aventstack.extentreports.ExtentTest;

/**
 * Exposes the ExtentTest for the currently running test method so test code can
 * log its own steps, without needing the ExtentTestNGListener to pass it around.
 */
public class ExtentTestManager {

    private static final ThreadLocal<ExtentTest> currentTest = new ThreadLocal<>();

    private ExtentTestManager() {
    }

    public static void setTest(ExtentTest test) {
        currentTest.set(test);
    }

    public static ExtentTest getTest() {
        return currentTest.get();
    }

    public static void unload() {
        currentTest.remove();
    }
}
