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

import static io.zeebe.msgpack.mapping.MappingBuilder.createMapping;
import static io.zeebe.msgpack.mapping.MappingBuilder.createMappings;
import static io.zeebe.msgpack.mapping.MappingTestUtil.*;
import static io.zeebe.msgpack.spec.MsgPackHelper.EMTPY_OBJECT;
import static io.zeebe.msgpack.spec.MsgPackHelper.NIL;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** Represents a test class to test the merge documents functionality with help of mappings. */
public class MappingMergeTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private MappingProcessor processor = new MappingProcessor(1024);

  @Test
  public void shouldThrowExceptionIfSourceDocumentIsNull() throws Throwable {
    // given payload
    final Mapping[] mapping = createMapping("$", "$");

    // expect
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Target document must not be null!");

    // when
    processor.merge(null, null, mapping);
  }

  @Test
  public void shouldThrowExceptionIfTargetDocumentIsNull() throws Throwable {
    // given payload
    final DirectBuffer targetDocument = new UnsafeBuffer(EMTPY_OBJECT);
    final Mapping[] mapping = createMapping("$", "$");

    // expect
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Source document must not be null!");

    // when
    processor.merge(null, targetDocument, mapping);
  }

  @Test
  public void shouldThrowExceptionIfMappingDoesNotMatch() throws Throwable {
    // given payload
    final DirectBuffer sourceDocument = new UnsafeBuffer(EMTPY_OBJECT);
    final Mapping[] mapping = createMapping("$.foo", "$");

    // expect
    expectedException.expect(MappingException.class);
    expectedException.expectMessage("No data found for query $.foo.");

    // when
    processor.merge(sourceDocument, sourceDocument, mapping);
  }

  @Test
  public void shouldThrowExceptionIfResultDocumentIsNoObject() throws Throwable {
    // given payload
    final DirectBuffer sourceDocument =
        new UnsafeBuffer(MSGPACK_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree("{'foo':'bar'}")));
    final Mapping[] mapping = createMapping("$.foo", "$");

    // expect
    expectedException.expect(MappingException.class);
    expectedException.expectMessage(
        "Processing failed, since mapping will result in a non map object (json object).");

    // when
    processor.merge(sourceDocument, sourceDocument, mapping);
  }

  @Test
  public void shouldMergeAndExtract() throws Throwable {
    // given payload
    final DirectBuffer sourceDocument = new UnsafeBuffer(MSG_PACK_BYTES);
    final Mapping[] extractMapping =
        createMappings().mapping(NODE_JSON_OBJECT_PATH, NODE_ROOT_PATH).build();

    // when extract
    int resultLength = processor.extract(sourceDocument, extractMapping);
    MutableDirectBuffer resultBuffer = processor.getResultBuffer();
    byte result[] = new byte[resultLength];
    resultBuffer.getBytes(0, result, 0, resultLength);

    // then expect result
    assertThat(MSGPACK_MAPPER.readTree(result))
        .isEqualTo(JSON_MAPPER.readTree("{'testAttr':'test'}"));

    // when merge after that
    final Mapping[] mergeMapping = createMappings().mapping("$.testAttr", "$.otherAttr").build();
    final UnsafeBuffer trimmedDocument = new UnsafeBuffer(0, 0);
    trimmedDocument.wrap(result);

    resultLength = processor.merge(trimmedDocument, sourceDocument, mergeMapping);
    resultBuffer = processor.getResultBuffer();
    result = new byte[resultLength];
    resultBuffer.getBytes(0, result, 0, resultLength);

    // then result is expected as
    final JsonNode expected =
        JSON_MAPPER.readTree(
            "{'boolean':false,'string':'value','array':[0,1,2,3],"
                + "'double':0.3,'integer':1024,'jsonObject':{'testAttr':'test'},"
                + "'long':9223372036854775807,'otherAttr':'test'}");
    assertThat(MSGPACK_MAPPER.readTree(result)).isEqualTo(expected);
  }

  @Test
  public void shouldMergeTwice() throws Throwable {
    // given documents
    DirectBuffer sourceDocument =
        new UnsafeBuffer(
            MSGPACK_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree("{'test':'thisValue'}")));

    final DirectBuffer targetDocument =
        new UnsafeBuffer(
            MSGPACK_MAPPER.writeValueAsBytes(
                JSON_MAPPER.readTree("{'arr':[0, 1], 'obj':{'int':1}, 'test':'value'}")));

    Mapping[] mergeMapping = createMappings().mapping("$.test", "$.obj").build();

    // when merge
    int resultLength = processor.merge(sourceDocument, targetDocument, mergeMapping);
    MutableDirectBuffer resultBuffer = processor.getResultBuffer();
    byte result[] = new byte[resultLength];
    resultBuffer.getBytes(0, result, 0, resultLength);

    // then expect result
    assertThat(MSGPACK_MAPPER.readTree(result))
        .isEqualTo(JSON_MAPPER.readTree("{'arr':[0, 1], 'obj':'thisValue', 'test':'value'}"));

    // new source and mappings
    sourceDocument =
        new UnsafeBuffer(
            MSGPACK_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree("{'other':[2, 3]}")));

    targetDocument.wrap(result);
    mergeMapping = createMappings().mapping("$.other[0]", "$.arr[0]").build();

    // when again merge after that
    resultLength = processor.merge(sourceDocument, targetDocument, mergeMapping);
    resultBuffer = processor.getResultBuffer();
    result = new byte[resultLength];
    resultBuffer.getBytes(0, result, 0, resultLength);

    // then expect result
    assertThat(MSGPACK_MAPPER.readTree(result))
        .isEqualTo(JSON_MAPPER.readTree("{'arr':[2, 1], 'obj':'thisValue', 'test':'value'}"));
  }

  @Test
  public void shouldMergeTwiceWithoutMappings() throws Throwable {
    // given documents
    DirectBuffer sourceDocument =
        new UnsafeBuffer(
            MSGPACK_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree("{'test':'thisValue'}")));

    final DirectBuffer targetDocument =
        new UnsafeBuffer(
            MSGPACK_MAPPER.writeValueAsBytes(
                JSON_MAPPER.readTree("{'arr':[0, 1], 'obj':{'int':1}, 'test':'value'}")));

    // when merge
    int resultLength = processor.merge(sourceDocument, targetDocument);
    MutableDirectBuffer resultBuffer = processor.getResultBuffer();
    byte result[] = new byte[resultLength];
    resultBuffer.getBytes(0, result, 0, resultLength);

    // then expect result
    assertThat(MSGPACK_MAPPER.readTree(result))
        .isEqualTo(JSON_MAPPER.readTree("{'arr':[0, 1], 'obj':{'int':1}, 'test':'thisValue'}"));

    // new source and mappings
    sourceDocument =
        new UnsafeBuffer(
            MSGPACK_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree("{'other':[2, 3]}")));

    targetDocument.wrap(result);

    // when again merge after that
    resultLength = processor.merge(sourceDocument, targetDocument);
    resultBuffer = processor.getResultBuffer();
    result = new byte[resultLength];
    resultBuffer.getBytes(0, result, 0, resultLength);

    // then expect result
    assertThat(MSGPACK_MAPPER.readTree(result))
        .isEqualTo(
            JSON_MAPPER.readTree(
                "{'arr':[0, 1], 'obj':{'int':1}, 'test':'thisValue', 'other':[2, 3]}"));
  }

  @Test
  public void shouldMergeNilWithEmptyObject() throws Throwable {
    // given payload
    final DirectBuffer sourceDocument = new UnsafeBuffer(NIL);
    final DirectBuffer targetDocument = new UnsafeBuffer(EMTPY_OBJECT);

    // when
    final int resultLength = processor.merge(sourceDocument, targetDocument);

    // then
    final DirectBuffer result = new UnsafeBuffer(0, 0);
    result.wrap(processor.getResultBuffer(), 0, resultLength);
    assertThat(result).isEqualByComparingTo(new UnsafeBuffer(EMTPY_OBJECT));
  }

  @Test
  public void shouldMergeEmptyObjectWithNil() throws Throwable {
    // given payload
    final DirectBuffer sourceDocument = new UnsafeBuffer(EMTPY_OBJECT);
    final DirectBuffer targetDocument = new UnsafeBuffer(NIL);

    // when
    final int resultLength = processor.merge(sourceDocument, targetDocument);

    // then
    final DirectBuffer result = new UnsafeBuffer(0, 0);
    result.wrap(processor.getResultBuffer(), 0, resultLength);
    assertThat(result).isEqualByComparingTo(new UnsafeBuffer(EMTPY_OBJECT));
  }

  @Test
  public void shouldMergeNilWithNil() throws Throwable {
    // given payload
    final DirectBuffer sourceDocument = new UnsafeBuffer(NIL);
    final DirectBuffer targetDocument = new UnsafeBuffer(NIL);

    // when
    final int resultLength = processor.merge(sourceDocument, targetDocument);

    // then
    final DirectBuffer result = new UnsafeBuffer(0, 0);
    result.wrap(processor.getResultBuffer(), 0, resultLength);
    assertThat(result).isEqualByComparingTo(new UnsafeBuffer(NIL));
  }
}
