/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gobblin.pnda;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.avro.Schema;
import org.apache.avro.file.CodecFactory;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumWriter;
import org.apache.hadoop.fs.FileSystem;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import gobblin.configuration.ConfigurationKeys;
import gobblin.configuration.State;
import gobblin.util.ForkOperatorUtils;
import gobblin.util.WriterUtils;

import gobblin.writer.FsDataWriter;
import gobblin.writer.FsDataWriterBuilder;

/**
 * An extension to {@link FsDataWriter} that writes in Avro format in the form of {@link GenericRecord}s.
 *
 * <p>
 *   This implementation allows users to specify the {@link CodecFactory} to use through the configuration
 *   property {@link ConfigurationKeys#WRITER_CODEC_TYPE}. By default, the deflate codec is used.
 * </p>
 *
 * @author Yinan Li
 * @author Stephane Lejeune
 */
public class AvroHdfsDataWriter extends FsDataWriter<GenericRecord> {

  private final Schema schema;
  private final OutputStream stagingFileOutputStream;
  private final DatumWriter<GenericRecord> datumWriter;
  private final DataFileWriter<GenericRecord> writer;
  private final Map<String, String> metadata;
  public static final String WRITER_FILE_AVRO_METADATA = "writer.file.avro.metadata";

  // Number of records successfully written
  protected final AtomicLong count = new AtomicLong(0);

  public AvroHdfsDataWriter(FsDataWriterBuilder<Schema, GenericRecord> builder, State state) throws IOException {
    super(builder, state);

    CodecFactory codecFactory = WriterUtils.getCodecFactory(
        Optional.fromNullable(this.properties.getProp(ForkOperatorUtils
            .getPropertyNameForBranch(ConfigurationKeys.WRITER_CODEC_TYPE, this.numBranches, this.branchId))),
        Optional.fromNullable(this.properties.getProp(ForkOperatorUtils
            .getPropertyNameForBranch(ConfigurationKeys.WRITER_DEFLATE_LEVEL, this.numBranches, this.branchId))));

    this.schema = builder.getSchema();
    this.metadata = collectMetadata(state);
    this.stagingFileOutputStream = createStagingFileOutputStream();
    this.datumWriter = new GenericDatumWriter<>();
    this.writer = this.closer.register(createDataFileWriter(codecFactory));
  }

  public FileSystem getFileSystem() {
    return this.fs;
  }

  @Override
  public void write(GenericRecord record) throws IOException {
    Preconditions.checkNotNull(record);

    this.writer.append(record);
    // Only increment when write is successful
    this.count.incrementAndGet();
  }

  @Override
  public long recordsWritten() {
    return this.count.get();
  }

  /**
   * Create a new {@link DataFileWriter} for writing Avro records.
   *
   * @param codecFactory a {@link CodecFactory} object for building the compression codec
   * @throws IOException if there is something wrong creating a new {@link DataFileWriter}
   */
  private DataFileWriter<GenericRecord> createDataFileWriter(CodecFactory codecFactory) throws IOException {
    @SuppressWarnings("resource")
    DataFileWriter<GenericRecord> writer = new DataFileWriter<>(this.datumWriter);
    writer.setCodec(codecFactory);
    this.metadata.forEach((key, value) -> writer.setMeta(key, value));
    // Open the file and return the DataFileWriter
    return writer.create(this.schema, this.stagingFileOutputStream);
  }

  private static Map<String, String> collectMetadata(State state) {
    Map<String, String> map = new HashMap<String, String>();
    List<String> keys = state.getPropAsList(WRITER_FILE_AVRO_METADATA);
    keys.forEach((key) -> map.put(key, state.getProp(key)));
    return map;
  }

  @Override
  public boolean isSpeculativeAttemptSafe() {
    return this.writerAttemptIdOptional.isPresent() && this.getClass() == AvroHdfsDataWriter.class;
  }
}
