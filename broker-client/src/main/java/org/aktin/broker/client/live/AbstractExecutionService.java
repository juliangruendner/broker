package org.aktin.broker.client.live;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;

import org.aktin.broker.client2.BrokerClient2;
import org.aktin.broker.client2.ClientNotificationListener;
import org.aktin.broker.xml.RequestInfo;
import org.aktin.broker.xml.RequestStatus;

import lombok.Getter;
import lombok.extern.java.Log;

/**
 * Abstract execution service running request executions with the provided executor.
 * Once the service is running, it can be stopped via {@link #shutdown()}. To wait for
 * the service to be finished, call {@link #wait()} on this instance. If woken up from {@link #wait()},
 * check {@link #isAborted()} to make sure a shutdown was initiated (and not any other interruption).
 * 
 * @author R.W.Majeed
 *
 * @param <T> request execution implementation
 */
@Log
public abstract class AbstractExecutionService<T extends AbortableRequestExecution> implements Function<Integer, Future<T>>, Closeable {

	@Getter
	protected BrokerClient2 client;
	protected AtomicBoolean abort;
	private ScheduledExecutorService executor;

	private Map<Integer, PendingExecution> pending;

	private ScheduledFuture<?> pingpongTimer;

	public AbstractExecutionService(BrokerClient2 client){
		this.abort = new AtomicBoolean();
		this.client = client;
		this.pending = Collections.synchronizedMap(new HashMap<>());
		this.client.addListener(new ClientNotificationListener() {
			
			@Override
			public void onResourceChanged(String resourceName) {
				// nothing happens
			}
			
			@Override
			public void onRequestPublished(int requestId) {
				// check if request already pending
				addRequest(requestId);
			}
			
			@Override
			public void onRequestClosed(int requestId) {
				cancelRequest(requestId, true);
			}

			@Override
			public void onWebsocketClosed(int statusCode) {
				AbstractExecutionService.this.onWebsocketClosed(statusCode);
			}

			@Override
			public void onPong(String msg) {
				long millis = System.currentTimeMillis()- Long.parseLong(msg);
				log.info("Websocket received pong, roundtrip="+millis);
			}
		});
	}

	protected void setExecutor(ScheduledExecutorService executor) {
		this.executor = executor;
	}

	private class PendingExecution implements Runnable{
		T execution;
		Future<T> future;

		public PendingExecution(T execution) {
			this.execution = execution;
		}
		private void abortLocally() {
			execution.abortLocally();
			if( future != null ) {
				future.cancel(true);
			}
		}
		@Override
		public void run() {
			execution.run();
			onFinished(this);
		}
	}

	/**
	 * Set a timer for client initiated ping-pong messages. Per default, the ping-pong timer is disabled.
	 * @param timerMillis Delay between pings in milliseconds. Set to {@code 0} to disable. Any positive value enables the timer.
	 */
	public void setWebsocketPingPongTimer(long timerMillis) {
		if( timerMillis < 0 ) {
			throw new IllegalArgumentException("Negative timer delay not allowed");
		}
		if( this.pingpongTimer != null ) {
			this.pingpongTimer.cancel(false);
			this.pingpongTimer = null;
		}
		if( timerMillis > 0 ) {
			this.pingpongTimer = executor.scheduleWithFixedDelay(this::sendWebsocketPing, timerMillis, timerMillis,TimeUnit.MILLISECONDS);
		}
	}

	/**
	 * Method will be called by scheduled executor thread to send a websocket ping.
	 */
	private void sendWebsocketPing() {
		if( client.getWebsocket() == null || client.getWebsocket().isOutputClosed() ) {
			// client websocket disconnected. try to reconnect
			log.info("Websocket ping skipped because websocket is closed. Trying reconnect.");
			try {
				client.connectWebsocket();
			} catch (IOException e) {
				log.warning("Websocket ping reconnect failed: "+e.getMessage());
			}
		}else {
			try {
				client.getWebsocket().sendText("ping "+System.currentTimeMillis(), true).get();
				log.info("Websocket ping sent");
			} catch (InterruptedException e) {
				log.info("Websocket ping interrupted");
			} catch (ExecutionException e) {
				log.log(Level.WARNING, "Websocket ping failed", e.getCause());
			} catch( CancellationException e ) {
				log.log(Level.WARNING, "Websocket ping canceled");
			} catch( Throwable e ) {
				log.log(Level.SEVERE, "Unexpected websocket failure", e);
			}
		}
	}
	
	public boolean isAborted() {
		return abort.get();
	}
	/**
	 * Load the previous executions. Implementation might call {@link #pollRequests()} to load
	 * the execution queue from the server. 
	 * Alternatively, local persistence can be used to load requests e.g. from a database.
	 * @throws IOException IO error during loading of queue
	 */
	public abstract void loadQueue() throws IOException;

