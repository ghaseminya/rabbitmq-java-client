package com.rabbitmq.client.test.functional;

import com.rabbitmq.client.*;
import com.rabbitmq.client.impl.ConcurrentStatistics;
import com.rabbitmq.client.impl.MetricsStatistics;
import com.rabbitmq.client.test.BrokerTestCase;
import org.awaitility.Duration;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

import static org.awaitility.Awaitility.to;
import static org.awaitility.Awaitility.waitAtMost;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;

/**
 *
 */
public class Statistics extends BrokerTestCase {

    static final String QUEUE = "statistics.queue";

    @Override
    protected void createResources() throws IOException, TimeoutException {
        channel.queueDeclare(QUEUE, false, false, false, null);
    }

    @Override
    protected void releaseResources() throws IOException {
        channel.queueDelete(QUEUE);
    }

    @Test public void statisticsStandardConnectionConcurrentStatistics() throws IOException, TimeoutException {
        doStatistics(new ConnectionFactory(), new ConcurrentStatistics());
    }

    @Test public void statisticsAutoRecoveryConnectionConcurrentStatistics() throws IOException, TimeoutException {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setAutomaticRecoveryEnabled(true);
        doStatistics(connectionFactory, new ConcurrentStatistics());
    }

    @Test public void statisticsStandardConnectionMetricsStatistics() throws IOException, TimeoutException {
        doStatistics(new ConnectionFactory(), new MetricsStatistics());
    }

    @Test public void statisticsAutoRecoveryConnectionMetricsStatistics() throws IOException, TimeoutException {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setAutomaticRecoveryEnabled(true);
        doStatistics(connectionFactory, new MetricsStatistics());
    }

    private void doStatistics(ConnectionFactory connectionFactory, StatisticsCollector statistics) throws IOException, TimeoutException {
        connectionFactory.setStatistics(statistics);
        Connection connection1 = null;
        Connection connection2 = null;
        try {
            connection1 = connectionFactory.newConnection();
            assertEquals(1, statistics.getConnectionCount());

            connection1.createChannel();
            connection1.createChannel();
            Channel channel = connection1.createChannel();
            assertEquals(3, statistics.getChannelCount());

            sendMessage(channel);
            assertEquals(1, statistics.getPublishedMessageCount());
            sendMessage(channel);
            assertEquals(2, statistics.getPublishedMessageCount());

            channel.basicGet(QUEUE, true);
            assertEquals(1, statistics.getConsumedMessageCount());
            channel.basicGet(QUEUE, true);
            assertEquals(2, statistics.getConsumedMessageCount());
            channel.basicGet(QUEUE, true);
            assertEquals(2, statistics.getConsumedMessageCount());

            connection2 = connectionFactory.newConnection();
            assertEquals(2, statistics.getConnectionCount());

            connection2.createChannel();
            channel = connection2.createChannel();
            assertEquals(3+2, statistics.getChannelCount());
            sendMessage(channel);
            sendMessage(channel);
            assertEquals(2+2, statistics.getPublishedMessageCount());

            channel.basicGet(QUEUE, true);
            assertEquals(2+1, statistics.getConsumedMessageCount());

            channel.basicConsume(QUEUE, true, new DefaultConsumer(channel));
            waitAtMost(timeout()).untilCall(to(statistics).getConsumedMessageCount(), equalTo(2L+1L+1L));

            safeClose(connection1);
            waitAtMost(timeout()).untilCall(to(statistics).getConnectionCount(), equalTo(1L));
            waitAtMost(timeout()).untilCall(to(statistics).getChannelCount(), equalTo(2L));

            safeClose(connection2);
            waitAtMost(timeout()).untilCall(to(statistics).getConnectionCount(), equalTo(0L));
            waitAtMost(timeout()).untilCall(to(statistics).getChannelCount(), equalTo(0L));

            assertEquals(0, statistics.getAcknowledgedMessageCount());
            assertEquals(0, statistics.getRejectedMessageCount());

        } finally {
            safeClose(connection1);
            safeClose(connection2);
        }
    }

