package com.dianping.cat.storage.message;

import java.io.IOException;

import com.dianping.cat.message.internal.MessageId;
import com.dianping.cat.message.spi.MessageTree;

public interface MessageBucketManager {
	public MessageTree loadMessage(String messageId) throws IOException;

	public void storeMessage(MessageTree tree,MessageId id) throws IOException;
}
