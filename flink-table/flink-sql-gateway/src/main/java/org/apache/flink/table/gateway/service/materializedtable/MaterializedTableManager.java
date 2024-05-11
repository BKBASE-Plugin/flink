/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.gateway.service.materializedtable;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.catalog.CatalogMaterializedTable;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.catalog.ResolvedCatalogBaseTable;
import org.apache.flink.table.catalog.ResolvedCatalogMaterializedTable;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.catalog.TableChange;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.gateway.api.operation.OperationHandle;
import org.apache.flink.table.gateway.api.results.ResultSet;
import org.apache.flink.table.gateway.service.operation.OperationExecutor;
import org.apache.flink.table.gateway.service.result.ResultFetcher;
import org.apache.flink.table.gateway.service.utils.SqlExecutionException;
import org.apache.flink.table.operations.materializedtable.AlterMaterializedTableChangeOperation;
import org.apache.flink.table.operations.materializedtable.AlterMaterializedTableRefreshOperation;
import org.apache.flink.table.operations.materializedtable.CreateMaterializedTableOperation;
import org.apache.flink.table.operations.materializedtable.DropMaterializedTableOperation;
import org.apache.flink.table.operations.materializedtable.MaterializedTableOperation;
import org.apache.flink.table.refresh.ContinuousRefreshHandler;
import org.apache.flink.table.refresh.ContinuousRefreshHandlerSerializer;
import org.apache.flink.table.types.logical.LogicalTypeFamily;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.flink.api.common.RuntimeExecutionMode.BATCH;
import static org.apache.flink.api.common.RuntimeExecutionMode.STREAMING;
import static org.apache.flink.configuration.DeploymentOptions.TARGET;
import static org.apache.flink.configuration.ExecutionOptions.RUNTIME_MODE;
import static org.apache.flink.configuration.PipelineOptions.NAME;
import static org.apache.flink.streaming.api.environment.ExecutionCheckpointingOptions.CHECKPOINTING_INTERVAL;
import static org.apache.flink.table.api.internal.TableResultInternal.TABLE_RESULT_OK;
import static org.apache.flink.table.catalog.CatalogBaseTable.TableKind.MATERIALIZED_TABLE;

/** Manager is responsible for execute the {@link MaterializedTableOperation}. */
@Internal
public class MaterializedTableManager {

    private static final Logger LOG = LoggerFactory.getLogger(MaterializedTableManager.class);

    public static ResultFetcher callMaterializedTableOperation(
            OperationExecutor operationExecutor,
            OperationHandle handle,
            MaterializedTableOperation op,
            String statement) {
        if (op instanceof CreateMaterializedTableOperation) {
            return callCreateMaterializedTableOperation(
                    operationExecutor, handle, (CreateMaterializedTableOperation) op);
        } else if (op instanceof AlterMaterializedTableRefreshOperation) {
            return callAlterMaterializedTableRefreshOperation(
                    operationExecutor, handle, (AlterMaterializedTableRefreshOperation) op);
        }

        throw new SqlExecutionException(
                String.format(
                        "Unsupported Operation %s for materialized table.", op.asSummaryString()));
    }

    private static ResultFetcher callCreateMaterializedTableOperation(
            OperationExecutor operationExecutor,
            OperationHandle handle,
            CreateMaterializedTableOperation createMaterializedTableOperation) {
        CatalogMaterializedTable materializedTable =
                createMaterializedTableOperation.getCatalogMaterializedTable();
        if (CatalogMaterializedTable.RefreshMode.CONTINUOUS == materializedTable.getRefreshMode()) {
            createMaterializedInContinuousMode(
                    operationExecutor, handle, createMaterializedTableOperation);
        } else {
            throw new SqlExecutionException(
                    "Only support create materialized table in continuous refresh mode currently.");
        }
        // Just return ok for unify different refresh job info of continuous and full mode, user
        // should get the refresh job info via desc table.
        return ResultFetcher.fromTableResult(handle, TABLE_RESULT_OK, false);
    }