    @Test public void statisticsClearStandardConnection() throws IOException, TimeoutException {
        doStatisticsClear(new ConnectionFactory());
    }

    @Test public void statisticsClearAutoRecoveryConnection() throws IOException, TimeoutException {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setAutomaticRecoveryEnabled(true);
        doStatisticsClear(connectionFactory);
    }

    private void doStatisticsClear(ConnectionFactory connectionFactory) throws IOException, TimeoutException {
        StatisticsCollector statistics = new ConcurrentStatistics();
        connectionFactory.setStatistics(statistics);
        try {
            Connection connection = connectionFactory.newConnection();
            Channel channel = connection.createChannel();
            sendMessage(channel);
            channel.basicGet(QUEUE, true);

            sendMessage(channel);
            GetResponse getResponse = channel.basicGet(QUEUE, false);
            channel.basicAck(getResponse.getEnvelope().getDeliveryTag(), false);

            sendMessage(channel);
            getResponse = channel.basicGet(QUEUE, false);
            channel.basicReject(getResponse.getEnvelope().getDeliveryTag(), false);

            statistics.clear();
            assertEquals(0, statistics.getConnectionCount());
            assertEquals(0, statistics.getChannelCount());
            assertEquals(0, statistics.getPublishedMessageCount());
            assertEquals(0, statistics.getConsumedMessageCount());
            assertEquals(0, statistics.getAcknowledgedMessageCount());
            assertEquals(0, statistics.getRejectedMessageCount());
        } finally {
            safeClose(connection);
        }

    }

    @Test public void statisticsAckStandardConnectionConcurrentStatistics() throws IOException, TimeoutException {
        doStatisticsAck(new ConnectionFactory(), new ConcurrentStatistics());
    }

    @Test public void statisticsAckAutoRecoveryConnectionConcurrentStatistics() throws IOException, TimeoutException {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setAutomaticRecoveryEnabled(true);
        doStatisticsAck(connectionFactory, new ConcurrentStatistics());
    }

    @Test public void statisticsAckStandardConnectionMetricsStatistics() throws IOException, TimeoutException {
        doStatisticsAck(new ConnectionFactory(), new MetricsStatistics());
    }

    @Test public void statisticsAckAutoRecoveryConnectionMetricsStatistics() throws IOException, TimeoutException {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setAutomaticRecoveryEnabled(true);
        doStatisticsAck(connectionFactory, new MetricsStatistics());
    }

    private void doStatisticsAck(ConnectionFactory connectionFactory, StatisticsCollector statistics) throws IOException, TimeoutException {
        connectionFactory.setStatistics(statistics);

        try {
            Connection connection = connectionFactory.newConnection();
            Channel channel1 = connection.createChannel();
            Channel channel2 = connection.createChannel();

            sendMessage(channel1);
            GetResponse getResponse = channel1.basicGet(QUEUE, false);
            channel1.basicAck(getResponse.getEnvelope().getDeliveryTag(), false);
            assertEquals(1, statistics.getConsumedMessageCount());
            assertEquals(1, statistics.getAcknowledgedMessageCount());

            // basicGet / basicAck
            sendMessage(channel1);
            sendMessage(channel2);
            sendMessage(channel1);
            sendMessage(channel2);
            sendMessage(channel1);
            sendMessage(channel2);

            GetResponse response1 = channel1.basicGet(QUEUE, false);
            GetResponse response2 = channel2.basicGet(QUEUE, false);
            GetResponse response3 = channel1.basicGet(QUEUE, false);
            GetResponse response4 = channel2.basicGet(QUEUE, false);
            GetResponse response5 = channel1.basicGet(QUEUE, false);
            GetResponse response6 = channel2.basicGet(QUEUE, false);

            assertEquals(1+6, statistics.getConsumedMessageCount());
            assertEquals(1, statistics.getAcknowledgedMessageCount());

            channel1.basicAck(response5.getEnvelope().getDeliveryTag(), false);
            assertEquals(1+1, statistics.getAcknowledgedMessageCount());
            channel1.basicAck(response3.getEnvelope().getDeliveryTag(), true);
            assertEquals(1+1+2, statistics.getAcknowledgedMessageCount());

            channel2.basicAck(response2.getEnvelope().getDeliveryTag(), true);
            assertEquals(1+(1+2)+1, statistics.getAcknowledgedMessageCount());
            channel2.basicAck(response6.getEnvelope().getDeliveryTag(), true);
            assertEquals(1+(1+2)+1+2, statistics.getAcknowledgedMessageCount());

            long alreadySentMessages = 1+(1+2)+1+2;

            // basicConsume / basicAck
            channel1.basicConsume(QUEUE, false, new MultipleAckConsumer(channel1, false));
            channel1.basicConsume(QUEUE, false, new MultipleAckConsumer(channel1, true));
            channel2.basicConsume(QUEUE, false, new MultipleAckConsumer(channel2, false));
            channel2.basicConsume(QUEUE, false, new MultipleAckConsumer(channel2, true));

            int nbMessages = 10;
            for(int i=0;i<nbMessages;i++) {
                sendMessage(i%2 == 0 ? channel1 : channel2);
            }

            waitAtMost(1, TimeUnit.SECONDS).untilCall(
                to(statistics).getConsumedMessageCount(),
                equalTo(alreadySentMessages+nbMessages)
            );

            waitAtMost(1, TimeUnit.SECONDS).untilCall(
                to(statistics).getAcknowledgedMessageCount(),
                equalTo(alreadySentMessages+nbMessages)
            );

        } finally {
            safeClose(connection);
        }
    }

