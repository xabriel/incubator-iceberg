/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.spark.source;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.ReplacePartitions;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SnapshotUpdate;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.encryption.EncryptedOutputFile;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.spark.data.SparkAvroWriter;
import org.apache.iceberg.spark.data.SparkParquetWriters;
import org.apache.iceberg.util.Tasks;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.sources.v2.DataSourceOptions;
import org.apache.spark.sql.sources.v2.writer.DataSourceWriter;
import org.apache.spark.sql.sources.v2.writer.DataWriter;
import org.apache.spark.sql.sources.v2.writer.DataWriterFactory;
import org.apache.spark.sql.sources.v2.writer.WriterCommitMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS;
import static org.apache.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS;
import static org.apache.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_NUM_RETRIES;
import static org.apache.iceberg.TableProperties.COMMIT_NUM_RETRIES_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS;
import static org.apache.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT;
import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT_DEFAULT;
import static org.apache.iceberg.TableProperties.WRITE_TARGET_FILE_SIZE;
import static org.apache.iceberg.TableProperties.WRITE_TARGET_FILE_SIZE_DEFAULT;

// TODO: parameterize DataSourceWriter with subclass of WriterCommitMessage
class Writer implements DataSourceWriter {
  private static final Logger LOG = LoggerFactory.getLogger(Writer.class);

  private final Table table;
  private final FileFormat format;
  private final FileIO fileIo;
  private final EncryptionManager encryptionManager;
  private final boolean replacePartitions;
  private final String applicationId;
  private final String wapId;
  private final long targetFileSize;

  Writer(Table table, DataSourceOptions options, boolean replacePartitions, String applicationId) {
    this(table, options, replacePartitions, applicationId, null);
  }

  Writer(Table table, DataSourceOptions options, boolean replacePartitions, String applicationId, String wapId) {
    this.table = table;
    this.format = getFileFormat(table.properties(), options);
    this.fileIo = table.io();
    this.encryptionManager = table.encryption();
    this.replacePartitions = replacePartitions;
    this.applicationId = applicationId;
    this.wapId = wapId;

    long tableTargetFileSize = Long.parseLong(table.properties().getOrDefault(
        WRITE_TARGET_FILE_SIZE, String.valueOf(WRITE_TARGET_FILE_SIZE_DEFAULT)));
    this.targetFileSize = options.getLong("target-file-size", tableTargetFileSize);
  }

  private FileFormat getFileFormat(Map<String, String> tableProperties, DataSourceOptions options) {
    Optional<String> formatOption = options.get("write-format");
    String formatString = formatOption
        .orElse(tableProperties.getOrDefault(DEFAULT_FILE_FORMAT, DEFAULT_FILE_FORMAT_DEFAULT));
    return FileFormat.valueOf(formatString.toUpperCase(Locale.ENGLISH));
  }

  private boolean isWapTable() {
    return Boolean.parseBoolean(table.properties().getOrDefault(
        TableProperties.WRITE_AUDIT_PUBLISH_ENABLED, TableProperties.WRITE_AUDIT_PUBLISH_ENABLED_DEFAULT));
  }

  @Override
  public DataWriterFactory<InternalRow> createWriterFactory() {
    return new WriterFactory(
        table.spec(), format, table.locationProvider(), table.properties(), fileIo, encryptionManager, targetFileSize);
  }

  @Override
  public void commit(WriterCommitMessage[] messages) {
    if (replacePartitions) {
      replacePartitions(messages);
    } else {
      append(messages);
    }
  }

  protected void commitOperation(SnapshotUpdate<?> operation, int numFiles, String description) {
    LOG.info("Committing {} with {} files to table {}", description, numFiles, table);
    if (applicationId != null) {
      operation.set("spark.app.id", applicationId);
    }

    if (isWapTable() && wapId != null) {
      // write-audit-publish is enabled for this table and job
      // stage the changes without changing the current snapshot
      operation.set("wap.id", wapId);
      operation.stageOnly();
    }

    long start = System.currentTimeMillis();
    operation.commit(); // abort is automatically called if this fails
    long duration = System.currentTimeMillis() - start;
    LOG.info("Committed in {} ms", duration);
  }

