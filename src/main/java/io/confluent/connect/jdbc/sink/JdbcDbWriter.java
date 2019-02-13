/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License; you may not use this file
 * except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.jdbc.sink;

import org.apache.kafka.common.cache.Cache;
import org.apache.kafka.common.cache.LRUCache;
import org.apache.kafka.common.cache.SynchronizedCache;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;

import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;

//import org.apache.kafka.connect.transforms.util.SchemaUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import io.confluent.connect.jdbc.dialect.DatabaseDialect;
import io.confluent.connect.jdbc.util.CachedConnectionProvider;
import io.confluent.connect.jdbc.util.TableId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JdbcDbWriter {
  private static final Logger log = LoggerFactory.getLogger(JdbcDbWriter.class);

  private final JdbcSinkConfig config;
  private final DatabaseDialect dbDialect;
  private final DbStructure dbStructure;
  final CachedConnectionProvider cachedConnectionProvider;

  private Cache<String, Schema> schemaUpdateCache;

  JdbcDbWriter(final JdbcSinkConfig config, DatabaseDialect dbDialect, DbStructure dbStructure) {
    this.config = config;
    this.dbDialect = dbDialect;
    this.dbStructure = dbStructure;

    this.cachedConnectionProvider = new CachedConnectionProvider(this.dbDialect) {
      @Override
      protected void onConnect(Connection connection) throws SQLException {
        log.info("JdbcDbWriter Connected");
        connection.setAutoCommit(false);
      }
    };

    this.schemaUpdateCache = new SynchronizedCache<>(new LRUCache<String, Schema>(16));
  }

  void write(final Collection<SinkRecord> records) throws SQLException {
    final Connection connection = cachedConnectionProvider.getConnection();

    final Map<TableId, BufferedRecords> bufferByTable = new HashMap<>();
    for (SinkRecord record : records) {
      SinkRecord flattenedRecord = processRecord(record);

      final TableId tableId = destinationTable(flattenedRecord.topic());
      BufferedRecords buffer = bufferByTable.get(tableId);
      if (buffer == null) {
        buffer = new BufferedRecords(config, tableId, dbDialect, dbStructure, connection);
        bufferByTable.put(tableId, buffer);
      }
      buffer.add(flattenedRecord);
    }
    for (Map.Entry<TableId, BufferedRecords> entry : bufferByTable.entrySet()) {
      TableId tableId = entry.getKey();
      BufferedRecords buffer = entry.getValue();
      log.debug("Flushing records in JDBC Writer for table ID: {}", tableId);
      buffer.flush();
      buffer.close();
    }
    connection.commit();
  }

  SinkRecord processRecord(SinkRecord record) {
    // For our use case, there is no Key, so we will only operate on the value portion
    Struct value = (Struct) record.value();

    // We will demux based on the only set inner metric
    String inner = findInnerMetric( value );

    // Create flattened schema, dropping top level structs that aren't inner
    Schema updatedSchema = flattenSchema( value, inner );

    final Struct updatedValue = new Struct(updatedSchema);
    buildWithSchema(value, null, updatedValue, inner);

    return record.newRecord(record.topic() + "_" + inner,
                            record.kafkaPartition(),
                            record.keySchema(),
                            record.key(),
                            updatedSchema,
                            updatedValue,
                            record.timestamp()
                           );

  }

  String findInnerMetric(Struct record) {
    // maybe ensure only one is set? this will slow down this connector
    for (Field field : record.schema().fields()) {
      if ( field.schema().type() != Schema.Type.STRUCT ) {
        continue;
      }
      if ( record.get(field) != null ) {
        return field.name();
      }
    }

    return null;
  }

  Schema flattenSchema(Struct value, String inner) {
    Schema updatedSchema = schemaUpdateCache.get(inner);
    if (updatedSchema == null) {
      final SchemaBuilder builder = copySchemaBasics(value.schema(), SchemaBuilder.struct());
      //final SchemaBuilder builder = SchemaUtil.copySchemaBasics(value.schema(), SchemaBuilder.struct());
      Struct defaultValue = (Struct) value.schema().defaultValue();
      buildUpdatedSchema(value.schema(), null, builder, value.schema().isOptional(), defaultValue, inner);
      updatedSchema = builder.build();
      schemaUpdateCache.put(inner, updatedSchema);
    }

    return updatedSchema;
  }

  void buildUpdatedSchema(Schema schema, String fieldNamePrefix, SchemaBuilder newSchema, boolean optional, Struct defaultFromParent, String inner) {
    for (Field field : schema.fields()) {
      final String fieldName = fieldName(fieldNamePrefix, field.name());
      final boolean fieldIsOptional = optional || field.schema().isOptional();
      Object fieldDefaultValue = null;
      if (field.schema().defaultValue() != null) {
        fieldDefaultValue = field.schema().defaultValue();
      } else if (defaultFromParent != null) {
        fieldDefaultValue = defaultFromParent.get(field);
      }
      switch (field.schema().type()) {
        case INT8:
        case INT16:
        case INT32:
        case INT64:
        case FLOAT32:
        case FLOAT64:
        case BOOLEAN:
        case STRING:
        case BYTES:
          newSchema.field(fieldName, convertFieldSchema(fieldName, field.schema(), fieldIsOptional, fieldDefaultValue));
          break;
        case STRUCT:
          // Only generate the schema for the fields that will make it to this demuxed table
          // i.e. for inner, and sub-structs of inner
          if ( fieldName.equals(inner) || fieldNamePrefix != null ) {
            buildUpdatedSchema(field.schema(), newPrefix(fieldNamePrefix, fieldName), newSchema, fieldIsOptional, (Struct) fieldDefaultValue, inner);
          }
          break;
        default:
          throw new DataException("Flatten transformation does not support " + field.schema().type()
                + " for record without schemas (for field " + fieldName + ").");
      }
    }
  }

  private Schema convertFieldSchema(String fieldName, Schema orig, boolean optional, Object defaultFromParent) {
    // Note that we don't use the schema translation cache here. It might save us a bit of effort, but we really
    // only care about caching top-level schema translations.

    final SchemaBuilder builder = fieldName.equals("timestamp")
                                    ? copySchemaBasics(orig, new SchemaBuilder(Schema.Type.FLOAT64))
                                    : copySchemaBasics(orig);

    //final SchemaBuilder builder = SchemaUtil.copySchemaBasics(orig);
    if (optional)
      builder.optional();
    if (defaultFromParent != null)
      builder.defaultValue(defaultFromParent);
    return builder.build();
  }

  void buildWithSchema(Struct record, String fieldNamePrefix, Struct newRecord, String inner) {
    for (Field field : record.schema().fields()) {
      final String fieldName = fieldName(fieldNamePrefix, field.name());
      switch (field.schema().type()) {
        case INT8:
        case INT16:
        case INT32:
        case INT64:
          // Kafka wants epoch to milisec, but grafana wants epoch to sec
          // Lets just fix it here in the new record
          if (fieldName.equals("timestamp")) {
            Double adjustedEpoch = (Long) record.get(field) / 1000.0;
            newRecord.put(fieldName, adjustedEpoch);
            break;
          }
        case FLOAT32:
        case FLOAT64:
        case BOOLEAN:
        case STRING:
        case BYTES:
          newRecord.put(fieldName, record.get(field));
          break;
        case STRUCT:
          // Only propagate the fields that will make it to this demuxed table
          // i.e. for inner, and sub-structs of inner
          if ( fieldName.equals(inner) || fieldNamePrefix != null ) {
            buildWithSchema(record.getStruct(field.name()), newPrefix(fieldNamePrefix, fieldName), newRecord, inner);
          }
          break;
        default:
          throw new DataException("Flatten transformation does not support " + field.schema().type()
                + " for record without schemas (for field " + fieldName + ").");
      }
    }
  }

  public static SchemaBuilder copySchemaBasics(Schema source) {
    return copySchemaBasics(source, new SchemaBuilder(source.type()));
  }

  public static SchemaBuilder copySchemaBasics(Schema source, SchemaBuilder builder) {
    builder.name(source.name());
    builder.version(source.version());
    builder.doc(source.doc());

    final Map<String, String> params = source.parameters();
    if (params != null) {
      builder.parameters(params);
    }

    return builder;
  }

  private String fieldName(String prefix, String fieldName) {
    return (prefix == null || prefix.isEmpty()) ? fieldName : (prefix + "." + fieldName);
  }

  private String newPrefix(String oldPrefix, String fieldName) {
    return oldPrefix == null ? "" : (oldPrefix + "." + fieldName);
  }


  void closeQuietly() {
    cachedConnectionProvider.close();
  }

  TableId destinationTable(String topic) {
    final String tableName = config.tableNameFormat.replace("${topic}", topic);
    if (tableName.isEmpty()) {
      throw new ConnectException(String.format(
          "Destination table name for topic '%s' is empty using the format string '%s'",
          topic,
          config.tableNameFormat
      ));
    }
    return dbDialect.parseTableIdentifier(tableName);
  }
}
