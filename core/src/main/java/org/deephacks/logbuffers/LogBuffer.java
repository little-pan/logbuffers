/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.deephacks.logbuffers;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import net.openhft.chronicle.ChronicleConfig;
import net.openhft.chronicle.ExcerptAppender;
import net.openhft.chronicle.ExcerptTailer;
import net.openhft.chronicle.IndexedChronicle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A log buffer persist data items sequentially on local disk.
 * <p/>
 * Every written log is assigned a unique writeIndex identifier that works like an sequential offset.
 * Indexes are used by readers to select one or more logs.
 * <p/>
 * A logical log buffer is divided into a set of files in a configurable directory.
 * New files are created when the capacity of the current file is reached. Files
 * are not deleted at the moment.
 * <p/>
 * The physical separation of a log buffer is an implementation detail that the user
 * does not need to care about.
 */
public final class LogBuffer {

  private final Logger logger;

  /**
   * system tmp dir
   */
  private static final String TMP_DIR = System.getProperty("java.io.tmpdir");

  /**
   * default path used by log files if not specified
   */
  private static final String DEFAULT_BASE_PATH = TMP_DIR + "/logbuffer";

  /**
   * optional executor used only by scheduled tailing
   */
  private ScheduledExecutorService cachedExecutor;

  /**
   * this should be rolling, but there are bugs. Change later to VanillaChronicle in later versions
   */
  private IndexedChronicle rollingChronicle;

  /**
   * log writer
   */
  private final ExcerptAppender excerptAppender;

  /**
   * log reader
   */
  private final ExcerptTailer excerptTailer;

  /**
   * path where log buffer files are stored
   */
  private String basePath;

  private ConcurrentHashMap<Class<?>, LogBufferTail<?>> tails = new ConcurrentHashMap<>();

  private Serializers serializers;

  private LogBuffer(Builder builder) throws IOException {
    this.basePath = builder.basePath.or(DEFAULT_BASE_PATH);
    this.logger = Logger.getLogger(LogBuffer.class.getName() + "." + checkNotNull(basePath + "/writer"));
    this.rollingChronicle = new IndexedChronicle(basePath + "/data", builder.config);
    this.excerptAppender = rollingChronicle.createAppender();
    this.excerptTailer = rollingChronicle.createTailer();
    this.serializers = builder.serializers;
  }

  synchronized ScheduledExecutorService getCachedExecutor() {
    if (cachedExecutor == null) {
      cachedExecutor = Executors.newSingleThreadScheduledExecutor();
    }
    return cachedExecutor;
  }

  /**
   * Write a new raw log object.
   *
   * @param content raw content.
   * @throws IOException
   */
  public RawLog write(byte[] content) throws IOException {
    return internalWrite(content, RawLog.DEFAULT_TYPE);
  }

  /**
   * Write an object into the log buffer.
   *
   * @param object to be written
   * @return the log that was created.
   * @throws IOException
   */
  public RawLog write(Object object) throws IOException {
    Class<?> cls = object.getClass();
    ObjectLogSerializer serializer = serializers.getSerializer(cls);
    byte[] content = serializer.serialize(object);
    return internalWrite(content, serializers.getType(cls));
  }

  private RawLog internalWrite(byte[] content, long type) throws IOException {
    // single writer is required in order append to file since there is
    // only one file written to at a given time. Also for generating unique
    // sequential indexes and sequential timestamps.
    synchronized (excerptAppender) {
      RawLog log = new RawLog(type, content);
      long index = log.write(excerptAppender);
      return new RawLog(log, index);
    }
  }

  /**
   * Select a list of log objects from a specific writeIndex up until the
   * most recent log written.
   *
   * @param fromIndex writeIndex to read from.
   * @return A list of raw object logs.
   * @throws IOException
   */
  public List<RawLog> select(long fromIndex) throws IOException {
    long writeIdx = getWriteIndex();
    return select(fromIndex, writeIdx);
  }

  Optional<RawLog> get(long index) throws IOException {
    synchronized (excerptTailer) {
      return RawLog.read(excerptTailer, index);
    }
  }

