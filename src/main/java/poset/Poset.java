package poset;

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.log4j.Level;
import org.jcsp.lang.One2OneChannel;

import autils.Logger;
import common.LRUCache;
import common.RResult;
import common.RResult3;
import common.StoreErr;
import common.StoreErrType;
import common.error;
import node.Core;
import peers.Peer;
import peers.Peers.Listener;

/**
 * Poset is a DAG of Events. It also contains methods to extract a consensus
 * order of Events and map them onto a blockchain.
 */
public class Poset {
	public peers.Peers Participants; //[public key] => id
	public Store Store;              //store of Events, Rounds, and Blocks
	List<String> UndeterminedEvents; //[index] => hash . FIFO queue of Events whose consensus order is not yet determined
	List<pendingRound> PendingRounds; //FIFO queue of Rounds which have not attained consensus yet
	long LastConsensusRound;       //index of last consensus round
	long FirstConsensusRound;      //index of first consensus round (only used in tests)
	long AnchorBlock;              //index of last block with enough signatures
	int LastCommitedRoundEvents;   //number of events in round before LastConsensusRound
	List<BlockSignature> SigPool;  //Pool of Block signatures that need to be processed
	long ConsensusTransactions;    //number of consensus transactions
	int PendingLoadedEvents;       //number of loaded events that are not yet committed
	One2OneChannel<Block> commitCh;//channel for committing Blocks
	long topologicalIndex;         //counter used to order events in topological order (only local)
	int superMajority;
	int trustCount;
	Core core;

	LRUCache<String,Boolean> ancestorCache;
	LRUCache<String,Boolean> selfAncestorCache;
	LRUCache<String,Boolean> stronglySeeCache;
	LRUCache<String,Long> roundCache;
	LRUCache<String,Long> timestampCache;

	Logger logger;

	/**
	 * Constructor
	 * Instantiates a Poset from a list of participants, underlying data store and commit channel
	 * @param participants
	 * @param store
	 * @param commitCh
	 * @param logger
	 */
	public Poset(peers.Peers participants, Store store, One2OneChannel<Block> commitCh /* chan Block */, Logger logger) {
		if (logger == null) {
			logger = Logger.getLogger(Poset.class);
			logger.setLevel(Level.DEBUG);
		}

		int superMajority = (int) 2*participants.length()/3 + 1;
		int trustCount = (int) Math.ceil(((double) participants.length()) / 3);

		int cacheSize = store.cacheSize();
		RResult<LRUCache<String,Boolean>> ancestorCacheCre = LRUCache.New(cacheSize);
		LRUCache<String,Boolean> ancestorCache = ancestorCacheCre.result;
		error err = ancestorCacheCre.err;
		if ( err != null) {
			logger.fatal("Unable to init Poset.ancestorCache");
		}

		RResult<LRUCache<String,Boolean>> selfAncestorCacheCre = LRUCache.New(cacheSize);
		LRUCache<String,Boolean> selfAncestorCache = selfAncestorCacheCre.result;
		err = selfAncestorCacheCre.err;
		if ( err != null) {
			logger.fatal("Unable to init Poset.selfAncestorCache");
		}

		RResult<LRUCache<String,Boolean>> stronglySeeCacheCre = LRUCache.New(cacheSize);
		LRUCache<String,Boolean> stronglySeeCache = stronglySeeCacheCre.result;
		err = stronglySeeCacheCre.err;
		if ( err != null) {
			logger.fatal("Unable to init Poset.stronglySeeCache");
		}

		RResult<LRUCache<String,Long>> roundCacheCre = LRUCache.New(cacheSize);
		LRUCache<String,Long> roundCache = roundCacheCre.result;
		err = roundCacheCre.err;
		if ( err != null) {
			logger.fatal("Unable to init Poset.roundCache");
		}

		RResult<LRUCache<String,Long>> timestampCacheCre = LRUCache.New(cacheSize);
		LRUCache<String,Long> timestampCache = timestampCacheCre.result;
		err = timestampCacheCre.err;
		if ( err != null) {
			logger.fatal("Unable to init Poset.timestampCache");
		}

		this.Participants=      participants;
		this.Store=             store;
		this.commitCh=          commitCh;
		this.ancestorCache=     ancestorCache;
		this.selfAncestorCache= selfAncestorCache;
		this.stronglySeeCache=  stronglySeeCache;
		this.roundCache=        roundCache;
		this.timestampCache=   timestampCache;
		this.logger=            logger;
		this.superMajority=     superMajority;
		this.trustCount=        trustCount;

		this.UndeterminedEvents = new ArrayList<String>();

		participants.onNewPeer(
			new Listener() {
				public void listen(Peer peer) {
					Poset.this.superMajority = 2*participants.length()/3 + 1;
					Poset.this.trustCount = (int) Math.ceil( (double) participants.length() / 3);
				}
			}
		);
	}

	// SetCore sets a core for poset.
	public void SetCore(Core core) {
		this.core = core;
	}

	/*******************************************************************************
	Private Methods
	*******************************************************************************/
	private static String Key (String x, String y) {
		return x + "*****************" + y;
	}

	//true if y is an ancestor of x
	public RResult<Boolean> ancestor(String x, String y) {
		Boolean c = ancestorCache.get(Key (x, y));
		if(c != null) {
			return new RResult<Boolean>(c, null);
		}

		if (x == null || x.isEmpty() || y == null || y.isEmpty()) {
			return new RResult<Boolean>(false, null);
		}

		RResult<Boolean> ancestor2 = ancestor2(x, y);
		Boolean a = ancestor2.result;
		error err = ancestor2.err;
		if (err != null) {
			return new RResult<Boolean>(false, err);
		}
		ancestorCache.put(Key(x, y), a);
		return new RResult<Boolean>(a, null);
	}

	public RResult<Boolean> ancestor2(String x, String y) {
		logger.field("x", x).field("y", y).debug("ancestor2(x,y) starts");

		if (x.equals(y)) {
			return new RResult<Boolean>(true, null);
		}

		RResult<Event> exr = Store.getEvent(x);
		Event ex = exr.result;
		error err = exr.err;
		logger.field("ex", ex).field("err", err).debug("ancestor2()");
		if (err != null) {
			RResult<Map<String,Root>> rbySelf = Store.rootsBySelfParent();
			Map<String, Root> roots = rbySelf.result;
			error err2 = rbySelf.err;
			if (err2 != null) {
				return new RResult<Boolean>(false, err2);
			}
			for (Root root: roots.values()) {
				logger.field("root", root).debug("ancestor2()");

				RootEvent other = root.Others.get(y);
				if ( other != null) {
					return new RResult<Boolean>(other.Hash.equals(x), null);
				}
			}
			return new RResult<Boolean>(false, null);
		}

		RResult<Long> lamportTimestampDiff = lamportTimestampDiff(x, y);
		Long lamportDiff = lamportTimestampDiff.result;
		err = lamportTimestampDiff.err;
		if ( err != null || lamportDiff > 0) {
			return new RResult<Boolean>(false, err);
		}

		RResult<Event> getEventY = Store.getEvent(y);
		Event ey = getEventY.result;
		err = getEventY.err;
		if (err != null) {
			// check y roots
			RResult<Map<String, Root>> rootsBySelfParent = Store.rootsBySelfParent();
			Map<String, Root> roots = rootsBySelfParent.result;
			error err2 = rootsBySelfParent.err;
			if (err2 != null) {
				return new RResult<Boolean>(false, err2);
			}
			Root root = roots.get(y);
			if (root != null) {
				String yCreator = Participants.getById().get(root.SelfParent.CreatorID).getPubKeyHex();
				if (ex.creator().equals(yCreator)) {
					return new RResult<Boolean>(ex.index() >= root.SelfParent.Index, null);
				}
			} else {
				return new RResult<Boolean>(false, null);
			}
		} else {
			// check if creators are equals and check indexes
			if (ex.creator().equals(ey.creator())){
				return new RResult<Boolean>(ex.index() >= ey.index(), null);
			}
		}

		RResult<Boolean> ancestorCall = ancestor(ex.selfParent(), y);
		Boolean res = ancestorCall.result;
		err = ancestorCall.err;
		if (err != null) {
			return new RResult<Boolean>(false, err);
		}

		if (res) {
			return new RResult<Boolean>(true, null);
		}

		return ancestor(ex.otherParent(), y);
	}

	//true if y is a self-ancestor of x
	public RResult<Boolean> selfAncestor(String x, String y) {
		Boolean c = selfAncestorCache.get(Key(x, y));
		if (c != null) {
			return new RResult<Boolean>(c, null);
		}
		if (x == null || x.length() == 0 || y == null || y.length() == 0) {
			return new  RResult<Boolean>(false, null);
		}
		RResult<Boolean> selfAncestor2 = selfAncestor2(x, y);
		boolean a = selfAncestor2.result;
		error err = selfAncestor2.err;
		if ( err != null) {
			return new  RResult<Boolean>( false, err);
		}
		selfAncestorCache.put(Key(x, y), a);
		return new  RResult<Boolean>(a, null);
	}

