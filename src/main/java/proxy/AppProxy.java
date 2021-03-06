package proxy;

import org.jcsp.lang.One2OneChannel;

import common.RResult;
import common.error;

/**
 * AppProxy provides an interface for lachesis to communicate with the
 * application.
 */
public interface AppProxy {
	One2OneChannel<byte[]> SubmitCh();

	One2OneChannel<poset.InternalTransaction> SubmitInternalCh();

	RResult<byte[]> CommitBlock(poset.Block block);

	RResult<byte[]> GetSnapshot(long blockIndex);

	error Restore(byte[] snapshot);
}