    @Test public void statisticsRejectStandardConnectionConcurrentStatistics() throws IOException, TimeoutException {
        doStatisticsReject(new ConnectionFactory(), new ConcurrentStatistics());
    }

    @Test public void statisticsRejectAutoRecoveryConnectionConcurrentStatistics() throws IOException, TimeoutException {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setAutomaticRecoveryEnabled(true);
        doStatisticsReject(connectionFactory, new ConcurrentStatistics());
    }

    @Test public void statisticsRejectStandardConnectionMetricsStatistics() throws IOException, TimeoutException {
        doStatisticsReject(new ConnectionFactory(), new MetricsStatistics());
    }

    @Test public void statisticsRejectAutoRecoveryConnectionMetricsStatistics() throws IOException, TimeoutException {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setAutomaticRecoveryEnabled(true);
        doStatisticsReject(connectionFactory, new MetricsStatistics());
    }

    private void doStatisticsReject(ConnectionFactory connectionFactory, StatisticsCollector statistics) throws IOException, TimeoutException {
        connectionFactory.setStatistics(statistics);

        Connection connection = connectionFactory.newConnection();
        Channel channel = connection.createChannel();

        sendMessage(channel);
        sendMessage(channel);
        sendMessage(channel);

        GetResponse response1 = channel.basicGet(QUEUE, false);
        GetResponse response2 = channel.basicGet(QUEUE, false);
        GetResponse response3 = channel.basicGet(QUEUE, false);

        channel.basicReject(response2.getEnvelope().getDeliveryTag(), false);
        assertEquals(1, statistics.getRejectedMessageCount());

        channel.basicNack(response3.getEnvelope().getDeliveryTag(), true, false);
        assertEquals(1+2, statistics.getRejectedMessageCount());
    }

    @Test public void multiThreadedStatisticsStandardConnectionConcurrentStatistics() throws InterruptedException, TimeoutException, IOException {
        doMultiThreadedStatistics(new ConnectionFactory(), new ConcurrentStatistics());
    }

    @Test public void multiThreadedStatisticsAutoRecoveryConnectionConcurrentStatistics() throws InterruptedException, TimeoutException, IOException {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setAutomaticRecoveryEnabled(true);
        doMultiThreadedStatistics(connectionFactory, new ConcurrentStatistics());
    }

    @Test public void multiThreadedStatisticsStandardConnectionMetricsStatistics() throws InterruptedException, TimeoutException, IOException {
        doMultiThreadedStatistics(new ConnectionFactory(), new MetricsStatistics());
    }

