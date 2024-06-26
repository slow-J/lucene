/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.tests.util;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.carrotsearch.randomizedtesting.RandomizedRunner;
import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.SysGlobals;
import com.carrotsearch.randomizedtesting.rules.TestRuleAdapter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import org.apache.lucene.tests.util.LuceneTestCase.SuppressSysoutChecks;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/**
 * An abstract test class that prepares nested test classes to run. A nested test class will assume
 * it's executed under control of this class and be ignored otherwise.
 *
 * <p>The purpose of this is so that nested test suites don't run from IDEs like Eclipse (where they
 * are automatically detected).
 *
 * <p>This class cannot extend {@link LuceneTestCase} because in case there's a nested {@link
 * LuceneTestCase} afterclass hooks run twice and cause havoc (static fields).
 */
public abstract class WithNestedTests {
  @SuppressSysoutChecks(bugUrl = "WithNestedTests has its own stream capture.")
  public abstract static class AbstractNestedTest extends LuceneTestCase
      implements TestRuleIgnoreTestSuites.NestedTestSuite {
    protected static boolean isRunningNested() {
      return TestRuleIgnoreTestSuites.isRunningNested();
    }
  }

  private final boolean suppressOutputStreams;

  protected WithNestedTests(boolean suppressOutputStreams) {
    this.suppressOutputStreams = suppressOutputStreams;
  }

  protected PrintStream prevSysErr;
  protected PrintStream prevSysOut;
  private ByteArrayOutputStream sysout;
  private ByteArrayOutputStream syserr;

  @ClassRule
  public static final TestRule classRules =
      RuleChain.outerRule(
          new TestRuleAdapter() {
            private TestRuleIgnoreAfterMaxFailures prevRule;

            @Override
            protected void before() {
              if (!isPropertyEmpty(SysGlobals.SYSPROP_TESTFILTER())
                  || !isPropertyEmpty(SysGlobals.SYSPROP_TESTCLASS())
                  || !isPropertyEmpty(SysGlobals.SYSPROP_TESTMETHOD())
                  || !isPropertyEmpty(SysGlobals.SYSPROP_ITERATIONS())) {
                // We're running with a complex test filter that is properly handled by classes
                // which are executed by RandomizedRunner. The "outer" classes testing
                // LuceneTestCase
                // itself are executed by the default JUnit runner and would always be executed.
                // We thus always skip execution if any filtering is detected.
                Assume.assumeTrue(false);
              }

              // Check zombie threads from previous suites. Don't run if zombies are around.
              RandomizedTest.assumeFalse(RandomizedRunner.hasZombieThreads());

              TestRuleIgnoreAfterMaxFailures newRule =
                  new TestRuleIgnoreAfterMaxFailures(Integer.MAX_VALUE);
              prevRule = LuceneTestCase.replaceMaxFailureRule(newRule);
              RandomizedTest.assumeFalse(FailureMarker.hadFailures());
            }

            @Override
            protected void afterAlways(List<Throwable> errors) {
              if (prevRule != null) {
                LuceneTestCase.replaceMaxFailureRule(prevRule);
              }
              FailureMarker.resetFailures();
            }

            private boolean isPropertyEmpty(String propertyName) {
              String value = System.getProperty(propertyName);
              return value == null || value.trim().isEmpty();
            }
          });

  /** Restore properties after test. */
  @Rule public final TestRule rules;

  {
    final TestRuleMarkFailure marker = new TestRuleMarkFailure();
    rules =
        RuleChain.outerRule(
                new TestRuleRestoreSystemProperties(TestRuleIgnoreTestSuites.PROPERTY_RUN_NESTED))
            .around(
                new TestRuleAdapter() {
                  @Override
                  protected void afterAlways(List<Throwable> errors) throws Throwable {
                    if (marker.hadFailures() && suppressOutputStreams) {
                      System.out.println("sysout from nested test: " + getSysOut() + "\n");
                      System.out.println("syserr from nested test: " + getSysErr());
                    }
                  }
                })
            .around(marker);
  }

  @Before
  public final void before() {
    if (suppressOutputStreams) {
      prevSysOut = System.out;
      prevSysErr = System.err;

      sysout = new ByteArrayOutputStream();
      System.setOut(new PrintStream(sysout, true, UTF_8));
      syserr = new ByteArrayOutputStream();
      System.setErr(new PrintStream(syserr, true, UTF_8));
    }

    FailureMarker.resetFailures();
    System.setProperty(TestRuleIgnoreTestSuites.PROPERTY_RUN_NESTED, "true");
  }

  @After
  public final void after() {
    if (suppressOutputStreams) {
      System.out.flush();
      System.err.flush();

      System.setOut(prevSysOut);
      System.setErr(prevSysErr);
    }
  }

  protected void assertFailureCount(int expected, Result result) {
    if (result.getFailureCount() != expected) {
      StringBuilder b = new StringBuilder();
      for (Failure f : result.getFailures()) {
        b.append("\n\n");
        b.append(f.getMessage());
        b.append("\n");
        b.append(f.getTrace());
      }
      Assert.assertFalse(
          "Expected failures: "
              + expected
              + " but was "
              + result.getFailureCount()
              + ", failures below: "
              + b,
          true);
    }
  }

  protected String getSysOut() {
    Assert.assertTrue(suppressOutputStreams);
    System.out.flush();
    return sysout.toString(UTF_8);
  }

  protected String getSysErr() {
    Assert.assertTrue(suppressOutputStreams);
    System.err.flush();
    return syserr.toString(UTF_8);
  }
}
