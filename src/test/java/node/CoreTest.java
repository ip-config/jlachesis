package node;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import autils.Appender;
import autils.Logger;
import common.Hash32;
import common.RResult;
import common.RResult3;
import common.error;
import crypto.Utils;
import peers.Peer;
import peers.Peers;
import poset.Block;
import poset.BlockSignature;
import poset.Event;
import poset.Frame;
import poset.InmemStore;
import poset.WireEvent;

/**
 * Test for Core
 * @author qn
 *
 */
public class CoreTest {
	Core[] cores;
	Map<String,String> index;
	Map<Long, KeyPair> participantKeys;

	private static Logger logger = Logger.getLogger(CoreTest.class);

	@Test
	public void TestEventDiff() {
		initCores(3);

		initPoset(cores, participantKeys, index, 0);

		/*
		  P0 knows

		  |  e12  |
		  |   | \ |
		  |   |   e20
		  |   | / |
		  |   /   |
		  | / |   |
		  e01 |   |        P1 knows
		  | \ |   |
		  e0  e1  e2       |   e1  |
		  0   1   2        0   1   2
		*/

		Map<Long,Long> knownBy1 = cores[1].knownEvents();
		RResult<poset.Event[]> eventDiff = cores[0].eventDiff(knownBy1);
		Event[] unknownBy1 = eventDiff.result;
		error err = eventDiff.err;
		assertNull("No error when event diff", err);

		assertEquals("length of unknown should be 5", 5, unknownBy1.length);

		String[] expectedOrder = new String[]{"e0", "e2", "e01", "e20", "e12"};
		for (int i =0; i< unknownBy1.length; ++i) {
			Event e = unknownBy1[i];
			String name = getName(index, e.hex());
			assertEquals(String.format("element %d should match",
				i), expectedOrder[i], name);
		}

	}

	@Test
	public void TestSync() {
		initCores(3);

		/*
		   core 0           core 1          core 2

		   e0  |   |        |   e1  |       |   |   e2
		   0   1   2        0   1   2       0   1   2
		*/

		HashMap<String,Long>[] expectedHeights = new HashMap[3];

		HashMap<String, Long> temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 1L);
		temp.put(cores[1].hexID, 0L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[0] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[1] = temp;


		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 0L);
		temp.put(cores[2].hexID, 1L);
		expectedHeights[2] = temp;

		checkHeights(cores, expectedHeights);

		// core 1 is going to tell core 0 everything it knows
		error err = synchronizeCores(cores, 1, 0, new byte[][]{});
		assertNull("no error synchronize core", err);

