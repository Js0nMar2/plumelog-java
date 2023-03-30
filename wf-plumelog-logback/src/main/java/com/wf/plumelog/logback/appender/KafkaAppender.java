package com.wf.plumelog.logback.appender;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.alibaba.fastjson.JSONObject;
import com.wf.plumelog.core.dto.BaseLogMessage;
import com.wf.plumelog.core.dto.RunLogMessage;
import com.wf.plumelog.logback.utils.LogMessageUtil;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;

/**
 * @author linzm
 * @Title:
 * @Description:
 * @date 2023/3/20 9:49
 */
public class KafkaAppender extends AppenderBase<ILoggingEvent> {

    private String appName;
    private String env;

    private String kafkaHost = "localhost:9092";

    public static KafkaProducer<String, String> kafkaProducer;

    public static BlockingQueue<String> dataQueue = new LinkedBlockingQueue<>();

    public String getKafkaHost() {
        return kafkaHost;
    }

    public void setKafkaHost(String kafkaHost) {
        this.kafkaHost = kafkaHost;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event != null) {
            send(event);
        }
    }

    protected void send(ILoggingEvent event) {
        final BaseLogMessage logMessage = LogMessageUtil.getLogMessage(appName, env, event);
        if (logMessage instanceof RunLogMessage) {
            final String message = LogMessageUtil.getLogMessage(logMessage, event);
            JSONObject json = new JSONObject();
            json.put("appName", appName);
            json.put("env", env);
            json.put("message", message);
            dataQueue.add(json.toJSONString());
        }
    }

    @Override
    public void start() {
        super.start();
        if (kafkaProducer == null) {
            Properties properties = new Properties();
            properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaHost);
            properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
            properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
            properties.put(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, 10000);
            properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
            properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
            kafkaProducer = new KafkaProducer<>(properties);
            kafkaProducer.send(new ProducerRecord<>("app-index", appName));
            ScheduledExecutorService executorService = Executors.newScheduledThreadPool(8);
            executorService.scheduleAtFixedRate(() -> {
                List<String> logs = new ArrayList<>();
                if (dataQueue.size() > 0) {
                    dataQueue.drainTo(logs, dataQueue.size());
                    kafkaProducer.send(new ProducerRecord<>(appName, JSONObject.toJSONString(logs)));
                }
            }, 1, 1, TimeUnit.SECONDS);
        }
    }
}
