/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.azuredfs.services;

import org.junit.Test;

import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsNetworkTrafficAnalysisResult;
import org.apache.hadoop.fs.azuredfs.contracts.services.AdfsNetworkTrafficMetrics;
import org.apache.hadoop.fs.contract.ContractTestUtils.NanoTimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for <code>ClientThrottlingAnalyzer</code>.
 */
public class TestNetworkThroughputAnalysisServiceImpl {
  private static final int ANALYSIS_PERIOD = 1000;
  private static final int ANALYSIS_PERIOD_PLUS_10_PERCENT = ANALYSIS_PERIOD
      + ANALYSIS_PERIOD / 10;
  private static final long MEGABYTE = 1024 * 1024;
  private static final int MAX_ACCEPTABLE_PERCENT_DIFFERENCE = 20;

  final AdfsNetworkTrafficAnalysisServiceImpl adfsNetworkTrafficAnalysisService;
  final AdfsHttpClientSessionImpl adfsHttpClientSession;
  final AdfsNetworkTrafficAnalysisResult adfsNetworkTrafficAnalysisResult;
  final AdfsNetworkTrafficMetrics adfsNetworkTrafficMetrics;

  public TestNetworkThroughputAnalysisServiceImpl() {
    this.adfsNetworkTrafficAnalysisService = new AdfsNetworkTrafficAnalysisServiceImpl(AdfsLoggingTestUtils.createMockLoggingService());
    this.adfsHttpClientSession = new AdfsHttpClientSessionImpl("testaccount.dfs.core.windows.net", "dGVzdFN0cmluZw==", "testFileSystem");
    this.adfsNetworkTrafficAnalysisService.subscribeForAnalysis(adfsHttpClientSession, ANALYSIS_PERIOD);
    this.adfsNetworkTrafficAnalysisResult = adfsNetworkTrafficAnalysisService.getAdfsNetworkTrafficAnalysisResult(adfsHttpClientSession);
    this.adfsNetworkTrafficMetrics = adfsNetworkTrafficAnalysisService.getAdfsNetworkThroughputMetrics(adfsHttpClientSession);
  }

  /**
   * Ensure that there is no waiting (sleepDuration = 0) if the metrics have
   * never been updated.  This validates proper initialization of
   * ClientThrottlingAnalyzer.
   */
  @Test
  public void testNoMetricUpdatesThenNoWaiting() {
    validate(0, adfsNetworkTrafficAnalysisResult.getReadAnalysisResult().getSleepDuration());
    validate(0, adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration());
    sleep(ANALYSIS_PERIOD_PLUS_10_PERCENT);
    validate(0, adfsNetworkTrafficAnalysisResult.getReadAnalysisResult().getSleepDuration());
    validate(0, adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration());
  }

  /**
   * Ensure that there is no waiting (sleepDuration = 0) if the metrics have
   * only been updated with successful requests.
   */
  @Test
  public void testOnlySuccessThenNoWaiting() {
    adfsNetworkTrafficMetrics.getWriteMetrics().addBytesTransferred(8 * MEGABYTE, false);
    validate(0, adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration());
    sleep(ANALYSIS_PERIOD_PLUS_10_PERCENT);
    validate(0, adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration());
  }

  /**
   * Ensure that there is waiting (sleepDuration != 0) if the metrics have
   * only been updated with failed requests.  Also ensure that the
   * sleepDuration decreases over time.
   */
  @Test
  public void testOnlyErrorsAndWaiting() {
    validate(0, adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration());
    adfsNetworkTrafficMetrics.getWriteMetrics().addBytesTransferred(8 * MEGABYTE, true);
    sleep(ANALYSIS_PERIOD_PLUS_10_PERCENT);
    final int expectedSleepDuration1 = 1100;
    validateLessThanOrEqual(expectedSleepDuration1, adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration());
    sleep(10 * ANALYSIS_PERIOD);
    final int expectedSleepDuration2 = 900;
    validateLessThanOrEqual(expectedSleepDuration2, adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration());
  }

  /**
   * Ensure that there is waiting (sleepDuration != 0) if the metrics have
   * only been updated with both successful and failed requests.  Also ensure
   * that the sleepDuration decreases over time.
   */
  @Test
  public void testSuccessAndErrorsAndWaiting() {
    validate(0, adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration());
    adfsNetworkTrafficMetrics.getWriteMetrics().addBytesTransferred(2 * MEGABYTE, true);
    adfsNetworkTrafficMetrics.getWriteMetrics().addBytesTransferred(8 * MEGABYTE, false);

    sleep(ANALYSIS_PERIOD_PLUS_10_PERCENT);
    NanoTimer timer = new NanoTimer();

    int sleepDuration = adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration();
    if (sleepDuration > 0) {
      sleep(sleepDuration);
    }

    final int expectedElapsedTime = 126;
    fuzzyValidate(expectedElapsedTime,
                  timer.elapsedTimeMs(),
                  MAX_ACCEPTABLE_PERCENT_DIFFERENCE);
    sleep(10 * ANALYSIS_PERIOD);
    final int expectedSleepDuration = 110;
    validateLessThanOrEqual(expectedSleepDuration, adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration());
  }

  /**
   * Ensure that there is waiting (sleepDuration != 0) if the metrics have
   * only been updated with many successful and failed requests.  Also ensure
   * that the sleepDuration decreases to zero over time.
   */
  @Test
  public void testManySuccessAndErrorsAndWaiting() {
    validate(0, adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration());

    final int numberOfRequests = 20;
    for (int i = 0; i < numberOfRequests; i++) {
      adfsNetworkTrafficMetrics.getWriteMetrics().addBytesTransferred(2 * MEGABYTE, true);
      adfsNetworkTrafficMetrics.getWriteMetrics().addBytesTransferred(8 * MEGABYTE, false);
    }
    sleep(ANALYSIS_PERIOD_PLUS_10_PERCENT);
    NanoTimer timer = new NanoTimer();

    int sleepDuration = adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration();
    if (sleepDuration > 0) {
      sleep(sleepDuration);
    }

    fuzzyValidate(7,
                  timer.elapsedTimeMs(),
                  MAX_ACCEPTABLE_PERCENT_DIFFERENCE);
    sleep(10 * ANALYSIS_PERIOD);
    validate(0, adfsNetworkTrafficAnalysisResult.getWriteAnalysisResult().getSleepDuration());
  }

  private void sleep(long milliseconds) {
    try {
      Thread.sleep(milliseconds);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void fuzzyValidate(long expected, long actual, double percentage) {
    final double lowerBound = Math.max(expected - percentage / 100 * expected, 0);
    final double upperBound = expected + percentage / 100 * expected;

    assertTrue(
        String.format(
            "The actual value %1$d is not within the expected range: "
                + "[%2$.2f, %3$.2f].",
            actual,
            lowerBound,
            upperBound),
        actual >= lowerBound && actual <= upperBound);
  }

  private void validate(long expected, long actual) {
    assertEquals(
        String.format("The actual value %1$d is not the expected value %2$d.",
            actual,
            expected),
        expected, actual);
  }

  private void validateLessThanOrEqual(long maxExpected, long actual) {
    assertTrue(
        String.format(
            "The actual value %1$d is not less than or equal to the maximum"
                + " expected value %2$d.",
            actual,
            maxExpected),
        actual < maxExpected);
  }
}