    @Test public void multiThreadedStatisticsAutoRecoveryConnectionMetricsStatistics() throws InterruptedException, TimeoutException, IOException {
        ConnectionFactory connectionFactory = new ConnectionFactory();
        connectionFactory.setAutomaticRecoveryEnabled(true);
        doMultiThreadedStatistics(connectionFactory, new MetricsStatistics());
    }

    private void doMultiThreadedStatistics(ConnectionFactory connectionFactory, StatisticsCollector statistics) throws IOException, TimeoutException, InterruptedException {
        connectionFactory.setStatistics(statistics);
        int nbConnections = 3;
        int nbChannelsPerConnection = 5;
        int nbChannels = nbConnections * nbChannelsPerConnection;
        long nbOfMessages = 100;
        int nbTasks = nbChannels; // channel are not thread-safe

        Random random = new Random();

        // create connections
        Connection [] connections = new Connection[nbConnections];
        Channel [] channels = new Channel[nbChannels];
        for(int i=0;i<nbConnections;i++) {
            connections[i] = connectionFactory.newConnection();
            for(int j=0;j<nbChannelsPerConnection;j++) {
                Channel channel = connections[i].createChannel();
                channel.basicQos(1);
                channels[i*nbChannelsPerConnection+j] = channel;
            }
        }

        // consume messages without ack
        for(int i=0;i<nbOfMessages;i++) {
            sendMessage(channels[random.nextInt(nbChannels)]);
        }

        ExecutorService executorService = Executors.newFixedThreadPool(nbTasks);
        List<Callable<Void>> tasks = new ArrayList<Callable<Void>>();
        for(int i=0;i<nbTasks;i++) {
            Channel channelForConsuming = channels[random.nextInt(nbChannels)];
            tasks.add(random.nextInt(10)%2 == 0 ?
                new BasicGetTask(channelForConsuming, true) :
                new BasicConsumeTask(channelForConsuming, true));
        }
        executorService.invokeAll(tasks);

        assertEquals(nbOfMessages, statistics.getPublishedMessageCount());
        waitAtMost(1, TimeUnit.SECONDS).untilCall(to(statistics).getConsumedMessageCount(), equalTo(nbOfMessages));
        assertEquals(0, statistics.getAcknowledgedMessageCount());

        // to remove the listeners
        for(int i=0;i<nbChannels;i++) {
            channels[i].close();
            Channel channel = connections[random.nextInt(nbConnections)].createChannel();
            channel.basicQos(1);
            channels[i] = channel;
        }

        // consume messages with ack
        for(int i=0;i<nbOfMessages;i++) {
            sendMessage(channels[random.nextInt(nbChannels)]);
        }

        executorService = Executors.newFixedThreadPool(nbTasks);
        tasks = new ArrayList<Callable<Void>>();
        for(int i=0;i<nbTasks;i++) {
            Channel channelForConsuming = channels[i];
            tasks.add(random.nextBoolean() ?
                new BasicGetTask(channelForConsuming, false) :
                new BasicConsumeTask(channelForConsuming, false));
        }
        executorService.invokeAll(tasks);

        assertEquals(2*nbOfMessages, statistics.getPublishedMessageCount());
        waitAtMost(1, TimeUnit.SECONDS).untilCall(to(statistics).getConsumedMessageCount(), equalTo(2*nbOfMessages));
        waitAtMost(1, TimeUnit.SECONDS).untilCall(to(statistics).getAcknowledgedMessageCount(), equalTo(nbOfMessages));

        // to remove the listeners
        for(int i=0;i<nbChannels;i++) {
            channels[i].close();
            Channel channel = connections[random.nextInt(nbConnections)].createChannel();
            channel.basicQos(1);
            channels[i] = channel;
        }

        // consume messages and reject them
        for(int i=0;i<nbOfMessages;i++) {
            sendMessage(channels[random.nextInt(nbChannels)]);
        }

        executorService = Executors.newFixedThreadPool(nbTasks);
        tasks = new ArrayList<Callable<Void>>();
        for(int i=0;i<nbTasks;i++) {
            Channel channelForConsuming = channels[i];
            tasks.add(random.nextBoolean() ?
                new BasicGetRejectTask(channelForConsuming) :
                new BasicConsumeRejectTask(channelForConsuming));
        }
        executorService.invokeAll(tasks);

        assertEquals(3*nbOfMessages, statistics.getPublishedMessageCount());
        waitAtMost(1, TimeUnit.SECONDS).untilCall(to(statistics).getConsumedMessageCount(), equalTo(3*nbOfMessages));
        waitAtMost(1, TimeUnit.SECONDS).untilCall(to(statistics).getAcknowledgedMessageCount(), equalTo(nbOfMessages));
        waitAtMost(1, TimeUnit.SECONDS).untilCall(to(statistics).getRejectedMessageCount(), equalTo(nbOfMessages));
    }

