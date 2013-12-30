package org.deephacks.logbuffers;

import com.google.common.base.Optional;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Specialized tail that provide logs iteratively in chunks according to a certain period of time.
 */
class LogBufferTailChunk<T> extends LogBufferTail<T> {
  private long chunkMs;
  public static SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss:SSS");

  LogBufferTailChunk(LogBuffer logBuffer, Tail<T> tail, long chunkMs) throws IOException {
    super(logBuffer, tail);
    this.chunkMs = chunkMs;
  }

  @Override
  ForwardResult forward() throws IOException {
    // getObjects the index that was written by previous tail acknowledgement
    long currentReadIndex = getReadIndex();

    // fetch the latest written log so we can determine how far ahead
    // the writer index is so we can speed up if the tail backlog is too big.
    Optional<Log> latestWrite = logBuffer.getLatestWrite();
    if (!latestWrite.isPresent()) {
      return new ForwardResult();
    }

    List<Log> current = logBuffer.select(currentReadIndex);
    if (current.isEmpty()) {
      return new ForwardResult();
    }
    // pick the next log by trying to select fixed chunk period
    long fixedFrom = fix(current.get(0).getTimestamp());
    long fixedTo = fixedFrom + chunkMs - 1;
    // do not process ahead of time, meaning tail will not try process
    // logs until the chunkMs have passed since the present.
    if (fixedTo > System.currentTimeMillis()) {
      return new ForwardResult();
    }
    Logs<T> logs = logBuffer.selectPeriod(type, fixedFrom, fixedTo);
    System.out.println(format.format(new Date(fixedFrom)) + " " + format.format(new Date(fixedTo)));

    // don't call tail if there are no logs
    if (logs.size() < 0) {
      return new ForwardResult();
    }
    // prepare the next read index BEFORE we hand over logs to tail
    Log lastRead = logs.getLastLog();
    long lastReadIndex = lastRead.getIndex();
    // prepare result
    ForwardResult result = new ForwardResult();
    if (lastRead.getTimestamp() < latestWrite.get().getTimestamp()) {
      // alter the result to indicate that there are already more logs
      // to process after this round have been executed. Hence we can
      // act quickly and process these as fast as possible, if needed.
      result = new ForwardResult(false);
    }
    // ready to process logs. ignore any exceptions since the LogBuffer
    // will take care of them and retry automatically for us next round.
    // haven't persistent anything to disk yet so tail is fine if it happens
    tail.process(logs);
    // only write/persist last read index if tail was successful
    writeReadIndex(lastReadIndex + 1);
    return result;
  }

  /**
   * Calculate a fixed period of time aligned with the chunk length.
   */
  private long fix(long timeMs) {
    return timeMs - (timeMs % chunkMs);
  }
}
