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

import static io.zeebe.msgpack.mapping.MsgPackNodeType.EXISTING_LEAF_NODE;
import static io.zeebe.msgpack.mapping.MsgPackNodeType.EXTRACTED_LEAF_NODE;

import io.zeebe.msgpack.spec.MsgPackWriter;
import java.util.*;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Represents a tree data structure, for a msg pack document.
 *
 * <p>The nodes of the tree can be either a real node, which has child's, or a leaf, which has a
 * mapping in the corresponding msg pack document to his value.
 *
 * <p>The message pack document tree can be created from scratch from a underlying document. This
 * can be done with the {@link MsgPackDocumentIndexer}. It can also be constructed from only a port
 * of a message pack document. This can be done with the {@link MsgPackDocumentExtractor}.
 *
 * <p>The message pack tree can consist from two different message pack documents. The underlying
 * document, from which the tree is completely build and the extract document, which can be a part
 * of another message pack document. The tree representation of the extract document will be as well
 * added to the current message pack tree object.
 *
 * <p>Since the leafs contains a mapping, which consist of position and length, it is necessary that
 * both documents are available for the message pack tree, so the leaf value can be resolved later.
 * The leafs have to be distinguished, is it a leaf from the underlying document or is it from the
 * extract document. For this distinction the {@link MsgPackNodeType#EXISTING_LEAF_NODE} and {@link
 * MsgPackNodeType#EXTRACTED_LEAF_NODE} are used.
 */
public class MsgPackTree {
  protected final Map<String, MsgPackNodeType> nodeTypeMap; // Bytes2LongHashIndex nodeTypeMap;
  protected final Map<String, Set<String>> nodeChildsMap;
  protected final Map<String, Long> leafMap; // Bytes2LongHashIndex leafMap;

  protected final DirectBuffer underlyingDocument = new UnsafeBuffer(0, 0);
  protected DirectBuffer extractDocument;

  public MsgPackTree() {
    nodeTypeMap = new HashMap<>();
    nodeChildsMap = new HashMap<>();
    leafMap = new HashMap<>();
  }

  public int size() {
    return nodeTypeMap.size();
  }

  public void wrap(DirectBuffer underlyingDocument) {
    clear();
    this.underlyingDocument.wrap(underlyingDocument);
  }

  public void clear() {
    extractDocument = null;
    nodeChildsMap.clear();
    nodeTypeMap.clear();
    leafMap.clear();
  }

  public Set<String> getChilds(String nodeId) {
    return nodeChildsMap.get(nodeId);
  }

  public void addLeafNode(String nodeId, long position, int length) {
    leafMap.put(nodeId, (position << 32) | length);
    nodeTypeMap.put(nodeId, extractDocument == null ? EXISTING_LEAF_NODE : EXTRACTED_LEAF_NODE);
  }

  private void addParentNode(String nodeId, MsgPackNodeType nodeType) {
    nodeTypeMap.put(nodeId, nodeType);
    if (!nodeChildsMap.containsKey(nodeId)) {
      nodeChildsMap.put(nodeId, new LinkedHashSet<>());
    }
  }

  public void addMapNode(String nodeId) {
    if (isLeaf(nodeId)) {
      leafMap.remove(nodeId);
    }
    addParentNode(nodeId, MsgPackNodeType.MAP_NODE);
  }

  public void addArrayNode(String nodeId) {
    addParentNode(nodeId, MsgPackNodeType.ARRAY_NODE);
  }

  public void addChildToNode(String childName, String parentId) {
    nodeChildsMap.get(parentId).add(childName);
  }

  public boolean isLeaf(String nodeId) {
    return leafMap.containsKey(nodeId);
  }

  public boolean isArrayNode(String nodeId) {
    final MsgPackNodeType msgPackNodeType = nodeTypeMap.get(nodeId);
    return msgPackNodeType != null && msgPackNodeType == MsgPackNodeType.ARRAY_NODE;
  }

  public boolean isMapNode(String nodeId) {
    final MsgPackNodeType msgPackNodeType = nodeTypeMap.get(nodeId);
    return msgPackNodeType != null && msgPackNodeType == MsgPackNodeType.MAP_NODE;
  }

  public void setExtractDocument(DirectBuffer documentBuffer) {
    this.extractDocument = documentBuffer;
  }

  public void writeLeafMapping(MsgPackWriter writer, String leafId) {
    final long mapping = leafMap.get(leafId);
    final MsgPackNodeType nodeType = nodeTypeMap.get(leafId);
    final int position = (int) (mapping >> 32);
    final int length = (int) mapping;
    DirectBuffer relatedBuffer = underlyingDocument;
    if (nodeType == EXTRACTED_LEAF_NODE) {
      relatedBuffer = extractDocument;
    }
    writer.writeRaw(relatedBuffer, position, length);
  }

  public void merge(MsgPackTree sourceTree) {
    extractDocument = sourceTree.underlyingDocument;
    for (Map.Entry<String, MsgPackNodeType> leafMapEntry : sourceTree.nodeTypeMap.entrySet()) {
      final String key = leafMapEntry.getKey();
      MsgPackNodeType nodeType = leafMapEntry.getValue();
      if (nodeType == EXISTING_LEAF_NODE) {
        nodeType = EXTRACTED_LEAF_NODE;
      }
      nodeTypeMap.put(key, nodeType);
    }
    leafMap.putAll(sourceTree.leafMap);

    for (Map.Entry<String, Set<String>> nodeChildsEntry : sourceTree.nodeChildsMap.entrySet()) {
      final String key = nodeChildsEntry.getKey();

      // if we change the following condition to if (nodeChildsMap.containsKey(key))
      // we get a deep merge
      if (key.equals(Mapping.JSON_ROOT_PATH)) {
        nodeChildsMap
            .computeIfAbsent(key, (k) -> new LinkedHashSet<>())
            .addAll(nodeChildsEntry.getValue());
      } else {
        nodeChildsMap.put(key, nodeChildsEntry.getValue());
      }
    }
  }
}
