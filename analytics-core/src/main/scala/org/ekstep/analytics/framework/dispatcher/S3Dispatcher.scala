package org.ekstep.analytics.framework.dispatcher

import org.ekstep.analytics.framework.exception.DispatcherException
import java.io.FileWriter
import org.ekstep.analytics.framework.util.CommonUtil
import org.ekstep.analytics.framework.util.JobLogger
import org.ekstep.analytics.framework.Level._
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.sunbird.cloud.storage.factory.StorageServiceFactory
import org.sunbird.cloud.storage.factory.StorageConfig
import org.sunbird.cloud.storage.conf.AppConf
import org.ekstep.analytics.framework.FrameworkContext

/**
 * @author Santhosh
 */
object S3Dispatcher extends IDispatcher {

    implicit val className = "org.ekstep.analytics.framework.dispatcher.S3Dispatcher"

    @throws(classOf[DispatcherException])
    def dispatch(events: Array[String], config: Map[String, AnyRef])(implicit fc: FrameworkContext): Array[String] = {
        var filePath = config.getOrElse("filePath", null).asInstanceOf[String];
        val bucket = config.getOrElse("bucket", null).asInstanceOf[String];
        val key = config.getOrElse("key", null).asInstanceOf[String];
        val zip = config.getOrElse("zip", false).asInstanceOf[Boolean];
        val isPublic = config.getOrElse("public", false).asInstanceOf[Boolean];

        if (null == bucket || null == key) {
            throw new DispatcherException("'bucket' & 'key' parameters are required to send output to S3")
        }
        var deleteFile = false;
        if (null == filePath) {
            filePath = AppConf.getConfig("spark_output_temp_dir") + "output-" + System.currentTimeMillis() + ".log";
            val fw = new FileWriter(filePath, true);
            events.foreach { x => { fw.write(x + "\n"); } };
            fw.close();
            deleteFile = true;
        }
        val finalPath = if (zip) CommonUtil.gzip(filePath) else filePath;
        Console.println(bucket, finalPath, key, Option(isPublic))
        Console.println("FC class type", fc.getClass.getTypeName);
        val storageService = fc.getStorageService("aws");
        storageService.upload(bucket, finalPath, key, Option(isPublic), None, None, None);
        storageService.closeContext();
        if (deleteFile) CommonUtil.deleteFile(filePath);
        if (zip) CommonUtil.deleteFile(finalPath);
        events;
    }
    
    def dispatch(config: Map[String, AnyRef], events: RDD[String])(implicit sc: SparkContext, fc: FrameworkContext) = {
        
        val bucket = config.getOrElse("bucket", null).asInstanceOf[String];
        val key = config.getOrElse("key", null).asInstanceOf[String];
        val isPublic = config.getOrElse("public", false).asInstanceOf[Boolean];

        if (null == bucket || null == key) {
            throw new DispatcherException("'bucket' & 'key' parameters are required to send output to S3")
        }
        events.saveAsTextFile("s3n://" + bucket + "/" + key);
    }

}