		/*
		   core 0           core 1          core 2

		   e01 |   |
		   | \ |   |
		   e0  e1  |        |   e1  |       |   |   e2
		   0   1   2        0   1   2       0   1   2
		*/

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[0] = temp;


		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[1] = temp;


		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 0L);
		temp.put(cores[2].hexID, 1L);
		expectedHeights[2] = temp;

		checkHeights(cores, expectedHeights);

		Map<Long,Long> knownBy0 = cores[0].knownEvents();

		long k = knownBy0.get((long) Hash32.Hash32(cores[0].pubKey));
		assertEquals("core 0 should have last-index 1 for core 0, not %d", 1, k);

		k = knownBy0.get((long) Hash32.Hash32(cores[1].pubKey));
		assertEquals("core 0 should have last-index 0 for core 1, not %d", 0, k);

		k = knownBy0.get((long) Hash32.Hash32(cores[2].pubKey));
		assertEquals("core 0 should have last-index -1 for core 2, not %d", -1, k);

		Event core0Head = cores[0].getHead().result;
		assertEquals("core 0 head self-parent should be e0", core0Head.selfParent(), index.get("e0"));

		assertEquals("core 0 head other-parent should be e1", core0Head.otherParent(), index.get("e1"));

		assertNotNull("flag table is not null", core0Head.getMessage().GetFlagTable());

		index.put("e01", core0Head.hex());

		// core 0 is going to tell core 2 everything it knows
		err = synchronizeCores(cores, 0, 2, new byte[][]{});
		assertNull("No error when core 0 synchronize to core 2", err);

		/*
		   core 0           core 1          core 2

		                                    |   |  e20
		                                    |   | / |
		                                    |   /   |
		                                    | / |   |
		   e01 |   |                        e01 |   |
		   | \ |   |                        | \ |   |
		   e0  e1  |        |   e1  |       e0  e1  e2
		   0   1   2        0   1   2       0   1   2
		*/

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[0] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[1] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 2L);
		expectedHeights[2] = temp;

		checkHeights(cores, expectedHeights);

		Map<Long, Long> knownBy2 = cores[2].knownEvents();

		k = knownBy2.get((long) Hash32.Hash32(cores[0].pubKey));
		assertEquals("core 2 should have last-index 1 for core 0, not %d", 1, k);

		k = knownBy2.get((long) Hash32.Hash32(cores[1].pubKey));
		assertEquals("core 2 should have last-index 0 core 1, not %d", 0, k);

		k = knownBy2.get((long) Hash32.Hash32(cores[2].pubKey));
		assertEquals("core 2 should have last-index 1 for core 2, not %d", 1, k);

		Event core2Head = cores[2].getHead().result;
		assertEquals("core 2 head self-parent should be e2", core2Head.selfParent(), index.get("e2"));

		assertEquals("core 2 head other-parent should be e01", core2Head.otherParent(),index.get("e01"));


		index.put("e20", core2Head.hex());

		// core 2 is going to tell core 1 everything it knows
		err = synchronizeCores(cores, 2, 1, new byte[][]{});
		assertNull("No error when core 2 synchronize to core 1", err);

		/*
		   core 0           core 1          core 2

		                    |  e12  |
		                    |   | \ |
		                    |   |  e20      |   |  e20
		                    |   | / |       |   | / |
		                    |   /   |       |   /   |
		                    | / |   |       | / |   |
		   e01 |   |        e01 |   |       e01 |   |
		   | \ |   |        | \ |   |       | \ |   |
		   e0  e1  |        e0  e1  e2      e0  e1  e2
		   0   1   2        0   1   2       0   1   2
		*/

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[0] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 2L);
		temp.put(cores[2].hexID, 2L);
		expectedHeights[1] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 2L);
		expectedHeights[2] = temp;

		checkHeights(cores, expectedHeights);

		Map<Long, Long> knownBy1 = cores[1].knownEvents();
		k = knownBy1.get((long) Hash32.Hash32(cores[0].pubKey));
		assertEquals("core 1 should have last-index 1 for core 0, not %d", 1, k);

		k = knownBy1.get((long) Hash32.Hash32(cores[1].pubKey));
		assertEquals("core 1 should have last-index 1 for core 1, not %d", 1, k);

		k = knownBy1.get((long) Hash32.Hash32(cores[2].pubKey));
		assertEquals("core 1 should have last-index 1 for core 2, not %d", 1, k);

		Event core1Head = cores[1].getHead().result;
		assertEquals("core 1 head self-parent should be e1", core1Head.selfParent(), index.get("e1"));

		assertEquals("core 1 head other-parent should be e20", core1Head.otherParent(), index.get("e20"));

		index.put("e12", core1Head.hex());
	}


	private void initCores(int n) {
		cores = null;
		index = new HashMap<String,String>();
		participantKeys = new HashMap<Long, KeyPair>();

		int cacheSize = 1000;

		Peers participants = new Peers();
		for (int i = 0; i < n; i++) {
			KeyPair key = crypto.Utils.GenerateECDSAKeyPair().result;
//			pubHex := String.format("0x%X", crypto.FromECDSAPub(&key.PublicKey));
			String pubHex = Utils.keyToHexString(key.getPublic());
			Peer peer = new Peer(pubHex, "");
			participants.addPeer(peer);
			participantKeys.put(peer.getID(), key);
		}

		Peer[] peers = participants.toPeerSlice();
		for (int i = 0; i < peers.length; ++i) {
			Peer peer = peers[i];
			Core core = new Core((long) i,
				participantKeys.get(peer.getID()),
				participants,
				new InmemStore(participants, cacheSize),
				null,
				logger );

			String selfParent = String.format("Root%d", peer.getID());

			HashMap<String, Long> flagTable = new HashMap<String,Long>();
			flagTable.put(selfParent, 1L);

			// Create and save the first Event
			Event initialEvent = new Event(null,
				new poset.InternalTransaction[]{},
				null,
				new String[]{selfParent, ""}, core.pubKey(), 0, flagTable);
			error err = core.signAndInsertSelfEvent(initialEvent);

			assertNull("No error when SignAndInsertSelfEvent", err);

			core.runConsensus();

			cores = Appender.append(cores, core);
			index.put(String.format("e%d", i), core.head);
		}
	}

	/*
	|  e12  |
	|   | \ |
	|   |   e20
	|   | / |
	|   /   |
	| / |   |
	e01 |   |
	| \ |   |
	e0  e1  e2
	0   1   2
	*/
	private void initPoset(Core[] cores, Map<Long, KeyPair> keys,
		Map<String,String> index, int participant) {
		error err;
		for (int i = 0; i < cores.length; i++) {
			if (i != participant) {
				RResult<Event> getEvent = cores[i].getEvent(index.get(String.format("e%d", i)));
				Event event = getEvent.result;
				err = getEvent.err;
				assertNull("No error getEvent", err);

				err = cores[participant].insertEvent(event, true);
				assertNull("No error inserting " + getName(index, event.hex()), err);
			}
		}

		// Get flag tables from parents
		RResult<Event> getEvent = cores[0].poset.Store.getEvent(index.get("e0"));
		Event event0 = getEvent.result;
		err = getEvent.err;
		assertNull("No error to get parent", err);

		RResult<Event> getEventCall1 = cores[0].poset.Store.getEvent(index.get("e1"));

		Event event1 = getEventCall1.result;
		err = getEventCall1.err;
		assertNull("No error when get parent", err);

		Map<String, Long> event1ft = event1.getFlagTable().result;
		Map<String, Long> event01ft = event0.mergeFlagTable(event1ft).result;

		Event event01 = new Event(new byte[][]{},
			new poset.InternalTransaction[]{},
			null,
			new String[]{index.get("e0"), index.get("e1")}, // e0 and e1
			cores[0].pubKey(), 1, event01ft);

		err = insertEvent(cores, keys, index, event01, "e01", participant,
				Hash32.Hash32(cores[0].pubKey));
		assertNull("No error inserting e01", err);

		// Get flag tables from parents
		RResult<Event> getEventCall2 = cores[2].poset.Store.getEvent(index.get("e2"));
		Event event2 = getEventCall2.result;
		err = getEventCall2.err;
		assertNull("No error get parent", err);

		Map<String, Long> event20ft = event2.mergeFlagTable(event01ft).result;

		Event event20 = new Event(new byte[][]{},
			new poset.InternalTransaction[]{},
			null,
			new String[]{index.get("e2"), index.get("e01")}, // e2 and e01
			cores[2].pubKey(), 1, event20ft);

		err = insertEvent(cores, keys, index, event20, "e20", participant,
				Hash32.Hash32(cores[2].pubKey));
		assertNull("No error inserting e20", err);

		Map<String, Long> event12ft = event1.mergeFlagTable(event20ft).result;

		Event event12 = new Event(new byte[][]{},
			new poset.InternalTransaction[]{},
			null,
			new String[]{index.get("e1"), index.get("e20")}, // e1 and e20
			cores[1].pubKey(), 1, event12ft);
		err = insertEvent(cores, keys, index, event12, "e12", participant,
				Hash32.Hash32(cores[1].pubKey));
		assertNull("No error inserting e12", err);
	}

	private error insertEvent(Core[] cores, Map<Long,KeyPair> keys,
		Map<String,String> index, Event event, String name, int participant,
		long creator)  {
		error err;
		if (participant == creator) {
			err = cores[participant].signAndInsertSelfEvent(event);
			if (err != null) {
				return err;
			}
			// event is not signed because passed by value
			index.put(name, cores[participant].head);
		} else {
			event.sign(keys.get(creator).getPrivate());
			err = cores[participant].insertEvent(event, true);
			if (err != null) {
				return err;
			}
			index.put(name, event.hex());
		}
		return null;
	}

	private void checkHeights(Core[] cores, Map<String,Long>[] expectedHeights) {
		for (int i = 0; i< cores.length; ++i) {
			Core core = cores[i];
			Map<String, Long> heights = core.heights();
			assertEquals(String.format("Cores[%d].Heights() should match",
					i), expectedHeights[i], heights);
		}
	}


	private void checkInDegree(Core[] cores, Map<String,Long>[] expectedInDegree) {
		for (int i= 0; i< cores.length; ++i) {
			Core core = cores[i];
			Map<String, Long> inDegrees = core.inDegrees();
			assertEquals(String.format("Cores[%d].InDegrees() should match",
				i), expectedInDegree[i], inDegrees);
		}
	}

	@Test
	public void testInDegrees() {
		initCores(3);

		/*
		   core 0           core 1          core 2

		   e0  |   |        |   e1  |       |   |   e2
		   0   1   2        0   1   2       0   1   2
		*/

		// core 1 is going to tell core 0 everything it knows
		error err = synchronizeCores(cores, 1, 0, new byte[][]{});
		assertNull("No error synchronizing core 1 to core 0", err);

		/*
		   core 0           core 1          core 2

		   e01 |   |
		   | \ |   |
		   e0  e1  |        |   e1  |       |   |   e2
		   0   1   2        0   1   2       0   1   2
		*/

		HashMap<String,Long>[] expectedHeights = new HashMap[3];

		HashMap<String,Long> temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[0] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[1] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 0L);
		temp.put(cores[2].hexID, 1L);
		expectedHeights[2] = temp;

		checkHeights(cores, expectedHeights);

		HashMap<String,Long>[] expectedInDegree = new HashMap[3];

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedInDegree[0] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 0L);
		temp.put(cores[2].hexID, 0L);
		expectedInDegree[1] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 0L);
		temp.put(cores[2].hexID, 0L);
		expectedInDegree[2] = temp;

		checkInDegree(cores, expectedInDegree);

		// core 1 is going to tell core 2 everything it knows
		err = synchronizeCores(cores, 1, 2, new byte[][]{});
		assertNull("No error synchronizing core 1 to core 2", err);

		/*
		   core 0           core 1          core 2

		   e01 |   |                        |   |  e21
		   | \ |   |                        |   | / |
		   e0  e1  |        |   e1  |       e0  e1  e2
		   0   1   2        0   1   2       0   1   2
		*/

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[0] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[1] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 2L);
		expectedHeights[2] = temp;

		checkHeights(cores, expectedHeights);

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedInDegree[0] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 0L);
		temp.put(cores[2].hexID, 0L);
		expectedInDegree[1] = temp;


		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedInDegree[2] = temp;

		checkInDegree(cores, expectedInDegree);

		// core 0 is going to tell core 2 everything it knows
		err = synchronizeCores(cores, 0, 2, new byte[][]{});
		assertNull("No error synchronizing core 0 to core 2", err);

		/*

		   core 0           core 1          core 2

		                                    |   |  e20
		                                    |   | / |
		                                    |   /   |
		                                    | / |   |
		   e01 |   |                        e01 |  e21
		   | \ |   |                        | \ | / |
		   e0  e1  |        |   e1  |       e0  e1  e2
		   0   1   2        0   1   2       0   1   2
		*/
		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[0] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[1] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 3L);
		expectedHeights[2] = temp;

		checkHeights(cores, expectedHeights);

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedInDegree[0] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 0L);
		temp.put(cores[2].hexID, 0L);
		expectedInDegree[1] = temp;


		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 1L);
		temp.put(cores[1].hexID, 2L);
		temp.put(cores[2].hexID, 0L);
		expectedInDegree[2] = temp;

		checkInDegree(cores, expectedInDegree);

		// core 2 is going to tell core 1 everything it knows
		err = synchronizeCores(cores, 2, 1, new byte[][]{});
		assertNull("No error synchronizing core 2 to core 1", err);

		/*

		   core 0           core 1          core 2

		                    |  e12  |
		                    |   | \ |
		                    |   |  e20      |   |  e20
		                    |   | / |       |   | / |
		                    |   /   |       |   /   |
		                    | / |   |       | / |   |
		   e01 |   |        e01 |  e21      e01 |  e21
		   | \ |   |        | \ | / |       | \ | / |
		   e0  e1  |        e0  e1  e2      e0  e1  e2
		   0   1   2        0   1   2       0   1   2
		*/
		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedHeights[0] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 2L);
		temp.put(cores[2].hexID, 3L);
		expectedHeights[1] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 2L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 3L);
		expectedHeights[2] = temp;

		checkHeights(cores, expectedHeights);

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 0L);
		temp.put(cores[1].hexID, 1L);
		temp.put(cores[2].hexID, 0L);
		expectedInDegree[0] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 1L);
		temp.put(cores[1].hexID, 0L);
		temp.put(cores[2].hexID, 1L);
		expectedInDegree[1] = temp;

		temp = new HashMap<String,Long>();
		temp.put(cores[0].hexID, 1L);
		temp.put(cores[1].hexID, 2L);
		temp.put(cores[2].hexID, 0L);
		expectedInDegree[2] = temp;

		checkInDegree(cores, expectedInDegree);
	}

	/*
	h0  |   h2
	| \ | / |
	|   h1  |
	|  /|   |--------------------
	g02 |   | R2
	| \ |   |
	|   \   |
	|   | \ |
	|   |  g21
	|   | / |
	|  g10  |
	| / |   |
	g0  |   g2
	| \ | / |
	|   g1  |
	|  /|   |--------------------
	f02 |   | R1
	| \ |   |
	|   \   |
	|   | \ |
	|   |  f21
	|   | / |
	|  f10  |
	| / |   |
	f0  |   f2
	| \ | / |
	|   f1  |
	|  /|   |--------------------
	e02 |   | R0 Consensus
	| \ |   |
	|   \   |
	|   | \ |
	|   |  e21
	|   | / |
	|  e10  |
	| / |   |
	e0  e1  e2
	0   1    2
	*/
	class play {
		int from;
		int to;
		byte[][] payload;
		public play(int from, int to, byte[][] payload) {
			super();
			this.from = from;
			this.to = to;
			this.payload = payload;
		}
	}

	private Core[] initConsensusPoset() {
		initCores(3);
		play[] playbook = new play[]{
			new play(0, 1, new  byte[][]{"e10".getBytes()}),
			new play(1, 2, new  byte[][]{"e21".getBytes()}),
			new play(2, 0, new  byte[][]{"e02".getBytes()}),
			new play(0, 1, new  byte[][]{"f1".getBytes()}),
			new play(1, 0, new  byte[][]{"f0".getBytes()}),
			new play(1, 2, new  byte[][]{"f2".getBytes()}),
			new play(0, 1, new  byte[][]{"f10".getBytes()}),
			new play(1, 2, new  byte[][]{"f21".getBytes()}),
			new play(2, 0, new  byte[][]{"f02".getBytes()}),
			new play(0, 1, new  byte[][]{"g1".getBytes()}),
			new play(1, 0, new  byte[][]{"g0".getBytes()}),
			new play(1, 2, new  byte[][]{"g2".getBytes()}),
			new play(0, 1, new  byte[][]{"g10".getBytes()}),
			new play(1, 2, new  byte[][]{"g21".getBytes()}),
			new play(2, 0, new  byte[][]{"g02".getBytes()}),
			new play(0, 1, new  byte[][]{"h1".getBytes()}),
			new play(1, 0, new  byte[][]{"h0".getBytes()}),
			new play(1, 2, new  byte[][]{"h2".getBytes()})
		};

		for (play play : playbook) {
			error err = syncAndRunConsensus(
					cores, play.from, play.to, play.payload);
			assertNull("No error synAndRunConsensus", err);
		}

		return cores;
	}

	//@Test
	public void testConsensus() {
		cores = initConsensusPoset();

		int l = cores[0].getConsensusEvents().length;
		assertEquals("length of consensus should match", 4, l);

		String[] core0Consensus = cores[0].getConsensusEvents();
		String[] core1Consensus = cores[1].getConsensusEvents();
		String[] core2Consensus = cores[2].getConsensusEvents();

		for (int i = 0; i < core0Consensus.length; ++i) {
			String e = core0Consensus[i];
			assertEquals(String.format("core 1 consensus[%d] should match core 0's", i), core1Consensus[i], e);
			assertEquals(String.format("core 2 consensus[%d] should match core 0's", i), core2Consensus[i], e);
		}
	}

	@Test
	public void testOverSyncLimit() {
		cores = initConsensusPoset();

		// positive
		HashMap<Long, Long> known = new HashMap<Long,Long>();
		known.put((long) Hash32.Hash32(cores[0].pubKey), 1L);
		known.put((long) Hash32.Hash32(cores[1].pubKey), 1L);
		known.put((long) Hash32.Hash32(cores[2].pubKey), 1L);

		long syncLimit = 10L;

		boolean overSyncLimit = cores[0].overSyncLimit(known, syncLimit);
		assertTrue(String.format("OverSyncLimit(%s, %d) should return true", known, syncLimit), overSyncLimit);

		// negative
		known = new HashMap<Long,Long>();
		known.put((long) Hash32.Hash32(cores[0].pubKey), 6L);
		known.put((long) Hash32.Hash32(cores[1].pubKey), 6L);
		known.put((long) Hash32.Hash32(cores[2].pubKey), 6L);

		overSyncLimit = cores[0].overSyncLimit(known, syncLimit);
		assertFalse(String.format("OverSyncLimit(%s, %s) should return false", known, syncLimit), overSyncLimit);

		// edge
		known = new HashMap<Long,Long>();
		known.put((long) Hash32.Hash32(cores[0].pubKey), 2L);
		known.put((long) Hash32.Hash32(cores[1].pubKey), 3L);
		known.put((long) Hash32.Hash32(cores[2].pubKey), 3L);

		overSyncLimit = cores[0].overSyncLimit(known, syncLimit);
		assertFalse(String.format("OverSyncLimit(%s, %s) should return false", known, syncLimit), overSyncLimit);
	}

	/*
	    |   |   |   |-----------------
		|   w31 |   | R3
		|	| \ |   |
	    |   |  w32  |
	    |   |   | \ |
	    |   |   |  w33
	    |   |   | / |-----------------
	    |   |  g21  | R2
		|   | / |   |
		|  w21  |   |
		|	| \ |   |
	    |   |  w22  |
	    |   |   | \ |
	    |   |   |  w23
	    |   |   | / |-----------------
	    |   |  f21  | R1
		|   | / |   | LastConsensusRound
		|  w11  |   |
		|	| \ |   |
	    |   |   \   |
	    |   |   | \ |
		|   |   |  w13
		|   |   | / |
	   FSE  |  w12  | FSE is only added after FastForward
	    |\  | / |   | -----------------
	    |  e13  |   | R0
		|	| \ |   |
	    |   |   \   |
	    |   |   | \ |
	    |   |   |  e32
	    |   |   | / |
	    |   |  e21  | All Events in Round 0 are Consensus Events.
	    |   | / |   |
	    |   e1  e2  e3
	    0	1	2	3
	*/
	private void initFFPoset(Core[] cores) {
		play[] playbook = new play[]{
			new play(1, 2, new byte[][]{"e21".getBytes()}),
			new play(2, 3, new byte[][]{"e32".getBytes()}),
			new play(3, 1, new byte[][]{"e13".getBytes()}),
			new play(1, 2, new byte[][]{"w12".getBytes()}),
			new play(2, 3, new byte[][]{"w13".getBytes()}),
			new play(3, 1, new byte[][]{"w11".getBytes()}),
			new play(1, 2, new byte[][]{"f21".getBytes()}),
			new play(2, 3, new byte[][]{"w23".getBytes()}),
			new play(3, 2, new byte[][]{"w22".getBytes()}),
			new play(2, 1, new byte[][]{"w21".getBytes()}),
			new play(1, 2, new byte[][]{"g21".getBytes()}),
			new play(2, 3, new byte[][]{"w33".getBytes()}),
			new play(3, 2, new byte[][]{"w32".getBytes()}),
			new play(2, 1, new byte[][]{"w31".getBytes()}),
		};

		for (int k = 0; k< playbook.length; ++k) {
			play play = playbook[k];
			error err = syncAndRunConsensus(cores, play.from, play.to, play.payload);
			assertNull(String.format("No error when syncAndRunConsensus play %d", k), err);
		}
	}

	//@Test
	public void testConsensusFF() {
		initCores(4);
		initFFPoset(cores);

		long r = cores[1].getLastConsensusRoundIndex();
		assertEquals("Cores[1] last consensus Round should be 1", 1, r);

		int l = cores[1].getConsensusEvents().length;
		assertEquals("Node 1 should have 7 consensus eventts", 7, l);

		String[] core1Consensus = cores[1].getConsensusEvents();
		String[] core2Consensus = cores[2].getConsensusEvents();
		String[] core3Consensus = cores[3].getConsensusEvents();

		for (int i =0; i < core1Consensus.length; ++i) {
			String e = core1Consensus[i];
			assertEquals(String.format("Node 2 consensus[%d] should match Node 1's", i), core2Consensus[i], e);
			assertEquals(String.format("Node 3 consensus[%d] should match Node 1's", i), core3Consensus[i], e);
		}
	}

	//@Test
	public void testCoreFastForward() {
		initCores(4);
		initFFPoset(cores);

		// Test no anchor block
		error err = cores[1].getAnchorBlockWithFrame().err;
		assertNotNull("GetAnchorBlockWithFrame should throw an error" +
				" because there is no anchor block yet", err);

		RResult<Block> getBlock = cores[1].poset.Store.getBlock(0);
		Block block0 = getBlock.result;
		err = getBlock.err;
		assertNull("No error when GetBlock 0", err);

		// collect signatures
		BlockSignature[] signatures = new BlockSignature[3];
		for (int k = 1; k< cores.length; ++k) {
			Core c = cores[k];
			RResult<Block> getBlock2 = c.poset.Store.getBlock(0);
			Block b = getBlock2.result;
			err = getBlock2.err;
			assertNull("No error when GetBlock 0", err);

			RResult<BlockSignature> signBlock = c.SignBlock(b);
			BlockSignature sig = signBlock.result;
			err = signBlock.err;
			assertNull("No error when SignBlock b=" + b, err);
			signatures[k] = sig;
		}

		// "Test not enough signatures"

		// Append only 1 signatures
		err = block0.setSignature(signatures[0]);
		assertNull("No error when SetSignature 0", err);

		// Save Block
		err = cores[1].poset.Store.setBlock(block0);
		assertNull("No error when SetBlock 0" + block0, err);

		// Assign AnchorBlock
		cores[1].poset.setAnchorBlock(0L);

			// Now the publiction should find an AnchorBlock
		RResult3<Block, Frame> getAnchorBlockWithFrame = cores[1].getAnchorBlockWithFrame();
		Block block = getAnchorBlockWithFrame.result1;
		Frame frame = getAnchorBlockWithFrame.result2;
		err = getAnchorBlockWithFrame.err;
		assertNull("No error when getAnchorBlockWithFrame()", err);

		err = cores[0].fastForward(cores[1].hexID, block, frame);
		// We should get an error because AnchorBlock doesnt contain enough
		// signatures
		assertNotNull("FastForward should throw an error because the Block" +
			" does not contain enough signatures", err);


		// "Test positive"
		// Append the 2nd and 3rd signatures
		for (int i = 1; i < 3; i++) {
			err = block0.setSignature(signatures[i]);
			assertNull("No error when SetSignature()", err);
		}

		// Save Block
		err = cores[1].poset.Store.setBlock(block0);
		assertNull("No error when SetBlock() of " + block0, err);

		RResult3<Block, Frame> getAnchorBlockWithFrame2 = cores[1].getAnchorBlockWithFrame();
		block = getAnchorBlockWithFrame2.result1;
		frame = getAnchorBlockWithFrame2.result2;
		err = getAnchorBlockWithFrame2.err;
		assertNull("No error when GetAnchorBlockWithFrame() of core1", err);

		err = cores[0].fastForward(cores[1].hexID, block, frame);
		assertNull("No error when FastForward() of core 0", err);

		Map<Long, Long> knownBy0 = cores[0].knownEvents();
		assertNotNull("Map of KnownEvents() of core 0", knownBy0);

		HashMap<Long, Long> expectedKnown = new HashMap<Long,Long>();
		expectedKnown.put((long) Hash32.Hash32(cores[0].pubKey), -1L);
		expectedKnown.put((long) Hash32.Hash32(cores[1].pubKey), 0L);
		expectedKnown.put((long) Hash32.Hash32(cores[2].pubKey), 1L);
		expectedKnown.put((long) Hash32.Hash32(cores[3].pubKey), 0L);

		assertEquals("Cores[0].Known should match", expectedKnown, knownBy0);

		long r = cores[0].getLastConsensusRoundIndex();
		assertEquals("Cores[0] last consensus Round should be 1", 1, r);

		long lbi = cores[0].poset.Store.lastBlockIndex();
		assertEquals("Cores[0].poset.LastBlockIndex should be 0", 0, lbi);

		RResult<Block> getBlock2 = cores[0].poset.Store.getBlock(block.Index());
		Block sBlock = getBlock2.result;
		err = getBlock2.err;
		assertNull("No Error retrieving latest Block from reset poset", err);

		assertEquals("Blocks match", sBlock.getBody(), block.getBody());

		RResult3<String, Boolean> lastEventFrom = cores[0].poset.Store.lastEventFrom(cores[0].hexID);
		String lastEventFrom0 = lastEventFrom.result1;
		err = lastEventFrom.err;
		assertNull("No Error when call LastEventFrom", err);

		String c0h = cores[0].head();
		assertEquals("Head should be match", lastEventFrom0, c0h);

		long c0s = cores[0].Seq;
		assertEquals("Seq should be -1", -1, c0s);
	}

	private error synchronizeCores(Core[] cores, int from, int to, byte[][] payload) {
		Map<Long, Long> knownByTo = cores[to].knownEvents();
		RResult<Event[]> eventDiff = cores[from].eventDiff(knownByTo);
		Event[] unknownByTo = eventDiff.result;
		error err = eventDiff.err;
		if (err != null) {
			return err;
		}

		RResult<WireEvent[]> toWire = cores[from].toWire(unknownByTo);
		WireEvent[] unknownWire = toWire.result;
		err = toWire.err;
		if (err != null) {
			return err;
		}

		cores[to].addTransactions(payload);

		return cores[to].Sync(unknownWire);
	}

	private error syncAndRunConsensus(Core[] cores, int from, int to, byte[][] payload)  {
		error err = synchronizeCores(cores, from, to, payload);
		if (err != null) {
			return err;
		}
		cores[to].runConsensus();
		return null;
	}

	private String getName(Map<String,String> index, String hash) {
		for (String name : index.keySet()) {
			String h = index.get(name);
			if (h.equals(hash)) {
				return name;
			}
		}
		return String.format("%s not found", hash);
	}
}