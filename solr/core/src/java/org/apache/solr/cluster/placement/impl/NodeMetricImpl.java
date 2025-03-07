/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cluster.placement.impl;

import java.util.Objects;
import java.util.function.Function;
import org.apache.solr.client.solrj.impl.NodeValueFetcher;
import org.apache.solr.cluster.placement.NodeMetric;

/**
 * Node metric identifier, corresponding to a node-level metric registry and the internal metric
 * name.
 */
public class NodeMetricImpl<T> extends MetricImpl<T> implements NodeMetric<T> {

  /** Total disk space in GB. */
  public static final NodeMetricImpl<Double> TOTAL_DISK_GB =
      new NodeMetricImpl<>(
          "totalDisk", Registry.SOLR_NODE, "CONTAINER.fs.totalSpace", BYTES_TO_GB_CONVERTER);

  /** Free (usable) disk space in GB. */
  public static final NodeMetricImpl<Double> FREE_DISK_GB =
      new NodeMetricImpl<>(
          "freeDisk", Registry.SOLR_NODE, "CONTAINER.fs.usableSpace", BYTES_TO_GB_CONVERTER);

  /** Number of all cores. */
  public static final NodeMetricImpl<Integer> NUM_CORES =
      new NodeMetricImpl<>(NodeValueFetcher.CORES);

  public static final NodeMetricImpl<Double> HEAP_USAGE =
      new NodeMetricImpl<>(NodeValueFetcher.Tags.HEAPUSAGE.tagName);

  /** System load average. */
  public static final NodeMetricImpl<Double> SYSLOAD_AVG =
      new NodeMetricImpl<>(
          NodeValueFetcher.Tags.SYSLOADAVG.tagName,
          Registry.SOLR_JVM,
          NodeValueFetcher.Tags.SYSLOADAVG.prefix);

  /** Number of available processors. */
  public static final NodeMetricImpl<Integer> AVAILABLE_PROCESSORS =
      new NodeMetricImpl<>("availableProcessors", Registry.SOLR_JVM, "os.availableProcessors");

  private final Registry registry;

  public NodeMetricImpl(String name, Registry registry, String internalName) {
    this(name, registry, internalName, null);
  }

  public NodeMetricImpl(
      String name, Registry registry, String internalName, Function<Object, T> converter) {
    super(name, internalName, converter);
    Objects.requireNonNull(registry);
    this.registry = registry;
  }

  public NodeMetricImpl(String key) {
    this(key, null);
  }

  public NodeMetricImpl(String key, Function<Object, T> converter) {
    super(key, key, converter);
    this.registry = Registry.UNSPECIFIED;
  }

  @Override
  public Registry getRegistry() {
    return registry;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof NodeMetricImpl<?> that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return registry == that.registry;
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), registry);
  }

  @Override
  public String toString() {
    if (registry != null) {
      return "NodeMetricImpl{"
          + "name='"
          + name
          + '\''
          + ", internalName='"
          + internalName
          + '\''
          + ", converter="
          + converter
          + ", registry="
          + registry
          + '}';
    } else {
      return "NodeMetricImpl{key=" + internalName + "}";
    }
  }
}