  /**
   * Get the next forward index of specified type.
   */
  public <T> Optional<RawLog> getNext(Class<T> cls, long index) throws IOException {
    synchronized (excerptTailer) {
      long writeIndex = getWriteIndex();
      while (index < writeIndex) {
        Optional<Long> optional = peekType(index++);
        if (!optional.isPresent()) {
          continue;
        }
        long type = optional.get();
        if (type != RawLog.DEFAULT_TYPE) {
          ObjectLogSerializer serializer = serializers.getSerializer(type);
          Class<?> found = serializer.getMapping().get(type);
          if (cls.isAssignableFrom(found)) {
            return get(index - 1);
          }
        } else {
          return get(index - 1);
        }
      }
    }
    return Optional.absent();
  }

  /**
   * Only reads the timestamp in order to avoid serialization overhead.
   */
  Optional<Long> peekTimestamp(long index) throws IOException {
    synchronized (excerptTailer) {
      return RawLog.peekTimestamp(excerptTailer, index);
    }
  }

  Optional<Long> peekType(long index) throws IOException {
    synchronized (excerptTailer) {
      return RawLog.peekType(excerptTailer, index);
    }
  }

  Optional<RawLog> getLatestWrite() throws IOException {
    long index = getWriteIndex();
    if (index == 0) {
      return get(0);
    }
    return get(index - 1);
  }

  /**
   * Select a list of log objects from a specific writeIndex up until a
   * provided writeIndex.
   *
   * @param fromIndex writeIndex to read from.
   * @param toIndex   writeIndex to read up until.
   * @return A list of raw object logs.
   * @throws IOException
   */
  public List<RawLog> select(long fromIndex, long toIndex) throws IOException {
    Preconditions.checkArgument(fromIndex <= toIndex, "from must be less than to");
    List<RawLog> messages = new ArrayList<>();
    synchronized (excerptTailer) {
      long read = fromIndex;
      while (read < toIndex) {
        Optional<RawLog> optional = get(read++);
        if (!optional.isPresent()) {
          break;
        }
        messages.add(optional.get());
      }
      return messages;
    }
  }

  /**
   * Select a list of logs based on the given period of time with respect
   * to the timestamp of each log.
   *
   * @param fromTimeMs from (inclusive)
   * @param toTimeMs   to (inclusive)
   * @return list of matching logs
   * @throws IOException
   */
  public List<RawLog> selectBackward(long fromTimeMs, long toTimeMs) throws IOException {
    return selectBackward(getWriteIndex() - 1, fromTimeMs, toTimeMs);
  }

  /**
   * Select a list of logs based on the given period of time with respect
   * to the timestamp of each log.
   *
   * @param fromTimeMs from (inclusive)
   * @param toTimeMs   to (inclusive)
   * @return list of matching logs
   * @throws IOException
   */
  public List<RawLog> selectForward(long fromTimeMs, long toTimeMs) throws IOException {
    return selectForward(getWriteIndex() - 1, fromTimeMs, toTimeMs);
  }

  /**
   * Select a list of logs based on the given period of time with respect
   * to the timestamp of each log, start scanning at from index, going
   * backwards in time.
   *
   * @param fromTimeMs from (inclusive)
   * @param toTimeMs   to (inclusive)
   * @param fromIndex  from what index to start scanning
   * @return list of matching logs
   * @throws IOException
   */
  public List<RawLog> selectBackward(long fromIndex, long fromTimeMs, long toTimeMs) throws IOException {
    Preconditions.checkArgument(fromTimeMs <= toTimeMs, "from must be less than to");
    LinkedList<RawLog> messages = new LinkedList<>();
    synchronized (excerptTailer) {
      for (long i = fromIndex; i > -1; i--) {
        Optional<Long> optional = peekTimestamp(i);
        if (!optional.isPresent()) {
          continue;
        }
        long timestamp = optional.get();
        if (timestamp >= fromTimeMs && timestamp <= toTimeMs) {
          messages.addFirst(get(i).get());
        }
        if (timestamp < fromTimeMs) {
          // moved past fromTime and since all timestamps are sequential
          // there is no more data to find at this point.
          break;
        }
      }
      return messages;
    }
  }

