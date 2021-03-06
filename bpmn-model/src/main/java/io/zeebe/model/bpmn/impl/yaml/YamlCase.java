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
package io.zeebe.model.bpmn.impl.yaml;

import com.fasterxml.jackson.annotation.JsonProperty;

public class YamlCase {
  @JsonProperty("case")
  private String condition = "";

  @JsonProperty("goto")
  private String next = "";

  @JsonProperty("default")
  private String defaultCase;

  public String getCondition() {
    return condition;
  }

  public void setCondition(String condition) {
    this.condition = condition;
  }

  public String getNext() {
    return next;
  }

  public void setNext(String next) {
    this.next = next;
  }

  public String getDefaultCase() {
    return defaultCase;
  }

  public void setDefaultCase(String defaultCase) {
    this.defaultCase = defaultCase;
  }
}