	public RResult<Boolean> selfAncestor2(String x, String y) {
		if (x.equals(y)) {
			return new RResult<Boolean>(true, null);
		}
		RResult<Event> getEventX = Store.getEvent(x);
		Event ex = getEventX.result;
		error err = getEventX.err;
		if (err != null) {
			RResult<Map<String, Root>> rootsBySelfParent = Store.rootsBySelfParent();
			Map<String, Root> roots = rootsBySelfParent.result;
			err = rootsBySelfParent.err;
			if (err != null) {
				return new RResult<Boolean>(false, err);
			}

			Root root = roots.get(x);
			if (root != null) {
				if (root.SelfParent.Hash.equals(y)) {
					return new RResult<Boolean>(true, null);
				}
			}
			return new RResult<Boolean>(false, err);
		}

		RResult<Event> getEventY = Store.getEvent(y);
		Event ey = getEventY.result;
		err = getEventY.err;
		if (err != null) {
			RResult<Map<String, Root>> rootsBySelfParent = Store.rootsBySelfParent();
			Map<String, Root> roots = rootsBySelfParent.result;
			error err2 = rootsBySelfParent.err;
			if (err2 != null) {
				return new RResult<Boolean>(false, err2);
			}
			Root root = roots.get(y);
			if (root != null) {
				String yCreator = Participants.getById().get(root.SelfParent.CreatorID).getPubKeyHex();
				if (ex.creator().equals(yCreator)) {
					return new RResult<Boolean>(ex.index() >= root.SelfParent.Index, null);
				}
			}
		} else {
			if (ex.creator().equals(ey.creator())) {
				return new RResult<Boolean>(ex.index() >= ey.index(), null);
			}
		}

		return new RResult<Boolean>(false, null);
	}

	//true if x sees y
	public RResult<Boolean> see(String x, String y) {
		return ancestor(x, y);
		//it is not necessary to detect forks because we assume that the InsertEvent
		//function makes it impossible to insert two Events at the same height for
		//the same participant.
	}

	//true if x strongly sees y
	public RResult<Boolean> stronglySee(String x, String y) {
		if (x == null || x.isEmpty() || y == null || y.isEmpty()) {
			return new RResult<Boolean>(false, null);
		}

		Boolean c = stronglySeeCache.get( Key(x, y));
		if (c != null) {
			return new RResult<Boolean>(c, null);
		}

		RResult<Boolean> stronglySee2 = stronglySee2(x, y);
		Boolean ss = stronglySee2.result;
		error err = stronglySee2.err;
		if (err != null) {
			return new RResult<Boolean>(false, err);
		}
		stronglySeeCache.put(Key(x, y), ss);
		return new RResult<Boolean>(ss, null);
	}

	// Possible improvement: Populate the cache for upper and downer events
	// that also stronglySee y
	public RResult<Boolean> stronglySee2(String x, String y) {
		Map<String,Boolean> sentinels = new HashMap<String,Boolean>();

		error err = MapSentinels(x, y, sentinels);
		if  (err != null) {
			return new RResult<Boolean>(false, err);
		}

		return new RResult<Boolean>(sentinels.size()>= superMajority, null);
	}

	// participants in x's ancestry that see y
	public error MapSentinels(String x, String y, Map<String,Boolean> sentinels) {
		if (x == null || x.isEmpty()) {
			return null;
		}

		RResult<Boolean> seeXY = see(x, y);
		Boolean see = seeXY.result;
		error err = seeXY.err;
		if (err != null || !see) {
			return err;
		}

		RResult<Event> getEventX = Store.getEvent(x);
		Event ex = getEventX.result;
		err = getEventX.err;
		if (err != null) {
			RResult<Map<String, Root>> rootsBySelfParent = Store.rootsBySelfParent();
			Map<String, Root> roots = rootsBySelfParent.result;
			error err2 = rootsBySelfParent.err;

			if (err2 != null) {
				return err2;
			}

			Root root = roots.get(x);
			if (root != null) {
				Peer creator = Participants.getById().get(root.SelfParent.CreatorID);
				sentinels.put( creator.getPubKeyHex(), true);
				return null;
			}

			return err;
		}

		sentinels.put(ex.creator(), true);

		if (x.equals(y)) {
			return null;
		}

		err = MapSentinels(ex.otherParent(), y, sentinels);
		if  (err != null) {
			return err;
		}

		return MapSentinels(ex.selfParent(), y, sentinels);
	}

	public RResult<Long> round(String x) {
		Long c = roundCache.get(x);
		if (c != null) {
			return new RResult<Long>( (long) c, null);
		}
		RResult<Long> round2 = round2(x);
		Long r = round2.result;
		error err = round2.err;
		if (err != null) {
			return new RResult<Long>( (long) -1, err);
		}
		roundCache.put(x, r);
		return new RResult<Long>(r, null);
	}

	public RResult<Long>  round2(String x) {
		/*
			x is the Root
			Use Root.SelfParent.Round
		*/
		Map<String, Root> rootsBySelfParent = Store.rootsBySelfParent().result;
		Root r = rootsBySelfParent.get(x);
		if  (r != null) {
			return new RResult<Long>(r.SelfParent.Round, null);
		}

		RResult<Event> getEventX = Store.getEvent(x);
		Event ex = getEventX.result;
		error err = getEventX.err;
		if (err != null) {
			return new RResult<Long>(Long.MIN_VALUE, err);
		}

		RResult<Root> getRoot = Store.getRoot(ex.creator());
		Root root = getRoot.result;
		err = getRoot.err;
		if (err != null) {
			return new RResult<Long>(Long.MIN_VALUE, err);
		}

		/*
			The Event is directly attached to the Root.
		*/
		if (ex.selfParent().equals(root.SelfParent.Hash)) {
			//Root is authoritative EXCEPT if other-parent is not in the root
			RootEvent other = root.Others.get(ex.hex());
			boolean ok = other != null;
			if  (ex.otherParent().isEmpty() ||
				(ok && other.Hash.equals(ex.otherParent()))) {

				return new RResult<Long>(root.NextRound, null);
			}
		}

		/*
			The Event's parents are "normal" Events.
			Use the whitepaper formula: parentRound + roundInc
		*/
		RResult<Long> roundCall = round(ex.selfParent());
		long spRound = roundCall.result;
		err = roundCall.err;
		if ( err != null) {
			return new RResult<Long> (Long.MIN_VALUE, err);
		}

		long parentRound = spRound;
		long opRound;


		if (ex.otherParent() != null && !ex.otherParent().isEmpty()) {
			//XXX
			RootEvent other = root.Others.get(ex.hex());
			if (other != null && other.Hash.equals(ex.otherParent())) {
				opRound = root.NextRound;
			} else {
				RResult<Long> roundCall2 = round(ex.otherParent());
				opRound = roundCall2.result;
				err = roundCall2.err;
				if (err != null) {
					return new RResult<Long>(Long.MIN_VALUE, err);
				}
			}

			if (opRound > parentRound) {
				boolean found = false;
				long seeOpRoundRoots = 0;

				// if in a flag table there are witnesses of the current round, then
				// current round is other parent round.
				String[] ws = Store.roundWitnesses(opRound);
				RResult<Map<String, Long>> getFlagTable = ex.getFlagTable();
				Map<String, Long> ft = getFlagTable.result;
				for (String k : ft.keySet()) {
					for (String w : ws) {
						if (w.equals(k) && !w.equals(ex.hex())) {
							RResult<Boolean> seeCall = see(ex.hex(), w);
							Boolean see = seeCall.result;
							err = seeCall.err;
							if ( err != null) {
								return new RResult<Long>(Long.MIN_VALUE, err);
							}

							if (see) {
								if (!found) {
									found = true;
								}
								seeOpRoundRoots++;
							}
						}
					}
				}

				if (seeOpRoundRoots >= (long)(superMajority)) {
					return new RResult<Long>(opRound + 1, null);
				}

				if (found) {
					return new RResult<Long>(opRound, null);
				}

				parentRound = opRound;
			}
		}

		String[] ws = Store.roundWitnesses(parentRound);

		IsSee isSee = new IsSee() {
			public boolean isSee(Poset poset, String string, String[] witnesses)  {
				for (String w : ws) {
					if (w.equals(root) && !w.equals(ex.hex())) {
						RResult<Boolean> seeCall = poset.see(ex.hex(), w);
						boolean see = seeCall.result;
						error err = seeCall.err;
						if (err != null) {
							return false;
						}
						if (see) {
							return true;
						}
					}
				}
				return false;
			}
		};

//		isSee := public boolean isSee(Poset poset, String string, String[] witnesses)  {
//			for (String w : ws) {
//				if (w == root && w != ex.Hex()) {
//					RetResult<Boolean> seeCall = poset.see(ex.Hex(), w);
//					boolean see = seeCall.result;
//					err = seeCall.err;
//					if (err != null) {
//						return false;
//					}
//					if (see) {
//						return true;
//					}
//				}
//			}
//			return false;
//		}

		// check wp
		if (ex.message.WitnessProof != null && ex.message.WitnessProof.length >= superMajority) {
			int count = 0;

			for (String root2 : ex.message.WitnessProof) {
				if (isSee.isSee(this, root2, ws)) {
					count++;
				}
			}

			if (count >= superMajority){
				return new RResult<Long>(parentRound + 1, err);
			}
		}

		// check ft
		Map<String, Long> ft = ex.getFlagTable().result;
		if (ft.size() >= superMajority) {
			int count = 0;

			for (String root2 : ft.keySet()) {
				if (isSee.isSee(this, root2, ws)) {
					count++;
				}
			}

			if (count >= superMajority) {
				return new RResult<Long>(parentRound + 1, err);
			}
		}

		return new RResult<Long>(parentRound, null);
	}

