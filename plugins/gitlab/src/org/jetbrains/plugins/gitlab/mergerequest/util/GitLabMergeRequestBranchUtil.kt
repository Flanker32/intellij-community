// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.util

import git4idea.remote.hosting.GitRemoteBranchesUtil
import org.jetbrains.plugins.gitlab.mergerequest.data.GitLabMergeRequestFullDetails
import org.jetbrains.plugins.gitlab.mergerequest.data.getRemoteDescriptor
import org.jetbrains.plugins.gitlab.util.GitLabProjectMapping

object GitLabMergeRequestBranchUtil {
  suspend fun fetchAndCheckoutBranch(mapping: GitLabProjectMapping, details: GitLabMergeRequestFullDetails) {
    val remoteDescriptor = details.getRemoteDescriptor(mapping.repository.serverPath) ?: return
    val localPrefix = if (details.sourceProject?.fullPath != details.targetProject.fullPath) {
      "fork/${remoteDescriptor.name}"
    }
    else {
      null
    }
    GitRemoteBranchesUtil.fetchAndCheckoutRemoteBranch(mapping.gitRepository, remoteDescriptor, details.sourceBranch, localPrefix)
  }
}