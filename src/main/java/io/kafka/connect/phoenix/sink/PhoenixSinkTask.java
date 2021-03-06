/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.kafka.connect.phoenix.sink;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.apache.kafka.connect.storage.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kafka.connect.phoenix.PhoenixClient;
import io.kafka.connect.phoenix.PhoenixConnectionManager;
import io.kafka.connect.phoenix.config.PhoenixSinkConfig;
import io.kafka.connect.phoenix.util.ToPhoenixRecordFunction;

/**
 * 
 * @author Dhananjay
 *
 */
public class PhoenixSinkTask extends SinkTask {
	
	private static final Logger log =LoggerFactory.getLogger(PhoenixSinkTask.class);

    private ToPhoenixRecordFunction toPhoenixRecordFunction;

	private Converter keyConverter;
	
	private Converter valueConverter;
	
	private PhoenixClient phoenixClient;
	
    @Override
    public String version() {
        return PhoenixSinkConnector.VERSION;
    }

    @Override
    public void start(Map<String, String> props) {
        final PhoenixSinkConfig sinkConfig = new PhoenixSinkConfig(props);
        sinkConfig.validate(); // we need to do some sanity checks of the properties we configure.
        
        
        this.toPhoenixRecordFunction = new ToPhoenixRecordFunction(sinkConfig);
        this.phoenixClient = new PhoenixClient(new PhoenixConnectionManager(sinkConfig.getPropertyValue(PhoenixSinkConfig.PQS_URL)));
    
        try {
			this.keyConverter = (Converter) Class.forName("org.apache.kafka.connect.json.JsonConverter").newInstance();
			this.valueConverter = (Converter) Class.forName("org.apache.kafka.connect.json.JsonConverter")
					.newInstance();
			Map<String, Object> converterMap = new HashMap<>();
			converterMap.put("key.converter.schemas.enable", false);
			converterMap.put("value.converter.schemas.enable", false);
			this.keyConverter.configure(converterMap, true);
			this.valueConverter.configure(converterMap, true);
		} catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
			log.warn("Failed to load key value converter for stat topic ", e);
		}
    }

    @Override
    public void put(final Collection<SinkRecord> records) { 
    	long startTime = System.nanoTime();
    	try{
        	Map<PhoenixSchemaInfo, List<SinkRecord>> bySchema = records.stream().collect(
        				groupingBy(e -> new PhoenixSchemaInfo(
        						toPhoenixRecordFunction.tableName(e.topic()),e.valueSchema())));
        	
        	Map<PhoenixSchemaInfo,List<Map<String,Object>>> toPhoenixRecords = bySchema.entrySet().parallelStream().collect(toMap( (es) -> es.getKey(),
        			(es) -> es.getValue().stream().map(r -> toPhoenixRecordFunction.apply(r)).collect(toList())));
        	
        	toPhoenixRecords.entrySet().forEach(e->{
        		this.phoenixClient.execute(e.getKey().getTableName(), e.getKey().getSchema(),e.getValue());
        	});
        }catch(Exception e){
        	log.error("Exception while persisting records"+ records,e);
        }
      log.info("Time taken to persist "+ records.size() +" sink records in ms"+(System.nanoTime()-startTime)/1000);
    }
 
    @Override
    public void flush(Map<TopicPartition, OffsetAndMetadata> offsets) {
        // NO-OP
    }

    @Override
    public void stop() {
        // NO-OP
    }

}