	interface IsSee {
		boolean isSee(Poset poset, String string, String[] witnesses);
	}

	// witness if is true then x is a witness (first event of a round for the owner)
	public RResult<Boolean>  witness(String x) {
		RResult<Event> getEvent = Store.getEvent(x);
		Event ex = getEvent.result;
		error err = getEvent.err;
		if ( err != null) {
			return new RResult<Boolean>(false, err);
		}

		RResult<Long> roundCall = round(x);
		long xRound = roundCall.result;
		err = roundCall.err;
		if ( err != null) {
			return new RResult<Boolean>(false, err);
		}

		RResult<Long> roundCall2 = round(ex.selfParent());
		long spRound = roundCall2.result;
		err = roundCall2.err;
		if ( err != null) {
			return new RResult<Boolean>(false, err);
		}
		return new RResult<Boolean>(xRound > spRound, null);
	}

	public RResult<Long> roundReceived(String x){

		RResult<Event> getEvent = Store.getEvent(x);
		Event ex = getEvent.result;
		error err = getEvent.err;
		if (err != null) {
			return new RResult<Long>( (long) -1, err);
		}

		long res = -1;
		if (ex.roundReceived != -1) {
			res = ex.roundReceived;
		}

		return new RResult<Long>(res, null);
	}

	public RResult<Long> lamportTimestamp(String x) {
		Long c = timestampCache.get(x);
		if (c != null) {
			return new RResult<Long>(c, null);
		}
		RResult<Long> lamportTimestamp = lamportTimestamp2(x);
		long r = lamportTimestamp.result;
		error err = lamportTimestamp.err;
		if (err != null) {
			return new RResult<Long> ( (long) -1, err);
		}
		timestampCache.put(x, r);
		return new RResult<Long> (r, null);
	}

	public RResult<Long> lamportTimestamp2(String x) {
		/*
			x is the Root
			User Root.SelfParent.LamportTimestamp
		*/
		Map<String, Root> rootsBySelfParent = Store.rootsBySelfParent().result;
		Root r = rootsBySelfParent.get(x);
		boolean ok = r != null;

		if (ok) {
			return new RResult<Long>(r.SelfParent.LamportTimestamp, null);
		}

		RResult<Event> getEvent = Store.getEvent(x);
		Event ex = getEvent.result;
		error err = getEvent.err;
		if ( err != null) {
			return new RResult<Long>(Long.MIN_VALUE, err);
		}

		//We are going to need the Root later
		RResult<Root> getRoot = Store.getRoot(ex.creator());
		Root root = getRoot.result;
		err = getRoot.err;
		if ( err != null) {
			return new RResult<Long>(Long.MIN_VALUE, err);
		}

		long plt = Long.MIN_VALUE; //:= int64(Long.MIN_VALUE);
		//If it is the creator's first Event, use the corresponding Root
		if (ex.selfParent().equals(root.SelfParent.Hash)) {
			plt = root.SelfParent.LamportTimestamp;
		} else {
			RResult<Long> lamportTimestampCall = lamportTimestamp(ex.selfParent());
			long t = lamportTimestampCall.result;
			err = lamportTimestampCall.err;
			if (err != null) {
				return new RResult<Long>(Long.MIN_VALUE, err);
			}
			plt = t;
		}

		if (ex.otherParent() != null && !ex.otherParent().isEmpty()) {
			long opLT = Long.MIN_VALUE;
			err = Store.getEvent(ex.otherParent()).err;
			if (err == null) {
				//if we know the other-parent, fetch its Round directly
				RResult<Long> lamportTimestamp = lamportTimestamp(ex.otherParent());
				Long t = lamportTimestamp.result;
				err = lamportTimestamp.err;
				if (err != null) {
					return new RResult<Long>(Long.MIN_VALUE, err);
				}
				opLT = t;
			} else {
				RootEvent other = root.Others.get(x);
				ok = other != null;
				if (ok && other.Hash.equals(ex.otherParent())){
				//we do not know the other-parent but it is referenced  in Root.Others
				//we use the Root's LamportTimestamp
				opLT = other.LamportTimestamp;
				}
			}

			if (opLT > plt) {
				plt = opLT;
			}
		}

		return new RResult<Long>(plt + 1, null);
	}

	// lamport(y) - lamport(x)
	public RResult<Long>  lamportTimestampDiff(String x, String y) {
		RResult<Long> lamportTimestamp = lamportTimestamp(x);
		long xlt = lamportTimestamp.result;
		error err = lamportTimestamp.err;
		if ( err != null) {
			return new RResult<Long>( (long) 0, err);
		}
		RResult<Long> lamportTimestamp2 = lamportTimestamp(y);
		Long ylt = lamportTimestamp2.result;
		err = lamportTimestamp2.err;
		if ( err != null) {
			return new RResult<Long>((long) 0, err);
		}
		return new RResult<Long>(ylt - xlt, null);
	}

	//round(x) - round(y)
	public RResult<Long>  roundDiff(String x, String y) {
		RResult<Long> roundXCall = round(x);
		long xRound = roundXCall.result;
		error err = roundXCall.err;
		if (err != null) {
			return new RResult<Long>(Long.MIN_VALUE, error.Errorf(String.format("event %s has negative round", x)));
		}

		RResult<Long> roundYCall = round(y);
		long yRound = roundYCall.result;
		err = roundYCall.err;
		if ( err != null) {
			return new RResult<Long>(Long.MIN_VALUE, error.Errorf(String.format("event %s has negative round", y)));
		}

		return new RResult<Long>(xRound - yRound, null);
	}

	//Check the SelfParent is the Creator's last known Event
	public error checkSelfParent(Event event ) {
		String selfParent = event.selfParent();
		String creator = event.creator();
		RResult3<String, Boolean> lastEventFromCall = Store.lastEventFrom(creator);
		String creatorLastKnown = lastEventFromCall.result1;
		error err = lastEventFromCall.err;

		logger.field("selfParent", selfParent)
		.field("creator", creator)
		.field("creatorLastKnown", creatorLastKnown)
		.field("event", event.hex())
		.debug("checkSelfParent");

		if (err != null) {
			return err;
		}

		boolean selfParentLegit = (selfParent.equals(creatorLastKnown));

		if (!selfParentLegit) {
			return error.Errorf("self-parent not last known event by creator");
		}

		return null;
	}

	//Check if we know the OtherParent
	public error checkOtherParent(Event event) {
		String otherParent = event.otherParent();
		if (otherParent != null && !otherParent.isEmpty()) {
			//Check if we have it

			RResult<Event> getEvent = Store.getEvent(otherParent);
			error err = getEvent.err;
			if ( err != null) {
				//it might still be in the Root
				RResult<Root> getRoot = Store.getRoot(event.creator());
				Root root = getRoot.result;
				err = getRoot.err;
				if ( err != null) {
					return err;
				}

				RootEvent other = root.Others.get(event.hex());
				boolean ok = other != null;
				if (ok && other.Hash.equals(event.otherParent())) {
					return null;
				}
				return error.Errorf("other-parent not known");
			}
		}
		return null;
	}

	public RResult<RootEvent>  createSelfParentRootEvent(Event ev) {
		String sp = ev.selfParent();
		RResult<Long> lamportTimestampCall = lamportTimestamp(sp);
		long spLT = lamportTimestampCall.result;
		error err = lamportTimestampCall.err;
		if ( err != null) {
			return new RResult<RootEvent>(new RootEvent(), err);
		}
		RResult<Long> roundCall = round(sp);
		long spRound = roundCall.result;
		err = roundCall.err;
		if ( err != null) {
			return new RResult<RootEvent>(new RootEvent(), err);
		}

		RootEvent selfParentRootEvent = new RootEvent(
			sp,
			Participants.getByPubKey().get(ev.creator()).getID(),
			ev.index() - 1,
			spLT,
			spRound
			//FlagTable:ev.FlagTable,
			//flags:ev.flags,
		);
		return new RResult<RootEvent>(selfParentRootEvent, null);
	}

