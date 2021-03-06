/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.tests.product.launcher.cli;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.inject.Module;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.prestosql.tests.product.launcher.Extensions;
import io.prestosql.tests.product.launcher.LauncherModule;
import io.prestosql.tests.product.launcher.env.EnvironmentConfig;
import io.prestosql.tests.product.launcher.env.EnvironmentConfigFactory;
import io.prestosql.tests.product.launcher.env.EnvironmentFactory;
import io.prestosql.tests.product.launcher.env.EnvironmentModule;
import io.prestosql.tests.product.launcher.env.EnvironmentOptions;
import io.prestosql.tests.product.launcher.suite.Suite;
import io.prestosql.tests.product.launcher.suite.SuiteFactory;
import io.prestosql.tests.product.launcher.suite.SuiteModule;
import io.prestosql.tests.product.launcher.suite.SuiteTestRun;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import javax.inject.Inject;

import java.io.File;
import java.lang.management.ThreadInfo;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.units.Duration.nanosSince;
import static io.airlift.units.Duration.succinctNanos;
import static io.prestosql.tests.product.launcher.cli.Commands.runCommand;
import static java.lang.Math.max;
import static java.lang.String.format;
import static java.lang.management.ManagementFactory.getThreadMXBean;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;

@Command(
        name = "run",
        description = "Run suite tests",
        usageHelpAutoWidth = true)