	public boolean isWebsocketClosed() {
		return client.getWebsocket() == null || client.getWebsocket().isInputClosed();
	}
	/**
	 * Start the websocket listener to retrieve live updates about published or closed requests.
	 * To close the websocket connection, call shutdown or close
	 * @throws IOException
	 */
	public void startupWebsocketListener() throws IOException {
		if( client.getWebsocket() != null ) {
			// close previous websocket
			client.closeWebsocket();
		}
		client.connectWebsocket();
	}
	/**
	 * Abort the executor by shutting down the websocket and aborting all
	 * pending and running executions.
	 * The method will also call {@link #notifyAll()} to notify threads waiting
	 * for a successful shutdown. If woken up, check {@link #isAborted()} whether
	 * a shutdown is in progress.
	 */
	@SuppressWarnings("unchecked")
	public List<T> shutdown() {
		client.closeWebsocket();
		this.abort.set(true);
		List<Runnable> aborted = executor.shutdownNow();
		// extract executions from local wrapper PendingExecution
		List<T> list = new ArrayList<>(aborted.size());
		aborted.forEach( (r) -> {
			if( r instanceof AbstractExecutionService.PendingExecution){
				list.add(((PendingExecution)r).execution);
			}
		} );
		onShutdown(list);

		// notify threads waiting on this object
		synchronized( this ) {
			this.notifyAll();
		}

		return list;
	}

	@Override /* allowed to throw IOException, but we don't */
	public void close() {
		shutdown();
	}

	/**
	 * This method is called during shutdown and can be used to persist the list of unprocessed
	 * executions. During next startup, {@link #loadQueue()} could be used to load and resume the queue. 
	 * @param unprocessedExecutions list of unprocessed executions waiting in the queue.
	 */
	protected abstract void onShutdown(List<T> unprocessedExecutions);
	protected abstract void onStatusUpdate(T execution, RequestStatus status);
	protected abstract void onWebsocketClosed(int status);

	protected abstract T initializeExecution(Integer requestId);

	/**
	 * Callback once the execution is finished. Used to maintain our local queue/cache.
	 * @param execution execution just finished
	 */
	private void onFinished(PendingExecution p) {
		pending.remove(p.execution.getRequestId());
	}

	/**
	 * Add a pending request. This method will be automatically called by the websocket {@code request published} notification.
	 * @param requestId request id to add/execute
	 * @return Future for the request. If the request is already pending, the previous (unfinished) future is returned.
	 */
	@SuppressWarnings("unchecked")
	public Future<T> addRequest(Integer requestId){
		// if request already pending, return existing future
		PendingExecution p = pending.get(requestId);
		if( p == null ) {
			// request not pending previously
			// this is the normal case
			p = new PendingExecution(initializeExecution(requestId));
			p.execution.setClient(client);
			p.execution.setGlobalAbort(abort::get);
			p.execution.setStatusListener((e,s) -> this.onStatusUpdate((T)e, s));
			pending.put(requestId, p);
			this.onStatusUpdate(p.execution, RequestStatus.queued);
			p.future = executor.submit(p, p.execution);
//			alternatively, we could also return CompletableFuture: CompletableFuture.runAsync(p, executor).thenApply((x) -> p.execution);
		}
		return p.future;
	}

	/**
	 * Cancel a pending request. This method will be automatically called by the websocket {@code request closed} notification.
	 * @param requestId request id to cancel
	 * @param expired whether the request expired from the server point of view e.g. closed by the broker. If set to {@code false} the remote status will be updated to rejected.
	 * @return Future for the request if still pending. If the future is not pending anymore (e.g. already finished), then {@code null} is returned.
	 */
	public Future<T> cancelRequest(Integer requestId, boolean expired) {
		PendingExecution p = pending.get(requestId);
		if( p != null ) {
			p.abortLocally();
			if( expired == true ) {
				onStatusUpdate(p.execution, RequestStatus.expired);
			}else {
				// TODO report status update to broker
				onStatusUpdate(p.execution, RequestStatus.rejected);
			}
			return p.future;
		}else {
			return null;
		}
	}

	@Override
	public Future<T> apply(Integer requestId) {
		return addRequest(requestId);
	}


	/**
	 * Poll the server for new requests.
	 * @throws IOException IO error during polling
	 */
	public void pollRequests() throws IOException {
		List<RequestInfo> list = client.listMyRequests();
		log.log(Level.INFO,"Retrieved {0} requests",list.size());
		for( RequestInfo request : list ) {
			if( request.closed != null ) {
				// closed request. see if it is in the queue
				PendingExecution p = pending.get(request.getId());
				if( p != null ) {
					p.abortLocally();
				}else {
					// we don't have the execution in our queue
					;// nothing to do
				}
				client.deleteMyRequest(request.getId());
			}else{
				// request still open, check if it is already queued
				PendingExecution p = pending.get(request.getId());
				if( p == null ) {
					// add request to queue
					addRequest(request.getId());
				}else {
					// request already queued
					;// nothing to do
				}
				
			}
		}
		// TODO there might be requests queued, which are no longer on the server. these should be canceled too
	}
}
