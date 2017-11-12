/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.coprocessor;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static org.apache.hadoop.hbase.KeyValueUtil.createFirstOnRow;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.APPEND_ONLY_SCHEMA_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.ARRAY_SIZE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.AUTO_PARTITION_SEQ_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.CLASS_NAME_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.COLUMN_COUNT_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.COLUMN_DEF_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.COLUMN_NAME_INDEX;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.COLUMN_QUALIFIER_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.COLUMN_QUALIFIER_COUNTER_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.COLUMN_SIZE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.DATA_TABLE_NAME_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.DATA_TYPE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.DECIMAL_DIGITS_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.DEFAULT_COLUMN_FAMILY_NAME_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.DEFAULT_VALUE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.DISABLE_WAL_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.ENCODING_SCHEME_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.FAMILY_NAME_INDEX;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.IMMUTABLE_ROWS_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.INDEX_DISABLE_TIMESTAMP_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.INDEX_STATE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.INDEX_TYPE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.IS_ARRAY_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.IS_CONSTANT_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.IS_NAMESPACE_MAPPED_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.IS_ROW_TIMESTAMP_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.IS_VIEW_REFERENCED_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.JAR_PATH_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.LINK_TYPE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.MAX_VALUE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.MIN_VALUE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.MULTI_TENANT_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.NULLABLE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.NUM_ARGS_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.ORDINAL_POSITION_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.PK_NAME_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.RETURN_TYPE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SALT_BUCKETS_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SCHEMA_NAME_INDEX;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.SORT_ORDER_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.STORAGE_SCHEME_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.STORE_NULLS_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_NAME_INDEX;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_SEQ_NUM_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TABLE_TYPE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TENANT_ID_INDEX;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TRANSACTIONAL_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.TYPE_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.UPDATE_CACHE_FREQUENCY_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.USE_STATS_FOR_PARALLELIZATION_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VIEW_CONSTANT_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VIEW_INDEX_ID_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VIEW_STATEMENT_BYTES;
import static org.apache.phoenix.jdbc.PhoenixDatabaseMetaData.VIEW_TYPE_BYTES;
import static org.apache.phoenix.query.QueryConstants.DIVERGED_VIEW_BASE_COLUMN_COUNT;
import static org.apache.phoenix.schema.PTableType.INDEX;
import static org.apache.phoenix.schema.PTableType.TABLE;
import static org.apache.phoenix.util.SchemaUtil.getVarCharLength;
import static org.apache.phoenix.util.SchemaUtil.getVarChars;

import java.io.IOException;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValue.Type;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.CoprocessorException;
import org.apache.hadoop.hbase.coprocessor.CoprocessorService;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.regionserver.Region.RowLock;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.VersionInfo;
import org.apache.phoenix.cache.GlobalCache;
import org.apache.phoenix.cache.GlobalCache.FunctionBytesPtr;
import org.apache.phoenix.compile.ColumnNameTrackingExpressionCompiler;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.compile.ScanRanges;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.AddColumnRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.ClearCacheRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.ClearCacheResponse;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.ClearTableFromCacheRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.ClearTableFromCacheResponse;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.CreateFunctionRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.CreateSchemaRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.CreateTableRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.DropColumnRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.DropFunctionRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.DropSchemaRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.DropTableRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.GetFunctionsRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.GetSchemaRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.GetTableRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.GetVersionRequest;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.GetVersionResponse;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.MetaDataResponse;
import org.apache.phoenix.coprocessor.generated.MetaDataProtos.UpdateIndexStateRequest;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.expression.KeyValueColumnExpression;
import org.apache.phoenix.expression.LiteralExpression;
import org.apache.phoenix.expression.ProjectedColumnExpression;
import org.apache.phoenix.expression.RowKeyColumnExpression;
import org.apache.phoenix.expression.visitor.StatelessTraverseAllExpressionVisitor;
import org.apache.phoenix.hbase.index.covered.update.ColumnReference;
import org.apache.phoenix.hbase.index.util.GenericKeyValueBuilder;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.hbase.index.util.KeyValueBuilder;
import org.apache.phoenix.index.IndexMaintainer;
import org.apache.phoenix.iterate.ResultIterator;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixDatabaseMetaData;
import org.apache.phoenix.jdbc.PhoenixResultSet;
import org.apache.phoenix.jdbc.PhoenixStatement;
import org.apache.phoenix.metrics.Metrics;
import org.apache.phoenix.parse.LiteralParseNode;
import org.apache.phoenix.parse.PFunction;
import org.apache.phoenix.parse.PFunction.FunctionArgument;
import org.apache.phoenix.parse.PSchema;
import org.apache.phoenix.parse.ParseNode;
import org.apache.phoenix.parse.SQLParser;
import org.apache.phoenix.protobuf.ProtobufUtil;
import org.apache.phoenix.query.KeyRange;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.schema.ColumnFamilyNotFoundException;
import org.apache.phoenix.schema.ColumnNotFoundException;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PColumnFamily;
import org.apache.phoenix.schema.PColumnImpl;
import org.apache.phoenix.schema.PIndexState;
import org.apache.phoenix.schema.PMetaDataEntity;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.PNameFactory;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.PTable.EncodedCQCounter;
import org.apache.phoenix.schema.PTable.ImmutableStorageScheme;
import org.apache.phoenix.schema.PTable.IndexType;
import org.apache.phoenix.schema.PTable.LinkType;
import org.apache.phoenix.schema.PTable.QualifierEncodingScheme;
import org.apache.phoenix.schema.PTable.ViewType;
import org.apache.phoenix.schema.PTableImpl;
import org.apache.phoenix.schema.PTableType;
import org.apache.phoenix.schema.SaltingUtil;
import org.apache.phoenix.schema.SequenceAllocation;
import org.apache.phoenix.schema.SequenceAlreadyExistsException;
import org.apache.phoenix.schema.SequenceKey;
import org.apache.phoenix.schema.SequenceNotFoundException;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.TableNotFoundException;
import org.apache.phoenix.schema.TableProperty;
import org.apache.phoenix.schema.types.PBinary;
import org.apache.phoenix.schema.types.PBoolean;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PInteger;
import org.apache.phoenix.schema.types.PLong;
import org.apache.phoenix.schema.types.PSmallint;
import org.apache.phoenix.schema.types.PTinyint;
import org.apache.phoenix.schema.types.PVarbinary;
import org.apache.phoenix.schema.types.PVarchar;
import org.apache.phoenix.trace.util.Tracing;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.EncodedColumnsUtil;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.apache.phoenix.util.IndexUtil;
import org.apache.phoenix.util.KeyValueUtil;
import org.apache.phoenix.util.MetaDataUtil;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.ServerUtil;
import org.apache.phoenix.util.UpgradeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.google.protobuf.Service;

/**
 *
 * Endpoint co-processor through which all Phoenix metadata mutations flow.
 * We only allow mutations to the latest version of a Phoenix table (i.e. the
 * timeStamp must be increasing).
 * For adding/dropping columns use a sequence number on the table to ensure that
 * the client has the latest version.
 * The timeStamp on the table correlates with the timeStamp on the data row.
 * TODO: we should enforce that a metadata mutation uses a timeStamp bigger than
 * any in use on the data table, b/c otherwise we can end up with data rows that
 * are not valid against a schema row.
 *
 *
 * @since 0.1
 */
@SuppressWarnings("deprecation")
public class MetaDataEndpointImpl extends MetaDataProtocol implements CoprocessorService, Coprocessor {
    private static final Logger logger = LoggerFactory.getLogger(MetaDataEndpointImpl.class);

    // Column to track tables that have been upgraded based on PHOENIX-2067
    public static final String ROW_KEY_ORDER_OPTIMIZABLE = "ROW_KEY_ORDER_OPTIMIZABLE";
    public static final byte[] ROW_KEY_ORDER_OPTIMIZABLE_BYTES = Bytes.toBytes(ROW_KEY_ORDER_OPTIMIZABLE);

