/*
 * Copyright 2010 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.store.readwrite.benchmark;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.jdom.JDOMException;

import voldemort.VoldemortException;
import voldemort.cluster.Cluster;
import voldemort.server.VoldemortConfig;
import voldemort.store.StoreDefinition;
import voldemort.store.readwrite.mr.AbstractRWHadoopStoreBuilderMapper;
import voldemort.store.readwrite.mr.HadoopRWStoreBuilder;
import voldemort.store.readwrite.mr.HadoopRWStoreJobRunner;
import voldemort.utils.ByteUtils;
import voldemort.utils.Utils;
import voldemort.xml.ClusterMapper;
import voldemort.xml.StoreDefinitionsMapper;

import com.google.common.collect.ImmutableCollection;

/**
 * Build a test read-write store from the generated data. Should have the
 * Voldemort server running.
 * 
 * We can use the data generated by
 * {@link voldemort.store.readonly.benchmark.BuildTestStore}
 * 
 */
@SuppressWarnings("deprecation")
public class BuildTestRWStore extends Configured implements Tool {

    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new BuildTestRWStore(), args);
        System.exit(res);
    }

    @SuppressWarnings("unchecked")
    public int run(String[] args) throws Exception {
        if(args.length != 4)
            Utils.croak("Expected arguments store_name config_dir input_path temp_path");
        String storeName = args[0];
        String configDir = args[1];
        String inputDir = args[2];
        String tempDir = args[3];

        List<StoreDefinition> storeDefs = new StoreDefinitionsMapper().readStoreList(new File(configDir,
                                                                                              "stores.xml"));
        StoreDefinition def = null;
        for(StoreDefinition d: storeDefs)
            if(d.getName().equals(storeName))
                def = d;
        Cluster cluster = new ClusterMapper().readCluster(new File(configDir, "cluster.xml"));

        Configuration config = this.getConf();
        config.set("mapred.job.name", "test-store-builder");

        Class[] deps = new Class[] { ImmutableCollection.class, JDOMException.class,
                VoldemortConfig.class, HadoopRWStoreJobRunner.class, VoldemortException.class };

        Configuration conf = getConf();
        HadoopRWStoreJobRunner.addDepJars(conf, deps, new ArrayList<String>());

        HadoopRWStoreBuilder builder = new HadoopRWStoreBuilder(config,
                                                                BuildTestStoreMapper.class,
                                                                SequenceFileInputFormat.class,
                                                                cluster,
                                                                def,
                                                                (long) (1.5 * 1024 * 1024 * 1024),
                                                                new Path(tempDir),
                                                                new Path(inputDir));
        builder.build();
        return 0;
    }

    public static class BuildTestStoreMapper extends
            AbstractRWHadoopStoreBuilderMapper<BytesWritable, BytesWritable> {

        @Override
        public Object makeKey(BytesWritable key, BytesWritable value) {
            return getValid(key);
        }

        @Override
        public Object makeValue(BytesWritable key, BytesWritable value) {
            return getValid(value);
        }

        private byte[] getValid(BytesWritable writable) {
            if(writable.getSize() == writable.getCapacity())
                return writable.get();
            else
                return ByteUtils.copy(writable.get(), 0, writable.getSize());
        }

    }
}