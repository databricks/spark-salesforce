package com.springml.spark.salesforce

import java.text.SimpleDateFormat

import com.springml.salesforce.wave.api.BulkAPI
import org.apache.log4j.Logger
import org.apache.spark.sql.{DataFrame, Dataset, Row, SQLContext}
import org.apache.spark.sql.sources.{BaseRelation, TableScan}
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import com.springml.salesforce.wave.model.{BatchInfo, BatchInfoList, JobInfo}
import org.apache.http.Header
import org.apache.spark.rdd.RDD

import scala.collection.JavaConverters._

/**
  * Relation class for reading data from Salesforce and construct RDD
  */
case class BulkRelation(
    query: String,
    sfObject: String,
    bulkAPI: BulkAPI,
    contentType: String,
    customHeaders: List[Header],
    userSchema: StructType,
    sqlContext: SQLContext,
    encodeFields: Option[String],
    inferSchema: Boolean,
    replaceDatasetNameWithId: Boolean,
    sdf: SimpleDateFormat) extends BaseRelation with TableScan {

  private val logger = Logger.getLogger(classOf[BulkRelation])

  def schema = records.schema
  def buildScan() = records.rdd

  lazy val records: DataFrame = {
    val inputJobInfo = new JobInfo(contentType, sfObject, "query")
    val jobInfo = bulkAPI.createJob(inputJobInfo, customHeaders)
    val jobId = jobInfo.getId

    val batchInfo = bulkAPI.addBatch(jobId, query)

    if (awaitJobCompleted(jobId)) {
      val batchInfoList = bulkAPI.getBatchInfoList(jobId)
      val batchInfos = batchInfoList.getBatchInfo().asScala.toList
      val completedBatchInfos = batchInfos.filter(batchInfo => batchInfo.getState().equals("Completed"))

      val csvData = sqlContext.sparkContext.parallelize(
        completedBatchInfos.flatMap(batchInfo => {
          val resultIds = bulkAPI.getBatchResultIds(jobId, batchInfo.getId)

          val result = bulkAPI.getBatchResult(jobId, batchInfo.getId, resultIds.get(resultIds.size() - 1))

          result.stripMargin.lines.toList
        })
      ).toDS()

      sqlContext.sparkSession.read.option("header", true).option("inferSchema",true).csv(csvData)
    } else {
      throw new Exception("Job completion timeout")
    }
  }

  private def awaitJobCompleted(jobId: String): Boolean = {
    var i = 1
    // Maximum wait time is 10 mins for a job
    while (i < 3000) {
      if (bulkAPI.isCompleted(jobId)) {
        logger.info("Job completed")
        return true
      }

      logger.info("Job not completed, waiting...")
      Thread.sleep(200)
      i = i + 1
    }

    return false
  }

  override def schema: StructType = {
    if (userSchema != null) {
      userSchema
    } else if (records == null || records.size() == 0) {
      new StructType()
    } else if (inferSchema) {
      InferSchema(sampleRDD, header, sdf)
    } else {
      val schemaHeader = header
      val structFields = new Array[StructField](schemaHeader.length)
      var index: Int = 0
      logger.debug("header size " + schemaHeader.length)
      for (fieldEntry <- schemaHeader) {
        logger.debug("header (" + index + ") = " + fieldEntry)
        structFields(index) = StructField(fieldEntry, StringType, nullable = true)
        index = index + 1
      }

      StructType(structFields)
    }
  }

  override def buildScan(): RDD[Row] = {
    val schemaFields = schema.fields
    logger.info("Total records size : " + records.size())
    val rowArray = new Array[Row](records.size())
    var rowIndex: Int = 0
    for (row <- records) {
      val fieldArray = new Array[Any](schemaFields.length)
      logger.debug("Total Fields length : " + schemaFields.length)
      var fieldIndex: Int = 0
      for (fields <- schemaFields) {
        val value = fieldValue(row, fields.name)
        logger.debug("fieldValue " + value)
        fieldArray(fieldIndex) = cast(value, fields.dataType, fields.nullable, fields.name)
        fieldIndex = fieldIndex + 1
      }

      logger.debug("rowIndex : " + rowIndex)
      rowArray(rowIndex) = Row.fromSeq(fieldArray)
      rowIndex = rowIndex + 1
    }
    sqlContext.sparkContext.parallelize(rowArray)
  }
}