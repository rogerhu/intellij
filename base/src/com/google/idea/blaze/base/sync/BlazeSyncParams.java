/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
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
package com.google.idea.blaze.base.sync;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import java.util.Collection;

/** Parameters that control the sync. */
@AutoValue
public abstract class BlazeSyncParams {

  public abstract String title();

  public abstract SyncMode syncMode();

  public abstract BlazeBuildParams blazeBuildParams();

  public abstract boolean backgroundSync();

  public abstract boolean addProjectViewTargets();

  public abstract boolean addWorkingSet();

  public abstract ImmutableSet<TargetExpression> targetExpressions();

  public abstract Builder toBuilder();

  public static Builder builder() {
    return new AutoValue_BlazeSyncParams.Builder()
        .setBackgroundSync(false)
        .setAddProjectViewTargets(false)
        .setAddWorkingSet(false);
  }

  /** Builder for {@link BlazeSyncParams}. */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder setTitle(String value);

    public abstract Builder setSyncMode(SyncMode value);

    public abstract Builder setBlazeBuildParams(BlazeBuildParams value);

    public abstract Builder setBackgroundSync(boolean value);

    public abstract Builder setAddProjectViewTargets(boolean value);

    public abstract Builder setAddWorkingSet(boolean value);

    abstract ImmutableSet.Builder<TargetExpression> targetExpressionsBuilder();

    public Builder addTargetExpression(TargetExpression targetExpression) {
      targetExpressionsBuilder().add(targetExpression);
      return this;
    }

    public Builder addTargetExpressions(Collection<? extends TargetExpression> targets) {
      targetExpressionsBuilder().addAll(targets);
      return this;
    }

    public abstract BlazeSyncParams build();
  }

  /** Combine {@link BlazeSyncParams} from multiple build phases. */
  public static BlazeSyncParams combine(BlazeSyncParams first, BlazeSyncParams second) {
    BlazeSyncParams base =
        first.syncMode().ordinal() > second.syncMode().ordinal() ? first : second;
    return builder()
        .setTitle(base.title())
        .setSyncMode(base.syncMode())
        .setBlazeBuildParams(base.blazeBuildParams())
        .setBackgroundSync(first.backgroundSync() && second.backgroundSync())
        .addTargetExpressions(first.targetExpressions())
        .addTargetExpressions(second.targetExpressions())
        .setAddProjectViewTargets(first.addProjectViewTargets() || second.addProjectViewTargets())
        .setAddWorkingSet(first.addWorkingSet() || second.addWorkingSet())
        .build();
  }

  @Override
  public final String toString() {
    return String.format("%s (%s)", title(), syncMode().name().toLowerCase());
  }
}