    // KeyValues for Table
    private static final KeyValue TABLE_TYPE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, TABLE_TYPE_BYTES);
    private static final KeyValue TABLE_SEQ_NUM_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, TABLE_SEQ_NUM_BYTES);
    private static final KeyValue COLUMN_COUNT_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, COLUMN_COUNT_BYTES);
    private static final KeyValue SALT_BUCKETS_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, SALT_BUCKETS_BYTES);
    private static final KeyValue PK_NAME_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, PK_NAME_BYTES);
    private static final KeyValue DATA_TABLE_NAME_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, DATA_TABLE_NAME_BYTES);
    private static final KeyValue INDEX_STATE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, INDEX_STATE_BYTES);
    private static final KeyValue IMMUTABLE_ROWS_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, IMMUTABLE_ROWS_BYTES);
    private static final KeyValue VIEW_EXPRESSION_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, VIEW_STATEMENT_BYTES);
    private static final KeyValue DEFAULT_COLUMN_FAMILY_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, DEFAULT_COLUMN_FAMILY_NAME_BYTES);
    private static final KeyValue DISABLE_WAL_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, DISABLE_WAL_BYTES);
    private static final KeyValue MULTI_TENANT_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, MULTI_TENANT_BYTES);
    private static final KeyValue VIEW_TYPE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, VIEW_TYPE_BYTES);
    private static final KeyValue VIEW_INDEX_ID_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, VIEW_INDEX_ID_BYTES);
    private static final KeyValue INDEX_TYPE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, INDEX_TYPE_BYTES);
    private static final KeyValue INDEX_DISABLE_TIMESTAMP_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, INDEX_DISABLE_TIMESTAMP_BYTES);
    private static final KeyValue STORE_NULLS_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, STORE_NULLS_BYTES);
    private static final KeyValue EMPTY_KEYVALUE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, QueryConstants.EMPTY_COLUMN_BYTES);
    private static final KeyValue BASE_COLUMN_COUNT_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, PhoenixDatabaseMetaData.BASE_COLUMN_COUNT_BYTES);
    private static final KeyValue ROW_KEY_ORDER_OPTIMIZABLE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, ROW_KEY_ORDER_OPTIMIZABLE_BYTES);
    private static final KeyValue TRANSACTIONAL_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, TRANSACTIONAL_BYTES);
    private static final KeyValue UPDATE_CACHE_FREQUENCY_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, UPDATE_CACHE_FREQUENCY_BYTES);
    private static final KeyValue IS_NAMESPACE_MAPPED_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY,
            TABLE_FAMILY_BYTES, IS_NAMESPACE_MAPPED_BYTES);
    private static final KeyValue AUTO_PARTITION_SEQ_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, AUTO_PARTITION_SEQ_BYTES);
    private static final KeyValue APPEND_ONLY_SCHEMA_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, APPEND_ONLY_SCHEMA_BYTES);
    private static final KeyValue STORAGE_SCHEME_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, STORAGE_SCHEME_BYTES);
    private static final KeyValue ENCODING_SCHEME_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, ENCODING_SCHEME_BYTES);
    private static final KeyValue USE_STATS_FOR_PARALLELIZATION_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, USE_STATS_FOR_PARALLELIZATION_BYTES);
    
    private static final List<KeyValue> TABLE_KV_COLUMNS = Arrays.<KeyValue>asList(
            EMPTY_KEYVALUE_KV,
            TABLE_TYPE_KV,
            TABLE_SEQ_NUM_KV,
            COLUMN_COUNT_KV,
            SALT_BUCKETS_KV,
            PK_NAME_KV,
            DATA_TABLE_NAME_KV,
            INDEX_STATE_KV,
            IMMUTABLE_ROWS_KV,
            VIEW_EXPRESSION_KV,
            DEFAULT_COLUMN_FAMILY_KV,
            DISABLE_WAL_KV,
            MULTI_TENANT_KV,
            VIEW_TYPE_KV,
            VIEW_INDEX_ID_KV,
            INDEX_TYPE_KV,
            INDEX_DISABLE_TIMESTAMP_KV,
            STORE_NULLS_KV,
            BASE_COLUMN_COUNT_KV,
            ROW_KEY_ORDER_OPTIMIZABLE_KV,
            TRANSACTIONAL_KV,
            UPDATE_CACHE_FREQUENCY_KV,
            IS_NAMESPACE_MAPPED_KV,
            AUTO_PARTITION_SEQ_KV,
            APPEND_ONLY_SCHEMA_KV,
            STORAGE_SCHEME_KV,
            ENCODING_SCHEME_KV,
            USE_STATS_FOR_PARALLELIZATION_KV
            );
    static {
        Collections.sort(TABLE_KV_COLUMNS, KeyValue.COMPARATOR);
    }

    private static final int TABLE_TYPE_INDEX = TABLE_KV_COLUMNS.indexOf(TABLE_TYPE_KV);
    private static final int TABLE_SEQ_NUM_INDEX = TABLE_KV_COLUMNS.indexOf(TABLE_SEQ_NUM_KV);
    private static final int COLUMN_COUNT_INDEX = TABLE_KV_COLUMNS.indexOf(COLUMN_COUNT_KV);
    private static final int SALT_BUCKETS_INDEX = TABLE_KV_COLUMNS.indexOf(SALT_BUCKETS_KV);
    private static final int PK_NAME_INDEX = TABLE_KV_COLUMNS.indexOf(PK_NAME_KV);
    private static final int DATA_TABLE_NAME_INDEX = TABLE_KV_COLUMNS.indexOf(DATA_TABLE_NAME_KV);
    private static final int INDEX_STATE_INDEX = TABLE_KV_COLUMNS.indexOf(INDEX_STATE_KV);
    private static final int IMMUTABLE_ROWS_INDEX = TABLE_KV_COLUMNS.indexOf(IMMUTABLE_ROWS_KV);
    private static final int VIEW_STATEMENT_INDEX = TABLE_KV_COLUMNS.indexOf(VIEW_EXPRESSION_KV);
    private static final int DEFAULT_COLUMN_FAMILY_INDEX = TABLE_KV_COLUMNS.indexOf(DEFAULT_COLUMN_FAMILY_KV);
    private static final int DISABLE_WAL_INDEX = TABLE_KV_COLUMNS.indexOf(DISABLE_WAL_KV);
    private static final int MULTI_TENANT_INDEX = TABLE_KV_COLUMNS.indexOf(MULTI_TENANT_KV);
    private static final int VIEW_TYPE_INDEX = TABLE_KV_COLUMNS.indexOf(VIEW_TYPE_KV);
    private static final int VIEW_INDEX_ID_INDEX = TABLE_KV_COLUMNS.indexOf(VIEW_INDEX_ID_KV);
    private static final int INDEX_TYPE_INDEX = TABLE_KV_COLUMNS.indexOf(INDEX_TYPE_KV);
    private static final int STORE_NULLS_INDEX = TABLE_KV_COLUMNS.indexOf(STORE_NULLS_KV);
    private static final int BASE_COLUMN_COUNT_INDEX = TABLE_KV_COLUMNS.indexOf(BASE_COLUMN_COUNT_KV);
    private static final int ROW_KEY_ORDER_OPTIMIZABLE_INDEX = TABLE_KV_COLUMNS.indexOf(ROW_KEY_ORDER_OPTIMIZABLE_KV);
    private static final int TRANSACTIONAL_INDEX = TABLE_KV_COLUMNS.indexOf(TRANSACTIONAL_KV);
    private static final int UPDATE_CACHE_FREQUENCY_INDEX = TABLE_KV_COLUMNS.indexOf(UPDATE_CACHE_FREQUENCY_KV);
    private static final int INDEX_DISABLE_TIMESTAMP = TABLE_KV_COLUMNS.indexOf(INDEX_DISABLE_TIMESTAMP_KV);
    private static final int IS_NAMESPACE_MAPPED_INDEX = TABLE_KV_COLUMNS.indexOf(IS_NAMESPACE_MAPPED_KV);
    private static final int AUTO_PARTITION_SEQ_INDEX = TABLE_KV_COLUMNS.indexOf(AUTO_PARTITION_SEQ_KV);
    private static final int APPEND_ONLY_SCHEMA_INDEX = TABLE_KV_COLUMNS.indexOf(APPEND_ONLY_SCHEMA_KV);
    private static final int STORAGE_SCHEME_INDEX = TABLE_KV_COLUMNS.indexOf(STORAGE_SCHEME_KV);
    private static final int QUALIFIER_ENCODING_SCHEME_INDEX = TABLE_KV_COLUMNS.indexOf(ENCODING_SCHEME_KV);
    private static final int USE_STATS_FOR_PARALLELIZATION_INDEX = TABLE_KV_COLUMNS.indexOf(USE_STATS_FOR_PARALLELIZATION_KV);

    // KeyValues for Column
    private static final KeyValue DECIMAL_DIGITS_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, DECIMAL_DIGITS_BYTES);
    private static final KeyValue COLUMN_SIZE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, COLUMN_SIZE_BYTES);
    private static final KeyValue NULLABLE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, NULLABLE_BYTES);
    private static final KeyValue DATA_TYPE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, DATA_TYPE_BYTES);
    private static final KeyValue ORDINAL_POSITION_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, ORDINAL_POSITION_BYTES);
    private static final KeyValue SORT_ORDER_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, SORT_ORDER_BYTES);
    private static final KeyValue ARRAY_SIZE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, ARRAY_SIZE_BYTES);
    private static final KeyValue VIEW_CONSTANT_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, VIEW_CONSTANT_BYTES);
    private static final KeyValue IS_VIEW_REFERENCED_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, IS_VIEW_REFERENCED_BYTES);
    private static final KeyValue COLUMN_DEF_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, COLUMN_DEF_BYTES);
    private static final KeyValue IS_ROW_TIMESTAMP_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, IS_ROW_TIMESTAMP_BYTES);
    private static final KeyValue COLUMN_QUALIFIER_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, COLUMN_QUALIFIER_BYTES);

    private static final List<KeyValue> COLUMN_KV_COLUMNS = Arrays.<KeyValue>asList(
            DECIMAL_DIGITS_KV,
            COLUMN_SIZE_KV,
            NULLABLE_KV,
            DATA_TYPE_KV,
            ORDINAL_POSITION_KV,
            SORT_ORDER_KV,
            DATA_TABLE_NAME_KV, // included in both column and table row for metadata APIs
            ARRAY_SIZE_KV,
            VIEW_CONSTANT_KV,
            IS_VIEW_REFERENCED_KV,
            COLUMN_DEF_KV,
            IS_ROW_TIMESTAMP_KV,
            COLUMN_QUALIFIER_KV
            );

    static {
        Collections.sort(COLUMN_KV_COLUMNS, KeyValue.COMPARATOR);
    }
    private static final KeyValue QUALIFIER_COUNTER_KV = KeyValue.createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, COLUMN_QUALIFIER_COUNTER_BYTES);
    private static final int DECIMAL_DIGITS_INDEX = COLUMN_KV_COLUMNS.indexOf(DECIMAL_DIGITS_KV);
    private static final int COLUMN_SIZE_INDEX = COLUMN_KV_COLUMNS.indexOf(COLUMN_SIZE_KV);
    private static final int NULLABLE_INDEX = COLUMN_KV_COLUMNS.indexOf(NULLABLE_KV);
    private static final int DATA_TYPE_INDEX = COLUMN_KV_COLUMNS.indexOf(DATA_TYPE_KV);
    private static final int ORDINAL_POSITION_INDEX = COLUMN_KV_COLUMNS.indexOf(ORDINAL_POSITION_KV);
    private static final int SORT_ORDER_INDEX = COLUMN_KV_COLUMNS.indexOf(SORT_ORDER_KV);
    private static final int ARRAY_SIZE_INDEX = COLUMN_KV_COLUMNS.indexOf(ARRAY_SIZE_KV);
    private static final int VIEW_CONSTANT_INDEX = COLUMN_KV_COLUMNS.indexOf(VIEW_CONSTANT_KV);
    private static final int IS_VIEW_REFERENCED_INDEX = COLUMN_KV_COLUMNS.indexOf(IS_VIEW_REFERENCED_KV);
    private static final int COLUMN_DEF_INDEX = COLUMN_KV_COLUMNS.indexOf(COLUMN_DEF_KV);
    private static final int IS_ROW_TIMESTAMP_INDEX = COLUMN_KV_COLUMNS.indexOf(IS_ROW_TIMESTAMP_KV);
    private static final int COLUMN_QUALIFIER_INDEX = COLUMN_KV_COLUMNS.indexOf(COLUMN_QUALIFIER_KV);

    private static final int LINK_TYPE_INDEX = 0;

    private static final KeyValue CLASS_NAME_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, CLASS_NAME_BYTES);
    private static final KeyValue JAR_PATH_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, JAR_PATH_BYTES);
    private static final KeyValue RETURN_TYPE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, RETURN_TYPE_BYTES);
    private static final KeyValue NUM_ARGS_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, NUM_ARGS_BYTES);
    private static final KeyValue TYPE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, TYPE_BYTES);
    private static final KeyValue IS_CONSTANT_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, IS_CONSTANT_BYTES);
    private static final KeyValue DEFAULT_VALUE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, DEFAULT_VALUE_BYTES);
    private static final KeyValue MIN_VALUE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, MIN_VALUE_BYTES);
    private static final KeyValue MAX_VALUE_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, MAX_VALUE_BYTES);
    private static final KeyValue IS_ARRAY_KV = createFirstOnRow(ByteUtil.EMPTY_BYTE_ARRAY, TABLE_FAMILY_BYTES, IS_ARRAY_BYTES);

    private static final List<KeyValue> FUNCTION_KV_COLUMNS = Arrays.<KeyValue>asList(
        EMPTY_KEYVALUE_KV,
        CLASS_NAME_KV,
        JAR_PATH_KV,
        RETURN_TYPE_KV,
        NUM_ARGS_KV
        );
    static {
        Collections.sort(FUNCTION_KV_COLUMNS, KeyValue.COMPARATOR);
    }

    private static final int CLASS_NAME_INDEX = FUNCTION_KV_COLUMNS.indexOf(CLASS_NAME_KV);
    private static final int JAR_PATH_INDEX = FUNCTION_KV_COLUMNS.indexOf(JAR_PATH_KV);
    private static final int RETURN_TYPE_INDEX = FUNCTION_KV_COLUMNS.indexOf(RETURN_TYPE_KV);
    private static final int NUM_ARGS_INDEX = FUNCTION_KV_COLUMNS.indexOf(NUM_ARGS_KV);

    private static final List<KeyValue> FUNCTION_ARG_KV_COLUMNS = Arrays.<KeyValue>asList(
        TYPE_KV,
        IS_ARRAY_KV,
        IS_CONSTANT_KV,
        DEFAULT_VALUE_KV,
        MIN_VALUE_KV,
        MAX_VALUE_KV
        );
    static {
        Collections.sort(FUNCTION_ARG_KV_COLUMNS, KeyValue.COMPARATOR);
    }

    private static final int IS_ARRAY_INDEX = FUNCTION_ARG_KV_COLUMNS.indexOf(IS_ARRAY_KV);
    private static final int IS_CONSTANT_INDEX = FUNCTION_ARG_KV_COLUMNS.indexOf(IS_CONSTANT_KV);
    private static final int DEFAULT_VALUE_INDEX = FUNCTION_ARG_KV_COLUMNS.indexOf(DEFAULT_VALUE_KV);
    private static final int MIN_VALUE_INDEX = FUNCTION_ARG_KV_COLUMNS.indexOf(MIN_VALUE_KV);
    private static final int MAX_VALUE_INDEX = FUNCTION_ARG_KV_COLUMNS.indexOf(MAX_VALUE_KV);

    private static PName newPName(byte[] keyBuffer, int keyOffset, int keyLength) {
        if (keyLength <= 0) {
            return null;
        }
        int length = getVarCharLength(keyBuffer, keyOffset, keyLength);
        return PNameFactory.newName(keyBuffer, keyOffset, length);
    }

    private RegionCoprocessorEnvironment env;

    /**
     * Stores a reference to the coprocessor environment provided by the
     * {@link org.apache.hadoop.hbase.regionserver.RegionCoprocessorHost} from the region where this
     * coprocessor is loaded. Since this is a coprocessor endpoint, it always expects to be loaded
     * on a table region, so always expects this to be an instance of
     * {@link RegionCoprocessorEnvironment}.
     * @param env the environment provided by the coprocessor host
     * @throws IOException if the provided environment is not an instance of
     *             {@code RegionCoprocessorEnvironment}
     */
    @Override
    public void start(CoprocessorEnvironment env) throws IOException {
        if (env instanceof RegionCoprocessorEnvironment) {
            this.env = (RegionCoprocessorEnvironment) env;
        } else {
            throw new CoprocessorException("Must be loaded on a table region!");
        }
        logger.info("Starting Tracing-Metrics Systems");
        // Start the phoenix trace collection
        Tracing.addTraceMetricsSource();
        Metrics.ensureConfigured();
    }

    @Override
    public void stop(CoprocessorEnvironment env) throws IOException {
        // nothing to do
    }

    @Override
    public Service getService() {
        return this;
    }

    @Override
    public void getTable(RpcController controller, GetTableRequest request,
            RpcCallback<MetaDataResponse> done) {
        MetaDataResponse.Builder builder = MetaDataResponse.newBuilder();
        byte[] tenantId = request.getTenantId().toByteArray();
        byte[] schemaName = request.getSchemaName().toByteArray();
        byte[] tableName = request.getTableName().toByteArray();
        byte[] key = SchemaUtil.getTableKey(tenantId, schemaName, tableName);
        long tableTimeStamp = request.getTableTimestamp();
        try {
            // TODO: check that key is within region.getStartKey() and region.getEndKey()
            // and return special code to force client to lookup region from meta.
            Region region = env.getRegion();
            MetaDataMutationResult result = checkTableKeyInRegion(key, region);
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
                return;
            }

            long currentTime = EnvironmentEdgeManager.currentTimeMillis();
            PTable table = doGetTable(key, request.getClientTimestamp(), request.getClientVersion());
            if (table == null) {
                builder.setReturnCode(MetaDataProtos.MutationCode.TABLE_NOT_FOUND);
                builder.setMutationTime(currentTime);
                done.run(builder.build());
                return;
            }
            builder.setReturnCode(MetaDataProtos.MutationCode.TABLE_ALREADY_EXISTS);
            long disableIndexTimestamp = table.getIndexDisableTimestamp();
            long minNonZerodisableIndexTimestamp = disableIndexTimestamp > 0 ? disableIndexTimestamp : Long.MAX_VALUE;
            for (PTable index : table.getIndexes()) {
                disableIndexTimestamp = index.getIndexDisableTimestamp();
                if (disableIndexTimestamp > 0 && (index.getIndexState() == PIndexState.ACTIVE || index.getIndexState() == PIndexState.PENDING_ACTIVE) && disableIndexTimestamp < minNonZerodisableIndexTimestamp) {
                    minNonZerodisableIndexTimestamp = disableIndexTimestamp;
                }
            }
            // Freeze time for table at min non-zero value of INDEX_DISABLE_TIMESTAMP
            // This will keep the table consistent with index as the table has had one more
            // batch applied to it.
            if (minNonZerodisableIndexTimestamp == Long.MAX_VALUE) {
                builder.setMutationTime(currentTime);
            } else {
                // Subtract one because we add one due to timestamp granularity in Windows
                builder.setMutationTime(minNonZerodisableIndexTimestamp - 1);
            }
            Pair<PTable, MetaDataProtos.MutationCode> pair = combineColumns(table, tenantId, schemaName, tableName, request.getClientTimestamp(), request.getClientVersion());
            table = pair.getFirst();
            if (table == null) {
                builder.setReturnCode(pair.getSecond());
                builder.setMutationTime(currentTime);
            }
            else if (table.getTimeStamp() != tableTimeStamp) {
                builder.setTable(PTableImpl.toProto(table));
            }
            done.run(builder.build());
        } catch (Throwable t) {
        	logger.error("getTable failed", t);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException(SchemaUtil.getTableName(schemaName, tableName), t));
        }
    }
    
	private Pair<PTable, MetaDataProtos.MutationCode> combineColumns(PTable table, byte[] tenantId, byte[] schemaName,
			byte[] tableName, long timestamp, int clientVersion) throws SQLException, IOException {
		// combine columns for view and view indexes
		boolean hasIndexId = table.getViewIndexId() != null;
		if (table.getType() != PTableType.VIEW && !hasIndexId) {
			return new Pair<PTable, MetaDataProtos.MutationCode>(table, MetaDataProtos.MutationCode.TABLE_ALREADY_EXISTS);
		}
		boolean isDiverged = isDivergedView(table);
		// here you combine columns from the parent tables
		// the logic is as follows, if the PColumn is in the EXCLUDED_COLUMNS
		// remove it,
		// otherwise priority of keeping duplicate columns is child -> parent
		List<byte[]> ancestorList = Lists.newArrayList();
		TableViewFinderResult viewFinderResult = new TableViewFinderResult();
		if (PTableType.VIEW == table.getType()) {
			findAncestorViews(tenantId, schemaName, tableName, viewFinderResult);
		} else { // is a view index
			findAncestorViewsOfIndex(tenantId, schemaName, tableName, viewFinderResult);
		}
		if (viewFinderResult.getResults().isEmpty()) {
			// no need to combine columns for local indexes on regular tables
			return new Pair<PTable, MetaDataProtos.MutationCode>(table, MetaDataProtos.MutationCode.TABLE_ALREADY_EXISTS);
		}
		for (TableInfo viewInfo : viewFinderResult.getResults()) {
			ancestorList.add(viewInfo.getRowKeyPrefix());
		}
		List<PColumn> allColumns = Lists.newArrayList();
		List<PColumn> excludedColumns = Lists.newArrayList();
		// add my own columns first in reverse order
		List<PColumn> myColumns = table.getColumns();
		for (int i = myColumns.size() - 1; i >= 0; i--) {
			PColumn pColumn = myColumns.get(i);
			if (pColumn.isExcluded()) {
				excludedColumns.add(pColumn);
			} else if (!pColumn.equals(SaltingUtil.SALTING_COLUMN)) { // skip salted column as it will be added from the base table columns
				allColumns.add(pColumn);
			}
		}
		// index columns that have been dropped in the parent table
		boolean isSalted = table.getBucketNum() != null;
		int indexPosOffset = (isSalted ? 1 : 0) + (table.isMultiTenant() ? 1 : 0) + 1;
		// map from indexed expression to list of data columns that have been dropped
		Map<PColumn, List<String>> droppedColMap = Maps.newHashMapWithExpectedSize(table.getColumns().size());
		if (hasIndexId) {
			ColumnNameTrackingExpressionCompiler expressionCompiler = new ColumnNameTrackingExpressionCompiler();
	        for (int i = indexPosOffset; i < table.getPKColumns().size(); i++) {
	            PColumn indexColumn = table.getPKColumns().get(i);
	            try {
	                expressionCompiler.reset();
	                String expressionStr = IndexUtil.getIndexColumnExpressionStr(indexColumn);
	                ParseNode parseNode  = SQLParser.parseCondition(expressionStr);
	                parseNode.accept(expressionCompiler);
	                droppedColMap.put(indexColumn, Lists.newArrayList(expressionCompiler.getDataColumnNames()));
	            } catch (SQLException e) {
	                throw new RuntimeException(e); // Impossible
	            }
	        }
		}
		// now go up from child to parent all the way to the base table:
		PTable baseTable = null;
		long maxTableTimestamp = -1;
		int numPKCols = table.getPKColumns().size();
		for (int i = 0; i < ancestorList.size(); i++) {
			byte[] tableInQuestion = ancestorList.get(i);
			PTable pTable = this.doGetTable(tableInQuestion, timestamp, clientVersion);
			if (pTable == null) {
				String tableNameLink = Bytes.toString(tableInQuestion);
				throw new TableNotFoundException("ERROR COMBINING COLUMNS FOR: " + tableNameLink);
			} else {
				// if it has an index id only combine columns for view indexes
				// (and not local indexes on regular tables)
				if (i == 0 && hasIndexId && pTable.getType() != PTableType.VIEW) {
					return new Pair<PTable, MetaDataProtos.MutationCode>(table, MetaDataProtos.MutationCode.TABLE_ALREADY_EXISTS);
				}
				if (TABLE.equals(pTable.getType())) {
					baseTable = pTable;
				}
				maxTableTimestamp = Math.max(maxTableTimestamp, pTable.getTimeStamp());
				if (hasIndexId) {
					// add all pk columns of parent tables to indexes
					for (PColumn column : pTable.getPKColumns()) {
						if (column.isExcluded()) {
							continue;
						}
						column = IndexUtil.getIndexPKColumn(++numPKCols, column);
						int existingColumnIndex = allColumns.indexOf(column);
						if (existingColumnIndex == -1) {
							allColumns.add(0, column);
						}
						// TODO should we just generate columnsToAdd here (since
						// it doesnt need to be reversed)
					}
					for (int j = 0; j < pTable.getColumns().size(); j++) {
						PColumn tableColumn = pTable.getColumns().get(j);
						if (tableColumn.isExcluded()) {
							continue;
						}
						String dataColumnName = tableColumn.getName().getString();
						// remove from list of dropped columns since it
						// still exists
						for (Entry<PColumn, List<String>> entry : droppedColMap.entrySet()) {
							entry.getValue().remove(dataColumnName);
						}
					}
				} else {
					List<PColumn> someTablesColumns = PTableImpl.getColumnsToClone(pTable);
					if (someTablesColumns != null) {
						for (int j = someTablesColumns.size() - 1; j >= 0; j--) {
							PColumn column = someTablesColumns.get(j);
							// For diverged views we always include pk columns
							// of the base table. We have to include these pk
							// columns to be able to support adding pk columns
							// to the diverged view
							// We only include regular columns that were created
							// before the view diverged
							if (isDiverged && column.getFamilyName()!=null && column.getTimestamp() > table.getTimeStamp()) {
								continue;
							}
							// need to check if this column is in the list of excluded (dropped) columns of the view
							int existingIndex = excludedColumns.indexOf(column);
							if (existingIndex != -1) {
								// if it is, only exclude the column if was
								// created before the column was dropped in the
								// view in order to handle the case where a base
								// table column is dropped in a view, then
								// dropped in the base table and then added back
								// to the base table
								if (column.getTimestamp() <= excludedColumns.get(existingIndex).getTimestamp()) {
									continue;
								}
							}
							if (column.isExcluded()) {
								excludedColumns.add(column);
							} else {
								int existingColumnIndex = allColumns.indexOf(column);
								if (existingColumnIndex != -1) {
									// TODO ask james about this
									// we always keep the parent table column so
									// that we can handle
									// the case when you add a column that
									// already exists in a view to the base
									// table
									PColumn existingColumn = allColumns.get(existingColumnIndex);
									// if (column.getTimestamp() <
									// existingColumn.getTimestamp()) {
									allColumns.remove(existingColumnIndex);
									allColumns.add(column);
									// }
								} else {
									allColumns.add(column);
								}
							}
						}
					}
				}
			}
		}
		for (Entry<PColumn, List<String>> entry : droppedColMap.entrySet()) {
			if (!entry.getValue().isEmpty()) {
				PColumn indexColumnToBeDropped = entry.getKey();
				if (SchemaUtil.isPKColumn(indexColumnToBeDropped)) {
					// if an indexed column was dropped in an ancestor then we
					// cannot use this index an more
					// TODO figure out a way to actually drop this view index
					return new Pair<PTable, MetaDataProtos.MutationCode>(null, MetaDataProtos.MutationCode.TABLE_NOT_FOUND);
				} else {
					allColumns.remove(indexColumnToBeDropped);
				}
			}
		}
		// lets remove the excluded columns first if the timestamp is newer than
		// the added column
		for (PColumn excludedColumn : excludedColumns) {
			int index = allColumns.indexOf(excludedColumn);
			if (index != -1) {
				if (allColumns.get(index).getTimestamp() <= excludedColumn.getTimestamp()) {
					allColumns.remove(excludedColumn);
				}
			}
		}
		List<PColumn> columnsToAdd = Lists.newArrayList();
		int position = isSalted ? 1 : 0;
		for (int i = allColumns.size() - 1; i >= 0; i--) {
			PColumn column = allColumns.get(i);
			if (table.getColumns().contains(column)) {
				// for views this column is not derived from an ancestor
				columnsToAdd.add(new PColumnImpl(column, position));
			} else {
				columnsToAdd.add(new PColumnImpl(column, true, position));
			}
			position++;
		}
		// need to have the columns in the PTable to use the WhereCompiler
		// unfortunately so this needs to be done
		// twice....
		// TODO set the view properties correctly instead of just setting them
		// same as the base table
		int baseTableColumnCount = isDiverged ? QueryConstants.DIVERGED_VIEW_BASE_COLUMN_COUNT : columnsToAdd.size() - myColumns.size();
		PTableImpl pTable = PTableImpl.makePTable(table, baseTable, columnsToAdd, maxTableTimestamp, baseTableColumnCount);
		return WhereConstantParser.addViewInfoToPColumnsIfNeeded(pTable);
	}

    private PTable buildTable(byte[] key, ImmutableBytesPtr cacheKey, Region region,
            long clientTimeStamp, int clientVersion) throws IOException, SQLException {
        Scan scan = MetaDataUtil.newTableRowsScan(key, MIN_TABLE_TIMESTAMP, clientTimeStamp);
        Cache<ImmutableBytesPtr,PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
        try (RegionScanner scanner = region.getScanner(scan)) {
            PTable oldTable = (PTable)metaDataCache.getIfPresent(cacheKey);
            long tableTimeStamp = oldTable == null ? MIN_TABLE_TIMESTAMP-1 : oldTable.getTimeStamp();
            PTable newTable;
            boolean blockWriteRebuildIndex = env.getConfiguration().getBoolean(QueryServices.INDEX_FAILURE_BLOCK_WRITE,
                    QueryServicesOptions.DEFAULT_INDEX_FAILURE_BLOCK_WRITE);
            newTable = getTable(scanner, clientTimeStamp, tableTimeStamp, clientVersion);
            if (newTable == null) {
                return null;
            }
            if (oldTable == null || tableTimeStamp < newTable.getTimeStamp()
                    || (blockWriteRebuildIndex && newTable.getIndexDisableTimestamp() > 0)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Caching table "
                            + Bytes.toStringBinary(cacheKey.get(), cacheKey.getOffset(),
                                cacheKey.getLength()) + " at seqNum "
                            + newTable.getSequenceNumber() + " with newer timestamp "
                            + newTable.getTimeStamp() + " versus " + tableTimeStamp);
                }
                metaDataCache.put(cacheKey, newTable);
            }
            return newTable;
        }
    }

    private List<PFunction> buildFunctions(List<byte[]> keys, Region region,
            long clientTimeStamp, boolean isReplace, List<Mutation> deleteMutationsForReplace) throws IOException, SQLException {
        List<KeyRange> keyRanges = Lists.newArrayListWithExpectedSize(keys.size());
        for (byte[] key : keys) {
            byte[] stopKey = ByteUtil.concat(key, QueryConstants.SEPARATOR_BYTE_ARRAY);
            ByteUtil.nextKey(stopKey, stopKey.length);
            keyRanges.add(PVarbinary.INSTANCE.getKeyRange(key, true, stopKey, false));
        }
        Scan scan = new Scan();
        scan.setTimeRange(MIN_TABLE_TIMESTAMP, clientTimeStamp);
        ScanRanges scanRanges = ScanRanges.createPointLookup(keyRanges);
        scanRanges.initializeScan(scan);
        scan.setFilter(scanRanges.getSkipScanFilter());
        Cache<ImmutableBytesPtr,PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
        List<PFunction> functions = new ArrayList<PFunction>();
        PFunction function = null;
        try (RegionScanner scanner = region.getScanner(scan)) {
            for(int i = 0; i< keys.size(); i++) {
                function = null;
                function =
                        getFunction(scanner, isReplace, clientTimeStamp, deleteMutationsForReplace);
                if (function == null) {
                    return null;
                }
                byte[] functionKey =
                        SchemaUtil.getFunctionKey(
                            function.getTenantId() == null ? ByteUtil.EMPTY_BYTE_ARRAY : function
                                    .getTenantId().getBytes(), Bytes.toBytes(function
                                    .getFunctionName()));
                metaDataCache.put(new FunctionBytesPtr(functionKey), function);
                functions.add(function);
            }
            return functions;
        }
    }

    private List<PSchema> buildSchemas(List<byte[]> keys, Region region, long clientTimeStamp,
            ImmutableBytesPtr cacheKey) throws IOException, SQLException {
        List<KeyRange> keyRanges = Lists.newArrayListWithExpectedSize(keys.size());
        for (byte[] key : keys) {
            byte[] stopKey = ByteUtil.concat(key, QueryConstants.SEPARATOR_BYTE_ARRAY);
            ByteUtil.nextKey(stopKey, stopKey.length);
            keyRanges.add(PVarbinary.INSTANCE.getKeyRange(key, true, stopKey, false));
        }
        Scan scan = new Scan();
        scan.setTimeRange(MIN_TABLE_TIMESTAMP, clientTimeStamp);
        ScanRanges scanRanges = ScanRanges.createPointLookup(keyRanges);
        scanRanges.initializeScan(scan);
        scan.setFilter(scanRanges.getSkipScanFilter());
        Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
        List<PSchema> schemas = new ArrayList<PSchema>();
        PSchema schema = null;
        try (RegionScanner scanner = region.getScanner(scan)) {
            for (int i = 0; i < keys.size(); i++) {
                schema = null;
                schema = getSchema(scanner, clientTimeStamp);
                if (schema == null) { return null; }
                metaDataCache.put(cacheKey, schema);
                schemas.add(schema);
            }
            return schemas;
        }
    }

    private void addIndexToTable(PName tenantId, PName schemaName, PName indexName, PName tableName, long clientTimeStamp, List<PTable> indexes, int clientVersion) throws IOException, SQLException {
        byte[] key = SchemaUtil.getTableKey(tenantId == null ? ByteUtil.EMPTY_BYTE_ARRAY : tenantId.getBytes(), schemaName.getBytes(), indexName.getBytes());
        PTable indexTable = doGetTable(key, clientTimeStamp, clientVersion);
        if (indexTable == null) {
            ServerUtil.throwIOException("Index not found", new TableNotFoundException(schemaName.getString(), indexName.getString()));
            return;
        }
        indexes.add(indexTable);
    }

    private void addExcludedColumnToTable(List<PColumn> pColumns, PName colName, PName famName, long timestamp) {
        PColumnImpl pColumn = PColumnImpl.createExcludedColumn(famName, colName, timestamp);
        pColumns.add(pColumn);
    }

    private void addColumnToTable(List<Cell> results, PName colName, PName famName,
        Cell[] colKeyValues, List<PColumn> columns, boolean isSalted) {
        int i = 0;
        int j = 0;
        while (i < results.size() && j < COLUMN_KV_COLUMNS.size()) {
            Cell kv = results.get(i);
            Cell searchKv = COLUMN_KV_COLUMNS.get(j);
            int cmp =
                    Bytes.compareTo(kv.getQualifierArray(), kv.getQualifierOffset(),
                        kv.getQualifierLength(), searchKv.getQualifierArray(),
                        searchKv.getQualifierOffset(), searchKv.getQualifierLength());
            if (cmp == 0) {
                colKeyValues[j++] = kv;
                i++;
            } else if (cmp > 0) {
                colKeyValues[j++] = null;
            } else {
                i++; // shouldn't happen - means unexpected KV in system table column row
            }
        }

        if (colKeyValues[DATA_TYPE_INDEX] == null || colKeyValues[NULLABLE_INDEX] == null
                || colKeyValues[ORDINAL_POSITION_INDEX] == null) {
            throw new IllegalStateException("Didn't find all required key values in '"
                    + colName.getString() + "' column metadata row");
        }

        Cell columnSizeKv = colKeyValues[COLUMN_SIZE_INDEX];
        Integer maxLength =
                columnSizeKv == null ? null : PInteger.INSTANCE.getCodec().decodeInt(
                    columnSizeKv.getValueArray(), columnSizeKv.getValueOffset(), SortOrder.getDefault());
        Cell decimalDigitKv = colKeyValues[DECIMAL_DIGITS_INDEX];
        Integer scale =
                decimalDigitKv == null ? null : PInteger.INSTANCE.getCodec().decodeInt(
                    decimalDigitKv.getValueArray(), decimalDigitKv.getValueOffset(), SortOrder.getDefault());
        Cell ordinalPositionKv = colKeyValues[ORDINAL_POSITION_INDEX];
        int position =
            PInteger.INSTANCE.getCodec().decodeInt(ordinalPositionKv.getValueArray(),
                    ordinalPositionKv.getValueOffset(), SortOrder.getDefault()) + (isSalted ? 1 : 0);
        Cell nullableKv = colKeyValues[NULLABLE_INDEX];
        boolean isNullable =
            PInteger.INSTANCE.getCodec().decodeInt(nullableKv.getValueArray(),
                    nullableKv.getValueOffset(), SortOrder.getDefault()) != ResultSetMetaData.columnNoNulls;
        Cell dataTypeKv = colKeyValues[DATA_TYPE_INDEX];
        PDataType dataType =
                PDataType.fromTypeId(PInteger.INSTANCE.getCodec().decodeInt(
                  dataTypeKv.getValueArray(), dataTypeKv.getValueOffset(), SortOrder.getDefault()));
        if (maxLength == null && dataType == PBinary.INSTANCE) dataType = PVarbinary.INSTANCE;   // For
                                                                                               // backward
                                                                                               // compatibility.
        Cell sortOrderKv = colKeyValues[SORT_ORDER_INDEX];
        SortOrder sortOrder =
        		sortOrderKv == null ? SortOrder.getDefault() : SortOrder.fromSystemValue(PInteger.INSTANCE
                        .getCodec().decodeInt(sortOrderKv.getValueArray(),
                        		sortOrderKv.getValueOffset(), SortOrder.getDefault()));

        Cell arraySizeKv = colKeyValues[ARRAY_SIZE_INDEX];
        Integer arraySize = arraySizeKv == null ? null :
            PInteger.INSTANCE.getCodec().decodeInt(arraySizeKv.getValueArray(), arraySizeKv.getValueOffset(), SortOrder.getDefault());

        Cell viewConstantKv = colKeyValues[VIEW_CONSTANT_INDEX];
        byte[] viewConstant = viewConstantKv == null ? null : viewConstantKv.getValue();
        Cell isViewReferencedKv = colKeyValues[IS_VIEW_REFERENCED_INDEX];
        boolean isViewReferenced = isViewReferencedKv != null && Boolean.TRUE.equals(PBoolean.INSTANCE.toObject(isViewReferencedKv.getValueArray(), isViewReferencedKv.getValueOffset(), isViewReferencedKv.getValueLength()));
        Cell columnDefKv = colKeyValues[COLUMN_DEF_INDEX];
        String expressionStr = columnDefKv==null ? null : (String)PVarchar.INSTANCE.toObject(columnDefKv.getValueArray(), columnDefKv.getValueOffset(), columnDefKv.getValueLength());
        Cell isRowTimestampKV = colKeyValues[IS_ROW_TIMESTAMP_INDEX];
        boolean isRowTimestamp =
                isRowTimestampKV == null ? false : Boolean.TRUE.equals(PBoolean.INSTANCE.toObject(
                        isRowTimestampKV.getValueArray(), isRowTimestampKV.getValueOffset(),
                        isRowTimestampKV.getValueLength()));

        boolean isPkColumn = famName == null || famName.getString() == null;
        Cell columnQualifierKV = colKeyValues[COLUMN_QUALIFIER_INDEX];
        // Older tables won't have column qualifier metadata present. To make things simpler, just set the
        // column qualifier bytes by using the column name.
        byte[] columnQualifierBytes = columnQualifierKV != null ?
                Arrays.copyOfRange(columnQualifierKV.getValueArray(),
                    columnQualifierKV.getValueOffset(), columnQualifierKV.getValueOffset()
                            + columnQualifierKV.getValueLength()) : (isPkColumn ? null : colName.getBytes());
        PColumn column = new PColumnImpl(colName, famName, dataType, maxLength, scale, isNullable, position-1, sortOrder, arraySize, viewConstant, isViewReferenced, expressionStr, isRowTimestamp, false, columnQualifierBytes,
            results.get(0).getTimestamp());
        columns.add(column);
    }

    private void addArgumentToFunction(List<Cell> results, PName functionName, PName type,
        Cell[] functionKeyValues, List<FunctionArgument> arguments, short argPosition) throws SQLException {
        int i = 0;
        int j = 0;
        while (i < results.size() && j < FUNCTION_ARG_KV_COLUMNS.size()) {
            Cell kv = results.get(i);
            Cell searchKv = FUNCTION_ARG_KV_COLUMNS.get(j);
            int cmp =
                    Bytes.compareTo(kv.getQualifierArray(), kv.getQualifierOffset(),
                        kv.getQualifierLength(), searchKv.getQualifierArray(),
                        searchKv.getQualifierOffset(), searchKv.getQualifierLength());
            if (cmp == 0) {
                functionKeyValues[j++] = kv;
                i++;
            } else if (cmp > 0) {
                functionKeyValues[j++] = null;
            } else {
                i++; // shouldn't happen - means unexpected KV in system table column row
            }
        }

        Cell isArrayKv = functionKeyValues[IS_ARRAY_INDEX];
        boolean isArrayType =
                isArrayKv == null ? false : Boolean.TRUE.equals(PBoolean.INSTANCE.toObject(
                    isArrayKv.getValueArray(), isArrayKv.getValueOffset(),
                    isArrayKv.getValueLength()));
        Cell isConstantKv = functionKeyValues[IS_CONSTANT_INDEX];
        boolean isConstant =
                isConstantKv == null ? false : Boolean.TRUE.equals(PBoolean.INSTANCE.toObject(
                    isConstantKv.getValueArray(), isConstantKv.getValueOffset(),
                    isConstantKv.getValueLength()));
        Cell defaultValueKv = functionKeyValues[DEFAULT_VALUE_INDEX];
        String defaultValue =
                defaultValueKv == null ? null : (String) PVarchar.INSTANCE.toObject(
                    defaultValueKv.getValueArray(), defaultValueKv.getValueOffset(),
                    defaultValueKv.getValueLength());
        Cell minValueKv = functionKeyValues[MIN_VALUE_INDEX];
        String minValue =
                minValueKv == null ? null : (String) PVarchar.INSTANCE.toObject(
                    minValueKv.getValueArray(), minValueKv.getValueOffset(),
                    minValueKv.getValueLength());
        Cell maxValueKv = functionKeyValues[MAX_VALUE_INDEX];
        String maxValue =
                maxValueKv == null ? null : (String) PVarchar.INSTANCE.toObject(
                    maxValueKv.getValueArray(), maxValueKv.getValueOffset(),
                    maxValueKv.getValueLength());
        FunctionArgument arg =
                new FunctionArgument(type.getString(), isArrayType, isConstant,
                        defaultValue == null ? null : LiteralExpression.newConstant((new LiteralParseNode(defaultValue)).getValue()),
                        minValue == null ? null : LiteralExpression.newConstant((new LiteralParseNode(minValue)).getValue()),
                        maxValue == null ? null : LiteralExpression.newConstant((new LiteralParseNode(maxValue)).getValue()),
                        argPosition);
        arguments.add(arg);
    }

    private PTable getTable(RegionScanner scanner, long clientTimeStamp, long tableTimeStamp, int clientVersion)
        throws IOException, SQLException {
        List<Cell> results = Lists.newArrayList();
        scanner.next(results);
        if (results.isEmpty()) {
            return null;
        }
        Cell[] tableKeyValues = new Cell[TABLE_KV_COLUMNS.size()];
        Cell[] colKeyValues = new Cell[COLUMN_KV_COLUMNS.size()];

        // Create PTable based on KeyValues from scan
        Cell keyValue = results.get(0);
        byte[] keyBuffer = keyValue.getRowArray();
        int keyLength = keyValue.getRowLength();
        int keyOffset = keyValue.getRowOffset();
        PName tenantId = newPName(keyBuffer, keyOffset, keyLength);
        int tenantIdLength = (tenantId == null) ? 0 : tenantId.getBytes().length;
        if (tenantIdLength == 0) {
            tenantId = null;
        }
        PName schemaName = newPName(keyBuffer, keyOffset+tenantIdLength+1, keyLength);
        int schemaNameLength = schemaName.getBytes().length;
        int tableNameLength = keyLength - schemaNameLength - 1 - tenantIdLength - 1;
        byte[] tableNameBytes = new byte[tableNameLength];
        System.arraycopy(keyBuffer, keyOffset + schemaNameLength + 1 + tenantIdLength + 1,
            tableNameBytes, 0, tableNameLength);
        PName tableName = PNameFactory.newName(tableNameBytes);

        int offset = tenantIdLength + schemaNameLength + tableNameLength + 3;
        // This will prevent the client from continually looking for the current
        // table when we know that there will never be one since we disallow updates
        // unless the table is the latest
        // If we already have a table newer than the one we just found and
        // the client timestamp is less that the existing table time stamp,
        // bump up the timeStamp to right before the client time stamp, since
        // we know it can't possibly change.
        long timeStamp = keyValue.getTimestamp();
        // long timeStamp = tableTimeStamp > keyValue.getTimestamp() &&
        // clientTimeStamp < tableTimeStamp
        // ? clientTimeStamp-1
        // : keyValue.getTimestamp();

        int i = 0;
        int j = 0;
        while (i < results.size() && j < TABLE_KV_COLUMNS.size()) {
            Cell kv = results.get(i);
            Cell searchKv = TABLE_KV_COLUMNS.get(j);
            int cmp =
                    Bytes.compareTo(kv.getQualifierArray(), kv.getQualifierOffset(),
                        kv.getQualifierLength(), searchKv.getQualifierArray(),
                        searchKv.getQualifierOffset(), searchKv.getQualifierLength());
            if (cmp == 0) {
                timeStamp = Math.max(timeStamp, kv.getTimestamp()); // Find max timestamp of table
                                                                    // header row
                tableKeyValues[j++] = kv;
                i++;
            } else if (cmp > 0) {
                timeStamp = Math.max(timeStamp, kv.getTimestamp());
                tableKeyValues[j++] = null;
            } else {
                i++; // shouldn't happen - means unexpected KV in system table header row
            }
        }
        // TABLE_TYPE, TABLE_SEQ_NUM and COLUMN_COUNT are required.
        if (tableKeyValues[TABLE_TYPE_INDEX] == null || tableKeyValues[TABLE_SEQ_NUM_INDEX] == null
                || tableKeyValues[COLUMN_COUNT_INDEX] == null) {
            throw new IllegalStateException(
                    "Didn't find expected key values for table row in metadata row");
        }

        Cell tableTypeKv = tableKeyValues[TABLE_TYPE_INDEX];
        PTableType tableType =
                PTableType
                        .fromSerializedValue(tableTypeKv.getValueArray()[tableTypeKv.getValueOffset()]);
        Cell tableSeqNumKv = tableKeyValues[TABLE_SEQ_NUM_INDEX];
        long tableSeqNum =
            PLong.INSTANCE.getCodec().decodeLong(tableSeqNumKv.getValueArray(),
                    tableSeqNumKv.getValueOffset(), SortOrder.getDefault());
        Cell columnCountKv = tableKeyValues[COLUMN_COUNT_INDEX];
        int columnCount =
            PInteger.INSTANCE.getCodec().decodeInt(columnCountKv.getValueArray(),
                    columnCountKv.getValueOffset(), SortOrder.getDefault());
        Cell pkNameKv = tableKeyValues[PK_NAME_INDEX];
        PName pkName =
                pkNameKv != null ? newPName(pkNameKv.getValueArray(), pkNameKv.getValueOffset(),
                    pkNameKv.getValueLength()) : null;
        Cell saltBucketNumKv = tableKeyValues[SALT_BUCKETS_INDEX];
        Integer saltBucketNum =
                saltBucketNumKv != null ? (Integer) PInteger.INSTANCE.getCodec().decodeInt(
                    saltBucketNumKv.getValueArray(), saltBucketNumKv.getValueOffset(), SortOrder.getDefault()) : null;
        if (saltBucketNum != null && saltBucketNum.intValue() == 0) {
            saltBucketNum = null; // Zero salt buckets means not salted
        }
        Cell dataTableNameKv = tableKeyValues[DATA_TABLE_NAME_INDEX];
        PName dataTableName =
                dataTableNameKv != null ? newPName(dataTableNameKv.getValueArray(),
                    dataTableNameKv.getValueOffset(), dataTableNameKv.getValueLength()) : null;
        Cell indexStateKv = tableKeyValues[INDEX_STATE_INDEX];
        PIndexState indexState =
                indexStateKv == null ? null : PIndexState.fromSerializedValue(indexStateKv
                        .getValueArray()[indexStateKv.getValueOffset()]);
        // If client is not yet up to 4.12, then translate PENDING_ACTIVE to ACTIVE (as would have been
        // the value in those versions) since the client won't have this index state in its enum.
        if (indexState == PIndexState.PENDING_ACTIVE && clientVersion < PhoenixDatabaseMetaData.MIN_PENDING_ACTIVE_INDEX) {
            indexState = PIndexState.ACTIVE;
        }
        Cell immutableRowsKv = tableKeyValues[IMMUTABLE_ROWS_INDEX];
        boolean isImmutableRows =
                immutableRowsKv == null ? false : (Boolean) PBoolean.INSTANCE.toObject(
                    immutableRowsKv.getValueArray(), immutableRowsKv.getValueOffset(),
                    immutableRowsKv.getValueLength());
        Cell defaultFamilyNameKv = tableKeyValues[DEFAULT_COLUMN_FAMILY_INDEX];
        PName defaultFamilyName = defaultFamilyNameKv != null ? newPName(defaultFamilyNameKv.getValueArray(), defaultFamilyNameKv.getValueOffset(), defaultFamilyNameKv.getValueLength()) : null;
        Cell viewStatementKv = tableKeyValues[VIEW_STATEMENT_INDEX];
        String viewStatement = viewStatementKv != null ? (String) PVarchar.INSTANCE.toObject(viewStatementKv.getValueArray(), viewStatementKv.getValueOffset(),
                viewStatementKv.getValueLength()) : null;
        Cell disableWALKv = tableKeyValues[DISABLE_WAL_INDEX];
        boolean disableWAL = disableWALKv == null ? PTable.DEFAULT_DISABLE_WAL : Boolean.TRUE.equals(
            PBoolean.INSTANCE.toObject(disableWALKv.getValueArray(), disableWALKv.getValueOffset(), disableWALKv.getValueLength()));
        Cell multiTenantKv = tableKeyValues[MULTI_TENANT_INDEX];
        boolean multiTenant = multiTenantKv == null ? false : Boolean.TRUE.equals(PBoolean.INSTANCE.toObject(multiTenantKv.getValueArray(), multiTenantKv.getValueOffset(), multiTenantKv.getValueLength()));
        Cell storeNullsKv = tableKeyValues[STORE_NULLS_INDEX];
        boolean storeNulls = storeNullsKv == null ? false : Boolean.TRUE.equals(PBoolean.INSTANCE.toObject(storeNullsKv.getValueArray(), storeNullsKv.getValueOffset(), storeNullsKv.getValueLength()));
        Cell transactionalKv = tableKeyValues[TRANSACTIONAL_INDEX];
        boolean transactional = transactionalKv == null ? false : Boolean.TRUE.equals(PBoolean.INSTANCE.toObject(transactionalKv.getValueArray(), transactionalKv.getValueOffset(), transactionalKv.getValueLength()));
        Cell viewTypeKv = tableKeyValues[VIEW_TYPE_INDEX];
        ViewType viewType = viewTypeKv == null ? null : ViewType.fromSerializedValue(viewTypeKv.getValueArray()[viewTypeKv.getValueOffset()]);
        Cell viewIndexIdKv = tableKeyValues[VIEW_INDEX_ID_INDEX];
        Short viewIndexId = viewIndexIdKv == null ? null : (Short)MetaDataUtil.getViewIndexIdDataType().getCodec().decodeShort(viewIndexIdKv.getValueArray(), viewIndexIdKv.getValueOffset(), SortOrder.getDefault());
        Cell indexTypeKv = tableKeyValues[INDEX_TYPE_INDEX];
        IndexType indexType = indexTypeKv == null ? null : IndexType.fromSerializedValue(indexTypeKv.getValueArray()[indexTypeKv.getValueOffset()]);
        Cell baseColumnCountKv = tableKeyValues[BASE_COLUMN_COUNT_INDEX];
        int baseColumnCount = baseColumnCountKv == null ? 0 : PInteger.INSTANCE.getCodec().decodeInt(baseColumnCountKv.getValueArray(),
            baseColumnCountKv.getValueOffset(), SortOrder.getDefault());
        Cell rowKeyOrderOptimizableKv = tableKeyValues[ROW_KEY_ORDER_OPTIMIZABLE_INDEX];
        boolean rowKeyOrderOptimizable = rowKeyOrderOptimizableKv == null ? false : Boolean.TRUE.equals(PBoolean.INSTANCE.toObject(rowKeyOrderOptimizableKv.getValueArray(), rowKeyOrderOptimizableKv.getValueOffset(), rowKeyOrderOptimizableKv.getValueLength()));
        Cell updateCacheFrequencyKv = tableKeyValues[UPDATE_CACHE_FREQUENCY_INDEX];
        long updateCacheFrequency = updateCacheFrequencyKv == null ? 0 :
            PLong.INSTANCE.getCodec().decodeLong(updateCacheFrequencyKv.getValueArray(),
                    updateCacheFrequencyKv.getValueOffset(), SortOrder.getDefault());
        Cell indexDisableTimestampKv = tableKeyValues[INDEX_DISABLE_TIMESTAMP];
        long indexDisableTimestamp = indexDisableTimestampKv == null ? 0L : PLong.INSTANCE.getCodec().decodeLong(indexDisableTimestampKv.getValueArray(),
                indexDisableTimestampKv.getValueOffset(), SortOrder.getDefault());
        Cell isNamespaceMappedKv = tableKeyValues[IS_NAMESPACE_MAPPED_INDEX];
        boolean isNamespaceMapped = isNamespaceMappedKv == null ? false
                : Boolean.TRUE.equals(PBoolean.INSTANCE.toObject(isNamespaceMappedKv.getValueArray(),
                        isNamespaceMappedKv.getValueOffset(), isNamespaceMappedKv.getValueLength()));
        Cell autoPartitionSeqKv = tableKeyValues[AUTO_PARTITION_SEQ_INDEX];
        String autoPartitionSeq = autoPartitionSeqKv != null ? (String) PVarchar.INSTANCE.toObject(autoPartitionSeqKv.getValueArray(), autoPartitionSeqKv.getValueOffset(),
            autoPartitionSeqKv.getValueLength()) : null;
        Cell isAppendOnlySchemaKv = tableKeyValues[APPEND_ONLY_SCHEMA_INDEX];
        boolean isAppendOnlySchema = isAppendOnlySchemaKv == null ? false
                : Boolean.TRUE.equals(PBoolean.INSTANCE.toObject(isAppendOnlySchemaKv.getValueArray(),
                    isAppendOnlySchemaKv.getValueOffset(), isAppendOnlySchemaKv.getValueLength()));
        Cell storageSchemeKv = tableKeyValues[STORAGE_SCHEME_INDEX];
        //TODO: change this once we start having other values for storage schemes
        ImmutableStorageScheme storageScheme = storageSchemeKv == null ? ImmutableStorageScheme.ONE_CELL_PER_COLUMN : ImmutableStorageScheme
                .fromSerializedValue((byte)PTinyint.INSTANCE.toObject(storageSchemeKv.getValueArray(),
                        storageSchemeKv.getValueOffset(), storageSchemeKv.getValueLength()));
        Cell encodingSchemeKv = tableKeyValues[QUALIFIER_ENCODING_SCHEME_INDEX];
        QualifierEncodingScheme encodingScheme = encodingSchemeKv == null ? QualifierEncodingScheme.NON_ENCODED_QUALIFIERS : QualifierEncodingScheme
                .fromSerializedValue((byte)PTinyint.INSTANCE.toObject(encodingSchemeKv.getValueArray(),
                    encodingSchemeKv.getValueOffset(), encodingSchemeKv.getValueLength()));
        Cell useStatsForParallelizationKv = tableKeyValues[USE_STATS_FOR_PARALLELIZATION_INDEX];
        Boolean useStatsForParallelization = useStatsForParallelizationKv == null ? null : Boolean.TRUE.equals(PBoolean.INSTANCE.toObject(useStatsForParallelizationKv.getValueArray(), useStatsForParallelizationKv.getValueOffset(), useStatsForParallelizationKv.getValueLength()));
        
        List<PColumn> columns = Lists.newArrayListWithExpectedSize(columnCount);
        List<PTable> indexes = Lists.newArrayList();
        List<PName> physicalTables = Lists.newArrayList();
        PName parentTableName = tableType == INDEX ? dataTableName : null;
        PName parentSchemaName = tableType == INDEX ? schemaName : null;
        EncodedCQCounter cqCounter =
                (!EncodedColumnsUtil.usesEncodedColumnNames(encodingScheme) || tableType == PTableType.VIEW) ? PTable.EncodedCQCounter.NULL_COUNTER
                        : new EncodedCQCounter();
        while (true) {
          results.clear();
          scanner.next(results);
          if (results.isEmpty()) {
              break;
          }
          Cell colKv = results.get(LINK_TYPE_INDEX);
          int colKeyLength = colKv.getRowLength();
          PName colName = newPName(colKv.getRowArray(), colKv.getRowOffset() + offset, colKeyLength-offset);
          int colKeyOffset = offset + colName.getBytes().length + 1;
          PName famName = newPName(colKv.getRowArray(), colKv.getRowOffset() + colKeyOffset, colKeyLength-colKeyOffset);
          if (isQualifierCounterKV(colKv)) {
              Integer value = PInteger.INSTANCE.getCodec().decodeInt(colKv.getValueArray(), colKv.getValueOffset(), SortOrder.ASC);
              cqCounter.setValue(famName.getString(), value);
          } else if (Bytes.compareTo(LINK_TYPE_BYTES, 0, LINK_TYPE_BYTES.length, colKv.getQualifierArray(), colKv.getQualifierOffset(), colKv.getQualifierLength())==0) {
              LinkType linkType = LinkType.fromSerializedValue(colKv.getValueArray()[colKv.getValueOffset()]);
              if (linkType == LinkType.INDEX_TABLE) {
                  addIndexToTable(tenantId, schemaName, famName, tableName, clientTimeStamp, indexes, clientVersion);
              } else if (linkType == LinkType.PHYSICAL_TABLE) {
                  physicalTables.add(famName);
              } else if (linkType == LinkType.PARENT_TABLE) {
                  parentTableName = PNameFactory.newName(SchemaUtil.getTableNameFromFullName(famName.getBytes()));
                  parentSchemaName = PNameFactory.newName(SchemaUtil.getSchemaNameFromFullName(famName.getBytes()));
              } else if (linkType == LinkType.EXCLUDED_COLUMN) {
                  // add the excludedColumn
                  addExcludedColumnToTable(columns, colName, famName, colKv.getTimestamp());
              }
          } else {
              // CM: should we add the lookup to the parent tables here?
              addColumnToTable(results, colName, famName, colKeyValues, columns, saltBucketNum != null);
          }
        }
        // TODO: rg should we adjust the ordinal position?


        // Avoid querying the stats table because we're holding the rowLock here. Issuing an RPC to a remote
        // server while holding this lock is a bad idea and likely to cause contention.
        return PTableImpl.makePTable(tenantId, schemaName, tableName, tableType, indexState, timeStamp, tableSeqNum,
                pkName, saltBucketNum, columns, parentSchemaName, parentTableName, indexes, isImmutableRows, physicalTables, defaultFamilyName,
                viewStatement, disableWAL, multiTenant, storeNulls, viewType, viewIndexId, indexType,
                rowKeyOrderOptimizable, transactional, updateCacheFrequency, baseColumnCount,
                indexDisableTimestamp, isNamespaceMapped, autoPartitionSeq, isAppendOnlySchema, storageScheme, encodingScheme, cqCounter, useStatsForParallelization);
    }

    private boolean isQualifierCounterKV(Cell kv) {
        int cmp =
                Bytes.compareTo(kv.getQualifierArray(), kv.getQualifierOffset(),
                    kv.getQualifierLength(), QUALIFIER_COUNTER_KV.getQualifierArray(),
                    QUALIFIER_COUNTER_KV.getQualifierOffset(), QUALIFIER_COUNTER_KV.getQualifierLength());
        return cmp == 0;
    }

    private PSchema getSchema(RegionScanner scanner, long clientTimeStamp) throws IOException, SQLException {
        List<Cell> results = Lists.newArrayList();
        scanner.next(results);
        if (results.isEmpty()) { return null; }

        Cell keyValue = results.get(0);
        byte[] keyBuffer = keyValue.getRowArray();
        int keyLength = keyValue.getRowLength();
        int keyOffset = keyValue.getRowOffset();
        PName tenantId = newPName(keyBuffer, keyOffset, keyLength);
        int tenantIdLength = (tenantId == null) ? 0 : tenantId.getBytes().length;
        if (tenantIdLength == 0) {
            tenantId = null;
        }
        PName schemaName = newPName(keyBuffer, keyOffset + tenantIdLength + 1, keyLength - tenantIdLength - 1);
        long timeStamp = keyValue.getTimestamp();
        return new PSchema(schemaName.getString(), timeStamp);
    }

    private PFunction getFunction(RegionScanner scanner, final boolean isReplace, long clientTimeStamp, List<Mutation> deleteMutationsForReplace)
            throws IOException, SQLException {
        List<Cell> results = Lists.newArrayList();
        scanner.next(results);
        if (results.isEmpty()) {
            return null;
        }
        Cell[] functionKeyValues = new Cell[FUNCTION_KV_COLUMNS.size()];
        Cell[] functionArgKeyValues = new Cell[FUNCTION_ARG_KV_COLUMNS.size()];
        // Create PFunction based on KeyValues from scan
        Cell keyValue = results.get(0);
        byte[] keyBuffer = keyValue.getRowArray();
        int keyLength = keyValue.getRowLength();
        int keyOffset = keyValue.getRowOffset();
        long currentTimeMillis = EnvironmentEdgeManager.currentTimeMillis();
        if(isReplace) {
            long deleteTimeStamp =
                    clientTimeStamp == HConstants.LATEST_TIMESTAMP ? currentTimeMillis - 1
                            : (keyValue.getTimestamp() < clientTimeStamp ? clientTimeStamp - 1
                                    : keyValue.getTimestamp());
            deleteMutationsForReplace.add(new Delete(keyBuffer, keyOffset, keyLength, deleteTimeStamp));
        }
        PName tenantId = newPName(keyBuffer, keyOffset, keyLength);
        int tenantIdLength = (tenantId == null) ? 0 : tenantId.getBytes().length;
        if (tenantIdLength == 0) {
            tenantId = null;
        }
        PName functionName =
                newPName(keyBuffer, keyOffset + tenantIdLength + 1, keyLength - tenantIdLength - 1);
        int functionNameLength = functionName.getBytes().length+1;
        int offset = tenantIdLength + functionNameLength + 1;

        long timeStamp = keyValue.getTimestamp();

        int i = 0;
        int j = 0;
        while (i < results.size() && j < FUNCTION_KV_COLUMNS.size()) {
            Cell kv = results.get(i);
            Cell searchKv = FUNCTION_KV_COLUMNS.get(j);
            int cmp =
                    Bytes.compareTo(kv.getQualifierArray(), kv.getQualifierOffset(),
                        kv.getQualifierLength(), searchKv.getQualifierArray(),
                        searchKv.getQualifierOffset(), searchKv.getQualifierLength());
            if (cmp == 0) {
                timeStamp = Math.max(timeStamp, kv.getTimestamp()); // Find max timestamp of table
                                                                    // header row
                functionKeyValues[j++] = kv;
                i++;
            } else if (cmp > 0) {
                timeStamp = Math.max(timeStamp, kv.getTimestamp());
                functionKeyValues[j++] = null;
            } else {
                i++; // shouldn't happen - means unexpected KV in system table header row
            }
        }
        // CLASS_NAME,NUM_ARGS and JAR_PATH are required.
        if (functionKeyValues[CLASS_NAME_INDEX] == null || functionKeyValues[NUM_ARGS_INDEX] == null) {
            throw new IllegalStateException(
                    "Didn't find expected key values for function row in metadata row");
        }

        Cell classNameKv = functionKeyValues[CLASS_NAME_INDEX];
        PName className = newPName(classNameKv.getValueArray(), classNameKv.getValueOffset(),
            classNameKv.getValueLength());
        Cell jarPathKv = functionKeyValues[JAR_PATH_INDEX];
        PName jarPath = null;
        if(jarPathKv != null) {
            jarPath = newPName(jarPathKv.getValueArray(), jarPathKv.getValueOffset(),
                jarPathKv.getValueLength());
        }
        Cell numArgsKv = functionKeyValues[NUM_ARGS_INDEX];
        int numArgs =
                PInteger.INSTANCE.getCodec().decodeInt(numArgsKv.getValueArray(),
                    numArgsKv.getValueOffset(), SortOrder.getDefault());
        Cell returnTypeKv = functionKeyValues[RETURN_TYPE_INDEX];
        PName returnType =
                returnTypeKv == null ? null : newPName(returnTypeKv.getValueArray(),
                    returnTypeKv.getValueOffset(), returnTypeKv.getValueLength());

        List<FunctionArgument> arguments = Lists.newArrayListWithExpectedSize(numArgs);
        for (int k = 0; k < numArgs; k++) {
            results.clear();
            scanner.next(results);
            if (results.isEmpty()) {
                break;
            }
            Cell typeKv = results.get(0);
            if(isReplace) {
                long deleteTimeStamp =
                        clientTimeStamp == HConstants.LATEST_TIMESTAMP ? currentTimeMillis - 1
                                : (typeKv.getTimestamp() < clientTimeStamp ? clientTimeStamp - 1
                                        : typeKv.getTimestamp());
                deleteMutationsForReplace.add(new Delete(typeKv.getRowArray(), typeKv
                        .getRowOffset(), typeKv.getRowLength(), deleteTimeStamp));
            }
            int typeKeyLength = typeKv.getRowLength();
            PName typeName =
                    newPName(typeKv.getRowArray(), typeKv.getRowOffset() + offset, typeKeyLength
                            - offset - 3);

            int argPositionOffset =  offset + typeName.getBytes().length + 1;
            short argPosition = Bytes.toShort(typeKv.getRowArray(), typeKv.getRowOffset() + argPositionOffset, typeKeyLength
                - argPositionOffset);
            addArgumentToFunction(results, functionName, typeName, functionArgKeyValues, arguments, argPosition);
        }
        Collections.sort(arguments, new Comparator<FunctionArgument>() {
            @Override
            public int compare(FunctionArgument o1, FunctionArgument o2) {
                return o1.getArgPosition() - o2.getArgPosition();
            }
        });
        return new PFunction(tenantId, functionName.getString(), arguments, returnType.getString(),
                className.getString(), jarPath == null ? null : jarPath.getString(), timeStamp);
    }

    private PTable buildDeletedTable(byte[] key, ImmutableBytesPtr cacheKey, Region region,
        long clientTimeStamp) throws IOException {
        if (clientTimeStamp == HConstants.LATEST_TIMESTAMP) {
            return null;
        }

        Scan scan = MetaDataUtil.newTableRowsScan(key, clientTimeStamp, HConstants.LATEST_TIMESTAMP);
        scan.setFilter(new FirstKeyOnlyFilter());
        scan.setRaw(true);
        List<Cell> results = Lists.<Cell> newArrayList();
        try (RegionScanner scanner = region.getScanner(scan)) {
          scanner.next(results);
        }
        for (Cell kv : results) {
            KeyValue.Type type = Type.codeToType(kv.getTypeByte());
            if (type == Type.DeleteFamily) { // Row was deleted
                Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache =
                        GlobalCache.getInstance(this.env).getMetaDataCache();
                PTable table = newDeletedTableMarker(kv.getTimestamp());
                metaDataCache.put(cacheKey, table);
                return table;
            }
        }
        return null;
    }


    private PFunction buildDeletedFunction(byte[] key, ImmutableBytesPtr cacheKey, Region region,
        long clientTimeStamp) throws IOException {
        if (clientTimeStamp == HConstants.LATEST_TIMESTAMP) {
            return null;
        }

        Scan scan = MetaDataUtil.newTableRowsScan(key, clientTimeStamp, HConstants.LATEST_TIMESTAMP);
        scan.setFilter(new FirstKeyOnlyFilter());
        scan.setRaw(true);
        List<Cell> results = Lists.<Cell> newArrayList();
        try (RegionScanner scanner = region.getScanner(scan);) {
          scanner.next(results);
        }
        // HBase ignores the time range on a raw scan (HBASE-7362)
        if (!results.isEmpty() && results.get(0).getTimestamp() > clientTimeStamp) {
            Cell kv = results.get(0);
            if (kv.getTypeByte() == Type.Delete.getCode()) {
                Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache =
                        GlobalCache.getInstance(this.env).getMetaDataCache();
                PFunction function = newDeletedFunctionMarker(kv.getTimestamp());
                metaDataCache.put(cacheKey, function);
                return function;
            }
        }
        return null;
    }

    private PSchema buildDeletedSchema(byte[] key, ImmutableBytesPtr cacheKey, Region region, long clientTimeStamp)
            throws IOException {
        if (clientTimeStamp == HConstants.LATEST_TIMESTAMP) { return null; }

        Scan scan = MetaDataUtil.newTableRowsScan(key, clientTimeStamp, HConstants.LATEST_TIMESTAMP);
        scan.setFilter(new FirstKeyOnlyFilter());
        scan.setRaw(true);
        List<Cell> results = Lists.<Cell> newArrayList();
        try (RegionScanner scanner = region.getScanner(scan);) {
            scanner.next(results);
        }
        // HBase ignores the time range on a raw scan (HBASE-7362)
        if (!results.isEmpty() && results.get(0).getTimestamp() > clientTimeStamp) {
            Cell kv = results.get(0);
            if (kv.getTypeByte() == Type.Delete.getCode()) {
                Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env)
                        .getMetaDataCache();
                PSchema schema = newDeletedSchemaMarker(kv.getTimestamp());
                metaDataCache.put(cacheKey, schema);
                return schema;
            }
        }
        return null;
    }

    private static PTable newDeletedTableMarker(long timestamp) {
        return new PTableImpl(timestamp);
    }

    private static PFunction newDeletedFunctionMarker(long timestamp) {
        return new PFunction(timestamp);
    }

    private static PSchema newDeletedSchemaMarker(long timestamp) {
        return new PSchema(timestamp);
    }

    private static boolean isTableDeleted(PTable table) {
        return table.getName() == null;
    }

	private static boolean isSchemaDeleted(PSchema schema) {
		return schema.getSchemaName() == null;
	}

    private static boolean isFunctionDeleted(PFunction function) {
        return function.getFunctionName() == null;
    }

    private PTable loadTable(RegionCoprocessorEnvironment env, byte[] key,
        ImmutableBytesPtr cacheKey, long clientTimeStamp, long asOfTimeStamp, int clientVersion)
        throws IOException, SQLException {
        Region region = env.getRegion();
        Cache<ImmutableBytesPtr,PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
        PTable table = (PTable)metaDataCache.getIfPresent(cacheKey);
        // We always cache the latest version - fault in if not in cache
        if (table != null || (table = buildTable(key, cacheKey, region, asOfTimeStamp, clientVersion)) != null) {
            return table;
        }
        // if not found then check if newer table already exists and add delete marker for timestamp
        // found
        if (table == null
                && (table = buildDeletedTable(key, cacheKey, region, clientTimeStamp)) != null) {
            return table;
        }
        return null;
    }

    private PFunction loadFunction(RegionCoprocessorEnvironment env, byte[] key,
            ImmutableBytesPtr cacheKey, long clientTimeStamp, long asOfTimeStamp, boolean isReplace, List<Mutation> deleteMutationsForReplace)
            throws IOException, SQLException {
            Region region = env.getRegion();
            Cache<ImmutableBytesPtr,PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
            PFunction function = (PFunction)metaDataCache.getIfPresent(cacheKey);
            // We always cache the latest version - fault in if not in cache
            if (function != null && !isReplace) {
                return function;
            }
            ArrayList<byte[]> arrayList = new ArrayList<byte[]>(1);
            arrayList.add(key);
            List<PFunction> functions = buildFunctions(arrayList, region, asOfTimeStamp, isReplace, deleteMutationsForReplace);
            if(functions != null) return functions.get(0);
            // if not found then check if newer table already exists and add delete marker for timestamp
            // found
            if (function == null
                    && (function = buildDeletedFunction(key, cacheKey, region, clientTimeStamp)) != null) {
                return function;
            }
            return null;
        }

    private PSchema loadSchema(RegionCoprocessorEnvironment env, byte[] key, ImmutableBytesPtr cacheKey,
            long clientTimeStamp, long asOfTimeStamp) throws IOException, SQLException {
        Region region = env.getRegion();
        Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
        PSchema schema = (PSchema)metaDataCache.getIfPresent(cacheKey);
        // We always cache the latest version - fault in if not in cache
        if (schema != null) { return schema; }
        ArrayList<byte[]> arrayList = new ArrayList<byte[]>(1);
        arrayList.add(key);
        List<PSchema> schemas = buildSchemas(arrayList, region, asOfTimeStamp, cacheKey);
        if (schemas != null) return schemas.get(0);
        // if not found then check if newer schema already exists and add delete marker for timestamp
        // found
        if (schema == null
                && (schema = buildDeletedSchema(key, cacheKey, region, clientTimeStamp)) != null) { return schema; }
        return null;
    }

    /**
     *
     * @return null if the physical table row information is not present.
     *
     */
    private static Mutation getPhysicalTableForView(List<Mutation> tableMetadata, byte[][] parentSchemaTableNames) {
        int size = tableMetadata.size();
        byte[][] rowKeyMetaData = new byte[3][];
        MetaDataUtil.getTenantIdAndSchemaAndTableName(tableMetadata, rowKeyMetaData);
        Mutation physicalTableRow = null;
        boolean physicalTableLinkFound = false;
        if (size >= 2) {
            int i = size - 1;
            while (i >= 1) {
                Mutation m = tableMetadata.get(i);
                if (m instanceof Put) {
                    LinkType linkType = MetaDataUtil.getLinkType(m);
                    if (linkType == LinkType.PHYSICAL_TABLE) {
                        physicalTableRow = m;
                        physicalTableLinkFound = true;
                        break;
                    }
                }
                i--;
            }
        }
        if (!physicalTableLinkFound) {
            parentSchemaTableNames[0] = null;
            parentSchemaTableNames[1] = null;
            return null;
        }
        rowKeyMetaData = new byte[5][];
        getVarChars(physicalTableRow.getRow(), 5, rowKeyMetaData);
        byte[] colBytes = rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX];
        byte[] famBytes = rowKeyMetaData[PhoenixDatabaseMetaData.FAMILY_NAME_INDEX];
        if ((colBytes == null || colBytes.length == 0) && (famBytes != null && famBytes.length > 0)) {
            byte[] sName = SchemaUtil.getSchemaNameFromFullName(famBytes).getBytes();
            byte[] tName = SchemaUtil.getTableNameFromFullName(famBytes).getBytes();
            parentSchemaTableNames[0] = sName;
            parentSchemaTableNames[1] = tName;
        }
        return physicalTableRow;
    }

    @Override
    public void createTable(RpcController controller, CreateTableRequest request,
            RpcCallback<MetaDataResponse> done) {
        MetaDataResponse.Builder builder = MetaDataResponse.newBuilder();
        byte[][] rowKeyMetaData = new byte[3][];
        byte[] schemaName = null;
        byte[] tableName = null;
        try {
            int clientVersion = request.getClientVersion();
            List<Mutation> tableMetadata = ProtobufUtil.getMutations(request);
            MetaDataUtil.getTenantIdAndSchemaAndTableName(tableMetadata, rowKeyMetaData);
            byte[] tenantIdBytes = rowKeyMetaData[PhoenixDatabaseMetaData.TENANT_ID_INDEX];
            schemaName = rowKeyMetaData[PhoenixDatabaseMetaData.SCHEMA_NAME_INDEX];
            tableName = rowKeyMetaData[PhoenixDatabaseMetaData.TABLE_NAME_INDEX];
            // no need to run OrpanCleaner (which cleans up orphaned views) while creating SYSTEM tables  env.getTable
            if (Bytes.compareTo(schemaName,PhoenixDatabaseMetaData.SYSTEM_SCHEMA_NAME_BYTES)!=0) {
	            HTableInterface systemCatalog = null;
	            try {
	            	// can't use SchemaUtil.getPhysicalTableName on server side as we don't know whether 
	            	// the system tables have been migrated to the system namespaces
	            	TableName systemCatalogTableName = env.getRegion().getTableDesc().getTableName();
	                systemCatalog = env.getTable(systemCatalogTableName);
	                OrphanCleaner.reapOrphans(systemCatalog, tenantIdBytes, schemaName, tableName);
	            } finally {
	                if (systemCatalog != null) {
	                    systemCatalog.close();
	                }
	            }
            }
            byte[] parentSchemaName = null;
            byte[] parentTableName = null;
            PTableType tableType = MetaDataUtil.getTableType(tableMetadata, GenericKeyValueBuilder.INSTANCE, new ImmutableBytesWritable());
            ViewType viewType = MetaDataUtil.getViewType(tableMetadata, GenericKeyValueBuilder.INSTANCE, new ImmutableBytesWritable());

            // Here we are passed the parent's columns to add to a view, PHOENIX-3534 allows for a splittable
            // System.Catalog thus we only store the columns that are new to the view, not the parents columns,
            // thus here we remove everything that is ORDINAL.POSITION <= baseColumnCount and update the
            // ORDINAL.POSITIONS to be shifted accordingly.
            if (PTableType.VIEW.equals(tableType) && !ViewType.MAPPED.equals(viewType)) {
            	boolean isSalted = MetaDataUtil.getSaltBuckets(tableMetadata, GenericKeyValueBuilder.INSTANCE, new ImmutableBytesWritable()) > 0;
				int baseColumnCount = MetaDataUtil.getBaseColumnCount(tableMetadata) - (isSalted ? 1 : 0);
                if (baseColumnCount > 0) {
                    Iterator<Mutation> mutationIterator = tableMetadata.iterator();
                    while (mutationIterator.hasNext()) {
                        Mutation mutation = mutationIterator.next();
                        // if not null and ordinal position < base column count remove this mutation
                        ImmutableBytesWritable ptr = new ImmutableBytesWritable();
                        MetaDataUtil.getMutationValue(mutation, PhoenixDatabaseMetaData.ORDINAL_POSITION_BYTES,
                            GenericKeyValueBuilder.INSTANCE, ptr);
                        if (MetaDataUtil.getMutationValue(mutation, PhoenixDatabaseMetaData.ORDINAL_POSITION_BYTES,
                            GenericKeyValueBuilder.INSTANCE, ptr)) {
                            int ordinalValue = PInteger.INSTANCE.getCodec().decodeInt(ptr, SortOrder.ASC);
                            if (ordinalValue <= baseColumnCount) {
                                mutationIterator.remove();
                            } else {
                                if (mutation instanceof Put) {
                                    byte[] ordinalPositionBytes = new byte[PInteger.INSTANCE.getByteSize()];
                                    int newOrdinalValue = ordinalValue - baseColumnCount;
                                    PInteger.INSTANCE.getCodec()
                                        .encodeInt(newOrdinalValue, ordinalPositionBytes, 0);
                                    byte[] family = Iterables.getOnlyElement(mutation.getFamilyCellMap().keySet());
                                    MetaDataUtil.mutatePutValue((Put) mutation, family, PhoenixDatabaseMetaData.ORDINAL_POSITION_BYTES, ordinalPositionBytes);
                                }
                            }
                        }
                    }
                }
            }

            byte[] parentTableKey = null;
            Mutation viewPhysicalTableRow = null;
            if (tableType == PTableType.VIEW) {
                byte[][] parentSchemaTableNames = new byte[2][];
                /*
                 * For a view, we lock the base physical table row. For a mapped view, there is 
                 * no link present to the physical table. So the viewPhysicalTableRow is null
                 * in that case.
                 */
                viewPhysicalTableRow = getPhysicalTableForView(tableMetadata, parentSchemaTableNames);
                parentSchemaName = parentSchemaTableNames[0];
                parentTableName = parentSchemaTableNames[1];
                if (parentTableName != null) {
                    parentTableKey = SchemaUtil.getTableKey(ByteUtil.EMPTY_BYTE_ARRAY, parentSchemaName, parentTableName);
                }
            } else if (tableType == PTableType.INDEX) {
                parentSchemaName = schemaName;
                /* 
                 * For an index we lock the parent table's row which could be a physical table or a view.
                 * If the parent table is a physical table, then the tenantIdBytes is empty because
                 * we allow creating an index with a tenant connection only if the parent table is a view.
                 */
                parentTableName = MetaDataUtil.getParentTableName(tableMetadata);
                parentTableKey = SchemaUtil.getTableKey(tenantIdBytes, parentSchemaName, parentTableName);
            }

            Region region = env.getRegion();
            List<RowLock> locks = Lists.newArrayList();
            // Place a lock using key for the table to be created
            byte[] tableKey = SchemaUtil.getTableKey(tenantIdBytes, schemaName, tableName);
            try {
                acquireLock(region, tableKey, locks);

                // If the table key resides outside the region, return without doing anything
                MetaDataMutationResult result = checkTableKeyInRegion(tableKey, region);
                if (result != null) {
                    done.run(MetaDataMutationResult.toProto(result));
                    return;
                }

                long clientTimeStamp = MetaDataUtil.getClientTimeStamp(tableMetadata);
                ImmutableBytesPtr parentCacheKey = null;
                PTable parentTable = null;
                if (parentTableName != null) {
                    // Check if the parent table resides in the same region. If not, don't worry about locking the parent table row
                    // or loading the parent table. For a view, the parent table that needs to be locked is the base physical table.
                    // For an index on view, the view header row needs to be locked.
                    result = checkTableKeyInRegion(parentTableKey, region);
                    if (result == null) {
                        acquireLock(region, parentTableKey, locks);
                        parentCacheKey = new ImmutableBytesPtr(parentTableKey);
                        parentTable = loadTable(env, parentTableKey, parentCacheKey, clientTimeStamp,
                                clientTimeStamp, clientVersion);
                        if (parentTable == null || isTableDeleted(parentTable)) {
                            builder.setReturnCode(MetaDataProtos.MutationCode.PARENT_TABLE_NOT_FOUND);
                            builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                            done.run(builder.build());
                            return;
                        }
                        // make sure we haven't gone over our threshold for indexes on this table.
                        if (execeededIndexQuota(tableType, parentTable, env.getConfiguration())) {
                            builder.setReturnCode(MetaDataProtos.MutationCode.TOO_MANY_INDEXES);
                            builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                            done.run(builder.build());
                            return;
                        }
                        long parentTableSeqNumber;
                        if (tableType == PTableType.VIEW && viewPhysicalTableRow != null && request.hasClientVersion()) {
                            // Starting 4.5, the client passes the sequence number of the physical table in the table metadata.
                            parentTableSeqNumber = MetaDataUtil.getSequenceNumber(viewPhysicalTableRow);
                        } else if (tableType == PTableType.VIEW && !request.hasClientVersion()) {
                            // Before 4.5, due to a bug, the parent table key wasn't available.
                            // So don't do anything and prevent the exception from being thrown.
                            parentTableSeqNumber = parentTable.getSequenceNumber();
                        } else {
                            parentTableSeqNumber = MetaDataUtil.getParentSequenceNumber(tableMetadata);
                        }
                        // If parent table isn't at the expected sequence number, then return
                        if (parentTable.getSequenceNumber() != parentTableSeqNumber) {
                            builder.setReturnCode(MetaDataProtos.MutationCode.CONCURRENT_TABLE_MUTATION);
                            builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                            builder.setTable(PTableImpl.toProto(parentTable));
                            done.run(builder.build());
                            return;
                        }
                    }
                }
                // Load child table next
                ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(tableKey);
                // Get as of latest timestamp so we can detect if we have a newer table that already
                // exists without making an additional query
                PTable table =
                        loadTable(env, tableKey, cacheKey, clientTimeStamp, HConstants.LATEST_TIMESTAMP, clientVersion);
                if (table != null) {
                	table = combineColumns(table, tenantIdBytes, schemaName, tableName, clientTimeStamp, clientVersion).getFirst();
                    if (table.getTimeStamp() < clientTimeStamp) {
                        // If the table is older than the client time stamp and it's deleted,
                        // continue
                        if (!isTableDeleted(table)) {
                            builder.setReturnCode(MetaDataProtos.MutationCode.TABLE_ALREADY_EXISTS);
                            builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                            builder.setTable(PTableImpl.toProto(table));
                            done.run(builder.build());
                            return;
                        }
                    } else {
                        builder.setReturnCode(MetaDataProtos.MutationCode.NEWER_TABLE_FOUND);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        builder.setTable(PTableImpl.toProto(table));
                        done.run(builder.build());
                        return;
                    }
                }
                // Add cell for ROW_KEY_ORDER_OPTIMIZABLE = true, as we know that new tables
                // conform the correct row key. The exception is for a VIEW, which the client
                // sends over depending on its base physical table.
                if (tableType != PTableType.VIEW) {
                    UpgradeUtil.addRowKeyOrderOptimizableCell(tableMetadata, tableKey, clientTimeStamp);
                }
                // If the parent table of the view has the auto partition sequence name attribute, modify the
                // tableMetadata and set the view statement and partition column correctly
                if (parentTable!=null && parentTable.getAutoPartitionSeqName()!=null) {
                    long autoPartitionNum = 1;
                    try (PhoenixConnection connection = QueryUtil.getConnectionOnServer(env.getConfiguration()).unwrap(PhoenixConnection.class);
                        Statement stmt = connection.createStatement()) {
                        String seqName = parentTable.getAutoPartitionSeqName();
                        // Not going through the standard route of using statement.execute() as that code path
                        // is blocked if the metadata hasn't been been upgraded to the new minor release.
                        String seqNextValueSql = String.format("SELECT NEXT VALUE FOR %s", seqName);
                        PhoenixStatement ps = stmt.unwrap(PhoenixStatement.class);
                        QueryPlan plan = ps.compileQuery(seqNextValueSql);
                        ResultIterator resultIterator = plan.iterator();
                        PhoenixResultSet rs = ps.newResultSet(resultIterator, plan.getProjector(), plan.getContext());
                        rs.next();
                        autoPartitionNum = rs.getLong(1);
                    }
                    catch (SequenceNotFoundException e) {
                        builder.setReturnCode(MetaDataProtos.MutationCode.AUTO_PARTITION_SEQUENCE_NOT_FOUND);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        done.run(builder.build());
                        return;
                    }
                    PColumn autoPartitionCol = parentTable.getPKColumns().get(MetaDataUtil.getAutoPartitionColIndex(parentTable));
                    if (!PLong.INSTANCE.isCoercibleTo(autoPartitionCol.getDataType(), autoPartitionNum)) {
                        builder.setReturnCode(MetaDataProtos.MutationCode.CANNOT_COERCE_AUTO_PARTITION_ID);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        done.run(builder.build());
                        return;
                    }
                    builder.setAutoPartitionNum(autoPartitionNum);

                    // set the VIEW STATEMENT column of the header row
                    Put tableHeaderPut = MetaDataUtil.getPutOnlyTableHeaderRow(tableMetadata);
                    NavigableMap<byte[], List<Cell>> familyCellMap = tableHeaderPut.getFamilyCellMap();
                    List<Cell> cells = familyCellMap.get(TABLE_FAMILY_BYTES);
                    Cell cell = cells.get(0);
                    String autoPartitionWhere = QueryUtil.getViewPartitionClause(MetaDataUtil.getAutoPartitionColumnName(parentTable), autoPartitionNum);
                    String hbaseVersion = VersionInfo.getVersion();
                    ImmutableBytesPtr ptr = new ImmutableBytesPtr();
                    KeyValueBuilder kvBuilder = KeyValueBuilder.get(hbaseVersion);
                    MetaDataUtil.getMutationValue(tableHeaderPut, VIEW_STATEMENT_BYTES, kvBuilder, ptr);
                    byte[] value = ptr.copyBytesIfNecessary();
                    byte[] viewStatement = null;
                    // if we have an existing where clause add the auto partition where clause to it
                    if (!Bytes.equals(value, QueryConstants.EMPTY_COLUMN_VALUE_BYTES)) {
                        viewStatement = Bytes.add(value, Bytes.toBytes(" AND "), Bytes.toBytes(autoPartitionWhere));
                    }
                    else {
                        viewStatement = Bytes.toBytes(QueryUtil.getViewStatement(parentTable.getSchemaName().getString(), parentTable.getTableName().getString(), autoPartitionWhere));
                    }
                    Cell viewStatementCell = new KeyValue(cell.getRow(), cell.getFamily(), VIEW_STATEMENT_BYTES,
                        cell.getTimestamp(), Type.codeToType(cell.getTypeByte()), viewStatement);
                    cells.add(viewStatementCell);

                    // set the IS_VIEW_REFERENCED column of the auto partition column row
                    Put autoPartitionPut = MetaDataUtil.getPutOnlyAutoPartitionColumn(parentTable, tableMetadata);
                    familyCellMap = autoPartitionPut.getFamilyCellMap();
                    cells = familyCellMap.get(TABLE_FAMILY_BYTES);
                    cell = cells.get(0);
                    PDataType dataType = autoPartitionCol.getDataType();
                    Object val = dataType.toObject(autoPartitionNum, PLong.INSTANCE);
                    byte[] bytes = new byte [dataType.getByteSize() + 1];
                    dataType.toBytes(val, bytes, 0);
                    Cell viewConstantCell = new KeyValue(cell.getRow(), cell.getFamily(), VIEW_CONSTANT_BYTES,
                        cell.getTimestamp(), Type.codeToType(cell.getTypeByte()), bytes);
                    cells.add(viewConstantCell);
                }
                Short indexId = null;
                if (request.hasAllocateIndexId() && request.getAllocateIndexId()) {
                    String tenantIdStr = tenantIdBytes.length == 0 ? null : Bytes.toString(tenantIdBytes);
                    try (PhoenixConnection connection = QueryUtil.getConnectionOnServer(env.getConfiguration()).unwrap(PhoenixConnection.class)) {
                        PName physicalName = parentTable.getPhysicalName();
                        int nSequenceSaltBuckets = connection.getQueryServices().getSequenceSaltBuckets();
                        SequenceKey key = MetaDataUtil.getViewIndexSequenceKey(tenantIdStr, physicalName,
                            nSequenceSaltBuckets, parentTable.isNamespaceMapped() );
                        // TODO Review Earlier sequence was created at (SCN-1/LATEST_TIMESTAMP) and incremented at the client max(SCN,dataTable.getTimestamp), but it seems we should
                        // use always LATEST_TIMESTAMP to avoid seeing wrong sequence values by different connection having SCN
                        // or not.
                        long sequenceTimestamp = HConstants.LATEST_TIMESTAMP;
                        try {
                            connection.getQueryServices().createSequence(key.getTenantId(), key.getSchemaName(), key.getSequenceName(),
                                Short.MIN_VALUE, 1, 1, Long.MIN_VALUE, Long.MAX_VALUE, false, sequenceTimestamp);
                        } catch (SequenceAlreadyExistsException e) {
                        }
                        long[] seqValues = new long[1];
                        SQLException[] sqlExceptions = new SQLException[1];
                        connection.getQueryServices().incrementSequences(Collections.singletonList(new SequenceAllocation(key, 1)),
                            HConstants.LATEST_TIMESTAMP, seqValues, sqlExceptions);
                        if (sqlExceptions[0] != null) {
                            throw sqlExceptions[0];
                        }
                        long seqValue = seqValues[0];
                        if (seqValue > Short.MAX_VALUE) {
                            builder.setReturnCode(MetaDataProtos.MutationCode.TOO_MANY_INDEXES);
                            builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                            done.run(builder.build());
                            return;
                        }
                        Put tableHeaderPut = MetaDataUtil.getPutOnlyTableHeaderRow(tableMetadata);

                        NavigableMap<byte[], List<Cell>> familyCellMap = tableHeaderPut.getFamilyCellMap();
                        List<Cell> cells = familyCellMap.get(TABLE_FAMILY_BYTES);
                        Cell cell = cells.get(0);
                        PDataType dataType = MetaDataUtil.getViewIndexIdDataType();
                        Object val = dataType.toObject(seqValue, PLong.INSTANCE);
                        byte[] bytes = new byte [dataType.getByteSize() + 1];
                        dataType.toBytes(val, bytes, 0);
                        Cell indexIdCell = new KeyValue(cell.getRow(), cell.getFamily(), VIEW_INDEX_ID_BYTES,
                            cell.getTimestamp(), Type.codeToType(cell.getTypeByte()), bytes);
                        cells.add(indexIdCell);
                        indexId = (short) seqValue;
                    }
                }
                
                // the child links are stored in a separate table SYSTEM.CHILD_LINK from 4.14 onwards
                List<Mutation> childLinkMutations = MetaDataUtil.removeChildLinks(tableMetadata);
                HTableInterface hTable = null;
                try {
                	hTable = env.getTable(SchemaUtil
	                        .getPhysicalTableName(PhoenixDatabaseMetaData.SYSTEM_CHILD_LINK_NAME_BYTES, env.getConfiguration()));
                    hTable.batch(childLinkMutations);
                } catch (Throwable t) {
                    logger.error("creating child links failed", t);
                    ProtobufUtil.setControllerException(controller,
                        ServerUtil.createIOException(SchemaUtil.getTableName(schemaName, tableName), t));
                } finally {
                    if (hTable != null) {
                        hTable.close();
                    }
                }
                
                // TODO: Switch this to HRegion#batchMutate when we want to support indexes on the
                // system table. Basically, we get all the locks that we don't already hold for all the
                // tableMetadata rows. This ensures we don't have deadlock situations (ensuring
                // primary and then index table locks are held, in that order). For now, we just don't support
                // indexing on the system table. This is an issue because of the way we manage batch mutation
                // in the Indexer.
                region.mutateRowsWithLocks(tableMetadata, Collections.<byte[]> emptySet(), HConstants.NO_NONCE, HConstants.NO_NONCE);

                // Invalidate the cache - the next getTable call will add it
                // TODO: consider loading the table that was just created here, patching up the parent table, and updating the cache
                Cache<ImmutableBytesPtr,PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
                if (parentCacheKey != null) {
                    metaDataCache.invalidate(parentCacheKey);
                }
                metaDataCache.invalidate(cacheKey);
                // Get timeStamp from mutations - the above method sets it if it's unset
                long currentTimeStamp = MetaDataUtil.getClientTimeStamp(tableMetadata);
                builder.setReturnCode(MetaDataProtos.MutationCode.TABLE_NOT_FOUND);
                if (indexId != null) {
                    builder.setViewIndexId(indexId);
                }
                builder.setMutationTime(currentTimeStamp);
                done.run(builder.build());
                return;
            } finally {
                region.releaseRowLocks(locks);
            }
        } catch (Throwable t) {
            logger.error("createTable failed", t);
            ProtobufUtil.setControllerException(controller,
                    ServerUtil.createIOException(SchemaUtil.getTableName(schemaName, tableName), t));
        }
    }

    @VisibleForTesting
    static boolean execeededIndexQuota(PTableType tableType, PTable parentTable, Configuration configuration) {
        return PTableType.INDEX == tableType && parentTable.getIndexes().size() >= configuration
            .getInt(QueryServices.MAX_INDEXES_PER_TABLE,
                QueryServicesOptions.DEFAULT_MAX_INDEXES_PER_TABLE);
    }

    private static RowLock acquireLock(Region region, byte[] key, List<RowLock> locks)
        throws IOException {
        RowLock rowLock = region.getRowLock(key, false);
        if (rowLock == null) {
            throw new IOException("Failed to acquire lock on " + Bytes.toStringBinary(key));
        }
        locks.add(rowLock);
        return rowLock;
    }

    private static void printMutations(List<Mutation> mutations) {
        for (Mutation mutation : mutations) {
            if (mutation instanceof Put) {
                Put put = (Put) mutation;
                NavigableMap<byte[], List<Cell>> familyCellMap = put.getFamilyCellMap();
                for (List<Cell> cells : familyCellMap.values()) {
                    StringBuilder builder = new StringBuilder();
                    for (Cell cell : cells) {
                        // print the rowkey
                        builder.append("ROW_KEY: " + Bytes.toStringBinary(cell.getRowArray(), cell.getRowOffset(), cell.getRowLength()));
                        builder.append("\t");
                        builder.append("QUALIFIER: "+  Bytes
                            .toStringBinary(cell.getQualifierArray(), cell.getQualifierOffset(), cell.getQualifierLength()));
                        builder.append("\t");
                        builder.append("VALUE: " + Bytes
                            .toStringBinary(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength()));
                        builder.append("\n");
                        System.out.println(builder.toString());
                    }
                }
            }
        }
    }
    
    private void findAncestorViewsOfIndex(byte[] tenantId, byte[] schemaName, byte[] indexName, TableViewFinderResult result) throws IOException {
        HTableInterface hTable = env.getTable(SchemaUtil
                .getPhysicalTableName(PhoenixDatabaseMetaData.SYSTEM_CATALOG_NAME_BYTES, env.getConfiguration()));
        try {
            TableViewFinderResult currentResult = ViewFinder.findParentViewofIndex(hTable, tenantId, schemaName, indexName);
//            currentResult.addResult(ViewFinder.findBaseTable(hTable, tenantId, schemaName, indexName));
//            if ( currentResult.getResults().size()!=1 ) {
//                throw new RuntimeException("View index should have exactly one parent");
//            }
            if (currentResult.getResults().size()==1) {
            	result.addResult(currentResult);
            	TableInfo tableInfo = currentResult.getResults().get(0);
            	findAncestorViews(tableInfo.getTenantId(), tableInfo.getSchemaName(), tableInfo.getTableName(), result);
            }
            // else this is an index on a regular table and so we don't need to combine columns
        } finally {
            hTable.close();
        }
    }
    
    private void findAncestorViews(byte[] tenantId, byte[] schemaName, byte[] tableName, TableViewFinderResult result) throws IOException {
    	HTableInterface hTable = env.getTable(SchemaUtil
                .getPhysicalTableName(PhoenixDatabaseMetaData.SYSTEM_CATALOG_NAME_BYTES, env.getConfiguration()));
        try {
            ViewFinder.findAllRelatives(hTable, tenantId, schemaName, tableName, LinkType.PARENT_TABLE, result);
            // TODO ask james if we need to do this for tables with namespace mapping
            result.addResult(ViewFinder.findBaseTable(hTable, tenantId, schemaName, tableName));
        } finally {
            hTable.close();
        }
    }

    private void findAllChildViews(byte[] tenantId, byte[] schemaName, byte[] tableName, TableViewFinderResult result) throws IOException {
    	HTableInterface hTable = env.getTable(SchemaUtil
                .getPhysicalTableName(PhoenixDatabaseMetaData.SYSTEM_CHILD_LINK_NAME_BYTES, env.getConfiguration()));
        try {
            ViewFinder.findAllRelatives(hTable, tenantId, schemaName, tableName, LinkType.CHILD_TABLE, result);
        } finally {
            hTable.close();
        }
    }
    
	private void separateLocalAndRemoteMutations(Region region, List<Mutation> mutations,
			List<Mutation> localRegionMutations, List<Mutation> remoteRegionMutations) {
		HRegionInfo regionInfo = region.getRegionInfo();
		for (Mutation mutation : mutations) {
			if (regionInfo.containsRow(mutation.getRow())) {
				localRegionMutations.add(mutation);
			} else {
				remoteRegionMutations.add(mutation);
			}
		}
	}

    @Override
    public void dropTable(RpcController controller, DropTableRequest request,
            RpcCallback<MetaDataResponse> done) {
        MetaDataResponse.Builder builder = MetaDataResponse.newBuilder();
        boolean isCascade = request.getCascade();
        byte[][] rowKeyMetaData = new byte[3][];
        String tableType = request.getTableType();
        byte[] schemaName = null;
        byte[] tableName = null;

        try {
            List<Mutation> catalogMutations = ProtobufUtil.getMutations(request);
            List<Mutation> childLinkMutations = Lists.newArrayList();
        	List<Mutation> localRegionMutations = Lists.newArrayList();
			List<Mutation> remoteRegionMutations = Lists.newArrayList();
            MetaDataUtil.getTenantIdAndSchemaAndTableName(catalogMutations, rowKeyMetaData);
            byte[] tenantIdBytes = rowKeyMetaData[PhoenixDatabaseMetaData.TENANT_ID_INDEX];
            schemaName = rowKeyMetaData[PhoenixDatabaseMetaData.SCHEMA_NAME_INDEX];
            tableName = rowKeyMetaData[PhoenixDatabaseMetaData.TABLE_NAME_INDEX];
            // Disallow deletion of a system table
            if (tableType.equals(PTableType.SYSTEM.getSerializedValue())) {
                builder.setReturnCode(MetaDataProtos.MutationCode.UNALLOWED_TABLE_MUTATION);
                builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                done.run(builder.build());
                return;
            }
            List<byte[]> tableNamesToDelete = Lists.newArrayList();
            List<SharedTableState> sharedTablesToDelete = Lists.newArrayList();
            byte[] parentTableName = MetaDataUtil.getParentTableName(catalogMutations);
            byte[] lockTableName = parentTableName == null ? tableName : parentTableName;
            byte[] lockKey = SchemaUtil.getTableKey(tenantIdBytes, schemaName, lockTableName);
            byte[] key =
                    parentTableName == null ? lockKey : SchemaUtil.getTableKey(tenantIdBytes,
                        schemaName, tableName);

            Region region = env.getRegion();
            MetaDataMutationResult result = checkTableKeyInRegion(key, region);
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
                return;
            }
            List<RowLock> locks = Lists.newArrayList();
            try {
                acquireLock(region, lockKey, locks);
                if (key != lockKey) {
                    acquireLock(region, key, locks);
                }
                List<ImmutableBytesPtr> invalidateList = new ArrayList<ImmutableBytesPtr>();
                result =
                        doDropTable(key, tenantIdBytes, schemaName, tableName, parentTableName,
                            PTableType.fromSerializedValue(tableType), catalogMutations, childLinkMutations,
                            invalidateList, tableNamesToDelete, sharedTablesToDelete, isCascade, request.getClientVersion());
                if (result.getMutationCode() != MutationCode.TABLE_ALREADY_EXISTS) {
                    done.run(MetaDataMutationResult.toProto(result));
                    return;
                }
				Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env)
						.getMetaDataCache();
				// since the mutations in catalogMutations can span multiple
				// regions first we first process process mutations local to
				// this region, then we process the remaining mutations, finally
				// we process the child link mutations if any of the mutations
				// fail, we can will clean them up later using
				// OrphanCleaner.reapOrphans()
				separateLocalAndRemoteMutations(region, catalogMutations, localRegionMutations, remoteRegionMutations);
				// drop rows from catalog on this region
				region.mutateRowsWithLocks(localRegionMutations, Collections.<byte[]> emptyList(), HConstants.NO_NONCE,
						HConstants.NO_NONCE);

                long currentTime = MetaDataUtil.getClientTimeStamp(catalogMutations);
                for (ImmutableBytesPtr ckey : invalidateList) {
                    metaDataCache.put(ckey, newDeletedTableMarker(currentTime));
                }
                if (parentTableName != null) {
                    ImmutableBytesPtr parentCacheKey = new ImmutableBytesPtr(lockKey);
                    metaDataCache.invalidate(parentCacheKey);
                }
                done.run(MetaDataMutationResult.toProto(result));
                return;
            } finally {
                region.releaseRowLocks(locks);
                // drop rows from catalog on remote regions
                processMutations(controller, PhoenixDatabaseMetaData.SYSTEM_CATALOG_NAME_BYTES, SchemaUtil.getTableName(schemaName, tableName), remoteRegionMutations);
                // drop all child links 
                processMutations(controller, PhoenixDatabaseMetaData.SYSTEM_CHILD_LINK_NAME_BYTES, SchemaUtil.getTableName(schemaName, tableName), childLinkMutations);
            }
        } catch (Throwable t) {
          logger.error("dropTable failed", t);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException(SchemaUtil.getTableName(schemaName, tableName), t));
        }
    }

	private void processMutations(RpcController controller, byte[] systemTableName, String droppedTableName,
			List<Mutation> childLinkMutations) throws IOException {
		HTableInterface hTable = null;
		try {
			hTable = env.getTable(SchemaUtil.getPhysicalTableName(systemTableName, env.getConfiguration()));
			hTable.batch(childLinkMutations);
		} catch (Throwable t) {
			logger.error("dropTable failed", t);
			ProtobufUtil.setControllerException(controller, ServerUtil.createIOException(droppedTableName, t));
		} finally {
			if (hTable != null) {
				hTable.close();
			}
		}
	}

	private MetaDataMutationResult doDropTable(byte[] key, byte[] tenantId, byte[] schemaName, byte[] tableName,
			byte[] parentTableName, PTableType tableType, List<Mutation> catalogMutations,
			List<Mutation> childLinkMutations, List<ImmutableBytesPtr> invalidateList, List<byte[]> tableNamesToDelete,
			List<SharedTableState> sharedTablesToDelete, boolean isCascade, int clientVersion)
			throws IOException, SQLException {
        long clientTimeStamp = MetaDataUtil.getClientTimeStamp(catalogMutations);

        Region region = env.getRegion();
        ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(key);

        Cache<ImmutableBytesPtr,PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
        PTable table = (PTable)metaDataCache.getIfPresent(cacheKey);

        // We always cache the latest version - fault in if not in cache
        if (table != null
                || (table = buildTable(key, cacheKey, region, HConstants.LATEST_TIMESTAMP, clientVersion)) != null) {
            if (table.getTimeStamp() < clientTimeStamp) {
                if (isTableDeleted(table) || tableType != table.getType()) {
                    return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND, EnvironmentEdgeManager.currentTimeMillis(), null);
                }
            } else {
                return new MetaDataMutationResult(MutationCode.NEWER_TABLE_FOUND,
                        EnvironmentEdgeManager.currentTimeMillis(), null);
            }
        }
        // We didn't find a table at the latest timestamp, so either there is no table or
        // there was a table, but it's been deleted. In either case we want to return.
        if (table == null) {
            if (buildDeletedTable(key, cacheKey, region, clientTimeStamp) != null) {
                return new MetaDataMutationResult(MutationCode.NEWER_TABLE_FOUND, EnvironmentEdgeManager.currentTimeMillis(), null);
            }
            return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND, EnvironmentEdgeManager.currentTimeMillis(), null);
        }
        // Make sure we're not deleting the "wrong" child
        if (parentTableName!=null && table.getParentTableName() != null && !Arrays.equals(parentTableName, table.getParentTableName().getBytes())) {
            return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND, EnvironmentEdgeManager.currentTimeMillis(), null);
        }
        // Since we don't allow back in time DDL, we know if we have a table it's the one
        // we want to delete. FIXME: we shouldn't need a scan here, but should be able to
        // use the table to generate the Delete markers.
        Scan scan = MetaDataUtil.newTableRowsScan(key, MIN_TABLE_TIMESTAMP, clientTimeStamp);
        List<byte[]> indexNames = Lists.newArrayList();
        List<Cell> results = Lists.newArrayList();
        try (RegionScanner scanner = region.getScanner(scan);) {
            scanner.next(results);
            if (results.isEmpty()) { // Should not be possible
                return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND,
                        EnvironmentEdgeManager.currentTimeMillis(), null);
            }

            if (tableType == PTableType.TABLE || tableType == PTableType.SYSTEM) {
                // Handle any child views that exist
                TableViewFinderResult tableViewFinderResult = new TableViewFinderResult();
                findAllChildViews(tenantId, table.getSchemaName().getBytes(), table.getTableName().getBytes(), tableViewFinderResult);
                if (tableViewFinderResult.hasViews()) {
                    if (isCascade) {
                        // Recursively delete views adding the mutations to delete child views to rowsToDelete
                        for (TableInfo tableInfo : tableViewFinderResult.getResults()) {
                            byte[] viewTenantId = tableInfo.getTenantId();
                            byte[] viewSchemaName = tableInfo.getSchemaName();
                            byte[] viewName = tableInfo.getTableName();
                            byte[] viewKey = tableInfo.getRowKeyPrefix();
                            Delete delete = new Delete(tableInfo.getRowKeyPrefix(), clientTimeStamp);
                            catalogMutations.add(delete);
                            MetaDataMutationResult result = doDropTable(viewKey, viewTenantId, viewSchemaName,
                                    viewName, null, PTableType.VIEW, catalogMutations, childLinkMutations, invalidateList,
                                    tableNamesToDelete, sharedTablesToDelete, false, clientVersion);
                            if (result.getMutationCode() != MutationCode.TABLE_ALREADY_EXISTS) {
                                return result;
                            }
                        }
                    } else {
                        // DROP without CASCADE on tables with child views is not permitted
                        return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION,
                                EnvironmentEdgeManager.currentTimeMillis(), null);
                    }
                }
            }

            // Add to list of HTables to delete, unless it's a view or its a shared index
            if (tableType != PTableType.VIEW && table.getViewIndexId()==null) {
                tableNamesToDelete.add(table.getPhysicalName().getBytes());
            }
            else {
                sharedTablesToDelete.add(new SharedTableState(table));
            }
            invalidateList.add(cacheKey);
            byte[][] rowKeyMetaData = new byte[5][];
            do {
                Cell kv = results.get(LINK_TYPE_INDEX);
                int nColumns = getVarChars(kv.getRowArray(), kv.getRowOffset(), kv.getRowLength(), 0, rowKeyMetaData);
                if (nColumns == 5
                        && rowKeyMetaData[PhoenixDatabaseMetaData.FAMILY_NAME_INDEX].length > 0
                        && Bytes.compareTo(kv.getQualifierArray(), kv.getQualifierOffset(), kv.getQualifierLength(),
                                LINK_TYPE_BYTES, 0, LINK_TYPE_BYTES.length) == 0) {
                        LinkType linkType = LinkType.fromSerializedValue(kv.getValueArray()[kv.getValueOffset()]);
                        if (rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX].length == 0 && linkType == LinkType.INDEX_TABLE) {
                            indexNames.add(rowKeyMetaData[PhoenixDatabaseMetaData.FAMILY_NAME_INDEX]);
                        } else if (linkType == LinkType.PARENT_TABLE || linkType == LinkType.PHYSICAL_TABLE) {
                            // delete parent->child link for views
                            Cell parentTenantIdCell = MetaDataUtil.getCell(results, PhoenixDatabaseMetaData.PARENT_TENANT_ID_BYTES);
                            PName parentTenantId = parentTenantIdCell!=null ? PNameFactory.newName(parentTenantIdCell.getValueArray(), parentTenantIdCell.getValueOffset(), parentTenantIdCell.getValueLength()) : null;
                            byte[] linkKey = MetaDataUtil.getChildLinkKey(parentTenantId, table.getParentSchemaName(), table.getParentTableName(), table.getTenantId(), table.getName());
                            Delete linkDelete = new Delete(linkKey, clientTimeStamp);
                            childLinkMutations.add(linkDelete);
                        }
                }
                // FIXME: Remove when unintentionally deprecated method is fixed (HBASE-7870).
                // FIXME: the version of the Delete constructor without the lock args was introduced
                // in 0.94.4, thus if we try to use it here we can no longer use the 0.94.2 version
                // of the client.
                Delete delete = new Delete(kv.getRowArray(), kv.getRowOffset(), kv.getRowLength(), clientTimeStamp);
                catalogMutations.add(delete);
                results.clear();
                scanner.next(results);
            } while (!results.isEmpty());
        }

        // Recursively delete indexes
        for (byte[] indexName : indexNames) {
            byte[] indexKey = SchemaUtil.getTableKey(tenantId, schemaName, indexName);
            // FIXME: Remove when unintentionally deprecated method is fixed (HBASE-7870).
            // FIXME: the version of the Delete constructor without the lock args was introduced
            // in 0.94.4, thus if we try to use it here we can no longer use the 0.94.2 version
            // of the client.
            Delete delete = new Delete(indexKey, clientTimeStamp);
            catalogMutations.add(delete);
            MetaDataMutationResult result =
                    doDropTable(indexKey, tenantId, schemaName, indexName, tableName, PTableType.INDEX,
                        catalogMutations, childLinkMutations, invalidateList, tableNamesToDelete, sharedTablesToDelete, false, clientVersion);
            if (result.getMutationCode() != MutationCode.TABLE_ALREADY_EXISTS) {
                return result;
            }
        }

        return new MetaDataMutationResult(MutationCode.TABLE_ALREADY_EXISTS,
                EnvironmentEdgeManager.currentTimeMillis(), table, tableNamesToDelete);
    }


    private static interface ColumnMutator {
        MetaDataMutationResult updateMutation(PTable table, byte[][] rowKeyMetaData,
            List<Mutation> tableMetadata, Region region,
            List<ImmutableBytesPtr> invalidateList, List<RowLock> locks, long clientTimeStamp) throws IOException,
            SQLException;
    }

    private MetaDataMutationResult
    mutateColumn(List<Mutation> tableMetadata, ColumnMutator mutator, int clientVersion) throws IOException {
        byte[][] rowKeyMetaData = new byte[5][];
        MetaDataUtil.getTenantIdAndSchemaAndTableName(tableMetadata, rowKeyMetaData);
        byte[] tenantId = rowKeyMetaData[PhoenixDatabaseMetaData.TENANT_ID_INDEX];
        byte[] schemaName = rowKeyMetaData[PhoenixDatabaseMetaData.SCHEMA_NAME_INDEX];
        byte[] tableName = rowKeyMetaData[PhoenixDatabaseMetaData.TABLE_NAME_INDEX];
        byte[] key = SchemaUtil.getTableKey(tenantId, schemaName, tableName);
        try {
            Region region = env.getRegion();
            MetaDataMutationResult result = checkTableKeyInRegion(key, region);
            if (result != null) {
                return result;
            }
            List<RowLock> locks = Lists.newArrayList();
            try {
            	acquireLock(region, key, locks);
                ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(key);
                List<ImmutableBytesPtr> invalidateList = new ArrayList<ImmutableBytesPtr>();
                invalidateList.add(cacheKey);
                Cache<ImmutableBytesPtr,PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
                PTable table = (PTable)metaDataCache.getIfPresent(cacheKey);
                if (logger.isDebugEnabled()) {
                    if (table == null) {
                        logger.debug("Table " + Bytes.toStringBinary(key)
                                + " not found in cache. Will build through scan");
                    } else {
                        logger.debug("Table " + Bytes.toStringBinary(key)
                                + " found in cache with timestamp " + table.getTimeStamp()
                                + " seqNum " + table.getSequenceNumber());
                    }
                }
                // Get client timeStamp from mutations
                long clientTimeStamp = MetaDataUtil.getClientTimeStamp(tableMetadata);
                if (table == null
                        && (table = buildTable(key, cacheKey, region, HConstants.LATEST_TIMESTAMP, clientVersion)) == null) {
                    // if not found then call newerTableExists and add delete marker for timestamp
                    // found
                    table = buildDeletedTable(key, cacheKey, region, clientTimeStamp);
                    if (table != null) {
                        logger.info("Found newer table deleted as of " + table.getTimeStamp() + " versus client timestamp of " + clientTimeStamp);
                        return new MetaDataMutationResult(MutationCode.NEWER_TABLE_FOUND,
                                EnvironmentEdgeManager.currentTimeMillis(), null);
                    }
                    return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND,
                            EnvironmentEdgeManager.currentTimeMillis(), null);
                }
                if (table.getTimeStamp() >= clientTimeStamp) {
                    logger.info("Found newer table as of " + table.getTimeStamp() + " versus client timestamp of " + clientTimeStamp);
                    return new MetaDataMutationResult(MutationCode.NEWER_TABLE_FOUND,
                            EnvironmentEdgeManager.currentTimeMillis(), table);
                } else if (isTableDeleted(table)) {
                    return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND,
                            EnvironmentEdgeManager.currentTimeMillis(), null);
                }

                long expectedSeqNum = MetaDataUtil.getSequenceNumber(tableMetadata) - 1; // lookup
                                                                                         // TABLE_SEQ_NUM
                                                                                         // in
                                                                                         // tableMetaData
                if (logger.isDebugEnabled()) {
                    logger.debug("For table " + Bytes.toStringBinary(key) + " expecting seqNum "
                            + expectedSeqNum + " and found seqNum " + table.getSequenceNumber()
                            + " with " + table.getColumns().size() + " columns: "
                            + table.getColumns());
                }
                if (expectedSeqNum != table.getSequenceNumber()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("For table " + Bytes.toStringBinary(key)
                                + " returning CONCURRENT_TABLE_MUTATION due to unexpected seqNum");
                    }
                    return new MetaDataMutationResult(MutationCode.CONCURRENT_TABLE_MUTATION,
                            EnvironmentEdgeManager.currentTimeMillis(), table);
                }

                PTableType type = table.getType();
                if (type == PTableType.INDEX) {
                    // Disallow mutation of an index table
                    return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION,
                            EnvironmentEdgeManager.currentTimeMillis(), null);
                } else {
                    // server-side, except for indexing, we always expect the keyvalues to be standard KeyValues
                    PTableType expectedType = MetaDataUtil.getTableType(tableMetadata, GenericKeyValueBuilder.INSTANCE,
                            new ImmutableBytesWritable());
                    // We said to drop a table, but found a view or visa versa
                    if (type != expectedType) { return new MetaDataMutationResult(MutationCode.TABLE_NOT_FOUND,
                            EnvironmentEdgeManager.currentTimeMillis(), null); }
                }
                result = mutator.updateMutation(table, rowKeyMetaData, tableMetadata, region,
                            invalidateList, locks, clientTimeStamp);
                // if the update mutation caused tables to be deleted, the mutation code returned will be MutationCode.TABLE_ALREADY_EXISTS 
                if (result != null && result.getMutationCode()!=MutationCode.TABLE_ALREADY_EXISTS) {
                    return result;
                }
                region.batchMutate(tableMetadata.toArray(new Mutation[0]), HConstants.NO_NONCE, HConstants.NO_NONCE);
                // Invalidate from cache
                for (ImmutableBytesPtr invalidateKey : invalidateList) {
                    metaDataCache.invalidate(invalidateKey);
                }
                // Get client timeStamp from mutations, since it may get updated by the
                // mutateRowsWithLocks call
                long currentTime = MetaDataUtil.getClientTimeStamp(tableMetadata);
                // if the update mutation caused tables to be deleted just return the result which will contain the table to be deleted
                if (result !=null) {
                    return result;
                } else {
                    table = buildTable(key, cacheKey, region, HConstants.LATEST_TIMESTAMP, clientVersion);
                    table = combineColumns(table, tenantId, schemaName, tableName, clientTimeStamp, clientVersion).getFirst();
                    return new MetaDataMutationResult(MutationCode.TABLE_ALREADY_EXISTS, currentTime, table);
                }
            } finally {
                region.releaseRowLocks(locks);
            }
        } catch (Throwable t) {
            ServerUtil.throwIOException(SchemaUtil.getTableName(schemaName, tableName), t);
            return null; // impossible
        }
    }

    private final class PutWithOrdinalPosition implements Comparable<PutWithOrdinalPosition>{
        private final Put put;
        private final int ordinalPosition;

        public PutWithOrdinalPosition(Put put, int ordinalPos) {
            this.put = put;
            this.ordinalPosition = ordinalPos;
        }

        @Override
        public int compareTo(PutWithOrdinalPosition o) {
            return (this.ordinalPosition < o.ordinalPosition ? -1 : this.ordinalPosition > o.ordinalPosition ? 1 : 0);
        }
    }

    private static int getOrdinalPosition(PTable table, PColumn col) {
        return table.getBucketNum() == null ? col.getPosition() + 1 : col.getPosition();
    }

    private static boolean isDivergedView(PTable view) {
        return view.getBaseColumnCount() == QueryConstants.DIVERGED_VIEW_BASE_COLUMN_COUNT;
    }

    /**
     *
     * Class to keep track of columns and their ordinal positions as we
     * process through the list of columns to be added.
     *
     */
    private static class ColumnOrdinalPositionUpdateList {
        final List<byte[]> columnKeys = new ArrayList<>(10);
        int offset;

        int size()  {
            return columnKeys.size();
        }

        private void setOffset(int lowestOrdinalPos) {
            this.offset = lowestOrdinalPos;
        }

        private void addColumn(byte[] columnKey) {
            columnKeys.add(columnKey);
        }

        private void dropColumn(byte[] columnKey) {
            // Check if an entry for this column key exists
            int index = -1;
            for (int i = 0; i < columnKeys.size(); i++) {
                if (Bytes.equals(columnKeys.get(i), columnKey)) {
                    index = i;
                    break;
                }
            }
            if (index != -1) {
                columnKeys.remove(index);
            }
        }

        private void addColumn(byte[] columnKey, int position) {
            checkArgument(position >= this.offset);
            int index = position - offset;
            int size = columnKeys.size();
            checkState(index <= size);
            if (size == 0) {
                columnKeys.add(columnKey);
                return;
            }
            int stopIndex = size;
            // Check if an entry for this column key is already there.
            for (int i = 0; i < size; i++) {
                if (Bytes.equals(columnKeys.get(i), columnKey)) {
                    stopIndex = i;
                    break;
                }
            }
            if (stopIndex == size) {
                /*
                 * The column key is not present in the list. So add it at the specified index
                 * and right shift the elements at this index and beyond.
                 */
                columnKeys.add(index, columnKey);
            } else {
                /*
                 * The column key is already present in the list.
                 * Move the elements of the list to the left up to the stop index
                 */
                for (int i = stopIndex; i > index; i--) {
                    columnKeys.set(i, columnKeys.get(i - 1));
                }
                columnKeys.set(index, columnKey);
            }
        }

        private int getOrdinalPositionFromListIdx(int listIndex) {
            checkArgument(listIndex < columnKeys.size());
            return listIndex + offset;
        }

        /**
         * @param columnKey
         * @return if present - the ordinal position of the column in this list.
         * If not present - -1.
         */
        private int getOrdinalPositionOfColumn(byte[] columnKey) {
            int i = 0;
            for (byte[] key : columnKeys) {
                if (Bytes.equals(key, columnKey)) {
                    return i + offset;
                }
                i++;
            }
            return -1;
        }
    }

    private static byte[] getColumnKey(byte[] viewKey, PColumn column) {
        return getColumnKey(viewKey, column.getName().getString(), column.getFamilyName() != null ? column.getFamilyName().getString() : null);
    }

    private static byte[] getColumnKey(byte[] viewKey, String columnName, String columnFamily) {
        byte[] columnKey = ByteUtil.concat(viewKey, QueryConstants.SEPARATOR_BYTE_ARRAY,
                Bytes.toBytes(columnName));
        if (columnFamily != null) {
            columnKey = ByteUtil.concat(columnKey, QueryConstants.SEPARATOR_BYTE_ARRAY,
                    Bytes.toBytes(columnFamily));
        }
        return columnKey;
    }

    private boolean switchAttribute(PTable table, boolean currAttribute, List<Mutation> tableMetaData, byte[] attrQualifier) {
        for (Mutation m : tableMetaData) {
            if (m instanceof Put) {
                Put p = (Put)m;
                List<Cell> cells = p.get(TABLE_FAMILY_BYTES, attrQualifier);
                if (cells != null && cells.size() > 0) {
                    Cell cell = cells.get(0);
                    boolean newAttribute = (boolean)PBoolean.INSTANCE.toObject(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength());
                    return currAttribute != newAttribute;
                }
            }
        }
        return false;
    }


    private MetaDataMutationResult validateColumnForAddToBaseTable(PTable basePhysicalTable,
            List<Mutation> tableMetadata, byte[][] rowKeyMetaData,
            TableViewFinderResult childViewsResult, long clientTimeStamp, int clientVersion)
            throws IOException, SQLException {
        byte[] schemaName = rowKeyMetaData[SCHEMA_NAME_INDEX];
        byte[] tableName = rowKeyMetaData[TABLE_NAME_INDEX];
        List<PutWithOrdinalPosition> columnPutsForBaseTable =
                Lists.newArrayListWithExpectedSize(tableMetadata.size());
        Map<TableProperty, Cell> tablePropertyCellMap =
                Maps.newHashMapWithExpectedSize(tableMetadata.size());
        // Isolate the puts relevant to adding columns. Also figure out what kind of columns are
        // being added.
        for (Mutation m : tableMetadata) {
            if (m instanceof Put) {
                byte[][] rkmd = new byte[5][];
                int pkCount = getVarChars(m.getRow(), rkmd);
                // check if this put is for adding a column
                if (pkCount > COLUMN_NAME_INDEX && rkmd[COLUMN_NAME_INDEX] != null
                        && rkmd[COLUMN_NAME_INDEX].length > 0
                        && Bytes.compareTo(schemaName, rkmd[SCHEMA_NAME_INDEX]) == 0
                        && Bytes.compareTo(tableName, rkmd[TABLE_NAME_INDEX]) == 0) {
                    columnPutsForBaseTable.add(new PutWithOrdinalPosition((Put) m,
                            getInteger((Put) m, TABLE_FAMILY_BYTES, ORDINAL_POSITION_BYTES)));
                }
                // check if the put is for a table property
                else if (pkCount <= COLUMN_NAME_INDEX
                        && Bytes.compareTo(schemaName, rkmd[SCHEMA_NAME_INDEX]) == 0
                        && Bytes.compareTo(tableName, rkmd[TABLE_NAME_INDEX]) == 0) {
                    for (Cell cell : m.getFamilyCellMap()
                            .get(QueryConstants.DEFAULT_COLUMN_FAMILY_BYTES)) {
                        for (TableProperty tableProp : TableProperty.values()) {
                            byte[] propNameBytes = Bytes.toBytes(tableProp.getPropertyName());
                            if (Bytes.compareTo(propNameBytes, 0, propNameBytes.length,
                                cell.getQualifierArray(), cell.getQualifierOffset(),
                                cell.getQualifierLength()) == 0 && tableProp.isValidOnView()
                                    && tableProp.isMutable()) {
                                Cell tablePropCell =
                                        CellUtil.createCell(cell.getRow(),
                                            CellUtil.cloneFamily(cell),
                                            CellUtil.cloneQualifier(cell), cell.getTimestamp(),
                                            cell.getTypeByte(), CellUtil.cloneValue(cell));
                                tablePropertyCellMap.put(tableProp, tablePropCell);
                            }
                        }
                    }
                }
            }
        }
        // Sort the puts by ordinal position
        Collections.sort(columnPutsForBaseTable);
        for (TableInfo viewInfo : childViewsResult.getResults()) {
            byte[] viewKey = SchemaUtil.getTableKey(viewInfo.getTenantId(), viewInfo.getSchemaName(), viewInfo.getTableName());
            PTable view = doGetTable(viewKey, clientTimeStamp, clientVersion);

            // add the new columns to the child view
            List<PColumn> viewPkCols = new ArrayList<>(view.getPKColumns());
            boolean addingExistingPkCol = false;
            for (PutWithOrdinalPosition p : columnPutsForBaseTable) {
                Put columnToBeAdded = p.put;
                PColumn existingViewColumn = null;
                byte[][] rkmd = new byte[5][];
                getVarChars(columnToBeAdded.getRow(), rkmd);
                String columnName = Bytes.toString(rkmd[COLUMN_NAME_INDEX]);
                String columnFamily =
                        rkmd[FAMILY_NAME_INDEX] == null ? null
                                : Bytes.toString(rkmd[FAMILY_NAME_INDEX]);
                try {
                    existingViewColumn =
                            columnFamily == null ? view.getColumnForColumnName(columnName)
                                    : view.getColumnFamily(columnFamily)
                                            .getPColumnForColumnName(columnName);
                } catch (ColumnFamilyNotFoundException e) {
                    // ignore since it means that the column family is not present for the column to
                    // be added.
                } catch (ColumnNotFoundException e) {
                    // ignore since it means the column is not present in the view
                }

                boolean isColumnToBeAddPkCol = columnFamily == null;
                if (existingViewColumn != null) {
                    if (EncodedColumnsUtil.usesEncodedColumnNames(basePhysicalTable)
                            && !SchemaUtil.isPKColumn(existingViewColumn)) {
                        /*
                         * If the column already exists in a view, then we cannot add the column to
                         * the base table. The reason is subtle and is as follows: consider the case
                         * where a table has two views where both the views have the same key value
                         * column KV. Now, we dole out encoded column qualifiers for key value
                         * columns in views by using the counters stored in the base physical table.
                         * So the KV column can have different column qualifiers for the two views.
                         * For example, 11 for VIEW1 and 12 for VIEW2. This naturally extends to
                         * rows being inserted using the two views having different column
                         * qualifiers for the column named KV. Now, when an attempt is made to add
                         * column KV to the base table, we cannot decide which column qualifier
                         * should that column be assigned. It cannot be a number different than 11
                         * or 12 since a query like SELECT KV FROM BASETABLE would return null for
                         * KV which is incorrect since column KV is present in rows inserted from
                         * the two views. We cannot use 11 or 12 either because we will then
                         * incorrectly return value of KV column inserted using only one view.
                         */
                        return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION,
                                EnvironmentEdgeManager.currentTimeMillis(), basePhysicalTable);
                    }
                    // Validate data type is same
                    int baseColumnDataType =
                            getInteger(columnToBeAdded, TABLE_FAMILY_BYTES, DATA_TYPE_BYTES);
                    if (baseColumnDataType != existingViewColumn.getDataType().getSqlType()) {
                        return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION,
                                EnvironmentEdgeManager.currentTimeMillis(), basePhysicalTable);
                    }

                    // Validate max length is same
                    int maxLength =
                            getInteger(columnToBeAdded, TABLE_FAMILY_BYTES, COLUMN_SIZE_BYTES);
                    int existingMaxLength =
                            existingViewColumn.getMaxLength() == null ? 0
                                    : existingViewColumn.getMaxLength();
                    if (maxLength != existingMaxLength) {
                        return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION,
                                EnvironmentEdgeManager.currentTimeMillis(), basePhysicalTable);
                    }

                    // Validate scale is same
                    int scale =
                            getInteger(columnToBeAdded, TABLE_FAMILY_BYTES, DECIMAL_DIGITS_BYTES);
                    int existingScale =
                            existingViewColumn.getScale() == null ? 0
                                    : existingViewColumn.getScale();
                    if (scale != existingScale) {
                        return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION,
                                EnvironmentEdgeManager.currentTimeMillis(), basePhysicalTable);
                    }

                    // Validate sort order is same
                    int sortOrder =
                            getInteger(columnToBeAdded, TABLE_FAMILY_BYTES, SORT_ORDER_BYTES);
                    if (sortOrder != existingViewColumn.getSortOrder().getSystemValue()) {
                        return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION,
                                EnvironmentEdgeManager.currentTimeMillis(), basePhysicalTable);
                    }

                    // if the column to be added to the base table is a pk column, then we need to
                    // validate that the key slot position is the same
                    if (isColumnToBeAddPkCol) {
                        List<Cell> keySeqCells =
                                columnToBeAdded.get(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                                    PhoenixDatabaseMetaData.KEY_SEQ_BYTES);
                        if (keySeqCells != null && keySeqCells.size() > 0) {
                            Cell cell = keySeqCells.get(0);
                            int keySeq =
                                    PSmallint.INSTANCE.getCodec().decodeInt(cell.getValueArray(),
                                        cell.getValueOffset(), SortOrder.getDefault());
                            int pkPosition = basePhysicalTable.getPKColumns().size() + SchemaUtil.getPKPosition(view, existingViewColumn) + 1;
                            if (pkPosition != keySeq) {
                                return new MetaDataMutationResult(
                                        MutationCode.UNALLOWED_TABLE_MUTATION,
                                        EnvironmentEdgeManager.currentTimeMillis(),
                                        basePhysicalTable);
                            }
                        }
                    }
                }
                if (isColumnToBeAddPkCol) {
                    viewPkCols.remove(existingViewColumn);
                    addingExistingPkCol = true;
                }
            }
            /*
             * Allow adding a pk columns to base table : 1. if all the view pk columns are exactly the same as the base
             * table pk columns 2. if we are adding all the existing view pk columns to the base table
             */ 
            if (addingExistingPkCol && !viewPkCols.isEmpty()) {
                return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION, EnvironmentEdgeManager.currentTimeMillis(), basePhysicalTable);
            }
        }
        return null;
    }
    
    private class ColumnFinder extends StatelessTraverseAllExpressionVisitor<Void> {
        private boolean columnFound;
        private final Expression columnExpression;

        public ColumnFinder(Expression columnExpression) {
            this.columnExpression = columnExpression;
            columnFound = false;
        }

        private Void process(Expression expression) {
            if (expression.equals(columnExpression)) {
                columnFound = true;
            }
            return null;
        }

        @Override
        public Void visit(KeyValueColumnExpression expression) {
            return process(expression);
        }

        @Override
        public Void visit(RowKeyColumnExpression expression) {
            return process(expression);
        }

        @Override
        public Void visit(ProjectedColumnExpression expression) {
            return process(expression);
        }

        public boolean getColumnFound() {
            return columnFound;
        }
    }
    
