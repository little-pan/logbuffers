package org.deephacks.logbuffers;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class DateRanges {
  private static SimpleDateFormat DAY_FORMAT = new SimpleDateFormat("yyyy-MM-dd");
  private static SimpleDateFormat HOUR_FORMAT = new SimpleDateFormat("yyyy-MM-dd-HH-z");
  private static SimpleDateFormat MINUTE_FORMAT = new SimpleDateFormat("yyyy-MM-dd-HH-mm-z");
  private static SimpleDateFormat SECOND_FORMAT = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-z");
  private static SimpleDateFormat MS_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS-z");

  /** number of events allowed to occur same millisecond */
  private static final int MAX_INDEX_PER_MS = 1000;
  private long indexesPerInterval = 0;
  private final long interval;

  private final SimpleDateFormat format;
  private final TimeZone defaultTimeZone = TimeZone.getTimeZone("GMT");

  private DateRanges(TimeUnit unit, SimpleDateFormat format) {
    this.interval = unit.toMillis(1);
    this.indexesPerInterval = interval * MAX_INDEX_PER_MS;
    this.format = format;
    this.format.setTimeZone(defaultTimeZone);
  }

  /**
   * Create a second range.
   */
  public static DateRanges secondly() {
    return new DateRanges(TimeUnit.SECONDS, SECOND_FORMAT);
  }

  /**
   * Create a minute range.
   */
  public static DateRanges minutely() {
    return new DateRanges(TimeUnit.MINUTES, MINUTE_FORMAT);
  }

  /**
   * Create a hour range.
   */
  public static DateRanges hourly() {
    return new DateRanges(TimeUnit.HOURS, HOUR_FORMAT);
  }

  /**
   * Create a daily range.
   */
  public static DateRanges daily() {
    return new DateRanges(TimeUnit.DAYS, DAY_FORMAT);
  }

  /**
   * Format time according to the interval of this date range.
   */
  public String formatTime(long timestamp) {
    return format.format(new Date(timestamp));
  }

  /**
   * Get the start and stop index that belong to a certain time.
   */
  public long[] index(long time) {
    long from = (time / interval) * indexesPerInterval;
    long to = ((time + interval) / interval ) * indexesPerInterval - 1;
    return new long[] {from, to};
  }

  /**
   * Get the start time of certain index.
   */
  public long getStartTime(long index) {
    long firstIndexOfInterval = index - (index % indexesPerInterval);
    return ((firstIndexOfInterval / indexesPerInterval) * interval);
  }

  /**
   * Get the formatted time of a certain index.
   */
  public String getStartTimeFormat(long index) {
    long time = getStartTime(index);
    Date date = new Date(time);
    return format.format(date);
  }
}