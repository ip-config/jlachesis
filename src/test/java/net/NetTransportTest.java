package net;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.time.Duration;
import java.util.HashMap;

import org.jcsp.lang.One2OneChannel;
import org.junit.Test;

import autils.Logger;
import autils.time;
import channel.ExecService;
import common.RResult;
import common.TimedOutEventSelectors;
import common.error;
import node.WaitGroup;
import poset.Block;
import poset.Frame;
import poset.WireBody;

/**
 * Test for NetTransport
 * @author qn
 *
 */
public class NetTransportTest {
	static Logger logger = null; //Logger.getLogger(NetTransportTest.class);

	static final long timeout = 1200 * time.Millisecond;
	static final String errTimeout = "time is over";
	int maxPool = 3;

	protected SyncRequest getExpectedSyncRequest() {
		HashMap<Long,Long> map = new HashMap<Long,Long>();
		map.put(0L, 1L);
		map.put(1L, 2L);
		map.put(2L, 3L);

		return new SyncRequest(0, map);
	}
	protected SyncResponse getExpectedSyncResponse() {
		HashMap<Long,Long> map = new HashMap<Long,Long>();
		map.put(0L, 5L);
		map.put(1L, 5L);
		map.put(2L, 6L);

		WireBody wireBody = new poset.WireBody(null, null, null, 1L, 10L, 0L, 9L, -1L);
		SyncResponse expectedResp = new SyncResponse(1, false,
				new poset.WireEvent[]{new poset.WireEvent(wireBody, null)},	map);
		return expectedResp;
	}

	protected void testSync(One2OneChannel<RPC> rpcCh, Transport trans1, Transport trans2) {
		SyncRequest expectedReq = getExpectedSyncRequest();
		SyncResponse expectedResp = getExpectedSyncResponse();

		ExecService.go(() -> {
			new TimedOutEventSelectors<RPC>(rpcCh) {
				public void onEvent() {
					RPC rpc = ch.in().read();
					SyncRequest req = (SyncRequest) rpc.getCommand();
					assertEquals("Response rpc should match", expectedReq, req);
					rpc.respond(expectedResp, null);
				}
			}.setTimeout(timeout, errTimeout).run();
		});

		SyncResponse resp = new SyncResponse();
		error err = trans2.sync(trans1.localAddr(), expectedReq, resp);
		assertNull("No error when Sync", err);
		assertEquals("Sync response should match", expectedResp, resp);
	}


	protected void testEagerSync(One2OneChannel<RPC> rpcCh, Transport trans1, Transport trans2) {
		WireBody wireBody = new poset.WireBody(null, null, null, 1L, 10L, 0L, 9L, -1L);
		EagerSyncRequest expectedReq = new EagerSyncRequest(0, new poset.WireEvent[]{new poset.WireEvent(wireBody, null)});
		EagerSyncResponse expectedResp = new EagerSyncResponse(1, true);

		ExecService.go(() -> {
			new TimedOutEventSelectors<RPC>(rpcCh) {
				public void onEvent() {
					RPC rpc = ch.in().read();
					EagerSyncRequest req = (EagerSyncRequest) rpc.getCommand();
					assertEquals("Response rpc should match", expectedReq, req);
					rpc.respond(expectedResp, null);
				}
			}.setTimeout(timeout, errTimeout).run();
		});

		EagerSyncResponse resp = new EagerSyncResponse();
		error err = trans2.eagerSync(trans1.localAddr(), expectedReq, resp);
		assertNull("No error when Sync", err);
		assertEquals("EagerSync response should match", expectedResp, resp);
	}

	protected void testFastForward(One2OneChannel<RPC> rpcCh, Transport trans1, Transport trans2) {
		FastForwardRequest expectedReq = new FastForwardRequest(0);

		Frame frame = new Frame();
		RResult<Block> newBlockFromFrame = Block.newBlockFromFrame(1, frame);
		Block block = newBlockFromFrame.result;
		error err = newBlockFromFrame.err;

		assertNull("No error when creating block from frame", err);
		FastForwardResponse expectedResp = new FastForwardResponse (1, block, frame, "snapshot".getBytes());

		ExecService.go(() -> {
			new TimedOutEventSelectors<RPC>(rpcCh) {
				public void onEvent() {
					RPC rpc = ch.in().read();
					FastForwardRequest req = (FastForwardRequest) rpc.getCommand();
					assertEquals("Response rpc should match", expectedReq, req);
					rpc.respond(expectedResp, null);
				}
			}.setTimeout(timeout, errTimeout).run();
		});

		FastForwardResponse resp = new FastForwardResponse();
		err = trans2.fastForward(trans1.localAddr(), expectedReq, resp);
		assertNull("No error when creating block from frame", err);

		assertEquals("response snapshot should match" , expectedResp.Snapshot, resp.Snapshot);
		assertEquals("response id should match" , expectedResp.FromID, resp.FromID);

		assertEquals("Response block should match", resp.Block, expectedResp.Block);
		assertEquals("Response's frame should match", resp.Frame, expectedResp.Frame);
	}

	public void testPooledConn(One2OneChannel<RPC> rpcCh, NetworkTransport trans1, NetworkTransport trans2) {
		SyncRequest expectedReq = getExpectedSyncRequest();
		SyncResponse expectedResp = getExpectedSyncResponse();

		ExecService.go(() -> {
			while(true) {
				new TimedOutEventSelectors<RPC>(rpcCh) {
					public void onEvent() {
						RPC rpc = ch.in().read();
						SyncRequest req = (SyncRequest) rpc.getCommand();
						assertEquals("Response rpc should match", expectedReq, req);
						rpc.respond(expectedResp, null);
					}
				}.setTimeout(timeout, errTimeout).run();
			}
		});

		WaitGroup wg = new WaitGroup();

		// Try to do parallel appends, should stress the conn pool
		int count = maxPool * 2;
		wg.add(count);
		for (int i = 0; i < count; i++) {
			ExecService.go(() -> {
				SyncResponse resp = new SyncResponse();
				error err = trans2.sync(trans1.localAddr(), expectedReq, resp);
				assertNull("No error when Sync", err);
				assertEquals("Response should match", expectedResp, resp);
				wg.done();
			});
		}
		try {
			wg.await();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		// Check the conn pool size
		String addr = trans1.localAddr();
		assertEquals("Length should match", maxPool, trans2.connPool.get(addr).size());
	}

	//@Test
	public void testNetworkTransport() {
		// Transport 1 is consumer
		RResult<NetworkTransport> newTCPTransport = TCPTransport.NewTCPTransport("127.0.0.1:0", null, 2 , Duration.ofSeconds(1), logger);
		NetworkTransport trans1 = newTCPTransport.result;
		error err = newTCPTransport.err;
		assertNull("No error", err);

		One2OneChannel<RPC> rpcCh = trans1.getConsumer();

		// Transport 2 makes outbound request
		RResult<NetworkTransport> newTCPTransport2 = TCPTransport.NewTCPTransport("127.0.0.1:0", null, maxPool, Duration.ofSeconds(1), logger);
		NetworkTransport trans2 = newTCPTransport2.result;
		err = newTCPTransport2.err;
		assertNull("No error", err);

		// "Sync"
		testSync(rpcCh, trans1, trans2);

		// "EagerSync"
		testEagerSync(rpcCh, trans1, trans2);

		// "FastForward"
		testFastForward(rpcCh, trans1, trans2);

		// "PooledConn"
		testPooledConn(rpcCh, trans1, trans2);

		trans2.close();
		trans1.close();
	}

}