  private void append(WriterCommitMessage[] messages) {
    AppendFiles append = table.newAppend();

    int numFiles = 0;
    for (DataFile file : files(messages)) {
      numFiles += 1;
      append.appendFile(file);
    }

    commitOperation(append, numFiles, "append");
  }

  private void replacePartitions(WriterCommitMessage[] messages) {
    ReplacePartitions dynamicOverwrite = table.newReplacePartitions();

    int numFiles = 0;
    for (DataFile file : files(messages)) {
      numFiles += 1;
      dynamicOverwrite.addFile(file);
    }

    commitOperation(dynamicOverwrite, numFiles, "dynamic partition overwrite");
  }

  @Override
  public void abort(WriterCommitMessage[] messages) {
    Tasks.foreach(files(messages))
        .retry(propertyAsInt(COMMIT_NUM_RETRIES, COMMIT_NUM_RETRIES_DEFAULT))
        .exponentialBackoff(
            propertyAsInt(COMMIT_MIN_RETRY_WAIT_MS, COMMIT_MIN_RETRY_WAIT_MS_DEFAULT),
            propertyAsInt(COMMIT_MAX_RETRY_WAIT_MS, COMMIT_MAX_RETRY_WAIT_MS_DEFAULT),
            propertyAsInt(COMMIT_TOTAL_RETRY_TIME_MS, COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT),
            2.0 /* exponential */)
        .throwFailureWhenFinished()
        .run(file -> {
          fileIo.deleteFile(file.path().toString());
        });
  }

  protected Table table() {
    return table;
  }

  protected Iterable<DataFile> files(WriterCommitMessage[] messages) {
    if (messages.length > 0) {
      return Iterables.concat(Iterables.transform(Arrays.asList(messages), message -> message != null ?
          ImmutableList.copyOf(((TaskCommit) message).files()) :
          ImmutableList.of()));
    }
    return ImmutableList.of();
  }

  private int propertyAsInt(String property, int defaultValue) {
    Map<String, String> properties = table.properties();
    String value = properties.get(property);
    if (value != null) {
      return Integer.parseInt(properties.get(property));
    }
    return defaultValue;
  }

  @Override
  public String toString() {
    return String.format("IcebergWrite(table=%s, format=%s)", table, format);
  }


  private static class TaskCommit implements WriterCommitMessage {
    private final DataFile[] files;

    TaskCommit() {
      this.files = new DataFile[0];
    }

    TaskCommit(DataFile file) {
      this.files = new DataFile[] { file };
    }

    TaskCommit(List<DataFile> files) {
      this.files = files.toArray(new DataFile[files.size()]);
    }

    DataFile[] files() {
      return files;
    }
  }

  private static class WriterFactory implements DataWriterFactory<InternalRow> {
    private final PartitionSpec spec;
    private final FileFormat format;
    private final LocationProvider locations;
    private final Map<String, String> properties;
    private final FileIO fileIo;
    private final EncryptionManager encryptionManager;
    private final long targetFileSize;

    WriterFactory(PartitionSpec spec, FileFormat format, LocationProvider locations,
                  Map<String, String> properties, FileIO fileIo, EncryptionManager encryptionManager,
                  long targetFileSize) {
      this.spec = spec;
      this.format = format;
      this.locations = locations;
      this.properties = properties;
      this.fileIo = fileIo;
      this.encryptionManager = encryptionManager;
      this.targetFileSize = targetFileSize;
    }

    @Override
    public DataWriter<InternalRow> createDataWriter(int partitionId, long taskId, long epochId) {
      OutputFileFactory<EncryptedOutputFile> fileFactory = new EncryptedOutputFileFactory(partitionId, taskId, epochId);
      AppenderFactory<InternalRow> appenderFactory = new SparkAppenderFactory();

      if (spec.fields().isEmpty()) {
        return new UnpartitionedWriter(fileFactory, format, appenderFactory, fileIo, targetFileSize);
      } else {
        return new PartitionedWriter(spec, format, appenderFactory, fileFactory, fileIo, targetFileSize);
      }
    }

