package org.deephacks.logbuffers;

import org.deephacks.logbuffers.LogBufferTest.TailLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;

public class LogBufferTailConcurrencyTest {
  LogBuffer logBuffer;
  TailLog tail;
  int numLogs = 500_000;
  String path;

  @Before
  public void before() throws IOException {
    if (logBuffer != null) {
      logBuffer.close();
    }
    this.path = LogUtil.cleanupTmpDir();
    logBuffer = LogBuffer.newBuilder()
      .hourly()
      .basePath(path).build();
    tail = new TailLog();
  }

  @After
  public void after() throws IOException {
    logBuffer.close();
  }

  @Test
  public void testConcurrency() throws IOException, InterruptedException {
    ExecutorService executor = Executors.newCachedThreadPool();
    Stopwatch stopwatch = new Stopwatch().start();
    final CountDownLatch latch = new CountDownLatch(numLogs);
    for (int i = 0; i < numLogs; i++) {
      executor.submit(() -> {
        try {
          logBuffer.write(LogUtil.randomLog());
        } catch (IOException e) {
          throw new RuntimeException(e);
        } finally {
          latch.countDown();
        }
      });
    }
    latch.await();
    TailSchedule schedule = TailSchedule.builder(tail).build();
    System.out.println("Wrote " + numLogs + " in " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + "ms");
    stopwatch = new Stopwatch().start();
    logBuffer.forward(schedule);
    System.out.println("Read " + numLogs + " in " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + "ms");
    assertThat(tail.logs.size(), is(numLogs));
    // check uniqueness and ordering
    Log previous = null;
    for (int i = 0; i < numLogs; i++) {
      Log current = tail.logs.get(i);
      if (previous != null) {
        assertTrue(previous.getIndex() < current.getIndex());
        assertTrue(previous.getTimestamp() <= current.getIndex());
      }
      previous = current;
    }
  }
}
