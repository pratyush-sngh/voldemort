package voldemort.coordinator;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_LENGTH;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponse;

import voldemort.client.ClientConfig;
import voldemort.client.DefaultStoreClient;
import voldemort.client.SocketStoreClientFactory;
import voldemort.utils.ByteArray;
import voldemort.versioning.ObsoleteVersionException;
import voldemort.versioning.VectorClock;
import voldemort.versioning.Versioned;

public class FatClientWrapper {

    private ExecutorService fatClientExecutor;
    private SynchronousQueue<Future> fatClientRequestQueue;
    private SocketStoreClientFactory storeClientFactory;
    private DefaultStoreClient<Object, Object> storeClient;

    /**
     * A Wrapper class to provide asynchronous API for calling the fat client
     * methods. These methods will be invoked by the Netty request handler
     * instead of invoking the Fat Client methods on its own
     * 
     * @param storeName: Store to connect to via this fat client
     * @param bootstrapURLs: Bootstrap URLs for the intended cluster
     */
    public FatClientWrapper(String storeName, String[] bootstrapURLs) {
        this.fatClientExecutor = new ThreadPoolExecutor(20, // Core pool size
                                                        20, // Max pool size
                                                        60, // Keepalive
                                                        TimeUnit.SECONDS, // Keepalive
                                                                          // Timeunit
                                                        new SynchronousQueue<Runnable>(), // Queue
                                                                                          // for
                                                                                          // pending
                                                                                          // tasks

                                                        new ThreadFactory() {

                                                            @Override
                                                            public Thread newThread(Runnable r) {
                                                                Thread t = new Thread(r);
                                                                t.setName("FatClientExecutor");
                                                                return t;
                                                            }
                                                        },

                                                        new RejectedExecutionHandler() { // Handler

                                                            // for
                                                            // rejected
                                                            // tasks

                                                            @Override
                                                            public void rejectedExecution(Runnable r,
                                                                                          ThreadPoolExecutor executor) {

                                                            }
                                                        });
        this.fatClientRequestQueue = new SynchronousQueue<Future>();

        // Setup the Voldemort client
        ClientConfig clientConfig = new ClientConfig().setBootstrapUrls(bootstrapURLs)
                                                      .setEnableLazy(false)
                                                      .setEnableCompressionLayer(false)
                                                      .setEnableSerializationLayer(false)
                                                      .setEnableInconsistencyResolvingLayer(false)
                                                      .enableDefaultClient(true);

        this.storeClientFactory = new SocketStoreClientFactory(clientConfig);
        this.storeClient = (DefaultStoreClient<Object, Object>) this.storeClientFactory.getStoreClient(storeName);

    }

    /**
     * Interface to do get from the Fat client
     * 
     * @param key: ByteArray representation of the key to get received from the
     *        thin client
     * @param getRequest: MessageEvent to write the response on.
     */
    void submitGetRequest(final ByteArray key, final MessageEvent getRequest) {
        try {

            Future f = this.fatClientExecutor.submit(new GetRequestExecutor(key, null, getRequest));

            // Keep track of this request for monitoring
            // this.fatClientRequestQueue.add(f);
        } catch(RejectedExecutionException rej) {
            handleRejectedException(getRequest);
        }
    }

    /**
     * Interface to perform put operation on the Fat client
     * 
     * @param key: ByteArray representation of the key to put
     * @param value: value corresponding to the key to put
     * @param putRequest: MessageEvent to write the response on.
     */
    void submitPutRequest(final ByteArray key, final byte[] value, final MessageEvent putRequest) {
        try {

            Future f = this.fatClientExecutor.submit(new PutRequestExecutor(key, value, putRequest));

            // Keep track of this request for monitoring
            // this.fatClientRequestQueue.add(f);
        } catch(RejectedExecutionException rej) {
            handleRejectedException(putRequest);
        }
    }

    private void handleRejectedException(MessageEvent getRequest) {
        getRequest.getChannel().write(null); // Write error back to the thin
                                             // client
    }

    private class GetRequestExecutor implements Runnable {

        private ByteArray key;
        private Versioned<Object> defaultValue;
        private MessageEvent getRequest;
        private ChannelBuffer responseContent;

        public GetRequestExecutor(ByteArray key,
                                  Versioned<Object> defaultValue,
                                  MessageEvent request) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.getRequest = request;
        }

        private void writeResponse(List<Versioned<Object>> values) {
            responseContent.writeInt(values.size());
            for(Versioned<Object> v: values) {
                byte[] clock = ((VectorClock) v.getVersion()).toBytes();
                byte[] value = (byte[]) v.getValue();
                responseContent.writeInt(clock.length + value.length);
                responseContent.writeBytes(clock);
                responseContent.writeBytes(value);
            }

            // 1. Create the Response object
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);

            // 2. Set the right headers
            // response.setHeader(CONTENT_TYPE, "text/plain; charset=UTF-8");
            response.setHeader(CONTENT_TYPE, "application/pdf");
            // response.setChunked(true);

            // 3. Copy the data into the payload
            response.setContent(responseContent);
            response.setHeader(CONTENT_LENGTH, response.getContent().readableBytes());

            // Write the response to the Netty Channel
            ChannelFuture future = this.getRequest.getChannel().write(response);

            // Close the non-keep-alive connection after the write operation is
            // done.
            future.addListener(ChannelFutureListener.CLOSE);

        }

        @Override
        public void run() {
            List<Versioned<Object>> results = new ArrayList<Versioned<Object>>();
            Versioned<Object> responseVersioned = storeClient.get(this.key);
            System.out.println("Get successful !");
            if(responseVersioned == null) {
                if(this.defaultValue != null) {
                    responseVersioned = this.defaultValue;
                } else {
                    byte[] nullByteArray = new byte[1];
                    nullByteArray[0] = 0;
                    responseVersioned = new Versioned<Object>(nullByteArray);
                }
            }
            results.add(responseVersioned);
            byte[] responseValue = (byte[]) responseVersioned.getValue();
            this.responseContent = ChannelBuffers.dynamicBuffer(responseValue.length);
            writeResponse(results);
        }

    }

    private class PutRequestExecutor implements Runnable {

        private ByteArray key;
        private byte[] value;
        private MessageEvent putRequest;
        private ChannelBuffer responseContent;

        public PutRequestExecutor(ByteArray key, byte[] value, MessageEvent request) {
            this.key = key;
            this.value = value;
            this.putRequest = request;
        }

        private void writeResponse() {
            // 1. Create the Response object
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);

            // 2. Set the right headers
            // response.setHeader(CONTENT_TYPE, "text/plain; charset=UTF-8");
            response.setHeader(CONTENT_TYPE, "application/pdf");
            // response.setChunked(true);

            // 3. Copy the data into the payload
            response.setContent(responseContent);
            response.setHeader(CONTENT_LENGTH, response.getContent().readableBytes());

            // Write the response to the Netty Channel
            ChannelFuture future = this.putRequest.getChannel().write(response);

            // Close the non-keep-alive connection after the write operation is
            // done.
            future.addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void run() {

            try {
                storeClient.put(key, value);
                System.out.println("Put successful !");
            } catch(ObsoleteVersionException oe) {
                // Ideally propagate the exception !
            }
            this.responseContent = ChannelBuffers.EMPTY_BUFFER;
            writeResponse();
        }

    }
}