	public RResult<RootEvent> createOtherParentRootEvent(Event ev) {
		String op = ev.otherParent();

		//it might still be in the Root
		RResult<Root> getRootCall = Store.getRoot(ev.creator());
		Root root = getRootCall.result;
		error err = getRootCall.err;
		if ( err != null) {
			return new RResult<RootEvent>(new RootEvent(), err);
		}

		RootEvent other = root.Others.get(ev.hex());
		boolean ok = other != null;
		if (ok && other.Hash.equals(op)) {
			return new RResult<RootEvent> (other, null);
		}

		RResult<Event> getEvent = Store.getEvent(op);
		Event otherParent = getEvent.result;
		err = getEvent.err;
		if ( err != null) {
			return new RResult<RootEvent> (new RootEvent(), err);
		}

		RResult<Long> lamportTimestampCall = lamportTimestamp(op);
		long opLT = lamportTimestampCall.result;
		err = lamportTimestampCall.err;
		if ( err != null) {
			return new RResult<RootEvent> (new RootEvent(), err);
		}

		RResult<Long> roundCall = round(op);
		long opRound = roundCall.result;
		err = roundCall.err;
		if ( err != null) {
			return new RResult<RootEvent> (new RootEvent(), err);
		}
		RootEvent otherParentRootEvent = new RootEvent(
			op,
			Participants.getByPubKey().get(otherParent.creator()).getID(),
			otherParent.index(),
			opLT,
			opRound
		);
		return new RResult<RootEvent> (otherParentRootEvent, null);

	}

	public RResult<Root> createRoot(Event ev) {
		RResult<Long> round = round(ev.hex());
		long evRound = round.result;
		error err = round.err;

		if ( err != null) {
			return new RResult<Root>(new Root(), err);
		}

		/*
			SelfParent
		*/
		RResult<RootEvent>  selfParentRootEventCall= createSelfParentRootEvent(ev);
		RootEvent selfParentRootEvent = selfParentRootEventCall.result;
		err = selfParentRootEventCall.err;
		if ( err != null) {
			return new RResult<Root>(new Root(), err);
		}

		/*
			OtherParent
		*/
		RootEvent otherParentRootEvent = null;
		if (!ev.otherParent().isEmpty()) {
			RResult<RootEvent> createOtherParentRootEvent = createOtherParentRootEvent(ev);
			RootEvent opre = createOtherParentRootEvent.result;
			err = createOtherParentRootEvent.err;
			if (err != null) {
				return new RResult<Root>(new Root(), err);
			}
			otherParentRootEvent = opre;
		}

		Root root = new Root(
				evRound,
				selfParentRootEvent,
				new HashMap<String,RootEvent>());

		if (otherParentRootEvent != null) {
			root.Others.put(ev.hex(), otherParentRootEvent);
		}

		return new RResult<Root>(root, null);
	}

	public error SetWireInfo(Event event) {
		return setWireInfo(event);
	}

	public error SetWireInfoAndSign(Event event, PrivateKey privKey) {
		error err = setWireInfo(event);
		if  (err != null) {
			return err;
		}
		return event.sign(privKey);
	}

	public error setWireInfo(Event event) {
		long selfParentIndex = -1;
		long otherParentCreatorID = -1;
		long otherParentIndex = -1;

		//could be the first Event inserted for this creator. In this case, use Root
		RResult3<String, Boolean> lastEventFrom = Store.lastEventFrom(event.creator());
		String lf = lastEventFrom.result1;
		boolean isRoot = lastEventFrom.result2;
		error err;
		if  (isRoot && lf.equals(event.selfParent())) {
			RResult<Root> getRoot = Store.getRoot(event.creator());
			Root root = getRoot.result;
			err = getRoot.err;
			if (err != null) {
				return err;
			}
			selfParentIndex = root.SelfParent.Index;
		} else {
			RResult<Event> getEvent = Store.getEvent(event.selfParent());
			Event selfParent = getEvent.result;
			err = getEvent.err;
			if (err != null ){
				return err;
			}
			selfParentIndex = selfParent.index();
		}

		if (event.otherParent() != null && !event.otherParent().isEmpty()) {
			//Check Root then regular Events
			RResult<Root> getRoot = Store.getRoot(event.creator());
			Root root = getRoot.result;
			err = getRoot.err;
			if (err != null) {
				return err;
			}

			RootEvent other = root.Others.get(event.hex());
			boolean ok = other != null;
			if  (ok && other.Hash.equals(event.otherParent())) {
				otherParentCreatorID = other.CreatorID;
				otherParentIndex = other.Index;
			} else {
				RResult<Event> getEvent = Store.getEvent(event.otherParent());
				Event otherParent = getEvent.result;
				err = getEvent.err;
				if (err != null) {
					return err;
				}
				otherParentCreatorID = Participants.getByPubKey().get(otherParent.creator()).getID();
				otherParentIndex = otherParent.index();
			}
		}

		event.setWireInfo(selfParentIndex,
			otherParentCreatorID,
			otherParentIndex,
			Participants.getByPubKey().get(event.creator()).getID());

		return null;
	}

	public void updatePendingRounds(Map<Long,Long> decidedRounds) {

		logger.field("decidedRounds", decidedRounds)
		.debug("updatePendingRounds() starts");

		for (pendingRound ur : PendingRounds) {
			boolean ok = decidedRounds.containsKey(ur.Index);
			if (ok){
				ur.Decided = true;
			}
		}
	}

	//Remove processed Signatures from SigPool
	public void removeProcessedSignatures(Map<Long,Boolean> processedSignatures) {
		ArrayList<BlockSignature> newSigPool = new ArrayList<BlockSignature>();
		for (BlockSignature bs : SigPool) {
			boolean ok = processedSignatures.get(bs.index);
			if (!ok) {
				newSigPool.add(bs);
			}
		}
		SigPool = newSigPool;
	}

	/*******************************************************************************
	Public Methods
	*******************************************************************************/

	//InsertEvent attempts to insert an Event in the DAG. It verifies the signature,
	//checks the ancestors are known, and prevents the introduction of forks.
	public error InsertEvent(Event event, boolean setWireInfo) {
		//verify signature
		RResult<Boolean> verify = event.verify();
		Boolean ok = verify.result;
		error err = verify.err;
		if  (!ok) {
			if ( err != null) {
				return err;
			}

			logger.field("event", event)
			.field("creator", event.creator())
			.field("selfParent", event.selfParent())
			.field("index", event.index())
			.field("hex", event.hex())
			.debug("Invalid Event signature");

			return error.Errorf("invalid Event signature");
		}
		err = checkSelfParent(event);
		if  (err != null) {
			return error.Errorf(String.format("CheckSelfParent: %s", err));
		}
		err = checkOtherParent(event);
		if  (err != null) {
			return error.Errorf(String.format("CheckOtherParent: %s", err));
		}

		event.message.TopologicalIndex = topologicalIndex;
		topologicalIndex++;

		if (setWireInfo) {
			err = setWireInfo(event);
			if (err != null) {
				return error.Errorf(String.format("SetWireInfo: %s", err));
			}
		}

		err = Store.setEvent(event);
		if  (err != null) {
			return error.Errorf(String.format("SetEvent: %s", err));
		}

		logger.field("UndeterminedEvents", UndeterminedEvents).debug("adding hex");

		if (UndeterminedEvents == null) {
			UndeterminedEvents = new ArrayList<>();
		}
		UndeterminedEvents.add(event.hex());

		if (event.isLoaded()) {
			PendingLoadedEvents++;
		}

		if (SigPool == null) {
			SigPool = new ArrayList<>();
		}
		if (event.blockSignatures() != null) {
			ArrayList<BlockSignature> blockSignatures = new ArrayList<BlockSignature>();
			for (BlockSignature bs: event.blockSignatures()) {
				blockSignatures.add(new BlockSignature(bs));
			}
			SigPool.addAll(blockSignatures);
		}

		return null;
	}

