/*
*  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.wso2.extension.siddhi.store.hbase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.wso2.extension.siddhi.store.hbase.condition.BasicCompareOperation;
import org.wso2.extension.siddhi.store.hbase.condition.HBaseCompiledCondition;
import org.wso2.extension.siddhi.store.hbase.condition.HBaseExpressionVisitor;
import org.wso2.extension.siddhi.store.hbase.exception.HBaseTableException;
import org.wso2.extension.siddhi.store.hbase.iterator.HBaseScanIterator;
import org.wso2.extension.siddhi.store.hbase.util.HBaseTableUtils;
import org.wso2.siddhi.core.exception.ConnectionUnavailableException;
import org.wso2.siddhi.core.exception.OperationNotSupportedException;
import org.wso2.siddhi.core.table.record.AbstractRecordTable;
import org.wso2.siddhi.core.table.record.ExpressionBuilder;
import org.wso2.siddhi.core.table.record.RecordIterator;
import org.wso2.siddhi.core.util.SiddhiConstants;
import org.wso2.siddhi.core.util.collection.operator.CompiledCondition;
import org.wso2.siddhi.core.util.collection.operator.CompiledExpression;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.query.api.annotation.Annotation;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.TableDefinition;
import org.wso2.siddhi.query.api.util.AnnotationHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.wso2.extension.siddhi.store.hbase.util.HBaseEventTableConstants.ANNOTATION_ELEMENT_CF_NAME;
import static org.wso2.extension.siddhi.store.hbase.util.HBaseEventTableConstants.ANNOTATION_ELEMENT_TABLE_NAME;
import static org.wso2.extension.siddhi.store.hbase.util.HBaseEventTableConstants.DEFAULT_CF_NAME;
import static org.wso2.siddhi.core.util.SiddhiConstants.ANNOTATION_STORE;

public class HBaseEventTable extends AbstractRecordTable {

    private static final Log log = LogFactory.getLog(HBaseEventTable.class);

    private Connection connection;
    private List<Attribute> schema;
    private List<Attribute> primaryKeys;
    private List<Integer> primaryKeyOrdinals;
    private Annotation storeAnnotation;
    private String tableName;
    private String columnFamily;
    private boolean noKeys;

    @Override
    protected void init(TableDefinition tableDefinition, ConfigReader configReader) {
        this.schema = tableDefinition.getAttributeList();
        this.storeAnnotation = AnnotationHelper.getAnnotation(ANNOTATION_STORE, tableDefinition.getAnnotations());
        Annotation primaryKeyAnnotation = AnnotationHelper.getAnnotation(SiddhiConstants.ANNOTATION_PRIMARY_KEY,
                tableDefinition.getAnnotations());
        String tableName = storeAnnotation.getElement(ANNOTATION_ELEMENT_TABLE_NAME);
        String cfName = storeAnnotation.getElement(ANNOTATION_ELEMENT_CF_NAME);
        this.tableName = HBaseTableUtils.isEmpty(tableName) ? tableDefinition.getId() : tableName;
        this.columnFamily = HBaseTableUtils.isEmpty(cfName) ? DEFAULT_CF_NAME : cfName;

        if (primaryKeyAnnotation == null) {
            this.noKeys = true;
            this.primaryKeyOrdinals = new ArrayList<>();
            this.primaryKeys = new ArrayList<>();
        } else {
            this.primaryKeyOrdinals = HBaseTableUtils.inferPrimaryKeyOrdinals(schema, primaryKeyAnnotation);
            this.primaryKeys = schema.stream().filter(elem -> this.primaryKeyOrdinals.contains(schema.indexOf(elem)))
                    .collect(Collectors.toList());
        }
    }

    @Override
    protected void add(List<Object[]> records) throws ConnectionUnavailableException {
        if (this.noKeys) {
            this.putRecords(records);
        } else {
            records.forEach(this::checkAndPutRecord);
        }
    }

    @Override
    protected RecordIterator<Object[]> find(Map<String, Object> findConditionParameterMap,
                                            CompiledCondition compiledCondition)
            throws ConnectionUnavailableException {
        boolean allKeysEquals = ((HBaseCompiledCondition) compiledCondition).isAllKeyEquals();
        if (!noKeys && allKeysEquals) {
            return this.readSingleRecord(findConditionParameterMap, compiledCondition);
        } else {
            return new HBaseScanIterator(findConditionParameterMap, this.tableName, this.columnFamily,
                    (HBaseCompiledCondition) compiledCondition, this.connection, this.schema);
        }
    }

    @Override
    protected boolean contains(Map<String, Object> containsConditionParameterMap, CompiledCondition compiledCondition)
            throws ConnectionUnavailableException {
        boolean allKeysEquals = ((HBaseCompiledCondition) compiledCondition).isAllKeyEquals();
        if (!noKeys && allKeysEquals) {
            return this.readSingleRecord(containsConditionParameterMap, compiledCondition).hasNext();
        } else {
            return new HBaseScanIterator(containsConditionParameterMap, this.tableName, this.columnFamily,
                    (HBaseCompiledCondition) compiledCondition, this.connection, this.schema).hasNext();
        }
    }

    @Override
    protected void delete(List<Map<String, Object>> deleteConditionParameterMaps,
                          CompiledCondition compiledCondition)
            throws ConnectionUnavailableException {
        if (noKeys) {
            throw new OperationNotSupportedException("The HBase Table extension requires the specification of " +
                    "Primary Keys for delete operations. Please check your query and try again");
        } else if (!((HBaseCompiledCondition) compiledCondition).isReadOnlyCondition()) {
            throw new OperationNotSupportedException("The HBase Table extension supports comparison operations for " +
                    "record read operations only. Please check your query and try again.");
        } else if (!((HBaseCompiledCondition) compiledCondition).isAllKeyEquals()) {
            throw new OperationNotSupportedException("The HBase Table extension requires that DELETE " +
                    "operations have all primary key entries to be present in the query in EQUALS form. " +
                    "Please check your query and try again");
        } else {
            List<Delete> deletes = HBaseTableUtils.getKeysForParameters(deleteConditionParameterMaps, primaryKeys)
                    .stream().map(Bytes::toBytes).map(Delete::new).collect(Collectors.toList());
            try (Table table = this.connection.getTable(TableName.valueOf(this.tableName))) {
                table.delete(deletes);
            } catch (IOException e) {
                throw new HBaseTableException("Error while performing delete operations on table '"
                        + this.tableName + "': " + e.getMessage(), e);
            }
        }
    }

    @Override
    protected void update(CompiledCondition compiledCondition, List<Map<String, Object>> updateConditionParameterMaps,
                          Map<String, CompiledExpression> updateSetExpressions,
                          List<Map<String, Object>> updateValues)
            throws ConnectionUnavailableException {
        this.checkAndUpdateRecords(compiledCondition, updateConditionParameterMaps, updateValues);
    }

    @Override
    protected void updateOrAdd(CompiledCondition compiledCondition,
                               List<Map<String, Object>> updateConditionParameterMaps,
                               Map<String, CompiledExpression> updateSetExpressions,
                               List<Map<String, Object>> updateSetParameterMaps, List<Object[]> addingRecords)
            throws ConnectionUnavailableException {
        if (((HBaseCompiledCondition) compiledCondition).isAllKeyEquals()) {
            this.putRecords(addingRecords);
        } else if (!((HBaseCompiledCondition) compiledCondition).isReadOnlyCondition()) {
            throw new OperationNotSupportedException("The HBase Table extension supports comparison operations for " +
                    "record read operations only. Please check your query and try again.");
        } else {
            throw new OperationNotSupportedException("The HBase Table extension requires that UPDATE OR INSERT " +
                    "operations have all primary key entries to be present in the query in EQUALS form. " +
                    "Please check your query and try again");
        }
    }

    @Override
    protected CompiledCondition compileCondition(ExpressionBuilder expressionBuilder) {
        HBaseExpressionVisitor visitor = new HBaseExpressionVisitor(this.primaryKeys);
        expressionBuilder.build(visitor);
        return new HBaseCompiledCondition(visitor.getConditions(), visitor.isReadOnlyCondition(),
                visitor.isAllKeyEquals());
    }

    @Override
    protected CompiledExpression compileSetAttribute(ExpressionBuilder expressionBuilder) {
        throw new OperationNotSupportedException("SET operations are not supported by the HBase Table extension. " +
                "Please check your query and try again");
    }

    @Override
    protected void connect() throws ConnectionUnavailableException {
        Configuration config = HBaseConfiguration.create();
        storeAnnotation.getElements().stream()
                .filter(Objects::nonNull)
                .filter(element -> !(element.getKey().equals(ANNOTATION_ELEMENT_TABLE_NAME)
                        || element.getKey().equals(ANNOTATION_ELEMENT_CF_NAME)))
                .filter(element -> !(HBaseTableUtils.isEmpty(element.getKey())
                        || HBaseTableUtils.isEmpty(element.getValue())))
                .forEach(element -> config.set(element.getKey(), element.getValue()));
        try {
            this.connection = ConnectionFactory.createConnection(config);
        } catch (IOException e) {
            throw new ConnectionUnavailableException("Failed to initialize store for table name '" +
                    this.tableName + "': " + e.getMessage(), e);
        }
        this.checkAndCreateTable();
    }

    @Override
    protected void disconnect() {
        if (this.connection != null && !this.connection.isClosed()) {
            HBaseTableUtils.closeQuietly(this.connection);
        }
    }

    @Override
    protected void destroy() {
        this.disconnect();
        if (log.isDebugEnabled()) {
            log.debug("Destroyed HBase connection for store table " + this.tableName);
        }
    }

    /**
     * This method will check the HBase instance whether the table specified by the particular Table instance,
     * and will create it if it doesn't.
     */
    private void checkAndCreateTable() {
        TableName table = TableName.valueOf(this.tableName);
        HTableDescriptor descriptor = new HTableDescriptor(table).addFamily(
                new HColumnDescriptor(this.columnFamily).setMaxVersions(1));
        Admin admin = null;
        try {
            admin = this.connection.getAdmin();
            if (admin.tableExists(table)) {
                log.debug("Table " + tableName + " already exists.");
                return;
            }
            admin.createTable(descriptor);
            log.debug("Table " + tableName + " created.");
        } catch (IOException e) {
            throw new HBaseTableException("Error creating table " + tableName + " : " + e.getMessage(), e);
        } finally {
            HBaseTableUtils.closeQuietly(admin);
        }
    }

    /**
     * Method which will perform an insertion operation for a given record, without updating the record's values
     * if it already exists.
     * Note that this method has to do an RPC call per record due to HBase API limitations. Hence, it is not
     * recommended for high throughput operations.
     *
     * @param record the record to be inserted into the HBase cluster.
     */
    private void checkAndPutRecord(Object[] record) {
        String rowID = HBaseTableUtils.generatePrimaryKeyValue(record, this.schema, this.primaryKeyOrdinals);
        Put put = new Put(Bytes.toBytes(rowID));
        byte[] firstColumn = Bytes.toBytes(this.schema.get(0).getName());
        for (int i = 0; i < this.schema.size(); i++) {
            Attribute column = this.schema.get(i);
            //method: CF, qualifier, value.
            put.addColumn(Bytes.toBytes(this.columnFamily), Bytes.toBytes(column.getName()),
                    HBaseTableUtils.encodeCell(column.getType(), record[i], rowID));
        }
        try (Table table = this.connection.getTable(TableName.valueOf(this.tableName))) {
            //method: rowID, CF, qualifier, value, Put.
            table.checkAndPut(Bytes.toBytes(rowID), Bytes.toBytes(this.columnFamily), firstColumn, null, put);
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("Error while performing insert operation on table '" + this.tableName + "' on row '"
                        + rowID + "' :" + e.getMessage());
            }
            throw new HBaseTableException("Error while performing insert operation on table '" + this.tableName + "': "
                    + e.getMessage(), e);
        }
    }

    private void putRecords(List<Object[]> records) {
        List<Put> puts = records.stream().map(record -> {
            String rowID = HBaseTableUtils.generatePrimaryKeyValue(record, this.schema, this.primaryKeyOrdinals);
            Put put = new Put(Bytes.toBytes(rowID));
            for (int i = 0; i < this.schema.size(); i++) {
                Attribute column = this.schema.get(i);
                //method: CF, qualifier, value.
                put.addColumn(Bytes.toBytes(this.columnFamily), Bytes.toBytes(column.getName()),
                        HBaseTableUtils.encodeCell(column.getType(), record[i], rowID));
            }
            return put;
        }).collect(Collectors.toList());
        try (Table table = this.connection.getTable(TableName.valueOf(this.tableName))) {
            table.put(puts);
        } catch (IOException e) {
            throw new HBaseTableException("Error while performing insert/update operation on table '" +
                    this.tableName + "': " + e.getMessage(), e);
        }
    }

    private void checkAndUpdateRecords(CompiledCondition compiledCondition,
                                       List<Map<String, Object>> updateConditionParameterMaps,
                                       List<Map<String, Object>> updateValues) {
        List<Put> puts = new ArrayList<>();
        if (((HBaseCompiledCondition) compiledCondition).isAllKeyEquals()) {
            while (updateConditionParameterMaps.iterator().hasNext() && updateValues.iterator().hasNext()) {
                Map<String, Object> conditionParameterMap = updateConditionParameterMaps.iterator().next();
                Map<String, Object> updateColumnValues = updateValues.iterator().next();
                String rowKey = HBaseTableUtils.inferKeyFromCondition(conditionParameterMap, this.primaryKeys);
                if (this.checkSingleRecord(conditionParameterMap)) {
                    Put put = new Put(Bytes.toBytes(rowKey));
                    for (int i = 0; i < this.schema.size(); i++) {
                        Attribute attribute = this.schema.get(i);
                        if (updateColumnValues.containsKey(attribute.getName())) {
                            // the incoming update data contains the column's new value.
                            if (this.primaryKeyOrdinals.contains(i)) {
                                /*let the user know it's not possible to change values defined as primary keys since
                                it could mean changing the row ID.*/
                                throw new OperationNotSupportedException("The HBase Table extension does not support" +
                                        " updating of a record's primary key. Please check your query and try again");
                            } else {
                                put.addColumn(Bytes.toBytes(this.columnFamily), Bytes.toBytes(attribute.getName()),
                                        HBaseTableUtils.encodeCell(attribute.getType(),
                                                updateColumnValues.get(attribute.getName()), rowKey));
                            }
                        }
                    }
                    puts.add(put);
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("a record with key '" + rowKey + "' fulfilling the criteria does not exist on " +
                                "table " + this.tableName + ", hence returning without changes");
                    }
                    return;
                }
            }
        } else {
            throw new OperationNotSupportedException("The HBase Table extension requires that UPDATE " +
                    "operations have all primary key entries to be present in the query in EQUALS form. " +
                    "Please check your query and try again");
        }
        try (Table table = this.connection.getTable(TableName.valueOf(this.tableName))) {
            table.put(puts);
        } catch (IOException e) {
            throw new HBaseTableException("Error while performing insert/update operation on table '" +
                    this.tableName + "': " + e.getMessage(), e);
        }
    }

    private RecordIterator<Object[]> readSingleRecord(Map<String, Object> conditionParameterMap,
                                                      CompiledCondition compiledCondition) {
        Table table;
        List<Object[]> records = new ArrayList<>();
        List<BasicCompareOperation> operations = ((HBaseCompiledCondition) compiledCondition).getOperations();
        String rowID = HBaseTableUtils.inferKeyFromCondition(conditionParameterMap, this.primaryKeys);
        Get get = new Get(Bytes.toBytes(rowID));
        operations.forEach(operation -> get.setFilter(HBaseTableUtils.initializeFilter(operation, conditionParameterMap, this.columnFamily)));
        try {
            table = this.connection.getTable(TableName.valueOf(this.tableName));
            Result result = table.get(get);
            records.add(HBaseTableUtils.constructRecord(rowID, this.columnFamily, result, this.schema));
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("Error while performing read operation on table '" + this.tableName + "' on row '"
                        + rowID + "' :" + e.getMessage());
            }
            throw new HBaseTableException("Error while performing read operation on table '" + this.tableName + "': "
                    + e.getMessage(), e);
        }
        return new HBaseGetIterator(records.iterator(), table);
    }

    private boolean checkSingleRecord(Map<String, Object> conditionParameterMap) {
        Get get = new Get(Bytes.toBytes(HBaseTableUtils.inferKeyFromCondition(conditionParameterMap,
                this.primaryKeys)));
        try (Table table = this.connection.getTable(TableName.valueOf(this.tableName))) {
            return table.exists(get);
        } catch (IOException e) {
            throw new HBaseTableException("Error while performing check operation on table '" + this.tableName + "': "
                    + e.getMessage(), e);
        }
    }

    private static class HBaseGetIterator implements RecordIterator<Object[]> {

        private Iterator<Object[]> internalIterator;
        Table table;

        HBaseGetIterator(Iterator<Object[]> iterator, Table table) {
            this.internalIterator = iterator;
            this.table = table;
        }

        @Override
        public void close() throws IOException {
            HBaseTableUtils.closeQuietly(table);
        }

        @Override
        public boolean hasNext() {
            return this.internalIterator.hasNext();
        }

        @Override
        public Object[] next() {
            return this.internalIterator.next();
        }

        @Override
        public void remove() {
            //Do nothing
        }
    }

}