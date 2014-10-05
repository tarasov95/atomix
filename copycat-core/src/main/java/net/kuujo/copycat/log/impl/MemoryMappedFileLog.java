/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.log.impl;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import net.kuujo.copycat.log.Compactable;
import net.kuujo.copycat.log.Entry;
import net.kuujo.copycat.log.LogIndexOutOfBoundsException;
import net.openhft.chronicle.Chronicle;
import net.openhft.chronicle.Excerpt;
import net.openhft.chronicle.ExcerptAppender;
import net.openhft.chronicle.ExcerptTailer;
import net.openhft.chronicle.IndexedChronicle;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

/**
 * Java chronicle based log implementation.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class MemoryMappedFileLog extends BaseFileLog implements Compactable {
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private static final byte DELETED = 0;
  private static final byte ACTIVE = 1;
  private final ByteBuffer buffer = ByteBuffer.allocate(4096);
  private final ByteBufferOutput output = new ByteBufferOutput(buffer);
  private final ByteBufferInput input = new ByteBufferInput(buffer);
  private File logFile;
  private Chronicle chronicle;
  private Excerpt excerpt;
  private ExcerptAppender appender;
  private ExcerptTailer tailer;
  private long firstIndex;
  private long lastIndex;
  private long syncInterval = 0;
  private ScheduledFuture<Void> syncFuture;

  public MemoryMappedFileLog(String baseName) {
    this(baseName, RaftEntry.class);
  }

  public MemoryMappedFileLog(File baseFile) {
    this(baseFile, RaftEntry.class);
  }

  public MemoryMappedFileLog(String baseName, Class<? extends Entry> entryType) {
    this(new File(baseName), entryType);
  }

  public MemoryMappedFileLog(File baseFile, Class<? extends Entry> entryType) {
    super(baseFile, entryType);
  }

  /**
   * Sets the interval at which to sync the log to disk.
   *
   * @param interval The interval at which to sync the log to disk.
   */
  public void setSyncInterval(long interval) {
    this.syncInterval = interval;
  }

  /**
   * Returns the interval at which to sync the log to disk.
   *
   * @return The interval at which to sync the log to disk.
   */
  public long getSyncInterval() {
    return syncInterval;
  }

  /**
   * Sets the interval at which to sync the log to disk, returning the log for method chaining.
   *
   * @param interval The interval at which to sync the log to disk.
   * @return The memory mapped file log for method chaining.
   */
  public MemoryMappedFileLog withSyncInterval(long interval) {
    this.syncInterval = interval;
    return this;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void open() throws IOException {
    logFile = findLogFile();
    chronicle = new IndexedChronicle(logFile.getAbsolutePath());

    excerpt = chronicle.createExcerpt();
    appender = chronicle.createAppender();
    tailer = chronicle.createTailer();

    tailer.toStart();
    while (tailer.nextIndex()) {
      long index = tailer.readLong();
      byte deleted = tailer.readByte();
      if (deleted == ACTIVE) {
        if (firstIndex == 0) {
          firstIndex = index;
        }
        lastIndex = index;
      }
    }

    if (syncInterval > 0 && syncFuture == null) {
      syncFuture = (ScheduledFuture<Void>) scheduler.scheduleAtFixedRate(() -> sync(), syncInterval, syncInterval, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Returns the next log index.
   *
   * @return The next log index.
   */
  private long nextIndex() {
    long index = ++lastIndex;
    if (firstIndex == 0) {
      firstIndex = 1;
    }
    return index;
  }

  @Override
  public long size() {
    return firstIndex > 0 ? lastIndex - firstIndex + 1 : 0;
  }

  @Override
  public boolean isEmpty() {
    return lastIndex > firstIndex;
  }

  @Override
  public long appendEntry(Entry entry) {
    long index = nextIndex();
    appender.startExcerpt();
    appender.writeLong(index);
    appender.writeByte(ACTIVE);
    kryo.writeClassAndObject(output, entry);
    byte[] bytes = output.toBytes();
    appender.writeInt(bytes.length);
    appender.write(bytes);
    output.clear();
    appender.finish();
    return index;
  }

  @Override
  public List<Long> appendEntries(Entry... entries) {
    List<Long> indices = new ArrayList<>();
    for (Entry entry : entries) {
      indices.add(appendEntry(entry));
    }
    return indices;
  }

  @Override
  public List<Long> appendEntries(List<Entry> entries) {
    List<Long> indices = new ArrayList<>();
    for (Entry entry : entries) {
      indices.add(appendEntry(entry));
    }
    return indices;
  }

  @Override
  public boolean containsEntry(long index) {
    return indexInRange(index);
  }

  @Override
  public long firstIndex() {
    return firstIndex;
  }

  @Override
  public <T extends Entry> T firstEntry() {
    return getEntry(firstIndex);
  }

  @Override
  public long lastIndex() {
    return lastIndex;
  }

  @Override
  public <T extends Entry> T lastEntry() {
    return getEntry(lastIndex);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends Entry> T getEntry(long index) {
    long matchIndex = findAbsoluteIndex(index);
    excerpt.index(matchIndex);
    excerpt.skip(9);
    int length = excerpt.readInt();
    byte[] bytes = new byte[length];
    excerpt.read(bytes);
    buffer.put(bytes);
    buffer.rewind();
    input.setBuffer(buffer);
    T entry = (T) kryo.readClassAndObject(input);
    buffer.clear();
    return entry;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends Entry> List<T> getEntries(long from, long to) {
    if (!indexInRange(from)) {
      throw new LogIndexOutOfBoundsException("From index out of bounds.");
    }
    if (!indexInRange(to)) {
      throw new LogIndexOutOfBoundsException("To index out of bounds.");
    }

    List<T> entries = new ArrayList<>();
    long matchIndex = findAbsoluteIndex(from);
    tailer.index(matchIndex);
    do {
      long index = tailer.readLong();
      byte status = tailer.readByte();
      if (status == ACTIVE) {
        int length = tailer.readInt();
        byte[] bytes = new byte[length];
        tailer.read(bytes);
        buffer.put(bytes);
        entries.add((T) kryo.readClassAndObject(input));
        buffer.clear();
        matchIndex = index;
      }
    } while (tailer.nextIndex() && matchIndex <= to);
    return entries;
  }

  @Override
  public void removeEntry(long index) {
    if (!indexInRange(index)) {
      throw new LogIndexOutOfBoundsException(String.format("Cannot remove entry at index %d", index));
    }
    long matchIndex = findAbsoluteIndex(index);
    if (matchIndex > -1) {
      tailer.index(matchIndex);
      tailer.skip(8);
      tailer.writeByte(DELETED);
    }
  }

  @Override
  public void removeAfter(long index) {
    if (!indexInRange(index)) {
      throw new LogIndexOutOfBoundsException(String.format("Cannot remove entry at index %d", index));
    }
    long matchIndex = findAbsoluteIndex(index);
    if (matchIndex > -1) {
      tailer.index(matchIndex);
      while (tailer.nextIndex()) {
        tailer.skip(8);
        tailer.writeByte(DELETED);
      }
    }
    lastIndex = index;
  }

  /**
   * Finds the absolute index of a log entry in the chronicle by log index.
   */
  private long findAbsoluteIndex(long index) {
    return excerpt.findMatch((excerpt) -> {
      long match = excerpt.readLong();
      if (match < index) {
        return -1;
      } else if (match > index) {
        return 1;
      } else {
        byte status = excerpt.readByte();
        if (status == DELETED) {
          return -1;
        }
      }
      return 0;
    });
  }

  /**
   * Returns a boolean indicating whether the given index is within the range
   * of the log.
   */
  private boolean indexInRange(long index) {
    return index >= firstIndex && index <= lastIndex;
  }

  @Override
  public void compact(long index, Entry snapshot) throws IOException {
    if (index > firstIndex) {
      // Create a new log file using the most recent timestamp.
      File newLogFile = createLogFile();
      File oldLogFile = logFile;
  
      // Create a new chronicle for the new log file.
      Chronicle chronicle = new IndexedChronicle(newLogFile.getAbsolutePath());
      ExcerptAppender appender = chronicle.createAppender();
      appender.startExcerpt();
      appender.writeLong(index);
      appender.writeByte(ACTIVE);
      kryo.writeClassAndObject(output, snapshot);
      byte[] snapshotBytes = output.toBytes();
      appender.writeInt(snapshotBytes.length);
      appender.write(snapshotBytes);
      output.clear();
      appender.finish();
  
      // Iterate through entries equal to or greater than the given index and copy them to the new chronicle.
      long matchIndex = findAbsoluteIndex(index);
      tailer.index(matchIndex);
      while (tailer.nextIndex()) {
        long entryIndex = tailer.readLong();
        byte entryStatus = tailer.readByte();
        if (entryStatus == ACTIVE) {
          int length = tailer.readInt();
          byte[] bytes = new byte[length];
          tailer.read(bytes);
          appender.startExcerpt();
          appender.writeLong(entryIndex);
          appender.writeByte(entryStatus);
          appender.writeInt(length);
          appender.write(bytes);
          appender.finish();
        }
      }
  
      // Override existing chronicle types.
      this.logFile = newLogFile;
      this.chronicle = chronicle;
      this.excerpt = chronicle.createExcerpt();
      this.appender = appender;
      this.tailer = chronicle.createTailer();
      this.firstIndex = index;
  
      // Finally, delete the old log file.
      deleteLogFile(oldLogFile);
    }
  }

  @Override
  public void sync() {
    appender.nextSynchronous(true);
  }

  @Override
  public void close() throws IOException {
    chronicle.close();
    firstIndex = 0;
    lastIndex = 0;
    if (syncFuture != null) {
      syncFuture.cancel(false);
    }
  }

  @Override
  public void delete() {
    if (chronicle != null) {
      chronicle.clear();
    }
  }

}
