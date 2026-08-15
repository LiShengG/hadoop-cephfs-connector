/*
 * 真实 Spark 3.4 Structured Streaming checkpoint 探针。
 * 使用 rate source、CephFS Parquet sink 与 CephFS checkpoint，支持以同一路径重启。
 */
import java.util.UUID;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.streaming.StreamingQuery;
import org.apache.spark.sql.streaming.StreamingQueryProgress;
import org.apache.spark.sql.streaming.Trigger;

public final class SpikeSparkCheckpoint {
  private SpikeSparkCheckpoint() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("用法: SpikeSparkCheckpoint <ceph 根路径> <运行秒数>");
      System.exit(2);
    }
    String root = args[0];
    long timeoutSeconds = Long.parseLong(args[1]);
    String checkpoint = root + "/checkpoint";
    String output = root + "/output";

    SparkSession spark = SparkSession.builder()
        .appName("cephfs-sp04-checkpoint")
        .getOrCreate();
    spark.sparkContext().setLogLevel("WARN");

    StreamingQuery query = null;
    try {
      Dataset<Row> input = spark.readStream()
          .format("rate-micro-batch")
          .option("rowsPerBatch", 40)
          .option("numPartitions", 2)
          .option("advanceMsPerBatch", 1000)
          .load();

      query = input.writeStream()
          .format("parquet")
          .outputMode("append")
          .option("path", output)
          .option("checkpointLocation", checkpoint)
          .trigger(Trigger.Once())
          .start();

      UUID queryId = query.id();
      UUID runId = query.runId();
      if (!query.awaitTermination(timeoutSeconds * 1000)) {
        query.stop();
        throw new IllegalStateException("Structured Streaming 单批次执行超时");
      }
      StreamingQueryProgress progress = query.lastProgress();
      if (progress == null) {
        throw new IllegalStateException("Structured Streaming 未产生进度记录");
      }
      long batchId = progress.batchId();
      query = null;

      long rows = spark.read().parquet(output).count();
      System.out.println("SPARK_CHECKPOINT|queryId=" + queryId + "|runId=" + runId
          + "|batchId=" + batchId + "|rows=" + rows);
      System.out.println("RESULT|SP-04D|REFUTED|Structured Streaming 使用 CephFS "
          + "checkpoint 完成提交；queryId=" + queryId + " batchId=" + batchId
          + " rows=" + rows);
    } finally {
      if (query != null) {
        query.stop();
      }
      spark.stop();
    }
  }
}
