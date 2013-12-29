### logbuffers - fast persistent buffers for streaming data. 


The purpose of logbuffers is to provide simple, reliable, low latency persistence buffers for high throughput data feeds. 

Every buffer consist of an ordered, immutable sequence of logs that is continually appended to — much like a commit log.
Every log is given a sequential index number (position/offset) that uniquely identifies each log within the buffer.

Buffers are stored in continuously rolling files and every buffer retain every log written to it - whether or not it have been consumed - until the physical file is manually removed. 

Normally a consumer will advance its index linearly as it reads logs, but can in fact consume logs in any order it likes. A consumer may want reset the index to an arbitrary position in order to reprocess logs in failure scenarios for example.

### Performance

- Writing one million 36 character logs concurrently takes approximately 1.8 seconds on modern hardware.
- Reading the same one million logs takes approximately 200 milliseconds.
- 1 hour processing downtime in a system that produce 10000 logs/sec (36m backlog) will be catched up in less than a minute.


### Reliability

- Buffers should not loose any data if the JVM crash and core dumps.
- Logs may be lost during power failures or a total OS crash.
- Buffers can be configured for synchronous writes and survive power failures.


### Selecting logs

A consumer may use the index to select any logs. A buffer does not track selected logs so the consumer itself may need to keep track of log indexes to avoid loosing or duplicating logs. 

```java
// create a buffer
LogBuffer buffer = new Builder().basePath("/tmp/logbuffer").build();

// write to buffer
buffer.write("log message".getBytes());

// select all logs from index 0 up until the most recent log written.
List<Log> logs = logBuffer.select(0);

// select all logs between index 10 - 20
List<Log> logs = logBuffer.select(10, 20);

```


### Tailing logs

A buffer can track log processing through tail instances. Each tail instance have a separate and persistent read index. The read index can be forwarded periodically and/or manually. Logs are considered successfully processed by the tail instance if no exception occured during the process() method. Any exception force the logs to be re-delivered next round, maybe along with additional unseen/new logs.


```java

LogTail tail = new LogTail();

// manual tail forwarding
buffer.forward(tail)

// schedule periodic tail forwarding
buffer.forwardWithFixedDelay(tail, 500, TimeUnit.MILLISECONDS);

// cancel tail schedule
buffer.cancel(tail);

class LogTail implements Tail<Log> {
  public void process(List<Log> logs) { 
    // group, aggregate, sum or whatever 
  }
}

```


### Object logs

Logs can be written and read in any object format in the same way as the raw log buffer. Note that each
tail instance track its specific type ONLY. This is by design so that different log types can be processed and
reported separately.

```java

// using protobufs
LogBuffer buffer = new Builder().addSerializer(new ProtobufSerializer()).build();

buffer.forwardWithFixedDelay(new PageViewTail(), 500, TimeUnit.MILLISECONDS);
buffer.forwardWithFixedDelay(new VisitTail(), 1, TimeUnit.SECONDS);

// write different types of protobuf logs to buffer
objectBuffer.write(PageView.newBuilder().setMsg("1").build());
objectBuffer.write(Visit.newBuilder().setMsg("1").build());
objectBuffer.write(PageView.newBuilder().setMsg("2").build());

// select only PageView protobufs
List<PageView> pageViews = objectBuffer.select(PageView.class, 0);

class PageViewTail implements Tail<PageView> {
  public void process(List<PageView> pageViews) { 
    // group and report 
  }
}

class VisitTail implements Tail<Visit> {
  public void process(List<Visit> visits) { 
    // group and report 
  }
}

```