	/*
	DivideRounds assigns a Round and LamportTimestamp to Events, and flags them as
	witnesses if necessary. Pushes Rounds in the PendingRounds queue if necessary.
	*/
	public error DivideRounds() {
		for (int i =0; i < UndeterminedEvents.size(); ++i) {
			String hash = UndeterminedEvents.get(i);
//		for (String h : UndeterminedEvents) {
			RResult<Event> getEvent = Store.getEvent(hash);
			Event ev = getEvent.result;
			error err = getEvent.err;
			if (err != null) {
				return err;
			}

			boolean updateEvent = false;

			/*
			   Compute Event's round, update the corresponding Round object, and
			   add it to the PendingRounds queue if necessary.
			*/
			// TODO java code can't check if a long is null
//			if (ev.round == null) {
			if (ev.round < 0) {
				RResult<Long> roundCall = round(hash);
				long roundNumber = roundCall.result;
				err = roundCall.err;
				if ( err != null) {
					return err;
				}

				ev.setRound(roundNumber);
				updateEvent = true;

				RResult<RoundInfo> getRound = Store.getRound(roundNumber);
				RoundInfo roundInfo = getRound.result;
				err = getRound.err;
				if (err != null && !StoreErr.Is(err, StoreErrType.KeyNotFound)) {
					return err;
				}

				/*
					Why the lower bound?
					Normally, once a Round has attained consensus, it is impossible for
					new Events from a previous Round to be inserted; the lower bound
					appears redundant. This is the case when the poset grows
					linearly, without jumps, which is what we intend by 'Normally'.
					But the Reset function introduces a discontinuity  by jumping
					straight to a specific place in the poset. This technique relies
					on a base layer of Events (the corresponding Frame's Events) for
					other Events to be added on top, but the base layer must not be
					reprocessed.
				*/

				// TODO go code check: LastConsensusRound == null
				if (!roundInfo.queued &&
					(LastConsensusRound < 0 ||
						roundNumber >= LastConsensusRound)) {

					if (PendingRounds == null) {
						PendingRounds = new ArrayList<>();
					}
					PendingRounds.add(new pendingRound (roundNumber, false));
					roundInfo.queued = true;
				}

				RResult<Boolean> witnessCall = witness(hash);
				Boolean witness = witnessCall.result;
				err = witnessCall.err;
				if (err != null) {
					return err;
				}
				roundInfo.AddEvent(hash, witness);

				err = Store.setRound(roundNumber, roundInfo);
				if (err != null) {
					return err;
				}

				if (witness) {
					// if event is self head
					if (core != null && ev.hex().equals(core.head()) &&
						ev.creator().equals(core.hexID())) {

//						replaceFlagTable := public(Event event, long round) {
//							HashMap<String, Long> ft = new HashMap<String, Long>();
//							String[] ws = Store.RoundWitnesses(round);
//							for (String v : ws) {
//								ft.put(v, (long) 1);
//							}
//							event.ReplaceFlagTable(ft);
//						}

						// special case
						if (ev.getRound() == 0) {
							replaceFlagTable(ev, 0);
							RResult<Root> getRoot = Store.getRoot(ev.creator());
							Root root = getRoot.result;
							err = getRoot.err;
							if ( err != null) {
								return err;
							}
							ev.message.WitnessProof = new String[]{root.SelfParent.Hash};
						} else {
							replaceFlagTable(ev, ev.getRound());
							String[] roots = Store.roundWitnesses(ev.getRound() - 1);
							ev.message.WitnessProof = roots;
						}
					}
				}
			}

			/*
				Compute the Event's LamportTimestamp
			*/
			if (ev.lamportTimestamp < 0) {

				RResult<Long> lamportTimestampCall = lamportTimestamp(hash);
				long lamportTimestamp = lamportTimestampCall.result;
				err = lamportTimestampCall.err;
				if (err != null) {
					return err;
				}

				ev.setLamportTimestamp(lamportTimestamp);
				updateEvent = true;
			}

			if (updateEvent) {
				if (ev.creatorID() == 0) {
					setWireInfo(ev);
				}
				Store.setEvent(ev);
			}
		}

		return null;
	}


	private void replaceFlagTable(Event event, long round) {
		HashMap<String, Long> ft = new HashMap<String, Long>();
		String[] ws = Store.roundWitnesses(round);
		for (String v : ws) {
			ft.put(v, (long) 1);
		}
		event.replaceFlagTable(ft);
	}

	private void setVote(HashMap<String,Map<String,Boolean>> votes, String x, String y, boolean vote) {
		if (votes.get(x) == null) {
			votes.put(x,  new HashMap<String,Boolean>());
		}
		votes.get(x).put(y, vote);
	};


	//DecideFame decides if witnesses are famous
	public error DecideFame() {

		logger.field("poset", this).debug("DecideFame() starts");

		//Initialize the vote map
		HashMap<String,Map<String,Boolean>> votes = new HashMap<String, Map<String, Boolean>>(); //[x][y]=>vote(x,y)

		Map<Long,Long> decidedRounds = new HashMap<Long,Long>(); // [round number] => index in PendingRounds

		for (int pos = 0; pos < PendingRounds.size(); ++pos ) {
			pendingRound r = PendingRounds.get(pos);
			long roundIndex = r.Index;
			RResult<RoundInfo> getRound = Store.getRound(roundIndex);
			RoundInfo roundInfo = getRound.result;
			error err = getRound.err;

			logger.field("roundIndex", roundIndex)
			.field("roundInfo", roundInfo)
			.field("roundInfo.WitnessesDecided()", roundInfo.WitnessesDecided())
			.field("roundInfo.Witnesses()", roundInfo.Witnesses().length)
			.debug("DecideFame() processing a pending round");

			if ( err != null) {
				return err;
			}
			for (String x :  roundInfo.Witnesses()) {
//				logger.WithFields(logrus.Fields{
//					"x": x,
//					"roundInfo.IsDecided(x)": roundInfo.IsDecided(x),
//				}).debug("DecideFame() processing a witness in pending round");

//				RoundEvent w = roundInfo.Message.Events.get(x);
//				logger.WithFields(logrus.Fields{
//					"witness": x,
//					"w": w,
//					"w.Consensus" : w.Consensus,
//					"w.Famous" : w.Famous,
//					"w.Witness" : w.Witness,
//					"ok": ok,
//				}).debug("DecideFame() check witness x");


				if (roundInfo.IsDecided(x)) {
					continue;
				}
			VOTE_LOOP:
				for (long j = roundIndex + 1; j <= Store.lastRound(); j++) {
					for (String y : Store.roundWitnesses(j)) {
						long diff = j - roundIndex;

//						logger.WithFields(logrus.Fields{
//							"Store.LastRound()": Store.LastRound(),
//							"j" : j,
//							"x": x,
//							"y": y,
//							"diff": diff,
//						}).debug("DecideFame() in VOTE_LOOP");

						if (diff == 1) {
							RResult<Boolean> seeCall = see(y, x);
							Boolean ycx = seeCall.result;
							err = seeCall.err;

//							logger.WithFields(logrus.Fields{
//								"ycx": ycx,
//								"err" : err,
//							}).debug("DecideFame() in VOTE_LOOP after see()");

							if (err != null) {
								return err;
							}
							setVote(votes, y, x, ycx);
						} else {
							//count votes
							List<String> ssWitnesses = new ArrayList<String>();
							for (String w1 : Store.roundWitnesses(j - 1)) {
								RResult<Boolean> stronglySeeCall = stronglySee(y, w1);
								boolean ss = stronglySeeCall.result;
								err = stronglySeeCall.err;

//								logger.WithFields(logrus.Fields{
//									"ss": ss,
//									"err" : err,
//								}).debug("DecideFame() in VOTE_LOOP after stronglySee()");

								if ( err != null) {
									return err;
								}
								if (ss) {
									ssWitnesses.add(w1);
								}
							}

//							logger.WithFields(logrus.Fields{
//								"ssWitnesses": ssWitnesses,
//								"Participants.Len()" : Participants.Len(),
//								"superMajority": superMajority,
//							}).debug("DecideFame() in VOTE_LOOP RRR compute ssWitnesses");

							long yays = 0;
							long nays = 0;
							for (String w1 : ssWitnesses) {
								if (votes.get(w1).get(x)) {
									yays++;
								} else {
									nays++;
								}
							}
							boolean v = false;
							long t = nays;
							if (yays >= nays) {
								v = true;
								t = yays;
							}

//							logger.WithFields(logrus.Fields{
//								"diff": diff,
//								"Participants.Len()" : Participants.Len(),
//								"superMajority": superMajority,
//								"t": t,
//								"v": v,
//								"yays": yays,
//								"nays" : nays,
//							}).debug("DecideFame() in VOTE_LOOP RRR before SetFame called");

							//normal round
							if ((diff % Participants.length()) > 0) {
								if (t >= superMajority) {

//									logger.WithFields(logrus.Fields{
//										"x": x,
//										"v": v,
//									}).debug("DecideFame() in VOTE_LOOP XXXXX calling SetFame");

									roundInfo.SetFame(x, v);
									setVote(votes, y, x, v);
									break VOTE_LOOP; //break out of j loop
								} else {

//									logger.WithFields(logrus.Fields{
//										"x": x,
//										"v": v,
//									}).debug("DecideFame() in VOTE_LOOP XXXXX calling setVote");

									setVote(votes, y, x, v);
								}
							} else { //coin round
								if (t >= superMajority) {
//									logger.WithFields(logrus.Fields{
//										"x": x,
//										"v": v,
//									}).debug("DecideFame() in VOTE_LOOP XXXXX1 calling setVote");

									setVote(votes, y, x, v);
								} else {

//									logger.WithFields(logrus.Fields{
//										"x": x,
//										"v": v,
//									}).debug("DecideFame() in VOTE_LOOP XXXXX2 calling setVote");

									setVote(votes, y, x, middleBit(y)); //middle bit of y's hash
								}
							}
						}
					}
				}
			}


			err = Store.setRound(roundIndex, roundInfo);

//			logger.WithFields(logrus.Fields{
//				"roundInfo": roundInfo,
//				"roundInfo.WitnessesDecided()": roundInfo.WitnessesDecided(),
//				"err": err,
//			}).debug("DecideFame() out of VOTE_LOOP, after check SetRound()");

			if ( err != null) {
				return err;
			}

//			logger.WithFields(logrus.Fields{
//				"roundInfo": roundInfo,
//				"roundInfo.WitnessesDecided()": roundInfo.WitnessesDecided(),
//			}).debug("DecideFame() out of VOTE_LOOP, after error check SetRound()");

//			for (Event e : roundInfo.Message.Events) {
//				logger.WithFields(logrus.Fields{
//					"e": e,
//					"e.Witness": e.Witness,
//					"e.Famous": e.Famous,
//					"(e.Witness && e.Famous == Trilean_UNDEFINED)=" : e.Witness && e.Famous == Trilean_UNDEFINED,
//				}).debug("DecideFame() out of VOTE_LOOP, forloop print");
//			}

			if (roundInfo.WitnessesDecided()) {
//				logger.WithFields(logrus.Fields{
//					"decidedRounds": decidedRounds,
//					"roundIndex" : roundIndex,
//					"pos" : pos,
//					"err": err,
//				}).debug("DecideFame() out of VOTE_LOOP, beofre set decidedRounds[roundIndex]");

				decidedRounds.put(roundIndex, (long) pos);
			}

		}

		updatePendingRounds(decidedRounds);
		return null;
	}

