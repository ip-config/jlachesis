package difftool;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Test;

import node.Node;
import node.NodeList;
import node.NodeList.Stoppable;

/**
 * Test of diff
 *
 * @author qn
 *
 */
public class ExampleTest {

	@Test
	public void testPem() {
		Logger logger = Logger.getLogger(this.getClass());
		logger.setLevel(Level.FATAL);

		NodeList nodes = new NodeList(3, logger);

		Stoppable stop = nodes.StartRandTxStream();
		nodes.WaitForBlock(5);
		stop.stop();

		Node[] nodeArray = nodes.Values();

		Diff diff = Diff.compare(nodeArray[0],  nodeArray[1]);

		System.out.println(diff);

//		if !diffResult.IsEmpty() {
//			logger.Fatal("\n" + diffResult.ToString())
//		}
//		fmt.Println("all good")
		// Output:
		// all good
	}

}