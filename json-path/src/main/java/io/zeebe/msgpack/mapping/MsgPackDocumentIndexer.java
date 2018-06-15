/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.msgpack.mapping;

import static io.zeebe.msgpack.mapping.MsgPackTreeNodeIdConstructor.*;

import io.zeebe.msgpack.query.MsgPackTokenVisitor;
import io.zeebe.msgpack.query.MsgPackTraverser;
import io.zeebe.msgpack.spec.MsgPackToken;
import io.zeebe.msgpack.spec.MsgPackType;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import org.agrona.DirectBuffer;

/**
 * Represents an message pack document indexer. During the indexing of an existing message pack
 * document an {@link MsgPackTree} object will be constructed, which corresponds to the structure of
 * the message pack document.
 *
 * <p>
 *
 * <p>Example:
 *
 * <pre>{@code
 * Say we have the following json as message pack document:
 * {
 *   "object1":{ "field1": true, "array":[ 1,2,3]},
 *   "field2" : "String"
 * }
 * }</pre>
 *
 * <p>
 *
 * <pre>
 * The {@link #index()} method will return an {@link MsgPackTree} object,
 * which has the following structure:
 * {@code
 *          $
 *        /   \
 *   field2   object1
 *     |     /       \
 * String  field1   array
 *          |      /  |  \
 *         true   1   2   3
 * }
 * </pre>
 *
 * <p>Then this correspond to the following message pack tree structure:
 *
 * <pre>NodeTypes:
 * {@code
 *
 * 1object1 : MAP_NODE
 * 2field1 : LEAF
 * 2array : ARRAY_NODE
 * 1field2: LEAF
 * }
 * </pre>
 *
 * <pre>NodeChildsMap:
 * {@code
 *
 * 1object1: field1, array
 * 2array : 2array1, 2array2, 2array3,
 * }
 * </pre>
 *
 * <pre>LeafMap:
 * {@code
 *
 * 2field1: mapping
 * 2array1: mapping
 * 2array2: mapping
 * 2array3: mapping
 * 1field2: mapping
 * }
 * </pre>
 */
public final class MsgPackDocumentIndexer implements MsgPackTokenVisitor {
  /** The message pack tree which is constructed via the indexing of the message pack document. */
  private MsgPackTree msgPackTree;

  /** The last key for the node, since the node is divided in separate MsgPackTokens. */
  private final byte lastKey[] = new byte[MappingProcessor.MAX_JSON_KEY_LEN];

  /** The length of the last key. */
  private int lastKeyLen;

  /** The type of the last processed node. */
  private MsgPackType lastType;

  /**
   * Contains the current parents of the current node. A node become a parent if the node is of type
   * MAP or ARRAY. This node will be added several times, corresponding to the size of the
   * MsgPackToken#size of this node.
   */
  private final Deque<String> parentsStack = new ArrayDeque<>();

  /** Indicates if the current value belongs to an array. */
  private final Deque<Boolean> arrayValueStack = new ArrayDeque<>();

  /** Contains the type of the last msg pack token. */
  private final Deque<MsgPackType> lastTypeStack = new ArrayDeque<>();

  /** The traverser which is used to index the message pack document. */
  private final MsgPackTraverser traverser = new MsgPackTraverser();

  public MsgPackDocumentIndexer() {
    msgPackTree = new MsgPackTree();
    lastKey[0] = '$';
    lastKeyLen = 1;
  }

  public void wrap(DirectBuffer msgPackDocument) {
    msgPackTree.wrap(msgPackDocument);
    traverser.wrap(msgPackDocument, 0, msgPackDocument.capacity());
  }

  public MsgPackTree index() {
    traverser.traverse(this);
    return msgPackTree;
  }

  @Override
  public void visitElement(int position, MsgPackToken currentValue) {
    final MsgPackType currentValueType = currentValue.getType();
    if (position != 0 || currentValueType != MsgPackType.NIL) {
      lastType = getLastNodeType();

      if (lastType == MsgPackType.MAP) {
        final DirectBuffer valueBuffer = currentValue.getValueBuffer();
        lastKeyLen = valueBuffer.capacity();
        valueBuffer.getBytes(0, lastKey, 0, lastKeyLen);
        lastTypeStack.push(MsgPackType.EXTENSION);
      } else {
        if (currentValueType == MsgPackType.MAP || currentValueType == MsgPackType.ARRAY) {
          final int childSize = currentValue.getSize();
          addNewParent(childSize, currentValueType);
        } else {
          processValueNode(position, currentValue);
        }
      }
    }
  }

  private MsgPackType getLastNodeType() {
    final MsgPackType lastType;
    if (lastTypeStack.isEmpty()) {
      lastType = MsgPackType.EXTENSION;
    } else {
      lastType = lastTypeStack.pop();
    }
    return lastType;
  }

