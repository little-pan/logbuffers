/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.deephacks.logbuffers;

import org.deephacks.vals.DirectBuffer;
import org.deephacks.vals.Encodable;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A stream of logs.
 */
public final class Logs {

  private Stream<Log> logs;

  Logs(Stream<Log> logs) {
    this.logs = logs;
  }

  public Stream<Log> stream() {
    return logs;
  }

  public <T extends Encodable> Stream<T> stream(Function<DirectBuffer, T> parseFrom) {
    return logs.map(l -> l.getVal(parseFrom));
  }

  public LinkedList<Log> toLinkedList() {
    return logs.collect(Collectors.toCollection(LinkedList::new));
  }

  public ArrayList<Log> toArrayList() {
    return logs.collect(Collectors.toCollection(ArrayList::new));
  }
}