  /**
   * Select a list of logs based on the given period of time with respect
   * to the timestamp of each log, start scanning at from index, going
   * forwards in time.
   *
   * @param fromTimeMs from (inclusive)
   * @param toTimeMs   to (inclusive)
   * @param fromIndex  from what index to start scanning
   * @return list of matching logs
   * @throws IOException
   */
  public List<RawLog> selectForward(long fromIndex, long fromTimeMs, long toTimeMs) throws IOException {
    Preconditions.checkArgument(fromTimeMs <= toTimeMs, "from must be less than to");
    LinkedList<RawLog> messages = new LinkedList<>();
    long writeIndex = getWriteIndex();
    synchronized (excerptTailer) {
      for (long i = fromIndex; i < writeIndex; i++) {
        Optional<Long> optional = peekTimestamp(i);
        if (!optional.isPresent()) {
          continue;
        }
        long timestamp = optional.get();
        if (timestamp >= fromTimeMs && timestamp <= toTimeMs) {
          messages.addLast(get(i).get());
        }
        if (timestamp > toTimeMs) {
          // moved past fromTime and since all timestamps are sequential
          // there is no more data to find at this point.
          break;
        }
      }
      return messages;
    }
  }

  /**
   * Selects logs only of a specific type, all other types are filtered out.
   *
   * @param type      the type of logs to be selected.
   * @param fromIndex writeIndex to read from.
   * @return A list of object logs.
   * @throws IOException
   */
  public <T> Logs<T> select(Class<T> type, long fromIndex) throws IOException {
    return select(type, fromIndex, getWriteIndex());
  }

  /**
   * Selects a specific type of logs only, all other types are filtered out.
   *
   * @param type      the type of logs to be selected.
   * @param fromIndex writeIndex to read from.
   * @param toIndex   writeIndex to read up until.
   * @return A list of object logs.
   * @throws IOException
   */
  public <T> Logs<T> select(Class<T> type, long fromIndex, long toIndex) throws IOException {
    List<RawLog> logs = select(fromIndex, toIndex);
    return convert(type, logs);
  }

  /**
   * Selects a specific type of logs only, all other types are filtered out, based on the
   * given period of time with respect to the timestamp of each log.
   * <p/>
   *
   * {@link #selectBackward(long, long, long)}
   */
  public <T> Logs<T> selectBackward(Class<T> type, long fromTimeMs, long toTimeMs) throws IOException {
    final List<RawLog> logs = selectBackward(fromTimeMs, toTimeMs);
    return convert(type, logs);
  }

  /**
   * Selects a specific type of logs only, all other types are filtered out, based on the
   * given period of time with respect to the timestamp of each log.
   * <p/>
   *
   * {@link #selectForward(Class, long, long, long)}
   */
  public <T> Logs<T> selectForward(Class<T> type, long fromIndex, long fromTimeMs, long toTimeMs) throws IOException {
    final List<RawLog> logs = selectForward(fromIndex, fromTimeMs, toTimeMs);
    return convert(type, logs);
  }

  private <T> Logs<T> convert(Class<T> type, List<RawLog> logs) {
    Logs<T> result = new Logs<>();
    for (RawLog log : logs) {
      if (log.getType() != RawLog.DEFAULT_TYPE) {
        ObjectLogSerializer serializer = serializers.getSerializer(log.getType());
        if (serializer == null) {
          throw new IllegalStateException("No serializer found for type " + log.getType());
        }
        Class<?> cls = serializer.getMapping().get(log.getType());
        if (type.isAssignableFrom(cls)) {
          T object = (T) serializer.deserialize(log.getContent(), log.getType());
          result.put(object, log);
        }
      } else {
        if (type.isAssignableFrom(RawLog.class)) {
          result.put((T) log, log);
        }
      }
    }
    return result;
  }

  public <T> ForwardResult forward(Tail<T> tail) throws IOException {
    LogBufferTail<T> tailBuffer = putIfAbsent(tail);
    return tailBuffer.forward();
  }

  /**
   * @return directory where this log buffer is stored
   */
  public String getBasePath() {
    return basePath;
  }

  /**
   * Close this log buffer.
   *
   * @throws IOException
   */
  public synchronized void close() throws IOException {
    synchronized (excerptAppender) {
      excerptAppender.close();
      for (Class<?> cls : tails.keySet()) {
        LogBufferTail<?> logBufferTail = tails.remove(cls);
        logBufferTail.cancel(true);
        logBufferTail.close();
      }
      if (cachedExecutor != null) {
        cachedExecutor.shutdown();
      }
      excerptTailer.close();
      rollingChronicle.close();
    }
  }

