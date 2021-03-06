/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.pivotal.spring.xd.jdbcgpfdist;

import com.codahale.metrics.Meter;
import io.pivotal.spring.xd.jdbcgpfdist.support.GreenplumLoad;
import io.pivotal.spring.xd.jdbcgpfdist.support.NetworkUtils;
import io.pivotal.spring.xd.jdbcgpfdist.support.RuntimeContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.StringUtils;
import org.springframework.util.concurrent.SettableListenableFuture;
import reactor.Environment;
import reactor.core.processor.RingBufferProcessor;
import reactor.io.buffer.Buffer;

import java.util.Date;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class GPFDistMessageHandler {

    private final Log log = LogFactory.getLog(GPFDistMessageHandler.class);

    private final int port;

    private final int flushCount;

    private final int flushTime;

    private final int batchTimeout;

    private final int batchCount;

    private final int batchPeriod;

    private final String delimiter;

    private GreenplumLoad greenplumLoad;

    private RingBufferProcessor<Buffer> processor;

    private GPFDistServer gpfdistServer;

    private int rateInterval = 0;

    private Meter meter = null;

    private int meterCount = 0;

    private RuntimeContext context;

    private  TaskFuture taskFuture;

    private TaskScheduler sqlTaskScheduler;


    public GPFDistMessageHandler(int port, int flushCount, int flushTime, int batchTimeout, int batchCount,
                                 int batchPeriod, String delimiter) {
        super();
        this.port = port;
        this.flushCount = flushCount;
        this.flushTime = flushTime;
        this.batchTimeout = batchTimeout;
        this.batchCount = batchCount;
        this.batchPeriod = batchPeriod;
        this.delimiter = StringUtils.hasLength(delimiter) ? delimiter : null;
    }


    public void start() {

        try {

            taskFuture =  new TaskFuture();
            //init counter
            meterCount = 0;

            context = new RuntimeContext();

            Environment.initializeIfEmpty().assignErrorJournal();
            processor = RingBufferProcessor.create(false);

            if (rateInterval > 0) {
                meter = new Meter();
            }

            log.info("Creating gpfdist protocol listener on port=" + port);
            gpfdistServer = new GPFDistServer(processor, port, flushCount, flushTime, batchTimeout, batchCount);
            gpfdistServer.start();
            log.info("gpfdist protocol listener running on port=" + gpfdistServer.getLocalPort());
            context.addLocation(NetworkUtils.getGPFDistUri(gpfdistServer.getLocalPort()));


            log.info("Scheduling gpfdist task with batchPeriod=" + batchPeriod);
            sqlTaskScheduler.schedule((new FutureTask<Void>(() -> {
                boolean taskValue = true;
                try {
                    while(!taskFuture.interrupted) {
                        try {
                            greenplumLoad.load(context);
                        } catch (Exception e) {
                            log.error("Error in load", e);
                        }
                        Thread.sleep(batchPeriod*1000);
                    }
                } catch (Exception e) {
                    taskValue = false;
                }
                taskFuture.set(taskValue);
            }, null)), new Date());

        } catch (Exception e) {
            throw new RuntimeException("Error starting protocol listener", e);
        }


    }


    public void stop() {

        boolean drained = false;
        if (greenplumLoad != null) {

            greenplumLoad.load(context);
            // xd waits 30s to shutdown module, so lets wait 25 to drain
            long waitDrain = System.currentTimeMillis() + 25000l;

            log.info("Trying to wait buffer to get drained");
            while (System.currentTimeMillis() < waitDrain) {
                long capacity = processor.getCapacity();
                long availableCapacity = processor.getAvailableCapacity();
                log.info("Buffer capacity " + capacity);
                log.info("Buffer available capacity " + availableCapacity);
                if (capacity != availableCapacity) {
                    try {
                        Thread.sleep(1000);
                    }
                    catch (InterruptedException e) {
                    }
                }
                else {
                    log.info("Marking stream drained");
                    drained = true;
                    break;
                }
            }

        }

        // try to wait current load operation to finish.
        taskFuture.interruptTask();
        try {
            long now = System.currentTimeMillis();
            // wait a bit more than batch period
            log.info("Cancelling loading task");
            Boolean value = taskFuture.get(batchTimeout + batchPeriod + 2, TimeUnit.SECONDS);
            log.info("Stopping, got future value " + value + " from task which took "
                    + (System.currentTimeMillis() - now) + "ms");
        }
        catch (Exception e) {
            log.warn("Got error from task wait value which may indicate trouble", e);
        }


        try {
            if (drained) {
                log.info("Sending onComplete to processor");
                processor.onComplete();
            }
            else {
                // if it looks like we didn't drain,
                // force shutdown as onComplete will
                // block otherwise.
                log.info("Forcing processor shutdown");
                processor.forceShutdown();
            }
            log.info("Shutting down protocol listener");
            gpfdistServer.stop();

        }
        catch (Exception e) {
            log.warn("Error shutting down protocol listener", e);
        }
    }

    public void sendMessage(String message)  {

        if (delimiter != null) {
            processor.onNext(Buffer.wrap(message + delimiter));
        }
        else {
            processor.onNext(Buffer.wrap(message));
        }
        if (meter != null) {
            if ((meterCount++ % rateInterval) == 0) {
                meter.mark(rateInterval);
                log.info("METER: 1 minute rate = " + meter.getOneMinuteRate() + " mean rate = " + meter.getMeanRate());
//                greenplumLoad.load(context);
            }
        }

    }

    public void setSqlTaskScheduler(TaskScheduler sqlTaskScheduler) {
        this.sqlTaskScheduler = sqlTaskScheduler;
    }

    public void setGreenplumLoad(GreenplumLoad greenplumLoad) {
        this.greenplumLoad = greenplumLoad;
    }

    public void setRateInterval(int rateInterval) {
        this.rateInterval = rateInterval;

    }

    private static class TaskFuture extends SettableListenableFuture<Boolean> {

        boolean interrupted = false;

        @Override
        protected void interruptTask() {
            interrupted = true;
        }


    }
}