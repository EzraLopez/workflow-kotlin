package com.squareup.workflow1.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.Lifecycle
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner

/**
 * The [SavedStateRegistry] for this context, or null if one can't be found.
 */
@WorkflowUiExperimentalApi
public tailrec fun Context.savedStateRegistryOrNull(): SavedStateRegistry? = when (this) {
  is SavedStateRegistryOwner -> this.savedStateRegistry
  else -> (this as? ContextWrapper)?.baseContext?.savedStateRegistryOrNull()
}
