/*
 * Morgan Stanley makes this available to you under the Apache License, Version 2.0 (the "License").
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 * See the NOTICE file distributed with this work for additional information regarding copyright ownership.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ms.silverking.cloud.dht.daemon.storage.protocol;

import com.ms.silverking.cloud.dht.common.OpResult;

/**
 * For a given storage message entry, the state of a StorageOperation.
 * Tracks the state at each replica.
 */
abstract class StorageEntryState {
  private static final int relTimeoutMillis = 100; // TODO (OPTIMUS-0000): make configurable
  static final int minRelTimeoutMillis = relTimeoutMillis;

  StorageEntryState() {
  }

  abstract OpResult getCurOpResult();
}
