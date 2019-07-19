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

package org.apache.spark.sql.execution.datasources.v2

import scala.collection.JavaConverters._

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalog.v2.{Identifier, StagingTableCatalog, TableCatalog}
import org.apache.spark.sql.catalog.v2.expressions.Transform
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.CannotReplaceMissingTableException
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.execution.LeafExecNode
import org.apache.spark.sql.sources.v2.StagedTable
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.Utils

case class ReplaceTableExec(
    catalog: TableCatalog,
    identifier: Identifier,
    tableSchema: StructType,
    partitioning: Seq[Transform],
    tableProperties: Map[String, String],
    orCreate: Boolean) extends LeafExecNode {

  override protected def doExecute(): RDD[InternalRow] = {
    if (catalog.tableExists(identifier)) {
      catalog.dropTable(identifier)
    } else if (!orCreate) {
      throw new CannotReplaceMissingTableException(identifier)
    }
    catalog.createTable(identifier, tableSchema, partitioning.toArray, tableProperties.asJava)
    sqlContext.sparkContext.parallelize(Seq.empty, 1)
  }

  override def output: Seq[Attribute] = Seq.empty
}

case class AtomicReplaceTableExec(
    catalog: StagingTableCatalog,
    identifier: Identifier,
    tableSchema: StructType,
    partitioning: Seq[Transform],
    tableProperties: Map[String, String],
    orCreate: Boolean) extends LeafExecNode {

  override protected def doExecute(): RDD[InternalRow] = {
    val staged = if (catalog.tableExists(identifier)) {
      catalog.stageReplace(
        identifier,
        tableSchema,
        partitioning.toArray,
        tableProperties.asJava)
    } else if (orCreate) {
      catalog.stageCreate(
        identifier,
        tableSchema,
        partitioning.toArray,
        tableProperties.asJava)
    } else {
      throw new CannotReplaceMissingTableException(identifier)
    }
    commitOrAbortStagedChanges(staged)

    sqlContext.sparkContext.parallelize(Seq.empty, 1)
  }

  override def output: Seq[Attribute] = Seq.empty

  private def commitOrAbortStagedChanges(staged: StagedTable): Unit = {
    Utils.tryWithSafeFinallyAndFailureCallbacks({
      staged.commitStagedChanges()
    })(catchBlock = {
      staged.abortStagedChanges()
    })
  }
}