    private static void createMaterializedInContinuousMode(
            OperationExecutor operationExecutor,
            OperationHandle handle,
            CreateMaterializedTableOperation createMaterializedTableOperation) {
        // create materialized table first
        operationExecutor.callExecutableOperation(handle, createMaterializedTableOperation);

        ObjectIdentifier materializedTableIdentifier =
                createMaterializedTableOperation.getTableIdentifier();
        CatalogMaterializedTable catalogMaterializedTable =
                createMaterializedTableOperation.getCatalogMaterializedTable();

        // Set job name, runtime mode, checkpoint interval
        // TODO: Set minibatch related optimization options.
        Configuration customConfig = new Configuration();
        String jobName =
                String.format(
                        "Materialized_table_%s_continuous_refresh_job",
                        materializedTableIdentifier.asSerializableString());
        customConfig.set(NAME, jobName);
        customConfig.set(RUNTIME_MODE, STREAMING);
        customConfig.set(CHECKPOINTING_INTERVAL, catalogMaterializedTable.getFreshness());

        String insertStatement =
                String.format(
                        "INSERT INTO %s %s",
                        materializedTableIdentifier, catalogMaterializedTable.getDefinitionQuery());
        try {
            // submit flink streaming job
            ResultFetcher resultFetcher =
                    operationExecutor.executeStatement(handle, customConfig, insertStatement);

            // get execution.target and jobId, currently doesn't support yarn and k8s, so doesn't
            // get clusterId
            List<RowData> results = fetchAllResults(resultFetcher);
            String jobId = results.get(0).getString(0).toString();
            String executeTarget =
                    operationExecutor.getSessionContext().getSessionConf().get(TARGET);
            ContinuousRefreshHandler continuousRefreshHandler =
                    new ContinuousRefreshHandler(executeTarget, jobId);
            byte[] serializedBytes =
                    ContinuousRefreshHandlerSerializer.INSTANCE.serialize(continuousRefreshHandler);

            // update RefreshHandler to Catalog
            CatalogMaterializedTable updatedMaterializedTable =
                    catalogMaterializedTable.copy(
                            CatalogMaterializedTable.RefreshStatus.ACTIVATED,
                            continuousRefreshHandler.asSummaryString(),
                            serializedBytes);
            List<TableChange> tableChanges = new ArrayList<>();
            tableChanges.add(
                    TableChange.modifyRefreshStatus(
                            CatalogMaterializedTable.RefreshStatus.ACTIVATED));
            tableChanges.add(
                    TableChange.modifyRefreshHandler(
                            continuousRefreshHandler.asSummaryString(), serializedBytes));

            AlterMaterializedTableChangeOperation alterMaterializedTableChangeOperation =
                    new AlterMaterializedTableChangeOperation(
                            materializedTableIdentifier, tableChanges, updatedMaterializedTable);
            operationExecutor.callExecutableOperation(
                    handle, alterMaterializedTableChangeOperation);
        } catch (Exception e) {
            // drop materialized table while submit flink streaming job occur exception. Thus, weak
            // atomicity is guaranteed
            operationExecutor.callExecutableOperation(
                    handle,
                    new DropMaterializedTableOperation(materializedTableIdentifier, true, false));
            // log and throw exception
            LOG.error(
                    "Submit continuous refresh job for materialized table {} occur exception.",
                    materializedTableIdentifier,
                    e);
            throw new SqlExecutionException(
                    String.format(
                            "Submit continuous refresh job for materialized table %s occur exception.",
                            materializedTableIdentifier),
                    e);
        }
    }