	//DecideRoundReceived assigns a RoundReceived to undetermined events when they
	//reach consensus
	public error DecideRoundReceived() {

		logger.field("poset", this)
			.field("UndeterminedEvents", UndeterminedEvents)
			.debug("DecideRoundReceived() starts");

		List<String> newUndeterminedEvents = new ArrayList<String>();

		/* From whitepaper - 18/03/18
		   "[...] An event is said to be “received” in the first round where all the
		   unique famous witnesses have received it, if all earlier rounds have the
		   fame of all witnesses decided"
		*/
		for (String x :  UndeterminedEvents) {

			boolean received = false;
			RResult<Long> roundCall = round(x);
			long r = roundCall.result;
			error err = roundCall.err;
			if (err != null) {
				return err;
			}

			RoundInfo tr;
			for (long i = r + 1; i <= Store.lastRound(); i++) {
				RResult<RoundInfo> getRound = Store.getRound(i);
				tr = getRound.result;
				err = getRound.err;
				if ( err != null) {
					//Can happen after a Reset/FastSync
					if (LastConsensusRound >=0 &&
						r < LastConsensusRound) {
						received = true;
						break;
					}
					return err;
				}

				//We are looping from earlier to later rounds; so if we encounter
				//one round with undecided witnesses, we are sure that this event
				//is not "received". Break out of i loop
				if (!tr.WitnessesDecided()) {
					break;
				}

				String[] fws = tr.FamousWitnesses();
				//set of famous witnesses that see x
				List<String> s = new ArrayList<String>();
				for (String w : fws) {
					RResult<Boolean> seeCall = see(w, x);
					boolean see = seeCall.result;
					err = seeCall.err;
					if ( err != null) {
						return err;
					}
					if (see) {
						s.add(w);
					}
				}

				if (s.size() == fws.length  && s.size() > 0) {

					received = true;

					RResult<Event> getEvent = Store.getEvent(x);
					Event ex = getEvent.result;
					err = getEvent.err;
					if ( err != null) {
						return err;
					}
					ex.setRoundReceived(i);

					err = Store.setEvent(ex);
					if ( err != null) {
						return err;
					}

					tr.SetConsensusEvent(x);
					err = Store.setRound(i, tr);
					if ( err != null) {
						return err;
					}

					//break out of i loop
					break;
				}

			}

			if (!received) {
				newUndeterminedEvents.add(x);
			}
		}

		UndeterminedEvents = newUndeterminedEvents;

		return null;
	}

	//ProcessDecidedRounds takes Rounds whose witnesses are decided, computes the
	//corresponding Frames, maps them into Blocks, and commits the Blocks via the
	//commit channel
	public error ProcessDecidedRounds() {
		logger.field("poset", this).debug("ProcessDecidedRounds() starts");

		//Defer removing processed Rounds from the PendingRounds Queue
		int processedIndex = 0;

		logger.field("PendingRounds", PendingRounds.size())
			.field("LastConsensusRound", LastConsensusRound)
			.debug("ProcessDecidedRounds() gets PendingRounds");

//		if LastConsensusRound != null {
//			logger.WithFields(logrus.Fields{
//				"*LastConsensusRound": *LastConsensusRound,
//			}).debug("ProcessDecidedRounds() gets PendingRounds");
//		}

		for (pendingRound r : PendingRounds) {

			logger
				.field("r.Decided", r.Decided)
				.field("r.Index", r.Index)
				.debug("ProcessDecidedRounds() processing pending round r");

			//Although it is possible for a Round to be 'decided' before a previous
			//round, we should NEVER process a decided round before all the previous
			//rounds are processed.
			if (!r.Decided) {
				break;
			}

			//This is similar to the lower bound introduced in DivideRounds; it is
			//redundant in normal operations, but becomes necessary after a Reset.
			//Indeed, after a Reset, LastConsensusRound is added to PendingRounds,
			//but its ConsensusEvents (which are necessarily 'under' this Round) are
			//already deemed committed. Hence, skip this Round after a Reset.
			if (LastConsensusRound >= 0 && r.Index == LastConsensusRound) {
				continue;
			}

			RResult<Frame> getFrameCall = GetFrame(r.Index);
			Frame frame = getFrameCall.result;
			error err = getFrameCall.err;
			if ( err != null) {
				return error.Errorf(
						String.format("getting Frame %d: %v", r.Index, err));
			}

			RResult<RoundInfo> getRoundCall = Store.getRound(r.Index);
			RoundInfo round = getRoundCall.result;
			err = getRoundCall.err;
			if ( err != null) {
				return err;
			}
			logger
				.field("round_received", r.Index)
				.field("witnesses",      round.FamousWitnesses())
				.field("events",         frame.Events.length)
				.field("roots",          frame.Roots)
				.debug("Processing Decided Round");

			if (frame.Events.length > 0) {
				for (EventMessage e : frame.Events) {
					Event ev = e.ToEvent();
					err = Store.addConsensusEvent(ev);
					if (err != null) {
						return err;
					}
					ConsensusTransactions += ev.transactions().length;
					if (ev.isLoaded()) {
						PendingLoadedEvents--;
					}
				}

				long lastBlockIndex = Store.lastBlockIndex();
				RResult<Block> newBlockFromFrame = Block.newBlockFromFrame(lastBlockIndex+1, frame);
				Block block = newBlockFromFrame.result;
				err = newBlockFromFrame.err;
				if ( err != null) {
					return err;
				}

				if (block.transactions().length > 0) {
					err = Store.setBlock(block);
					if (err != null){
						return err;
					}

					if (commitCh != null) {
						commitCh.out().write(block);
					}
				}

			} else {
				logger.debug(String.format("No Events to commit for ConsensusRound %d", r.Index));
			}

			processedIndex++;

			if (LastConsensusRound < 0 || r.Index > LastConsensusRound) {
				setLastConsensusRound(r.Index);
			}

		}

		PendingRounds = PendingRounds.subList(processedIndex, PendingRounds.size());
		return null;
	}

	//GetFrame computes the Frame corresponding to a RoundReceived.
	public RResult<Frame> GetFrame(long roundReceived) {

		logger.field("roundReceived", roundReceived).debug("GetFrame() getting frame");

		//Try to get it from the Store first
		RResult<Frame> getFrame = Store.getFrame(roundReceived);
		Frame frame = getFrame.result;
		error err = getFrame.err;
		if (err == null || !StoreErr.Is(err, common.StoreErrType.KeyNotFound)) {
			return new RResult<Frame>(frame, err);
		}

		logger.field("frame", frame).debug("GetFrame() found frame");

		//Get the Round and corresponding consensus Events
		RResult<RoundInfo> getRound = Store.getRound(roundReceived);
		RoundInfo round = getRound.result;
		err = getRound.err;
		if ( err != null) {
			return new RResult<Frame>(new Frame(), err);
		}

		logger.field("round", round)
			.field("round.ConsensusEvents()", round.ConsensusEvents())
			.debug("GetFrame() found round");

		List<Event> events = new ArrayList<Event>();
		for (String eh : round.ConsensusEvents()) {
			RResult<Event> getEvent = Store.getEvent(eh);
			Event e = getEvent.result;
			err = getEvent.err;
			if ( err != null) {
				new RResult<Frame>(new Frame(), err);
			}
			events.add(e);
		}

		logger.field("events", events)
			.debug("GetFrame() before sorting by Lamport timestamp");

		Collections.sort(events, new EventComparatorByLamportTimestamp());

		logger.field("events", events)
			.debug("GetFrame() after sorting by Lamport timestamp");

		// Get/Create Roots
		HashMap<String, Root> roots = new HashMap<String,Root>();
		//The events are in topological order. Each time we run into the first Event
		//of a participant, we create a Root for it.
		for (Event ev : events) {
			String c = ev.creator();
			if (roots.get(c) == null) {
				RResult<Root> createRoot = createRoot(ev);
				Root root = createRoot.result;
				err = createRoot.err;
				if ( err != null) {
					new RResult<Frame>(new Frame(), err);
				}
				roots.put(ev.creator(), root);
			}
		}

		//Every participant needs a Root in the Frame. For the participants that
		//have no Events in this Frame, we create a Root from their last consensus
		//Event, or their last known Root
		for (String peer : Participants.toPubKeySlice()) {
			if (roots.get(peer) == null) {
				Root root;
				RResult3<String,Boolean> lastConsensusCall = Store.lastConsensusEventFrom(peer);
				String lastConsensusEventHash = lastConsensusCall.result1;
				boolean isRoot = lastConsensusCall.result2;
				err = lastConsensusCall.err;
				if ( err != null) {
					return new RResult<Frame>(new Frame(), err);
				}
				if (isRoot) {
					root = Store.getRoot(peer).result;
				} else {
					RResult<Event> lastConsensusEventCall = Store.getEvent(lastConsensusEventHash);
					Event lastConsensusEvent = lastConsensusEventCall.result;
					err = lastConsensusEventCall.err;
					if ( err != null) {
						return new RResult<Frame>(new Frame(), err);
					}
					RResult<Root> createRootCall = createRoot(lastConsensusEvent);
					root = createRootCall.result;
					err = createRootCall.err;
					if ( err != null) {
						new RResult<Frame>(new Frame(), err);
					}
				}
				roots.put(peer,  root);
			}
		}

		//Some Events in the Frame might have other-parents that are outside of the
		//Frame (cf root.go ex 2)
		//When inserting these Events in a newly reset poset, the CheckOtherParent
		//method would return an error because the other-parent would not be found.
		//So we make it possible to also look for other-parents in the creator's Root.
		HashMap<String,Boolean> treated = new HashMap<String,Boolean>();
		EventMessage[] eventMessages = new EventMessage[events.size()];
		for (int i = 0; i < events.size(); ++i) {
			Event ev = events.get(i);
			treated.put(ev.hex(), true);
			String otherParent = ev.otherParent();
			if (!otherParent.isEmpty()) {
				Boolean opt = treated.get(otherParent);
				if (opt == null || !opt) {
					if (!ev.selfParent().equals(roots.get(ev.creator()).SelfParent.Hash)) {
						RResult<RootEvent> createOtherParentRootEvent = createOtherParentRootEvent(ev);
						RootEvent other = createOtherParentRootEvent.result;
						err = createOtherParentRootEvent.err;
						if (err != null) {
							new RResult<Frame>(new Frame(), err);
						}
						roots.get(ev.creator()).Others.put(ev.hex(), other);
					}
				}
			}
			eventMessages[i] = new EventMessage(ev.message);
		}

		//order roots
		Root[] orderedRoots = new Root[Participants.length()];
		for (int i=0; i<Participants.toPeerSlice().length; ++i) {
			Peer peer = Participants.toPeerSlice()[i];
			orderedRoots[i] = roots.get(peer.getPubKeyHex());
		}

		Frame res = new Frame (roundReceived, orderedRoots, eventMessages);

		err = Store.setFrame(res);
		if (err != null) {
			new RResult<Frame>(new Frame(), err);
		}

		return new RResult<Frame>(res, null);
	}