  /**
   * @return the current write writeIndex.
   * @throws IOException
   */
  public long getWriteIndex() throws IOException {
    synchronized (excerptAppender) {
      return excerptAppender.index();
    }
  }

  /**
   * Cancel the periodic tail task.
   */
  public <T> void cancel(Class<? extends Tail<T>> cls) throws IOException {
    cancel(cls, false);
  }

  /**
   * Cancel the periodic tail task.
   *
   * @param mayInterruptIfRunning if the thread executing this
   *                              task should be interrupted; otherwise, in-progress tasks are allowed
   *                              to complete
   */
  public <T> void cancel(Class<? extends Tail<T>> cls, boolean mayInterruptIfRunning) throws IOException {
    LogBufferTail<?> logBufferTail = tails.remove(cls);
    if (logBufferTail != null) {
      logBufferTail.cancel(mayInterruptIfRunning);
    }
  }

  /**
   * Forwards the log processing periodically by notifying the tail each round. All logs that are unprocessed
   * will be given each round. Logs are not duplicated. If a failure occur, all unprocessed logs are
   * retried next round.
   *
   * @param delay the delay between the termination of one execution and the commencement of the next.
   * @param unit  time unit of the delay parameter.
   */
  public void forwardWithFixedDelay(Tail<?> tail, int delay, TimeUnit unit) throws IOException {
    LogBufferTail<?> logBufferTail = putIfAbsent(tail);
    logBufferTail.forwardWithFixedDelay(delay, unit);
  }

  /**
   * Forwards the log processing periodically by notifying the tail each round. Logs are given
   * iteratively in chunks according to a certain period of time until all unprocessed logs are
   * finished. Logs are not duplicated. If a failure occur, the same chunk is retried next round.
   *
   * @param chunkMs how long each period of logs to be processed
   * @param delay   the delay between the termination of one execution and the commencement of the next.
   * @param unit    time unit of the delay parameter.
   * @throws IOException
   */
  public void forwardTimeChunksWithFixedDelay(Tail<?> tail, long chunkMs, int delay, TimeUnit unit) throws IOException {
    LogBufferTail<?> logBufferTail = putIfAbsent(tail, chunkMs);
    logBufferTail.forwardWithFixedDelay(delay, unit);
  }

  private <T> LogBufferTail<T> putIfAbsent(Tail<?> tail) throws IOException {
    LogBufferTail<?> logBufferTail = tails.putIfAbsent(tail.getClass(), new LogBufferTail<>(this, tail));
    if (logBufferTail == null) {
      logBufferTail = tails.get(tail.getClass());
    }
    return (LogBufferTail<T>) logBufferTail;
  }

  private <T> LogBufferTail<T> putIfAbsent(Tail<?> tail, long chunkMs) throws IOException {
    LogBufferTail<?> logBufferTail = tails.putIfAbsent(tail.getClass(), new LogBufferTailChunk<>(this, tail, chunkMs));
    if (logBufferTail == null) {
      logBufferTail = tails.get(tail.getClass());
    }
    return (LogBufferTail<T>) logBufferTail;
  }

  /**
   * Returns the index for a specific tail type.
   *
   * @return index for a specific tail type.
   * @throws IOException
   */
  public <T> long getReadIndex(Class<? extends Tail<T>> cls) throws IOException {
    LogBufferTail<?> logBufferTail = checkNotNull(tails.get(cls), "Tail type not registered " + cls);
    return logBufferTail.getReadIndex();
  }


  public static class Builder {
    private ChronicleConfig config = ChronicleConfig.LARGE.clone();
    private Optional<String> basePath = Optional.absent();
    private Serializers serializers = new Serializers();

    public Builder() {
      config.indexFileExcerpts(Short.MAX_VALUE);
    }

    public Builder basePath(String basePath) {
      this.basePath = Optional.fromNullable(basePath);
      return this;
    }

    public Builder addSerializer(ObjectLogSerializer serializer) {
      serializers.addSerializer(serializer);
      return this;
    }

    public Builder logsPerFile(int logsPerFile) {
      config.indexFileExcerpts(logsPerFile);
      return this;
    }

    public LogBuffer build() throws IOException {
      return new LogBuffer(this);
    }
  }
}
