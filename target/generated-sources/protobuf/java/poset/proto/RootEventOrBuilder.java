// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: poset/root.proto

package poset.proto;

public interface RootEventOrBuilder extends
    // @@protoc_insertion_point(interface_extends:poset.proto.RootEvent)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>string Hash = 1;</code>
   */
  java.lang.String getHash();
  /**
   * <code>string Hash = 1;</code>
   */
  com.google.protobuf.ByteString
      getHashBytes();

  /**
   * <code>int64 CreatorID = 2;</code>
   */
  long getCreatorID();

  /**
   * <code>int64 Index = 3;</code>
   */
  long getIndex();

  /**
   * <code>int64 LamportTimestamp = 4;</code>
   */
  long getLamportTimestamp();

  /**
   * <code>int64 Round = 5;</code>
   */
  long getRound();
}