	//ProcessSigPool runs through the SignaturePool and tries to map a Signature to
	//a known Block. If a Signature is found to be valid for a known Block, it is
	//appended to the block and removed from the SignaturePool
	public error ProcessSigPool() {
		Map<Long,Boolean> processedSignatures = new HashMap<Long,Boolean>(); //index in SigPool => Processed?

		for (int i = 0; i < SigPool.size(); ++i) {
			BlockSignature bs = SigPool.get(i);
			//check if validator belongs to list of participants
//			String validatorHex = String.format("0x%X", bs.Validator);
			String validatorHex = bs.validatorHex();

			Peer ok = Participants.getByPubKey().get(validatorHex);
			if (ok == null){
				logger
					.field("index",     bs.index)
					.field("validator", validatorHex)
					.warn("Verifying Block signature. Unknown validator");
				continue;
			}
			//only check if bs is greater than AnchorBlock, otherwise simply remove
			if (AnchorBlock < 0 ||
				bs.index > AnchorBlock) {
				RResult<Block> getBlock = Store.getBlock(bs.index);
				Block block = getBlock.result;
				error err = getBlock.err;
				if (err != null) {
					logger
						.field("index", bs.index)
						.field("msg",   err)
						.warn("Verifying Block signature. Could not fetch Block");
					continue;
				}
				RResult<Boolean> verify = block.verify(bs);
				Boolean valid = verify.result;
				err = verify.err;
				if ( err != null) {
					logger
						.field("index", bs.index)
						.field("msg",   err)
						.error("Verifying Block signature");
					return err;
				}
				if (!valid) {
					logger
						.field("index",     bs.index)
						.field("validator", Participants.byPubKey(validatorHex))
						.field("block",     block)
						.warn("Verifying Block signature. Invalid signature");
					continue;
				}

				block.setSignature(bs);

				err = Store.setBlock(block);
				if ( err != null) {
					logger
						.field("index", bs.index)
						.field("msg",   err)
						.warn("Saving Block");
				}

				if (block.getSignatures().size() > trustCount &&
					(AnchorBlock < 0 ||
						block.Index() > AnchorBlock)) {
					setAnchorBlock(block.Index());
					logger
						.field("block_index", block.Index())
						.field("signatures",  block.getSignatures().size())
						.field("trustCount",  trustCount)
						.debug("Setting AnchorBlock");
				}
			}

			processedSignatures.put((long) i, true);
		}

		removeProcessedSignatures(processedSignatures);

		return null;
	}

	//GetAnchorBlockWithFrame returns the AnchorBlock and the corresponding Frame.
	//This can be used as a base to Reset a Poset
	public RResult3<Block,Frame> GetAnchorBlockWithFrame() {

		if (AnchorBlock < 0) {
			return new RResult3<Block,Frame>(null, null, error.Errorf("no Anchor Block"));
		}

		RResult<Block> getBlock = Store.getBlock(AnchorBlock);
		Block block = getBlock.result;
		error err = getBlock.err;
		if (err != null) {
			return new RResult3<Block,Frame>(null, null, err);
		}

		RResult<Frame> getFrame = GetFrame(block.roundReceived());
		Frame frame = getFrame.result;
		err = getFrame.err;
		if ( err != null) {
			return new RResult3<Block,Frame>(null, null, err);
		}

		return new RResult3<Block,Frame>(block, frame, null);
	}

	//Reset clears the Poset and resets it from a new base.
	public error Reset(Block block, Frame frame) {
		logger.field("block", block).debug("Reset()");

		//Clear all state
		LastConsensusRound = -1;
		FirstConsensusRound = -1;
		AnchorBlock = -1;

		UndeterminedEvents = new ArrayList<String>();
		PendingRounds = new ArrayList<pendingRound>();
		PendingLoadedEvents = 0;
		topologicalIndex = 0;

		int cacheSize = Store.cacheSize();
		RResult<LRUCache<String,Boolean>> ancestorCacheCall = LRUCache.New(cacheSize);
		LRUCache<String,Boolean> ancestorCache = ancestorCacheCall.result;
		error err = ancestorCacheCall.err;
		if ( err != null) {
			logger.fatal("Unable to reset Poset.ancestorCache");
		}

		RResult<LRUCache<String,Boolean>> selfAncestorCacheCall = LRUCache.New(cacheSize);
		LRUCache<String,Boolean> selfAncestorCache = selfAncestorCacheCall.result;
		err = selfAncestorCacheCall.err;
		if ( err != null) {
			logger.fatal("Unable to reset Poset.selfAncestorCache");
		}
		RResult<LRUCache<String,Boolean>> stronglySeeCacheCall = LRUCache.New(cacheSize);
		LRUCache<String,Boolean> stronglySeeCache = stronglySeeCacheCall.result;
		err = stronglySeeCacheCall.err;
		if ( err != null) {
			logger.fatal("Unable to reset Poset.stronglySeeCache");
		}

		RResult<LRUCache<String,Long>> roundCacheCall = LRUCache.New(cacheSize);
		LRUCache<String,Long> roundCache = roundCacheCall.result;
		err = roundCacheCall.err;
		if (err != null) {
			logger.fatal("Unable to reset Poset.roundCache");
		}

		this.ancestorCache = ancestorCache;
		this.selfAncestorCache = selfAncestorCache;
		this.stronglySeeCache = stronglySeeCache;
		this.roundCache = roundCache;

		Peer[] participants = Participants.toPeerSlice();

		//Initialize new Roots
		HashMap<String, Root> rootMap = new HashMap<String,Root>();
		for (int id = 0; id < frame.Roots.length; id++) {
			Root root = frame.Roots[id];
			Peer p = participants[id];
			rootMap.put(p.getPubKeyHex(), root);
		}
		err = Store.reset(rootMap);
		if (err != null) {
			return err;
		}

		//Insert Block
		err = Store.setBlock(block);
		if (err != null) {
			return err;
		}

		setLastConsensusRound(block.roundReceived());

		//Insert Frame Events
		for (EventMessage ev : frame.Events) {
			err = InsertEvent(ev.ToEvent(), false);
			if (err != null){
				return err;
			}
		}

		return null;
	}

	//Bootstrap loads all Events from the Store's DB (if there is one) and feeds
	//them to the Poset (in topological order) for consensus ordering. After this
	//method call, the Poset should be in a state coherent with the 'tip' of the Poset
	public error Bootstrap() {
		if (Store instanceof BadgerStore) {
			BadgerStore badgerStore = (BadgerStore) Store;

			//Retrieve the Events from the underlying DB. They come out in topological order
			RResult<Event[]> dbTopologicalEventsCall = badgerStore.dbTopologicalEvents();
			Event[] topologicalEvents = dbTopologicalEventsCall.result;
			error err = dbTopologicalEventsCall.err;
			if (err != null) {
				return err;
			}

			//Insert the Events in the Poset
			for (Event e : topologicalEvents) {
				err = InsertEvent(e, true);
				if ( err != null) {
					return err;
				}
			}

			//Compute the consensus order of Events
			err = DivideRounds();
			if  (err != null) {
				return err;
			}
			err = DecideFame();
			if ( err != null) {
				return err;
			}

			err = DecideRoundReceived();
			if (err != null) {
				return err;
			}

			err = ProcessDecidedRounds();
			if (err != null) {
				return err;
			}
			err = ProcessSigPool();
			if (err != null) {
				return err;
			}
		}

		return null;
	}

