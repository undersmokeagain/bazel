// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.analysis.platform;

import static com.google.common.collect.ImmutableListMultimap.flatteningToImmutableListMultimap;
import static com.google.common.collect.ImmutableListMultimap.toImmutableListMultimap;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Streams;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.packages.NativeProvider;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec.VisibleForSerialization;
import com.google.devtools.build.lib.skylarkbuildapi.platform.PlatformInfoApi;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.util.Fingerprint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Provider for a platform, which is a group of constraints and values. */
@Immutable
@AutoCodec
public class PlatformInfo extends NativeInfo
    implements PlatformInfoApi<ConstraintSettingInfo, ConstraintValueInfo> {
  /** Name used in Skylark for accessing this provider. */
  public static final String SKYLARK_NAME = "PlatformInfo";

  /** Skylark constructor and identifier for this provider. */
  public static final NativeProvider<PlatformInfo> PROVIDER =
      new NativeProvider<PlatformInfo>(PlatformInfo.class, SKYLARK_NAME) {};

  private final Label label;
  private final ConstraintCollection constraints;
  private final String remoteExecutionProperties;

  @AutoCodec.Instantiator
  @VisibleForSerialization
  PlatformInfo(
      Label label,
      ConstraintCollection constraints,
      String remoteExecutionProperties,
      Location location) {
    super(PROVIDER, location);

    this.label = label;
    this.constraints = constraints;
    this.remoteExecutionProperties = remoteExecutionProperties;
  }

  @Override
  public Label label() {
    return label;
  }

  @Override
  public ConstraintCollection constraints() {
    return constraints;
  }

  @Override
  public String remoteExecutionProperties() {
    return remoteExecutionProperties;
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.format("PlatformInfo(%s, constraints=%s)", label.toString(), constraints.toString());
  }

  /** Returns a new {@link Builder} for creating a fresh {@link PlatformInfo} instance. */
  public static Builder builder() {
    return new Builder();
  }

  /** Add this platform to the given fingerprint. */
  public void addTo(Fingerprint fp) {
    fp.addString(label.toString());
    fp.addNullableString(remoteExecutionProperties);
    constraints.addToFingerprint(fp);
  }

  /** Builder class to facilitate creating valid {@link PlatformInfo} instances. */
  public static class Builder {
    private Label label;
    private final List<ConstraintValueInfo> constraints = new ArrayList<>();
    private String remoteExecutionProperties = "";
    private Location location = Location.BUILTIN;

    /**
     * Sets the {@link Label} for this {@link PlatformInfo}.
     *
     * @param label the label identifying this platform
     * @return the {@link Builder} instance for method chaining
     */
    public Builder setLabel(Label label) {
      this.label = label;
      return this;
    }

    /**
     * Adds the given constraint value to the constraints that define this {@link PlatformInfo}.
     *
     * @param constraint the constraint to add
     * @return the {@link Builder} instance for method chaining
     */
    public Builder addConstraint(ConstraintValueInfo constraint) {
      this.constraints.add(constraint);
      return this;
    }

    /**
     * Adds the given constraint values to the constraints that define this {@link PlatformInfo}.
     *
     * @param constraints the constraints to add
     * @return the {@link Builder} instance for method chaining
     */
    public Builder addConstraints(Iterable<ConstraintValueInfo> constraints) {
      for (ConstraintValueInfo constraint : constraints) {
        this.addConstraint(constraint);
      }

      return this;
    }

    /** Returns the data being sent to a potential remote executor. */
    public String getRemoteExecutionProperties() {
      return remoteExecutionProperties;
    }

    /**
     * Sets the data being sent to a potential remote executor.
     *
     * @param properties the properties to be added
     * @return the {@link Builder} instance for method chaining
     */
    public Builder setRemoteExecutionProperties(String properties) {
      this.remoteExecutionProperties = properties;
      return this;
    }

    /**
     * Sets the {@link Location} where this {@link PlatformInfo} was created.
     *
     * @param location the location where the instance was created
     * @return the {@link Builder} instance for method chaining
     */
    public Builder setLocation(Location location) {
      this.location = location;
      return this;
    }

    /**
     * Returns the new {@link PlatformInfo} instance.
     *
     * @throws DuplicateConstraintException if more than one constraint value exists for the same
     *     constraint setting
     */
    public PlatformInfo build() throws DuplicateConstraintException {
      ImmutableList<ConstraintValueInfo> validatedConstraints = validateConstraints(constraints);
      ConstraintCollection platformConstraints = new ConstraintCollection(validatedConstraints);
      return new PlatformInfo(label, platformConstraints, remoteExecutionProperties, location);
    }

    public static ImmutableList<ConstraintValueInfo> validateConstraints(
        Iterable<ConstraintValueInfo> constraintValues) throws DuplicateConstraintException {

      // Collect the constraints by the settings.
      ImmutableListMultimap<ConstraintSettingInfo, ConstraintValueInfo> constraints =
          Streams.stream(constraintValues)
              .collect(
                  toImmutableListMultimap(ConstraintValueInfo::constraint, Functions.identity()));

      // Find settings with duplicate values.
      ImmutableListMultimap<ConstraintSettingInfo, ConstraintValueInfo> duplicates =
          constraints
              .asMap()
              .entrySet()
              .stream()
              .filter(e -> e.getValue().size() > 1)
              .collect(
                  flatteningToImmutableListMultimap(Map.Entry::getKey, e -> e.getValue().stream()));

      if (!duplicates.isEmpty()) {
        throw new DuplicateConstraintException(duplicates);
      }
      return ImmutableList.copyOf(constraints.values());
    }
  }

  /**
   * Exception class used when more than one {@link ConstraintValueInfo} for the same {@link
   * ConstraintSettingInfo} is added to a {@link Builder}.
   */
  public static class DuplicateConstraintException extends Exception {
    private final ImmutableListMultimap<ConstraintSettingInfo, ConstraintValueInfo>
        duplicateConstraints;

    DuplicateConstraintException(
        ListMultimap<ConstraintSettingInfo, ConstraintValueInfo> duplicateConstraints) {
      super(formatError(duplicateConstraints));
      this.duplicateConstraints = ImmutableListMultimap.copyOf(duplicateConstraints);
    }

    public ImmutableListMultimap<ConstraintSettingInfo, ConstraintValueInfo>
        duplicateConstraints() {
      return duplicateConstraints;
    }

    public static String formatError(
        ListMultimap<ConstraintSettingInfo, ConstraintValueInfo> duplicateConstraints) {
      return String.format(
          "Duplicate constraint_values detected: %s",
          duplicateConstraints.asMap().entrySet().stream()
              .map(DuplicateConstraintException::describeSingleDuplicateConstraintSetting)
              .collect(joining(", ")));
    }

    private static String describeSingleDuplicateConstraintSetting(
        Map.Entry<ConstraintSettingInfo, Collection<ConstraintValueInfo>> duplicate) {
      return String.format(
          "constraint_setting %s has [%s]",
          duplicate.getKey().label(),
          duplicate
              .getValue()
              .stream()
              .map(ConstraintValueInfo::label)
              .map(Label::toString)
              .collect(joining(", ")));
    }
  }
}
