/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.log;

import net.kuujo.copycat.spi.CompactionStrategy;

import java.io.File;

/**
 * Log configuration.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public interface LogConfig {

  /**
   * Sets the log type.
   *
   * @param type The log type.
   */
  void setLogType(LogType type);

  /**
   * Returns the log type.
   *
   * @return The log type.
   */
  LogType getLogType();

  /**
   * Sets the log type.
   *
   * @param type The log type.
   * @return The log builder.
   */
  LogConfig withLogType(LogType type);

  /**
   * Sets the log directory.
   *
   * @param directory The log directory.
   */
  void setLogDirectory(String directory);

  /**
   * Sets the log directory.
   *
   * @param directory The log directory.
   */
  void setLogDirectory(File directory);

  /**
   * Returns the log directory.
   *
   * @return The log directory.
   */
  File getLogDirectory();

  /**
   * Sets the log directory.
   *
   * @param directory The log directory.
   * @return The log builder.
   */
  LogConfig withLogDirectory(String directory);

  /**
   * Sets the log directory.
   *
   * @param directory The log directory.
   * @return The log builder.
   */
  LogConfig withLogDirectory(File directory);

  /**
   * Sets the log compaction strategy.
   *
   * @param compactionStrategy The log compaction strategy.
   */
  void setCompactionStrategy(CompactionStrategy compactionStrategy);

  /**
   * Returns the log compaction strategy.
   *
   * @return The log compaction strategy.
   */
  CompactionStrategy getCompactionStrategy();

  /**
   * Sets the log compaction strategy.
   *
   * @param compactionStrategy The log compaction strategy.
   * @return The log builder.
   */
  LogConfig withCompactionStrategy(CompactionStrategy compactionStrategy);

}