//    private MetaDataMutationResult dropViewsOrViewIndexesIfNeeded(Region region,
//            PTable basePhysicalTable, List<Mutation> tableMetadata,
//            List<Mutation> mutationsForAddingColumnsToViews, byte[] schemaName, byte[] tableName,
//            List<ImmutableBytesPtr> invalidateList, long clientTimeStamp,
//            TableViewFinderResult childViewsResult)
//            throws IOException, SQLException {
//        List<Delete> columnDeletesForBaseTable = new ArrayList<>(tableMetadata.size());
//        // Isolate the deletes relevant to dropping columns. Also figure out what kind of columns
//        // are being added.
//        for (Mutation m : tableMetadata) {
//            if (m instanceof Delete) {
//                byte[][] rkmd = new byte[5][];
//                int pkCount = getVarChars(m.getRow(), rkmd);
//                if (pkCount > COLUMN_NAME_INDEX
//                        && Bytes.compareTo(schemaName, rkmd[SCHEMA_NAME_INDEX]) == 0
//                        && Bytes.compareTo(tableName, rkmd[TABLE_NAME_INDEX]) == 0) {
//                    columnDeletesForBaseTable.add((Delete) m);
//                }
//            }
//        }
//        for (TableInfo viewInfo : childViewsResult.getResults()) {
//        	byte[] viewKey = SchemaUtil.getTableKey(viewInfo.getTenantId(), viewInfo.getSchemaName(), viewInfo.getTableName());
//            PTable view = doGetTable(viewKey, clientTimeStamp, null);
//
//            ColumnOrdinalPositionUpdateList ordinalPositionList =
//                    new ColumnOrdinalPositionUpdateList();
//            for (Delete columnDeleteForBaseTable : columnDeletesForBaseTable) {
//                PColumn existingViewColumn = null;
//                byte[][] rkmd = new byte[5][];
//                getVarChars(columnDeleteForBaseTable.getRow(), rkmd);
//                String columnName = Bytes.toString(rkmd[COLUMN_NAME_INDEX]);
//                String columnFamily =
//                        rkmd[FAMILY_NAME_INDEX] == null ? null : Bytes
//                                .toString(rkmd[FAMILY_NAME_INDEX]);
//                byte[] columnKey = getColumnKey(viewKey, columnName, columnFamily);
//                try {
//                    existingViewColumn =
//                            columnFamily == null ? view.getColumnForColumnName(columnName) : view
//                                    .getColumnFamily(columnFamily).getPColumnForColumnName(columnName);
//                } catch (ColumnFamilyNotFoundException e) {
//                    // ignore since it means that the column family is not present for the column to
//                    // be added.
//                } catch (ColumnNotFoundException e) {
//                    // ignore since it means the column is not present in the view
//                }
//
//                // check if the view where expression contains the column being dropped and prevent
//                // it
//                if (existingViewColumn != null && view.getViewStatement() != null) {
//                    ParseNode viewWhere =
//                            new SQLParser(view.getViewStatement()).parseQuery().getWhere();
//                    PhoenixConnection conn=null;
//                    try {
//                        conn = QueryUtil.getConnectionOnServer(env.getConfiguration()).unwrap(
//                            PhoenixConnection.class);
//                    } catch (ClassNotFoundException e) {
//                    }
//                    PhoenixStatement statement = new PhoenixStatement(conn);
//                    TableRef baseTableRef = new TableRef(basePhysicalTable);
//                    ColumnResolver columnResolver = FromCompiler.getResolver(baseTableRef);
//                    StatementContext context = new StatementContext(statement, columnResolver);
//                    Expression whereExpression = WhereCompiler.compile(context, viewWhere);
//                    Expression colExpression =
//                            new ColumnRef(baseTableRef, existingViewColumn.getPosition())
//                                    .newColumnExpression();
//                    ColumnFinder columnFinder = new ColumnFinder(colExpression);
//                    whereExpression.accept(columnFinder);
//                    if (columnFinder.getColumnFound()) {
//                        // drop the view
//                    }
//                }
//
//                if (existingViewColumn != null) {
//                    // drop any view indexes that need this column
//                    dropIndexes(view, region, invalidateList, clientTimeStamp,
//                        schemaName, view.getName().getBytes(),
//                        mutationsForAddingColumnsToViews, existingViewColumn);
//                }
//            }
//        }
//        return null;
//    }

    private int getInteger(Put p, byte[] family, byte[] qualifier) {
        List<Cell> cells = p.get(family, qualifier);
        if (cells != null && cells.size() > 0) {
            Cell cell = cells.get(0);
            return (Integer)PInteger.INSTANCE.toObject(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength());
        }
        return 0;
    }

    @Override
    public void addColumn(RpcController controller, final AddColumnRequest request,
            RpcCallback<MetaDataResponse> done) {
        try {
            List<Mutation> tableMetaData = ProtobufUtil.getMutations(request);

            MetaDataMutationResult result = mutateColumn(tableMetaData, new ColumnMutator() {
                @Override
                public MetaDataMutationResult updateMutation(PTable table, byte[][] rowKeyMetaData,
                        List<Mutation> tableMetaData, Region region, List<ImmutableBytesPtr> invalidateList,
                        List<RowLock> locks, long clientTimeStamp) throws IOException, SQLException {
                    byte[] tenantId = rowKeyMetaData[TENANT_ID_INDEX];
                    byte[] schemaName = rowKeyMetaData[SCHEMA_NAME_INDEX];
                    byte[] tableName = rowKeyMetaData[TABLE_NAME_INDEX];
                    PTableType type = table.getType();
                    table = combineColumns(table, tenantId, schemaName, tableName, HConstants.LATEST_TIMESTAMP, request.getClientVersion()).getFirst();
                    byte[] tableHeaderRowKey = SchemaUtil.getTableKey(tenantId,
                            schemaName, tableName);
                    // Size for worst case - all new columns are PK column
                    List<Mutation> mutationsForAddingColumnsToViews = Lists.newArrayListWithExpectedSize(tableMetaData.size() * ( 1 + table.getIndexes().size()));

                    if (type == PTableType.TABLE || type == PTableType.SYSTEM) {
                        TableViewFinderResult childViewsResult = new TableViewFinderResult();
                        findAllChildViews(tenantId, table.getSchemaName().getBytes(), table.getTableName().getBytes(), childViewsResult);
                        if (childViewsResult.hasViews()) {
                            /* 
                             * Dis-allow if:
                             * 
                             * 1) The base column count is 0 which means that the metadata hasn't been upgraded yet or
                             * the upgrade is currently in progress.
                             * 
                             * 2) If the request is from a client that is older than 4.5 version of phoenix. 
                             * Starting from 4.5, metadata requests have the client version included in them. 
                             * We don't want to allow clients before 4.5 to add a column to the base table if it has views.
                             * 
                             * 3) Trying to swtich tenancy of a table that has views
                             */
                            if (table.getBaseColumnCount() == 0 
                                    || !request.hasClientVersion()
                                    || switchAttribute(table, table.isMultiTenant(), tableMetaData, MULTI_TENANT_BYTES)) {
                                return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION,
                                        EnvironmentEdgeManager.currentTimeMillis(), null);
                            } else {
                                MetaDataMutationResult mutationResult = validateColumnForAddToBaseTable(table, tableMetaData, rowKeyMetaData, childViewsResult, clientTimeStamp, request.getClientVersion());
                                // return if validation was not successful
                                if (mutationResult!=null)
                                    return mutationResult;
                            } 
                        }
                    } 
                    if (type == PTableType.VIEW
                            && EncodedColumnsUtil.usesEncodedColumnNames(table)) {
                        /*
                         * When adding a column to a view that uses encoded column name scheme, we
                         * need to modify the CQ counters stored in the view's physical table. So to
                         * make sure clients get the latest PTable, we need to invalidate the cache
                         * entry.
                         */
                        invalidateList.add(new ImmutableBytesPtr(MetaDataUtil
                                .getPhysicalTableRowForView(table)));



                    }
                    for (Mutation m : tableMetaData) {
                        byte[] key = m.getRow();
                        boolean addingPKColumn = false;
                        int pkCount = getVarChars(key, rowKeyMetaData);
                        if (pkCount > COLUMN_NAME_INDEX
                                && Bytes.compareTo(schemaName, rowKeyMetaData[SCHEMA_NAME_INDEX]) == 0
                                && Bytes.compareTo(tableName, rowKeyMetaData[TABLE_NAME_INDEX]) == 0) {
                            try {
                                if (pkCount > FAMILY_NAME_INDEX
                                        && rowKeyMetaData[PhoenixDatabaseMetaData.FAMILY_NAME_INDEX].length > 0) {
                                    PColumnFamily family =
                                            table.getColumnFamily(rowKeyMetaData[PhoenixDatabaseMetaData.FAMILY_NAME_INDEX]);
                                    family.getPColumnForColumnNameBytes(rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX]);
                                } else if (pkCount > COLUMN_NAME_INDEX
                                        && rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX].length > 0) {
                                    addingPKColumn = true;
                                    table.getPKColumn(new String(
                                            rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX]));
                                } else {
                                    continue;
                                }
                                return new MetaDataMutationResult(
                                        MutationCode.COLUMN_ALREADY_EXISTS, EnvironmentEdgeManager
                                        .currentTimeMillis(), table);
                            } catch (ColumnFamilyNotFoundException e) {
                                continue;
                            } catch (ColumnNotFoundException e) {
                                if (addingPKColumn) {
                                    // We may be adding a DESC column, so if table is already
                                    // able to be rowKeyOptimized, it should continue to be so.
                                    if (table.rowKeyOrderOptimizable()) {
                                        UpgradeUtil.addRowKeyOrderOptimizableCell(mutationsForAddingColumnsToViews, tableHeaderRowKey, clientTimeStamp);
                                    } else if (table.getType() == PTableType.VIEW){
                                        // Don't allow view PK to diverge from table PK as our upgrade code
                                        // does not handle this.
                                        return new MetaDataMutationResult(
                                                MutationCode.UNALLOWED_TABLE_MUTATION, EnvironmentEdgeManager
                                                .currentTimeMillis(), null);
                                    }
                                    // Add all indexes to invalidate list, as they will all be
                                    // adding the same PK column. No need to lock them, as we
                                    // have the parent table lock at this point.
                                    for (PTable index : table.getIndexes()) {
                                        invalidateList.add(new ImmutableBytesPtr(SchemaUtil
                                                .getTableKey(tenantId, index.getSchemaName()
                                                        .getBytes(), index.getTableName()
                                                        .getBytes())));
                                        // We may be adding a DESC column, so if index is already
                                        // able to be rowKeyOptimized, it should continue to be so.
                                        if (index.rowKeyOrderOptimizable()) {
                                            byte[] indexHeaderRowKey = SchemaUtil.getTableKey(index.getTenantId() == null ? ByteUtil.EMPTY_BYTE_ARRAY : index.getTenantId().getBytes(),
                                                    index.getSchemaName().getBytes(), index.getTableName().getBytes());
                                            UpgradeUtil.addRowKeyOrderOptimizableCell(mutationsForAddingColumnsToViews, indexHeaderRowKey, clientTimeStamp);
                                        }
                                    }
                                }
                                continue;
                            }
                        } else if (pkCount == COLUMN_NAME_INDEX &&
                                   ! (Bytes.compareTo(schemaName, rowKeyMetaData[SCHEMA_NAME_INDEX]) == 0 &&
                                      Bytes.compareTo(tableName, rowKeyMetaData[TABLE_NAME_INDEX]) == 0 ) ) {
                            // Invalidate any table with mutations
                            // TODO: this likely means we don't need the above logic that
                            // loops through the indexes if adding a PK column, since we'd
                            // always have header rows for those.
                            invalidateList.add(new ImmutableBytesPtr(SchemaUtil
                                    .getTableKey(tenantId,
                                            rowKeyMetaData[SCHEMA_NAME_INDEX],
                                            rowKeyMetaData[TABLE_NAME_INDEX])));
                        }
                    }
                    tableMetaData.addAll(mutationsForAddingColumnsToViews);
                    return null;
                }
            }, request.getClientVersion());
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
            }
        } catch (Throwable e) {
            logger.error("Add column failed: ", e);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException("Error when adding column: ", e));
        }
    }

    private PTable doGetTable(byte[] key, long clientTimeStamp, int clientVersion) throws IOException, SQLException {
        return doGetTable(key, clientTimeStamp, null, clientVersion);
    }

    private PTable doGetTable(byte[] key, long clientTimeStamp, RowLock rowLock, int clientVersion) throws IOException, SQLException {
        ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(key);
        Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache =
                GlobalCache.getInstance(this.env).getMetaDataCache();
        // Ask Lars about the expense of this call - if we don't take the lock, we still won't get
        // partial results
        // get the co-processor environment
        // TODO: check that key is within region.getStartKey() and region.getEndKey()
        // and return special code to force client to lookup region from meta.
        Region region = env.getRegion();
        /*
         * Lock directly on key, though it may be an index table. This will just prevent a table
         * from getting rebuilt too often.
         */
        final boolean wasLocked = (rowLock != null);
        boolean blockWriteRebuildIndex = env.getConfiguration().getBoolean(QueryServices.INDEX_FAILURE_BLOCK_WRITE,
                QueryServicesOptions.DEFAULT_INDEX_FAILURE_BLOCK_WRITE);
        if (!wasLocked) {
            rowLock = region.getRowLock(key, false);
            if (rowLock == null) {
                throw new IOException("Failed to acquire lock on " + Bytes.toStringBinary(key));
            }
        }
        try {
            PTable table = getCachedTable(clientTimeStamp, cacheKey, metaDataCache);
            if (table == null) {
                // Try cache again in case we were waiting on a lock
                table = getCachedTable(clientTimeStamp, cacheKey, metaDataCache);
                if (table == null) {
                    // Query for the latest table first, since it's not cached
                    table = buildTable(key, cacheKey, region, HConstants.LATEST_TIMESTAMP, clientVersion);
                    if ((table == null || table.getTimeStamp() >= clientTimeStamp) && (!blockWriteRebuildIndex
                        || table.getIndexDisableTimestamp() <= 0)) {
                        // Otherwise, query for an older version of the table - it won't be cached
                        table = buildTable(key, cacheKey, region, clientTimeStamp, clientVersion);

                    }
                }
                return table;
            }
            // Query for the latest table first, since it's not cached
            table = buildTable(key, cacheKey, region, HConstants.LATEST_TIMESTAMP, clientVersion);
            if ((table != null && table.getTimeStamp() < clientTimeStamp) || 
                    (blockWriteRebuildIndex && table.getIndexDisableTimestamp() > 0)) {
                return table;
            }
            // Otherwise, query for an older version of the table - it won't be cached
            return buildTable(key, cacheKey, region, clientTimeStamp, clientVersion);
        } finally {
            if (!wasLocked) rowLock.release();
        }
    }

    private PTable getCachedTable(long clientTimeStamp, ImmutableBytesPtr cacheKey,
        Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache) {
        PTable table = (PTable)metaDataCache.getIfPresent(cacheKey);
        // We only cache the latest, so we'll end up building the table with every call if the
        // client connection has specified an SCN.
        // TODO: If we indicate to the client that we're returning an older version, but there's a
        // newer version available, the client
        // can safely not call this, since we only allow modifications to the latest.
        if (table != null && table.getTimeStamp() < clientTimeStamp) {
            // Table on client is up-to-date with table on server, so just return
            if (isTableDeleted(table)) {
                table = null;
            }
            return table;
        }
        return null;
    }

    private List<PFunction> doGetFunctions(List<byte[]> keys, long clientTimeStamp) throws IOException, SQLException {
        Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache =
                GlobalCache.getInstance(this.env).getMetaDataCache();
        Region region = env.getRegion();
        Collections.sort(keys, new Comparator<byte[]>() {
            @Override
            public int compare(byte[] o1, byte[] o2) {
                return Bytes.compareTo(o1, o2);
            }
        });
        /*
         * Lock directly on key, though it may be an index table. This will just prevent a table
         * from getting rebuilt too often.
         */
        List<RowLock> rowLocks = new ArrayList<Region.RowLock>(keys.size());;
        try {
            rowLocks = new ArrayList<Region.RowLock>(keys.size());
            for (int i = 0; i < keys.size(); i++) {
                Region.RowLock rowLock = region.getRowLock(keys.get(i), false);
                if (rowLock == null) {
                    throw new IOException("Failed to acquire lock on "
                            + Bytes.toStringBinary(keys.get(i)));
                }
                rowLocks.add(rowLock);
            }

            List<PFunction> functionsAvailable = new ArrayList<PFunction>(keys.size());
            int numFunctions = keys.size();
            Iterator<byte[]> iterator = keys.iterator();
            while(iterator.hasNext()) {
                byte[] key = iterator.next();
                PFunction function = (PFunction)metaDataCache.getIfPresent(new FunctionBytesPtr(key));
                if (function != null && function.getTimeStamp() < clientTimeStamp) {
                    if (isFunctionDeleted(function)) {
                        return null;
                    }
                    functionsAvailable.add(function);
                    iterator.remove();
                }
            }
            if(functionsAvailable.size() == numFunctions) return functionsAvailable;

            // Query for the latest table first, since it's not cached
            List<PFunction> buildFunctions =
                    buildFunctions(keys, region, clientTimeStamp, false,
                        Collections.<Mutation> emptyList());
            if(buildFunctions == null || buildFunctions.isEmpty()) {
                return null;
            }
            functionsAvailable.addAll(buildFunctions);
            if(functionsAvailable.size() == numFunctions) return functionsAvailable;
            return null;
        } finally {
            for (Region.RowLock lock : rowLocks) {
                lock.release();
            }
            rowLocks.clear();
        }
    }
    
    private PColumn getColumn(int pkCount, byte[][] rowKeyMetaData, PTable table) throws ColumnFamilyNotFoundException, ColumnNotFoundException {
    	PColumn col = null;
        if (pkCount > FAMILY_NAME_INDEX
            && rowKeyMetaData[PhoenixDatabaseMetaData.FAMILY_NAME_INDEX].length > 0) {
            PColumnFamily family =
                table.getColumnFamily(rowKeyMetaData[PhoenixDatabaseMetaData.FAMILY_NAME_INDEX]);
            col =
                family.getPColumnForColumnNameBytes(rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX]);
        } else if (pkCount > COLUMN_NAME_INDEX
            && rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX].length > 0) {
            col = table.getPKColumn(new String(rowKeyMetaData[PhoenixDatabaseMetaData.COLUMN_NAME_INDEX]));
        }
    	return col;
    }

    @Override
    public void dropColumn(RpcController controller, final DropColumnRequest request,
            RpcCallback<MetaDataResponse> done) {
        List<Mutation> tableMetaData = null;
        final List<byte[]> tableNamesToDelete = Lists.newArrayList();
        final List<SharedTableState> sharedTablesToDelete = Lists.newArrayList();
        try {
            tableMetaData = ProtobufUtil.getMutations(request);
            MetaDataMutationResult result = mutateColumn(tableMetaData, new ColumnMutator() {
                @Override
                public MetaDataMutationResult updateMutation(PTable table, byte[][] rowKeyMetaData,
                        List<Mutation> tableMetaData, Region region,
                        List<ImmutableBytesPtr> invalidateList, List<RowLock> locks, long clientTimeStamp)
                        throws IOException, SQLException {

                    byte[] tenantId = rowKeyMetaData[TENANT_ID_INDEX];
                    byte[] schemaName = rowKeyMetaData[SCHEMA_NAME_INDEX];
                    byte[] tableName = rowKeyMetaData[TABLE_NAME_INDEX];
                    table = combineColumns(table, tenantId, schemaName, tableName, clientTimeStamp, request.getClientVersion()).getFirst();
                    boolean isView = table.getType() == PTableType.VIEW;
                    boolean deletePKColumn = false;
                    List<Mutation> additionalTableMetaData = Lists.newArrayList();
                    ListIterator<Mutation> iterator = tableMetaData.listIterator();
                    while (iterator.hasNext()) {
                        Mutation mutation = iterator.next();
                        byte[] key = mutation.getRow();
                        int pkCount = getVarChars(key, rowKeyMetaData);
                        if (isView && mutation instanceof Put) {
                        	PColumn column = getColumn(pkCount, rowKeyMetaData, table);
							if (column == null)
								continue;
                        	// ignore any puts that modify the ordinal positions of columns
                        	iterator.remove();
                        } 
                        else if (mutation instanceof Delete) {
                            if (pkCount > COLUMN_NAME_INDEX
                                && Bytes.compareTo(schemaName, rowKeyMetaData[SCHEMA_NAME_INDEX]) == 0
                                && Bytes.compareTo(tableName, rowKeyMetaData[TABLE_NAME_INDEX]) == 0) {
                                PColumn columnToDelete = null;
                                try {
                                	columnToDelete = getColumn(pkCount, rowKeyMetaData, table);
									if (columnToDelete == null)
										continue;
                                    deletePKColumn = columnToDelete.getFamilyName() == null;
									if (isView) {
                                        // if we are dropping a derived column add it to the excluded column list
                                        if (columnToDelete.isDerived()) {
                                            mutation = MetaDataUtil
                                                .cloneDeleteToPutAndAddColumn((Delete) mutation, TABLE_FAMILY_BYTES, LINK_TYPE_BYTES, LinkType.EXCLUDED_COLUMN.getSerializedValueAsByteArray());
                                            iterator.set(mutation);
                                        }

                                        if (table.getBaseColumnCount() != DIVERGED_VIEW_BASE_COLUMN_COUNT
                                            && columnToDelete.isDerived()) {
                                            /*
                                             * If the column being dropped is inherited from the base table, then the
                                             * view is about to diverge itself from the base table. The consequence of
                                             * this divergence is that that any further meta-data changes made to the
                                             * base table will not be propagated to the hierarchy of views where this
                                             * view is the root.
                                             */
                                            byte[] viewKey = SchemaUtil.getTableKey(tenantId, schemaName, tableName);
                                            Put updateBaseColumnCountPut = new Put(viewKey);
                                            byte[] baseColumnCountPtr = new byte[PInteger.INSTANCE.getByteSize()];
                                            PInteger.INSTANCE.getCodec().encodeInt(DIVERGED_VIEW_BASE_COLUMN_COUNT,
                                                baseColumnCountPtr, 0);
                                            updateBaseColumnCountPut.addColumn(PhoenixDatabaseMetaData.TABLE_FAMILY_BYTES,
                                                PhoenixDatabaseMetaData.BASE_COLUMN_COUNT_BYTES, clientTimeStamp,
                                                baseColumnCountPtr);
                                            additionalTableMetaData.add(updateBaseColumnCountPut);
                                        }
                                    }
                                    if (columnToDelete.isViewReferenced()) { // Disallow deletion of column referenced in WHERE clause of view
                                        return new MetaDataMutationResult(MutationCode.UNALLOWED_TABLE_MUTATION, EnvironmentEdgeManager.currentTimeMillis(), table, columnToDelete);
                                    }
                                    // drop any indexes that need the column that is going to be dropped
                                    dropIndexes(table, region, invalidateList, locks,
                                        clientTimeStamp, schemaName, tableName,
                                        additionalTableMetaData, columnToDelete,
                                        tableNamesToDelete, sharedTablesToDelete, request.getClientVersion());
                                } catch (ColumnFamilyNotFoundException e) {
                                    return new MetaDataMutationResult(
                                        MutationCode.COLUMN_NOT_FOUND, EnvironmentEdgeManager
                                        .currentTimeMillis(), table, columnToDelete);
                                } catch (ColumnNotFoundException e) {
                                    return new MetaDataMutationResult(
                                        MutationCode.COLUMN_NOT_FOUND, EnvironmentEdgeManager
                                        .currentTimeMillis(), table, columnToDelete);
                                }
                            }
                        }

                    }
                    if (deletePKColumn) {
                        if (table.getPKColumns().size() == 1) {
                            return new MetaDataMutationResult(MutationCode.NO_PK_COLUMNS,
                                    EnvironmentEdgeManager.currentTimeMillis(), null);
                        }
                    }
                    tableMetaData.addAll(additionalTableMetaData);
                    long currentTime = MetaDataUtil.getClientTimeStamp(tableMetaData);
                    return new MetaDataMutationResult(MutationCode.TABLE_ALREADY_EXISTS, currentTime, null, tableNamesToDelete, sharedTablesToDelete);
                }
            }, request.getClientVersion());
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
            }
        } catch (Throwable e) {
            logger.error("Drop column failed: ", e);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException("Error when dropping column: ", e));
        }
    }

    private void dropIndexes(PTable table, Region region, List<ImmutableBytesPtr> invalidateList,
            List<RowLock> locks, long clientTimeStamp, byte[] schemaName,
            byte[] tableName, List<Mutation> additionalTableMetaData, PColumn columnToDelete, 
            List<byte[]> tableNamesToDelete, List<SharedTableState> sharedTablesToDelete, int clientVersion)
            throws IOException, SQLException {
        // Look for columnToDelete in any indexes. If found as PK column, get lock and drop the
        // index and then invalidate it
        // Covered columns are deleted from the index by the client
        PhoenixConnection connection = null;
        try {
            connection = table.getIndexes().isEmpty() ? null : QueryUtil.getConnectionOnServer(
                env.getConfiguration()).unwrap(PhoenixConnection.class);
        } catch (ClassNotFoundException e) {
        }
        for (PTable index : table.getIndexes()) {
            byte[] tenantId = index.getTenantId() == null ? ByteUtil.EMPTY_BYTE_ARRAY : index.getTenantId().getBytes();
            IndexMaintainer indexMaintainer = index.getIndexMaintainer(table, connection);
            byte[] indexKey =
                    SchemaUtil.getTableKey(tenantId, index.getSchemaName().getBytes(), index
                            .getTableName().getBytes());
            Pair<String, String> columnToDeleteInfo = new Pair<>(columnToDelete.getFamilyName().getString(), columnToDelete.getName().getString());
            ColumnReference colDropRef = new ColumnReference(columnToDelete.getFamilyName().getBytes(), columnToDelete.getColumnQualifierBytes());
            boolean isColumnIndexed = indexMaintainer.getIndexedColumnInfo().contains(columnToDeleteInfo);
            boolean isCoveredColumn = indexMaintainer.getCoveredColumns().contains(colDropRef);
            // If index requires this column for its pk, then drop it
            if (isColumnIndexed) {
                // Since we're dropping the index, lock it to ensure
                // that a change in index state doesn't
                // occur while we're dropping it.
                acquireLock(region, indexKey, locks);
                // Drop the index table. The doDropTable will expand
                // this to all of the table rows and invalidate the
                // index table
                additionalTableMetaData.add(new Delete(indexKey, clientTimeStamp));
                byte[] linkKey =
                        MetaDataUtil.getParentLinkKey(tenantId, schemaName, tableName, index
                                .getTableName().getBytes());
                // Drop the link between the data table and the
                // index table
                additionalTableMetaData.add(new Delete(linkKey, clientTimeStamp));
                List<Mutation> childLinksMutations = Lists.newArrayList();
                doDropTable(indexKey, tenantId, index.getSchemaName().getBytes(), index
                        .getTableName().getBytes(), tableName, index.getType(),
                    additionalTableMetaData, childLinksMutations, invalidateList, tableNamesToDelete, sharedTablesToDelete, false, clientVersion);
                // there should be no child links to delete since we are just dropping an index
                assert(childLinksMutations.isEmpty());
                invalidateList.add(new ImmutableBytesPtr(indexKey));
            }
            // If the dropped column is a covered index column, invalidate the index
            else if (isCoveredColumn){
                invalidateList.add(new ImmutableBytesPtr(indexKey));
            }
        }
    }

    @Override
    public void clearCache(RpcController controller, ClearCacheRequest request,
            RpcCallback<ClearCacheResponse> done) {
        GlobalCache cache = GlobalCache.getInstance(this.env);
        Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache =
                GlobalCache.getInstance(this.env).getMetaDataCache();
        metaDataCache.invalidateAll();
        long unfreedBytes = cache.clearTenantCache();
        ClearCacheResponse.Builder builder = ClearCacheResponse.newBuilder();
        builder.setUnfreedBytes(unfreedBytes);
        done.run(builder.build());
    }

    @Override
    public void getVersion(RpcController controller, GetVersionRequest request, RpcCallback<GetVersionResponse> done) {

        GetVersionResponse.Builder builder = GetVersionResponse.newBuilder();
        Configuration config = env.getConfiguration();
        boolean isTablesMappingEnabled = SchemaUtil.isNamespaceMappingEnabled(PTableType.TABLE,
                new ReadOnlyProps(config.iterator()));
        if (isTablesMappingEnabled
                && PhoenixDatabaseMetaData.MIN_NAMESPACE_MAPPED_PHOENIX_VERSION > request.getClientVersion()) {
            logger.error("Old client is not compatible when" + " system tables are upgraded to map to namespace");
            ProtobufUtil.setControllerException(controller,
                    ServerUtil.createIOException(
                            SchemaUtil.getPhysicalTableName(PhoenixDatabaseMetaData.SYSTEM_CATALOG_NAME_BYTES,
                                    isTablesMappingEnabled).toString(),
                    new DoNotRetryIOException(
                            "Old client is not compatible when" + " system tables are upgraded to map to namespace")));
        }
        long version = MetaDataUtil.encodeVersion(env.getHBaseVersion(), config);

        builder.setVersion(version);
        done.run(builder.build());
    }

    @Override
    public void updateIndexState(RpcController controller, UpdateIndexStateRequest request,
            RpcCallback<MetaDataResponse> done) {
        MetaDataResponse.Builder builder = MetaDataResponse.newBuilder();
        byte[] schemaName = null;
        byte[] tableName = null;
        try {
            byte[][] rowKeyMetaData = new byte[3][];
            List<Mutation> tableMetadata = ProtobufUtil.getMutations(request);
            MetaDataUtil.getTenantIdAndSchemaAndTableName(tableMetadata, rowKeyMetaData);
            byte[] tenantId = rowKeyMetaData[PhoenixDatabaseMetaData.TENANT_ID_INDEX];
            schemaName = rowKeyMetaData[PhoenixDatabaseMetaData.SCHEMA_NAME_INDEX];
            tableName = rowKeyMetaData[PhoenixDatabaseMetaData.TABLE_NAME_INDEX];
            final byte[] key = SchemaUtil.getTableKey(tenantId, schemaName, tableName);
            Region region = env.getRegion();
            MetaDataMutationResult result = checkTableKeyInRegion(key, region);
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
                return;
            }
            long timeStamp = HConstants.LATEST_TIMESTAMP;
            ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(key);
            List<Cell> newKVs = tableMetadata.get(0).getFamilyCellMap().get(TABLE_FAMILY_BYTES);
            Cell newKV = null;
            int disableTimeStampKVIndex = -1;
            int indexStateKVIndex = 0;
            int index = 0;
            for(Cell cell : newKVs) {
                if(Bytes.compareTo(cell.getQualifierArray(), cell.getQualifierOffset(), cell.getQualifierLength(),
                      INDEX_STATE_BYTES, 0, INDEX_STATE_BYTES.length) == 0){
                  newKV = cell;
                  indexStateKVIndex = index;
                  timeStamp = cell.getTimestamp();
                } else if (Bytes.compareTo(cell.getQualifierArray(), cell.getQualifierOffset(), cell.getQualifierLength(),
                  INDEX_DISABLE_TIMESTAMP_BYTES, 0, INDEX_DISABLE_TIMESTAMP_BYTES.length) == 0) {
                  disableTimeStampKVIndex = index;
                }
                index++;
            }
            PIndexState newState =
                    PIndexState.fromSerializedValue(newKV.getValueArray()[newKV.getValueOffset()]);
            RowLock rowLock = region.getRowLock(key, false);
            if (rowLock == null) {
                throw new IOException("Failed to acquire lock on " + Bytes.toStringBinary(key));
            }
            try {
                Get get = new Get(key);
                get.addColumn(TABLE_FAMILY_BYTES, DATA_TABLE_NAME_BYTES);
                get.addColumn(TABLE_FAMILY_BYTES, INDEX_STATE_BYTES);
                get.addColumn(TABLE_FAMILY_BYTES, INDEX_DISABLE_TIMESTAMP_BYTES);
                get.addColumn(TABLE_FAMILY_BYTES, ROW_KEY_ORDER_OPTIMIZABLE_BYTES);
                Result currentResult = region.get(get);
                if (currentResult.rawCells().length == 0) {
                    builder.setReturnCode(MetaDataProtos.MutationCode.TABLE_NOT_FOUND);
                    builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                    done.run(builder.build());
                    return;
                }
                Cell dataTableKV = currentResult.getColumnLatestCell(TABLE_FAMILY_BYTES, DATA_TABLE_NAME_BYTES);
                Cell currentStateKV = currentResult.getColumnLatestCell(TABLE_FAMILY_BYTES, INDEX_STATE_BYTES);
                Cell currentDisableTimeStamp = currentResult.getColumnLatestCell(TABLE_FAMILY_BYTES, INDEX_DISABLE_TIMESTAMP_BYTES);
                boolean rowKeyOrderOptimizable = currentResult.getColumnLatestCell(TABLE_FAMILY_BYTES, ROW_KEY_ORDER_OPTIMIZABLE_BYTES) != null;

                PIndexState currentState =
                        PIndexState.fromSerializedValue(currentStateKV.getValueArray()[currentStateKV
                                .getValueOffset()]);
                // Timestamp of INDEX_STATE gets updated with each call
                long actualTimestamp = currentStateKV.getTimestamp();
                long curTimeStampVal = 0;
                if ((currentDisableTimeStamp != null && currentDisableTimeStamp.getValueLength() > 0)) {
                    curTimeStampVal = (Long) PLong.INSTANCE.toObject(currentDisableTimeStamp.getValueArray(),
                            currentDisableTimeStamp.getValueOffset(), currentDisableTimeStamp.getValueLength());
                    // new DisableTimeStamp is passed in
                    if (disableTimeStampKVIndex >= 0) {
                        Cell newDisableTimeStampCell = newKVs.get(disableTimeStampKVIndex);
                        long expectedTimestamp = newDisableTimeStampCell.getTimestamp();
                        // If the index status has been updated after the upper bound of the scan we use
                        // to partially rebuild the index, then we need to fail the rebuild because an
                        // index write failed before the rebuild was complete.
                        if (actualTimestamp > expectedTimestamp) {
                            builder.setReturnCode(MetaDataProtos.MutationCode.UNALLOWED_TABLE_MUTATION);
                            builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                            done.run(builder.build());
                            return;
                        }
                        long newDisableTimeStamp = (Long) PLong.INSTANCE.toObject(newDisableTimeStampCell.getValueArray(),
                                newDisableTimeStampCell.getValueOffset(), newDisableTimeStampCell.getValueLength());
                        // We use the sign of the INDEX_DISABLE_TIMESTAMP to differentiate the keep-index-active (negative)
                        // from block-writes-to-data-table case. In either case, we want to keep the oldest timestamp to
                        // drive the partial index rebuild rather than update it with each attempt to update the index
                        // when a new data table write occurs.
                        // We do legitimately move the INDEX_DISABLE_TIMESTAMP to be newer when we're rebuilding the
                        // index in which case the state will be INACTIVE or PENDING_ACTIVE.
                        if (curTimeStampVal != 0 
                                && (newState == PIndexState.DISABLE || newState == PIndexState.PENDING_ACTIVE) 
                                && Math.abs(curTimeStampVal) < Math.abs(newDisableTimeStamp)) {
                            // do not reset disable timestamp as we want to keep the min
                            newKVs.remove(disableTimeStampKVIndex);
                            disableTimeStampKVIndex = -1;
                        }
                    }
                }
                // Detect invalid transitions
                if (currentState == PIndexState.BUILDING) {
                    if (newState == PIndexState.USABLE) {
                        builder.setReturnCode(MetaDataProtos.MutationCode.UNALLOWED_TABLE_MUTATION);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        done.run(builder.build());
                        return;
                    }
                } else if (currentState == PIndexState.DISABLE) {
                    // Can't transition back to INACTIVE if INDEX_DISABLE_TIMESTAMP is 0
                    if (newState != PIndexState.BUILDING && newState != PIndexState.DISABLE &&
                        (newState != PIndexState.INACTIVE || curTimeStampVal == 0)) {
                        builder.setReturnCode(MetaDataProtos.MutationCode.UNALLOWED_TABLE_MUTATION);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        done.run(builder.build());
                        return;
                    }
                    // Done building, but was disable before that, so that in disabled state
                    if (newState == PIndexState.ACTIVE) {
                        newState = PIndexState.DISABLE;
                    }
                }

                if (currentState == PIndexState.BUILDING && newState != PIndexState.ACTIVE) {
                    timeStamp = currentStateKV.getTimestamp();
                }
                if ((currentState == PIndexState.ACTIVE || currentState == PIndexState.PENDING_ACTIVE) && newState == PIndexState.UNUSABLE) {
                    newState = PIndexState.INACTIVE;
                    newKVs.set(indexStateKVIndex, KeyValueUtil.newKeyValue(key, TABLE_FAMILY_BYTES,
                        INDEX_STATE_BYTES, timeStamp, Bytes.toBytes(newState.getSerializedValue())));
                } else if ((currentState == PIndexState.INACTIVE || currentState == PIndexState.PENDING_ACTIVE) && newState == PIndexState.USABLE) {
                    // Don't allow manual state change to USABLE (i.e. ACTIVE) if non zero INDEX_DISABLE_TIMESTAMP
                    if (curTimeStampVal != 0) {
                        newState = currentState;
                    } else {
                        newState = PIndexState.ACTIVE;
                    }
                    newKVs.set(indexStateKVIndex, KeyValueUtil.newKeyValue(key, TABLE_FAMILY_BYTES,
                        INDEX_STATE_BYTES, timeStamp, Bytes.toBytes(newState.getSerializedValue())));
                }

                PTable returnTable = null;
                if (currentState != newState || disableTimeStampKVIndex != -1) {
                    // make a copy of tableMetadata so we can add to it
                    tableMetadata = new ArrayList<Mutation>(tableMetadata);
                    // Always include the empty column value at latest timestamp so
                    // that clients pull over update.
                    Put emptyValue = new Put(key);
                    emptyValue.addColumn(TABLE_FAMILY_BYTES, 
                            QueryConstants.EMPTY_COLUMN_BYTES, 
                            HConstants.LATEST_TIMESTAMP, 
                            QueryConstants.EMPTY_COLUMN_VALUE_BYTES);
                    tableMetadata.add(emptyValue);
                    byte[] dataTableKey = null;
                    if (dataTableKV != null) {
                        dataTableKey = SchemaUtil.getTableKey(tenantId, schemaName, CellUtil.cloneValue(dataTableKV));
                        // insert an empty KV to trigger time stamp update on data table row
                        Put p = new Put(dataTableKey);
                        p.addColumn(TABLE_FAMILY_BYTES,
                                QueryConstants.EMPTY_COLUMN_BYTES,
                                HConstants.LATEST_TIMESTAMP,
                                QueryConstants.EMPTY_COLUMN_VALUE_BYTES);
                        tableMetadata.add(p);
                    }
                    boolean setRowKeyOrderOptimizableCell = newState == PIndexState.BUILDING && !rowKeyOrderOptimizable;
                    // We're starting a rebuild of the index, so add our rowKeyOrderOptimizable cell
                    // so that the row keys get generated using the new row key format
                    if (setRowKeyOrderOptimizableCell) {
                        UpgradeUtil.addRowKeyOrderOptimizableCell(tableMetadata, key, timeStamp);
                    }
                    region.mutateRowsWithLocks(tableMetadata, Collections.<byte[]> emptySet(), HConstants.NO_NONCE,
                        HConstants.NO_NONCE);
                    // Invalidate from cache
                    Cache<ImmutableBytesPtr,PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
                    metaDataCache.invalidate(cacheKey);
                    if(dataTableKey != null) {
                        metaDataCache.invalidate(new ImmutableBytesPtr(dataTableKey));
                    }
                    if (setRowKeyOrderOptimizableCell || disableTimeStampKVIndex != -1
                            || currentState == PIndexState.DISABLE || newState == PIndexState.BUILDING) {
                        returnTable = doGetTable(key, HConstants.LATEST_TIMESTAMP, rowLock, request.getClientVersion());
                    }
                }
                // Get client timeStamp from mutations, since it may get updated by the
                // mutateRowsWithLocks call
                long currentTime = MetaDataUtil.getClientTimeStamp(tableMetadata);
                builder.setReturnCode(MetaDataProtos.MutationCode.TABLE_ALREADY_EXISTS);
                builder.setMutationTime(currentTime);
                if (returnTable != null) {
                    builder.setTable(PTableImpl.toProto(returnTable));
                }
                done.run(builder.build());
                return;
            } finally {
                rowLock.release();
            }
        } catch (Throwable t) {
          logger.error("updateIndexState failed", t);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException(SchemaUtil.getTableName(schemaName, tableName), t));
        }
    }

    private static MetaDataMutationResult checkKeyInRegion(byte[] key, Region region, MutationCode code) {
        return ServerUtil.isKeyInRegion(key, region) ? null : 
            new MetaDataMutationResult(code, EnvironmentEdgeManager.currentTimeMillis(), null);
    }

    private static MetaDataMutationResult checkTableKeyInRegion(byte[] key, Region region) {
        return checkKeyInRegion(key, region, MutationCode.TABLE_NOT_IN_REGION);

    }

    private static MetaDataMutationResult checkFunctionKeyInRegion(byte[] key, Region region) {
        return checkKeyInRegion(key, region, MutationCode.FUNCTION_NOT_IN_REGION);
    }

    private static MetaDataMutationResult checkSchemaKeyInRegion(byte[] key, Region region) {
        return checkKeyInRegion(key, region, MutationCode.SCHEMA_NOT_IN_REGION);

    }
    
    private static class ViewInfo {
        private byte[] tenantId;
        private byte[] schemaName;
        private byte[] viewName;
        
        public ViewInfo(byte[] tenantId, byte[] schemaName, byte[] viewName) {
            super();
            this.tenantId = tenantId;
            this.schemaName = schemaName;
            this.viewName = viewName;
        }

        public byte[] getTenantId() {
            return tenantId;
        }

        public byte[] getSchemaName() {
            return schemaName;
        }

        public byte[] getViewName() {
            return viewName;
        }
    }

    @Override
    public void clearTableFromCache(RpcController controller, ClearTableFromCacheRequest request,
            RpcCallback<ClearTableFromCacheResponse> done) {
        byte[] schemaName = request.getSchemaName().toByteArray();
        byte[] tableName = request.getTableName().toByteArray();
        try {
            byte[] tenantId = request.getTenantId().toByteArray();
            byte[] key = SchemaUtil.getTableKey(tenantId, schemaName, tableName);
            ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(key);
            Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache =
                    GlobalCache.getInstance(this.env).getMetaDataCache();
            metaDataCache.invalidate(cacheKey);
        } catch (Throwable t) {
            logger.error("clearTableFromCache failed", t);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException(SchemaUtil.getTableName(schemaName, tableName), t));
        }
    }

    @Override
    public void getSchema(RpcController controller, GetSchemaRequest request, RpcCallback<MetaDataResponse> done) {
        MetaDataResponse.Builder builder = MetaDataResponse.newBuilder();
        Region region = env.getRegion();
        String schemaName = request.getSchemaName();
        byte[] lockKey = SchemaUtil.getSchemaKey(schemaName);
        MetaDataMutationResult result = checkSchemaKeyInRegion(lockKey, region);
        if (result != null) {
            done.run(MetaDataMutationResult.toProto(result));
            return;
        }
        long clientTimeStamp = request.getClientTimestamp();
        List<RowLock> locks = Lists.newArrayList();
        try {
            acquireLock(region, lockKey, locks);
            // Get as of latest timestamp so we can detect if we have a
            // newer schema that already
            // exists without making an additional query
            ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(lockKey);
            PSchema schema = loadSchema(env, lockKey, cacheKey, clientTimeStamp, clientTimeStamp);
            if (schema != null) {
                if (schema.getTimeStamp() < clientTimeStamp) {
                    if (!isSchemaDeleted(schema)) {
                        builder.setReturnCode(MetaDataProtos.MutationCode.SCHEMA_ALREADY_EXISTS);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        builder.setSchema(PSchema.toProto(schema));
                        done.run(builder.build());
                        return;
                    } else {
                        builder.setReturnCode(MetaDataProtos.MutationCode.NEWER_SCHEMA_FOUND);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        builder.setSchema(PSchema.toProto(schema));
                        done.run(builder.build());
                        return;
                    }
                }
            }
        } catch (Exception e) {
            long currentTime = EnvironmentEdgeManager.currentTimeMillis();
            builder.setReturnCode(MetaDataProtos.MutationCode.SCHEMA_NOT_FOUND);
            builder.setMutationTime(currentTime);
            done.run(builder.build());
            return;
        } finally {
            region.releaseRowLocks(locks);
        }
    }

    @Override
    public void getFunctions(RpcController controller, GetFunctionsRequest request,
            RpcCallback<MetaDataResponse> done) {
        MetaDataResponse.Builder builder = MetaDataResponse.newBuilder();
        byte[] tenantId = request.getTenantId().toByteArray();
        List<String> functionNames = new ArrayList<>(request.getFunctionNamesCount());
        try {
            Region region = env.getRegion();
            List<ByteString> functionNamesList = request.getFunctionNamesList();
            List<Long> functionTimestampsList = request.getFunctionTimestampsList();
            List<byte[]> keys = new ArrayList<byte[]>(request.getFunctionNamesCount());
            List<Pair<byte[], Long>> functions = new ArrayList<Pair<byte[], Long>>(request.getFunctionNamesCount());
            for(int i = 0; i< functionNamesList.size();i++) {
                byte[] functionName = functionNamesList.get(i).toByteArray();
                functionNames.add(Bytes.toString(functionName));
                byte[] key = SchemaUtil.getFunctionKey(tenantId, functionName);
                MetaDataMutationResult result = checkFunctionKeyInRegion(key, region);
                if (result != null) {
                    done.run(MetaDataMutationResult.toProto(result));
                    return;
                }
                functions.add(new Pair<byte[], Long>(functionName,functionTimestampsList.get(i)));
                keys.add(key);
            }

            long currentTime = EnvironmentEdgeManager.currentTimeMillis();
            List<PFunction> functionsAvailable = doGetFunctions(keys, request.getClientTimestamp());
            if (functionsAvailable == null) {
                builder.setReturnCode(MetaDataProtos.MutationCode.FUNCTION_NOT_FOUND);
                builder.setMutationTime(currentTime);
                done.run(builder.build());
                return;
            }
            builder.setReturnCode(MetaDataProtos.MutationCode.FUNCTION_ALREADY_EXISTS);
            builder.setMutationTime(currentTime);

            for (PFunction function : functionsAvailable) {
                builder.addFunction(PFunction.toProto(function));
            }
            done.run(builder.build());
            return;
        } catch (Throwable t) {
            logger.error("getFunctions failed", t);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException(functionNames.toString(), t));
        }
    }

    @Override
    public void createFunction(RpcController controller, CreateFunctionRequest request,
            RpcCallback<MetaDataResponse> done) {
        MetaDataResponse.Builder builder = MetaDataResponse.newBuilder();
        byte[][] rowKeyMetaData = new byte[2][];
        byte[] functionName = null;
        try {
            List<Mutation> functionMetaData = ProtobufUtil.getMutations(request);
            boolean temporaryFunction = request.getTemporary();
            MetaDataUtil.getTenantIdAndFunctionName(functionMetaData, rowKeyMetaData);
            byte[] tenantIdBytes = rowKeyMetaData[PhoenixDatabaseMetaData.TENANT_ID_INDEX];
            functionName = rowKeyMetaData[PhoenixDatabaseMetaData.FUNTION_NAME_INDEX];
            byte[] lockKey = SchemaUtil.getFunctionKey(tenantIdBytes, functionName);
            Region region = env.getRegion();
            MetaDataMutationResult result = checkFunctionKeyInRegion(lockKey, region);
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
                return;
            }
            List<RowLock> locks = Lists.newArrayList();
            long clientTimeStamp = MetaDataUtil.getClientTimeStamp(functionMetaData);
            try {
                acquireLock(region, lockKey, locks);
                // Get as of latest timestamp so we can detect if we have a newer function that already
                // exists without making an additional query
                ImmutableBytesPtr cacheKey = new FunctionBytesPtr(lockKey);
                PFunction function =
                        loadFunction(env, lockKey, cacheKey, clientTimeStamp, clientTimeStamp, request.getReplace(), functionMetaData);
                if (function != null) {
                    if (function.getTimeStamp() < clientTimeStamp) {
                        // If the function is older than the client time stamp and it's deleted,
                        // continue
                        if (!isFunctionDeleted(function)) {
                            builder.setReturnCode(MetaDataProtos.MutationCode.FUNCTION_ALREADY_EXISTS);
                            builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                            builder.addFunction(PFunction.toProto(function));
                            done.run(builder.build());
                            if(!request.getReplace()) {
                                return;
                            }
                        }
                    } else {
                        builder.setReturnCode(MetaDataProtos.MutationCode.NEWER_FUNCTION_FOUND);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        builder.addFunction(PFunction.toProto(function));
                        done.run(builder.build());
                        return;
                    }
                }
                // Don't store function info for temporary functions.
                if(!temporaryFunction) {
                    region.mutateRowsWithLocks(functionMetaData, Collections.<byte[]> emptySet(), HConstants.NO_NONCE, HConstants.NO_NONCE);
                }

                // Invalidate the cache - the next getFunction call will add it
                // TODO: consider loading the function that was just created here, patching up the parent function, and updating the cache
                Cache<ImmutableBytesPtr,PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
                metaDataCache.invalidate(cacheKey);
                // Get timeStamp from mutations - the above method sets it if it's unset
                long currentTimeStamp = MetaDataUtil.getClientTimeStamp(functionMetaData);
                builder.setReturnCode(MetaDataProtos.MutationCode.FUNCTION_NOT_FOUND);
                builder.setMutationTime(currentTimeStamp);
                done.run(builder.build());
                return;
            } finally {
                region.releaseRowLocks(locks);
            }
        } catch (Throwable t) {
          logger.error("createFunction failed", t);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException(Bytes.toString(functionName), t));
        }
    }

    @Override
    public void dropFunction(RpcController controller, DropFunctionRequest request,
            RpcCallback<MetaDataResponse> done) {
        byte[][] rowKeyMetaData = new byte[2][];
        byte[] functionName = null;
        try {
            List<Mutation> functionMetaData = ProtobufUtil.getMutations(request);
            MetaDataUtil.getTenantIdAndFunctionName(functionMetaData, rowKeyMetaData);
            byte[] tenantIdBytes = rowKeyMetaData[PhoenixDatabaseMetaData.TENANT_ID_INDEX];
            functionName = rowKeyMetaData[PhoenixDatabaseMetaData.FUNTION_NAME_INDEX];
            byte[] lockKey = SchemaUtil.getFunctionKey(tenantIdBytes, functionName);
            Region region = env.getRegion();
            MetaDataMutationResult result = checkFunctionKeyInRegion(lockKey, region);
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
                return;
            }
            List<RowLock> locks = Lists.newArrayList();
            long clientTimeStamp = MetaDataUtil.getClientTimeStamp(functionMetaData);
            try {
                acquireLock(region, lockKey, locks);
                List<byte[]> keys = new ArrayList<byte[]>(1);
                keys.add(lockKey);
                List<ImmutableBytesPtr> invalidateList = new ArrayList<ImmutableBytesPtr>();

                result = doDropFunction(clientTimeStamp, keys, functionMetaData, invalidateList);
                if (result.getMutationCode() != MutationCode.FUNCTION_ALREADY_EXISTS) {
                    done.run(MetaDataMutationResult.toProto(result));
                    return;
                }
                region.mutateRowsWithLocks(functionMetaData, Collections.<byte[]> emptySet(), HConstants.NO_NONCE, HConstants.NO_NONCE);

                Cache<ImmutableBytesPtr,PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env).getMetaDataCache();
                long currentTime = MetaDataUtil.getClientTimeStamp(functionMetaData);
                for(ImmutableBytesPtr ptr: invalidateList) {
                    metaDataCache.invalidate(ptr);
                    metaDataCache.put(ptr, newDeletedFunctionMarker(currentTime));

                }

                done.run(MetaDataMutationResult.toProto(result));
                return;
            } finally {
                region.releaseRowLocks(locks);
            }
        } catch (Throwable t) {
          logger.error("dropFunction failed", t);
            ProtobufUtil.setControllerException(controller,
                ServerUtil.createIOException(Bytes.toString(functionName), t));
        }
    }

    private MetaDataMutationResult doDropFunction(long clientTimeStamp, List<byte[]> keys, List<Mutation> functionMetaData, List<ImmutableBytesPtr> invalidateList)
            throws IOException, SQLException {
        List<byte[]> keysClone = new ArrayList<byte[]>(keys);
        List<PFunction> functions = doGetFunctions(keysClone, clientTimeStamp);
        // We didn't find a table at the latest timestamp, so either there is no table or
        // there was a table, but it's been deleted. In either case we want to return.
        if (functions == null || functions.isEmpty()) {
            if (buildDeletedFunction(keys.get(0), new FunctionBytesPtr(keys.get(0)), env.getRegion(), clientTimeStamp) != null) {
                return new MetaDataMutationResult(MutationCode.FUNCTION_ALREADY_EXISTS, EnvironmentEdgeManager.currentTimeMillis(), null);
            }
            return new MetaDataMutationResult(MutationCode.FUNCTION_NOT_FOUND, EnvironmentEdgeManager.currentTimeMillis(), null);
        }

        if (functions != null && !functions.isEmpty()) {
            if (functions.get(0).getTimeStamp() < clientTimeStamp) {
                // If the function is older than the client time stamp and it's deleted,
                // continue
                if (isFunctionDeleted(functions.get(0))) {
                    return new MetaDataMutationResult(MutationCode.FUNCTION_NOT_FOUND,
                            EnvironmentEdgeManager.currentTimeMillis(), null);
                }
                invalidateList.add(new FunctionBytesPtr(keys.get(0)));
                Region region = env.getRegion();
                Scan scan = MetaDataUtil.newTableRowsScan(keys.get(0), MIN_TABLE_TIMESTAMP, clientTimeStamp);
                List<Cell> results = Lists.newArrayList();
                try (RegionScanner scanner = region.getScanner(scan);) {
                  scanner.next(results);
                  if (results.isEmpty()) { // Should not be possible
                    return new MetaDataMutationResult(MutationCode.FUNCTION_NOT_FOUND, EnvironmentEdgeManager.currentTimeMillis(), null);
                  }
                  do {
                    Cell kv = results.get(0);
                    Delete delete = new Delete(kv.getRowArray(), kv.getRowOffset(), kv.getRowLength(), clientTimeStamp);
                    functionMetaData.add(delete);
                    results.clear();
                    scanner.next(results);
                  } while (!results.isEmpty());
                }
                return new MetaDataMutationResult(MutationCode.FUNCTION_ALREADY_EXISTS,
                        EnvironmentEdgeManager.currentTimeMillis(), functions, true);
            }
        }
        return new MetaDataMutationResult(MutationCode.FUNCTION_NOT_FOUND,
                EnvironmentEdgeManager.currentTimeMillis(), null);
    }

    @Override
    public void createSchema(RpcController controller, CreateSchemaRequest request,
            RpcCallback<MetaDataResponse> done) {
        MetaDataResponse.Builder builder = MetaDataResponse.newBuilder();
        String schemaName = null;
        try {
            List<Mutation> schemaMutations = ProtobufUtil.getMutations(request);
            schemaName = request.getSchemaName();
            Mutation m = MetaDataUtil.getPutOnlyTableHeaderRow(schemaMutations);

            byte[] lockKey = m.getRow();
            Region region = env.getRegion();
            MetaDataMutationResult result = checkSchemaKeyInRegion(lockKey, region);
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
                return;
            }
            List<RowLock> locks = Lists.newArrayList();
            long clientTimeStamp = MetaDataUtil.getClientTimeStamp(schemaMutations);
            try {
                acquireLock(region, lockKey, locks);
                // Get as of latest timestamp so we can detect if we have a newer schema that already exists without
                // making an additional query
                ImmutableBytesPtr cacheKey = new ImmutableBytesPtr(lockKey);
                PSchema schema = loadSchema(env, lockKey, cacheKey, clientTimeStamp, clientTimeStamp);
                if (schema != null) {
                    if (schema.getTimeStamp() < clientTimeStamp) {
                        if (!isSchemaDeleted(schema)) {
                            builder.setReturnCode(MetaDataProtos.MutationCode.SCHEMA_ALREADY_EXISTS);
                            builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                            builder.setSchema(PSchema.toProto(schema));
                            done.run(builder.build());
                            return;
                        }
                    } else {
                        builder.setReturnCode(MetaDataProtos.MutationCode.NEWER_SCHEMA_FOUND);
                        builder.setMutationTime(EnvironmentEdgeManager.currentTimeMillis());
                        builder.setSchema(PSchema.toProto(schema));
                        done.run(builder.build());
                        return;
                    }
                }
                region.mutateRowsWithLocks(schemaMutations, Collections.<byte[]> emptySet(), HConstants.NO_NONCE,
                        HConstants.NO_NONCE);

                // Invalidate the cache - the next getSchema call will add it
                Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env)
                        .getMetaDataCache();
                if (cacheKey != null) {
                    metaDataCache.invalidate(cacheKey);
                }

                // Get timeStamp from mutations - the above method sets it if
                // it's unset
                long currentTimeStamp = MetaDataUtil.getClientTimeStamp(schemaMutations);
                builder.setReturnCode(MetaDataProtos.MutationCode.SCHEMA_NOT_FOUND);
                builder.setMutationTime(currentTimeStamp);
                done.run(builder.build());
                return;
            } finally {
                region.releaseRowLocks(locks);
            }
        } catch (Throwable t) {
            logger.error("Creating the schema" + schemaName + "failed", t);
            ProtobufUtil.setControllerException(controller, ServerUtil.createIOException(schemaName, t));
        }
    }

    @Override
    public void dropSchema(RpcController controller, DropSchemaRequest request, RpcCallback<MetaDataResponse> done) {
        String schemaName = null;
        try {
            List<Mutation> schemaMetaData = ProtobufUtil.getMutations(request);
            schemaName = request.getSchemaName();
            byte[] lockKey = SchemaUtil.getSchemaKey(schemaName);
            Region region = env.getRegion();
            MetaDataMutationResult result = checkSchemaKeyInRegion(lockKey, region);
            if (result != null) {
                done.run(MetaDataMutationResult.toProto(result));
                return;
            }
            List<RowLock> locks = Lists.newArrayList();
            long clientTimeStamp = MetaDataUtil.getClientTimeStamp(schemaMetaData);
            try {
                acquireLock(region, lockKey, locks);
                List<ImmutableBytesPtr> invalidateList = new ArrayList<ImmutableBytesPtr>(1);
                result = doDropSchema(clientTimeStamp, schemaName, lockKey, schemaMetaData, invalidateList);
                if (result.getMutationCode() != MutationCode.SCHEMA_ALREADY_EXISTS) {
                    done.run(MetaDataMutationResult.toProto(result));
                    return;
                }
                region.mutateRowsWithLocks(schemaMetaData, Collections.<byte[]> emptySet(), HConstants.NO_NONCE,
                        HConstants.NO_NONCE);
                Cache<ImmutableBytesPtr, PMetaDataEntity> metaDataCache = GlobalCache.getInstance(this.env)
                        .getMetaDataCache();
                long currentTime = MetaDataUtil.getClientTimeStamp(schemaMetaData);
                for (ImmutableBytesPtr ptr : invalidateList) {
                    metaDataCache.invalidate(ptr);
                    metaDataCache.put(ptr, newDeletedSchemaMarker(currentTime));
                }
                done.run(MetaDataMutationResult.toProto(result));
                return;
            } finally {
                region.releaseRowLocks(locks);
            }
        } catch (Throwable t) {
            logger.error("drop schema failed:", t);
            ProtobufUtil.setControllerException(controller, ServerUtil.createIOException(schemaName, t));
        }
    }

    private MetaDataMutationResult doDropSchema(long clientTimeStamp, String schemaName, byte[] key,
            List<Mutation> schemaMutations, List<ImmutableBytesPtr> invalidateList) throws IOException, SQLException {
        PSchema schema = loadSchema(env, key, new ImmutableBytesPtr(key), clientTimeStamp, clientTimeStamp);
        boolean areTablesExists = false;
        if (schema == null) { return new MetaDataMutationResult(MutationCode.SCHEMA_NOT_FOUND,
                EnvironmentEdgeManager.currentTimeMillis(), null); }
        if (schema.getTimeStamp() < clientTimeStamp) {
            Region region = env.getRegion();
            Scan scan = MetaDataUtil.newTableRowsScan(SchemaUtil.getKeyForSchema(null, schemaName), MIN_TABLE_TIMESTAMP,
                    clientTimeStamp);
            List<Cell> results = Lists.newArrayList();
            try (RegionScanner scanner = region.getScanner(scan);) {
                scanner.next(results);
                if (results.isEmpty()) { // Should not be possible
                    return new MetaDataMutationResult(MutationCode.SCHEMA_NOT_FOUND,
                            EnvironmentEdgeManager.currentTimeMillis(), null);
                }
                do {
                    Cell kv = results.get(0);
                    if (Bytes.compareTo(kv.getRowArray(), kv.getRowOffset(), kv.getRowLength(), key, 0,
                            key.length) != 0) {
                        areTablesExists = true;
                        break;
                    }
                    results.clear();
                    scanner.next(results);
                } while (!results.isEmpty());
            }
            if (areTablesExists) { return new MetaDataMutationResult(MutationCode.TABLES_EXIST_ON_SCHEMA, schema,
                    EnvironmentEdgeManager.currentTimeMillis()); }
            invalidateList.add(new ImmutableBytesPtr(key));
            return new MetaDataMutationResult(MutationCode.SCHEMA_ALREADY_EXISTS, schema,
                    EnvironmentEdgeManager.currentTimeMillis());
        }
        return new MetaDataMutationResult(MutationCode.SCHEMA_NOT_FOUND, EnvironmentEdgeManager.currentTimeMillis(),
                null);

    }
}