	//ReadWireInfo converts a WireEvent to an Event by replacing int IDs with the
	//corresponding public keys.
	public RResult<Event> ReadWireInfo(WireEvent wevent) {
		String selfParent = Event.rootSelfParent(wevent.Body.CreatorID);
		String otherParent = "";
		error err;

		Peer creator = Participants.byId(wevent.Body.CreatorID);
		// FIXIT: creator can be null when wevent.Body.CreatorID == 0
		if (creator == null) {
			return new RResult<Event>(null, error.Errorf(
					String.format("unknown wevent.Body.CreatorID=%v", wevent.Body.CreatorID)));
		}

		RResult<byte[]> decodeString = crypto.Utils.decodeString(creator.getPubKeyHex().substring(2, creator.getPubKeyHex().length()));
		byte[] creatorBytes = decodeString.result;
		err = decodeString.err;
		if (err != null) {
			return new RResult<Event>(null, err);
		}

		if (wevent.Body.SelfParentIndex >= 0) {
			RResult<String> ParticipantEventCall = Store.participantEvent(creator.getPubKeyHex(), wevent.Body.SelfParentIndex);
			selfParent = ParticipantEventCall.result;
			err = ParticipantEventCall.err;
			if ( err != null) {
				return new RResult<Event>(null, err);
			}
		}
		if (wevent.Body.OtherParentIndex >= 0) {
			Peer otherParentCreator = Participants.byId(wevent.Body.OtherParentCreatorID);
			if (otherParentCreator != null) {
				RResult<String> participantEventCall = Store.participantEvent(otherParentCreator.getPubKeyHex(), wevent.Body.OtherParentIndex);
				otherParent = participantEventCall.result;
				err = participantEventCall.err;

				if ( err != null) {
					//PROBLEM Check if other parent can be found in the root
					//problem, we do not known the WireEvent's EventHash, and
					//we do not know the creators of the roots RootEvents
					RResult<Root> getRoot = Store.getRoot(creator.getPubKeyHex());
					Root root = getRoot.result;
					err = getRoot.err;
					if ( err != null) {
						return new RResult<Event>(null, err);
					}
					//loop through others
					boolean found = false;
					for (RootEvent re : root.Others.values()) {
						if (re.CreatorID == wevent.Body.OtherParentCreatorID &&
							re.Index == wevent.Body.OtherParentIndex) {
							otherParent = re.Hash;
							found = true;
							break;
						}
					}

					if (!found) {
						return new RResult<Event>(null, error.Errorf("OtherParent not found"));
					}
				}
			} else {
				// unknown participant
				// TODO: we should handle this nicely
				return new RResult<Event>(null, error.Errorf("unknown participant"));
			}
		}

		if (wevent.FlagTable.length == 0) {
			return new RResult<Event>(null, error.Errorf("flag table is null"));
		}

		InternalTransaction[] transactions = Utils.copyOf(wevent.Body.InternalTransactions);

		BlockSignature[] signatureValues = wevent.BlockSignatures(creatorBytes);
		BlockSignature[] blockSignatures = Utils.copyOf(signatureValues);

		EventBody body = new EventBody(
			wevent.Body.Transactions,
			transactions,
			new String[]{selfParent, otherParent},
			creatorBytes,
			wevent.Body.Index,
			blockSignatures
		);

		Event event = new Event(
			new EventMessage (
				body,
				wevent.Signature,
				wevent.FlagTable,
				wevent.WitnessProof,
				wevent.Body.SelfParentIndex,
				wevent.Body.OtherParentCreatorID,
				wevent.Body.OtherParentIndex,
				wevent.Body.CreatorID,
				-1
			)
		);

		logger
			.field("event.Signature",  event.message.Signature)
			.field("wevent.Signature", wevent.Signature)
			.debug("Return Event from ReadFromWire");

		return new RResult<Event>(event, null);
	}

	//CheckBlock returns an error if the Block does not contain valid signatures
	//from MORE than 1/3 of participants
	public error CheckBlock(Block block) {
		int validSignatures = 0;
		for(BlockSignature s : block.getBlockSignatures()) {
			boolean ok = block.verify(s).result;
			if (ok) {
				validSignatures++;
			}
		}
		if (validSignatures <= trustCount) {
			return error.Errorf(
					String.format("not enough valid signatures: got %d, need %d", validSignatures, trustCount+1));
		}

		logger.field("valid_signatures", validSignatures).debug("CheckBlock");
		return null;
	}

	/*******************************************************************************
	Setters
	*******************************************************************************/

	public void setLastConsensusRound(long i) {
		LastConsensusRound = i;

		if (FirstConsensusRound == -1) {
			FirstConsensusRound = i;
		}
	}

	public void setAnchorBlock(long i) {
		AnchorBlock = i;
	}

	/*
	*/
	public RResult<Map<String,Long>> GetFlagTableOfRandomUndeterminedEvent() {
		// FIXME: possible data race: UndeterminedEvents can be modified by other goroutine
		// TODO the java conversion is ok?
		//		perm := rand.Perm(UndeterminedEvents.length);
		List<Integer> perm = IntStream.range(0, UndeterminedEvents.size()).boxed().collect(Collectors.toList());
		Collections.shuffle(perm);
		error err = null;
		for (int i = 0; i< perm.size(); ++i) {
			String hash = UndeterminedEvents.get(perm.get(i));
			RResult<Event> getEvent = Store.getEvent(hash);
			Event ev = getEvent.result;
			err = getEvent.err;
			if (err != null) {
				continue;
			}
			RResult<Map<String, Long>> getFlagTable = ev.getFlagTable();
			Map<String, Long> ft = getFlagTable.result;
			err = getFlagTable.err;
			if (err != null) {
				continue;
			}
			return new RResult<Map<String,Long>>(ft, null);
		}
		return new RResult<Map<String,Long>>(null, err);
	}

	/*******************************************************************************
	   Getters and Setters
	*******************************************************************************/
	public int getPendingLoadedEvents() {
		return PendingLoadedEvents;
	}

	public List<String> getUndeterminedEvents() {
		return UndeterminedEvents;
	}

	public List<pendingRound> getPendingRounds() {
		return PendingRounds;
	}

	public long getLastConsensusRound() {
		return LastConsensusRound;
	}

	public long getFirstConsensusRound() {
		return FirstConsensusRound;
	}

	public int getLastCommitedRoundEvents() {
		return LastCommitedRoundEvents;
	}

	public long getConsensusTransactions() {
		return ConsensusTransactions;
	}

	/*******************************************************************************
	   Helpers
	*******************************************************************************/
	public boolean middleBit(String ehex) {
		RResult<byte[]> decodeString = crypto.Utils.decodeString(ehex.substring(2, ehex.length()));

		byte[] hash = decodeString.result;
		error err = decodeString.err;
		if (err != null) {
			System.err.println(
					String.format("ERROR decoding hex string: %s\n", err));
		}
		if (hash.length > 0 && hash[hash.length/2] == 0) {
			return false;
		}
		return true;
	}

	public void PrintStat(Logger logger) {
		logger.warn("****Known events:");
		for (long pid_id : Store.knownEvents().keySet()) {
			long index = Store.knownEvents().get(pid_id);
			logger.warn("    index=" + index +
				" peer=" + Participants.byId(pid_id).getNetAddr() +
				" pubKeyHex=" + Participants.byId(pid_id).getPubKeyHex());
		}
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("Poset [Participants=").append(Participants).append(", Store=").append(Store)
				.append(", UndeterminedEvents=").append(UndeterminedEvents).append(", PendingRounds=")
				.append(PendingRounds).append(", LastConsensusRound=").append(LastConsensusRound)
				.append(", FirstConsensusRound=").append(FirstConsensusRound).append(", AnchorBlock=")
				.append(AnchorBlock).append(", LastCommitedRoundEvents=").append(LastCommitedRoundEvents)
				.append(", SigPool=").append(SigPool).append(", ConsensusTransactions=").append(ConsensusTransactions)
				.append(", PendingLoadedEvents=").append(PendingLoadedEvents).append(", commitCh=").append(commitCh)
				.append(", topologicalIndex=").append(topologicalIndex).append(", superMajority=").append(superMajority)
				.append(", trustCount=").append(trustCount).append(", core=").append(core.hashCode()).append(", ancestorCache=")
				.append(ancestorCache).append(", selfAncestorCache=").append(selfAncestorCache)
				.append(", stronglySeeCache=").append(stronglySeeCache).append(", roundCache=").append(roundCache)
				.append(", timestampCache=").append(timestampCache).append(", logger=").append(logger).append("]");
		return builder.toString();
	}
}