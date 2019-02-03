package dummy;
import static org.junit.Assert.assertTrue;

import org.apache.log4j.Level;
import org.junit.Test;

import autils.Logger;
import autils.time;
import common.TestUtils;
import node.Node;
import node.NodeList;
import node.NodeList.Stoppable;

/**
 * Test of Client
 *
 * @author qn
 *
 */
public class ClientTest {

//	@Test
//	public void TestSocketProxyServer() {
//		long timeout = 2 * time.Second;
//		String errTimeout = "time is over";
//		String addr = "127.0.0.1:9990";
//
//		Logger logger = TestUtils.NewTestLogger(this.getClass());
//
//		byte[] txOrigin = "the test transaction".getBytes();
//
//		// Server
//		app, err := proxy.NewGrpcAppProxy(addr, timeout, logger)
//		asserter.NoError(err)
//
//		//  listens for a request
//		go public void() {
//			select {
//			case tx := <-app.SubmitCh():
//				asserter.Equal(txOrigin, tx)
//			case <-time.After(timeout):
//				asserter.Fail(errTimeout)
//			}
//		}()
//
//		// Client part connecting to RPC service and calling methods
//		lachesisProxy, err := proxy.NewGrpcLachesisProxy(addr, logger)
//		asserter.NoError(err)
//
//		node, err := NewDummyClient(lachesisProxy, nil, logger)
//		asserter.NoError(err)
//
//		err = node.SubmitTx(txOrigin)
//		asserter.NoError(err)
//	}

//	@Test
//	public void TestDummySocketClient() {
//		const (
//			timeout    = 2 * time.Second
//			errTimeout = "time is over"
//			addr       = "127.0.0.1:9992"
//		)
//		asserter := assert.New(t)
//		logger := common.NewTestLogger(t)
//
//		// server
//		appProxy, err := proxy.NewGrpcAppProxy(addr, timeout, logger)
//		asserter.NoError(err)
//		defer appProxy.Close()
//
//		// client
//		lachesisProxy, err := proxy.NewGrpcLachesisProxy(addr, logger)
//		asserter.NoError(err)
//		defer lachesisProxy.Close()
//
//		state := NewState(logger)
//
//		_, err = NewDummyClient(lachesisProxy, state, logger)
//		asserter.NoError(err)
//
//		initialStateHash := state.stateHash
//		//create a few blocks
//		blocks := [5]poset.Block{}
//		for i := int64(0); i < 5; i++ {
//			blocks[i] = poset.NewBlock(i, i+1, []byte{}, [][]byte{[]byte(fmt.Sprintf("block %d transaction", i))})
//		}
//
//		<-time.After(timeout / 4)
//
//		//commit first block and check that the client's statehash is correct
//		stateHash, err := appProxy.CommitBlock(blocks[0])
//		asserter.NoError(err)
//
//		expectedStateHash := initialStateHash
//
//		for _, t := range blocks[0].Transactions() {
//			tHash := bcrypto.SHA256(t)
//			expectedStateHash = bcrypto.SimpleHashFromTwoHashes(expectedStateHash, tHash)
//		}
//
//		asserter.Equal(expectedStateHash, stateHash)
//
//		snapshot, err := appProxy.GetSnapshot(blocks[0].Index())
//		asserter.NoError(err)
//
//		asserter.Equal(expectedStateHash, snapshot)
//
//		//commit a few more blocks, then attempt to restore back to block 0 state
//		for i := 1; i < 5; i++ {
//			_, err := appProxy.CommitBlock(blocks[i])
//			asserter.NoError(err)
//		}
//
//		err = appProxy.Restore(snapshot)
//		asserter.NoError(err)
//	}
}