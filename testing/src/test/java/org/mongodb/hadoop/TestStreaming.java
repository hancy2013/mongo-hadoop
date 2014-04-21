package org.mongodb.hadoop;

import com.mongodb.DBCollection;
import com.mongodb.ReadPreference;
import com.mongodb.hadoop.mapred.MongoInputFormat;
import com.mongodb.hadoop.mapred.MongoOutputFormat;
import com.mongodb.hadoop.streaming.io.MongoIdentifierResolver;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Before;
import org.junit.Test;
import org.zeroturnaround.exec.ProcessExecutor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

import static com.mongodb.hadoop.util.MongoConfigUtil.INPUT_URI;
import static com.mongodb.hadoop.util.MongoConfigUtil.OUTPUT_URI;
import static java.lang.String.format;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeFalse;

public class TestStreaming extends BaseHadoopTest {
    private static final String STREAMING_JAR = new File("../streaming/build/libs").listFiles(new HadoopVersionFilter())[0]
                                                    .getAbsolutePath();
    private static final String STREAMING_MAPPER = new File("../streaming/examples/treasury/mapper.py").getAbsolutePath();

    private static final String STREAMING_REDUCER = new File("../streaming/examples/treasury/reducer.py").getAbsolutePath();

    @Before
    public void hadoopVersionCheck() {
        assumeFalse(HADOOP_VERSION.startsWith("1.0") || HADOOP_VERSION.startsWith("0.20"));
    }

    protected static class StreamingJob {
        private static final Log LOG = LogFactory.getLog(StreamingJob.class);

        private List<String> cmd = new ArrayList<String>();

        private String hostName = "localhost:27017";
        private ReadPreference readPreference = ReadPreference.primary();

        private String inputAuth = null;
        private String inputCollection = "mongo_hadoop.yield_historical.in";
        private String inputFormat = MongoInputFormat.class.getName();
        private String inputPath = format("file://%s/in", System.getProperty("java.io.tmpdir"));

        private String outputAuth = null;
        private String outputCollection = "mongo_hadoop.yield_historical.out";
        private String outputFormat = MongoOutputFormat.class.getName();
        private String outputPath = format("file://%s/out", System.getProperty("java.io.tmpdir"));
        private Map<String, String> params;

        public StreamingJob() {
            cmd.add(HADOOP_HOME + "/bin/hadoop");
            cmd.add("jar");
            if (HADOOP_VERSION.startsWith("1.1")) {
                cmd.add(String.format("%s/contrib/streaming/hadoop-streaming-%s.jar", HADOOP_HOME, HADOOP_RELEASE_VERSION));
            } else {
                cmd.add(String.format("%s/share/hadoop/tools/lib/hadoop-streaming-%s.jar", HADOOP_HOME, HADOOP_RELEASE_VERSION));
            }
            add("-libjars", STREAMING_JAR);
            add("-io", "mongodb");
            add("-jobconf", "stream.io.identifier.resolver.class=" + MongoIdentifierResolver.class.getName());
            add("-mapper", STREAMING_MAPPER);
            add("-reducer", STREAMING_REDUCER);
        }

        public StreamingJob hostName(String hostName) {
            this.hostName = hostName;
            return this;
        }

        public StreamingJob readPreference(ReadPreference readPreference) {
            this.readPreference = readPreference;
            return this;
        }

        public StreamingJob inputAuth(String inputAuth) {
            this.inputAuth = inputAuth;
            return this;
        }

        public StreamingJob inputCollection(String inputCollection) {
            this.inputCollection = inputCollection;
            return this;
        }

        public StreamingJob inputFormat(String format) {
            inputFormat = format;
            return this;
        }

        public StreamingJob inputPath(String path) {
            inputPath = path;
            return this;
        }

        public StreamingJob outputAuth(String outputAuth) {
            this.outputAuth = outputAuth;
            return this;
        }

        public StreamingJob outputCollection(String outputCollection) {
            this.outputCollection = outputCollection;
            return this;
        }

        public StreamingJob outputFormat(String format) {
            outputFormat = format;
            return this;
        }

        public StreamingJob outputPath(String path) {
            outputPath = path;
            return this;
        }

        public StreamingJob params(Map<String, String> params) {
            this.params = params;
            return this;
        }

        public void execute() {
            try {
                add("-input", inputPath);
                add("-output", outputPath);
                add("-inputformat", inputFormat);
                add("-outputformat", outputFormat);

                add("-jobconf", format("%s=mongodb://%s%s/%s?readPreference=%s", INPUT_URI, inputAuth != null ? inputAuth + "@" : "",
                                       hostName, inputCollection, readPreference));

                add("-jobconf", format("%s=mongodb://%s%s/%s", OUTPUT_URI, outputAuth != null ? outputAuth + "@" : "", hostName,
                                       outputCollection));

                for (Entry<String, String> entry : params.entrySet()) {
                    add("-jobconf", entry.getKey() + "=" + entry.getValue());

                }
                Map<String, String> env = new TreeMap<String, String>(System.getenv());
                if (HADOOP_VERSION.startsWith("cdh")) {
                    env.put("MAPRED_DIR", "share/hadoop/mapreduce2");
                }

                LOG.info("Executing hadoop job:");

                StringBuilder output = new StringBuilder();
                Iterator<String> iterator = cmd.iterator();
                while (iterator.hasNext()) {
                    final String s = iterator.next();
                    if (output.length() != 0) {
                        output.append("\t");
                    } else {
                        output.append("\n");
                    }
                    output.append(s);
                    if (iterator.hasNext()) {
                        output.append(" \\");
                    }
                    output.append("\n");
                }

                LOG.info(output);
                new ProcessExecutor().command(cmd)
                                     .environment(env)
                                     .redirectError(System.out)
                                     .execute();

                Thread.sleep(5000);  // let the system settle
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (TimeoutException e) {
                e.printStackTrace();
            }

        }

        private void add(final String flag, final String value) {
            cmd.add(flag);
            cmd.add(value);
        }
    }

    @Test
    public void testBasicStreamingJob() {
        Map<String, String> params = new TreeMap<String, String>();
        params.put("mongo.input.query", "{_id:{$gt:{$date:883440000000}}}");
        new StreamingJob()
            .params(params)
            .execute();
        
        DBCollection collection = getClient().getDB("mongo_hadoop").getCollection("yield_historical.out");
        assertEquals(14, collection.count());
    }
}