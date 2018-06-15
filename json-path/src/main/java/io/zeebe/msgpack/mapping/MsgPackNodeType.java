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

/**
 * Represents the existing types for an message pack node. Is used in combination with the {@link
 * MsgPackTree}.
 */
public enum MsgPackNodeType {
  MAP_NODE,
  ARRAY_NODE,

  /**
   * Nodes of type {@link #EXISTING_LEAF_NODE} represents leafs, which are already exist in the
   * message pack document. These node types are used in indexing of a message pack document, they
   * have to be merged with new documents.
   */
  EXISTING_LEAF_NODE,

  /**
   * Nodes of type {@link #EXTRACTED_LEAF_NODE} represents leafs which are extracted from a message
   * pack document. These leaf node is created in the new document.
   */
  EXTRACTED_LEAF_NODE
}
