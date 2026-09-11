package com.platform.automation;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a spreadsheet "TEST CASE ID" to the TestNG class/method that automates it, loaded from
 * TestData/TestCaseAutomationMap.csv. Add a row there whenever a new test case is automated -
 * no code change needed for SheetRunnerAgent to pick it up.
 */
public record TestCaseMapping(String testClass, String testMethod, boolean usesTestCaseId, boolean enabled) {

    private static final String MAPPING_FILE = "TestData/TestCaseAutomationMap.csv";

    public static Map<String, TestCaseMapping> loadAll() {
        Map<String, TestCaseMapping> mappings = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(MAPPING_FILE))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                String testCaseId = normalize(fields[0]);
                mappings.put(testCaseId, new TestCaseMapping(
                        fields[1].trim(),
                        fields[2].trim(),
                        Boolean.parseBoolean(fields[3].trim()),
                        Boolean.parseBoolean(fields[4].trim())));
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to read test case mapping file: " + MAPPING_FILE, e);
        }
        return mappings;
    }

    static String normalize(String testCaseId) {
        return testCaseId.trim().toUpperCase();
    }
}
