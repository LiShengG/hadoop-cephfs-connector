/*
 * E3 MapReduce speculative execution probe.
 * The first attempt for slow.txt waits, while a speculative attempt can finish immediately.
 */
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

public final class SpikeMrSpeculation {
  private SpikeMrSpeculation() {
  }

  public static final class ProbeMapper
      extends Mapper<LongWritable, Text, Text, IntWritable> {
    private static final IntWritable ONE = new IntWritable(1);
    private final Text word = new Text();

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
      FileSplit split = (FileSplit) context.getInputSplit();
      int attempt = context.getTaskAttemptID().getId();
      boolean slow = split.getPath().getName().equals("slow.txt") && attempt == 0;
      System.out.println("SPEC_ATTEMPT|id=" + context.getTaskAttemptID()
          + "|file=" + split.getPath().getName() + "|slow=" + slow);
      if (slow) {
        Thread.sleep(90000L);
      }
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
        throws IOException, InterruptedException {
      for (String token : value.toString().split("\\s+")) {
        if (!token.isEmpty()) {
          word.set(token);
          context.write(word, ONE);
        }
      }
    }
  }

  public static final class SumReducer
      extends Reducer<Text, IntWritable, Text, IntWritable> {
    private final IntWritable total = new IntWritable();

    @Override
    protected void reduce(Text key, Iterable<IntWritable> values, Context context)
        throws IOException, InterruptedException {
      int sum = 0;
      for (IntWritable value : values) {
        sum += value.get();
      }
      total.set(sum);
      context.write(key, total);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("Usage: SpikeMrSpeculation <input> <output>");
      System.exit(2);
    }

    Configuration conf = new Configuration();
    conf.setBoolean("mapreduce.map.speculative", true);
    conf.setBoolean("mapreduce.reduce.speculative", false);
    conf.setInt("mapreduce.job.speculative.minimum-allowed-tasks", 1);
    conf.setFloat("mapreduce.job.speculative.speculative-cap-total-tasks", 1.0f);
    conf.setFloat("mapreduce.job.speculative.speculative-cap-running-tasks", 1.0f);
    conf.setFloat("mapreduce.job.speculative.slowtaskthreshold", 1.0f);
    conf.setLong("mapreduce.job.speculative.retry-after-no-speculate", 1000L);
    conf.setLong("mapreduce.job.speculative.retry-after-speculate", 1000L);
    conf.setInt("mapreduce.fileoutputcommitter.algorithm.version", 2);

    Job job = Job.getInstance(conf, "e3-cephfs-speculation");
    job.setJarByClass(SpikeMrSpeculation.class);
    job.setMapperClass(ProbeMapper.class);
    job.setReducerClass(SumReducer.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(IntWritable.class);
    job.setNumReduceTasks(1);
    FileInputFormat.addInputPath(job, new Path(args[0]));
    FileOutputFormat.setOutputPath(job, new Path(args[1]));
    System.exit(job.waitForCompletion(true) ? 0 : 1);
  }
}