    private static class BasicGetTask implements Callable<Void> {

        final Channel channel;
        final boolean autoAck;
        final Random random = new Random();

        private BasicGetTask(Channel channel, boolean autoAck) {
            this.channel = channel;
            this.autoAck = autoAck;
        }

        @Override
        public Void call() throws Exception {
            GetResponse getResponse = this.channel.basicGet(QUEUE, autoAck);
            if(!autoAck) {
                channel.basicAck(getResponse.getEnvelope().getDeliveryTag(), random.nextBoolean());
            }
            return null;
        }
    }

    private static class BasicConsumeTask implements Callable<Void> {

        final Channel channel;
        final boolean autoAck;
        final Random random = new Random();

        private BasicConsumeTask(Channel channel, boolean autoAck) {
            this.channel = channel;
            this.autoAck = autoAck;
        }

        @Override
        public Void call() throws Exception {
            this.channel.basicConsume(QUEUE, autoAck, new DefaultConsumer(channel) {

                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                    if(!autoAck) {
                        getChannel().basicAck(envelope.getDeliveryTag(), random.nextBoolean());
                    }
                }
            });
            return null;
        }
    }

    private static class BasicGetRejectTask implements Callable<Void> {

        final Channel channel;
        final Random random = new Random();

        private BasicGetRejectTask(Channel channel) {
            this.channel = channel;
        }

        @Override
        public Void call() throws Exception {
            GetResponse response = channel.basicGet(QUEUE, false);
            if(response != null) {
                if(random.nextBoolean()) {
                    channel.basicNack(response.getEnvelope().getDeliveryTag(), random.nextBoolean(), false);
                } else {
                    channel.basicReject(response.getEnvelope().getDeliveryTag(), false);
                }
            }
            return null;
        }
    }

    private static class BasicConsumeRejectTask implements Callable<Void> {

        final Channel channel;
        final Random random = new Random();

        private BasicConsumeRejectTask(Channel channel) {
            this.channel = channel;
        }

        @Override
        public Void call() throws Exception {
            this.channel.basicConsume(QUEUE, false, new DefaultConsumer(channel) {

                @Override
                public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                    if(random.nextBoolean()) {
                        channel.basicNack(envelope.getDeliveryTag(), random.nextBoolean(), false);
                    } else {
                        channel.basicReject(envelope.getDeliveryTag(), false);
                    }
                }
            });
            return null;
        }
    }

    private void safeClose(Connection connection) {
        if(connection != null) {
            try {
                connection.abort();
            } catch (Exception e) {
                // OK
            }
        }
    }

    private void sendMessage(Channel channel) throws IOException {
        channel.basicPublish("", QUEUE, null, "msg".getBytes("UTF-8"));
    }

    private Duration timeout() {
        return new Duration(150, TimeUnit.MILLISECONDS);
    }

    private static class MultipleAckConsumer extends DefaultConsumer {

        final boolean multiple;

        public MultipleAckConsumer(Channel channel, boolean multiple) {
            super(channel);
            this.multiple = multiple;
        }

        @Override
        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
            try {
                Thread.sleep(new Random().nextInt(10));
            } catch (InterruptedException e) {
                throw new RuntimeException("Error during randomized wait",e);
            }
            getChannel().basicAck(envelope.getDeliveryTag(), multiple);
        }
    }

}