    private static ResultFetcher callAlterMaterializedTableRefreshOperation(
            OperationExecutor operationExecutor,
            OperationHandle handle,
            AlterMaterializedTableRefreshOperation alterMaterializedTableRefreshOperation) {
        ObjectIdentifier materializedTableIdentifier =
                alterMaterializedTableRefreshOperation.getTableIdentifier();
        ResolvedCatalogBaseTable<?> table = operationExecutor.getTable(materializedTableIdentifier);
        if (MATERIALIZED_TABLE != table.getTableKind()) {
            throw new ValidationException(
                    String.format(
                            "The table '%s' is not a materialized table.",
                            materializedTableIdentifier));
        }

        ResolvedCatalogMaterializedTable materializedTable =
                (ResolvedCatalogMaterializedTable) table;

        Map<String, String> partitionSpec =
                alterMaterializedTableRefreshOperation.getPartitionSpec();

        validatePartitionSpec(partitionSpec, (ResolvedCatalogMaterializedTable) table);

        // Set job name, runtime mode
        Configuration customConfig = new Configuration();
        String jobName =
                String.format(
                        "Materialized_table_%s_one_time_refresh_job",
                        materializedTableIdentifier.asSerializableString());
        customConfig.set(NAME, jobName);
        customConfig.set(RUNTIME_MODE, BATCH);

        String insertStatement =
                getManuallyRefreshStatement(
                        materializedTableIdentifier.toString(),
                        materializedTable.getDefinitionQuery(),
                        partitionSpec);

        try {
            LOG.debug(
                    "Begin to manually refreshing the materialization table {}, statement: {}",
                    materializedTableIdentifier,
                    insertStatement);
            return operationExecutor.executeStatement(
                    handle, customConfig, insertStatement.toString());
        } catch (Exception e) {
            // log and throw exception
            LOG.error(
                    "Manually refreshing the materialization table {} occur exception.",
                    materializedTableIdentifier,
                    e);
            throw new SqlExecutionException(
                    String.format(
                            "Manually refreshing the materialization table %s occur exception.",
                            materializedTableIdentifier),
                    e);
        }
    }

    private static void validatePartitionSpec(
            Map<String, String> partitionSpec, ResolvedCatalogMaterializedTable table) {
        ResolvedSchema schema = table.getResolvedSchema();
        Set<String> allPartitionKeys = new HashSet<>(table.getPartitionKeys());

        Set<String> unknownPartitionKeys = new HashSet<>();
        Set<String> nonStringPartitionKeys = new HashSet<>();

        for (String partitionKey : partitionSpec.keySet()) {
            if (!schema.getColumn(partitionKey).isPresent()) {
                unknownPartitionKeys.add(partitionKey);
                continue;
            }

            if (!schema.getColumn(partitionKey)
                    .get()
                    .getDataType()
                    .getLogicalType()
                    .getTypeRoot()
                    .getFamilies()
                    .contains(LogicalTypeFamily.CHARACTER_STRING)) {
                nonStringPartitionKeys.add(partitionKey);
            }
        }

        if (!unknownPartitionKeys.isEmpty()) {
            throw new ValidationException(
                    String.format(
                            "The partition spec contains unknown partition keys:\n\n%s\n\nAll known partition keys are:\n\n%s",
                            String.join("\n", unknownPartitionKeys),
                            String.join("\n", allPartitionKeys)));
        }

        if (!nonStringPartitionKeys.isEmpty()) {
            throw new ValidationException(
                    String.format(
                            "Currently, manually refreshing materialized table only supports specifying char and string type"
                                    + " partition keys. All specific partition keys with unsupported types are:\n\n%s",
                            String.join("\n", nonStringPartitionKeys)));
        }
    }

    @VisibleForTesting
    protected static String getManuallyRefreshStatement(
            String tableIdentifier, String query, Map<String, String> partitionSpec) {
        StringBuilder insertStatement =
                new StringBuilder(
                        String.format(
                                "INSERT OVERWRITE %s\n  SELECT * FROM (%s)",
                                tableIdentifier, query));
        if (!partitionSpec.isEmpty()) {
            insertStatement.append("\n  WHERE ");
            insertStatement.append(
                    partitionSpec.entrySet().stream()
                            .map(
                                    entry ->
                                            String.format(
                                                    "%s = '%s'", entry.getKey(), entry.getValue()))
                            .reduce((s1, s2) -> s1 + " AND " + s2)
                            .get());
        }

        return insertStatement.toString();
    }

    private static List<RowData> fetchAllResults(ResultFetcher resultFetcher) {
        Long token = 0L;
        List<RowData> results = new ArrayList<>();
        while (token != null) {
            ResultSet result = resultFetcher.fetchResults(token, Integer.MAX_VALUE);
            results.addAll(result.getData());
            token = result.getNextToken();
        }
        return results;
    }
}