    private class SparkAppenderFactory implements AppenderFactory<InternalRow> {
      @Override
      public FileAppender<InternalRow> newAppender(OutputFile file, FileFormat fileFormat) {
        Schema schema = spec.schema();
        MetricsConfig metricsConfig = MetricsConfig.fromProperties(properties);
        try {
          switch (fileFormat) {
            case PARQUET:
              return Parquet.write(file)
                  .createWriterFunc(msgType -> SparkParquetWriters.buildWriter(schema, msgType))
                  .setAll(properties)
                  .metricsConfig(metricsConfig)
                  .schema(schema)
                  .build();

            case AVRO:
              return Avro.write(file)
                  .createWriterFunc(ignored -> new SparkAvroWriter(schema))
                  .setAll(properties)
                  .schema(schema)
                  .build();

            default:
              throw new UnsupportedOperationException("Cannot write unknown format: " + fileFormat);
          }
        } catch (IOException e) {
          throw new RuntimeIOException(e);
        }
      }
    }

    private class EncryptedOutputFileFactory implements OutputFileFactory<EncryptedOutputFile> {
      private final int partitionId;
      private final long taskId;
      private final long epochId;

      EncryptedOutputFileFactory(int partitionId, long taskId, long epochId) {
        this.partitionId = partitionId;
        this.taskId = taskId;
        this.epochId = epochId;
      }

      private String generateFilename() {
        return format.addExtension(String.format("%05d-%d-%s", partitionId, taskId, UUID.randomUUID().toString()));
      }

      /**
       * Generates EncryptedOutputFile for UnpartitionedWriter.
       */
      public EncryptedOutputFile newOutputFile() {
        OutputFile file = fileIo.newOutputFile(locations.newDataLocation(generateFilename()));
        return encryptionManager.encrypt(file);
      }

      /**
       * Generates EncryptedOutputFile for PartitionedWriter.
       */
      public EncryptedOutputFile newOutputFile(PartitionKey key) {
        OutputFile rawOutputFile = fileIo.newOutputFile(locations.newDataLocation(spec, key, generateFilename()));
        return encryptionManager.encrypt(rawOutputFile);
      }
    }
  }

  private interface AppenderFactory<T> {
    FileAppender<T> newAppender(OutputFile file, FileFormat format);
  }

  private interface OutputFileFactory<T> {
    T newOutputFile();
    T newOutputFile(PartitionKey key);
  }

  private static class UnpartitionedWriter implements DataWriter<InternalRow> {
    private final FileIO fileIo;
    private FileAppender<InternalRow> currentAppender = null;
    private final OutputFileFactory<EncryptedOutputFile> fileFactory;
    private final FileFormat format;
    private final AppenderFactory<InternalRow> appenderFactory;
    private EncryptedOutputFile currentFile = null;
    private final List<DataFile> completedFiles = Lists.newArrayList();
    private final long targetFileSize;

    UnpartitionedWriter(
        OutputFileFactory<EncryptedOutputFile> fileFactory,
        FileFormat format,
        AppenderFactory<InternalRow> appenderFactory,
        FileIO fileIo,
        long targetFileSize) {
      this.fileFactory = fileFactory;
      this.format = format;
      this.appenderFactory = appenderFactory;
      this.fileIo = fileIo;
      this.targetFileSize = targetFileSize;

      openCurrent();
    }

    @Override
    public void write(InternalRow record) throws IOException {
      if (currentAppender.length() >= targetFileSize) {
        closeCurrent();
        openCurrent();
      }

      currentAppender.add(record);
    }

    @Override
    public WriterCommitMessage commit() throws IOException {
      Preconditions.checkArgument(currentAppender != null, "Commit called on a closed writer: %s", this);

      // metrics and splitOffsets are populated on close
      closeCurrent();

      return new TaskCommit(completedFiles);
    }

    @Override
    public void abort() throws IOException {
      Preconditions.checkArgument(currentAppender != null, "Abort called on a closed writer: %s", this);

      closeCurrent();

      // clean up files created by this writer
      Tasks.foreach(completedFiles)
          .throwFailureWhenFinished()
          .noRetry()
          .run(file -> fileIo.deleteFile(file.path().toString()));
    }

    private void openCurrent() {
      this.currentFile = fileFactory.newOutputFile();
      this.currentAppender = appenderFactory.newAppender(currentFile.encryptingOutputFile(), format);
    }

