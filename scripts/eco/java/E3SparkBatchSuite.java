import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.concat;
import static org.apache.spark.sql.functions.count;
import static org.apache.spark.sql.functions.countDistinct;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.max;
import static org.apache.spark.sql.functions.min;
import static org.apache.spark.sql.functions.pmod;
import static org.apache.spark.sql.functions.sum;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;

/** Data and commit-integrity workload for ECO-SPK-01 and ECO-SPK-02. */
public final class E3SparkBatchSuite {
  private static final long ROWS = 10000;
  private static final long EXPECTED_SUM = ROWS * (ROWS - 1) / 2;

  private E3SparkBatchSuite() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("Usage: E3SparkBatchSuite <ceph test root> <committer version>");
      System.exit(2);
    }
    String root = args[0];
    String committerVersion = args[1];
    if (!"1".equals(committerVersion) && !"2".equals(committerVersion)) {
      throw new IllegalArgumentException("committer version must be 1 or 2");
    }

    SparkSession spark = SparkSession.builder()
        .appName("cephfs-eco-spk-p0-v" + committerVersion)
        .config("spark.hadoop.mapreduce.fileoutputcommitter.algorithm.version", committerVersion)
        .getOrCreate();
    spark.sparkContext().setLogLevel("WARN");

    try {
      Dataset<Row> source = spark.range(0, ROWS, 1, 12)
          .withColumn("bucket", pmod(col("id"), lit(17)).cast("int"))
          .withColumn("payload", concat(lit("row-"), col("id")));

      verifyFormat(spark, source, root, committerVersion, "parquet");
      verifyFormat(spark, source, root, committerVersion, "orc");
      System.out.println("SPARK_P0_RESULT|version=" + committerVersion
          + "|formats=parquet,orc|rows=" + ROWS + "|status=PASS");
    } finally {
      spark.stop();
    }
  }

  private static void verifyFormat(SparkSession spark, Dataset<Row> source, String root,
      String committerVersion, String format) throws Exception {
    String output = root + "/v" + committerVersion + "/" + format;
    source.write().format(format).mode(SaveMode.ErrorIfExists).save(output);

    Dataset<Row> readBack = spark.read().format(format).load(output);
    Row metrics = readBack.agg(
        count(lit(1)).alias("rows"),
        countDistinct("id").alias("distinct_ids"),
        sum("id").alias("id_sum"),
        min("id").alias("min_id"),
        max("id").alias("max_id"))
        .first();

    long rowCount = metrics.getLong(0);
    long distinctIds = metrics.getLong(1);
    long idSum = metrics.getLong(2);
    long minId = metrics.getLong(3);
    long maxId = metrics.getLong(4);
    if (rowCount != ROWS || distinctIds != ROWS || idSum != EXPECTED_SUM
        || minId != 0 || maxId != ROWS - 1) {
      throw new IllegalStateException("integrity mismatch for " + format
          + ": rows=" + rowCount + " distinct=" + distinctIds + " sum=" + idSum
          + " min=" + minId + " max=" + maxId);
    }

    Path outputPath = new Path(output);
    FileSystem fs = outputPath.getFileSystem(spark.sparkContext().hadoopConfiguration());
    boolean successMarker = fs.exists(new Path(outputPath, "_SUCCESS"));
    boolean temporaryResidue = fs.exists(new Path(outputPath, "_temporary"));
    if (!successMarker || temporaryResidue) {
      throw new IllegalStateException("commit residue mismatch for " + format
          + ": success=" + successMarker + " temporary=" + temporaryResidue);
    }

    System.out.println("SPARK_P0_FORMAT|version=" + committerVersion + "|format=" + format
        + "|rows=" + rowCount + "|distinct=" + distinctIds + "|sum=" + idSum
        + "|min=" + minId + "|max=" + maxId + "|success=" + successMarker
        + "|temporary=" + temporaryResidue);
  }
}
