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
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.runners.Parameterized.Parameter;
import static org.junit.runners.Parameterized.Parameters;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Represents a test class to test the merge documents functionality with help of mappings. */
@RunWith(Parameterized.class)
public class MappingMergeParameterizedTest {
  @Parameters(name = "Test {index}: merge {0} and {1} to {3}")
  public static Iterable<Object[]> parameters() throws Exception {
    return Arrays.asList(
        new Object[][] {
          {
            // source
            "{}",
            // target
            "{}",
            // mapping
            createMapping("$", "$"),
            // expected result
            "{}"
          },
          {
            // source
            "{'hallo':'twsewas','int':1}",
            // target
            "{'foo':'bar','int':3}",
            // mapping
            createMapping("$", "$"),
            // expected result
            "{'hallo':'twsewas','int':1}"
          },
          {
            // source
            "{'hallo':'twsewas','int':1}",
            // target
            "{'foo':'bar','int':3}",
            // mapping
            null,
            // expected result
            "{'hallo':'twsewas','foo':'bar','int':1}"
          },
          {
            // source
            "{'foo':'bar','int':1}",
            // target
            "{}",
            // mapping
            createMapping("$", "$"),
            // expected result
            "{'foo':'bar','int':1}"
          },
          {
            // source
            "{'foo':'bar','int':1}",
            // target
            "{'foo':'bar','int':2}",
            // mapping
            createMapping("$", "$"),
            // expected result
            "{'foo':'bar','int':1}"
          },
          {
            // source
            "{'foo':'bar','int':1,'obj':{'test':'ok'},'array':[1,2,3]}",
            // target
            "{}",
            // mapping
            createMapping("$", "$"),
            // expected result
            "{'foo':'bar','int':1,'obj':{'test':'ok'},'array':[1,2,3]}"
          },
          {
            // source
            "{'foo':'bar','int':1,'obj':{'test':'ok'},'array':[1,2,3]}",
            // target
            "{'foo':'bar','int':3,'obj':{'test':'ok'},'array':[1],'test':'value'}",
            // mapping
            createMapping("$", "$"),
            // expected result
            "{'foo':'bar','int':1,'obj':{'test':'ok'},'array':[1,2,3]}"
          },
          {
            // source
            "{'foo':'bar','int':1,'obj':{'test':'ok'},'array':[1,2,3]}",
            // target
            "{'foo':'bar','int':3,'obj':{'test':'ok'},'array':[1],'test':'value'}",
            // mapping
            null,
            // expected result
            "{'foo':'bar','int':1,'obj':{'test':'ok'},'array':[1,2,3],'test':'value'}"
          },
          {
            // source
            "{'arr':["
                + "{'obj':{'value':'x',"
                + " 'otherArr':[{'test':'hallo'}, {'obj':{'arr':[0, 1]}} ]}"
                + "}, {'otherValue':1}],"
                + " 'ab':{'b':{'value':'y'}}}",
            // target
            "{'foo':'bar','int':3,'obj':{'test':'ok'},'array':[1],'test':'value'}",
            // mapping
            null,
            // expected result
            "{'arr':["
                + "{'obj':{'value':'x',"
                + " 'otherArr':[{'test':'hallo'}, {'obj':{'arr':[0, 1]}} ]}"
                + "}, {'otherValue':1}], "
                + "'ab':{'b':{'value':'y'}},"
                + "'foo':'bar','int':3,'obj':{'test':'ok'},'array':[1],'test':'value'}"
          },
          {
            // source
            "{'arr':["
                + "{'obj':{'value':'x',"
                + " 'otherArr':[{'test':'hallo'}, {'obj':{'arr':[0, 1]}} ]}"
                + "}, {'otherValue':1}],"
                + " 'ab':{'b':{'value':'y'}}}",
            // target
            "{'foo':'bar','int':3,'obj':{'test':'ok'},'array':[1],'test':'value'}",
            // mapping
            createMapping("$", "$"),
            // expected result
            "{'arr':["
                + "{'obj':{'value':'x',"
                + " 'otherArr':[{'test':'hallo'}, {'obj':{'arr':[0, 1]}} ]}"
                + "}, {'otherValue':1}],"
                + " 'ab':{'b':{'value':'y'}}}"
          },
          {
            // source
            "{'arr':["
                + "{'obj':{'value':'x',"
                + " 'otherArr':[{'test':'hallo'}, {'obj':{'arr':[0, 1]}} ]}"
                + "}, {'otherValue':1}],"
                + " 'ab':{'b':{'value':'y'}}}",
            // target
            "{'foo':'bar','int':3,'ab':{'c':{'value':'z'}},'array':[1],'test':'value'}",
            // mapping
            null,
            // expected result
            "{'arr':["
                + "{'obj':{'value':'x',"
                + " 'otherArr':[{'test':'hallo'}, {'obj':{'arr':[0, 1]}} ]}"
                + "}, {'otherValue':1}],"
                + " 'ab':{'b':{'value':'y'}},"
                + "'foo':'bar','int':3,'array':[1],'test':'value'}"
          },
          {
            // source
            "{'foo':'bar','int':2}",
            // target
            "{'int':1}",
            // mapping
            createMapping("$.foo", "$.newFoo"),
            // expected result
            "{'newFoo':'bar','int':1}"
          },
          {
            // source
            "{'foo':'bar','int':2}",
            // target
            "{'int':1,'newFoo':'value'}",
            // mapping
            createMapping("$.foo", "$.newFoo"),
            // expected result
            "{'newFoo':'bar','int':1}"
          },
          {
            // source
            "{'foo':'bar','int':2}",
            // target
            "{'int':1}",
            // mapping
            createMapping("$.foo", "$.newFoo.newDepth.string"),
            // expected result
            "{'newFoo':{'newDepth':{'string':'bar'}}, 'int':1}"
          },
          {
            // source
            "{'foo':'bar','int':2}",
            // target
            "{'int':1,'newFoo':'value'}",
            // mapping
            createMapping("$.foo", "$.newFoo.newDepth.string"),
            // expected result
            "{'newFoo':{'newDepth':{'string':'bar'}}, 'int':1}"
          },
          {
            // source
            "{'obj':{'attr':'text'},'int':2}",
            // target
            "{'int':1}",
            // mapping
            createMapping("$.obj", "$.newObj"),
            // expected result
            "{'newObj':{'attr':'text'},'int':1}"
          },
          {
            // source
            "{'obj':{'attr':'text'},'int':2}",
            // target
            "{'newObj':'value','int':1}",
            // mapping
            createMapping("$.obj", "$.newObj"),
            // expected result
            "{'newObj':{'attr':'text'},'int':1}"
          },
          {
            // source
            "{'obj':{'attr':'text'},'int':2}",
            // target
            "{'newObj':{'attr':'value'},'int':1}",
            // mapping
            createMapping("$.obj", "$.newObj"),
            // expected result
            "{'newObj':{'attr':'text'},'int':1}"
          },
          {
            // source
            "{'obj':{'attr':'text'},'int':2}",
            // target
            "{'newObj':[1,2],'int':1}",
            // mapping
            createMapping("$.obj", "$.newObj"),
            // expected result
            "{'newObj':{'attr':'text'},'int':1}"
          },
          {
            // source
            "{'array':[1, 2, 3],'int':2}",
            // target
            "{'int':1}",
            // mapping
            createMapping("$.array", "$.newArray"),
            // expected result
            "{'newArray':[1, 2, 3], 'int':1}"
          },
          {
            // source
            "{'array':[1, 2, 3],'int':2}",
            // target
            "{'int':1, 'newArray':[4, 5, 6]}",
            // mapping
            createMapping("$.array", "$.newArray"),
            // expected result
            "{'newArray':[1, 2, 3], 'int':1}"
          },
          {
            // source
            "{'array':[1, 2, 3],'int':2}",
            // target
            "{'int':1, 'newArray':{'attr':'value'}}",
            // mapping
            createMapping("$.array", "$.newArray"),
            // expected result
            "{'newArray':[1, 2, 3], 'int':1}"
          },
          {
            // source
            "{'array':[1, 2, 3],'int':2}",
            // target
            "{'int':1, 'newArray':'value'}",
            // mapping
            createMapping("$.array", "$.newArray"),
            // expected result
            "{'newArray':[1, 2, 3], 'int':1}"
          },
          {
            // source
            "{'array':[1, 2, 3],'int':2}",
            // target
            "{'int':1}",
            // mapping
            createMapping("$.array[0]", "$.firstIdxValue"),
            // expected result
            "{'firstIdxValue':1, 'int':1}"
          },
          {
            // source
            "{'array':[1, 2, 3],'int':2}",
            // target
            "{'int':1}",
            // mapping
            createMapping("$.array[1]", "$.array[0]"),
            // expected result
            "{'array':[2],'int':1}"
          },
          {
            // source
            "{'array':[1, 2, 3], 'int':2}",
            // target
            "{'array':[1, 2, 3], 'int':1}",
            // mapping
            createMapping("$.array[1]", "$.array[0]"),
            // expected result
            "{'array':[2, 2, 3],'int':1}"
          },
          {
            // source
            "{'array':[1, 2, 3],'int':2}",
            // target
            "{'array':[5, 3, 8], 'int':1}",
            // mapping
            createMappings()
                .mapping("$.array[2]", "$.array[0]")
                .mapping("$.array[1]", "$.array[1]")
                .mapping("$.array[0]", "$.array[2]")
                .build(),
            // expected result
            "{'array':[3, 2, 1], 'int':1}"
          },
          {
            // source
            "{'array':[1, 2, 3],'int':2}",
            // target
            "{'int':1}",
            // mapping
            createMapping("$.array[1]", "$.array[0].test"),
            // expected result
            "{'array':[{'test':2}],'int':1}"
          },
          {
            // source
            "{'array':[1, 2, 3],'int':2}",
            // target
            "{'array':[5, 3, 8], 'int':1}",
            // mapping
            createMapping("$.array[1]", "$.array[0].test"),
            // expected result
            "{'array':[{'test':2},3,8],'int':1}"
          },
          {
            // source
            "{'array':[{'test':'value'}, 2, 3],'int':2}",
            // target
            "{'int':1}",
            // mapping
            createMapping("$.array[0].test", "$.testValue"),
            // expected result
            "{'testValue':'value','int':1}"
          },
          {
            // source
            "{'obj':{'test':'value'},'foo':'bar','array':[{'test':'value'}, 2, 3],'int':2}",
            // target
            "{'int':1}",
            // mapping
            createMappings().mapping("$.foo", "$.newFoo").mapping("$.obj", "$.newObj").build(),
            // expected result
            "{'newFoo':'bar', 'newObj':{'test':'value'},'int':1}"
          },
          {
            // source
            "{'obj':{'test':'value'},'foo':'bar','array':[{'test':'value'}, 2, 3],'int':2}",
            // target
            "{'newFoo':'baz','int':1}",
            // mapping
            createMappings().mapping("$.foo", "$.newFoo").mapping("$.obj", "$.newObj").build(),
            // expected result
            "{'newFoo':'bar', 'newObj':{'test':'value'},'int':1}"
          },
          {
            // source
            "{'obj':{'test':'value'},'foo':'bar','array':[{'test':'value'}, 2, 3],'int':2}",
            // target
            "{'int':1}",
            // mapping
            createMappings()
                .mapping("$.foo", "$.newDepth.newFoo")
                .mapping("$.obj", "$.newDepth.newObj")
                .build(),
            // expected result
            "{'newDepth':{'newFoo':'bar', 'newObj':{'test':'value'}},'int':1}"
          },
          {
            // source
            "{'obj':{'test':'value'},'foo':'bar','array':[{'test':'value'}, 2, 3],'int':2}",
            // target
            "{'newFoo':'baz','int':1}",
            // mapping
            createMappings()
                .mapping("$.foo", "$.newDepth.newFoo")
                .mapping("$.obj", "$.newDepth.newObj")
                .build(),
            // expected result
            "{'newDepth':{'newFoo':'bar', 'newObj':{'test':'value'}},'newFoo':'baz','int':1}"
          },
          {
            // source
            "{'obj':{'test':'value'},'foo':'bar','array':[{'test':'value'}, 2, 3],'int':2}",
            // target
            "{'newDepth':'baz','int':1}",
            // mapping
            createMappings()
                .mapping("$.foo", "$.newDepth.newFoo")
                .mapping("$.obj", "$.newDepth.newObj")
                .build(),
            // expected result
            "{'newDepth':{'newFoo':'bar', 'newObj':{'test':'value'}},'int':1}"
          },
          {
            // source
            "{'obj':{'test':'value'},'foo':'bar','array':[{'test':'value'}, 2, 3],'int':2}",
            // target
            "{'newDepth':{'newFow':'baz'},'int':1}",
            // mapping
            createMappings()
                .mapping("$.foo", "$.newDepth.newFoo")
                .mapping("$.obj", "$.newDepth.newObj")
                .build(),
            // expected result
            "{'newDepth':{'newFow':'baz', 'newFoo':'bar', 'newObj':{'test':'value'}},'int':1}"
          },
          {
            // source
            "{'obj':{'test':'value'},'foo':'bar','array':[{'test':'value'}, 2, 3],'int':2}",
            // target
            "{'int':1}",
            // mapping
            createMappings().mapping("$.foo", "$.newObj").mapping("$.obj", "$.newObj").build(),
            // expected result
            "{'newObj':{'test':'value'},'int':1}"
          },
          {
            // source
            "{'obj':{'test':'value'},'foo':'bar','array':[{'test':'value'}, 2, 3],'int':2}",
            // target
            "{'int':1}",
            // mapping
            createMapping("$.obj", "$"),
            // expected result
            "{'test':'value'}"
          },
          {
            // source
            "{'obj':{'test':'value'},'foo':'bar','array':[{'test':'value'}, 2, 3],'int':2}",
            // target
            "{'int':1}",
            // mapping
            createMapping("$", "$.newObj"),
            // expected result
            "{'newObj':{'obj':{'test':'value'},'foo':'bar','array':[{'test':'value'}, 2, 3],'int':2},'int':1}"
          },
          {
            // source
            "{'value':1,'int':2}",
            // target
            "{'obj':{'test':'value'},'anotherObj':{'test':'anotherValue'},'int':1}",
            // mapping
            createMapping("$.value", "$.obj.test"),
            // expected result
            "{'obj':{'test':1},'anotherObj':{'test':'anotherValue'},'int':1}"
          },
          {
            // source
            "{'value':1,'int':2}",
            // target
            "{'obj':{'test':'value'},'int':1}",
            // mapping
            createMapping("$.value", "$.obj.newFoo"),
            // expected result
            "{'obj':{'test':'value','newFoo':1},'int':1}"
          },
          // 37. Test zeebe-io/zeebe#297
          //                                            {
          //                                                    // source
          //
          // "{'foo':'bar','int':1,'obj':{'test':'ok'},'array':[1,2,3]}",
          //                                                // target
          //                                                "{}",
          //                                                    // mapping
          //                                                    createMappings()
          //                                                            .mapping("$", "$.foo")
          //                                                            .mapping("$.obj",
          // "$.foo.int")
          //                                                            .build(),
          //                                                    // expected result
          //
          // "{'foo':{'foo':'bar','int':{'test':'ok'},'obj':{'test':'ok'},'array':[1,2,3]}}"
          //
          //                                            },
          // 38.Test
          {
            // source
            "{'array':[[1,2],3,4], 'int':2}",
            // target
            "{'int':1}",
            // mapping
            createMapping("$.array[0]", "$.newArray"),
            // expected result
            "{'newArray':[1,2],'int':1}"
          },
          // 39.Test
          {
            // source
            "{'array':[1,3,4], 'int':2}",
            // target
            "{'array':[[1,2],3,4],'int':1}",
            // mapping
            createMapping("$.array[0]", "$.array[0]"),
            // expected result
            "{'array':[1,3,4],'int':1}"
          },
          // 40.Test
          {
            // source
            "{'array':[1,3,4], 'int':2}",
            // target
            "{'array':[[1,2],3,4],'int':1}",
            // mapping
            createMapping("$.array[0]", "$.array[1]"),
            // expected result
            "{'array':[[1,2],1, 4],'int':1}"
          },
          // 41.Test
          {
            // source
            "{'array':[[1,2],3,4], 'int':2}",
            // target
            "{'array':[1,3,4],'int':1}",
            // mapping
            createMapping("$.array[0]", "$.array[1]"),
            // expected result
            "{'array':[1,[1,2], 4],'int':1}"
          },
          // 42.Test
          {
            // source
            "{'a':{'bb':{'value':'x'}}, 'ab':{'b':{'value':'y'}}}}",
            // target
            "{'a':{'bb':{'value':'x'}}, 'ab':{'b':{'value':'y'}}}}",
            // mapping
            createMapping("$.ab.b", "$.a.bb"),
            // expected result
            "{'a':{'bb':{'value':'y'}}, 'ab':{'b':{'value':'y'}}}}"
          },
          // 43.Test
          {
            // source
            "{'name':'Willy Wonder'}",
            // target
            new String(
                Files.readAllBytes(
                    Paths.get(
                        MappingExtractParameterizedTest.class
                            .getResource("largeJsonDocument.json")
                            .toURI()))),
            // mapping
            createMapping("$.name", "$.fourth.friends[2].name"),
            // expected result
            new String(
                Files.readAllBytes(
                    Paths.get(
                        MappingExtractParameterizedTest.class
                            .getResource("largeChangedJsonDocument.json")
                            .toURI())))
          },
          // 44.Test
          {
            // source
            "{'array':[[1,2],3,4], 'int':2}",
            // target
            "{'arr':["
                + "{'obj':{'value':'x',"
                + " 'otherArr':[{'test':'hallo'}, {'obj':{'arr':[0, 1]}} ]}"
                + "}, {'otherValue':1}],"
                + " 'ab':{'b':{'value':'y'}}}",
            // mapping
            createMapping("$.array[0]", "$.arr[0].obj.otherArr[1].obj.arr[0]"),
            // expected result
            "{'arr':["
                + "{'obj':{'value':'x',"
                + " 'otherArr':[{'test':'hallo'}, {'obj':{'arr':[[1, 2], 1]}} ]}"
                + "}, {'otherValue':1}],"
                + " 'ab':{'b':{'value':'y'}}}"
          },
          // 45.Test
          {
            // source
            "{'array':[[1,2],3,4], 'int':2}",
            // target
            "{'arr':["
                + "{'obj':{'value':'x',"
                + " 'otherArr':[{'test':'hallo'}, {'obj':{'arr':[0, 1]}} ]}"
                + "}, {'otherValue':1}],"
                + " 'ab':{'b':{'value':'y'}}}",
            // mapping
            createMapping("$.array[0]", "$.arr[0].obj.otherArr[1].obj"),
            // expected result
            "{'arr':["
                + "{'obj':{'value':'x',"
                + " 'otherArr':[{'test':'hallo'}, {'obj':[1,2]}]}"
                + "}, {'otherValue':1}],"
                + " 'ab':{'b':{'value':'y'}}}"
          },
          // 46.Test
          {
            // source
            "{'arr':["
                + "{'obj':{'value':'x',"
                + " 'otherArr':[{'test':'hallo'}, {'obj':{'arr':[0, 1]}} ]}"
                + "}, {'otherValue':1}],"
                + " 'ab':{'b':{'value':'y'}}}",
            // target
            "{'array':[[1,2],3,4], 'int':2}",
            // mapping
            createMapping("$.arr[0].obj.otherArr[0]", "$.arr[0].obj.otherArr[1].obj"),
            // expected result
            "{'array':[[1,2],3,4],'arr':[{'obj':{'otherArr':[{'obj':{'test':'hallo'}}]}}], 'int':2}"
          },
          {
            // source
            "{}",
            // target
            "null",
            // mapping
            createMapping("$", "$"),
            // expected result
            "{}"
          },
          {
            // source
            "{}",
            // target
            "null",
            // mapping
            null,
            // expected result
            "{}"
          },
          {
            // source
            "{'foo':'bar'}",
            // target
            "{'obj':{'0':{'test':1}}}",
            // mapping
            createMapping("$.foo", "$.obj.0"),
            // expected result
            "{'obj':{'0':'bar'}}"
          },
          {
            // source
            "{'foo':'bar'}",
            // target
            "{'obj':{'0':{'test':1}}}",
            // mapping
            createMapping("$.foo", "$[obj][0]"),
            // expected result
            "{'obj':{'0':'bar'}}"
          },
          {
            // source
            "{'foo':'bar'}",
            // target
            "{'arr':[0, 1, 2], 'obj':{'0':{'test':1}}}",
            // mapping
            createMapping("$.foo", "$.arr.0s"),
            // expected result
            "{'arr':{'0':0, '1':1, '2':2, '0s':'bar'}, 'obj':{'0':{'test':1}}}"
          },
          {
            // source
            "{'foo':'bar'}",
            // target
            "{'arr':[0, 1, 2], 'obj':{'0':{'test':1}}}",
            // mapping
            createMapping("$.foo", "$[arr][0s]"),
            // expected result
            "{'arr':{'0':0, '1':1, '2':2, '0s':'bar'}, 'obj':{'0':{'test':1}}}"
          },
          {
            // source
            "{'foo':'bar'}",
            // target
            "{'arr':[0, 1, 2], 'obj':{'0':{'test':1}}}",
            // mapping
            createMapping("$.foo", "$.arr.0"),
            // expected result
            "{'arr':['bar', 1, 2], 'obj':{'0':{'test':1}}}"
          },
          {
            // source
            "{'foo':'bar'}",
            // target
            "{'obj':{'0':{'test':1}}}",
            // mapping
            createMapping("$.foo", "$.obj.1"),
            // expected result
            "{'obj':{'0':{'test':1},'1':'bar'}}"
          },
          {
            // source
            "{'foo':'bar'}",
            // target
            "{'obj':{'0':{'test':1}}}",
            // mapping
            createMapping("$.foo", "$[obj][1]"),
            // expected result
            "{'obj':{'0':{'test':1},'1':'bar'}}"
          },
          {
            // source
            "{'foo':'bar'}",
            // target
            "{'obj':{'0':{'test':1}}}",
            // mapping
            createMapping("$.foo", "$['obj']['1']"),
            // expected result
            "{'obj':{'0':{'test':1},'1':'bar'}}"
          },
          {
            // source
            "{'foo':'bar'}",
            // target
            "{'obj':{'0':{'test':1}}}",
            // mapping
            createMapping("$.foo", "$['obj.1']"),
            // expected result
            "{'obj':{'0':{'test':1}},'obj.1':'bar'}"
          },
          {
            // source
            "{'in':1}",
            // target
            "{'foo.bar':2, 'foo': {'bar': 3}}",
            // mapping
            createMapping("$.in", "$['foo.bar']"),
            // expected result
            "{'foo.bar':1, 'foo': {'bar': 3}}"
          },
          {
            // source
            "{'in':1}",
            // target
            "{'foo.bar':2, 'foo': {'bar': 3}}",
            // mapping
            createMapping("$.in", "$.foo.bar"),
            // expected result
            "{'foo.bar':2, 'foo': {'bar': 1}}"
          },
          {
            // source
            "{'in':1}",
            // target
            "{'array[bar]':2, 'array': {'bar': 3}}",
            // mapping
            createMapping("$.in", "$array.bar"),
            // expected result
            "{'array[bar]':2, 'array': {'bar': 1}}"
          },
          {
            // source
            "{'in':1}",
            // target
            "{'array[bar]':2, 'array': {'bar': 3}}",
            // mapping
            createMapping("$.in", "$['array']['bar']"),
            // expected result
            "{'array[bar]':2, 'array': {'bar': 1}}"
          },
          {
            // source
            "{'in':1}",
            // target
            "{'array[bar]':[0, 1, 2], 'array': {'bar': 3}}",
            // mapping
            createMapping("$.in", "$['array[bar]']"),
            // expected result
            "{'array[bar]':1, 'array': {'bar': 3}}"
          },
          {
            // source
            "{'in':1}",
            // target
            "{'array[bar]':[0, 1, 2], 'array': {'bar': 3}}",
            // mapping
            createMapping("$.in", "$['array[bar]'][0]"),
            // expected result
            "{'array[bar]':[1, 1, 2], 'array': {'bar': 3}}"
          },
          {
            // source
            "{'in':1}",
            // target
            "{'array[bar]':[0, 1, 2], 'array': {'bar': 3}}",
            // mapping
            createMapping("$.in", "$['array[bar][0]']"),
            // expected result
            "{'array[bar]':[0, 1, 2], 'array': {'bar': 3}, 'array[bar][0]':1}"
          },
        });
  }

  @Parameter public String sourcePayload;

  @Parameter(1)
  public String targetPayload;

  @Parameter(2)
  public Mapping[] mappings;

  @Parameter(3)
  public String expectedPayload;

  private MappingProcessor processor = new MappingProcessor(1024);

  @Test
  public void shouldExtractNothingFromEmptyObject() throws Throwable {
    // given payload
    final byte[] sourceBytes =
        MSGPACK_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree(sourcePayload));
    final DirectBuffer sourceDocument = new UnsafeBuffer(sourceBytes);

    final byte[] targetBytes =
        MSGPACK_MAPPER.writeValueAsBytes(JSON_MAPPER.readTree(targetPayload));
    final DirectBuffer targetDocument = new UnsafeBuffer(targetBytes);

    // when
    final int resultLength = processor.merge(sourceDocument, targetDocument, mappings);

    final MutableDirectBuffer resultBuffer = processor.getResultBuffer();
    final byte result[] = new byte[resultLength];
    resultBuffer.getBytes(0, result, 0, resultLength);

    // then result is expected as
    assertThat(MSGPACK_MAPPER.readTree(result)).isEqualTo(JSON_MAPPER.readTree(expectedPayload));
  }
}
