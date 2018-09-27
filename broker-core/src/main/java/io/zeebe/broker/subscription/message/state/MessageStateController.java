/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.subscription.message.state;

import io.zeebe.broker.util.KeyStateController;
import java.io.File;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;

public class MessageStateController extends KeyStateController {

  private static final byte[] EXISTENCE = new byte[] {1};

  private static final int TIME_OFFSET = 0;
  private static final int KEY_OFFSET = TIME_OFFSET + Long.BYTES;

  private static final byte[] MSG_TIME_TO_LIVE_NAME = "msgTimeToLive".getBytes();
  private static final byte[] MSG_ID_NAME = "messageId".getBytes();

  private final UnsafeBuffer iterateKeyBuffer = new UnsafeBuffer(0, 0);

  protected ColumnFamilyHandle timeToLiveHandle;
  private ColumnFamilyHandle messageIdHandle;

  private ExpandableArrayBuffer keyBuffer;
  private ExpandableArrayBuffer valueBuffer;
  private SubscriptionState<MessageSubscription> subscriptionState;

  @Override
  public RocksDB open(final File dbDirectory, final boolean reopen) throws Exception {
    final RocksDB rocksDB = super.open(dbDirectory, reopen);
    keyBuffer = new ExpandableArrayBuffer();
    valueBuffer = new ExpandableArrayBuffer();

    timeToLiveHandle = createColumnFamily(MSG_TIME_TO_LIVE_NAME);
    messageIdHandle = createColumnFamily(MSG_ID_NAME);
    subscriptionState = new SubscriptionState<>(this, () -> new MessageSubscription());

    return rocksDB;
  }

  public void put(final Message message) {
    message.writeKey(keyBuffer, TIME_OFFSET);
    message.write(valueBuffer, 0);

    final int keyLength = message.getKeyLength();
    final int messageLength = message.getLength();
    writeKeyWithoutTimeWithValue(getDb().getDefaultColumnFamily(), keyLength, messageLength);
    writeTimeAndKey(timeToLiveHandle, keyLength);

    final DirectBuffer messageId = message.getId();
    final int messageIdLength = messageId.capacity();
    if (messageIdLength > 0) {
      int offset = keyLength;
      keyBuffer.putBytes(offset, messageId, 0, messageIdLength);
      offset += messageIdLength;
      put(
          messageIdHandle,
          keyBuffer.byteArray(),
          KEY_OFFSET,
          offset - KEY_OFFSET,
          EXISTENCE,
          0,
          EXISTENCE.length);
    }
  }

  public void put(final MessageSubscription subscription) {
    subscriptionState.put(subscription);
  }

  public void updateCommandSentTime(final MessageSubscription subscription) {
    subscriptionState.updateCommandSentTime(subscription);
  }

  private void writeKeyWithoutTimeWithValue(
      final ColumnFamilyHandle handle, final int timeWithkeyLength, final int valueLength) {
    put(
        handle,
        keyBuffer.byteArray(),
        KEY_OFFSET,
        timeWithkeyLength - KEY_OFFSET,
        valueBuffer.byteArray(),
        0,
        valueLength);
  }

  /**
   * The time + name + correlation key acts as key and maps to name and correlation key. This is to
   * sort and find keys which are before and given timestamp.
   *
   * @param keyLength
   */
  private void writeTimeAndKey(final ColumnFamilyHandle handle, final int keyLength) {
    put(handle, keyBuffer.byteArray(), TIME_OFFSET, keyLength, EXISTENCE, 0, EXISTENCE.length);
  }

  public Message findMessage(final DirectBuffer name, final DirectBuffer correlationKey) {
    final int offset =
        Message.writeMessageKeyToBuffer(keyBuffer, TIME_OFFSET, name, correlationKey);
    return getMessage(keyBuffer, TIME_OFFSET, offset);
  }

  private Message getMessage(final DirectBuffer buffer, final int offset, final int length) {
    final int valueBufferSize = valueBuffer.capacity();
    final int readBytes =
        get(buffer.byteArray(), offset, length, valueBuffer.byteArray(), 0, valueBufferSize);

    if (readBytes > 0) {
      final Message message = new Message();
      message.wrap(valueBuffer, 0, readBytes);

      return message;
    } else if (readBytes > valueBufferSize) {
      valueBuffer.checkLimit(readBytes);
      // try again
      return getMessage(buffer, offset, length);
    } else {
      return null;
    }
  }

  public List<MessageSubscription> findSubscriptions(
      final DirectBuffer messageName, final DirectBuffer correlationKey) {
    return subscriptionState.findSubscriptions(messageName, correlationKey);
  }

  public List<Message> findMessageBefore(final long timestamp) {
    final List<Message> messageList = new ArrayList<>();
    foreach(
        timeToLiveHandle,
        (key, value) -> {
          iterateKeyBuffer.wrap(key);
          final long time = iterateKeyBuffer.getLong(TIME_OFFSET, ByteOrder.LITTLE_ENDIAN);

          if (time <= timestamp) {
            final int keyLength = key.length - KEY_OFFSET;
            messageList.add(getMessage(iterateKeyBuffer, KEY_OFFSET, keyLength));
          }
        });
    return messageList;
  }

  public List<MessageSubscription> findSubscriptionBefore(final long deadline) {
    return subscriptionState.findSubscriptionBefore(deadline);
  }

  public boolean exist(final Message message) {
    message.writeKey(keyBuffer, TIME_OFFSET);
    int offset = message.getKeyLength();
    final int idLength = message.getId().capacity();
    keyBuffer.putBytes(offset, message.getId(), 0, idLength);
    offset += idLength;

    return exist(messageIdHandle, keyBuffer.byteArray(), KEY_OFFSET, offset - KEY_OFFSET);
  }

  public boolean exist(final MessageSubscription subscription) {
    return subscriptionState.exist(subscription);
  }

  public void remove(final Message message) {
    message.writeKey(keyBuffer, TIME_OFFSET);

    final int keyLength = message.getKeyLength() - KEY_OFFSET;
    remove(keyBuffer.byteArray(), KEY_OFFSET, keyLength);
    remove(timeToLiveHandle, keyBuffer.byteArray(), 0, message.getKeyLength());

    final DirectBuffer messageId = message.getId();
    final int messageIdLength = messageId.capacity();
    if (messageIdLength > 0) {
      int offset = message.getKeyLength();
      keyBuffer.putBytes(offset, messageId, 0, messageIdLength);
      offset += messageIdLength;

      remove(messageIdHandle, keyBuffer.byteArray(), KEY_OFFSET, offset - KEY_OFFSET);
    }
  }

  public void remove(MessageSubscription messageSubscription) {
    subscriptionState.remove(messageSubscription);
  }
}
