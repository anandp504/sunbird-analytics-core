package org.ekstep.analytics.framework.util

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.ekstep.analytics.framework.dispatcher.S3Dispatcher
import org.ekstep.analytics.framework.StorageConfig
import org.ekstep.analytics.framework.exception.DispatcherException
import org.apache.spark.sql.functions.col
import java.nio.file.Paths

class DatasetExt(df: Dataset[Row]) {

  private val fileUtil = new HadoopFileUtil();
  
  private def getTempDir(filePrefix: String, reportId: String): String = {
    Paths.get(filePrefix, reportId, "/_tmp/").toString()
  }
  
  private def getFinalDir(filePrefix: String, reportId: String): String = {
    Paths.get(filePrefix, reportId).toString();
  }
  
  private def filePaths(dims: Seq[String], row: Row, format: String, tempDir: String, finalDir: String): (String, String) = {
    
    val dimPaths = for(dim <- dims) yield {
      dim + "=" + row.get(row.fieldIndex(dim))
    }
    
    val paths = for(dim <- dims) yield {
      row.get(row.fieldIndex(dim))
    }
    
    (Paths.get(tempDir, dimPaths.mkString("/")).toString(), Paths.get(finalDir, paths.mkString("/")) + "." + format)
  }

  def saveToBlobStore(storageConfig: StorageConfig, format: String, reportId: String, options: Option[Map[String, String]], partitioningColumns: Option[Seq[String]]) = {

    val conf = df.sparkSession.sparkContext.hadoopConfiguration;

    val file = storageConfig.store.toLowerCase() match {
      case "s3" =>
        CommonUtil.getS3File(storageConfig.container, storageConfig.fileName);
      case "azure" =>
        CommonUtil.getAzureFileWithoutPrefix(storageConfig.container, storageConfig.fileName, storageConfig.accountKey.getOrElse("azure_storage_key"))
      case _ =>
        storageConfig.fileName
    }
    
    val filePrefix = storageConfig.store.toLowerCase() match {
      case "s3" =>
        "s3n://"
      case "azure" =>
        "wasb://"
      case _ =>
        ""
    }
    
    val tempDir = getTempDir(file, reportId);
    val finalDir = getFinalDir(file, reportId);

    val dims = partitioningColumns.getOrElse(Seq());

    fileUtil.delete(conf, filePrefix + tempDir)
    
    if(dims.nonEmpty) {
      val map = df.select(dims.map(f => col(f)):_*).distinct().collect().map(f => filePaths(dims, f, format, tempDir, finalDir)).toMap
      df.write.format(format).options(options.getOrElse(Map())).partitionBy(dims: _*).save(filePrefix + tempDir);
      map.foreach(f => {
        fileUtil.delete(conf, filePrefix + f._2)
        fileUtil.copyMerge(filePrefix + f._1, filePrefix + f._2, conf, true);
      })
    } else {
      df.write.format(format).options(options.getOrElse(Map())).save(filePrefix + tempDir);
      fileUtil.delete(conf, filePrefix + finalDir + "." + format)
      fileUtil.copyMerge(filePrefix + tempDir, filePrefix + finalDir + "." + format, conf, true);  
    }
    fileUtil.delete(conf, filePrefix + tempDir)
    
  }
  
}

object DatasetUtil {
  implicit def extensions(df: Dataset[Row]) = new DatasetExt(df);
  
}