public class SuiteRun
        implements Callable<Integer>
{
    private static final Logger log = Logger.get(SuiteRun.class);

    private static final ScheduledExecutorService diagnosticExecutor = newScheduledThreadPool(2, daemonThreadsNamed("TestRun-diagnostic"));

    private final Module additionalEnvironments;
    private final Module additionalSuites;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit")
    public boolean usageHelpRequested;

    @Mixin
    public SuiteRunOptions suiteRunOptions = new SuiteRunOptions();

    @Mixin
    public EnvironmentOptions environmentOptions = new EnvironmentOptions();

    public SuiteRun(Extensions extensions)
    {
        this.additionalEnvironments = requireNonNull(extensions, "extensions is null").getAdditionalEnvironments();
        this.additionalSuites = requireNonNull(extensions, "extensions is null").getAdditionalSuites();
    }

    @Override
    public Integer call()
    {
        return runCommand(
                ImmutableList.<Module>builder()
                        .add(new LauncherModule())
                        .add(new SuiteModule(additionalSuites))
                        .add(new EnvironmentModule(environmentOptions, additionalEnvironments))
                        .add(suiteRunOptions.toModule())
                        .build(),
                Execution.class);
    }

    public static class SuiteRunOptions
    {
        private static final String DEFAULT_VALUE = "(default: ${DEFAULT-VALUE})";

        @Option(names = "--suite", paramLabel = "<suite>", description = "Name of the suite to run", required = true)
        public String suite;

        @Option(names = "--test-jar", paramLabel = "<jar>", description = "Path to test JAR " + DEFAULT_VALUE, defaultValue = "${product-tests.module}/target/${product-tests.module}-${project.version}-executable.jar")
        public File testJar;

        @Option(names = "--cli-executable", paramLabel = "<jar>", description = "Path to CLI executable " + DEFAULT_VALUE, defaultValue = "${cli.bin}")
        public File cliJar;

        @Option(names = "--logs-dir", paramLabel = "<dir>", description = "Location of the exported logs directory " + DEFAULT_VALUE, converter = OptionalPathConverter.class, defaultValue = "")
        public Optional<Path> logsDirBase;

        @Option(names = "--timeout", paramLabel = "<timeout>", description = "Maximum duration of suite execution " + DEFAULT_VALUE, converter = DurationConverter.class, defaultValue = "999d")
        public Duration timeout;

        public Module toModule()
        {
            return binder -> binder.bind(SuiteRunOptions.class).toInstance(this);
        }
    }

    public static class Execution
            implements Callable<Integer>
    {
        // TODO do not store mutable state
        private final SuiteRunOptions suiteRunOptions;
        // TODO do not store mutable state
        private final EnvironmentOptions environmentOptions;
        private final SuiteFactory suiteFactory;
        private final EnvironmentFactory environmentFactory;
        private final EnvironmentConfigFactory configFactory;
        private final long suiteStartTime;

        @Inject
        public Execution(
                SuiteRunOptions suiteRunOptions,
                EnvironmentOptions environmentOptions,
                SuiteFactory suiteFactory,
                EnvironmentFactory environmentFactory,
                EnvironmentConfigFactory configFactory)
        {
            this.suiteRunOptions = requireNonNull(suiteRunOptions, "suiteRunOptions is null");
            this.environmentOptions = requireNonNull(environmentOptions, "environmentOptions is null");
            this.suiteFactory = requireNonNull(suiteFactory, "suiteFactory is null");
            this.environmentFactory = requireNonNull(environmentFactory, "environmentFactory is null");
            this.configFactory = requireNonNull(configFactory, "configFactory is null");
            this.suiteStartTime = System.nanoTime();
        }

        @Override
        public Integer call()
        {
            AtomicBoolean finished = new AtomicBoolean();
            Future<?> diagnosticFlow = immediateFuture(null);
            try {
                long timeoutMillis = suiteRunOptions.timeout.toMillis();
                long marginMillis = SECONDS.toMillis(10);
                if (timeoutMillis < marginMillis) {
                    log.error("Unsupported small timeout value: %s ms", timeoutMillis);
                    return ExitCode.SOFTWARE;
                }

                diagnosticFlow = diagnosticExecutor.schedule(() -> reportSuspectedTimeout(finished), timeoutMillis - marginMillis, MILLISECONDS);

                return runSuite();
            }
            finally {
                finished.set(true);
                diagnosticFlow.cancel(true);
            }
        }

        private int runSuite()
        {
            String suiteName = requireNonNull(suiteRunOptions.suite, "suiteRunOptions.suite is null");

            Suite suite = suiteFactory.getSuite(suiteName);
            EnvironmentConfig environmentConfig = configFactory.getConfig(environmentOptions.config);
            List<SuiteTestRun> suiteTestRuns = suite.getTestRuns(environmentConfig);

            log.info("Starting suite '%s' with config '%s' and test runs: ", suiteName, environmentConfig.getConfigName());

            for (int runId = 0; runId < suiteTestRuns.size(); runId++) {
                SuiteTestRun testRun = suiteTestRuns.get(runId);
                log.info("#%02d %s - groups: %s, excluded groups: %s, tests: %s, excluded tests: %s",
                        runId + 1,
                        testRun.getEnvironmentName(),
                        testRun.getGroups(),
                        testRun.getExcludedGroups(),
                        testRun.getTests(),
                        testRun.getExcludedTests());
            }
            ImmutableList.Builder<TestRunResult> results = ImmutableList.builder();

            for (int runId = 0; runId < suiteTestRuns.size(); runId++) {
                results.add(executeSuiteTestRun(runId + 1, suiteName, suiteTestRuns.get(runId), environmentConfig));
            }

            List<TestRunResult> testRunsResults = results.build();
            printTestRunsSummary(suiteName, testRunsResults);

            return getFailedCount(testRunsResults) == 0 ? ExitCode.OK : ExitCode.SOFTWARE;
        }

        private void printTestRunsSummary(String suiteName, List<TestRunResult> results)
        {
            long failedRuns = getFailedCount(results);

            if (failedRuns > 0) {
                log.info("Suite %s failed in %s (%d passed, %d failed): ", suiteName, nanosSince(suiteStartTime), results.size() - failedRuns, failedRuns);
            }
            else {
                log.info("Suite %s succeeded in %s: ", suiteName, nanosSince(suiteStartTime));
            }

            results.stream()
                    .filter(TestRunResult::isSuccessful)
                    .forEach(Execution::printTestRunSummary);

            results.stream()
                    .filter(TestRunResult::hasFailed)
                    .forEach(Execution::printTestRunSummary);
        }

        private static long getFailedCount(List<TestRunResult> results)
        {
            return results.stream()
                    .filter(TestRunResult::hasFailed)
                    .count();
        }

        private static void printTestRunSummary(TestRunResult result)
        {
            if (result.isSuccessful()) {
                log.info("PASSED %s with %s [took %s]", result.getSuiteRun(), result.getSuiteConfig(), result.getDuration());
            }
            else if (result.getThrowable().isPresent()) {
                log.error(result.getThrowable().get(), "FAILED %s with %s [took %s]", result.getSuiteRun(), result.getSuiteConfig(), result.getDuration());
            }
            else {
                log.error("FAILED %s with %s [took %s]", result.getSuiteRun(), result.getSuiteConfig(), result.getDuration());
            }
        }

        public TestRunResult executeSuiteTestRun(int runId, String suiteName, SuiteTestRun suiteTestRun, EnvironmentConfig environmentConfig)
        {
            TestRun.TestRunOptions testRunOptions = createTestRunOptions(runId, suiteName, suiteTestRun, environmentConfig, suiteRunOptions.logsDirBase);
            if (testRunOptions.timeout.toMillis() == 0) {
                return new TestRunResult(
                        runId,
                        suiteTestRun,
                        environmentConfig,
                        new Duration(0, MILLISECONDS),
                        Optional.of(new Exception("Test execution not attempted because suite total running time limit was exhausted")));
            }

            log.info("Starting test run #%02d %s with config %s and remaining timeout %s", runId, suiteTestRun, environmentConfig, testRunOptions.timeout);
            log.info("Execute this test run using:\n%s test run %s", environmentOptions.launcherBin, OptionsPrinter.format(environmentOptions, testRunOptions));

            Stopwatch stopwatch = Stopwatch.createStarted();
            Optional<Throwable> exception = runTest(environmentConfig, testRunOptions);
            return new TestRunResult(runId, suiteTestRun, environmentConfig, succinctNanos(stopwatch.stop().elapsed(NANOSECONDS)), exception);
        }

        private Optional<Throwable> runTest(EnvironmentConfig environmentConfig, TestRun.TestRunOptions testRunOptions)
        {
            try {
                TestRun.Execution execution = new TestRun.Execution(environmentFactory, environmentOptions, environmentConfig, testRunOptions);
                int exitCode = execution.call();

                if (exitCode > 0) {
                    return Optional.of(new RuntimeException(format("Tests exited with code %d", exitCode)));
                }

                return Optional.empty();
            }
            catch (RuntimeException e) {
                return Optional.of(e);
            }
        }

        private TestRun.TestRunOptions createTestRunOptions(int runId, String suiteName, SuiteTestRun suiteTestRun, EnvironmentConfig environmentConfig, Optional<Path> logsDirBase)
        {
            TestRun.TestRunOptions testRunOptions = new TestRun.TestRunOptions();
            testRunOptions.environment = suiteTestRun.getEnvironmentName();
            testRunOptions.testArguments = suiteTestRun.getTemptoRunArguments(environmentConfig);
            testRunOptions.testJar = suiteRunOptions.testJar;
            testRunOptions.cliJar = suiteRunOptions.cliJar;
            String suiteRunId = suiteRunId(runId, suiteName, suiteTestRun, environmentConfig);
            testRunOptions.reportsDir = Paths.get("presto-product-tests/target/reports/" + suiteRunId);
            testRunOptions.logsDirBase = logsDirBase.map(dir -> dir.resolve(suiteRunId));
            // Calculate remaining time
            testRunOptions.timeout = remainingTimeout();
            return testRunOptions;
        }

        private Duration remainingTimeout()
        {
            long remainingNanos = suiteRunOptions.timeout.roundTo(NANOSECONDS) - nanosSince(suiteStartTime).roundTo(NANOSECONDS);
            return succinctNanos(max(remainingNanos, 0));
        }

        private void reportSuspectedTimeout(AtomicBoolean finished)
        {
            if (finished.get()) {
                return;
            }

            // Test may not complete on time because they take too long (legitimate from Launcher's perspective), or because Launcher hangs.
            // In the latter case it's worth reporting Launcher's threads.

            // Log something immediately before getting thread information
            log.warn("Test execution is not finished yet, a deadlock or hang is suspected. Thread dump will follow.");
            log.warn(
                    "Full Thread Dump:\n%s",
                    Arrays.stream(getThreadMXBean().dumpAllThreads(true, true))
                            // TODO ThreadInfo.toString truncates stacktrace to java.lang.management.ThreadInfo#MAX_FRAMES
                            .map(ThreadInfo::toString)
                            .collect(joining("\n")));
        }

        private static String suiteRunId(int runId, String suiteName, SuiteTestRun suiteTestRun, EnvironmentConfig environmentConfig)
        {
            return format("%s-%s-%s-%02d", suiteName, suiteTestRun.getEnvironmentName(), environmentConfig.getConfigName(), runId);
        }
    }

    private static class TestRunResult
    {
        private final int runId;
        private final SuiteTestRun suiteRun;
        private final EnvironmentConfig environmentConfig;
        private final Duration duration;
        private final Optional<Throwable> throwable;

        public TestRunResult(int runId, SuiteTestRun suiteRun, EnvironmentConfig environmentConfig, Duration duration, Optional<Throwable> throwable)
        {
            this.runId = runId;
            this.suiteRun = requireNonNull(suiteRun, "suiteRun is null");
            this.environmentConfig = requireNonNull(environmentConfig, "suiteConfig is null");
            this.duration = requireNonNull(duration, "duration is null");
            this.throwable = requireNonNull(throwable, "throwable is null");
        }

        public int getRunId()
        {
            return this.runId;
        }

        public SuiteTestRun getSuiteRun()
        {
            return this.suiteRun;
        }

        public EnvironmentConfig getSuiteConfig()
        {
            return this.environmentConfig;
        }

        public Duration getDuration()
        {
            return this.duration;
        }

        public boolean isSuccessful()
        {
            return this.throwable.isEmpty();
        }

        public boolean hasFailed()
        {
            return this.throwable.isPresent();
        }

        public Optional<Throwable> getThrowable()
        {
            return this.throwable;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("runId", runId)
                    .add("suiteRun", suiteRun)
                    .add("suiteConfig", environmentConfig)
                    .add("duration", duration)
                    .add("throwable", throwable)
                    .toString();
        }
    }
}
