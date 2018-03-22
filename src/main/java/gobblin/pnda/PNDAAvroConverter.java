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
import java.lang.RuntimeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.generic.GenericDatumReader;

import gobblin.configuration.WorkUnitState;
import gobblin.converter.Converter;
import gobblin.converter.DataConversionException;
import gobblin.util.EmptyIterable;
import gobblin.converter.SchemaConversionException;
import gobblin.converter.SingleRecordIterable;

/**
 * An implementation of {@link Converter}.
 *
 * <p>
 *   This converter converts the input string schema into an
 *   Avro {@link org.apache.avro.Schema} and each input byte[] document
 *   into an Avro {@link org.apache.avro.generic.GenericRecord}.
 * </p>
 */
public class PNDAAvroConverter extends PNDAAbstractConverter {

  private GenericDatumReader<Record> reader;
  private static final Logger log = LoggerFactory.getLogger(PNDAAvroConverter.class);
  private boolean wrap = true; 

  public void close() throws IOException {
    super.close();
  }
  private boolean logOnce = true;

  @Override
  public Schema convertSchema(String topic, WorkUnitState workUnit)
      throws SchemaConversionException {

    Schema outputSchema = super.convertSchema(topic, workUnit);
    AvroTopicConfig avroconfig = (AvroTopicConfig)getConfig();
    Schema inputSchema = parseSchema(avroconfig.getSchema());
    /*
     * If the output schema and the input schema are identical
     * and the source and timestamp don't need to be altered
     * then wrapping it again in an envelop would just be a waste of space.
     * In this case, the record will just be passed trough without wrapping 
     * it in a new envelop. 
     */
    if( outputSchema.equals(inputSchema) &&
        avroconfig.hasSource() && SOURCE_FIELD.equals(avroconfig.getSourceField()) &&
        avroconfig.hasTimeStamp() && TIMESTAMP_FIELD.equals(avroconfig.getTimestampField())
      ) {
      this.wrap = false;
      log.info("Already wrapped with the output schema.");
    } else {
      log.info(String.format("inputSchema  %s ", inputSchema));
      log.info(String.format("differes from"));
      log.info(String.format("outputSchema %s", outputSchema));
      this.wrap = true;
    }
    this.reader = new GenericDatumReader<>(inputSchema);
    return outputSchema;
  }

  @Override
  public Iterable<GenericRecord> convertRecord(Schema schema, byte[] inputRecord,
                                               WorkUnitState workUnit)
      throws DataConversionException {

      Decoder binaryDecoder = DecoderFactory.get().binaryDecoder(inputRecord, null);
      GenericRecord record = null;
      try {
        record = reader.read(null, binaryDecoder);
      } catch(IOException | RuntimeException ex) {
        writeErrorData(inputRecord, "Unable to deserialize data");
        return new EmptyIterable<GenericRecord>();
      }
      
      if(!wrap) {
        return new SingleRecordIterable<GenericRecord>(record);
      }
      if(logOnce) log.debug(String.format("Input record %s", record));
      AvroTopicConfig avroConfig =(AvroTopicConfig) getConfig(); 
      Object source = null;
      Object timestamp = null;

      if(avroConfig.hasSource()) {
        source = record.get(avroConfig.getSourceField());
        if(logOnce) log.debug(String.format("Extracted source: %s=%s",avroConfig.getSourceField(),source));
      }
      if(avroConfig.hasTimeStamp()) {
        timestamp = record.get(avroConfig.getTimestampField());
        if(logOnce) log.debug(String.format("Extracted timestamp: %s=%s", avroConfig.getTimestampField(), timestamp));
      }
      record = generateRecord(inputRecord, workUnit, source, timestamp);
      if(logOnce) log.debug(String.format("Created record %s", record)); 
      logOnce=false;
      return new SingleRecordIterable<GenericRecord>(record);

    }
    public boolean valdidateConfig(TopicConfig config) {
      return (config instanceof AvroTopicConfig);
    }
}
