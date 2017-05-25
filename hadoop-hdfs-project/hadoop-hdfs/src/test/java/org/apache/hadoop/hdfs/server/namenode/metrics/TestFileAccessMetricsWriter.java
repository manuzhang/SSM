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
package org.apache.hadoop.hdfs.server.namenode.metrics;

import org.apache.commons.configuration.SubsetConfiguration;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.metrics2.AbstractMetric;
import org.apache.hadoop.metrics2.MetricsRecord;
import org.apache.hadoop.metrics2.MetricsSink;
import org.apache.hadoop.metrics2.MetricsSystem;
import org.apache.hadoop.metrics2.annotation.Metric;
import org.apache.hadoop.metrics2.annotation.Metrics;
import org.apache.hadoop.metrics2.impl.ConfigBuilder;
import org.apache.hadoop.metrics2.impl.MetricsSystemImpl;
import org.apache.hadoop.metrics2.impl.TestMetricsConfig;
import org.apache.hadoop.metrics2.lib.MutableGaugeInt;
import org.apache.hadoop.util.Time;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestFileAccessMetricsWriter {

  private static final int NUM_DATANODES = 4;
  private MiniDFSCluster cluster;

  /**
   * The name of the current test method.
   */
  @Rule
  public TestName methodName = new TestName();

  /**
   * Create a {@link MiniDFSCluster} instance with four nodes.  The
   * node count is required to allow append to function. Also clear the
   * sink's test flags.
   *
   * @throws IOException thrown if cluster creation fails
   */
  @Before
  public void setupHdfs() throws IOException {
    Configuration conf = new Configuration();

    // It appears that since HDFS-265, append is always enabled.
    cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(NUM_DATANODES).build();
  }

  /**
   * Stop the {@link MiniDFSCluster}.
   */
  @After
  public void shutdownHdfs() {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void testAppend() throws Exception {
    String path = "hdfs://" + cluster.getNameNode().getHostAndPort() + "/tmp";
    MetricsSystem ms = initMetricsSystem(path, false, true);

    preCreateLogFile(path);
    assertExtraContents(doWriteTest(ms, path));
  }

  /**
   * Test writing logs to HDFS.
   *
   * @throws Exception thrown when things break
   */
  @Test
  public void testWrite() throws Exception {
    String path = "hdfs://" + cluster.getNameNode().getHostAndPort() + "/tmp";
    MetricsSystem ms = initMetricsSystem(path, false, true);

    assertMetricsContents(doWriteTest(ms, path));
  }

  private MetricsSystem initMetricsSystem(String path, boolean ignoreErrors,
      boolean allowAppend) {
    // If the prefix is not lower case, the metrics system won't be able to
    // read any of the properties.
    String prefix = methodName.getMethodName().toLowerCase();

    ConfigBuilder builder = new ConfigBuilder().add("*.period", 10000)
        .add(prefix + ".sink.mysink0.class", FileAccessMetrics.Writer.class.getName())
        .add(prefix + ".sink.mysink0.basepath", path)
        .add(prefix + ".sink.mysink0.source", FileAccessMetrics.NAME)
        .add(prefix + ".sink.mysink0.context", FileAccessMetrics.CONTEXT_VALUE)
        .add(prefix + ".sink.mysink0.ignore-error", ignoreErrors)
        .add(prefix + ".sink.mysink0.allow-append", allowAppend)
        .add(prefix + ".sink.mysink0.roll-offset-interval-millis", 0)
        .add(prefix + ".sink.mysink0.roll-interval", "1m")
        .add(prefix + ".sink.mysink1.class", TestSink.class.getName())
        .add(prefix + ".sink.mysink1.source", "testsrc")
        .add(prefix + ".sink.mysink1.context", "test1");

    builder.save(TestMetricsConfig.getTestFilename("hadoop-metrics2-" + prefix));

    MetricsSystemImpl ms = new MetricsSystemImpl(prefix);

    ms.start();

    return ms;
  }

  private String doWriteTest(MetricsSystem ms, String path)
      throws IOException, URISyntaxException {
    FileAccessMetrics metrics = FileAccessMetrics.create(ms);
    metrics.add("path1", "user1", Time.now());
    metrics.add("path2", "", Time.now());

    TestMetrics tm = new TestMetrics().registerWith(ms);
    tm.testMetric1.incr();

    ms.publishMetricsNow(); // publish the metrics

    try {
      ms.stop();
    } finally {
      ms.shutdown();
    }

    return readLogFile(path);
  }

  private String readLogFile(String path)
      throws IOException, URISyntaxException {
    final String now = FileAccessMetrics.DATE_FORMAT.format(new Date()) + "00";
    final String logFile = FileAccessMetrics.getLogFileName(FileAccessMetrics.NAME);
    FileSystem fs = FileSystem.get(new URI(path), new Configuration());
    StringBuilder metrics = new StringBuilder();
    boolean found = false;

    for (FileStatus status : fs.listStatus(new Path(path))) {
      Path logDir = status.getPath();

      readLogData(fs, FileAccessMetrics.findMostRecentLogFile(fs, new Path(logDir, logFile)),
          metrics);
      found = true;
    }

    assertTrue("No valid log directories found", found);

    return metrics.toString();
  }

  /**
   * Read the target log file and append its contents to the StringBuilder.
   * @param fs the target FileSystem
   * @param logFile the target file path
   * @param metrics where to append the file contents
   * @throws IOException thrown if the file cannot be read
   */
  private void readLogData(FileSystem fs, Path logFile, StringBuilder metrics)
      throws IOException {
    FSDataInputStream fsin = fs.open(logFile);
    BufferedReader in = new BufferedReader(new InputStreamReader(fsin,
        StandardCharsets.UTF_8));
    String line = null;

    while ((line = in.readLine()) != null) {
      metrics.append(line).append("\n");
    }
  }

  private void preCreateLogFile(String path)
      throws IOException, InterruptedException, URISyntaxException {
    Calendar now = getNowNotTopOfHour();

    FileSystem fs = FileSystem.get(new URI(path), new Configuration());
    Path dir = new Path(path, FileAccessMetrics.getLogDirName(now.getTimeInMillis()));

    fs.mkdirs(dir);

    Path file = new Path(dir, FileAccessMetrics.getLogFileName(FileAccessMetrics.NAME));

    // Create the log file to force the sink to append
    try (FSDataOutputStream out = fs.create(file)) {
      out.write("Extra stuff\n".getBytes());
      out.flush();
    }
  }

  private void assertExtraContents(String contents) {
    final Pattern expectedContentPattern = Pattern.compile(
        "Extra stuff[\\n\\r]*" +
        ".*:.*:\\d+=1$[\\n\\r]*" +
        ".*:.*:\\d+=1$[\\n\\r]*",
        Pattern.MULTILINE);

    assertTrue("Sink did not produce the expected output. Actual output was: "
        + contents, expectedContentPattern.matcher(contents).matches());
  }

  private void assertMetricsContents(String contents) {
    final Pattern expectedContentPattern = Pattern.compile(
        ".*:.*:\\d+=1$[\\n\\r]*" +
        ".*:.*:\\d+=1$[\\n\\r]*",
        Pattern.MULTILINE);

    assertTrue("Sink did not produce the expected output. Actual output was: "
        + contents, expectedContentPattern.matcher(contents).matches());
  }

  /**
   * Return a calendar based on the current time.  If the current time is very
   * near the top of the hour (less than 20 seconds), sleep until the new hour
   * before returning a new Calendar instance.
   *
   * @return a new Calendar instance that isn't near the top of the hour
   * @throws InterruptedException if interrupted while sleeping
   */
  private Calendar getNowNotTopOfHour() throws InterruptedException {
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

    // If we're at the very top of the hour, sleep until the next hour
    // so that we don't get confused by the directory rolling
    if ((now.get(Calendar.MINUTE) == 59) && (now.get(Calendar.SECOND) > 40)) {
      Thread.sleep((61 - now.get(Calendar.SECOND)) * 1000L);
      now.setTime(new Date());
    }

    return now;
  }

  @Metrics(name="testRecord1", context="test1")
  private class TestMetrics {
    @Metric(value={"testMetric1", "An integer gauge"}, always=true)
    MutableGaugeInt testMetric1;

    public TestMetrics registerWith(MetricsSystem ms) {
      return ms.register(methodName.getMethodName() + "-m1", null, this);
    }
  }

  public static class TestSink implements MetricsSink {

    @Override
    public void init(SubsetConfiguration conf) {
    }

    @Override
    public void putMetrics(MetricsRecord record) {
      assertEquals("test1", record.context());
      assertEquals("testRecord1", record.name());
      AbstractMetric metric = record.metrics().iterator().next();
      assertEquals("testMetric1", metric.name());
      assertEquals(1, metric.value());
    }

    @Override
    public void flush() {
    }

  }

}
