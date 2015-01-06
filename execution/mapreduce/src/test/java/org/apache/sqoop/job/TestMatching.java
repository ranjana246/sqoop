/**
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
package org.apache.sqoop.job;

import static org.apache.sqoop.connector.common.SqoopIDFUtils.BYTE_FIELD_CHARSET;
import static org.apache.sqoop.connector.common.SqoopIDFUtils.toText;
import static org.junit.Assert.assertEquals;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.sqoop.common.Direction;
import org.apache.sqoop.connector.common.EmptyConfiguration;
import org.apache.sqoop.connector.idf.CSVIntermediateDataFormat;
import org.apache.sqoop.connector.idf.IntermediateDataFormat;
import org.apache.sqoop.connector.matcher.Matcher;
import org.apache.sqoop.connector.matcher.MatcherFactory;
import org.apache.sqoop.job.etl.Extractor;
import org.apache.sqoop.job.etl.ExtractorContext;
import org.apache.sqoop.job.etl.Partition;
import org.apache.sqoop.job.etl.Partitioner;
import org.apache.sqoop.job.etl.PartitionerContext;
import org.apache.sqoop.job.io.SqoopWritable;
import org.apache.sqoop.job.mr.MRConfigurationUtils;
import org.apache.sqoop.job.mr.SqoopInputFormat;
import org.apache.sqoop.job.mr.SqoopMapper;
import org.apache.sqoop.job.util.MRJobTestUtil;
import org.apache.sqoop.schema.NullSchema;
import org.apache.sqoop.schema.Schema;
import org.apache.sqoop.schema.type.FixedPoint;
import org.apache.sqoop.schema.type.FloatingPoint;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;


@RunWith(Parameterized.class)
public class TestMatching {
  private static final int START_PARTITION = 1;
  private static final int NUMBER_OF_PARTITIONS = 1;
  private static final int NUMBER_OF_ROWS_PER_PARTITION = 1;

  private Schema from;
  private Schema to;

  public TestMatching(Schema from,
                       Schema to)
      throws Exception {
    this.from = from;
    this.to = to;

    System.out.println("Testing with Schemas\n\tFROM: " + this.from + "\n\tTO: " + this.to);
  }

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    List<Object[]> parameters = new ArrayList<Object[]>();

    Schema emptyFrom = new Schema("FROM-EMPTY");
    Schema emptyTo = new Schema("TO-EMPTY");
    Schema from1 = new Schema("FROM-1");
    Schema to1 = new Schema("TO-1");
    Schema from2 = new Schema("FROM-2");
    Schema to2 = new Schema("TO-2");

    from1.addColumn(new FixedPoint("1")).addColumn(new FloatingPoint("2"))
        .addColumn(new org.apache.sqoop.schema.type.Text("3"));
    to1.addColumn(new FixedPoint("1")).addColumn(new FloatingPoint("2"))
      .addColumn(new org.apache.sqoop.schema.type.Text("3"));
    from2.addColumn(new FixedPoint("1")).addColumn(new FloatingPoint("2"));
    to2.addColumn(new FixedPoint("1")).addColumn(new FloatingPoint("2"));

    parameters.add(new Object[]{
        emptyFrom,
        emptyTo
    });
    parameters.add(new Object[]{
        from1,
        emptyTo
    });
    parameters.add(new Object[]{
        emptyTo,
        to1
    });
    parameters.add(new Object[]{
        from1,
        to1
    });
    parameters.add(new Object[]{
        from2,
        to1
    });
    parameters.add(new Object[]{
        from1,
        to2
    });

    return parameters;
  }

  @SuppressWarnings("deprecation")
  @Test
  public void testSchemaMatching() throws Exception {
    Configuration conf = new Configuration();
    conf.set(MRJobConstants.JOB_ETL_PARTITIONER, DummyPartitioner.class.getName());
    conf.set(MRJobConstants.JOB_ETL_EXTRACTOR, DummyExtractor.class.getName());
    conf.set(MRJobConstants.FROM_INTERMEDIATE_DATA_FORMAT, CSVIntermediateDataFormat.class.getName());
    conf.set(MRJobConstants.TO_INTERMEDIATE_DATA_FORMAT, CSVIntermediateDataFormat.class.getName());

    Job job = new Job(conf);
    MRConfigurationUtils.setConnectorSchema(Direction.FROM, job, from);
    MRConfigurationUtils.setConnectorSchema(Direction.TO, job, to);
    MRJobTestUtil.runJob(job.getConfiguration(), SqoopInputFormat.class, SqoopMapper.class,
        DummyOutputFormat.class);
    boolean success = MRJobTestUtil.runJob(job.getConfiguration(),
        SqoopInputFormat.class, SqoopMapper.class,
        DummyOutputFormat.class);
    if (from.getName().split("-")[1].equals("EMPTY")) {
      if (to.getName().split("-")[1].equals("EMPTY")) {
        Assert.assertEquals("Job succeeded!", false, success);
      } else {
        Assert.assertEquals("Job failed!", true, success);
      }
    } else {
      if (to.getName().split("-")[1].equals("EMPTY")) {
        Assert.assertEquals("Job failed!", true, success);
      } else if (from.getName().split("-")[1].equals(to.getName().split("-")[1])) {
        Assert.assertEquals("Job failed!", true, success);
      } else {
        Assert.assertEquals("Job succeeded!", false, success);
      }
    }
  }


  @Test
  public void testSchemalessFromAndTo() throws UnsupportedEncodingException {
    CSVIntermediateDataFormat dataFormat = new CSVIntermediateDataFormat();
    String testData = "\"This is the data you are looking for. It has no structure.\"";
    Object[] testObject = new Object[] {testData.getBytes(BYTE_FIELD_CHARSET)};
    Object[] testObjectCopy = new Object[1];
    System.arraycopy(testObject,0,testObjectCopy,0,testObject.length);

    Matcher matcher = MatcherFactory.getMatcher(NullSchema.getInstance(),
            NullSchema.getInstance());
    // Checking FROM side only because currently that is the only IDF that is used
    dataFormat.setSchema(matcher.getFromSchema());

    // Setting data as CSV and validating getting CSV and object
    dataFormat.setCSVTextData(testData);

    String validateCSV = dataFormat.getCSVTextData();
    Object[] validateObj = dataFormat.getObjectData();

    assertEquals(testData, validateCSV);
    assertEquals(testObject, validateObj);

    // Setting data as Object
    dataFormat.setObjectData(testObject);

    validateCSV = toText(dataFormat.getCSVTextData());
    validateObj = dataFormat.getObjectData();

    assertEquals(testData, validateCSV);
    assertEquals(testObjectCopy, validateObj);
  }

  public static class DummyPartition extends Partition {
    private int id;

    public void setId(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    @Override
    public void readFields(DataInput in) throws IOException {
      id = in.readInt();
    }

    @Override
    public void write(DataOutput out) throws IOException {
      out.writeInt(id);
    }

    @Override
    public String toString() {
      return Integer.toString(id);
    }
  }

  public static class DummyPartitioner extends Partitioner {
    @Override
    public List<Partition> getPartitions(PartitionerContext context, Object oc, Object oj) {
      List<Partition> partitions = new LinkedList<Partition>();
      for (int id = START_PARTITION; id <= NUMBER_OF_PARTITIONS; id++) {
        DummyPartition partition = new DummyPartition();
        partition.setId(id);
        partitions.add(partition);
      }
      return partitions;
    }
  }

  public static class DummyExtractor extends Extractor<EmptyConfiguration, EmptyConfiguration, Partition> {
    @Override
    public void extract(ExtractorContext context, EmptyConfiguration oc, EmptyConfiguration oj, Partition partition) {
      int id = ((DummyPartition)partition).getId();
      for (int row = 0; row < NUMBER_OF_ROWS_PER_PARTITION; row++) {
        context.getDataWriter().writeArrayRecord(new Object[] {
            id * NUMBER_OF_ROWS_PER_PARTITION + row,
            (double) (id * NUMBER_OF_ROWS_PER_PARTITION + row),
            String.valueOf(id*NUMBER_OF_ROWS_PER_PARTITION+row)});
      }
    }

    @Override
    public long getRowsRead() {
      return NUMBER_OF_ROWS_PER_PARTITION;
    }
  }

  public static class DummyOutputFormat
      extends OutputFormat<SqoopWritable, NullWritable> {
    @Override
    public void checkOutputSpecs(JobContext context) {
      // do nothing
    }

    @Override
    public RecordWriter<SqoopWritable, NullWritable> getRecordWriter(
        TaskAttemptContext context) {
      return new DummyRecordWriter();
    }

    @Override
    public OutputCommitter getOutputCommitter(TaskAttemptContext context) {
      return new DummyOutputCommitter();
    }

    public static class DummyRecordWriter
        extends RecordWriter<SqoopWritable, NullWritable> {
      private int index = START_PARTITION*NUMBER_OF_ROWS_PER_PARTITION;
      private IntermediateDataFormat<?> dataFormat = MRJobTestUtil.getTestIDF();

      @Override
      public void write(SqoopWritable key, NullWritable value) {
        String testData = "" + index + "," +  (double) index + ",'" + String.valueOf(index) + "'";
        dataFormat.setCSVTextData(testData);
        index++;
        assertEquals(dataFormat.getCSVTextData().toString(), key.toString());
      }

      @Override
      public void close(TaskAttemptContext context) {
        // do nothing
      }
    }

    public static class DummyOutputCommitter extends OutputCommitter {
      @Override
      public void setupJob(JobContext jobContext) { }

      @Override
      public void setupTask(TaskAttemptContext taskContext) { }

      @Override
      public void commitTask(TaskAttemptContext taskContext) { }

      @Override
      public void abortTask(TaskAttemptContext taskContext) { }

      @Override
      public boolean needsTaskCommit(TaskAttemptContext taskContext) {
        return false;
      }
    }
  }
}