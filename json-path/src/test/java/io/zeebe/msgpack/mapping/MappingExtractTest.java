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
import static io.zeebe.msgpack.mapping.MappingTestUtil.JSON_MAPPER;
import static io.zeebe.msgpack.mapping.MappingTestUtil.MSGPACK_MAPPER;
import static io.zeebe.msgpack.spec.MsgPackHelper.EMTPY_OBJECT;
import static org.assertj.core.api.Assertions.assertThat;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** Represents a test class to test the extract document functionality with help of mappings. */
public class MappingExtractTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private MappingProcessor processor = new MappingProcessor(1024);

  @Test
  public void shouldThrowExceptionIfDocumentIsNull() throws Throwable {
    // given mapping
    final Mapping[] mapping = createMapping("$", "$");

    // expect
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage("Source document must not be null!");

    // when
    processor.extract(null, mapping);
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
    processor.extract(sourceDocument, mapping);
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
    processor.extract(sourceDocument, mapping);
  }

  @Test
  public void shouldExtractTwice() throws Throwable {
    // given documents
    final DirectBuffer sourceDocument =
        new UnsafeBuffer(
            MSGPACK_MAPPER.writeValueAsBytes(
                JSON_MAPPER.readTree(
                    "{'arr':[{'deepObj':{'value':123}}, 1], 'obj':{'int':1}, 'test':'value'}")));
    Mapping[] extractMapping = createMappings().mapping("$.arr[0]", "$").build();

    // when merge
    int resultLength = processor.extract(sourceDocument, extractMapping);
    MutableDirectBuffer resultBuffer = processor.getResultBuffer();
    byte result[] = new byte[resultLength];
    resultBuffer.getBytes(0, result, 0, resultLength);

    // then expect result
    assertThat(MSGPACK_MAPPER.readTree(result))
        .isEqualTo(JSON_MAPPER.readTree("{'deepObj':{'value':123}}"));

    // new source and mappings
    sourceDocument.wrap(result);
    extractMapping = createMappings().mapping("$.deepObj", "$").build();

    // when again merge after that
    resultLength = processor.extract(sourceDocument, extractMapping);
    resultBuffer = processor.getResultBuffer();
    result = new byte[resultLength];
    resultBuffer.getBytes(0, result, 0, resultLength);

    // then expect result
    assertThat(MSGPACK_MAPPER.readTree(result)).isEqualTo(JSON_MAPPER.readTree("{'value':123}"));
  }

  @Test
  public void shouldExtractTwiceWithoutMapping() throws Throwable {
    // given documents
    final DirectBuffer sourceDocument =
        new UnsafeBuffer(
            MSGPACK_MAPPER.writeValueAsBytes(
                JSON_MAPPER.readTree(
                    "{'arr':[{'deepObj':{'value':123}}, 1], 'obj':{'int':1}, 'test':'value'}")));

    // when merge
    int resultLength = processor.extract(sourceDocument);
    MutableDirectBuffer resultBuffer = processor.getResultBuffer();
    byte result[] = new byte[resultLength];
    resultBuffer.getBytes(0, result, 0, resultLength);

    // then expect result
    assertThat(MSGPACK_MAPPER.readTree(result))
        .isEqualTo(
            JSON_MAPPER.readTree(
                "{'arr':[{'deepObj':{'value':123}}, 1], 'obj':{'int':1}, 'test':'value'}}"));

    // new source and mappings
    sourceDocument.wrap(MSGPACK_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree("{'foo':'bar'}")));

    // when again merge after that
    resultLength = processor.extract(sourceDocument);
    resultBuffer = processor.getResultBuffer();
    result = new byte[resultLength];
    resultBuffer.getBytes(0, result, 0, resultLength);

    // then expect result
    assertThat(MSGPACK_MAPPER.readTree(result)).isEqualTo(JSON_MAPPER.readTree("{'foo':'bar'}"));
  }
}