    private void closeCurrent() throws IOException {
      if (currentAppender != null) {
        currentAppender.close();
        // metrics are only valid after the appender is closed
        Metrics metrics = currentAppender.metrics();
        List<Long> splitOffsets = currentAppender.splitOffsets();
        this.currentAppender = null;

        if (metrics.recordCount() == 0L) {
          fileIo.deleteFile(currentFile.encryptingOutputFile());
        } else {
          DataFile dataFile = DataFiles.fromEncryptedOutputFile(currentFile, null, metrics, splitOffsets);
          completedFiles.add(dataFile);
        }

        this.currentFile = null;
      }
    }
  }

  private static class PartitionedWriter implements DataWriter<InternalRow> {
    private final Set<PartitionKey> completedPartitions = Sets.newHashSet();
    private final List<DataFile> completedFiles = Lists.newArrayList();
    private final PartitionSpec spec;
    private final FileFormat format;
    private final AppenderFactory<InternalRow> appenderFactory;
    private final OutputFileFactory<EncryptedOutputFile> fileFactory;
    private final PartitionKey key;
    private final FileIO fileIo;
    private final long targetFileSize;

    private PartitionKey currentKey = null;
    private FileAppender<InternalRow> currentAppender = null;
    private EncryptedOutputFile currentFile = null;

    PartitionedWriter(
        PartitionSpec spec,
        FileFormat format,
        AppenderFactory<InternalRow> appenderFactory,
        OutputFileFactory<EncryptedOutputFile> fileFactory,
        FileIO fileIo,
        long targetFileSize) {
      this.spec = spec;
      this.format = format;
      this.appenderFactory = appenderFactory;
      this.fileFactory = fileFactory;
      this.key = new PartitionKey(spec);
      this.fileIo = fileIo;
      this.targetFileSize = targetFileSize;
    }

    @Override
    public void write(InternalRow row) throws IOException {
      key.partition(row);

      if (!key.equals(currentKey)) {
        closeCurrent();
        completedPartitions.add(currentKey);

        if (completedPartitions.contains(key)) {
          // if rows are not correctly grouped, detect and fail the write
          PartitionKey existingKey = Iterables.find(completedPartitions, key::equals, null);
          LOG.warn("Duplicate key: {} == {}", existingKey, key);
          throw new IllegalStateException("Already closed files for partition: " + key.toPath());
        }

        this.currentKey = key.copy();
        this.currentFile = fileFactory.newOutputFile(currentKey);
        this.currentAppender = appenderFactory.newAppender(currentFile.encryptingOutputFile(), format);
      }

      if (currentAppender.length() >= targetFileSize) {
        closeCurrent();
        openCurrent();
      }

      currentAppender.add(row);
    }

    @Override
    public WriterCommitMessage commit() throws IOException {
      closeCurrent();
      return new TaskCommit(completedFiles);
    }

    @Override
    public void abort() throws IOException {
      closeCurrent();

      // clean up files created by this writer
      Tasks.foreach(completedFiles)
          .throwFailureWhenFinished()
          .noRetry()
          .run(file -> fileIo.deleteFile(file.path().toString()));
    }

    private void openCurrent() {
      this.currentFile = fileFactory.newOutputFile(currentKey);
      this.currentAppender = appenderFactory.newAppender(currentFile.encryptingOutputFile(), format);
    }

    private void closeCurrent() throws IOException {
      if (currentAppender != null) {
        currentAppender.close();
        // metrics are only valid after the appender is closed
        Metrics metrics = currentAppender.metrics();
        List<Long> splitOffsets = currentAppender.splitOffsets();
        this.currentAppender = null;

        if (metrics.recordCount() == 0L) {
          fileIo.deleteFile(currentFile.encryptingOutputFile());
        } else {
          DataFile dataFile = DataFiles.builder(spec)
              .withEncryptedOutputFile(currentFile)
              .withPartition(currentKey)
              .withMetrics(metrics)
              .withSplitOffsets(splitOffsets)
              .build();
          completedFiles.add(dataFile);
        }

        this.currentFile = null;
      }
    }
  }
}