  /**
   * Creates and returns the nodeId, which consist of the parentId and the node name.
   *
   * @param nodeName the name of the current node
   * @return the id of the current node
   */
  private String createNodeId(String nodeName) {
    if (!parentsStack.isEmpty()) {
      final String parentId = parentsStack.peek();
      return construct(parentId, nodeName);
    }
    return nodeName;
  }

  /**
   * Adds a new parent of the given type with the given child size to the internal data structure.
   *
   * @param childCount the count of child's
   * @param currentMsgPackType the message pack type of the current node
   */
  private void addNewParent(int childCount, MsgPackType currentMsgPackType) {
    final String nodeName = getNodeName(lastKey, lastKeyLen);
    String nodeId;
    final boolean isArrayValue;
    if (!arrayValueStack.isEmpty()) {
      isArrayValue = arrayValueStack.pop();
      nodeId = parentsStack.pop();

      if (lastType != MsgPackType.ARRAY) {
        parentsStack.push(nodeId);
        nodeId = construct(nodeId, nodeName);
      }
    } else {
      nodeId = createNodeId(nodeName);
      isArrayValue = false;
    }

    addParentNodeToTree(currentMsgPackType == MsgPackType.ARRAY, nodeName, nodeId);

    addParentForChildCountToStacks(childCount, currentMsgPackType, nodeId, isArrayValue);
  }

  private void addParentNodeToTree(boolean isArray, String nodeName, String nodeId) {
    if (isArray) {
      msgPackTree.addArrayNode(nodeId);
    } else {
      msgPackTree.addMapNode(nodeId);
    }

    if (!parentsStack.isEmpty() && lastType != MsgPackType.ARRAY) {
      final String parentId = parentsStack.pop();
      msgPackTree.addChildToNode(nodeName, parentId);
    }
  }

  private void addParentForChildCountToStacks(
      int childCount, MsgPackType currentMsgPackType, String nodeId, boolean isArrayValue) {
    for (int i = 0; i < childCount; i++) {
      if (currentMsgPackType == MsgPackType.ARRAY) {
        msgPackTree.addChildToNode("" + i, nodeId);
        parentsStack.push(construct(nodeId, "" + (childCount - 1 - i)));
        arrayValueStack.push(true);
      } else {
        parentsStack.push(nodeId);
        if (isArrayValue) {
          arrayValueStack.push(true);
        }
      }
      lastTypeStack.push(currentMsgPackType);
    }
  }

  /**
   * The current node is a simple value and the currentValue reference to a token of another type
   * except MAP and ARRAY.
   *
   * <p>This method process the value node and inserts the node properties into the tree data
   * structure. It will add the nodeType ( type = leaf), pops a parent of the stack and add this
   * node to his child list. Also the leaf mapping will be created and inserted.
   *
   * @param position the position of the token in the payload
   * @param currentValue the message pack token which represents a simple value
   */
  private void processValueNode(long position, MsgPackToken currentValue) {
    String parentId = parentsStack.pop();
    final String nodeName;
    final String nodeId;

    if (!arrayValueStack.isEmpty()) {
      if (lastType != MsgPackType.ARRAY) {
        // object in array
        nodeName = getNodeName(lastKey, lastKeyLen);
        nodeId = construct(parentId, nodeName);
      } else {
        // plain array value
        nodeId = parentId;
        // node name is array value index
        nodeName = getArrayValueIndex(parentId);
        // parent id is without idx
        parentId = getLastParentId(parentId);
      }

      arrayValueStack.pop();
    } else {
      nodeName = getNodeName(lastKey, lastKeyLen);
      nodeId = construct(parentId, nodeName);
    }

    msgPackTree.addChildToNode(nodeName, parentId);
    msgPackTree.addLeafNode(nodeId, position, currentValue.getTotalLength());
  }

  /**
   * Extracts the index of the array value from the current node id.
   *
   * <p>Example: * given nodeId = "$[arrayId][0]" * returns "0" as string Even if the nodeId
   * contains square brackets in the node name like "$[array[Id]][0]" will return the right index
   * "0".
   *
   * @param nodeId the node id which contains the index
   * @return the array value index
   */
  private static String getArrayValueIndex(String nodeId) {
    final int lastIndex = nodeId.lastIndexOf(JSON_PATH_SEPARATOR);
    final String nodeName =
        nodeId.substring(
            lastIndex + JSON_PATH_SEPARATOR.length(),
            nodeId.length() - JSON_PATH_SEPARATOR_END.length());
    return nodeName;
  }

  /** Clears the preprocessor and resets to the initial state. */
  public void clear() {
    lastKey[0] = '$';
    lastKeyLen = 1;
    parentsStack.clear();
    arrayValueStack.clear();
    lastTypeStack.clear();
  }

  /**
   * Returns the node name.
   *
   * @param bytes the bytes which contains the name
   * @param length the length of the name
   * @return the name as string
   */
  private String getNodeName(byte[] bytes, int length) {
    final byte nameBytes[] = Arrays.copyOf(bytes, length);
    return new String(nameBytes);
  }
}
